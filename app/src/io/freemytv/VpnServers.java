package io.freemytv;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Picks the VPN server in V2RayTun: opens the app, runs its own response-time test ("Время отклика конфигов"), reads the
 * list (names and times) through the accessibility service, chooses by the rules below and taps that server. It only
 * runs when nobody is using the box (or when asked, or when the VPN could not be brought back), and it puts the screen
 * back where it was.
 *
 * Rules: an "Auto" server under 80 ms wins (the lowest). If the Auto ones are slower, any country comes next (or a slow
 * Auto one; Russia and the torrent server are never tried), and the servers with limited traffic (Gemini/"AI", "Белые списки") last. If everything
 * is much slower than usual, the problem is the internet and not the server: nothing changes.
 */
final class VpnServers {
    private VpnServers() {}

    static final String V2 = "com.v2raytun.android";
    private static final String SERVICE = HomeMode.PKG + "/" + HomeMode.PKG + ".V2RayService";
    private static final Pattern SERVICE_NAME = Pattern.compile("[\\w.]+/[\\w.$]+");
    private static final Pattern PING = Pattern.compile("^\\s*(\\?|\\d{1,5})\\s*(?:ms|m)?\\s*$");
    private static final Pattern PROTOCOL = Pattern.compile("^[A-Za-z0-9-]+ / [A-Za-z0-9-]+$");
    private static final int AUTO_GOOD = 80, OTHER_OK = 200, SWITCH_MARGIN = 25;
    private static final int NONE = -2, DEAD = -1;
    private static final long RANKING_FRESH_MS = 12 * 3_600_000L;
    private static boolean running, debug;

    enum Group { AUTO, COUNTRY, LIMITED, OTHER }

    static final class Row {
        String title = "", sub = "";
        int ping = NONE;
        boolean selected;
        Rect rect = new Rect();
        AccessibilityNodeInfo info;
        Group group() {
            String t = title;
            if (t.contains("Russia") || t.contains("Торрент")) return Group.OTHER;   // never tried: Russia, torrents
            if (t.startsWith("Auto") && t.contains("Оптимальн")) return Group.AUTO;
            if (t.contains("Белые списки") || t.contains("Gemini") || t.matches("^\\W*AI\\W*$") || t.contains("🤖")) return Group.LIMITED;
            if (t.contains("→")) return Group.OTHER;   // servers made for one task (YouTube without ads, torrents, state services)
            return Group.COUNTRY;
        }
        boolean valid() { return ping > 0; }
        String key() { return title + "|" + sub; }
    }

    // MARK: - The slow timer: every few hours, when nobody is using the box

    static final String ACTION = "io.freemytv.VPN_SERVERS";
    private static final long EVERY = 3 * 3_600_000L;

