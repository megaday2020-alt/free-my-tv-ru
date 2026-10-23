package io.freemytv;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextClock;

import java.util.Random;

/**
 * Night clock: a dim clock and date on a black screen that stands in for "sleep". The picture is nearly dark, but the
 * box stays awake, so any key on any remote brings the home screen back (a real suspend can leave a box whose remote is a
 * 2.4 GHz USB dongle impossible to wake). The clock moves a little every minute so it never burns into the panel.
 */
public class DarkActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private LinearLayout box;
    private FrameLayout root;

    private final Runnable drift = new Runnable() {
        @Override public void run() {
            int w = root.getWidth(), h = root.getHeight();
            if (w > 0 && box.getWidth() > 0) {
                float maxX = Math.max(0, (w - box.getWidth()) / 2f), maxY = Math.max(0, (h - box.getHeight()) / 2f);
                box.setTranslationX((random.nextFloat() * 2 - 1) * maxX * 0.8f);
                box.setTranslationY((random.nextFloat() * 2 - 1) * maxY * 0.8f);
            }
            handler.postDelayed(this, 60_000);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        root.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);

        box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);

        TextClock clock = new TextClock(this);
        clock.setFormat24Hour("HH:mm");
        clock.setFormat12Hour("HH:mm");
        clock.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, 150);
        clock.setTextColor(0xFF4A4A4F);   // dim on purpose
        clock.setIncludeFontPadding(false);
        box.addView(clock);

        TextClock date = new TextClock(this);
        date.setFormat24Hour("EEEE, d MMMM");
        date.setFormat12Hour("EEEE, d MMMM");
        date.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        date.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        date.setTextColor(0xFF38383C);
        date.setGravity(Gravity.CENTER_HORIZONTAL);
        box.addView(date);

        root.addView(box, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        setContentView(root);

        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = 0.05f;   // where the box controls the panel's brightness
        getWindow().setAttributes(lp);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    @Override protected void onResume() {
        super.onResume();
        handler.postDelayed(drift, 60_000);
    }

    @Override protected void onPause() {
        handler.removeCallbacks(drift);
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
