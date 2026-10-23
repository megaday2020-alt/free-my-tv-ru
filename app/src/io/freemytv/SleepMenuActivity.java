package io.freemytv;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * The sleep timer menu: pick how long until the night clock comes up (or turn the timer off). A small dark panel over the
 * home screen; up and down move, OK picks, Back closes.
 */
public class SleepMenuActivity extends Activity {
    private static final int[] MINUTES = {15, 30, 45, 60, 90, 120};

    private float dp;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.init(this);
        dp = getResources().getDisplayMetrics().density;
        int active = SleepTimer.remainingMinutes(this);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xB3000000);
        root.setOnClickListener(v -> finish());

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(px(28), px(24), px(28), px(20));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF21C1C1E);
        bg.setCornerRadius(px(24));
        bg.setStroke(Math.max(1, px(1)), 0x33FFFFFF);
        panel.setBackground(bg);
        panel.setMinimumWidth(px(380));

        TextView title = text(Ui.ru ? "Таймер сна" : Ui.tr("Temporizador", "Sleep timer"), 22, 0xFFFFFFFF, true);
        panel.addView(title);
        String state = active == 0 ? (Ui.ru ? "Сейчас выключен" : Ui.tr("Desligado", "Off now"))
                : (Ui.ru ? "Сработает через " + active + " мин" : Ui.tr("Em " + active + " min", "In " + active + " min"));
        TextView sub = text(state + (Ui.ru ? ". Ночные часы и пауза видео." : ". Night clock, and the video pauses."), 15, 0xFF9A9AA0, false);
        sub.setPadding(0, px(4), 0, px(14));
        panel.addView(sub);

        View first = null;
        for (int m : MINUTES) {
            TextView row = option((Ui.ru ? "Через " + m + " мин" : Ui.tr("Em " + m + " min", "In " + m + " min")), () -> choose(m));
            panel.addView(row);
            if (first == null) first = row;
        }
        if (active > 0) panel.addView(option(Ui.ru ? "Выключить таймер" : Ui.tr("Desligar o temporizador", "Turn the timer off"), () -> choose(0)));

        root.addView(panel, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        setContentView(root);
        if (first != null) first.requestFocus();
    }

    private void choose(int minutes) {
        SleepTimer.set(this, minutes);
        Toast.makeText(this, minutes == 0 ? (Ui.ru ? "Таймер сна выключен" : Ui.tr("Temporizador desligado", "Sleep timer off"))
                : (Ui.ru ? "Ночные часы через " + minutes + " мин" : Ui.tr("Relógio noturno em " + minutes + " min", "Night clock in " + minutes + " min")),
                Toast.LENGTH_SHORT).show();
        finish();
    }

    private int px(float v) {
        return Math.round(v * dp);
    }

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (Ui.semibold != null && bold) t.setTypeface(Ui.semibold);
        else if (Ui.medium != null) t.setTypeface(Ui.medium);
        return t;
    }

    /** A focusable row: dark, and white with dark text when focused. */
    private TextView option(String label, Runnable action) {
        TextView t = text(label, 19, 0xFFF2F2F7, false);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setPadding(px(18), px(12), px(18), px(12));
        t.setFocusable(true);
        t.setClickable(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = px(6);
        t.setLayoutParams(lp);
        paint(t, false);
        t.setOnFocusChangeListener((v, focused) -> paint(t, focused));
        t.setOnClickListener(v -> action.run());
        return t;
    }

    private void paint(TextView t, boolean focused) {
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(px(14));
        d.setColor(focused ? 0xFFF5F5F7 : 0x1FFFFFFF);
        t.setBackground(d);
        t.setTextColor(focused ? 0xFF1D1D1F : 0xFFF2F2F7);
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent e) {
        if (keyCode == KeyEvent.KEYCODE_ESCAPE) {
            finish();
            return true;
        }
        return super.onKeyDown(keyCode, e);
    }
}
