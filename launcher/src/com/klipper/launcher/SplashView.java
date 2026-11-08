package com.klipper.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

/**
 * Full-screen boot splash: printer line art on the left, KLIPPER wordmark
 * in the middle, rotating spinner below it and the author credit in the
 * bottom-right corner. All geometry is proportional to the screen size so
 * it looks the same on any phone in landscape.
 */
public class SplashView extends View {
    private static final int BG = Color.rgb(0x1E, 0x1E, 0x1E);
    private static final int TRACK = Color.rgb(0x45, 0x45, 0x45);
    private static final int ACCENT = Color.rgb(0x3D, 0x9B, 0xE9);
    private static final int CREDIT = Color.rgb(0x8C, 0x8C, 0x8C);

    private final Paint logoPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint titlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint creditPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect src = new Rect();
    private final RectF logoDst = new RectF();
    private final RectF spinner = new RectF();

    private final Bitmap logo;
    private float titleCenterY;
    private float creditRight;
    private float creditBaseline;
    private float angle;
    private final Runnable frame = this::tick;

    public SplashView(Context context) {
        super(context);
        setBackgroundColor(BG);

        logo = BitmapFactory.decodeResource(getResources(), R.drawable.logo);
        // The artwork is black line art on transparency; invert it to white.
        ColorMatrix invert = new ColorMatrix(new float[]{
                -1, 0, 0, 0, 255,
                0, -1, 0, 0, 255,
                0, 0, -1, 0, 255,
                0, 0, 0, 1, 0
        });
        logoPaint.setColorFilter(new ColorMatrixColorFilter(invert));
        logoPaint.setAlpha(150);

        titlePaint.setColor(Color.WHITE);
        titlePaint.setTypeface(Typeface.create("sans-serif-black", Typeface.BOLD));
        titlePaint.setTextAlign(Paint.Align.CENTER);
        titlePaint.setShadowLayer(10f, 0f, 4f, 0x99000000);

        trackPaint.setStyle(Paint.Style.STROKE);
        trackPaint.setColor(TRACK);
        arcPaint.setStyle(Paint.Style.STROKE);
        arcPaint.setStrokeCap(Paint.Cap.ROUND);
        arcPaint.setColor(ACCENT);

        creditPaint.setColor(CREDIT);
        creditPaint.setTypeface(Typeface.MONOSPACE);
        creditPaint.setTextAlign(Paint.Align.RIGHT);
        creditPaint.setLetterSpacing(0.08f);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);

        titlePaint.setTextSize(h * 0.14f);
        titleCenterY = h * 0.405f;

        float diameter = h * 0.155f;
        float stroke = Math.max(4f, h * 0.017f);
        float cx = w * 0.5f;
        float cy = h * 0.603f;
        spinner.set(cx - diameter / 2f, cy - diameter / 2f,
                cx + diameter / 2f, cy + diameter / 2f);
        trackPaint.setStrokeWidth(stroke);
        arcPaint.setStrokeWidth(stroke);

        if (logo != null) {
            float logoH = h * 0.74f;
            float logoW = logoH * logo.getWidth() / logo.getHeight();
            float left = w * 0.005f;
            float top = h * 0.25f;
            logoDst.set(left, top, left + logoW, top + logoH);
            src.set(0, 0, logo.getWidth(), logo.getHeight());
        }

        creditPaint.setTextSize(h * 0.024f);
        creditRight = w - w * 0.03f;
        creditBaseline = h - h * 0.035f - creditPaint.descent();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (logo != null) {
            canvas.drawBitmap(logo, src, logoDst, logoPaint);
        }

        Paint.FontMetrics fm = titlePaint.getFontMetrics();
        float baseline = titleCenterY - (fm.ascent + fm.descent) / 2f;
        canvas.drawText("KLIPPER", getWidth() * 0.5f, baseline, titlePaint);

        float inset = trackPaint.getStrokeWidth() / 2f;
        RectF ring = new RectF(spinner);
        ring.inset(inset, inset);
        canvas.drawArc(ring, 0f, 360f, false, trackPaint);
        canvas.drawArc(ring, angle, 70f, false, arcPaint);

        canvas.drawText("by: adilo321", creditRight, creditBaseline, creditPaint);
    }

    private void tick() {
        angle += 8f;
        if (angle >= 360f) angle -= 360f;
        invalidate();
        postDelayed(frame, 30);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        removeCallbacks(frame);
        post(frame);
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(frame);
        super.onDetachedFromWindow();
    }
}
