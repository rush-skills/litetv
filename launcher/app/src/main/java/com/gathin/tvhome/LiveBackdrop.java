package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BlendMode;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Build;
import android.os.SystemClock;
import android.view.View;

import java.util.Random;

/**
 * An animated backdrop behind Home (Launcher settings, Background): northern lights, a starfield that drifts
 * towards the viewer, or slow waves. With parallax on, it drifts against the focused tile, and the near stars
 * move more than the far ones, which gives Home some depth.
 *
 * It is made for a 1 GB TV that runs Home all day. The lights and the waves are drawn into a small bitmap (an
 * eighth of the screen) that is stretched with filtering, which also softens them for free; the stars are 160
 * dots. It draws at most 30 frames a second, and nothing at all between stop() and start() (Home hidden).
 */
final class LiveBackdrop extends View {
    static final String OFF = "off", AURORA = "aurora", STARS = "stars", WAVES = "waves";

    static boolean valid(String style) {
        return OFF.equals(style) || AURORA.equals(style) || STARS.equals(style) || WAVES.equals(style);
    }

    private static final long FRAME_MS = 33;
    /** The low-resolution canvas for the lights and the waves; 16:9 like the screen. */
    private static final int BUFFER_W = 192, BUFFER_H = 108;
    private static final int STAR_COUNT = 160;
    private static final float TWO_PI = (float) (Math.PI * 2);

    private final String style;
    private final boolean parallax;
    private final float density;
    /** How far the backdrop may drift with the focus, in px; the drawing overscans by this much. */
    private final float maxShift;
    private boolean running;
    /** Animation time in ms; it pauses with the backdrop, so a restart continues instead of jumping. */
    private long elapsedMs, lastFrame;
    private float targetX, targetY, shiftX, shiftY;

    private final Paint blit = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix matrix = new Matrix();
    private final Rect source = new Rect(0, 0, BUFFER_W, BUFFER_H);
    private final RectF dest = new RectF();
    private final Path path = new Path();
    private Bitmap buffer;
    private Canvas bufferCanvas;
    private Paint sky;

    // Northern lights: four soft curtains, each one radial gradient moved and stretched by its local matrix.
    private static final int[] AURORA_COLORS = {0xFF1DE9B6, 0xFF7C4DFF, 0xFF2979FF, 0xFFE040FB};
    private final RadialGradient[] curtains = new RadialGradient[AURORA_COLORS.length];

    // Waves: back to front, with a lighter crest line on each.
    private static final int[] WAVE_COLORS = {0x553949AB, 0x557C4DFF, 0x5500BFA5, 0x661E88E5};

    // Stars: position on the near plane (x, y in -1..1) and depth z (1 far, 0 at the viewer).
    private final float[] starX = new float[STAR_COUNT], starY = new float[STAR_COUNT], starZ = new float[STAR_COUNT];
    private final float[] twinkle = new float[STAR_COUNT];
    private final Random random = new Random(7);

    private final Runnable frame = this::invalidate;

