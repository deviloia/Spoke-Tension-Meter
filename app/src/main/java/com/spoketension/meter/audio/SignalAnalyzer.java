package com.spoketension.meter.audio;

import com.spoketension.meter.calc.SoundTensionCalculator;

import java.util.ArrayList;
import java.util.List;

/** Shared band and analysis-window selection for the FFT and MatrixPencil(New) methods. */
public final class SignalAnalyzer {
    private SignalAnalyzer() {}
    public static final int SAMPLE_RATE = 44100;
    // Same window for FFT and MatrixPencil (~186 ms). MatrixPencil decimates it to ~602 samples.
    // 8192 beat 4096 on synthetic plucks (beating neighbour, speech) for both methods.
    public static final int ANALYSIS_SAMPLES = 8192;
    // Hard limits: the tension band is clamped into this (MatrixPencil decimation assumes < 1650 Hz).
    public static final double MIN_HZ = 150.0;
    public static final double MAX_HZ = 1500.0;
    /** Up to this many pluck candidates (steepest attack first); MatrixPencil tries them in order. */
    public static final int MAX_TAPS = 3;

    /** Frequency band {lo, hi} for a tension range (ideal string), clamped to MIN_HZ..MAX_HZ. */
    public static double[] bandFromTension(double tMinKgf, double tMaxKgf, double lengthCm,
                                           double diameterMm, double densityKgM3) {
        return new double[]{
                Math.max(MIN_HZ, SoundTensionCalculator.frequencyForTension(tMinKgf, lengthCm, diameterMm, densityKgM3)),
                Math.min(MAX_HZ, SoundTensionCalculator.frequencyForTension(tMaxKgf, lengthCm, diameterMm, densityKgM3))};
    }

    public static final class Window {
        public final double[] samples;
        public final int start, onset;
        public final double jumpRatio;
        Window(double[] samples, int start, int onset, double jumpRatio) {
            this.samples = samples;
            this.start = start;
            this.onset = onset;
            this.jumpRatio = jumpRatio;
        }
    }

    /**
     * Analysis windows after each pluck candidate, steepest attack first (zip's TapDetector: volume jumps
     * >= 4x within ~23 ms, relative to the recording's own noise floor, so speech before the pluck and a
     * quiet UNPROCESSED mic are both handled). Skips 40 ms of strike transient. Empty -> no pluck.
     */
    public static List<Window> windows(double[] x, int fs) {
        List<Window> out = new ArrayList<>();
        int n = ANALYSIS_SAMPLES;
        for (TapDetector.Tap t : TapDetector.findCandidates(x, fs, MAX_TAPS)) {
            // Late pluck: slide the window back (shortening the 40 ms skip) but never before the onset.
            int start = Math.min(t.analysisStart, x.length - n);
            if (start < t.onsetSample) continue; // pluck in the last ~186 ms: no full window after it
            double[] s = new double[n];
            System.arraycopy(x, start, s, 0, n);
            out.add(new Window(s, start, t.onsetSample, t.jumpRatio));
        }
        return out;
    }
}
