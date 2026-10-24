package io.freemytv;

import android.app.Activity;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.RouteInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "Check the network": in about fifteen seconds shows how good the Wi-Fi signal is, how fast the router and the internet
 * answer (the TV's own ADB can ping outside the VPN), and how the VPN does (delay and download speed). Ends with one plain
 * sentence. A dark panel over the home screen; OK checks again, Back closes. The result also goes to the event log.
 */
public class NetCheckActivity extends Activity {
    private static final String DNS_PING = "77.88.8.8";
    private static final String LATENCY_URL = "https://www.gstatic.com/generate_204";
    /** Test files, tried in turn (one may be blocked from the VPN's exit). */
    private static final String[] SPEED_URLS = {"https://speed.cloudflare.com/__down?bytes=12000000",
            "https://cachefly.cachefly.net/10mb.test", "https://proof.ovh.net/files/10Mb.dat"};

    private final Handler handler = new Handler(Looper.getMainLooper());
    private float dp;
    private TextView status, body, hint;
    private volatile int session;
    private volatile boolean busy;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.init(this);
        dp = getResources().getDisplayMetrics().density;

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0x99000000);
        root.setOnClickListener(v -> finish());

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(px(34), px(26), px(34), px(22));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF21C1C1E);
        bg.setCornerRadius(px(26));
        bg.setStroke(Math.max(1, px(1)), 0x33FFFFFF);
        panel.setBackground(bg);

        status = text("", 22, 0xFF8EC5FF, true);
        panel.addView(status);
        body = text("", 20, 0xFFF5F5F7, false);
        body.setLineSpacing(0, 1.25f);
        body.setPadding(0, px(12), 0, px(4));
        panel.addView(body);
        hint = text(Ui.ru ? "OK: проверить ещё раз   ·   Назад: закрыть" : "OK: check again   ·   Back: close", 15, 0xFF77777D, false);
        hint.setPadding(0, px(10), 0, 0);
        panel.addView(hint);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(px(860), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        root.addView(panel, lp);
        setContentView(root);
        start();
    }

    @Override protected void onDestroy() {
        session++;
        super.onDestroy();
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent e) {
        if (e.getRepeatCount() == 0 && (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)) {
            start();
            return true;
        }
        return super.onKeyDown(keyCode, e);
    }

    private void start() {
        if (busy) return;
        busy = true;
        final int mine = ++session;
        status.setText(Ui.ru ? "Проверяю сеть… около 15 секунд" : "Checking the network… about 15 seconds");
        body.setText("");
        final Context c = getApplicationContext();
        new Thread(() -> {
            final List<String> lines = new ArrayList<>();
            String verdict;
            try {
                verdict = measure(c, lines, mine);
            } catch (RuntimeException e) {
                AppLog.w("network check: " + e);
                verdict = Ui.ru ? "Проверка не удалась." : "The check failed.";
            }
            final String sentence = verdict;
            StringBuilder all = new StringBuilder();
            for (String l : lines) all.append(l).append('\n');
            AppLog.w("network check: " + all.toString().replace('\n', ';') + " verdict: " + sentence);
            busy = false;
            if (mine != session) return;
            handler.post(() -> {
                status.setText(sentence);
                body.setText(all.toString().trim());
            });
        }, "netcheck").start();
    }

    // MARK: - Measuring

    /** Fills `lines` and returns the one-sentence verdict. */
    private String measure(Context c, List<String> lines, int mine) {
        boolean ru = Ui.ru;
        // 1. Wi-Fi signal (the TV may be on a cable instead).
        int rssi = -127;
        String carrier = "none";
        Network direct = null;
        ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
        Network vpn = null;
        try {
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities nc = cm.getNetworkCapabilities(n);
                if (nc == null) continue;
                if (nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) { vpn = n; continue; }
                if (!nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue;
                direct = n;
                carrier = nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ? "wifi" : nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ? "eth" : "other";
            }
        } catch (RuntimeException ignored) {}
        String gateway = "";
        if (direct != null) {
            LinkProperties lp = cm.getLinkProperties(direct);
            if (lp != null) for (RouteInfo r : lp.getRoutes()) if (r.isDefaultRoute() && r.getGateway() != null && !r.getGateway().isAnyLocalAddress()
                    && r.getGateway() instanceof java.net.Inet4Address) gateway = r.getGateway().getHostAddress();
        }
        String wifiVerdict = "";
        if (carrier.equals("wifi")) {
            WifiManager wm = (WifiManager) c.getSystemService(Context.WIFI_SERVICE);
            WifiInfo wi = wm == null ? null : wm.getConnectionInfo();
            if (wi != null) {
                rssi = wi.getRssi();
                String band = wi.getFrequency() >= 4900 ? "5 ГГц" : "2,4 ГГц";
                String quality = rssi >= -60 ? (ru ? "хороший" : "good") : rssi >= -70 ? (ru ? "средний" : "fair") : (ru ? "слабый" : "weak");
                wifiVerdict = quality;
                lines.add((ru ? "Wi-Fi: сигнал " : "Wi-Fi: signal ") + rssi + " dBm (" + quality + "), " + (ru ? band : band.replace("ГГц", "GHz"))
                        + (ru ? ", скорость соединения " : ", link speed ") + wi.getLinkSpeed() + (ru ? " Мбит/с" : " Mbps"));
            }
        } else if (carrier.equals("eth")) {
            lines.add(ru ? "Подключение: кабель (Ethernet)" : "Connection: cable (Ethernet)");
        } else if (carrier.equals("none")) {
            lines.add(ru ? "Подключения к сети нет." : "No network connection.");
            return ru ? "Нет подключения к сети." : "There is no network connection.";
        }
        if (mine != session) return "";

        // 2. The router and the internet outside the VPN: ping through the TV's own ADB (the app itself is routed into the VPN).
        double routerMs = Double.NaN, netMs = Double.NaN, netLoss = Double.NaN;
        try (Adb adb = Adb.connect(c, 4_000, null)) {
            if (!gateway.isEmpty()) {
                double[] r = ping(adb, gateway, 6);
                routerMs = r[0];
                lines.add((ru ? "Роутер: " : "Router: ") + (Double.isNaN(routerMs) ? (ru ? "не отвечает" : "no answer")
                        : Math.round(routerMs) + (ru ? " мс" : " ms") + (r[1] > 0 ? ", потеряно " + Math.round(r[1]) + "%" : "")));
            }
            double[] r = ping(adb, DNS_PING, 6);
            netMs = r[0];
            netLoss = r[1];
            lines.add((ru ? "Интернет напрямую: " : "Internet, direct: ") + (Double.isNaN(netMs) ? (ru ? "не отвечает" : "no answer")
                    : Math.round(netMs) + (ru ? " мс" : " ms") + (netLoss > 0 ? (ru ? ", потеряно " : ", lost ") + Math.round(netLoss) + "%" : "")));
        } catch (Exception e) {
            lines.add(ru ? "Пинг напрямую недоступен (нужна отладка по USB)." : "Direct ping isn't available (USB debugging is needed).");
        }
        if (mine != session) return "";

        // 3. Through the VPN: delay and download speed.
        double vpnMs = Double.NaN, vpnMbit = Double.NaN;
        if (vpn != null) {
            vpnMs = medianLatency(vpn, 4);
            lines.add((ru ? "Через VPN: задержка " : "Through the VPN: delay ") + (Double.isNaN(vpnMs) ? (ru ? "нет ответа" : "no answer")
                    : Math.round(vpnMs) + (ru ? " мс" : " ms")));
            if (!Double.isNaN(vpnMs)) {
                for (String url : SPEED_URLS) {
                    vpnMbit = downloadMbit(vpn, url, 6_000);
                    if (!Double.isNaN(vpnMbit) || mine != session) break;
                }
                lines.add((ru ? "Скорость через VPN: " : "Speed through the VPN: ") + (Double.isNaN(vpnMbit) ? (ru ? "не удалось измерить" : "couldn't measure")
                        : String.format(java.util.Locale.US, "%.0f", vpnMbit) + (ru ? " Мбит/с" : " Mbps")));
            }
        } else {
            lines.add(ru ? "VPN сейчас выключен." : "The VPN is off now.");
        }

        // 4. One sentence.
        return verdict(ru, rssi, carrier.equals("wifi"), routerMs, netMs, netLoss, vpn != null, vpnMs, vpnMbit);
    }

    private static String verdict(boolean ru, int rssi, boolean wifi, double routerMs, double netMs, double netLoss, boolean vpnOn, double vpnMs, double vpnMbit) {
        if (wifi && rssi < -70) return ru ? "Слабый сигнал Wi-Fi: поднесите роутер ближе или перенесите его." : "The Wi-Fi signal is weak: move the router closer.";
        if (!Double.isNaN(routerMs) && routerMs > 40) return ru ? "Роутер отвечает медленно: проблема в Wi-Fi или в самом роутере." : "The router answers slowly: a Wi-Fi or router problem.";
        if (Double.isNaN(netMs) || netLoss >= 30) return ru ? "Интернет почти не отвечает: проблема у провайдера или у роутера." : "The internet hardly answers: a provider or router problem.";
        if (vpnOn && (Double.isNaN(vpnMs) || vpnMs > 400)) return ru ? "Интернет в порядке, но VPN отвечает плохо: нажмите «Сервер VPN»." : "The internet is fine, but the VPN is poor: try «VPN server».";
        if (vpnOn && !Double.isNaN(vpnMbit) && vpnMbit < 8) return ru ? "Интернет в порядке, но через VPN медленно: нажмите «Сервер VPN»." : "The internet is fine, but the VPN is slow: try «VPN server».";
        if (netLoss > 0 || (wifi && rssi < -62)) return ru ? "В целом нормально, но связь не идеальна." : "Mostly fine, but not perfect.";
        return ru ? "Всё в порядке." : "All good.";
    }

    /** Average ms and loss % of `count` pings from the TV's shell (outside the VPN); NaN when nothing came back. */
    private static double[] ping(Adb adb, String host, int count) throws java.io.IOException {
        String out = adb.shell("ping -c " + count + " -W 2 " + host + " 2>&1");
        // The middle of the answers, not their mean: the first ping after a quiet moment can be slow while the Wi-Fi wakes up.
        List<Double> times = new ArrayList<>();
        Matcher t = Pattern.compile("time=([0-9.]+) ms").matcher(out);
        while (t.find()) times.add(Double.parseDouble(t.group(1)));
        Collections.sort(times);
        double avg = times.isEmpty() ? Double.NaN : times.get(times.size() / 2);
        double loss = 100;
        Matcher l = Pattern.compile("([0-9]+)% packet loss").matcher(out);
        if (l.find()) loss = Double.parseDouble(l.group(1));
        return new double[] {avg, loss};
    }

    /** Median time of a tiny HTTPS request over the network (connect, handshake and answer), ms; NaN when none answered. */
    private static double medianLatency(Network n, int tries) {
        List<Long> times = new ArrayList<>();
        for (int i = 0; i < tries; i++) {
            long t0 = android.os.SystemClock.uptimeMillis();
            try {
                HttpURLConnection h = (HttpURLConnection) n.openConnection(new URL(LATENCY_URL));
                h.setConnectTimeout(5000);
                h.setReadTimeout(5000);
                h.setUseCaches(false);
                int code = h.getResponseCode();
                h.disconnect();
                if (code == 204 || code == 200) times.add(android.os.SystemClock.uptimeMillis() - t0);
            } catch (Exception ignored) {}
        }
        if (times.isEmpty()) return Double.NaN;
        Collections.sort(times);
        return times.get(times.size() / 2);
    }

    /** Download speed over the network in megabits per second, measured for up to `ms`; NaN if it didn't start. */
    private static double downloadMbit(Network n, String url, long ms) {
        try {
            HttpURLConnection h = (HttpURLConnection) n.openConnection(new URL(url));
            h.setConnectTimeout(6000);
            h.setReadTimeout(6000);
            h.setUseCaches(false);
            int code = h.getResponseCode();
            if (code != 200) {
                AppLog.w("network check: speed file " + url + " answered " + code);
                return Double.NaN;
            }
            long started = android.os.SystemClock.uptimeMillis(), bytes = 0;
            byte[] buffer = new byte[65536];
            try (InputStream in = h.getInputStream()) {
                int r;
                while ((r = in.read(buffer)) > 0) {
                    bytes += r;
                    if (android.os.SystemClock.uptimeMillis() - started > ms) break;
                }
            } finally {
                h.disconnect();
            }
            long elapsed = Math.max(1, android.os.SystemClock.uptimeMillis() - started);
            if (bytes < 400_000) {   // an error page or an empty answer, not the test file
                AppLog.w("network check: speed file " + url + " gave only " + bytes + " bytes");
                return Double.NaN;
            }
            return bytes * 8.0 / 1000.0 / elapsed;   // bits per ms = kilobits per s; /1000 = megabits per s
        } catch (Exception e) {
            AppLog.w("network check: speed file " + url + " failed: " + e);
            return Double.NaN;
        }
    }

    // MARK: - Small helpers

    private int px(float v) {
        return Math.round(v * dp);
    }

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (Ui.semibold != null && bold) t.setTypeface(Ui.semibold);
        else if (Ui.medium != null) t.setTypeface(Ui.medium);
        return t;
    }
}
