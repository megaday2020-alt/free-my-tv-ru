package io.freemytv;

import android.app.Application;

/** Gives Prefs a context in every process, and makes a crash leave a line in the log. */
public class App extends Application {
    @Override public void onCreate() {
        super.onCreate();
        Prefs.init(this);
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            StringBuilder sb = new StringBuilder("CRASH in thread " + thread.getName() + ": " + error);
            StackTraceElement[] frames = error.getStackTrace();
            for (int i = 0; i < Math.min(5, frames.length); i++) sb.append(" | ").append(frames[i]);
            AppLog.w(sb.toString());
            if (previous != null) previous.uncaughtException(thread, error);
        });
        AppLog.w("process started, version " + Updater.installed(this));
    }
}
