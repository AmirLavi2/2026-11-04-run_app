package app.runtracker;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

import java.util.Random;

import app.runtracker.core.Conditions.TimeOfDay;

/**
 * The screen background: a sky drawn to match the time of day. Positions are in dp from the top so
 * they line up with the screen's fixed layout: the horizon sits just above the Start button.
 */
final class SkyDrawable extends Drawable {
    private static final float HORIZON_DP = 372;
    /** Each cloud: x as a fraction of the width, y in dp, size. They sit clear of the big numbers. */
    private static final float[][] CLOUDS = {
            {0.92f, 118, 0.8f}, {0.07f, 345, 0.7f}, {0.75f, 560, 1.0f}, {0.25f, 680, 0.8f}};
    private static final int[] DAY_SKY = {0xFF1F7FCC, 0xFF5FB2E8};
    private static final int[] MORNING_SKY = {0xFF1F7FCC, 0xFF4B9EDC, 0xFFFFD59A};
    private static final float[] MORNING_STOPS = {0, 0.75f, 1};
    private static final int[] EVENING_SKY = {0xFF23204F, 0xFF7B2D7E, 0xFFE8603C, 0xFFFFA94D};
    private static final float[] EVENING_STOPS = {0, 0.45f, 0.88f, 1};
    private static final int[] NIGHT_SKY = {0xFF050A1E, 0xFF101B3A};
    private static final int MORNING_GROUND = 0xFF163844;
    private static final int EVENING_GROUND = 0xFF1A1230;

    private final TimeOfDay timeOfDay;
    private final float dp;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    SkyDrawable(TimeOfDay timeOfDay, float density) {
        this.timeOfDay = timeOfDay;
        this.dp = density;
    }

    @Override
    public void draw(Canvas canvas) {
        Rect b = getBounds();
        float w = b.width();
        float h = b.height();
        float horizon = HORIZON_DP * dp;
        switch (timeOfDay) {
            case MORNING:
                fillSky(canvas, w, horizon, MORNING_SKY, MORNING_STOPS);
                drawRays(canvas, w * 0.8f, horizon, 330 * dp);
                drawSun(canvas, w * 0.8f, horizon, 34 * dp, 0xFFFFE9A8, 0xCCFFD27A);
                drawClouds(canvas, w);
                fillGround(canvas, w, h, horizon, MORNING_GROUND);
                break;
            case NOON:
                fillSky(canvas, w, h, DAY_SKY, null);
                drawClouds(canvas, w);
                drawSun(canvas, w * 0.72f, 62 * dp, 32 * dp, Color.WHITE, 0xCCFFFFFF);
                break;
            case EVENING:
                fillSky(canvas, w, horizon, EVENING_SKY, EVENING_STOPS);
                drawStars(canvas, w, 160 * dp, 25);
                drawSun(canvas, w * 0.8f, horizon, 30 * dp, 0xFFFF5A36, 0xCCFF8A4D);
                fillGround(canvas, w, h, horizon, EVENING_GROUND);
                break;
            default:
                fillSky(canvas, w, h, NIGHT_SKY, null);
                drawStars(canvas, w, h, 170);
                break;
        }
    }

    private void fillSky(Canvas canvas, float w, float h, int[] colors, float[] stops) {
        paint.setShader(new LinearGradient(0, 0, 0, h, colors, stops, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, h, paint);
        paint.setShader(null);
    }

    private void fillGround(Canvas canvas, float w, float h, float horizon, int color) {
        paint.setColor(color);
        canvas.drawRect(0, horizon, w, h, paint);
    }

    private void drawSun(Canvas canvas, float x, float y, float radius, int core, int glow) {
        float glowRadius = radius * 3.2f;
        paint.setShader(new RadialGradient(x, y, glowRadius, glow, glow & 0x00FFFFFF, Shader.TileMode.CLAMP));
        canvas.drawCircle(x, y, glowRadius, paint);
        paint.setShader(null);
        paint.setColor(core);
        canvas.drawCircle(x, y, radius, paint);
    }

    /** Soft beams fanning up from the sun, for the morning. */
    private void drawRays(Canvas canvas, float x, float y, float length) {
        paint.setColor(0x22FFF3C4);
        paint.setStrokeWidth(10 * dp);
        paint.setStrokeCap(Paint.Cap.ROUND);
        for (int degrees = 15; degrees <= 165; degrees += 25) {
            double a = Math.toRadians(degrees);
            canvas.drawLine(x, y, x + (float) Math.cos(a) * length, y - (float) Math.sin(a) * length, paint);
        }
    }

    private void drawClouds(Canvas canvas, float w) {
        paint.setColor(0x8CFFFFFF);
        for (float[] cloud : CLOUDS) {
            float x = cloud[0] * w;
            float y = cloud[1] * dp;
            float r = 20 * dp * cloud[2];
            canvas.drawCircle(x - r * 1.2f, y, r * 0.8f, paint);
            canvas.drawCircle(x, y - r * 0.4f, r, paint);
            canvas.drawCircle(x + r * 1.2f, y, r * 0.8f, paint);
        }
    }

    /** A fixed seed keeps every star in the same place each time the screen is drawn. */
    private void drawStars(Canvas canvas, float w, float h, int count) {
        Random random = new Random(42);
        for (int i = 0; i < count; i++) {
            float x = random.nextFloat() * w;
            float y = random.nextFloat() * h;
            float radius = (0.5f + random.nextFloat() * 1.1f) * dp;
            paint.setColor(Color.argb(110 + random.nextInt(146), 255, 255, 255));
            canvas.drawCircle(x, y, radius, paint);
        }
    }

    @Override
    public void setAlpha(int alpha) {
        paint.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        paint.setColorFilter(colorFilter);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.OPAQUE;
    }
}
