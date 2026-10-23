package io.freemytv;

import android.content.Context;
import android.os.SystemClock;

/**
 * A warmer picture in the evening and at night, like a "night light" or eye-comfort mode: it uses Android's own Night
 * Display (colour temperature, from sunset to sunrise), switched on through the TV's own ADB. Brightness is never touched.
 */
final class WarmScreen {
    private WarmScreen() {}

    /** Colour temperature per level: off, soft, medium, strong (Android's range is about 2600 to 4000 K; 3300 was too strong). */
    static final int[] KELVIN = {0, 4000, 3700, 3300};
    private static final long PREVIEW_MS = 20_000;
    private static volatile long previewToken;

    /**
     * Writes the setting (background thread). Level 0 turns the night light off again. With `preview`, the tint also
     * shows for a few seconds right now (even by day), so the person can judge the strength they just picked.
     */
    static void apply(Context c, boolean preview) {
        int level = Prefs.warm();
        long token = ++previewToken;
        try (Adb adb = Adb.connect(c, 5_000, null)) {
            if (level <= 0) {
                adb.shell("settings put secure night_display_auto_mode 0; settings put secure night_display_activated 0");
                AppLog.w("warm screen: off");
                return;
            }
            String schedule = schedule(c, level);
            adb.shell(schedule);
            AppLog.w("warm screen: level " + level + ", " + KELVIN[level] + " K");
            if (!preview) return;
            adb.shell("settings put secure night_display_activated 1");
            SystemClock.sleep(PREVIEW_MS);
            if (token != previewToken) return;   // another change came in: it takes over
            adb.shell("settings put secure night_display_auto_mode 0; settings put secure night_display_activated 0; "
                    + "settings put secure night_display_auto_mode 1");   // back to the schedule
        } catch (Exception e) {
            AppLog.w("warm screen: " + e.getMessage());
        }
    }

    private static String schedule(Context c, int level) {
        HomeData.Weather wx = HomeData.get(c).weather;
        long rise = Shaders.timeOfDay(wx.sunrise, 6 * 60 * 60_000L), set = Shaders.timeOfDay(wx.sunset, 18 * 60 * 60_000L);
        return "settings put secure night_display_color_temperature " + KELVIN[Math.min(level, KELVIN.length - 1)]
                + "; settings put secure night_display_custom_start_time " + set
                + "; settings put secure night_display_custom_end_time " + rise
                + "; settings put secure night_display_auto_mode 1";
    }
}
