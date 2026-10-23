package io.freemytv;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/**
 * Sleep timer: "Night clock in N minutes", picked in the menu (SleepMenuActivity). When it runs out, whatever plays is
 * paused and the night clock (a dim clock on a black screen) comes up. A restart of the box clears the timer.
 */
final class SleepTimer {
    private SleepTimer() {}

    static final String ACTION = "io.freemytv.SLEEP_TIMER";

    /** Minutes left (rounded up), or 0 when no timer is set. */
    static int remainingMinutes(Context c) {
        long at = c.getSharedPreferences("sleeptimer", Context.MODE_PRIVATE).getLong("at", 0);
        long left = at - System.currentTimeMillis();
        return left <= 0 ? 0 : (int) ((left + 59_999) / 60_000);
    }

    /** Sets the timer to `minutes` from now, or cancels it with 0. */
    static void set(Context c, int minutes) {
        long at = System.currentTimeMillis() + minutes * 60_000L;
        c.getSharedPreferences("sleeptimer", Context.MODE_PRIVATE).edit().putLong("at", minutes == 0 ? 0 : at).apply();
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        PendingIntent pi = PendingIntent.getBroadcast(c, 79, new Intent(c, HomeReceiver.class).setAction(ACTION),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        if (minutes == 0) {
            am.cancel(pi);
            return;
        }
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        } catch (SecurityException e) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        }
    }

    /** The time is up (on a background thread, from ModeJob). */
    static void fireNow(Context c) {
        c.getSharedPreferences("sleeptimer", Context.MODE_PRIVATE).edit().putLong("at", 0).apply();
        try (Adb adb = Adb.connect(c, 5_000, null)) {
            adb.shell("input keyevent 127");   // media pause
            adb.shell("am start -n " + HomeMode.PKG + "/" + HomeMode.PKG + ".DarkActivity");
        } catch (Exception e) {
            AppLog.w("sleep timer: " + e.getMessage());
        }
    }
}
