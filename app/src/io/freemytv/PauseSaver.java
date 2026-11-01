package io.freemytv;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Starts the screensaver by itself when nobody has touched the remote for a while and nothing is playing. Some apps (SmartTube,
 * for one) keep the screen on for as long as their player is open, even paused, so the system never starts its own screensaver
 * there. Every two minutes, one short look through the TV's own ADB: if the idle time is over the limit, no video is playing and
 * another app is in front, the screensaver opens over it; any key closes it and the app is there as it was.
 */
final class PauseSaver {
    private PauseSaver() {}

    static final String ACTION = "io.freemytv.PAUSE_SAVER";
    private static final long EVERY = 2 * 60_000L;
    private static final Pattern IDLE = Pattern.compile("mLastUserActivityTime=\\d+ \\((\\d+) ms ago\\)");

    static void schedule(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        PendingIntent pi = PendingIntent.getBroadcast(c, 83, new Intent(c, HomeReceiver.class).setAction(ACTION),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        if (Prefs.pauseSaverMinutes() == 0) {
            am.cancel(pi);
            return;
        }
        long at = System.currentTimeMillis() + EVERY;
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        } catch (SecurityException e) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        }
    }

    /** One look (on a background thread, from ModeJob). */
    static void checkNow(Context c) {
        int minutes = Prefs.pauseSaverMinutes();
        if (minutes == 0) return;
        try (Adb adb = Adb.connect(c, 5_000, null)) {
            String top = adb.shell("dumpsys activity activities | grep mResumedActivity");
            if (top.isEmpty() || top.contains(HomeMode.PKG + "/") || top.contains("com.v2raytun.android")) return;   // our own screens, or the VPN app
            if (adb.shell("dumpsys media_session | grep -E 'state=PlaybackState'").contains("state=3,")) return;   // something is playing
            Matcher m = IDLE.matcher(adb.shell("dumpsys power | grep mLastUserActivityTime"));
            if (!m.find() || Long.parseLong(m.group(1)) < minutes * 60_000L) return;
            AppLog.w("screensaver: idle for " + Long.parseLong(m.group(1)) / 60_000 + " min over an app that keeps the screen on, starting it");
            adb.shell("am start -n " + HomeMode.PKG + "/.DarkActivity");
        } catch (Exception e) {
            AppLog.w("pause saver: " + e.getMessage());
        }
    }
}
