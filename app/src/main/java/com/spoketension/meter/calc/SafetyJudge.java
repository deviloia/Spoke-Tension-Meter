package com.spoketension.meter.calc;

/** Classifies one spoke: danger (above rim limit), loose, uneven, or ok. */
public final class SafetyJudge {

    private SafetyJudge() { }

    public enum Status { OK, UNEVEN, LOOSE, DANGER }

    // ponytail: starting thresholds, tune with tension-meter data (method ③)
    /** More than 20 % off the side average -> uneven. */
    public static final double UNEVEN_RATIO = 0.20;
    /** More than 30 % below the side average -> loose. */
    public static final double LOOSE_RATIO = 0.30;

    // ponytail: starting value, tune with real plucks. 3 % of the frequency (~10 Hz at 350 Hz)
    // keeps the mean within ±1.5 % Hz = ±3 % tension, far below the 20 % "uneven" threshold.
    /** Sally and MatrixPencil must agree within this fraction of their mean, or the pluck is rejected. */
    public static final double AGREE_RATIO = 0.03;

    /** Final frequency = mean of Sally and MatrixPencil; NaN when either is missing or they disagree. */
    public static double combineHz(double sallyHz, double mpHz) {
        if (!(sallyHz > 0) || !(mpHz > 0)) return Double.NaN;
        return spread(sallyHz, mpHz) > AGREE_RATIO ? Double.NaN : (sallyHz + mpHz) / 2;
    }

    /** |a - b| as a fraction of their mean; the quantity AGREE_RATIO limits. */
    public static double spread(double a, double b) {
        return Math.abs(a - b) / ((a + b) / 2);
    }

    /**
     * @param sideAverageKgf reference: the side's expected tension (or its average); NaN when unknown (then only the rim limit is checked)
     */
    public static Status judge(double kgf, double rimMaxKgf, double sideAverageKgf) {
        if (rimMaxKgf > 0 && kgf > rimMaxKgf) return Status.DANGER;
        if (!(sideAverageKgf > 0) || !Double.isFinite(kgf)) return Status.OK;
        double dev = (kgf - sideAverageKgf) / sideAverageKgf;
        if (dev < -LOOSE_RATIO) return Status.LOOSE;
        if (Math.abs(dev) > UNEVEN_RATIO) return Status.UNEVEN;
        return Status.OK;
    }
}
