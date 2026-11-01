package io.freemytv;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.regex.Pattern;

/**
 * The screensaver's "Road" card: how long one fixed trip takes now, with traffic (the public Yandex Maps page, free, no key).
 * Both points are the owner's and private: they come from config (see Prefs.pinRoute), never from the repository,
 * and the address is never written to the log. The page is read like the weather one, so it may change or ask for a captcha: then the old value stays. Asked every 20 minutes, and only while the saver is on.
 */
final class Route {
    private Route() {}

    private static final long FRESH_MS = 20 * 60_000L, RETRY_MS = 5 * 60_000L, STALE_MS = 45 * 60_000L;
    private static final Pattern POINT = Pattern.compile("-?\\d{1,3}(\\.\\d+)?,\\s*-?\\d{1,3}(\\.\\d+)?");
    private static boolean busy;

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("route", Context.MODE_PRIVATE);
    }

    /** True when both points are set: otherwise the card is not shown. */
    static boolean configured() {
        return POINT.matcher(Prefs.routeFrom()).matches() && POINT.matcher(Prefs.routeTo()).matches();
    }

    private static final Pattern ROUTE = Pattern.compile(
            "\"distance\":\\{\"value\":([0-9.]+),\"text\":\"[^\"]*\"\\},\"duration\":[0-9.]+,\"durationInTraffic\":([0-9.]+),\"bounds\"");

    /** Seconds (with traffic) and meters of the quickest route on the Yandex Maps page; throws if there is none. */
    static long[] parse(String page) throws Exception {
        java.util.regex.Matcher m = ROUTE.matcher(page);
        double bestSec = -1, bestM = 0;
        while (m.find()) {
            double sec = Double.parseDouble(m.group(2));
            if (bestSec < 0 || sec < bestSec) {
                bestSec = sec;
                bestM = Double.parseDouble(m.group(1));
            }
        }
        if (bestSec <= 0) throw new Exception("no route on the page");
        return new long[] {Math.round(bestSec), Math.round(bestM)};
    }

    /** "1 ч 12 мин", "45 мин". */
    static String time(long seconds) {
        long min = Math.max(1, Math.round(seconds / 60.0));
        String h = Ui.ru ? " ч " : " h ", m = Ui.ru ? " мин" : " min";
        return min >= 60 ? (min / 60) + h + (min % 60) + m : min + m;
    }

    static String km(long meters) {
        return Math.round(meters / 1000.0) + (Ui.ru ? " км" : " km");
    }

    /** Seconds, meters and the time they were read at; null before the first reading. */
    static long[] saved(Context c) {
        SharedPreferences p = sp(c);
        return p.contains("sec") ? new long[] {p.getLong("sec", 0), p.getLong("m", 0), p.getLong("at", 0)} : null;
    }

    static boolean stale(long at) {
        return System.currentTimeMillis() - at > STALE_MS;
    }

    static void refresh(Context c, Runnable done) {
        if (!configured() || !Prefs.saverRoute()) return;
        final Context app = c.getApplicationContext();
        SharedPreferences p = sp(app);
        long now = System.currentTimeMillis();
        synchronized (Route.class) {
            if (busy || now - p.getLong("at", 0) < FRESH_MS || now - p.getLong("tried", 0) < RETRY_MS) return;
            busy = true;
        }
        p.edit().putLong("tried", now).apply();
        new Thread(() -> {
            try {
                String url = "https://yandex.com/maps/?rtext=" + URLEncoder.encode(Prefs.routeFrom().replace(" ", ""), "UTF-8")
                        + "~" + URLEncoder.encode(Prefs.routeTo().replace(" ", ""), "UTF-8") + "&rtt=auto";
                long[] r = parse(NewsFeed.download(url));   // download() does not log the address
                p.edit().putLong("sec", r[0]).putLong("m", r[1]).putLong("at", System.currentTimeMillis()).apply();
                if (done != null) done.run();
            } catch (Exception e) {
                AppLog.w("route: " + e.getMessage());   // HTTP code or reason only
            } finally {
                synchronized (Route.class) { busy = false; }
            }
        }, "route").start();
    }
}