    LiveBackdrop(Context context, String style, boolean parallax) {
        super(context);
        this.style = style;
        this.parallax = parallax;
        density = context.getResources().getDisplayMetrics().density;
        maxShift = parallax ? 22 * density : 0;
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        if (AURORA.equals(style) || WAVES.equals(style)) {
            buffer = Bitmap.createBitmap(BUFFER_W, BUFFER_H, Bitmap.Config.ARGB_8888);
            bufferCanvas = new Canvas(buffer);
        }
        if (AURORA.equals(style)) {
            for (int i = 0; i < curtains.length; i++) {
                int c = AURORA_COLORS[i];
                curtains[i] = new RadialGradient(0, 0, 1, new int[]{(c & 0x00FFFFFF) | 0x8C000000,
                        (c & 0x00FFFFFF) | 0x33000000, c & 0x00FFFFFF}, new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP);
            }
            if (Build.VERSION.SDK_INT >= 29) glow.setBlendMode(BlendMode.PLUS);
            else glow.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.ADD));
        }
        if (STARS.equals(style)) {
            for (int i = 0; i < STAR_COUNT; i++) spawnStar(i, random.nextFloat());
        }
    }

    /** Draws and animates while Home is on screen. */
    void start() {
        if (running) return;
        running = true;
        lastFrame = SystemClock.uptimeMillis();
        invalidate();
    }

    void stop() {
        running = false;
        removeCallbacks(frame);
    }

    /**
     * The focused tile's centre as a share of the screen (0..1). With parallax on, the backdrop drifts the other
     * way, easing there over about a second.
     */
    void setFocusPoint(float x, float y) {
        if (!parallax) return;
        targetX = (0.5f - x) * 2 * maxShift;
        targetY = (0.5f - y) * 2 * maxShift * 0.6f;
        if (running) invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        sky = new Paint();
        sky.setShader(new LinearGradient(0, 0, 0, h, STARS.equals(style) ? 0xFF020309 : 0xFF04060C,
                STARS.equals(style) ? 0xFF070C20 : 0xFF0A0E1E, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        stop();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        long now = SystemClock.uptimeMillis();
        long dt = running ? Math.min(100, now - lastFrame) : 0;
        lastFrame = now;
        elapsedMs += dt;
        float t = elapsedMs / 1000f;
        // Ease the drift towards the target: about 63% of the way in 0.4 s.
        float ease = 1f - (float) Math.exp(-dt / 400.0);
        shiftX += (targetX - shiftX) * ease;
        shiftY += (targetY - shiftY) * ease;

        int w = getWidth(), h = getHeight();
        if (sky != null) canvas.drawRect(0, 0, w, h, sky);
        if (STARS.equals(style)) {
            drawStars(canvas, w, h, t, dt / 1000f);
        } else if (buffer != null) {
            if (AURORA.equals(style)) drawAurora(t);
            else drawWaves(t);
            dest.set(-maxShift + shiftX, -maxShift + shiftY, w + maxShift + shiftX, h + maxShift + shiftY);
            canvas.drawBitmap(buffer, source, dest, blit);
        }
        // One pending frame at a time: a focus move also invalidates, and must not start a second chain.
        removeCallbacks(frame);
        if (running) postDelayed(frame, FRAME_MS);
    }

    /** Four curtains drift on slow, unrelated paths and breathe a little; where they cross they add up. */
    private void drawAurora(float t) {
        Canvas c = bufferCanvas;
        c.drawColor(0, PorterDuff.Mode.CLEAR);
        float w = BUFFER_W, h = BUFFER_H;
        for (int i = 0; i < curtains.length; i++) {
            float speed = 0.021f + i * 0.007f;
            float cx = w * (0.5f + 0.42f * (float) Math.sin(t * speed * TWO_PI + i * 1.7f));
            float cy = h * (0.34f + 0.16f * (float) Math.sin(t * speed * 0.8f * TWO_PI + i * 2.3f));
            float rx = w * (0.42f + 0.06f * (float) Math.sin(t * 0.09f * TWO_PI + i));
            float ry = h * (0.24f + 0.05f * (float) Math.cos(t * 0.07f * TWO_PI + i * 0.6f));
            matrix.setScale(rx, ry);
            matrix.postRotate(-12f + 10f * (float) Math.sin(t * 0.03f * TWO_PI + i), 0, 0);
            matrix.postTranslate(cx, cy);
            curtains[i].setLocalMatrix(matrix);
            glow.setShader(curtains[i]);
            c.drawRect(0, 0, w, h, glow);
        }
    }

    /** Layered swells in the lower half, each the sum of two sine waves moving in opposite directions. */
    private void drawWaves(float t) {
        Canvas c = bufferCanvas;
        c.drawColor(0, PorterDuff.Mode.CLEAR);
        float w = BUFFER_W, h = BUFFER_H;
        int steps = 48;
        for (int layer = 0; layer < WAVE_COLORS.length; layer++) {
            float base = h * (0.52f + layer * 0.1f);
            float amp = h * (0.07f - layer * 0.008f);
            float k = TWO_PI * (1.1f + layer * 0.35f) / w;
            float speed = 0.05f + layer * 0.025f;
            path.rewind();
            for (int s = 0; s <= steps; s++) {
                float x = w * s / steps;
                float y = base + amp * (float) Math.sin(k * x + t * speed * TWO_PI + layer * 1.3f)
                        + amp * 0.45f * (float) Math.sin(k * 2.3f * x - t * speed * 1.6f * TWO_PI + layer);
                if (s == 0) path.moveTo(x, y);
                else path.lineTo(x, y);
            }
            fill.setStyle(Paint.Style.STROKE);
            fill.setStrokeWidth(1f);
            fill.setColor(0x40FFFFFF);
            c.drawPath(path, fill);
            path.lineTo(w, h);
            path.lineTo(0, h);
            path.close();
            fill.setStyle(Paint.Style.FILL);
            fill.setColor(WAVE_COLORS[layer]);
            c.drawPath(path, fill);
        }
    }

    /** Stars fly slowly towards the viewer from a vanishing point that the focus moves, nearer ones faster. */
    private void drawStars(Canvas canvas, int w, int h, float t, float dt) {
        float cx = w / 2f + shiftX, cy = h * 0.42f + shiftY;
        float focal = Math.max(w, h) * 0.5f;
        fill.setStyle(Paint.Style.FILL);
        for (int i = 0; i < STAR_COUNT; i++) {
            starZ[i] -= dt * 0.035f;
            if (starZ[i] <= 0.04f) spawnStar(i, 1f);
            float z = starZ[i];
            // Parallax: the near stars move further with the focus than the far ones.
            float near = 1f - z;
            float x = cx + starX[i] / z * focal * 0.3f + shiftX * near;
            float y = cy + starY[i] / z * focal * 0.3f + shiftY * near;
            if (x < -10 || x > w + 10 || y < -10 || y > h + 10) {
                spawnStar(i, 1f);
                continue;
            }
            float fadeIn = Math.min(1f, near * 3f);
            float shine = 0.7f + 0.3f * (float) Math.sin(t * twinkle[i] + i);
            fill.setColor(0xFFDCE6FF);
            fill.setAlpha((int) (235 * fadeIn * shine * (0.35f + 0.65f * near)));
            canvas.drawCircle(x, y, density * (0.5f + 1.9f * near * near), fill);
        }
    }

    private void spawnStar(int i, float z) {
        starX[i] = random.nextFloat() * 2f - 1f;
        starY[i] = random.nextFloat() * 2f - 1f;
        starZ[i] = 0.1f + 0.9f * z;
        twinkle[i] = 1.5f + random.nextFloat() * 3f;
    }
}
