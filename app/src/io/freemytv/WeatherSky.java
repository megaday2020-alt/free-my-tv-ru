package io.freemytv;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

import java.util.Random;

/**
 * A flat, cartoon sky drawn behind the home screen: a sun or a moon that crosses the sky with the time of day, stars at
 * night, drifting clouds, rain, snow, fog and lightning by the weather code. Everything is computed from the clock (no
 * state, no bitmaps, no allocations per frame): circles, rounded rectangles and lines only.
 */
final class WeatherSky {
    private static final int CLOUDS = 7, STARS = 36, DROPS = 80, FLAKES = 50;

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final Path moon = new Path(), cut = new Path();
    private float moonR = -1;

    private final float[] cloudX = new float[CLOUDS], cloudY = new float[CLOUDS], cloudS = new float[CLOUDS], cloudV = new float[CLOUDS];
    private final float[] starX = new float[STARS], starY = new float[STARS], starP = new float[STARS], starR = new float[STARS];
    private final float[] dropX = new float[DROPS], dropY = new float[DROPS], dropV = new float[DROPS];
    private final float[] flakeX = new float[FLAKES], flakeY = new float[FLAKES], flakeV = new float[FLAKES], flakeR = new float[FLAKES];

    WeatherSky() {
        Random rnd = new Random(7);
        for (int i = 0; i < CLOUDS; i++) {
            cloudX[i] = rnd.nextFloat();
            cloudY[i] = 0.2f + 0.3f * ((i * 0.618f) % 1f);
            cloudS[i] = 0.8f + 0.8f * rnd.nextFloat();
            cloudV[i] = 9 + 14 * rnd.nextFloat();
        }
        for (int i = 0; i < STARS; i++) {
            starX[i] = rnd.nextFloat();
            starY[i] = 0.55f * rnd.nextFloat();
            starP[i] = 6.28f * rnd.nextFloat();
            starR[i] = 1.6f + 1.8f * rnd.nextFloat();
        }
        for (int i = 0; i < DROPS; i++) {
            dropX[i] = rnd.nextFloat();
            dropY[i] = rnd.nextFloat();
            dropV[i] = 0.8f + 0.5f * rnd.nextFloat();
        }
        for (int i = 0; i < FLAKES; i++) {
            flakeX[i] = rnd.nextFloat();
            flakeY[i] = rnd.nextFloat();
            flakeV[i] = 0.7f + 0.6f * rnd.nextFloat();
            flakeR[i] = 3 + 3.5f * rnd.nextFloat();
        }
    }

    private static int mix(int a, int b, float t) {
        t = Math.max(0, Math.min(1, t));
        int ar = a >> 16 & 255, ag = a >> 8 & 255, ab = a & 255, br = b >> 16 & 255, bg = b >> 8 & 255, bb = b & 255;
        return 0xFF000000 | Math.round(ar + (br - ar) * t) << 16 | Math.round(ag + (bg - ag) * t) << 8 | Math.round(ab + (bb - ab) * t);
    }

