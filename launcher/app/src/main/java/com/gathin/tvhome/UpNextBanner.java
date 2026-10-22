package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import androidx.annotation.NonNull;

/** The Up Next tile's banner: a deep blue to violet field with a play mark and the name in Outfit. */
final class UpNextBanner extends Drawable {
    private static final int FROM = 0xFF1E3A8A, TO = 0xFF7C3AED;

    private final Typeface face;
    private final String label;
    private final float density;
    private final Paint field = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path play = new Path();

    UpNextBanner(Context ctx) {
        this(ctx.getResources().getFont(R.font.outfit_medium), ctx.getString(R.string.up_next),
                ctx.getResources().getDisplayMetrics().density);
    }

    private UpNextBanner(Typeface face, String label, float density) {
        this.face = face;
        this.label = label;
        this.density = density;
        ink.setColor(0xFFFFFFFF);
        ink.setTypeface(face);
    }

    @Override
    protected void onBoundsChange(@NonNull Rect b) {
        field.setShader(new LinearGradient(b.left, b.bottom, b.right, b.top, FROM, TO, Shader.TileMode.CLAMP));
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        Rect b = getBounds();
        canvas.drawRect(b, field);
        float size = b.height() * 0.24f;
        ink.setTextSize(size);
        float mark = size * 0.72f, gap = size * 0.4f;
        float textW = ink.measureText(label);
        float left = b.exactCenterX() - (mark + gap + textW) / 2f;
        float cy = b.exactCenterY();
        play.reset();
        play.moveTo(left, cy - mark / 2f);
        play.lineTo(left + mark * 0.87f, cy);
        play.lineTo(left, cy + mark / 2f);
        play.close();
        canvas.drawPath(play, ink);
        Paint.FontMetrics fm = ink.getFontMetrics();
        canvas.drawText(label, left + mark + gap, cy - (fm.ascent + fm.descent) / 2f, ink);
    }

    @Override
    public int getIntrinsicWidth() {
        return Math.round(320 * density);
    }

    @Override
    public int getIntrinsicHeight() {
        return Math.round(180 * density);
    }

    @Override
    public void setAlpha(int alpha) {
        field.setAlpha(alpha);
        ink.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter filter) {
        field.setColorFilter(filter);
        ink.setColorFilter(filter);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.OPAQUE;
    }

    /** AppAdapter samples the glow colour from a private copy of the banner. */
    @Override
    public ConstantState getConstantState() {
        return new ConstantState() {
            @NonNull
            @Override
            public Drawable newDrawable() {
                return new UpNextBanner(face, label, density);
            }

            @Override
            public int getChangingConfigurations() {
                return 0;
            }
        };
    }
}
