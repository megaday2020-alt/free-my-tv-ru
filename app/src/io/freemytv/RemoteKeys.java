package io.freemytv;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.view.InputDevice;
import android.view.KeyEvent;

/**
 * The remote's app buttons on TCL TVs. The stock home screen opens the apps for Netflix, YouTube and Prime (and TCL
 * Channel); with it off, the keys still arrive, so we open the app the person picked (Settings > Remote Buttons).
 * Disney and Globoplay are opened by the system itself, so they are not listed. The YouTube button (BUTTON_3) never
 * reaches an app's window; only the accessibility service's key filter (RemoteKeyService) sees it.
 * On Vestel TVs only the Netflix button is ours: the system opens Netflix itself (a global key, after the filter).
 */
final class RemoteKeys {
    private RemoteKeys() {}

    /** Some TCL remotes (BT_RC833) send TCL's own YouTube key instead of BUTTON_3: the same button. */
    private static final int KEY_YOUTUBE_TCL = 4065;

    /**
     * The key codes are the brand's own: on other brands they can be other buttons, so nothing here runs there.
     * Vestel builds TVs sold under other brands (F&U...), so BRAND varies: check the maker too.
     */
    static final boolean TCL = is("TCL"), VESTEL = is("Vestel"), XIAOMI = is("Xiaomi");

    /**
     * Xiaomi's six app buttons (Ivi, Okko, Kion, VK Video, Rutube, Wink) arrive as BUTTON_1..16, which the TV hands to its own
     * key receiver after the key filter. Value: a package to open instead, or NONE to swallow the key; a key not listed here
     * is left to the system (so the VK Video button keeps working). While DISCOVER is on, every one of them is swallowed and
     * logged ("xiaomi remote key"), to find out which code is which button without launching anything.
     */
    static final String NONE = "none";
    static final boolean DISCOVER = false;
    private static final java.util.Map<Integer, String> XIAOMI_KEYS = new java.util.HashMap<>();
    static {   // codes read from this remote's log (Mi TV MOOQ1): 188 is VK Video, left to the system
        XIAOMI_KEYS.put(199, "org.smarttube.stable");    // Ivi
        XIAOMI_KEYS.put(200, "com.kinopub");             // Okko
        XIAOMI_KEYS.put(203, "com.v2raytun.android");    // Rutube
        XIAOMI_KEYS.put(197, "ru.kinopoisk.tv");         // Wink
        XIAOMI_KEYS.put(198, NONE);                      // Kion: nothing
    }

    static boolean supported() {
        return TCL || VESTEL;
    }

    /** The key service also runs on Xiaomi, which has no Remote Buttons screen (its mapping is in this file). */
    static boolean serviceNeeded() {
        return supported() || XIAOMI;
    }

    private static boolean is(String maker) {
        return maker.equalsIgnoreCase(Build.MANUFACTURER) || maker.equalsIgnoreCase(Build.BRAND);
    }

    /** Opens the app for this key on its first press. True if the key is ours and in use (it is then consumed). */
    static boolean onKey(Context c, KeyEvent e) {
        if (XIAOMI) return xiaomiKey(c, e);
        if (!supported()) return false;
        // BUTTON_3 is also a game controller's button: keys from a gamepad or a joystick are left alone.
        int source = e.getSource();
        if ((source & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (source & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) return false;
        int keyCode = e.getKeyCode() == KEY_YOUTUBE_TCL ? Prefs.KEY_YOUTUBE : e.getKeyCode();
        boolean ours = false;
        for (int key : Prefs.KEYS) ours |= key == keyCode;
        if (!ours) return false;
        String pkg = Prefs.keyApp(keyCode);
        if (pkg.isEmpty()) return false;   // "Nothing": the key goes on as if we weren't here
        if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
            Intent intent = launchIntent(c.getPackageManager(), pkg);
            AppLog.w("remote key " + e.getKeyCode() + " -> " + pkg + (intent == null ? " (not installed)" : ""));
            if (intent != null) c.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
        return true;
    }

    private static boolean xiaomiKey(Context c, KeyEvent e) {
        int code = e.getKeyCode();
        if (code < KeyEvent.KEYCODE_BUTTON_1 || code > KeyEvent.KEYCODE_BUTTON_16) return false;
        int source = e.getSource();
        if ((source & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (source & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) return false;
        boolean first = e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0;
        if (DISCOVER) {
            if (first) AppLog.w("xiaomi remote key " + code + " (scan " + e.getScanCode() + ")");
            return true;
        }
        String target = XIAOMI_KEYS.get(code);
        if (target == null) return false;
        if (first && !target.equals(NONE)) {
            Intent intent = launchIntent(c.getPackageManager(), target);
            AppLog.w("xiaomi remote key " + code + " -> " + target + (intent == null ? " (not installed)" : ""));
            if (intent != null) c.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
        return true;
    }

    /** The app's TV launcher, or its plain one (kept here, not in HomeApps, so the key process stays small). */
    private static Intent launchIntent(PackageManager pm, String pkg) {
        Intent intent = pm.getLeanbackLaunchIntentForPackage(pkg);
        return intent != null ? intent : pm.getLaunchIntentForPackage(pkg);
    }
}
