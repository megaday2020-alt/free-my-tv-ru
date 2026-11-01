package io.freemytv;

import android.animation.ObjectAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * The screensaver: an information board on a dark background with a slow glow that follows the weather. Cards (each with its
 * own switch in Settings): the clock and date; the weather now, today and the next two days; the latest headlines;
 * the Central Bank's rates; and how the connection is (VPN delay, Wi-Fi). After a while it goes to "economy": only a dim clock
 * on black, nothing animated, and a host that owns the window turns the panel down. Shown by the system when the TV is idle,
 * by the "Screensaver" tile and by the sleep timer. Any key leaves (that is up to the host).
 */
final class InfoView extends FrameLayout implements HomeData.Listener {
    private static final long PAGE_MS = 30_000;
    private static final int HEADLINES = 6;
    private static final int WHITE = 0xFFFFFFFF, DIM = 0xB8FFFFFF, FAINT = 0x70FFFFFF, CARD = 0x17FFFFFF, CARD_EDGE = 0x2AFFFFFF;
    private static final int WARM = 0xFFFFB35C, BLUE = 0xFF6FB1FF, GREEN = 0xFF63E0A3, VIOLET = 0xFFB69CFF;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final HomeData data;
    private final float u;                       // one unit = 1/1920 of the screen width
    private final Locale locale;
    private final Random random = new Random();
    private boolean running, eco;
    private int page;
    private long startedAt;
    /** Set by a host that owns the window: called with the panel brightness wanted now (0.02 in economy). */
    Runnable onEco;

