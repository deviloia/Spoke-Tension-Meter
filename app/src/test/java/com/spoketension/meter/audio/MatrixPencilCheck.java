package com.spoketension.meter.audio;

import java.util.Locale;
import java.util.Random;

/**
 * Synthetic self-check for MatrixPencil(New), no framework (run main(), see README). Full 2 s recordings
 * through the same path as the app: SignalAnalyzer.windows -> MatrixPencil.measure. Throws on failure.
 * Spoke: L=20.5 cm, d=2 mm, rho=7850, band 30-200 kgf (~266-688 Hz).
 */
public final class MatrixPencilCheck {
    static final int FS = SignalAnalyzer.SAMPLE_RATE;
    static final double[] BAND = SignalAnalyzer.bandFromTension(30, 200, 20.5, 2.0, 7850);

    public static void main(String[] args) {
        double c = 0.9 * Math.cos(0.5), s = 0.9 * Math.sin(0.5);
        double[] lr = new double[3], li = new double[3];
        MatrixPencil.eigvals(new double[][]{{c, -s, 1}, {s, c, 2}, {0, 0, 0.5}}, lr, li);
        double maxIm = 0;
        for (double v : li) maxIm = Math.max(maxIm, v);
        check(Math.abs(maxIm - s) < 1e-9, "eigvals imag " + maxIm);
        System.out.printf(Locale.US, "band %.1f-%.1f Hz, window %d samples%n", BAND[0], BAND[1],
                SignalAnalyzer.ANALYSIS_SAMPLES);

        double[] x = rec();
        pluck(x, 0.8, 450, 0.2, new double[]{1, 0.4, 0.2});
        expect("a) clean pluck", 450, x);

        x = rec();
        speech(x, 0.05, 0.7, 0.3);
        pluck(x, 0.8, 450, 0.2, new double[]{1, 0.4, 0.2});
        expect("b) loud speech before the pluck", 450, x);

        x = rec();
        pluck(x, 0.8, 300, 0.2, new double[]{1, 2.5, 0.3});
        expect("c) 2nd harmonic 2.5x louder (octave check)", 300, x);

        x = rec();
        pluck(x, 0.8, 450, 0.2, new double[]{1, 0.4, 0.2});
        pluck(x, 0.8, 456, 0.12, new double[]{1, 0.5, 0.2});
        expect("d) neighbour spoke 6 Hz away at 60%", 450, x);

        // f) Real-pluck failure: a thump 3x louder at 297 Hz dying in 30 ms beat the 411 Hz spoke on initial amplitude.
        x = rec();
        pluck(x, 0.8, 411, 0.05, new double[]{1, 0.4, 0.2});
        for (int i = (int) (0.84 * FS); i < x.length; i++) {
            double t = i / (double) FS - 0.84;
            x[i] += 0.15 * Math.exp(-t / 0.03) * Math.sin(2 * Math.PI * 297 * t);
        }
        expect("f) loud 30 ms thump at 297 Hz", 411, x);

        // e) Damping gate, on one window directly (a hum this loud would also hide the attack from TapDetector).
        x = rec();
        for (int i = 0; i < x.length; i++) x[i] += 0.1 * Math.sin(2 * Math.PI * 400 * i / FS); // steady hum
        pluck(x, 0, 500, 0.05, new double[]{1, 0.4, 0.2});
        double f = MatrixPencil.nrpe(java.util.Arrays.copyOf(x, SignalAnalyzer.ANALYSIS_SAMPLES), FS, BAND[0], BAND[1]).freqHz;
        System.out.printf(Locale.US, "%-40s target 500.00 got %.3f%n", "e) 2x louder steady 400 Hz hum (gate)", f);
        check(Math.abs(f - 500) <= 0.5, "e) hum not rejected: got " + f);
        System.out.println("OK");
    }

    static double measure(double[] x) {
        return MatrixPencil.measure(SignalAnalyzer.windows(x, FS), FS, BAND[0], BAND[1]).freqHz;
    }

    static void expect(String name, double target, double[] x) {
        double f = measure(x);
        System.out.printf(Locale.US, "%-40s target %.2f got %.3f%n", name, target, f);
        check(Math.abs(f - target) <= 0.001 * target, name + ": got " + f);
    }

    static double[] rec() {
        double[] x = new double[2 * FS];
        Random r = new Random(7);
        for (int i = 0; i < x.length; i++) x[i] = 5e-4 * r.nextGaussian();
        return x;
    }

    /** Decaying pluck (tau 0.3 s), higher partials decay faster. */
    static void pluck(double[] x, double startS, double f0, double amp, double[] h) {
        int s0 = (int) (startS * FS);
        for (int i = s0; i < x.length; i++) {
            double t = (i - s0) / (double) FS, v = 0;
            for (int k = 1; k <= h.length; k++)
                v += h[k - 1] * Math.exp(-t * (1 / 0.3 + 2 * (k - 1))) * Math.sin(2 * Math.PI * f0 * k * t);
            x[i] += amp * Math.min(1, t / 0.001) * v;
        }
    }

    /** Speech-like: gliding pitch 150-250 Hz, 15 harmonics, 4 syllables/s. */
    static void speech(double[] x, double from, double to, double amp) {
        double phase = 0;
        for (int i = (int) (from * FS); i < (int) (to * FS); i++) {
            double t = i / (double) FS, f = 200 + 50 * Math.sin(2 * Math.PI * 4 * t);
            phase += 2 * Math.PI * f / FS;
            double v = 0;
            for (int k = 1; k <= 15; k++) v += Math.sin(k * phase) / k;
            x[i] += amp * (0.5 - 0.5 * Math.cos(2 * Math.PI * 4 * t)) * v;
        }
    }

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }
}
