package io.freemytv;

import android.app.Application;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/**
 * The app's log: logcat (adb logcat -s FreeMyTV) and a file that keeps days of history, which logcat on a TV never does.
 * The file is events.log in the app's folder (adb pull /sdcard/Android/data/io.freemytv/files/events.log), about 300 KB,
 * then it rolls over to events.old.log. Every process writes to it, each line starting with the time and the process.
 * Never logs keys.
 */
final class AppLog {
    private AppLog() {}

    private static final long MAX_BYTES = 300_000;
    private static final long SAME_FOR_MS = 10 * 60_000L;   // the same line again within ten minutes is written once
    private static final SimpleDateFormat TIME = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US);
    private static String last = "";
    private static long lastAt;
    private static String process;

    static void w(String message) {
        Log.i("FreeMyTV", message);
        if (message.startsWith("shader unavailable") || message.startsWith("vpn probe: YouTube answers")) return;   // every few minutes, nothing to learn
        try {
            write(message);
        } catch (Exception ignored) {}   // the log must never be the thing that fails
    }

    private static synchronized void write(String message) throws IOException {
        File file = file(false);
        if (file == null) return;
        long now = System.currentTimeMillis();
        if (message.equals(last) && now - lastAt < SAME_FOR_MS) return;
        last = message;
        lastAt = now;
        if (file.length() > MAX_BYTES) {
            File old = file(true);
            //noinspection ResultOfMethodCallIgnored
            if (old != null) { old.delete(); file.renameTo(old); }
        }
        try (FileWriter out = new FileWriter(file, true)) {
            out.write(TIME.format(new Date(now)) + " [" + process() + "] " + message.replace('\n', ' ') + "\n");
        }
    }

    private static String process() {
        if (process == null) {
            String name = Application.getProcessName();
            int colon = name == null ? -1 : name.indexOf(':');
            process = name == null ? "?" : colon >= 0 ? name.substring(colon + 1) : "main";
        }
        return process;
    }

    /** events.log (or events.old.log) in the app's folder on the shared storage, readable over adb; the private folder if it is missing. */
    private static File file(boolean old) {
        android.content.Context c = Prefs.context();
        if (c == null) return null;
        File dir = c.getExternalFilesDir(null);
        if (dir == null) dir = c.getFilesDir();
        return new File(dir, old ? "events.old.log" : "events.log");
    }

    /** The last `count` lines, oldest first (for the settings screen). */
    static synchronized String tail(int count) {
        File file = file(false);
        if (file == null || !file.isFile()) return "";
        ArrayDeque<String> lines = new ArrayDeque<>();
        try (BufferedReader r = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = r.readLine()) != null) {
                lines.addLast(line);
                if (lines.size() > count) lines.removeFirst();
            }
        } catch (IOException e) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String s : lines) sb.append(s).append('\n');
        return sb.toString();
    }
}
