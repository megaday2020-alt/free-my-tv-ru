package io.freemytv;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;

/**
 * VPN guard. The system tells us at once when a VPN network appears or goes away (a network callback that is delivered as
 * a broadcast, so it works while nothing of ours is running, even during a video). A few seconds after the VPN is gone we
 * ask V2RayTun to connect; the broadcast to its widget toggles the connection, so it is sent only after a second look from
 * the shell shows the VPN is really down. Up to three tries per outage, 25 seconds apart. A slow check every few
 * minutes is the safety net (and re-registers the callback). Turn the guard off in the settings to disconnect on purpose.
 */
final class VpnGuard {
    private VpnGuard() {}

    static final String ACTION = "io.freemytv.VPN_GUARD", NETWORK = "io.freemytv.VPN_NETWORK";
    private static final String V2RAYTUN = "com.v2raytun.android";
    private static final long SLOW_EVERY = 3 * 60_000L, DOWN_FOR = 7_000L, RETRY_AFTER = 25_000L, QUICK = 8_000L;
    private static final int MAX_TRIES = 3;
    /** V2RayTun listens here for local SOCKS clients; a request through it travels the same way the VPN does. */
    static final int V2RAY_SOCKS = 10808;

    private static PendingIntent intent(Context c, int code, String action, int flags) {
        return PendingIntent.getBroadcast(c, code, new Intent(c, HomeReceiver.class).setAction(action), flags);
    }

    /** Arms the slow safety-net check and (re)registers the network callback; cancels both when the guard is off. */
    static void schedule(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
        // FLAG_MUTABLE: the system adds the network to the intent (the flag is ignored before Android 12).
        PendingIntent callback = intent(c, 81, NETWORK, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
        try {
            if (cm != null) cm.unregisterNetworkCallback(callback);
        } catch (RuntimeException ignored) {}   // not registered yet
        if (am == null) return;
        PendingIntent slow = intent(c, 78, ACTION, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        if (!Prefs.vpnGuard()) {
            am.cancel(slow);
            return;
        }
        try {
            if (cm != null) {
                cm.registerNetworkCallback(new NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_VPN)
                        .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(), callback);
            }
        } catch (RuntimeException e) {
            AppLog.w("vpn guard: no network callback: " + e.getMessage());
        }
        arm(am, slow, System.currentTimeMillis() + SLOW_EVERY);
    }

    /** The system says a VPN came or went: look again in a few seconds (so a quick reconnect is not mistaken for an outage). */
    private static volatile int lastUp = -1;   // -1 unknown, 0 down, 1 up (as of the last event)

    static void onNetworkEvent(Context c) {
        if (!Prefs.vpnGuard()) return;
        int now = vpnUp(c) ? 1 : 0;
        if (now == lastUp) return;   // a capability change of a VPN that stayed up (they come every few seconds): nothing to do
        lastUp = now;
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am != null) arm(am, intent(c, 80, ACTION, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE), System.currentTimeMillis() + QUICK);
    }

    private static void arm(AlarmManager am, PendingIntent pi, long at) {
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        } catch (SecurityException e) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        }
    }

    /** One tiny request over the VPN network (or the default one): the HTTP code, or -1 with the reason in `why`. */
    static int probe(Context c, String url, int timeoutMs, boolean viaVpn, boolean viaSocks, StringBuilder why) {
        long t0 = android.os.SystemClock.uptimeMillis();
        try {
            Network target = null;
            if (viaVpn) {
                ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
                for (Network n : cm.getAllNetworks()) {
                    NetworkCapabilities nc = cm.getNetworkCapabilities(n);
                    if (nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) target = n;
                }
                if (target == null) {
                    why.append("no VPN network");
                    return -1;
                }
            }
            java.net.URL u = new java.net.URL(url);
            java.net.HttpURLConnection h = (java.net.HttpURLConnection) (target != null ? target.openConnection(u)
                    : viaSocks ? u.openConnection(new java.net.Proxy(java.net.Proxy.Type.SOCKS, new java.net.InetSocketAddress("127.0.0.1", V2RAY_SOCKS)))
                    : u.openConnection());
            h.setConnectTimeout(timeoutMs);
            h.setReadTimeout(timeoutMs);
            h.setInstanceFollowRedirects(false);
            int code = h.getResponseCode();
            h.disconnect();
            why.append(code).append(" in ").append(android.os.SystemClock.uptimeMillis() - t0).append(" ms");
            return code;
        } catch (Exception e) {
            why.append(e.getClass().getSimpleName()).append(": ").append(e.getMessage()).append(" after ").append(android.os.SystemClock.uptimeMillis() - t0).append(" ms");
            return -1;
        }
    }

    static boolean vpnUp(Context c) {
        try {
            ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities nc = cm.getNetworkCapabilities(n);
                if (nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return true;
            }
        } catch (RuntimeException ignored) {}
        return false;
    }

    /** One check (on a background thread, from ModeJob). */
    static void checkNow(Context c) {
        if (!Prefs.vpnGuard()) return;
        try {
            c.getPackageManager().getPackageInfo(V2RAYTUN, 0);
        } catch (PackageManager.NameNotFoundException e) {
            return;
        }
        SharedPreferences sp = c.getSharedPreferences("vpnguard", Context.MODE_PRIVATE);
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        long now = System.currentTimeMillis();
        if (vpnUp(c)) {
            sp.edit().putLong("downSince", 0).putInt("tries", 0).putBoolean("fellBack", false).apply();
            return;
        }
        long down = sp.getLong("downSince", 0);
        if (down == 0) {
            sp.edit().putLong("downSince", now).apply();
            if (am != null) arm(am, intent(c, 80, ACTION, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE), now + QUICK);
            return;
        }
        int tries = sp.getInt("tries", 0);
        // The same server did not come back after the first try: go straight to a good backup server.
        if (tries >= 1 && Prefs.serverPick() && !sp.getBoolean("fellBack", false) && now - sp.getLong("lastTry", 0) >= 10_000) {
            sp.edit().putBoolean("fellBack", true).apply();
            AppLog.w("vpn guard: the VPN did not come back, switching to a backup server");
            VpnServers.run(c, "backup");
            return;
        }
        if (now - down < DOWN_FOR || tries >= MAX_TRIES || now - sp.getLong("lastTry", 0) < RETRY_AFTER) {
            if (tries < MAX_TRIES && am != null) {   // not yet: look again soon
                arm(am, intent(c, 80, ACTION, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE), now + QUICK);
            }
            return;
        }
        sp.edit().putLong("lastTry", now).putInt("tries", tries + 1).apply();
        try (Adb adb = Adb.connect(c, 5_000, null)) {
            // A second look from the shell: the broadcast toggles the connection, so never send it to a VPN that is up.
            if (adb.shell("ip link | grep -c tun0").trim().startsWith("0")) {
                adb.shell("am broadcast -n " + V2RAYTUN + "/.receiver.WidgetProvider1x1 -a com.v2raytun.android.action.widget.click");
                AppLog.w("vpn guard: asked V2RayTun to connect (try " + (tries + 1) + ")");
            }
        } catch (Exception e) {
            AppLog.w("vpn guard: " + e.getMessage());
        }
        if (am != null && tries + 1 < MAX_TRIES) {   // did it work? look again after the retry gap
            arm(am, intent(c, 80, ACTION, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE), System.currentTimeMillis() + (Prefs.serverPick() ? 12_000 : RETRY_AFTER + 1_000));
        }
    }
}
