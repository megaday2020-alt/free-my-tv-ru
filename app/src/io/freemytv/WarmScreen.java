package io.freemytv;

import android.content.Context;

/**
 * A warmer picture in the evening and at night, like a "night light" or eye-comfort mode: it uses Android's own Night
 * Display (colour temperature, from sunset to sunrise), switched on through the TV's own ADB. Brightness is never touched.
 */
final class WarmScreen {
    private WarmScreen() {}

    /** Colour temperature per level: off, soft, medium, strong (Android's range is about 2600 to 4000 K). */
    static final int[] KELVIN = {0, 3800, 3300, 2850};

    /** Writes the setting (background thread). Level 0 turns the night light off again. */
    static void apply(Context c) {
        int level = Prefs.warm();
        try (Adb adb = Adb.connect(c, 5_000, null)) {
            if (level <= 0) {
                adb.shell("settings put secure night_display_auto_mode 0; settings put secure night_display_activated 0");
                AppLog.w("warm screen: off");
                return;
            }
            HomeData.Weather wx = HomeData.get(c).weather;
            long rise = Shaders.timeOfDay(wx.sunrise, 6 * 60 * 60_000L), set = Shaders.timeOfDay(wx.sunset, 18 * 60 * 60_000L);
            adb.shell("settings put secure night_display_color_temperature " + KELVIN[Math.min(level, KELVIN.length - 1)]
                    + "; settings put secure night_display_custom_start_time " + set
                    + "; settings put secure night_display_custom_end_time " + rise
                    + "; settings put secure night_display_auto_mode 1");
            AppLog.w("warm screen: level " + level + ", from " + set / 60_000 + " to " + rise / 60_000 + " min of the day");
        } catch (Exception e) {
            AppLog.w("warm screen: " + e.getMessage());
        }
    }
}
