package io.freemytv;

import android.app.Activity;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

/**
 * The "Screensaver" tile and the sleep timer: the same information board the system shows when the TV is idle (InfoView), in
 * an activity of our own. The box stays awake, so any key on any remote brings the home screen back (a real suspend can leave a
 * box whose remote is a 2.4 GHz USB dongle impossible to wake).
 */
public class DarkActivity extends Activity {
    private InfoView view;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        view = new InfoView(this);
        view.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        setContentView(view);
        setBrightness();
        view.onEco = this::setBrightness;   // economy turns the panel down further
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    /** Where the box controls the panel's brightness. */
    private void setBrightness() {
        float brightness = view.panelBrightness();
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = brightness > 0 ? brightness : WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
        getWindow().setAttributes(lp);
    }

    @Override protected void onResume() {
        super.onResume();
        view.start();
    }

    @Override protected void onPause() {
        view.stop();
        super.onPause();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getAction() == KeyEvent.ACTION_DOWN) finish();   // any key wakes it (the key itself is not passed on)
        return true;
    }

    @Override public boolean dispatchTouchEvent(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_DOWN) finish();
        return true;
    }

    @Override public boolean dispatchGenericMotionEvent(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_BUTTON_PRESS) finish();   // the air mouse's click
        return true;
    }
}
