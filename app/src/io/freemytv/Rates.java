package io.freemytv;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** The Central Bank of Russia's daily exchange rates (no key), for the screensaver. Saved, and read again every two hours. */
final class Rates {
    private Rates() {}

    static final class Rate {
        final String code;
        final double value, previous;
        Rate(String code, double value, double previous) {
            this.code = code;
            this.value = value;
            this.previous = previous;
        }
    }

    private static final String[] CODES = {"USD", "EUR", "CNY"};
    private static final long FRESH_MS = 2 * 3_600_000L;
    private static boolean busy;

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("rates", Context.MODE_PRIVATE);
    }

    /** The saved rates (empty before the first reading). */
    static List<Rate> saved(Context c) {
        List<Rate> out = new ArrayList<>();
        SharedPreferences p = sp(c);
        for (String code : CODES) {
            if (p.contains(code)) out.add(new Rate(code, p.getFloat(code, 0), p.getFloat(code + "p", 0)));
        }
        return out;
    }

    static void refresh(Context c, Runnable done) {
        final Context app = c.getApplicationContext();
        SharedPreferences p = sp(app);
        synchronized (Rates.class) {
            if (busy || System.currentTimeMillis() - p.getLong("at", 0) < FRESH_MS) return;
            busy = true;
        }
        new Thread(() -> {
            try {
                SharedPreferences.Editor e = p.edit();
                boolean got = false;
                try {
                    String xml = NewsFeed.download("https://www.cbr.ru/scripts/XML_daily.asp");
                    for (String code : CODES) {
                        java.util.regex.Matcher m = java.util.regex.Pattern.compile("<CharCode>" + code + "</CharCode>.*?<VunitRate>([0-9,.]+)</VunitRate>",
                                java.util.regex.Pattern.DOTALL).matcher(xml);
                        if (!m.find()) continue;
                        float value = Float.parseFloat(m.group(1).replace(',', '.'));
                        float old = p.getFloat(code, 0);
                        // The day before, as far as we know: the last value that was different from today's.
                        e.putFloat(code + "p", old != 0 && Math.abs(old - value) > 0.0001f ? old : p.getFloat(code + "p", value));
                        e.putFloat(code, value);
                        got = true;
                    }
                } catch (Exception first) {
                    JSONObject all = new JSONObject(NewsFeed.download("https://www.cbr-xml-daily.ru/daily_json.js")).getJSONObject("Valute");
                    for (String code : CODES) {
                        JSONObject one = all.optJSONObject(code);
                        if (one == null) continue;
                        e.putFloat(code, (float) one.getDouble("Value")).putFloat(code + "p", (float) one.getDouble("Previous"));
                        got = true;
                    }
                }
                if (!got) throw new Exception("no rates in the answer");
                e.putLong("at", System.currentTimeMillis()).apply();
                if (done != null) done.run();
            } catch (Exception e) {
                AppLog.w("rates: " + e.getMessage());
            } finally {
                synchronized (Rates.class) { busy = false; }
            }
        }, "rates").start();
    }
}
