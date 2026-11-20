package com.spoketension.meter.calc;

import java.util.List;

/**
 * Method ①: how much looser/tighter a spoke is than its side's average, in %.
 * Tension ∝ f², so no length/diameter/material is needed. 330 vs 350 Hz -> −11.1 %.
 */
public final class RelativeTension {

    private RelativeTension() { }

    public static double percent(double hz, double sideAverageHz) {
        if (!(hz > 0) || !(sideAverageHz > 0)) return Double.NaN;
        double r = hz / sideAverageHz;
        return (r * r - 1) * 100;
    }

    /** Frequency matching the side's average TENSION: sqrt(mean(f²)). NaN if empty. */
    public static double sideAverageHz(List<Double> hz) {
        double sum = 0;
        int n = 0;
        for (Double f : hz) if (f != null && f > 0) { sum += f * f; n++; }
        return n == 0 ? Double.NaN : Math.sqrt(sum / n);
    }
}
