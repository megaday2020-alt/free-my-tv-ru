package io.freemytv;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Headlines for the screensaver, from a news site's RSS feed (no key needed). The last ones read stay saved, so the screensaver
 * has something to show at once and when the network is down; a new reading is asked for while the screensaver is on.
 */
final class NewsFeed {
    private NewsFeed() {}

    static final String[] NAMES = {"Лента.ру", "РИА Новости", "Интерфакс"};
    private static final String[] URLS = {"https://lenta.ru/rss/top7", "https://ria.ru/export/rss2/archive/index.xml",
            "https://www.interfax.ru/rss.asp"};
    private static final int KEEP = 12;
    private static final long FRESH_MS = 10 * 60_000L;
    private static final Pattern ITEM = Pattern.compile("<item>(.*?)</item>", Pattern.DOTALL);
    private static final Pattern TITLE = Pattern.compile("<title>(?:<!\\[CDATA\\[)?(.*?)(?:\\]\\]>)?</title>", Pattern.DOTALL);
    private static boolean busy;

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("news", Context.MODE_PRIVATE);
    }

    /** The saved headlines of this source (empty when none yet). */
    static List<String> saved(Context c, int source) {
        List<String> out = new ArrayList<>();
        for (String line : sp(c).getString("n" + source, "").split("\\n")) if (!line.trim().isEmpty()) out.add(line.trim());
        return out;
    }

    /** Reads the feed in the background when the saved headlines are older than ten minutes; `done` runs on the thread afterwards. */
    static void refresh(Context c, int source, Runnable done) {
        final Context app = c.getApplicationContext();
        SharedPreferences p = sp(app);
        synchronized (NewsFeed.class) {
            if (busy || System.currentTimeMillis() - p.getLong("at" + source, 0) < FRESH_MS) return;
            busy = true;
        }
        new Thread(() -> {
            try {
                List<String> titles = parse(download(URLS[Math.max(0, Math.min(source, URLS.length - 1))]));
                if (!titles.isEmpty()) {
                    p.edit().putString("n" + source, String.join("\n", titles)).putLong("at" + source, System.currentTimeMillis()).apply();
                    if (done != null) done.run();
                }
            } catch (Exception e) {
                AppLog.w("news: " + e.getMessage());
            } finally {
                synchronized (NewsFeed.class) { busy = false; }
            }
        }, "news-feed").start();
    }

    private static String download(String url) throws java.io.IOException {
        java.io.IOException last = null;
        for (int attempt = 0; attempt < 2; attempt++) {   // outside the VPN first (these are Russian sites), then the default network
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

    static List<String> parse(String xml) {
        List<String> out = new ArrayList<>();
        Matcher item = ITEM.matcher(xml);
        while (item.find() && out.size() < KEEP) {
            Matcher t = TITLE.matcher(item.group(1));
            if (!t.find()) continue;
            String s = unescape(t.group(1)).replaceAll("\\s+", " ").trim();
            if (s.length() >= 15 && s.length() <= 220) out.add(s);
        }
        return out;
    }

    private static String unescape(String s) {
        return s.replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'").replace("&laquo;", "«").replace("&raquo;", "»")
                .replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&");
    }
}
