package io.freemytv;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Lets the VPN server picker (VpnServers) read V2RayTun's screens and tap its buttons. Limited by its configuration to
 * that one app; it does nothing on its own: VpnServers uses `instance` when it runs.
 */
public class V2RayService extends AccessibilityService {
    static volatile V2RayService instance;
    private android.view.View banner;
    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());

    /** A small notice at the top of the screen while the picker works; it takes no touches and no focus. */
    static void banner(String text) {
        final V2RayService s = instance;
        if (s == null) return;
        s.main.post(() -> {
            try {
                android.view.WindowManager wm = (android.view.WindowManager) s.getSystemService(WINDOW_SERVICE);
                if (s.banner != null) {
                    wm.removeView(s.banner);
                    s.banner = null;
                }
                if (text == null) return;
                android.widget.TextView t = new android.widget.TextView(s);
                t.setText(text);
                t.setTextColor(0xFFFFFFFF);
                t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 20);
                t.setGravity(android.view.Gravity.CENTER);
                float d = s.getResources().getDisplayMetrics().density;
                t.setPadding((int) (26 * d), (int) (14 * d), (int) (26 * d), (int) (14 * d));
                android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
                bg.setColor(0xE61C1C1E);
                bg.setCornerRadius(18 * d);
                t.setBackground(bg);
                android.view.WindowManager.LayoutParams lp = new android.view.WindowManager.LayoutParams(
                        android.view.WindowManager.LayoutParams.WRAP_CONTENT, android.view.WindowManager.LayoutParams.WRAP_CONTENT,
                        android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                        android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                                | android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                        android.graphics.PixelFormat.TRANSLUCENT);
                lp.gravity = android.view.Gravity.TOP | android.view.Gravity.CENTER_HORIZONTAL;
                lp.y = (int) (28 * d);
                wm.addView(t, lp);
                s.banner = t;
            } catch (RuntimeException e) {
                AppLog.w("v2ray service: banner: " + e.getMessage());
            }
        });
    }

    @Override protected void onServiceConnected() {
        instance = this;
        AppLog.w("v2ray service connected");
    }

    @Override public boolean onUnbind(Intent intent) {
        banner(null);
        instance = null;
        return super.onUnbind(intent);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {}

    @Override public void onInterrupt() {}
}
