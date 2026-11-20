package com.spoketension.meter.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

/**
 * Radar chart of tension per spoke: one axis per spoke number (1 at the top, clockwise),
 * a dashed grey ring per side at its expected tension, and a coloured line per side.
 * Unmeasured spokes (NaN) are not drawn; lines only join neighbouring measured spokes.
 */
public class RadarView extends View {

    static final int LEFT_COLOR = Color.rgb(30, 136, 229), RIGHT_COLOR = Color.rgb(251, 140, 0);

    private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint expect = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);

    private double[] left = new double[0], right = new double[0];
    private double expectLeft, expectRight, max = 1;

    public RadarView(Context c, AttributeSet attrs) {
        super(c, attrs);
        float d = getResources().getDisplayMetrics().density;
        grid.setStyle(Paint.Style.STROKE);
        grid.setColor(Color.rgb(200, 200, 200));
        grid.setStrokeWidth(d);
        expect.setStyle(Paint.Style.STROKE);
        expect.setColor(Color.rgb(140, 140, 140));
        expect.setStrokeWidth(2 * d);
        expect.setPathEffect(new DashPathEffect(new float[]{6 * d, 4 * d}, 0));
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(2 * d);
        label.setColor(Color.GRAY);
        label.setTextSize(10 * d);
        label.setTextAlign(Paint.Align.CENTER);
    }

    /** kgf per spoke (index 0 = spoke 1), NaN = not measured. */
    void setData(double[] left, double[] right, double expectLeft, double expectRight, double max) {
        this.left = left;
        this.right = right;
        this.expectLeft = expectLeft;
        this.expectRight = expectRight;
        this.max = max > 0 ? max : 1;
        invalidate();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int w = MeasureSpec.getSize(widthSpec);
        setMeasuredDimension(w, w);   // square
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int n = left.length;
        if (n < 3) return;
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        float r = Math.min(cx, cy) - label.getTextSize() * 1.5f;
        for (int ring = 1; ring <= 4; ring++) canvas.drawCircle(cx, cy, r * ring / 4, grid);
        for (int i = 0; i < n; i++) {
            float[] p = point(cx, cy, r, i, n, 1);
            canvas.drawLine(cx, cy, p[0], p[1], grid);
            float[] t = point(cx, cy, r + label.getTextSize(), i, n, 1);
            canvas.drawText(String.valueOf(i + 1), t[0], t[1] + label.getTextSize() / 3, label);
        }
        drawExpect(canvas, cx, cy, r, expectLeft, LEFT_COLOR, Paint.Align.RIGHT);
        drawExpect(canvas, cx, cy, r, expectRight, RIGHT_COLOR, Paint.Align.LEFT);
        drawSide(canvas, cx, cy, r, left, LEFT_COLOR);
        drawSide(canvas, cx, cy, r, right, RIGHT_COLOR);
    }

    /** Grey dashed ring plus its kgf value in the side's colour, left or right of the top axis. */
    private void drawExpect(Canvas canvas, float cx, float cy, float r, double kgf, int color, Paint.Align align) {
        if (!(kgf > 0)) return;
        float rr = (float) (r * Math.min(kgf / max, 1.05));
        canvas.drawCircle(cx, cy, rr, expect);
        float gap = label.getTextSize() / 3;
        label.setColor(color);
        label.setTextAlign(align);
        canvas.drawText(String.format(java.util.Locale.US, "%.0f", kgf),
                align == Paint.Align.LEFT ? cx + gap : cx - gap, cy - rr - gap, label);
        label.setColor(Color.GRAY);
        label.setTextAlign(Paint.Align.CENTER);
    }

    private void drawSide(Canvas canvas, float cx, float cy, float r, double[] kgf, int color) {
        int n = kgf.length;
        line.setColor(color);
        dot.setColor(color);
        Path path = new Path();
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            if (Double.isNaN(kgf[i]) || Double.isNaN(kgf[j])) continue;
            float[] a = point(cx, cy, r, i, n, kgf[i] / max), b = point(cx, cy, r, j, n, kgf[j] / max);
            path.moveTo(a[0], a[1]);
            path.lineTo(b[0], b[1]);
        }
        canvas.drawPath(path, line);
        float dotR = 3 * getResources().getDisplayMetrics().density;
        for (int i = 0; i < n; i++) {
            if (Double.isNaN(kgf[i])) continue;
            float[] a = point(cx, cy, r, i, n, kgf[i] / max);
            canvas.drawCircle(a[0], a[1], dotR, dot);
        }
    }

    /** Spoke i on its axis at fraction f of the radius; spoke 1 at the top, clockwise. */
    private static float[] point(float cx, float cy, float r, int i, int n, double f) {
        double angle = -Math.PI / 2 + 2 * Math.PI * i / n;
        float len = (float) (r * Math.min(f, 1.05));
        return new float[]{cx + len * (float) Math.cos(angle), cy + len * (float) Math.sin(angle)};
    }
}
