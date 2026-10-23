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

    @Override protected void onServiceConnected() {
        instance = this;
        AppLog.w("v2ray service connected");
    }

    @Override public boolean onUnbind(Intent intent) {
        instance = null;
        return super.onUnbind(intent);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {}

    @Override public void onInterrupt() {}
}
