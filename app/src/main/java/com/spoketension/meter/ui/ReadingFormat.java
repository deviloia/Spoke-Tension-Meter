package com.spoketension.meter.ui;

import android.content.Context;
import android.graphics.Color;

import com.spoketension.meter.R;
import com.spoketension.meter.data.model.Reading;
import com.spoketension.meter.data.model.Wheel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Display helpers shared by MeasureActivity and ResultActivity. */
final class ReadingFormat {

    private ReadingFormat() { }

    /** Same rejection rule as the lab screen: methods more than this apart -> re-pluck suggested. */
    static final double SPREAD_MAX_HZ = 10;

    /** Rear wheel: right = gear (drive) side. Front wheel: left = disc side. Stored codes stay L/R. */
    static String sideName(Context c, Wheel w, String side) {
        boolean rear = w != null && "rear".equals(w.position);
        boolean right = "R".equals(side);
        return c.getString(rear ? (right ? R.string.side_right_rear : R.string.side_left_rear)
                : (right ? R.string.side_right_front : R.string.side_left_front));
    }

    /** "#5  Gear side (R)  346.1 Hz → 101.9 kgf  ok" + the three-method line. */
    static String rowText(Context c, Wheel w, Reading r) {
        return String.format(Locale.US, "#%d  %s  %.1f Hz → %.1f kgf  %s%n", r.spokeNo,
                sideName(c, w, r.side), r.freqMedianHz, r.tensionKgf, r.status)
                + methodsLine(r.freqMedianHz, medianFinite(r.fftHz), medianFinite(r.mpHz));
    }

    /** "Sally 346.1 · FFT 345.9 · MP 346.0 Hz (spread 0.2)" with a warning when they disagree. */
    static String methodsLine(double sally, double fft, double mp) {
        double lo = Double.POSITIVE_INFINITY, hi = Double.NEGATIVE_INFINITY;
        int found = 0;
        for (double f : new double[]{sally, fft, mp})
            if (Double.isFinite(f)) { lo = Math.min(lo, f); hi = Math.max(hi, f); found++; }
        String line = String.format(Locale.US, "Sally %s · FFT %s · MP %s Hz", fmt(sally), fmt(fft), fmt(mp));
        if (found < 2) return line;
        double spread = hi - lo;
        return line + String.format(Locale.US,
                spread > SPREAD_MAX_HZ ? "  ⚠ spread %.1f Hz" : "  (spread %.1f)", spread);
    }

    private static String fmt(double v) {
        return Double.isFinite(v) ? String.format(Locale.US, "%.1f", v) : "—";
    }

    /** Median of the finite values (older readings may have no FFT/MP list); NaN if none. */
    static double medianFinite(List<Double> values) {
        List<Double> finite = new ArrayList<>();
        if (values != null) for (Double v : values) if (v != null && Double.isFinite(v)) finite.add(v);
        return finite.isEmpty() ? Double.NaN : median(finite);
    }

    static double median(List<Double> values) {
        List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int middle = sorted.size() / 2;
        return sorted.size() % 2 == 1 ? sorted.get(middle)
                : (sorted.get(middle - 1) + sorted.get(middle)) / 2;
    }

    static int color(String status) {
        if ("danger".equals(status)) return Color.rgb(255, 205, 210);
        if ("loose".equals(status)) return Color.rgb(255, 224, 178);
        if ("uneven".equals(status)) return Color.rgb(255, 249, 196);
        return Color.TRANSPARENT;
    }
}
