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

    /**
     * @param sideAverageKgf average of the same side; NaN when unknown (then only the rim limit is checked)
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
