package io.freemytv;

import android.app.ActivityManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.SystemClock;

/** Once an hour, one line about how the box is doing (how long it has been up, free memory, VPN, Wi-Fi), for the log. */
final class Snapshot {
    private Snapshot() {}

    private static final long EVERY_MS = 55 * 60_000L;

    /** Writes the line if the last one is old enough. Cheap to call often; not for the UI thread (it touches the disk). */
    static void maybe(Context c) {
        Context app = c.getApplicationContext();
        SharedPreferences sp = app.getSharedPreferences("health", Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        long last = sp.getLong("snapshotAt", 0);
        if (now >= last && now - last < EVERY_MS) return;
        sp.edit().putLong("snapshotAt", now).apply();
        StringBuilder sb = new StringBuilder("status: up ");
        long minutes = SystemClock.elapsedRealtime() / 60_000;
        sb.append(minutes / 1440).append("d ").append(minutes / 60 % 24).append("h");
        try {
            ActivityManager am = (ActivityManager) app.getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            sb.append(", free memory ").append(mi.availMem >> 20).append(" MB").append(mi.lowMemory ? " (LOW)" : "");
        } catch (RuntimeException ignored) {}
        sb.append(", VPN ").append(VpnGuard.vpnUp(app) ? "up" : "DOWN");
        try {
            // Which network carries the traffic (the VPN rides on top of one), and how good the Wi-Fi signal is: without the
            // location permission Android hides the network id and name but not the signal.
            ConnectivityManager cm = (ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
            String carrier = "none";
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities nc = cm.getNetworkCapabilities(n);
                if (nc == null || nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                        || !nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue;
                carrier = nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ? "Wi-Fi" : nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ? "Ethernet" : "other";
            }
            sb.append(", network ").append(carrier);
            if (carrier.equals("Wi-Fi")) {
                WifiManager wm = (WifiManager) app.getSystemService(Context.WIFI_SERVICE);
                WifiInfo wi = wm == null ? null : wm.getConnectionInfo();
                if (wi != null) sb.append(" ").append(wi.getRssi()).append(" dBm ").append(wi.getFrequency()).append(" MHz ")
                        .append(wi.getLinkSpeed()).append(" Mbps");
            }
        } catch (RuntimeException ignored) {}
        AppLog.w(sb.toString());
    }
}
