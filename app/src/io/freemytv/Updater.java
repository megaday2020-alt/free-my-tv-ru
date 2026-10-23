package io.freemytv;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Updates from this project's GitHub releases. It looks for a newer release once a night (and when asked in Settings),
 * remembers it, and installs it only after the person confirms: the APK is downloaded, then installed with the TV's own
 * ADB. Android refuses an APK signed with another key, so a wrong file can't replace the app.
 */
final class Updater {
    private Updater() {}

    static final String REPO = "megaday2020-alt/free-my-tv-ru";
    private static final long MAX_APK = 40L << 20;

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences("updater", Context.MODE_PRIVATE);
    }

    static String installed(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "0";
        }
    }

    /** The newer version found by the last check, or "" when this one is the latest. */
    static String available(Context c) {
        SharedPreferences sp = sp(c);
        String v = sp.getString("version", "");
        return !v.isEmpty() && newer(v, installed(c)) && !sp.getString("url", "").isEmpty() ? v : "";
    }

    static boolean checkedBefore(Context c) {
        return sp(c).getLong("checkedAt", 0) > 0;
    }

    /** After an update started here, a short message that it finished (shown over whatever is on the screen). */
    static void announce(Context c) {
        SharedPreferences sp = sp(c);
        String version = sp.getString("notify", "");
        // The older version that started the update left no note, but its downloaded file stays behind.
        File dir = c.getExternalFilesDir(null);
        File leftover = dir == null ? null : new File(dir, "update.apk");
        if (leftover != null && leftover.exists()) {
            leftover.delete();
            if (version.isEmpty()) version = installed(c);
        }
        if (version.isEmpty()) return;
        sp.edit().remove("notify").apply();
        if (!version.equals(installed(c))) return;
        String lang = Prefs.language();
        boolean ru = lang.equals("ru") || lang.equals("auto") && java.util.Locale.getDefault().getLanguage().equals("ru");
        android.widget.Toast.makeText(c.getApplicationContext(),
                (ru ? "Free My TV обновлён до версии " : "Free My TV updated to version ") + version, android.widget.Toast.LENGTH_LONG).show();
    }

    /** "1.10" is newer than "1.9": compares the dotted numbers one by one. */
    static boolean newer(String a, String b) {
        String[] x = a.replaceAll("[^0-9.]", "").split("\\."), y = b.replaceAll("[^0-9.]", "").split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? num(x[i]) : 0, q = i < y.length ? num(y[i]) : 0;
            if (p != q) return p > q;
        }
        return false;
    }

    private static int num(String s) {
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return 0; }
    }

    /** Asks GitHub for the latest release (background thread). Returns the new version, "" if none, or throws. */
    static String check(Context c) throws Exception {
        JSONObject release = new JSONObject(download("https://api.github.com/repos/" + REPO + "/releases/latest", 1 << 20, null));
        String tag = release.getString("tag_name");
        String url = "";
        JSONArray assets = release.optJSONArray("assets");
        for (int i = 0; assets != null && i < assets.length(); i++) {
            JSONObject a = assets.getJSONObject(i);
            if (a.getString("name").endsWith(".apk")) { url = a.getString("browser_download_url"); break; }
        }
        String version = tag.replaceAll("^[^0-9]*", "").replaceAll("[^0-9.].*$", "");
        sp(c).edit().putString("version", version).putString("url", url).putLong("checkedAt", System.currentTimeMillis()).apply();
        return available(c);
    }

    /** Downloads the new APK and installs it over this app (background thread). The app restarts when it succeeds. */
    static void install(Context c) throws Exception {
        String url = sp(c).getString("url", "");
        if (url.isEmpty() || available(c).isEmpty()) throw new IOException("no update");
        File dir = c.getExternalFilesDir(null);
        if (dir == null) throw new IOException("no storage");
        File apk = new File(dir, "update.apk");
        download(url, MAX_APK, apk);
        try (java.io.RandomAccessFile f = new java.io.RandomAccessFile(apk, "r")) {
            if (apk.length() < 200_000 || f.readByte() != 'P' || f.readByte() != 'K') throw new IOException("the file is not an APK");
        }
        AppLog.w("update: installing " + available(c));
        sp(c).edit().putString("notify", available(c)).apply();   // the restarted app says the update is done
        try (Adb adb = Adb.connect(c, 5_000, null)) {
            String out = adb.shell("pm install -r " + apk.getAbsolutePath());
            AppLog.w("update: " + out.trim());
            if (!out.contains("Success")) throw new IOException(out.trim());
        } finally {
            if (apk.exists()) apk.delete();
        }
    }

    /** Reads a URL into a string (limit bytes), or into `to` when it is given (then returns ""). */
    private static String download(String url, long limit, File to) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10_000);
        c.setReadTimeout(30_000);
        c.setRequestProperty("User-Agent", "FreeMyTV-updater");
        c.setRequestProperty("Accept", "application/vnd.github+json");
        try {
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream mem = to == null ? new ByteArrayOutputStream() : null;
                try (FileOutputStream file = to == null ? null : new FileOutputStream(to)) {
                    byte[] buffer = new byte[16384];
                    long total = 0;
                    int n;
                    while ((n = in.read(buffer)) > 0) {
                        total += n;
                        if (total > limit) throw new IOException("file too large");
                        if (to == null) mem.write(buffer, 0, n); else file.write(buffer, 0, n);
                    }
                }
                return to == null ? mem.toString("UTF-8") : "";
            }
        } finally {
            c.disconnect();
        }
    }
}
