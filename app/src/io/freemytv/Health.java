package io.freemytv;

import android.content.Context;
import android.content.SharedPreferences;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;
import java.util.Map;

/** A week of quiet counters (VPN drops, guard restores, server switches, the hottest the box got), for the report in Settings. */
final class Health {
    private Health() {}

    private static final String[] COUNTERS = {"drops", "restores", "switches"};
    private static final int DAYS = 7;

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences("health", Context.MODE_PRIVATE);
    }

    private static String day(int back) {
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DAY_OF_YEAR, -back);
        return new SimpleDateFormat("yyyyMMdd", Locale.US).format(cal.getTime());
    }

    static synchronized void count(Context c, String what) {
        SharedPreferences sp = sp(c);
        String key = what + ":" + day(0);
        sp.edit().putInt(key, sp.getInt(key, 0) + 1).apply();
    }

    /** Remembers the day's highest temperature (whole degrees). */
    static synchronized void temperature(Context c, float t) {
        SharedPreferences sp = sp(c);
        String key = "temp:" + day(0);
        if (t > sp.getFloat(key, -1000f)) sp.edit().putFloat(key, t).apply();
    }

    /** The last seven days, plain words; also forgets older days. */
    static synchronized String report(Context c) {
        SharedPreferences sp = sp(c);
        java.util.HashSet<String> keep = new java.util.HashSet<>();
        for (int i = 0; i < DAYS; i++) keep.add(day(i));
        SharedPreferences.Editor e = sp.edit();
        int[] sums = new int[COUNTERS.length];
        float hottest = -1000f;
        for (Map.Entry<String, ?> en : sp.getAll().entrySet()) {
            String k = en.getKey();
            int colon = k.indexOf(':');
            if (colon < 0) continue;
            if (!keep.contains(k.substring(colon + 1))) { e.remove(k); continue; }
            String name = k.substring(0, colon);
            if (name.equals("temp")) {
                hottest = Math.max(hottest, ((Number) en.getValue()).floatValue());
                continue;
            }
            for (int i = 0; i < COUNTERS.length; i++) if (COUNTERS[i].equals(name)) sums[i] += ((Number) en.getValue()).intValue();
        }
        e.apply();
        String temp = hottest < -999f ? "—" : Math.round(hottest) + "°C";
        if (Ui.ru) {
            return "За последние 7 дней:\n\n"
                    + "VPN пропадал: " + sums[0] + " раз\n"
                    + "Страж вернул VPN: " + sums[1] + " раз\n"
                    + "Сервер менялся: " + sums[2] + " раз\n"
                    + "Самая высокая температура приставки: " + temp;
        }
        return "Last 7 days:\n\n"
                + "VPN dropped: " + sums[0] + " times\n"
                + "Guard restored the VPN: " + sums[1] + " times\n"
                + "Server changed: " + sums[2] + " times\n"
                + "Hottest the box got: " + temp;
    }
}
