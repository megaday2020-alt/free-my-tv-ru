package io.freemytv;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import java.util.Calendar;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Night maintenance: around 4:00, when nobody is using the box, clear the apps' caches; once the box has been up for
 * about a week, restart it. It runs only while Free My TV's own screens are in front (the home screen, the night clock
 * or the settings), never over a video, and it needs the TV's debugging on (it uses the TV's own ADB, like the quick
 * actions). The next run is scheduled again every time.
 */
final class Maintenance {
    private Maintenance() {}

    static final String ACTION = "io.freemytv.MAINTENANCE";
    private static final long UPTIME_FOR_RESTART_S = 6 * 24 * 3600L;   // restart after ~6 days up, at the next 4:00

    /** Arms the alarm for the next 4:00 (or cancels it when the setting is off). */
    static void schedule(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        PendingIntent pi = PendingIntent.getBroadcast(c, 77, new Intent(c, HomeReceiver.class).setAction(ACTION),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        if (!Prefs.maintenance()) {
            am.cancel(pi);
            return;
        }
        Calendar t = Calendar.getInstance();
        t.set(Calendar.HOUR_OF_DAY, 4);
        t.set(Calendar.MINUTE, 0);
        t.set(Calendar.SECOND, 0);
        t.set(Calendar.MILLISECOND, 0);
        if (t.getTimeInMillis() <= System.currentTimeMillis() + 60_000) t.add(Calendar.DAY_OF_MONTH, 1);
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t.getTimeInMillis(), pi);
        } catch (SecurityException e) {   // Android 12+ without the exact-alarm permission: close enough is fine
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t.getTimeInMillis(), pi);
        }
    }

    /** The work itself (on a background thread, from ModeJob). `allowRestart` false = caches only (for tests). */
    static void runNow(Context c, boolean allowRestart) {
        try (Adb adb = Adb.connect(c, 5_000, null)) {
            String top = adb.shell("dumpsys activity activities | grep mResumedActivity");
            if (!top.contains(HomeMode.PKG + "/")) {
                AppLog.w("maintenance: skipped, something else is on the screen");
                return;
            }
            adb.shell("pm trim-caches 8G");
            AppLog.w("maintenance: caches cleared");
            VpnServers.run(c, "night");   // refresh the subscriptions and pick the best VPN server (only if the picker is on)
            Matcher m = Pattern.compile("^(\\d+)").matcher(adb.shell("cat /proc/uptime").trim());
            if (allowRestart && m.find() && Long.parseLong(m.group(1)) >= UPTIME_FOR_RESTART_S) {
                AppLog.w("maintenance: restarting after a week up");
                adb.shell("reboot");
            }
        } catch (Exception e) {
            AppLog.w("maintenance failed: " + e.getMessage());
        }
    }
}
