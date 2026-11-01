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
import android.widget.TextView;

import java.util.List;

import java.util.Random;

/**
 * Screensaver (the dim "night clock"): a clock, the date, the weather and a headline from the news on a black screen that
 * stands in for "sleep". Each part has its own switch in Settings, and so does how bright it is. The picture is nearly dark, but the
 * box stays awake, so any key on any remote brings the home screen back (a real suspend can leave a box whose remote is a
 * 2.4 GHz USB dongle impossible to wake). The clock moves a little every minute so it never burns into the panel.
 */
public class DarkActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private LinearLayout box;
    private FrameLayout root;
    private TextView weatherText, newsSource, newsText;
    private int shown;
    // Text colors and the panel brightness, by level: dim, medium, bright.
    private static final int[] CLOCK = {0xFF4A4A4F, 0xFF7C7C84, 0xFFB4B4BC}, DATE = {0xFF38383C, 0xFF63636B, 0xFF9A9AA2},
            INFO = {0xFF3E3E43, 0xFF6C6C74, 0xFFA4A4AC}, FAINT = {0xFF2E2E33, 0xFF525259, 0xFF84848C};
    private static final float[] PANEL = {0.05f, 0.15f, 0.35f};
    private static final long NEXT_HEADLINE = 25_000;

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

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            showWeather();
            if (newsText != null) nextHeadline();
            handler.postDelayed(this, NEXT_HEADLINE);
        }
    };

    private final Runnable tickWeather = new Runnable() {
        @Override public void run() {
            showWeather();
            handler.postDelayed(this, 60_000);
        }
    };

    private void showWeather() {
        if (weatherText == null) return;
        HomeData.Weather w = HomeData.get(this).weather;
        if (Double.isNaN(w.temp)) {
            weatherText.setText("");
            return;
        }
        StringBuilder sb = new StringBuilder(HomeData.degrees(w.temp)).append("  ").append(HomeData.condition(w.code, w.isDay));
        if (!Double.isNaN(w.dayTemp)) sb.append(Ui.ru ? "  ·  днём " : "  ·  day ").append(HomeData.degrees(w.dayTemp));
        String wind = HomeData.windSpeedText(w);
        if (!wind.isEmpty()) sb.append("  ·  ").append(wind);
        weatherText.setText(sb.toString());
    }

    /** The next headline, faded in (and the first one at once). */
    private void nextHeadline() {
        List<String> list = NewsFeed.saved(this, Prefs.newsSource());
        if (list.isEmpty()) {
            newsText.setText("");
            newsSource.setText("");
            return;
        }
        final String line = list.get(shown++ % list.size());
        final String source = NewsFeed.NAMES[Prefs.newsSource()];
        newsText.animate().alpha(0f).setDuration(400).withEndAction(() -> {
            newsSource.setText(source);
            newsText.setText(line);
            newsText.animate().alpha(1f).setDuration(600).start();
        }).start();
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        root.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);

        box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);

        final int level = Prefs.saverBrightness();
        if (Prefs.saverClock()) {
            TextClock clock = new TextClock(this);
            clock.setFormat24Hour("HH:mm");
            clock.setFormat12Hour("HH:mm");
            clock.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
            clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, 150);
            clock.setTextColor(CLOCK[level]);   // dim on purpose
            clock.setIncludeFontPadding(false);
            box.addView(clock);
        }

        if (Prefs.saverDate()) {
            TextClock date = new TextClock(this);
            date.setFormat24Hour("EEEE, d MMMM");
            date.setFormat12Hour("EEEE, d MMMM");
            date.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            date.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
            date.setTextColor(DATE[level]);
            date.setGravity(Gravity.CENTER_HORIZONTAL);
            box.addView(date);
        }

        if (Prefs.saverWeather()) {
            weatherText = new TextView(this);
            weatherText.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            weatherText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
            weatherText.setTextColor(INFO[level]);
            weatherText.setGravity(Gravity.CENTER_HORIZONTAL);
            weatherText.setPadding(0, dp(Prefs.saverDate() || Prefs.saverClock() ? 28 : 0), 0, 0);
            box.addView(weatherText);
        }

        if (Prefs.saverNews()) {
            int width = Math.round(getResources().getDisplayMetrics().widthPixels * 0.62f);
            newsSource = new TextView(this);
            newsSource.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
            newsSource.setTextColor(FAINT[level]);
            newsSource.setGravity(Gravity.CENTER_HORIZONTAL);
            newsSource.setPadding(0, dp(40), 0, dp(6));
            box.addView(newsSource, new LinearLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT));
            newsText = new TextView(this);
            newsText.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            newsText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
            newsText.setTextColor(INFO[level]);
            newsText.setGravity(Gravity.CENTER_HORIZONTAL);
            newsText.setLineSpacing(0, 1.15f);
            box.addView(newsText, new LinearLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        root.addView(box, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        setContentView(root);

        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = PANEL[level];   // where the box controls the panel's brightness
        getWindow().setAttributes(lp);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    @Override protected void onResume() {
        super.onResume();
        handler.postDelayed(drift, 60_000);
        showWeather();
        if (newsText != null) {
            nextHeadline();
            handler.postDelayed(tick, NEXT_HEADLINE);
            NewsFeed.refresh(this, Prefs.newsSource(), () -> handler.post(this::nextHeadline));
            handler.postDelayed(refreshNews, 15 * 60_000L);
        } else {
            handler.postDelayed(tickWeather, 60_000);
        }
    }

    private final Runnable refreshNews = new Runnable() {
        @Override public void run() {
            NewsFeed.refresh(DarkActivity.this, Prefs.newsSource(), null);
            handler.postDelayed(this, 15 * 60_000L);
        }
    };

    @Override protected void onPause() {
        handler.removeCallbacks(drift);
        handler.removeCallbacks(tick);
        handler.removeCallbacks(tickWeather);
        handler.removeCallbacks(refreshNews);
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
