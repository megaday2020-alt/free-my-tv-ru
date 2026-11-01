package io.freemytv;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;

import java.util.Arrays;

/**
 * How the VPN answers, measured all the time in the :home process (not only while the home screen is in front), so the
 * number is ready the moment the person comes back to the home screen. One tiny HTTPS request to YouTube over the VPN
 * every 30 s while it works (about 6 KB), every 10 s while it does not.
 */
final class VpnPing {
    private VpnPing() {}

    static final int OFF = 0, WORKS = 1, FAILS = 2;
    /** Written by the probe thread, read by the home screen. */
    static volatile int state = OFF, ms = -1;
    static volatile long version;   // bumped by every result: the screen redraws when it changes

    private static final int[] recent = new int[3];
    private static int seen, misses;
    private static Handler handler;
    private static Context app;
    private static final Runnable TICK = VpnPing::probe;

    /** Starts the loop (once). */
    static synchronized void start(Context c) {
        if (handler != null) return;
        app = c.getApplicationContext();
        HandlerThread t = new HandlerThread("vpn-ping");
        t.start();
        handler = new Handler(t.getLooper());
        handler.post(TICK);
    }

    /** A look right now (the home screen came back to the front). */
    static void kick() {
        Handler h = handler;
        if (h == null) return;
        h.removeCallbacks(TICK);
        h.post(TICK);
    }

    private static void probe() {
        long next = 10_000;
        try {
            ConnectivityManager cm = (ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkCapabilities mine = cm.getNetworkCapabilities(cm.getActiveNetwork());
            if (mine == null || !mine.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {   // no VPN to test
                set(OFF, -1);
                seen = 0;
                misses = 0;
                next = 5_000;
            } else {
                StringBuilder why = new StringBuilder();
                long t0 = SystemClock.uptimeMillis();
                int code = VpnGuard.probe(app, "https://www.youtube.com/generate_204", 6000, false, false, why);
                int took = (int) (SystemClock.uptimeMillis() - t0);
                if (code > 0 && code < 500) {
                    misses = 0;
                    recent[seen++ % 3] = took;
                    int[] s = Arrays.copyOf(recent, Math.min(seen, 3));
                    Arrays.sort(s);
                    set(WORKS, s[s.length / 2]);   // the middle of the last three: one slow answer does not jump the number
                    next = 30_000;
                } else {
                    AppLog.w("vpn probe: " + why);
                    seen = 0;
                    misses++;
                    set(misses >= 2 ? FAILS : state, -1);   // red only after two misses in a row
                }
            }
        } catch (RuntimeException e) {
            AppLog.w("vpn probe: " + e);
        }
        handler.postDelayed(TICK, next);
    }

    private static void set(int newState, int newMs) {
        if (newState != state) AppLog.w("vpn probe: " + (newState == WORKS ? "YouTube answers through the VPN" : newState == FAILS ? "no answer through the VPN" : "no VPN"));
        state = newState;
        ms = newMs;
        version++;
    }
}
