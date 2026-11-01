package io.freemytv;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Headlines for the screensaver: Telegram channels (their public web preview, t.me/s/NAME) and news sites' RSS feeds (no keys).
 * A channel post counts as important when its views are well above what the channel's posts usually get (the median), corrected
 * for how young the post is. Each source keeps its last reading saved ("millis TAB rating TAB title"), so the screensaver has
 * something at once and when the network is down; the merge, the filter and the duplicates are worked out when it is shown.
 */
final class NewsFeed {
    private NewsFeed() {}

    static final String[] NAMES = {"Топор", "Ateo Breaking", "РИА Новости", "Лента.ру", "Интерфакс"};
    static final String[] TAGS = {"Топор", "Ateo", "РИА", "Лента", "Интерфакс"};
    private static final String[] URLS = {"https://t.me/s/topor", "https://t.me/s/ateobreaking", "https://ria.ru/export/rss2/archive/index.xml",
            "https://lenta.ru/rss/top7", "https://www.interfax.ru/rss.asp"};
    /** Telegram sources are rated by views; the others are not (a news site's own front page counts as already picked). */
    private static final int FIRST_RSS = 2;
    private static final float CURATED = 99f, UNRATED = -1f;
    private static final float[] THRESHOLDS = {0f, 1.15f, 1.3f, 1.7f};   // Prefs.newsImportant(): off, more, usual, only the main ones
    private static final int SHOWN = 18;
    /** A message that long fills three lines of the card: longer ones are not shown. */
    private static final int MAX_CHARS = 135;
    private static final long FRESH_MS = 10 * 60_000L, MAX_AGE_MS = 24 * 3_600_000L;
    private static final Pattern ITEM = Pattern.compile("<item>(.*?)</item>", Pattern.DOTALL);
    private static final Pattern DATE = Pattern.compile("<pubDate>(.*?)</pubDate>", Pattern.DOTALL);
    private static final Pattern TITLE = Pattern.compile("<title>(?:<!\\[CDATA\\[)?(.*?)(?:\\]\\]>)?</title>", Pattern.DOTALL);
    private static final Pattern TEXT = Pattern.compile("tgme_widget_message_text[^\"]*\"[^>]*>(.*?)</div>", Pattern.DOTALL);
    private static final Pattern VIEWS = Pattern.compile("tgme_widget_message_views\">([^<]+)<");
    private static final Pattern TIME = Pattern.compile("<time datetime=\"([^\"]+)\"");
    private static final Pattern EMOJI = Pattern.compile("<i class=\"emoji\".*?</i>", Pattern.DOTALL);
    private static final Pattern BREAK = Pattern.compile("<br\\s*/?>");
    private static final Pattern TAG = Pattern.compile("<[^>]*>");
    private static final Pattern URL = Pattern.compile("(?:https?://|www\\.|t\\.me/)\\S+");
    private static final Pattern HASHTAG = Pattern.compile("[#@][\\p{L}\\p{N}_]+");
    private static final Pattern ADS = Pattern.compile("(?i)erid|реклам|на правах рекламы|промокод");
    private static final Pattern SIGNATURE = Pattern.compile("(?i)подписа|прислать новост|присылайте|ваши новости|наш чат|наш бот|предложить новост");
    private static boolean busy;

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("news", Context.MODE_PRIVATE);
    }

    private static final class Item {
        int src;
        long at;
        float rating;
        String title, tags;
        Set<String> words;
        float boost;   // by the words of the title: war, accidents, money, the state are up; show business and the like are down
    }

    // MARK: - What the screensaver shows

    /** The headlines to show now, newest first, as "HH:mm TAB tag TAB title" (empty when nothing is saved yet). */
    static List<String> saved(Context c) {
        int mask = Prefs.newsMask();
        float threshold = THRESHOLDS[Prefs.newsImportant()];
        long now = System.currentTimeMillis();
        boolean rated = (mask & 3) != 0;
        List<Item> all = new ArrayList<>();
        for (int s = 0; s < NAMES.length; s++) if ((mask & (1 << s)) != 0) all.addAll(load(c, s, now));

        List<Item> keep = new ArrayList<>();
        for (Item it : all) {
            boolean ok = threshold == 0 || it.rating >= threshold;
            if (!ok && threshold > 0 && it.rating == UNRATED) {   // unrated: when there is nothing to rate by, or when an important post says the same
                ok = !rated || it.boost >= 0.5f;
                if (!ok) for (Item o : all) if (o != it && o.src < FIRST_RSS && o.rating >= threshold && similar(it, o)) { ok = true; break; }
            }
            if (ok) keep.add(it);
        }
        if (threshold > 0 && keep.isEmpty()) {   // a very quiet day: the three best, so that the card is not empty
            List<Item> rest = new ArrayList<>();
            for (Item it : all) if (it.rating >= 0 && it.rating < CURATED) rest.add(it);
            Collections.sort(rest, (x, y) -> Float.compare(y.rating, x.rating));
            for (int i = 0; i < rest.size() && i < 3; i++) keep.add(rest.get(i));
        }
        if (threshold > 0) {   // no single channel fills the card
            for (int src = 0; src < FIRST_RSS; src++) {
                List<Item> mine = new ArrayList<>();
                for (Item it : keep) if (it.src == src) mine.add(it);
                Collections.sort(mine, (x, y) -> Float.compare(y.rating, x.rating));
                for (int i = 6; i < mine.size(); i++) keep.remove(mine.get(i));
            }
        }
        // Best-rated first, so that of two near copies the more important one stays; then newest first.
        Collections.sort(keep, (a, b) -> Float.compare(b.rating, a.rating));
        List<Item> unique = new ArrayList<>();
        for (Item it : keep) {
            Item twin = null;
            for (Item u : unique) if (similar(it, u)) { twin = u; break; }
            if (twin == null) unique.add(it);
            else if (!twin.tags.contains(TAGS[it.src])) twin.tags += " · " + TAGS[it.src];
        }
        Collections.sort(unique, (a, b) -> Long.compare(b.at, a.at));
        AppLog.w("news: shown " + unique.size() + " of " + all.size() + " (kept " + keep.size() + ", threshold " + threshold + ")");
        java.text.SimpleDateFormat hm = new java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault());
        List<String> out = new ArrayList<>();
        for (Item it : unique) {
            if (out.size() >= SHOWN) break;
            out.add(hm.format(new java.util.Date(it.at)) + "\t" + it.tags + "\t" + it.title);
        }
        return out;
    }

    /** Short enough for two lines on the card: cut at a comma or a dash when there is one, else at a word. */
    static String fit(String title) {
        final int max = 74;
        if (title.length() <= max) return title;
        int cut = -1;
        for (String sep : new String[] {", ", " — ", " – ", "; ", ": "}) cut = Math.max(cut, title.lastIndexOf(sep, max - 1));
        if (cut >= 32) return title.substring(0, cut);
        cut = title.lastIndexOf(' ', max - 2);
        return title.substring(0, cut > 32 ? cut : max - 2).replaceAll("[\\s,;:\\-—]+$", "") + "…";
    }

    private static final Pattern UP = Pattern.compile("(?i)срочн|молни|взрыв|ракет|удар|атак|погиб|теракт|обстрел|эвакуац|пожар|авари|крушен|землетряс|наводнен|президент|путин|трамп|зеленск|правительств|госдум|санкци|переговор|перемири|мобилиз|ключев|инфляц|нефт|газ |выбор|закон|запрет|арест|задерж|обвинил|отставк|армия|войн|нато|беспилотник|дрон|ядерн|курс доллар|курс евро|цб ");
    private static final Pattern DOWN = Pattern.compile("(?i)звезд|шоу|певиц|певец|актер|актёр|актрис|блогер|развод|свадьб|гороскоп|собак|котик|рецепт|мем |сериал|фильм|клип|инстаграм|футбол|хоккей|матч|гол |чемпионат|скидк|акция|распродаж");

    /** What the words of a title say about its weight: up to +0.7 for serious subjects, -0.5 for light ones. */
    static float boost(String title) {
        float b = 0;
        Matcher up = UP.matcher(title);
        int n = 0;
        while (up.find() && n < 2) n++;
        b += 0.35f * n;
        if (DOWN.matcher(title).find()) b -= 0.5f;
        return b;
    }

    private static boolean similar(Item a, Item b) {
        if (a.words.isEmpty() || b.words.isEmpty()) return false;
        int common = 0;
        for (String w : a.words) if (b.words.contains(w)) common++;
        return common >= 3 && common >= 0.5f * Math.min(a.words.size(), b.words.size());
    }

    private static Set<String> words(String title) {
        Set<String> out = new HashSet<>();
        for (String w : title.toLowerCase(java.util.Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (w.length() >= 4) out.add(w.length() > 5 ? w.substring(0, 5) : w);
        }
        return out;
    }

    private static List<Item> load(Context c, int src, long now) {
        List<Item> out = new ArrayList<>();
        for (String line : sp(c).getString("e" + src, "").split("\\n")) {
            String[] f = line.split("\t", 3);
            if (f.length < 3) continue;
            try {
                Item it = new Item();
                it.src = src;
                it.at = Long.parseLong(f[0]);
                it.rating = Float.parseFloat(f[1]);
                it.title = f[2];
                it.tags = TAGS[src];
                it.words = words(f[2]);
                it.boost = boost(f[2]);
                if (it.rating >= 0 && it.rating < CURATED) it.rating += it.boost;
                if (now - it.at <= MAX_AGE_MS) out.add(it);
            } catch (NumberFormatException ignored) {}
        }
        return out;
    }

    // MARK: - Reading

    /** Reads the chosen sources in the background when their saved headlines are older than ten minutes; `done` runs on that thread. */
    static void refresh(Context c, Runnable done) {
        final Context app = c.getApplicationContext();
        final SharedPreferences p = sp(app);
        final int mask = Prefs.newsMask();
        synchronized (NewsFeed.class) {
            if (busy) return;
            busy = true;
        }
        new Thread(() -> {
            boolean any = false;
            try {
                for (int s = 0; s < NAMES.length; s++) {
                    if ((mask & (1 << s)) == 0 || System.currentTimeMillis() - p.getLong("ct" + s, 0) < FRESH_MS) continue;
                    try {
                        List<String> lines = s < FIRST_RSS ? parseChannel(download(URLS[s]), System.currentTimeMillis())
                                : parseRss(download(URLS[s]), s == 2 ? UNRATED : CURATED);
                        AppLog.w("news: " + NAMES[s] + " " + lines.size());
                        if (!lines.isEmpty()) {
                            p.edit().putString("e" + s, String.join("\n", lines)).putLong("ct" + s, System.currentTimeMillis()).apply();
                            any = true;
                        }
                    } catch (Exception e) {
                        AppLog.w("news: " + NAMES[s] + ": " + e.getMessage());
                    }
                }
                if (any && done != null) done.run();
            } finally {
                synchronized (NewsFeed.class) { busy = false; }
            }
        }, "news-feed").start();
    }

    static String download(String url) throws java.io.IOException {
        java.io.IOException last = null;
        for (int attempt = 0; attempt < 2; attempt++) {   // outside the VPN first (Russian sites), then the default network (through the VPN)
            try {
                HttpURLConnection h = HomeData.open(url, attempt);
                h.setConnectTimeout(8000);
                h.setReadTimeout(10000);
                h.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 Chrome/120 Safari/537.36");
                try {
                    if (h.getResponseCode() != 200) throw new java.io.IOException("HTTP " + h.getResponseCode());
                    try (InputStream in = h.getInputStream()) {
                        ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 17);
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = in.read(buf)) > 0) {
                            out.write(buf, 0, n);
                            if (out.size() > 2 << 20) throw new java.io.IOException("feed too large");
                        }
                        return out.toString(StandardCharsets.UTF_8.name());
                    }
                } finally {
                    h.disconnect();
                }
            } catch (java.io.IOException e) {
                last = e;
            }
        }
        throw last;
    }

    // MARK: - Telegram

    /** The posts of a channel's web preview as "millis TAB rating TAB title"; the rating is views over the usual views. */
    static List<String> parseChannel(String html, long now) {
        List<long[]> meta = new ArrayList<>();   // {time, views, reactions}
        List<String> titles = new ArrayList<>();
        String[] posts = html.split("tgme_widget_message_wrap");
        for (int i = 1; i < posts.length; i++) {
            String post = posts[i];
            Matcher t = TEXT.matcher(post), v = VIEWS.matcher(post), d = TIME.matcher(post);
            if (!t.find() || !v.find() || !d.find()) continue;
            String title = headline(t.group(1));
            if (title == null) continue;
            long at;
            try {
                at = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", java.util.Locale.ENGLISH).parse(d.group(1)).getTime();
            } catch (java.text.ParseException e) {
                continue;
            }
            meta.add(new long[] {at, views(v.group(1)), post.contains("tgme_widget_message_reactions") ? 1 : 0});
            titles.add(title);
        }
        // Views grow with the age of a post, so a post is rated against its neighbours in time: the median of the six posts
        // nearest to it. A young post is not expected to have all its views yet.
        List<String> out = new ArrayList<>();
        for (int i = 0; i < meta.size(); i++) {
            long[] m = meta.get(i);
            if (m[1] <= 0 || now - m[0] > MAX_AGE_MS) continue;
            final long at = m[0];
            List<long[]> near = new ArrayList<>();
            for (int j = 0; j < meta.size(); j++) if (j != i && meta.get(j)[1] > 0) near.add(meta.get(j));
            Collections.sort(near, (x, y) -> Long.compare(Math.abs(x[0] - at), Math.abs(y[0] - at)));
            List<Long> views = new ArrayList<>();
            for (int j = 0; j < Math.min(6, near.size()); j++) views.add(near.get(j)[1]);
            double usual = median(views);
            if (usual <= 0) continue;
            double ageHours = Math.max(0, (now - m[0]) / 3_600_000.0);
            double expected = usual * Math.min(1.0, (ageHours + 0.5) / 4.0);
            double rating = m[1] / expected + (m[2] > 0 ? 0.25 : 0);
            out.add(m[0] + "	" + String.format(java.util.Locale.ROOT, "%.2f", rating) + "	" + titles.get(i));
        }
        return out;
    }

    private static double median(List<Long> values) {
        if (values.isEmpty()) return 0;
        Long[] a = values.toArray(new Long[0]);
        Arrays.sort(a);
        return a.length % 2 == 1 ? a[a.length / 2] : (a[a.length / 2 - 1] + a[a.length / 2]) / 2.0;
    }

    /** "91.4K" is 91400, "1.2M" is 1200000. */
    static long views(String s) {
        s = s.trim().toUpperCase(java.util.Locale.ROOT).replace(",", ".");
        double mul = 1;
        if (s.endsWith("K")) { mul = 1e3; s = s.substring(0, s.length() - 1); }
        else if (s.endsWith("M")) { mul = 1e6; s = s.substring(0, s.length() - 1); }
        try {
            return Math.round(Double.parseDouble(s.trim()) * mul);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** The title of a post: its first line or sentence, cleaned (no tags, links, hashtags or signatures); null for an ad or no text. */
    static String headline(String html) {
        String s = EMOJI.matcher(html).replaceAll("");
        s = BREAK.matcher(s).replaceAll("\n");
        s = unescape(TAG.matcher(s).replaceAll(""));
        if (ADS.matcher(s).find()) return null;
        s = URL.matcher(s).replaceAll("");
        s = HASHTAG.matcher(s).replaceAll("");
        StringBuilder plain = new StringBuilder();
        for (int i = 0; i < s.length(); ) {   // symbols and pictographs: the TV's font cannot draw them
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (cp >= 0x1F000 || cp == 0xFE0F || cp == 0x200D || (cp >= 0x2600 && cp <= 0x27BF) || (cp >= 0x2B00 && cp <= 0x2BFF)) continue;
            plain.appendCodePoint(cp);
        }
        List<String> lines = new ArrayList<>();
        for (String line : plain.toString().split("\n")) {
            line = line.replaceAll("[ \\t\\u00A0]+", " ").trim();
            if (line.isEmpty() || SIGNATURE.matcher(line).find()) continue;
            lines.add(line);
        }
        if (lines.isEmpty()) return null;
        String first = lines.get(0);
        if (first.length() < 25 && lines.size() > 1) first += " " + lines.get(1);   // a lone "Срочно!" says nothing
        first = first.replaceAll("[\\s,;:\\-—]+$", "");
        return first.length() < 15 || first.length() > MAX_CHARS ? null : first;
    }

    // MARK: - RSS

    static List<String> parseRss(String xml, float rating) {
        List<String> out = new ArrayList<>();
        Matcher item = ITEM.matcher(xml);
        while (item.find() && out.size() < 20) {
            Matcher t = TITLE.matcher(item.group(1));
            Matcher d = DATE.matcher(item.group(1));
            if (!t.find() || !d.find()) continue;
            String s = unescape(t.group(1)).replaceAll("\\s+", " ").trim();
            if (s.length() < 15 || s.length() > MAX_CHARS) continue;
            try {
                long at = new java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", java.util.Locale.ENGLISH).parse(d.group(1).trim()).getTime();
                out.add(at + "\t" + rating + "\t" + s);
            } catch (java.text.ParseException ignored) {}
        }
        return out;
    }

    private static String unescape(String s) {
        Matcher m = Pattern.compile("&#(x?)([0-9a-fA-F]+);").matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String rep = " ";
            try {
                rep = new String(Character.toChars(Integer.parseInt(m.group(2), m.group(1).isEmpty() ? 10 : 16)));
            } catch (RuntimeException ignored) {}
            m.appendReplacement(sb, Matcher.quoteReplacement(rep));
        }
        m.appendTail(sb);
        return sb.toString().replace("&quot;", "\"").replace("&apos;", "'").replace("&laquo;", "«").replace("&raquo;", "»")
                .replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&");
    }
}