    private final View scrim;
    private final GlowView glowA, glowB;
    private final List<ObjectAnimator> motion = new ArrayList<>();
    private LinearLayout row;
    private TextView ecoClock;
    private TextView dateText, tempText, condText, detailText, todayText, nowcastText, headSource, routeBody, linkBody;
    private SymbolView nowIcon;
    private final List<TextView> headlineRows = new ArrayList<>();
    private LinearLayout headlineBox;
    private final List<TextView> dayName = new ArrayList<>(), dayTemps = new ArrayList<>();
    private final List<SymbolView> dayIcon = new ArrayList<>();

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            long ecoAfter = Prefs.saverEcoMinutes() * 60_000L;
            if (!eco && ecoAfter > 0 && SystemClock.uptimeMillis() - startedAt >= ecoAfter) {
                enterEco();
            }
            refreshData();
            flipHeadlines();
            handler.postDelayed(this, PAGE_MS);
        }
    };

    private final Runnable fast = new Runnable() {   // the date and the connection card change often
        @Override public void run() {
            if (!running) return;
            fill();
            handler.postDelayed(this, 5_000);
        }
    };

    private final Runnable moveEco = new Runnable() {   // economy: the clock moves a little every minute
        @Override public void run() {
            if (!running || !eco) return;
            int w = getWidth(), h = getHeight();
            if (w > 0 && ecoClock.getWidth() > 0) {
                ecoClock.setTranslationX((random.nextFloat() * 2 - 1) * Math.max(0, (w - ecoClock.getWidth()) / 2f) * 0.8f);
                ecoClock.setTranslationY((random.nextFloat() * 2 - 1) * Math.max(0, (h - ecoClock.getHeight()) / 2f) * 0.8f);
            }
            handler.postDelayed(this, 60_000);
        }
    };

    InfoView(Context c) {
        super(c);
        Ui.init(c);
        data = HomeData.get(c);
        u = Math.max(0.5f, getResources().getDisplayMetrics().widthPixels / 1920f);
        locale = Ui.ru ? new Locale("ru") : Locale.getDefault();
        setKeepScreenOn(true);
        setBackgroundColor(0xFF070A14);

        // A slow glow behind the cards: two soft spots of colour that drift (and stop in economy).
        glowA = new GlowView(c);
        glowB = new GlowView(c);
        addView(glowA, new FrameLayout.LayoutParams(Math.round(1300 * u), Math.round(1300 * u), Gravity.TOP | Gravity.START));
        addView(glowB, new FrameLayout.LayoutParams(Math.round(1100 * u), Math.round(1100 * u), Gravity.BOTTOM | Gravity.END));
        glowA.setTranslationX(-300 * u);
        glowA.setTranslationY(-420 * u);
        glowB.setTranslationX(250 * u);
        glowB.setTranslationY(380 * u);

        row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_HORIZONTAL);
        row.setPadding(0, Math.round(56 * u), 0, Math.round(56 * u));
        LinearLayout left = column(c, 880), right = column(c, 840);
        boolean leftAny = false, rightAny = false;

        if (Prefs.saverClock() || Prefs.saverDate()) {
            if (Prefs.saverClock()) {
                final TextClock clock = new TextClock(c);
                clock.setFormat24Hour("HH:mm");
                clock.setFormat12Hour("HH:mm");
                clock.setTypeface(Ui.displaySemibold);
                clock.setTextSize(TypedValue.COMPLEX_UNIT_PX, 170 * u);
                clock.setTextColor(WHITE);
                clock.setIncludeFontPadding(false);
                clock.setLetterSpacing(-0.02f);
                clock.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {   // the digits fade from white into a cool tint
                    clock.getPaint().setShader(new LinearGradient(0, 0, 0, b - t, 0xFFFFFFFF, 0xFF9DB4FF, Shader.TileMode.CLAMP));
                });
                left.addView(clock, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            if (Prefs.saverDate()) {
                dateText = text(c, Ui.displayMedium, 40, DIM);
                dateText.setLetterSpacing(0.02f);
                dateText.setPadding(Math.round(8 * u), Math.round((Prefs.saverClock() ? 2 : 0) * u), 0, Math.round(14 * u));
                left.addView(dateText);
            }
            leftAny = true;
        }
        if (Prefs.saverWeather()) {
            left.addView(weatherCard(c), cardParams(0));
            leftAny = true;
        }
        if (Prefs.saverNews()) {
            right.addView(newsCard(c), stretch(0));
            rightAny = true;
        }
        boolean route = Prefs.saverRoute() && Route.configured();
        if (route || Prefs.saverLink()) {
            LinearLayout small = new LinearLayout(c);
            small.setOrientation(LinearLayout.HORIZONTAL);
            if (route) small.addView(smallCard(c, "home", GREEN, Prefs.routeName().isEmpty() ? (Ui.ru ? "Дорога" : "Road") : Prefs.routeName(), true), smallParams(Prefs.saverLink()));
            if (Prefs.saverLink()) small.addView(smallCard(c, "wifi", VIOLET, Ui.ru ? "Связь" : "Connection", false),
                    route ? smallParams(false) : new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            if (Prefs.saverNews()) {   // under the weather; without the news they go to the right column
                LinearLayout.LayoutParams sp = cardParams(leftAny ? 24 : 0);
                if (!route) sp.width = ViewGroup.LayoutParams.WRAP_CONTENT;   // the connection card alone: as wide as its text
                left.addView(small, sp);
                leftAny = true;
            } else {
                right.addView(small, stretch(0));
                rightAny = true;
            }
        }
        if (leftAny) row.addView(left);
        if (leftAny && rightAny) row.addView(new View(c), new LinearLayout.LayoutParams(Math.round(48 * u), 1));
        if (rightAny) row.addView(right);
        addView(row, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        ecoClock = new TextClock(c);
        ((TextClock) ecoClock).setFormat24Hour("HH:mm");
        ((TextClock) ecoClock).setFormat12Hour("HH:mm");
        ecoClock.setTypeface(Ui.thin);
        ecoClock.setTextSize(TypedValue.COMPLEX_UNIT_PX, 180 * u);
        ecoClock.setTextColor(0xFF3A3C48);   // dim on purpose
        ecoClock.setIncludeFontPadding(false);
        ecoClock.setVisibility(View.GONE);
        addView(ecoClock, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        scrim = new View(c);   // darkens the whole picture: as chosen in Settings (and by night)
        scrim.setBackgroundColor(0xFF000000);
        addView(scrim, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        paint();
        fill();
    }

    // MARK: - Building blocks

    private LinearLayout column(Context c, int width) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setLayoutParams(new LinearLayout.LayoutParams(Math.round(width * u), ViewGroup.LayoutParams.MATCH_PARENT));
        return l;
    }

    private LinearLayout.LayoutParams cardParams(float topMargin) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Math.round(topMargin * u);
        return lp;
    }

    /** A card that takes the height left in its column, so both columns end at the same line. */
    private LinearLayout.LayoutParams stretch(float topMargin) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        lp.topMargin = Math.round(topMargin * u);
        return lp;
    }

    private LinearLayout.LayoutParams smallParams(boolean gapAfter) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);   // both cards as tall as the taller
        if (gapAfter) lp.rightMargin = Math.round(24 * u);
        return lp;
    }

    private LinearLayout card(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[] {0x22FFFFFF, 0x0EFFFFFF});
        bg.setCornerRadius(38 * u);
        bg.setStroke(Math.max(1, Math.round(1.5f * u)), CARD_EDGE);
        l.setBackground(bg);
        int pad = Math.round(34 * u);
        l.setPadding(pad, Math.round(22 * u), pad, Math.round(24 * u));
        return l;
    }

    private TextView text(Context c, Typeface face, float size, int color) {
        TextView t = new TextView(c);
        t.setTypeface(face);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, size * u);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        return t;
    }

    /** The card's title: a round badge in the card's colour with a symbol in it, and the name. */
    private LinearLayout header(Context c, String symbol, int accent, String title, TextView right) {
        LinearLayout h = new LinearLayout(c);
        h.setOrientation(LinearLayout.HORIZONTAL);
        h.setGravity(Gravity.CENTER_VERTICAL);
        SymbolView badge = new SymbolView(c);
        GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL);
        ring.setColor((accent & 0x00FFFFFF) | 0x30000000);
        badge.setBackground(ring);
        badge.set(symbol, accent, 58 * u);
        h.addView(badge, new LinearLayout.LayoutParams(Math.round(58 * u), Math.round(58 * u)));
        TextView t = text(c, Ui.displaySemibold, 32, WHITE);
        t.setText(title);
        t.setLetterSpacing(0.03f);
        t.setPadding(Math.round(18 * u), 0, 0, 0);
        h.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (right != null) h.addView(right);
        return h;
    }

    private View divider(Context c, float top, float bottom) {
        View line = new View(c);
        line.setBackgroundColor(0x24FFFFFF);
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Math.round(1.5f * u)));
        ll.topMargin = Math.round(top * u);
        ll.bottomMargin = Math.round(bottom * u);
        line.setLayoutParams(ll);
        return line;
    }

    private LinearLayout weatherCard(Context c) {
        LinearLayout card = card(c);
        TextView place = text(c, Ui.medium, 26, FAINT);
        place.setText(Prefs.city());
        card.addView(header(c, "sunny", WARM, Ui.ru ? "Погода" : "Weather", place));

        LinearLayout top = new LinearLayout(c);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(0, Math.round(8 * u), 0, 0);
        nowIcon = new SymbolView(c);
        top.addView(nowIcon, new LinearLayout.LayoutParams(Math.round(96 * u), Math.round(96 * u)));
        tempText = text(c, Ui.displaySemibold, 96, WHITE);
        tempText.setPadding(Math.round(12 * u), 0, Math.round(24 * u), 0);
        top.addView(tempText);
        LinearLayout words = new LinearLayout(c);
        words.setOrientation(LinearLayout.VERTICAL);
        condText = text(c, Ui.displaySemibold, 38, WHITE);
        detailText = text(c, Ui.medium, 26, DIM);
        detailText.setPadding(0, Math.round(6 * u), 0, 0);
        words.addView(condText);
        words.addView(detailText);
        top.addView(words, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        card.addView(top);

        todayText = text(c, Ui.medium, 28, WHITE);   // today: the day's high and low, and rain soon
        todayText.setPadding(0, Math.round(8 * u), 0, 0);
        card.addView(todayText);
        nowcastText = text(c, Ui.semibold, 26, BLUE);
        nowcastText.setPadding(0, Math.round(6 * u), 0, 0);
        card.addView(nowcastText);

        card.addView(divider(c, 12, 12));
        LinearLayout days = new LinearLayout(c);
        days.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < 2; i++) {   // tomorrow and the day after: icon, then the weekday over high / low
            LinearLayout cell = new LinearLayout(c);
            cell.setOrientation(LinearLayout.HORIZONTAL);
            cell.setGravity(Gravity.CENTER_VERTICAL);
            SymbolView icon = new SymbolView(c);
            cell.addView(icon, new LinearLayout.LayoutParams(Math.round(56 * u), Math.round(56 * u)));
            LinearLayout col = new LinearLayout(c);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setPadding(Math.round(12 * u), 0, 0, 0);
            TextView name = text(c, Ui.medium, 25, DIM);
            TextView t = text(c, Ui.displaySemibold, 29, WHITE);
            col.addView(name);
            col.addView(t);
            cell.addView(col);
            dayName.add(name);
            dayIcon.add(icon);
            dayTemps.add(t);
            days.addView(cell, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        card.addView(days);
        return card;
    }

    private LinearLayout newsCard(Context c) {
        LinearLayout card = card(c);
        headSource = text(c, Ui.medium, 26, FAINT);
        card.addView(header(c, "live_tv", BLUE, Ui.ru ? "Новости" : "News", headSource));
        headlineBox = new LinearLayout(c);
        headlineBox.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < HEADLINES; i++) {
            TextView t = text(c, Ui.medium, 30, WHITE);
            t.setLineSpacing(0, 1.1f);
            t.setMaxLines(3);
            t.setEllipsize(TextUtils.TruncateAt.END);
            t.setPadding(0, Math.round(18 * u), 0, 0);
            headlineRows.add(t);
            headlineBox.addView(t);
        }
        card.addView(headlineBox);
        return card;
    }

    private LinearLayout smallCard(Context c, String symbol, int accent, String title, boolean road) {
        LinearLayout card = card(c);
        card.setPadding(Math.round(28 * u), Math.round(24 * u), Math.round(24 * u), Math.round(26 * u));
        card.addView(header(c, symbol, accent, title, null));
        TextView body = text(c, Ui.displayMedium, 31, WHITE);
        body.setLineSpacing(0, 1.2f);
        body.setPadding(0, Math.round(14 * u), 0, 0);
        card.addView(body);
        if (road) routeBody = body;
        else linkBody = body;
        return card;
    }

    // MARK: - Filling

    private String cap(String s) {
        return s.isEmpty() ? s : s.substring(0, 1).toUpperCase(locale) + s.substring(1);
    }

    /** Puts the numbers on the cards (cheap: called every few seconds). */
    private void fill() {
        long now = System.currentTimeMillis();
        HomeData.Weather w = data.weather;
        if (dateText != null) dateText.setText(cap(new SimpleDateFormat("EEEE, d MMMM", locale).format(new Date(now))));

        if (tempText != null) {
            boolean have = !Double.isNaN(w.temp);
            tempText.setText(have ? HomeData.degrees(w.temp) : "—");
            condText.setText(have ? HomeData.condition(w.code, w.isDay) : (Ui.ru ? "Нет данных о погоде" : "No weather data"));
            nowIcon.set(have ? Symbols.weather(w.code, w.isDay) : null, WARM, 96 * u);
            StringBuilder d = new StringBuilder();
            String wind = HomeData.windSpeedText(w);
            if (!wind.isEmpty()) d.append(Ui.ru ? "Ветер " : "Wind ").append(wind);
            String pressure = HomeData.pressureValue(w);
            if (!pressure.isEmpty()) d.append(d.length() > 0 ? "  ·  " : "").append(pressure);
            detailText.setText(d.toString());
            detailText.setVisibility(d.length() == 0 ? View.GONE : View.VISIBLE);
            boolean range = !Double.isNaN(w.max) && !Double.isNaN(w.min);
            todayText.setText(range ? (Ui.ru ? "Сегодня: днём " : "Today: high ") + HomeData.degrees(w.max)
                    + (Ui.ru ? ", ночью " : ", low ") + HomeData.degrees(w.min) : "");
            todayText.setVisibility(range ? View.VISIBLE : View.GONE);
            nowcastText.setText(w.nowcast == null ? "" : w.nowcast);
            nowcastText.setVisibility(w.nowcast == null || w.nowcast.isEmpty() ? View.GONE : View.VISIBLE);

            SimpleDateFormat dayFormat = new SimpleDateFormat("EEEE", locale);
            for (int i = 0; i < dayName.size(); i++) {
                int at = i + 1;   // fc[0] is today
                boolean ok = w.fcAt[at] > 0 && !Double.isNaN(w.fcMax[at]);
                dayName.get(i).setText(ok ? cap(dayFormat.format(new Date(w.fcAt[at]))) : "");
                dayIcon.get(i).set(ok && w.fcCode[at] >= 0 ? Symbols.weather(w.fcCode[at], true) : null, WHITE, 56 * u);
                dayTemps.get(i).setText(ok ? HomeData.degrees(w.fcMax[at]) + " / " + HomeData.degrees(w.fcMin[at]) : "");
            }
        }

        if (routeBody != null) {
            long[] r = Route.saved(getContext());
            if (r == null) {
                routeBody.setText("—");
            } else {
                String t = Route.time(r[0]) + "\n" + Route.km(r[1]);
                if (Route.stale(r[2])) t += (Ui.ru ? "  ·  данные на " : "  ·  as of ") + new SimpleDateFormat("HH:mm", locale).format(new Date(r[2]));
                routeBody.setText(t);
            }
        }

        if (linkBody != null) {
            StringBuilder sb = new StringBuilder();
            int state = VpnPing.state, ms = VpnPing.ms;
            sb.append("VPN   ").append(state == VpnPing.WORKS && ms > 0 ? ms + (Ui.ru ? " мс" : " ms")
                    : state == VpnPing.FAILS ? (Ui.ru ? "нет связи" : "no answer") : (Ui.ru ? "выкл" : "off"));
            sb.append("\nWi-Fi   ").append(wifi());
            SpannableString span = new SpannableString(sb.toString());
            if (state == VpnPing.WORKS && ms > 400) {   // orange above 400 ms, red above 800 (the home screen's limits)
                int from = sb.indexOf(String.valueOf(ms));
                span.setSpan(new ForegroundColorSpan(ms > 800 ? 0xFFFF6B5F : 0xFFFFB340), from, from + String.valueOf(ms).length() + 3, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            linkBody.setText(span);
        }
    }

    private String wifi() {
        try {
            WifiManager wm = (WifiManager) getContext().getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            WifiInfo wi = wm == null ? null : wm.getConnectionInfo();
            if (wi == null || wi.getRssi() <= -127) return Ui.ru ? "кабель" : "cable";
            int rssi = wi.getRssi();
            return rssi >= -60 ? (Ui.ru ? "хороший" : "good") : rssi >= -70 ? (Ui.ru ? "средний" : "fair") : (Ui.ru ? "слабый" : "weak");
        } catch (RuntimeException e) {
            return "—";
        }
    }

    private final List<String> shown = new ArrayList<>();       // the lines on the card now, newest first
    private final java.util.Set<String> seen = new java.util.HashSet<>();   // titles that were in the feed already: never shown again once they leave

    /**
     * The news as a feed: the card keeps its headlines still, and a story that is new (not seen before, and not the same story
     * as one already on the card) comes in at the top while the oldest one goes. Nothing comes back after it has left.
     */
    private void flipHeadlines() {
        if (headlineBox == null) return;
        final List<String> list = NewsFeed.saved(getContext());
        if (list.isEmpty()) {
            if (!shown.isEmpty()) return;   // the old ones stay while the network is away
            headSource.setText("");
            for (TextView t : headlineRows) t.setText("");
            headlineRows.get(0).setText(Ui.ru ? "Новости загружаются…" : "Loading the news…");
            return;
        }
        java.util.Set<String> current = new java.util.HashSet<>();
        List<String> fresh = new ArrayList<>();
        for (String line : list) {
            String[] f = line.split("\t", 3);
            if (f.length < 3) continue;
            current.add(f[2]);
            if (seen.add(f[2])) fresh.add(line);
        }
        if (seen.size() > 400) { seen.clear(); seen.addAll(current); }
        List<String> next = new ArrayList<>();
        for (String line : fresh) {
            String title = line.split("\t", 3)[2];
            boolean twin = false;
            for (String o : next) if (NewsFeed.sameStory(title, o.split("\t", 3)[2])) { twin = true; break; }
            for (String o : shown) if (!twin && NewsFeed.sameStory(title, o.split("\t", 3)[2])) { twin = true; break; }
            if (!twin) next.add(line);
        }
        for (String line : shown) {   // what was on the card stays while it is still in the feed
            String[] f = line.split("\t", 3);
            if (f.length == 3 && current.contains(f[2])) next.add(line);
        }
        while (next.size() > HEADLINES) next.remove(next.size() - 1);
        if (next.equals(shown)) return;
        shown.clear();
        shown.addAll(next);
        final List<String> lines = new ArrayList<>(next);
        headlineBox.animate().alpha(0f).setDuration(350).withEndAction(() -> {
            headSource.setText(Prefs.newsImportant() > 0 ? (Ui.ru ? "только важное" : "important only") : "");
            for (int i = 0; i < HEADLINES; i++) {
                TextView t = headlineRows.get(i);
                if (i >= lines.size()) {
                    t.setText("");
                    continue;
                }
                String line = lines.get(i);
                String[] f = line.split("\t", 3);
                if (f.length < 3) {
                    t.setText(line);
                    continue;
                }
                String head = f[0] + "  " + f[1];   // the time, then where it came from in a small violet tag
                SpannableString s = new SpannableString(head + "   " + f[2]);
                s.setSpan(new ForegroundColorSpan(BLUE), 0, f[0].length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                s.setSpan(new ForegroundColorSpan(VIOLET), f[0].length() + 2, head.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                s.setSpan(new android.text.style.RelativeSizeSpan(0.78f), f[0].length() + 2, head.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                t.setText(s);
            }
            headlineBox.animate().alpha(1f).setDuration(500).start();
        }).start();
    }

    private void refreshData() {
        data.refreshIfStale();
        NewsFeed.refresh(getContext(), () -> handler.post(this::flipHeadlines));
        Route.refresh(getContext(), () -> handler.post(this::fill));
    }

    /** The glow follows the weather and the time of day; the scrim darkens everything as set in Settings (and by night). */
    private void paint() {
        HomeData.Weather w = data.weather;
        boolean day = w.isDay;
        int code = w.code;
        int a, b;
        if (!day) {
            a = 0xFF3B4CC4;
            b = 0xFF7A3FB5;
        } else if (code == 45 || code == 48) {
            a = 0xFF7C8A99;
            b = 0xFF5B6B80;
        } else if (code >= 95) {
            a = 0xFF5A4FB0;
            b = 0xFF2E3A7A;
        } else if (code >= 71 && code <= 77 || code == 85 || code == 86) {
            a = 0xFF7FA5D6;
            b = 0xFF5C7FB0;
        } else if (code >= 51) {
            a = 0xFF3F6FA8;
            b = 0xFF2E4F80;
        } else if (code == 3 || code == 2) {
            a = 0xFF4F7CB8;
            b = 0xFF7F8FC0;
        } else {
            a = 0xFF2F7CE0;
            b = 0xFFE59A4A;
        }
        glowA.setColor(a);
        glowB.setColor(b);
        scrim.setAlpha(dimming());
        if (running) Backlight.set(getContext(), backlight());
    }

    /** The panel's backlight in percent while the screensaver is on (0 = leave it as the person set it). */
    private int backlight() {
        if (eco) return 8;
        int level = Prefs.saverBrightness();
        return level == 3 ? 0 : level == 1 ? 12 : level == 2 ? 30 : data.weather.isDay ? 30 : 15;   // low from the start
    }

    /** 0 = leave as it is, up to 0.6 = much darker. Brightness in Settings: Auto (dark at night), Dim, Medium, Bright. */
    private float dimming() {
        int level = Prefs.saverBrightness();
        if (level == 0) return data.weather.isDay ? 0.3f : 0.55f;
        return level == 1 ? 0.6f : level == 2 ? 0.35f : 0f;
    }

    /** For a host that owns the window: the panel's brightness now, or -1 to leave it as it is. */
    float panelBrightness() {
        if (eco) return 0.02f;
        float d = dimming();
        return d >= 0.5f ? 0.1f : d > 0 ? 0.3f : -1f;
    }

    // MARK: - Economy

    /** After a while: the same board, nothing moving, and the panel turned down further. */
    private void enterEco() {
        eco = true;
        for (ObjectAnimator a : motion) a.cancel();
        motion.clear();
        Backlight.set(getContext(), backlight());
        if (onEco != null) onEco.run();
        AppLog.w("screensaver: economy mode");
    }

    // MARK: - Lifecycle

    void start() {
        if (running) return;
        running = true;
        eco = false;
        startedAt = SystemClock.uptimeMillis();
        row.setAlpha(1f);
        row.setVisibility(View.VISIBLE);
        ecoClock.setVisibility(View.GONE);
        glowA.setAlpha(1f);
        glowB.setAlpha(1f);
        VpnPing.start(getContext());
        data.addListener(this);
        paint();
        fill();
        refreshData();
        page = 0;
        flipHeadlines();
        drift(glowA, -300 * u, 200 * u, -420 * u, -150 * u, 95_000);
        drift(glowB, 250 * u, -250 * u, 380 * u, 150 * u, 120_000);
        handler.postDelayed(tick, PAGE_MS);
        handler.postDelayed(fast, 5_000);
    }

    private void drift(View v, float x0, float x1, float y0, float y1, long ms) {
        for (String prop : new String[] {"translationX", "translationY"}) {
            ObjectAnimator a = ObjectAnimator.ofFloat(v, prop, prop.equals("translationX") ? x0 : y0, prop.equals("translationX") ? x1 : y1);
            a.setDuration(ms);
            a.setRepeatMode(ObjectAnimator.REVERSE);
            a.setRepeatCount(ObjectAnimator.INFINITE);
            a.setInterpolator(new LinearInterpolator());
            a.start();
            motion.add(a);
        }
    }

    void stop() {
        if (!running) return;
        running = false;
        for (ObjectAnimator a : motion) a.cancel();
        motion.clear();
        handler.removeCallbacksAndMessages(null);
        data.removeListener(this);
        Backlight.restore(getContext());
    }

    @Override protected void onDetachedFromWindow() {
        stop();
        super.onDetachedFromWindow();
    }

    @Override public void onData() {
        paint();
        fill();
    }

    // MARK: - Small views

    /** A big soft spot of colour (a radial gradient that fades to nothing). */
    private static final class GlowView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int color = 0xFF2F7CE0;

        GlowView(Context c) {
            super(c);
        }

        void setColor(int c) {
            color = c;
            paint.setShader(null);
            invalidate();
        }

        @Override protected void onDraw(Canvas c) {
            if (paint.getShader() == null) {
                float r = Math.min(getWidth(), getHeight()) / 2f;
                paint.setShader(new RadialGradient(getWidth() / 2f, getHeight() / 2f, r, new int[] {(color & 0x00FFFFFF) | 0x66000000, (color & 0x00FFFFFF) | 0x22000000, 0x00000000},
                        new float[] {0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
            }
            c.drawCircle(getWidth() / 2f, getHeight() / 2f, Math.min(getWidth(), getHeight()) / 2f, paint);
        }
    }

    /** A symbol, in one color. */
    private static final class SymbolView extends View {
        private String name;
        private int color;
        private float size;

        SymbolView(Context c) {
            super(c);
        }

        void set(String name, int color, float size) {
            if ((name == null ? this.name == null : name.equals(this.name)) && this.color == color) return;
            this.name = name;
            this.color = color;
            this.size = size;
            invalidate();
        }

        @Override protected void onDraw(Canvas c) {
            if (name != null) Symbols.draw(getContext(), c, name, getWidth() / 2f, getHeight() / 2f, size * 0.55f, color);
        }
    }
}