    /**
     * @param code WMO weather code (-1: unknown), night 0 (day) to 1 (night), tod the time of day in ms, rise and set the
     *             time of day of sunrise and sunset, alpha 0 to 1 (the drawer fades it out)
     */
    void draw(Canvas c, float W, float H, float k, long nowMs, int code, float night, long tod, long rise, long set, float alpha) {
        if (alpha < 0.03f) return;
        float t = (nowMs % 3_600_000L) / 1000f;
        boolean storm = code >= 95;
        boolean snow = code >= 71 && code <= 77 || code == 85 || code == 86;
        boolean rain = !storm && !snow && (code >= 51 && code <= 67 || code >= 80 && code <= 82);
        boolean fog = code == 45 || code == 48;
        int drops = !(rain || storm) ? 0 : storm ? 70 : (code >= 51 && code <= 57) ? 26 : (code == 65 || code == 67 || code == 82) ? 80 : 48;
        int clouds = code < 0 ? 2 : code == 0 ? 1 : code == 1 ? 2 : code == 2 ? 4 : code == 3 ? 6 : fog ? 3 : 6;
        // How much of the sun or moon shows through.
        float body = code <= 2 ? 1 : code == 3 ? 0.3f : fog ? 0.4f : 0;
        boolean stars = code <= 2 && night > 0.05f;

        // Stars.
        if (stars) {
            p.setStyle(Paint.Style.FILL);
            float cover = code == 2 ? 0.7f : 1;
            for (int i = 0; i < Math.round(STARS * cover); i++) {
                float tw = 0.5f + 0.5f * (float) Math.sin(t * 1.4f + starP[i]);
                p.setColor(0xFFFFFFFF);
                p.setAlpha(Math.round(255 * alpha * night * (0.25f + 0.75f * tw)));
                c.drawCircle(starX[i] * W, starY[i] * H, starR[i] * k, p);
            }
        }

        // Sun by day, moon by night: each crosses the sky between its rise and set.
        if (body > 0) {
            float day = 1 - night;
            long dayLen = Math.max(1, set - rise);
            float f = Math.max(0, Math.min(1, (tod - rise) / (float) dayLen));
            long nightLen = 24 * 3_600_000L - dayLen;
            long since = tod >= set ? tod - set : tod + 24 * 3_600_000L - set;
            float g = Math.max(0, Math.min(1, since / (float) Math.max(1, nightLen)));
            if (day > 0.02f) drawSun(c, W * (0.5f + 0.38f * f), H * (0.42f - 0.30f * (float) Math.sin(Math.PI * f)), 62 * k, t, alpha * day * body);
            if (night > 0.02f) drawMoon(c, W * (0.5f + 0.38f * g), H * (0.42f - 0.30f * (float) Math.sin(Math.PI * g)), 54 * k, alpha * night * body);
        }

        // Clouds: flat, opaque, drifting slowly.
        int cloudColor = storm ? mix(0xFF7E769C, 0xFF3A2D5C, night)
                : rain ? mix(0xFF93A6BA, 0xFF2B3B50, night)
                : snow ? mix(0xFFFFFFFF, 0xFF3B4A68, night)
                : code == 3 ? mix(0xFFF7F9FB, 0xFF2F3A52, night)
                : fog ? mix(0xFFF4F6F8, 0xFF353A42, night)
                : mix(0xFFFFFFFF, 0xFF33436E, night);
        p.setStyle(Paint.Style.FILL);
        p.setColor(cloudColor);
        p.setAlpha(Math.round(255 * alpha));
        float span = W + 700 * k;
        for (int i = 0; i < clouds; i++) {
            float x = (cloudX[i] * span + t * cloudV[i] * k) % span - 350 * k;
            drawCloud(c, x, cloudY[i] * H, 58 * k * cloudS[i]);
        }

        // Fog: wide pale bands.
        if (fog) {
            p.setColor(mix(0xFFFFFFFF, 0xFF4A4F57, night));
            p.setAlpha(Math.round(110 * alpha));
            for (int i = 0; i < 3; i++) {
                float x = ((i * 0.37f) * W + t * (14 + 6 * i) * k) % (W + 900 * k) - 700 * k;
                float y = H * (0.30f + 0.13f * i);
                r.set(x, y, x + 760 * k, y + 56 * k);
                c.drawRoundRect(r, 28 * k, 28 * k, p);
            }
        }

        // Rain.
        if (drops > 0) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeWidth(3.2f * k);
            p.setColor(mix(0xFF5C8FD0, 0xFF8DB4F5, night));
            p.setAlpha(Math.round(170 * alpha));
            float len = 30 * k, fall = H + len * 2;
            for (int i = 0; i < drops; i++) {
                float y = (dropY[i] * fall + t * 760 * k * dropV[i]) % fall - len;
                float x = dropX[i] * (W + 200 * k) - 100 * k + y * 0.22f;
                c.drawLine(x, y, x - len * 0.22f, y + len, p);
            }
            p.setStyle(Paint.Style.FILL);
        }

