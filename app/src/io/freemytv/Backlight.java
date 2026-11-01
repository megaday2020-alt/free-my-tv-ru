package io.freemytv;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The panel's backlight on a Xiaomi/Mi TV is the "picture_backlight" setting (0 to 100), not Android's screen brightness, which
 * the TV ignores. The screensaver turns it down through the TV's own ADB and puts the person's value back when it ends. The
 * value to go back to is kept on disk, so a screensaver that died halfway is put right the next time the home screen shows.
 */
final class Backlight {
    private Backlight() {}

    private static final String SETTING = "picture_backlight";
    private static final ExecutorService ORDER = Executors.newSingleThreadExecutor();

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("backlight", Context.MODE_PRIVATE);
    }

    /** Sets the backlight to `percent` (1..100), or puts the saved value back when it is below 1. Runs in the background, in order. */
    static void set(Context c, int percent) {
        final Context app = c.getApplicationContext();
        ORDER.execute(() -> {
            SharedPreferences p = sp(app);
            if (percent < 1 && !p.contains("saved")) return;
            try (Adb adb = Adb.connect(app, 5_000, null)) {
                if (percent < 1) {
                    adb.shell("settings put global " + SETTING + " " + p.getInt("saved", 100));
                    p.edit().remove("saved").commit();
                    return;
                }
                if (!p.contains("saved")) {
                    int now = -1;
                    try {
                        now = Integer.parseInt(adb.shell("settings get global " + SETTING).trim());
                    } catch (NumberFormatException ignored) {}
                    if (now < 1 || now > 100) return;   // not a TV with this setting
                    p.edit().putInt("saved", now).commit();
                }
                adb.shell("settings put global " + SETTING + " " + Math.min(100, percent));
            } catch (Exception e) {
                AppLog.w("backlight: " + e.getMessage());
            }
        });
    }

    /** Puts the person's backlight back if a screensaver left it turned down. */
    static void restore(Context c) {
        if (sp(c).contains("saved")) set(c, 0);
    }
}