    static void schedule(Context c) {
        android.app.AlarmManager am = (android.app.AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        android.app.PendingIntent pi = android.app.PendingIntent.getBroadcast(c, 82,
                new android.content.Intent(c, HomeReceiver.class).setAction(ACTION),
                android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);
        if (!Prefs.serverPick()) {
            am.cancel(pi);
            return;
        }
        long at = System.currentTimeMillis() + EVERY;
        try {
            am.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, at, pi);
        } catch (SecurityException e) {
            am.setAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, at, pi);
        }
    }

    // MARK: - Entry points (from ModeJob)

    /** mode: "auto" (idle only, the slow timer), "night" (also refresh the subscriptions), "manual" (the button), "backup" (the VPN is down), "quick" (the saved results, no new test). */
    static void run(Context c, String mode) {
        synchronized (VpnServers.class) {
            if (running) return;
            running = true;
        }
        AppLog.w("vpn servers: start, mode " + mode);
        try {
            runLocked(c, mode);
        } catch (Throwable t) {
            AppLog.w("vpn servers: failed: " + t);
        } finally {
            synchronized (VpnServers.class) { running = false; }
        }
    }

    private static void runLocked(Context c, String mode) throws Exception {
        boolean forced = mode.equals("manual") || mode.equals("backup") || mode.equals("debug") || mode.equals("quick");
        debug = mode.equals("debug");
        if (!forced && !Prefs.serverPick()) return;
        try {
            c.getPackageManager().getPackageInfo(V2, 0);
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            return;   // no V2RayTun on this device: nothing to pick
        }
        try (Adb adb = Adb.connect(c, 10_000, null)) {
            sh = adb;
            if (V2.equals(topPackage(adb))) {   // the user is in V2RayTun right now: never fight over its screen
                AppLog.w("vpn servers: skipped, V2RayTun is already open");
                return;
            }
            if (!forced && !idle(adb)) {
                AppLog.w("vpn servers: skipped, the box is in use");
                return;
            }
            if (!ensureService(adb)) return;
            String before = topPackage(adb);
            adb.shell("am start -n " + V2 + "/.ui.activity.MainActivity");
            if (!waitFor(() -> root() != null && V2.equals(String.valueOf(root().getPackageName())), 10_000)) {
                AppLog.w("vpn servers: V2RayTun did not come up");
                back(adb, before);
                return;
            }
            SystemClock.sleep(1500);
            debugDump("V2RayTun opened");
            try {
                List<Row> rows;
                if (debug) {   // only look: scroll through the list and say what was seen
                    dump(collect());
                    return;
                }
                SharedPreferences sp = c.getSharedPreferences("vpnservers", Context.MODE_PRIVATE);
                String current = sp.getString("current", "");
                if (mode.equals("night")) {
                    if (openMenuAndClick("Обновить подписки")) {
                        AppLog.w("vpn servers: subscriptions refresh asked");
                        SystemClock.sleep(15_000);
                    }
                }
                rows = loadRanking(sp);
                if (rows == null || !(mode.equals("backup") || mode.equals("quick"))) {
                    if (openMenuAndClick("Время отклика конфигов")) {
                        waitForTest();
                    } else {
                        AppLog.w("vpn servers: the test button was not found");
                    }
                    rows = collect();
                    saveRanking(sp, rows);
                }
                dump(rows);
                Row selected = null;
                for (Row r : rows) if (r.selected) selected = r;
                if (selected != null) current = selected.title;
                Row choice = pick(rows, current, mode.equals("backup") ? current : "", sp);
                if (choice == null) {
                    AppLog.w("vpn servers: no change (" + why + ")");
                } else {
                    AppLog.w("vpn servers: choosing \"" + choice.title + "\" " + choice.ping + " ms (" + why + ")");
                    if (select(choice)) {
                        sp.edit().putString("current", choice.title).apply();
                        SystemClock.sleep(6_000);
                        AppLog.w("vpn servers: VPN is " + (VpnGuard.vpnUp(c) ? "up" : "DOWN") + " after the switch");
                    }
                }
            } finally {
                back(adb, before);
            }
        }
    }

    // MARK: - The decision

    private static String why = "";

    /** The server to switch to, or null to leave things as they are. `avoid` is a server that just failed. */
    private static Row pick(List<Row> rows, String current, String avoid, SharedPreferences sp) {
        List<Row> ok = new ArrayList<>();
        for (Row r : rows) if (r.valid() && !r.title.equals(avoid) && r.group() != Group.OTHER) ok.add(r);
        Row bestAuto = best(ok, Group.AUTO), bestCountry = best(ok, Group.COUNTRY), bestLimited = best(ok, Group.LIMITED);
        Row bestOpen = lower(bestAuto, bestCountry, null);
        float baseline = sp.getFloat("baseline", 0);
        if (bestOpen != null && bestOpen.ping < 400) sp.edit().putFloat("baseline", baseline == 0 ? bestOpen.ping : 0.8f * baseline + 0.2f * bestOpen.ping).apply();
        Row choice;
        if (bestAuto != null && bestAuto.ping < AUTO_GOOD) {
            choice = bestAuto;
            why = "Auto under " + AUTO_GOOD + " ms";
        } else if (bestOpen != null && baseline > 0 && bestOpen.ping > 2.2f * baseline && bestOpen.ping > 150 && avoid.isEmpty()) {
            why = "everything is slow (best " + bestOpen.ping + " ms, usual ~" + Math.round(baseline) + " ms): the internet, not the server";
            return null;
        } else if (lower(bestAuto, bestCountry, null) != null && lower(bestAuto, bestCountry, null).ping <= OTHER_OK) {
            choice = lower(bestAuto, bestCountry, null);
            why = "best of the others";
        } else if (bestLimited != null) {
            choice = bestLimited;
            why = "only limited-traffic servers answer";
        } else if (bestOpen != null) {
            choice = bestOpen;
            why = "slow but the only ones";
        } else {
            why = "nothing answers";
            return null;
        }
        // Don't hop for a few milliseconds: stay on a server that is nearly as good.
        for (Row r : rows) {
            if (r.title.equals(current) && r.valid() && r.group() != Group.LIMITED && choice.group() != Group.LIMITED
                    && r.ping <= choice.ping + SWITCH_MARGIN && avoid.isEmpty()) {
                why = "the current one (" + r.ping + " ms) is close enough to " + choice.ping + " ms";
                return null;
            }
        }
        return choice.title.equals(current) && avoid.isEmpty() ? null : choice;
    }

    private static Row best(List<Row> rows, Group g) {
        Row b = null;
        for (Row r : rows) if (r.group() == g && (b == null || r.ping < b.ping)) b = r;
        return b;
    }

    private static Row lower(Row a, Row b, Row c) {
        Row best = null;
        for (Row r : new Row[] {a, b, c}) if (r != null && (best == null || r.ping < best.ping)) best = r;
        return best;
    }

    // MARK: - Idle check and the accessibility service

    private static boolean idle(Adb adb) throws java.io.IOException {
        String top = adb.shell("dumpsys activity activities | grep mResumedActivity");
        if (!top.contains(HomeMode.PKG + "/")) return false;   // another app is in front, even paused
        String sessions = adb.shell("dumpsys media_session | grep -E 'state=PlaybackState'");
        return !sessions.contains("state=3,");                  // 3 = playing
    }

    private static String topPackage(Adb adb) throws java.io.IOException {
        String top = adb.shell("dumpsys activity activities | grep mResumedActivity");
        Matcher m = Pattern.compile("u0 ([\\w.]+)/").matcher(top);
        return m.find() ? m.group(1) : "";
    }

    /** Puts the screen back: Back until the app that was in front is in front again (then, failing that, Home). */
    private static void back(Adb adb, String packageBefore) throws java.io.IOException {
        for (int i = 0; i < 4; i++) {
            String top = topPackage(adb);
            if (!top.equals(V2)) return;
            adb.shell("input keyevent 4");
            SystemClock.sleep(900);
        }
        if (topPackage(adb).equals(V2)) adb.shell("am start -a android.intent.action.MAIN -c android.intent.category.HOME");
    }

    /** Switches our accessibility service on (keeping the others) and waits until the system has started it. */
    private static boolean ensureService(Adb adb) throws java.io.IOException {
        if (V2RayService.instance != null) return true;
        String current = adb.shell("settings get secure enabled_accessibility_services").trim();
        if (current.equals("null")) current = "";
        List<String> list = new ArrayList<>();
        for (String one : current.split(":")) {
            if (one.isEmpty()) continue;
            if (!SERVICE_NAME.matcher(one).matches()) {
                AppLog.w("vpn servers: accessibility services left as they are: unexpected entry");
                return false;
            }
            if (!one.equals(SERVICE)) list.add(one);
        }
        list.add(SERVICE);
        adb.shell("settings put secure enabled_accessibility_services '" + String.join(":", list) + "'");
        adb.shell("settings put secure accessibility_enabled 1");
        boolean up = waitFor(() -> V2RayService.instance != null, 10_000);
        if (!up) AppLog.w("vpn servers: the accessibility service did not start");
        return up;
    }

    // MARK: - Reading and tapping V2RayTun's screens

    private interface Condition { boolean ok(); }

    private static boolean waitFor(Condition c, long timeoutMs) {
        long end = SystemClock.uptimeMillis() + timeoutMs;
        while (SystemClock.uptimeMillis() < end) {
            try { if (c.ok()) return true; } catch (RuntimeException ignored) {}
            SystemClock.sleep(400);
        }
        return false;
    }

    private static AccessibilityNodeInfo root() {
        V2RayService s = V2RayService.instance;
        return s == null ? null : s.getRootInActiveWindow();
    }

    private static final class N {
        String text = "";
        Rect r = new Rect();
        boolean selected, scrollable, clickable;
        String cls = "";
        AccessibilityNodeInfo info;
    }

    private static void walk(AccessibilityNodeInfo n, boolean inheritedSelected, List<N> out, int depth) {
        if (n == null || depth > 40) return;
        boolean sel = inheritedSelected || n.isSelected() || n.isChecked();
        CharSequence t = n.getText();
        if (n.isVisibleToUser() && ((t != null && t.length() > 0) || n.isScrollable())) {
            N x = new N();
            x.text = t == null ? "" : t.toString();
            n.getBoundsInScreen(x.r);
            x.selected = sel;
            x.scrollable = n.isScrollable();
            x.clickable = n.isClickable();
            x.cls = String.valueOf(n.getClassName());
            x.info = n;
            out.add(x);
        }
        for (int i = 0; i < n.getChildCount(); i++) walk(n.getChild(i), sel, out, depth + 1);
    }

    private static List<N> nodes() {
        List<N> out = new ArrayList<>();
        walk(root(), false, out, 0);
        return out;
    }

    private static int pingOf(String text) {
        Matcher m = PING.matcher(text);
        if (!m.matches()) return NONE;
        return m.group(1).equals("?") ? DEAD : Integer.parseInt(m.group(1));
    }

    /** The rows of the server list that are on the screen now. */
    private static List<Row> rowsOnScreen() {
        List<N> all = nodes();
        List<Row> rows = new ArrayList<>();
        for (N s : all) {
            if (!PROTOCOL.matcher(s.text.trim()).matches()) continue;
            N title = null;
            for (N t : all) {
                if (t == s || t.text.isEmpty() || pingOf(t.text) != NONE || PROTOCOL.matcher(t.text.trim()).matches()) continue;
                if (Math.abs(t.r.left - s.r.left) > 14 || t.r.bottom > s.r.top + 8) continue;
                if (title == null || t.r.bottom > title.r.bottom) title = t;
            }
            if (title == null) continue;
            Row row = new Row();
            row.title = title.text.trim();
            row.sub = s.text.trim();
            row.selected = title.selected;
            row.rect.set(title.r);
            row.info = title.info;
            for (N p : all) {
                int v = pingOf(p.text);
                if (v == NONE || p.r.left <= s.r.right) continue;
                if (p.r.centerY() >= title.r.top - 40 && p.r.centerY() <= s.r.bottom + 6) {
                    row.ping = v;
                    break;
                }
            }
            rows.add(row);
        }
        return rows;
    }

    /** The shell used for swipes (the accessibility scroll action does not move V2RayTun's list). */
    private static Adb sh;

    /** One swipe on the server list: forward = towards the bottom of the list. Returns false when the shell failed. */
    private static boolean scroll(boolean forward) {
        try {
            sh.shell(forward ? "input swipe 430 640 430 240 450" : "input swipe 430 240 430 640 250");
            SystemClock.sleep(forward ? 600 : 350);
            return true;
        } catch (java.io.IOException e) {
            return false;
        }
    }

    private static void toTop() {
        for (int i = 0; i < 14 && scroll(false); i++) {}
        SystemClock.sleep(400);
    }

    /** The whole list, top to bottom (a row keeps the newest time it was seen with). */
    private static List<Row> collect() {
        toTop();
        SystemClock.sleep(500);
        Map<String, Row> map = new LinkedHashMap<>();
        int still = 0;
        for (int i = 0; i < 30 && still < 2; i++) {
            boolean added = false;
            for (Row r : rowsOnScreen()) {
                Row old = map.get(r.key());
                if (old == null) {
                    map.put(r.key(), r);
                    added = true;
                } else if (r.ping != NONE) {
                    old.ping = r.ping;
                    old.selected |= r.selected;
                }
            }
            if (debug) {
                List<Row> now = rowsOnScreen();
                AppLog.w("vpn servers: collect step " + i + ": " + now.size() + " rows on screen, first=" + (now.isEmpty() ? "-" : now.get(0).title) + ", last=" + (now.isEmpty() ? "-" : now.get(now.size() - 1).title) + ", added=" + added);
            }
            still = added ? 0 : still + 1;
            if (!scroll(true)) break;
        }
        return new ArrayList<>(map.values());
    }

    private static boolean clickNode(AccessibilityNodeInfo n) {
        for (AccessibilityNodeInfo p = n; p != null; p = p.getParent()) {
            if (p.isClickable()) return p.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        }
        return false;
    }

    private static N findText(String text) {
        for (N n : nodes()) if (n.text.trim().equals(text)) return n;
        return null;
    }

    /** Opens the "Управление" panel and taps one of its items. */
    private static boolean openMenuAndClick(String item) {
        toTop();
        N menu = findText("Управление");
        if (menu == null || !clickNode(menu.info)) {
            AppLog.w("vpn servers: the \"Управление\" button was not found");
            return false;
        }
        waitFor(() -> findText("Импорт из файла") != null, 4_000);   // the panel is up
        // The panel is a list taller than the screen: swipe it until the item is on the screen.
        for (int i = 0; i < 4 && findText(item) == null; i++) {
            try {
                sh.shell("input swipe 640 640 640 220 450");
            } catch (java.io.IOException e) {
                break;
            }
            SystemClock.sleep(700);
        }
        if (findText(item) == null) {
            AppLog.w("vpn servers: \"" + item + "\" was not found in the panel");
            debugDump("panel without " + item);
            back2();
            return false;
        }
        boolean ok = clickNode(findText(item).info);
        SystemClock.sleep(800);
        return ok;
    }

    /** What the screen holds (for finding out why a step failed). */
    private static void debugDump(String why) {
        List<N> all = nodes();
        StringBuilder sb = new StringBuilder("vpn servers: screen dump (" + why + "), " + all.size() + " nodes, window=" + (root() == null ? "none" : root().getPackageName()));
        int n = 0;
        for (N x : all) {
            if (++n > 70) break;
            sb.append("\n  ").append(x.cls.substring(x.cls.lastIndexOf('.') + 1)).append(x.scrollable ? " SCROLL" : "").append(x.clickable ? " CLICK" : "")
                    .append(" [").append(x.r.left).append(',').append(x.r.top).append(',').append(x.r.right).append(',').append(x.r.bottom).append("] ").append(x.text);
        }
        AppLog.w(sb.toString());
    }

    private static void back2() {
        V2RayService s = V2RayService.instance;
        if (s != null) s.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);
    }

    /** Waits for the response-time test to settle: the times on the screen stop changing. */
    private static void waitForTest() {
        long start = SystemClock.uptimeMillis();
        String last = "";
        long stableSince = start;
        while (SystemClock.uptimeMillis() - start < 120_000) {
            SystemClock.sleep(3_000);
            StringBuilder sb = new StringBuilder();
            for (Row r : rowsOnScreen()) sb.append(r.title).append('=').append(r.ping).append(';');
            String now = sb.toString();
            if (!now.equals(last)) {
                last = now;
                stableSince = SystemClock.uptimeMillis();
            }
            if (SystemClock.uptimeMillis() - start >= 15_000 && SystemClock.uptimeMillis() - stableSince >= 9_000) return;
        }
    }

    /** Taps the server's row (scrolling to it first). */
    private static boolean select(Row choice) {
        toTop();
        for (int i = 0; i < 30; i++) {
            for (Row r : rowsOnScreen()) {
                if (r.key().equals(choice.key())) {
                    boolean ok = r.info != null && clickNode(r.info);
                    SystemClock.sleep(800);
                    return ok;
                }
            }
            if (!scroll(true)) break;
        }
        AppLog.w("vpn servers: the row \"" + choice.title + "\" was not found to tap");
        return false;
    }

    // MARK: - Saved results (so a backup can be chosen at once when the VPN is down)

    private static void saveRanking(SharedPreferences sp, List<Row> rows) {
        try {
            JSONArray a = new JSONArray();
            for (Row r : rows) a.put(new JSONObject().put("t", r.title).put("s", r.sub).put("p", r.ping).put("c", r.selected));
            sp.edit().putString("ranking", a.toString()).putLong("rankingAt", System.currentTimeMillis()).apply();
        } catch (Exception e) {
            AppLog.w("vpn servers: ranking not saved: " + e);
        }
    }

    private static List<Row> loadRanking(SharedPreferences sp) {
        try {
            if (System.currentTimeMillis() - sp.getLong("rankingAt", 0) > RANKING_FRESH_MS) return null;
            JSONArray a = new JSONArray(sp.getString("ranking", "[]"));
            if (a.length() == 0) return null;
            List<Row> rows = new ArrayList<>();
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Row r = new Row();
                r.title = o.getString("t");
                r.sub = o.optString("s");
                r.ping = o.optInt("p", NONE);
                r.selected = o.optBoolean("c");
                rows.add(r);
            }
            return rows;
        } catch (Exception e) {
            return null;
        }
    }

    private static void dump(List<Row> rows) {
        StringBuilder sb = new StringBuilder("vpn servers: ").append(rows.size()).append(" rows:");
        for (Row r : rows) sb.append("\n  ").append(r.selected ? "* " : "  ").append(r.group()).append(' ').append(r.ping).append(" ms  ").append(r.title);
        AppLog.w(sb.toString());
    }
}