        // Snow.
        if (snow) {
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFFFFFFFF);
            p.setAlpha(Math.round(235 * alpha));
            for (int i = 0; i < FLAKES; i++) {
                float y = (flakeY[i] * H + t * 55 * k * flakeV[i]) % (H + 20 * k) - 10 * k;
                float x = flakeX[i] * W + (float) Math.sin(t * 0.9f * flakeV[i] + i) * 22 * k;
                c.drawCircle(x, y, flakeR[i] * k, p);
            }
        }

        // Lightning: a short double flash every ~8 seconds, with a bolt.
        if (storm) {
            float cycle = t % 8f;
            float flash = cycle < 0.12f ? 1 : cycle > 0.22f && cycle < 0.32f ? 0.7f : 0;
            if (flash > 0) {
                p.setStyle(Paint.Style.FILL);
                p.setColor(0xFFFFFFFF);
                p.setAlpha(Math.round(70 * flash * alpha));
                r.set(0, 0, W, H);
                c.drawRect(r, p);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeCap(Paint.Cap.ROUND);
                p.setStrokeWidth(9 * k);
                p.setColor(0xFFFFE066);
                p.setAlpha(Math.round(255 * flash * alpha));
                float x = W * 0.72f, y = H * 0.30f;
                c.drawLine(x, y, x - 36 * k, y + 70 * k, p);
                c.drawLine(x - 36 * k, y + 70 * k, x + 6 * k, y + 78 * k, p);
                c.drawLine(x + 6 * k, y + 78 * k, x - 30 * k, y + 160 * k, p);
                p.setStyle(Paint.Style.FILL);
            }
        }
        p.setAlpha(255);
    }

    /** Three circles on a rounded base, in the current paint color. */
    private void drawCloud(Canvas c, float x, float y, float u) {
        r.set(x - 2.3f * u, y - 0.15f * u, x + 2.3f * u, y + 1.0f * u);
        c.drawRoundRect(r, 0.58f * u, 0.58f * u, p);
        c.drawCircle(x - 0.95f * u, y - 0.2f * u, 0.9f * u, p);
        c.drawCircle(x + 0.35f * u, y - 0.55f * u, 1.2f * u, p);
        c.drawCircle(x + 1.55f * u, y - 0.1f * u, 0.8f * u, p);
    }

    private void drawSun(Canvas c, float x, float y, float R, float t, float alpha) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFFFFD34D);
        p.setAlpha(Math.round(46 * alpha));
        c.drawCircle(x, y, R * (2.0f + 0.06f * (float) Math.sin(t * 1.3f)), p);
        p.setAlpha(Math.round(84 * alpha));
        c.drawCircle(x, y, R * 1.5f, p);
        p.setColor(0xFFFFC93C);
        p.setAlpha(Math.round(255 * alpha));
        c.drawCircle(x, y, R, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeWidth(R * 0.15f);
        float a0 = t * 0.12f;
        for (int i = 0; i < 8; i++) {
            float a = a0 + i * (float) (Math.PI / 4), cs = (float) Math.cos(a), sn = (float) Math.sin(a);
            c.drawLine(x + cs * R * 1.28f, y + sn * R * 1.28f, x + cs * R * 1.58f, y + sn * R * 1.58f, p);
        }
        p.setStyle(Paint.Style.FILL);
    }

    private void drawMoon(Canvas c, float x, float y, float R, float alpha) {
        if (R != moonR) {
            moonR = R;
            moon.rewind();
            moon.addCircle(0, 0, R, Path.Direction.CW);
            cut.rewind();
            cut.addCircle(R * 0.42f, -R * 0.2f, R * 0.88f, Path.Direction.CW);
            moon.op(cut, Path.Op.DIFFERENCE);
        }
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFFF6F1D5);
        p.setAlpha(Math.round(16 * alpha));
        c.drawCircle(x, y, R * 1.9f, p);
        p.setAlpha(Math.round(255 * alpha));
        c.save();
        c.translate(x, y);
        c.rotate(-18);
        c.drawPath(moon, p);
        c.restore();
    }
}
