package com.spoketension.meter.calc;

/**
 * Converts between FREQUENCY and TENSION using the ideal vibrating-string model.
 *
 * Base formula:     f = (1 / 2L) * sqrt(T / mu)
 * Inverted:         T = 4 * L^2 * mu * f^2
 * For a round spoke: mu = rho * pi * d^2 / 4   (mass per metre of length, kg/m)
 *
 * Inputs use "bike mechanic" units for convenience (cm, mm); internally everything is in metres.
 *
 * LIMITATIONS: the ideal model ignores the spoke's bending stiffness, the end conditions and
 * butted spokes. The result is therefore an ESTIMATE (method ② in the design document),
 * not a reference value.
 *
 * This class has no Android dependencies, so it runs and is testable on a regular computer.
 */
public final class SoundTensionCalculator {

    private SoundTensionCalculator() { }

    /** Standard gravity, used to convert Newtons to kgf. */
    public static final double G = 9.80665;

    /**
     * Plausible tension range of a bicycle spoke. Used to derive the FREQUENCY BAND to search,
     * which filters out speech and harmonics that fall outside the band.
     */
    public static final double MIN_TENSION_KGF = 30.0;
    public static final double MAX_TENSION_KGF = 200.0;

    /** Mass per metre of a round spoke (kg/m). */
    public static double linearDensity(double diameterMm, double densityKgM3) {
        double d = diameterMm / 1000.0;                 // mm -> m
        return densityKgM3 * Math.PI * d * d / 4.0;
    }

    /**
     * Frequency (Hz) -> tension (kgf).
     * Sanity check: 346 Hz, L = 29 cm, d = 2.0 mm, stainless 7900 kg/m3 -> about 102 kgf.
     */
    public static double tensionKgf(double frequencyHz, double lengthCm,
                                    double diameterMm, double densityKgM3) {
        if (!(frequencyHz > 0) || !(lengthCm > 0) || !(diameterMm > 0) || !(densityKgM3 > 0)) {
            return Double.NaN;
        }
        double length = lengthCm / 100.0;               // cm -> m
        double mu = linearDensity(diameterMm, densityKgM3);
        double tensionNewton = 4.0 * length * length * mu * frequencyHz * frequencyHz;
        return tensionNewton / G;
    }

    // ─────────────── Bending-stiffness correction (ESTIMATE, not yet validated experimentally) ───────────────
    //
    // A 2 mm steel spoke is not as flexible as a guitar string: while vibrating it also "resists bending",
    // which raises the frequency above the ideal-string formula. Ignoring this makes the tension HIGHER
    // than it really is.
    // Stiff-string model (Fletcher, 1964), with the dimensionless coefficient
    //     B = π³ · E · d⁴ / (64 · T · L²)
    //   - "Pinned" ends (free to rotate):  f = f_ideal · sqrt(1 + B)
    //   - "Clamped" ends:                  f = f_ideal · sqrt(1 + B) · (1 + 2√B/π + 4B/π²)
    // Real spoke ends (nipple at the rim, hook at the hub) lie somewhere BETWEEN these two cases,
    // so the app shows both as a RANGE. To get one correct number, calibrate against a
    // tension meter (method ③).

    /** Young's modulus of stainless spoke steel (Pa). About 193–210 GPa depending on the grade. */
    public static final double STEEL_YOUNG_MODULUS_PA = 200e9;

    /** Stiffness constant K = B·T (Newtons): independent of tension. */
    private static double bendingConstantN(double lengthM, double diameterM, double youngPa) {
        return Math.pow(Math.PI, 3) * youngPa * Math.pow(diameterM, 4) / (64.0 * lengthM * lengthM);
    }

    /**
     * Frequency (Hz) -> tension (kgf), including bending stiffness, PINNED ends.
     * Closed form: f²·4L²μ = T·(1 + B) = T + K  =>  T = T_ideal − K.
     * This is the UPPER BOUND of the corrected tension (smallest correction).
     */
    public static double tensionKgfPinned(double frequencyHz, double lengthCm, double diameterMm,
                                          double densityKgM3, double youngPa) {
        double ideal = tensionKgf(frequencyHz, lengthCm, diameterMm, densityKgM3);
        if (!Double.isFinite(ideal)) return Double.NaN;
        double k = bendingConstantN(lengthCm / 100.0, diameterMm / 1000.0, youngPa) / G;
        double t = ideal - k;
        return t > 0 ? t : Double.NaN;
    }

    /**
     * Frequency (Hz) -> tension (kgf), including bending stiffness, CLAMPED ends.
     * There is no closed form, so it is solved by bisection.
     * This is the LOWER BOUND of the corrected tension (largest correction).
     * Returns NaN if the spoke is too stiff relative to the tension (B > 0.1), where the model no longer holds.
     */
    public static double tensionKgfClamped(double frequencyHz, double lengthCm, double diameterMm,
                                           double densityKgM3, double youngPa) {
        double idealKgf = tensionKgf(frequencyHz, lengthCm, diameterMm, densityKgM3);
        if (!Double.isFinite(idealKgf)) return Double.NaN;
        double length = lengthCm / 100.0;
        double mu = linearDensity(diameterMm, densityKgM3);
        double k = bendingConstantN(length, diameterMm / 1000.0, youngPa);

        // The correction always RAISES the frequency, so the true tension < the ideal tension.
        double hi = idealKgf * G, lo = 0.3 * hi;
        if (clampedFrequency(lo, length, mu, k) > frequencyHz) return Double.NaN;
        for (int i = 0; i < 100; i++) {
            double mid = 0.5 * (lo + hi);
            if (clampedFrequency(mid, length, mu, k) > frequencyHz) hi = mid; else lo = mid;
        }
        double tension = 0.5 * (lo + hi);
        return k / tension > 0.1 ? Double.NaN : tension / G;
    }

    /** Fundamental frequency of a stiff string with clamped ends, at tension T (Newtons). */
    private static double clampedFrequency(double tensionN, double lengthM, double mu, double k) {
        double b = k / tensionN;
        double ideal = Math.sqrt(tensionN / mu) / (2.0 * lengthM);
        return ideal * Math.sqrt(1 + b) * (1 + 2 * Math.sqrt(b) / Math.PI + 4 * b / (Math.PI * Math.PI));
    }

    /** Tension (kgf) -> frequency (Hz). Used to compute the search band. */
    public static double frequencyForTension(double tensionKgf, double lengthCm,
                                             double diameterMm, double densityKgM3) {
        double length = lengthCm / 100.0;
        double mu = linearDensity(diameterMm, densityKgM3);
        return Math.sqrt(tensionKgf * G / mu) / (2.0 * length);
    }

    /** Design-document form, SI units: kgf(346, 0.290, 0.002, Material.STAINLESS) ≈ 102 kgf. */
    public static double kgf(double frequencyHz, double lengthM, double diameterM, Material material) {
        return tensionKgf(frequencyHz, lengthM * 100, diameterM * 1000, material.densityKgM3);
    }
}
