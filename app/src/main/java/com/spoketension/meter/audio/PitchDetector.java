package com.spoketension.meter.audio;

import com.spoketension.meter.calc.SoundTensionCalculator;

import java.util.List;

/**
 * The "CONDUCTOR": ties the processing steps together. LabActivity only calls analyze().
 *
 *   2 seconds of audio
 *     -> (0) compute the search band from length, diameter and density
 *     -> (1) TapDetector: find candidate plucks
 *     -> (2) PitchTracker: for each candidate, find a stable, decaying tone
 *     -> (3) YinPitch: cross-check, catch 2nd-harmonic errors
 *     -> (4) SoundTensionCalculator: frequency -> kgf
 *     -> (5) Also estimate a tension range that accounts for the spoke's bending stiffness
 *
 * Also computes the result with the OLD METHOD (largest FFT peak, 150-1500 Hz band)
 * so the two can be shown side by side for comparison.
 *
 * No Android dependencies.
 */
public final class PitchDetector {

    private PitchDetector() { }

    /** Maximum number of candidate plucks to try. */
    public static final int MAX_TAP_CANDIDATES = 3;
    /** FFT and YIN "agree" if they differ by less than 2%. */
    public static final double AGREE_TOLERANCE = 0.02;
    /** Frequency band of the old method (kept unchanged for comparison). */
    public static final double LEGACY_MIN_HZ = 150, LEGACY_MAX_HZ = 1500;
    public static final int LEGACY_SAMPLES = 8192;

    public static final class Params {
        public final double lengthCm, diameterMm, densityKgM3;

        public Params(double lengthCm, double diameterMm, double densityKgM3) {
            this.lengthCm = lengthCm;
            this.diameterMm = diameterMm;
            this.densityKgM3 = densityKgM3;
        }
    }

    /** Confidence level of the final result. */
    public enum Verdict {
        RELIABLE,          // multi-frame FFT and YIN agree
        OCTAVE_CORRECTED,  // a 2nd-harmonic error was detected and corrected
        FFT_ONLY,          // YIN gave no result; relies on multi-frame FFT only
        DISAGREE,          // the two algorithms disagree -> measure again
        FAILED             // no spoke tone found
    }

    public static final class Report {
        public double minHz, maxHz;                  // search band used
        public TapDetector.Tap tap;                  // pluck that was used
        public PitchTracker.Result tracked;          // new-method result
        public YinPitch.Result yin;                  // YIN result
        public FftPitch.Peak legacy;                 // old-method result
        public double finalHz = Double.NaN;
        public double finalKgf = Double.NaN;
        public double legacyKgf = Double.NaN;
        /** Tension including bending stiffness (estimate): range [clamped, pinned]. */
        public double stiffKgfClamped = Double.NaN, stiffKgfPinned = Double.NaN;
        public Verdict verdict = Verdict.FAILED;
        public String explanation = "";
    }

    public static Report analyze(double[] x, int sampleRate, Params p) {
        Report r = new Report();

        // (0) Frequency band matching 30-200 kgf for the spoke being measured
        r.minHz = SoundTensionCalculator.frequencyForTension(
                SoundTensionCalculator.MIN_TENSION_KGF, p.lengthCm, p.diameterMm, p.densityKgM3);
        r.maxHz = SoundTensionCalculator.frequencyForTension(
                SoundTensionCalculator.MAX_TENSION_KGF, p.lengthCm, p.diameterMm, p.densityKgM3);

        // (1) Candidate plucks
        List<TapDetector.Tap> taps = TapDetector.findCandidates(x, sampleRate, MAX_TAP_CANDIDATES);
        if (taps.isEmpty()) {
            r.explanation = "No sharp tap detected. Pluck firmly, closer to the phone.";
            return r;
        }

        // (2) Try each candidate; stop at the first one with a stable tone
        PitchTracker.Result firstFailure = null;
        for (TapDetector.Tap tap : taps) {
            PitchTracker.Result tr = PitchTracker.track(x, tap.analysisStart, sampleRate, r.minHz, r.maxHz);
            if (tr.found()) {
                r.tap = tap;
                r.tracked = tr;
                break;
            }
            if (firstFailure == null) { firstFailure = tr; r.tap = tap; r.tracked = tr; }
        }
        if (!r.tracked.found()) {
            r.explanation = "Tap found but " + firstFailure.message + ". Keep quiet and pluck again.";
            return r;
        }

        int start = r.tap.analysisStart;

        // Old method, on the same audio segment, for comparison
        int legacyLength = Math.min(LEGACY_SAMPLES, x.length - start);
        if (legacyLength >= 1024) {
            r.legacy = FftPitch.dominantPeak(x, start, legacyLength, sampleRate, LEGACY_MIN_HZ, LEGACY_MAX_HZ);
            if (r.legacy != null) r.legacyKgf = kgf(r.legacy.frequencyHz, p);
        }

        // (3) YIN cross-check; extend the band down to 1/2 so the true fundamental is still visible
        r.yin = YinPitch.estimate(x, start, PitchTracker.FRAME, sampleRate, r.minHz * 0.5, r.maxHz * 1.1);
        double fftHz = r.tracked.frequencyHz;

        if (!r.yin.found()) {
            r.finalHz = fftHz;
            r.verdict = r.tracked.octaveCorrected ? Verdict.OCTAVE_CORRECTED : Verdict.FFT_ONLY;
            r.explanation = "YIN found no clear period; result relies on multi-frame FFT only.";
        } else {
            double yinHz = r.yin.frequencyHz;
            double diff = Math.abs(fftHz - yinHz) / fftHz;
            double octaveDiff = Math.abs(fftHz - 2 * yinHz) / fftHz;
            if (diff < AGREE_TOLERANCE) {
                r.finalHz = fftHz;               // FFT is more precise in the decimals, so use FFT
                r.verdict = r.tracked.octaveCorrected ? Verdict.OCTAVE_CORRECTED : Verdict.RELIABLE;
                r.explanation = String.format(java.util.Locale.US,
                        "Multi-frame FFT and YIN agree (%.2f%% apart).", diff * 100);
            } else if (octaveDiff < AGREE_TOLERANCE && yinHz >= r.minHz * 0.9) {
                r.finalHz = yinHz;               // FFT locked onto the 2nd harmonic -> use YIN
                r.verdict = Verdict.OCTAVE_CORRECTED;
                r.explanation = "FFT locked onto the 2nd harmonic; using YIN's fundamental.";
            } else {
                r.finalHz = fftHz;
                r.verdict = Verdict.DISAGREE;
                r.explanation = String.format(java.util.Locale.US,
                        "FFT and YIN disagree (%.1f%%). Measure this spoke again.", diff * 100);
            }
        }

        // (4) Frequency -> tension
        r.finalKgf = kgf(r.finalHz, p);
        // (5) Estimate including the spoke's bending stiffness (see SoundTensionCalculator)
        r.stiffKgfPinned = SoundTensionCalculator.tensionKgfPinned(r.finalHz, p.lengthCm,
                p.diameterMm, p.densityKgM3, SoundTensionCalculator.STEEL_YOUNG_MODULUS_PA);
        r.stiffKgfClamped = SoundTensionCalculator.tensionKgfClamped(r.finalHz, p.lengthCm,
                p.diameterMm, p.densityKgM3, SoundTensionCalculator.STEEL_YOUNG_MODULUS_PA);
        return r;
    }

    private static double kgf(double hz, Params p) {
        return SoundTensionCalculator.tensionKgf(hz, p.lengthCm, p.diameterMm, p.densityKgM3);
    }
}
