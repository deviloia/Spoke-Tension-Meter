package com.spoketension.meter.audio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import com.spoketension.meter.calc.SoundTensionCalculator;

import org.junit.Test;

import java.util.Random;

/**
 * Tests with SIMULATED SIGNALS, run on a computer, no phone needed:
 *   gradlew test   (or run the "Run unit tests" task in VS Code)
 *
 * Simulated spoke: decaying sine wave + harmonics.
 * Simulated speech: many harmonics, pitch rising and falling, volume changing per syllable.
 * Simulated signals cannot replace real data, but they reveal immediately when a code
 * change breaks the algorithm.
 */
public class PitchDetectorTest {

    private static final int SR = 44100;
    private static final double SECONDS = 2.0;
    /** Default spoke on screen: search band about 266–688 Hz. */
    private static final PitchDetector.Params PARAMS = new PitchDetector.Params(20.5, 2.0, 7850);

    // ─────────────────────────── formula ───────────────────────────

    @Test
    public void tensionFormulaMatchesDesignDocument() {
        // Design document: 346 Hz, L = 290 mm, d = 2.0 mm, stainless -> about 102 kgf
        assertEquals(101.9, SoundTensionCalculator.tensionKgf(346, 29, 2.0, 7900), 0.3);
        assertEquals(95.0, SoundTensionCalculator.tensionKgf(346, 28, 2.0, 7900), 0.3);
    }

    @Test
    public void frequencyAndTensionAreInverse() {
        double hz = SoundTensionCalculator.frequencyForTension(100, 20.5, 2.0, 7850);
        assertEquals(100, SoundTensionCalculator.tensionKgf(hz, 20.5, 2.0, 7850), 1e-9);
    }

    // ─────────────────────────── algorithm ───────────────────────────

    @Test
    public void cleanPluckIsMeasuredAccurately() {
        double[] x = silence();
        addPluck(x, 0.8, 420, 0.15, 0.4);
        addNoise(x, 0.0005);
        PitchDetector.Report r = PitchDetector.analyze(x, SR, PARAMS);
        assertEquals(420, r.finalHz, 420 * 0.003);
        assertEquals(PitchDetector.Verdict.RELIABLE, r.verdict);
    }

    @Test
    public void loudSpeechBeforePluckIsIgnored() {
        // The case the old version got wrong: someone speaks (loudly) before the pluck
        double[] x = silence();
        addSpeech(x, 0.05, 0.70, 0.30, 1);
        addPluck(x, 0.90, 420, 0.10, 0.4);
        addNoise(x, 0.0005);
        PitchDetector.Report r = PitchDetector.analyze(x, SR, PARAMS);
        assertEquals(420, r.finalHz, 420 * 0.005);
    }

    @Test
    public void speechDuringPluckIsIgnored() {
        // Someone speaks continuously, quieter than the spoke
        double[] x = silence();
        addSpeech(x, 0.0, 2.0, 0.04, 2);
        addPluck(x, 0.80, 420, 0.15, 0.4);
        addNoise(x, 0.0005);
        PitchDetector.Report r = PitchDetector.analyze(x, SR, PARAMS);
        assertEquals(420, r.finalHz, 420 * 0.005);
    }

    @Test
    public void strongSecondHarmonicStillGivesFundamental() {
        // 2nd harmonic twice as loud as the fundamental: the old method reports 600 Hz
        double[] x = silence();
        addPluck(x, 0.8, 300, 0.08, 2.0);
        addNoise(x, 0.0005);
        PitchDetector.Report r = PitchDetector.analyze(x, SR, PARAMS);
        assertEquals(300, r.finalHz, 300 * 0.005);
        assertEquals(600, r.legacy.frequencyHz, 600 * 0.01);   // old method picks the harmonic
    }

    @Test
    public void speechOnlyIsRejected() {
        double[] x = silence();
        addSpeech(x, 0.1, 1.9, 0.30, 3);
        addNoise(x, 0.0005);
        PitchDetector.Report r = PitchDetector.analyze(x, SR, PARAMS);
        assertEquals(PitchDetector.Verdict.FAILED, r.verdict);
        assertNotEquals(true, Double.isFinite(r.finalHz));
    }

    // ─────────────────────────── version 0.3 ───────────────────────────

    @Test
    public void steadyToneThatBarelyFadesIsRejected() {
        // Stable pitch but barely fading (humming, machine noise):
        // version 0.2 accepted it because the slope only had to be < 0 dB/s; version 0.3 requires < -1.5 dB/s.
        double[] x = silence();
        addTone(x, 0.8, 420, 0.15, 10.0);      // time constant 10 s ≈ -0.9 dB/s
        addNoise(x, 0.0005);
        PitchDetector.Report r = PitchDetector.analyze(x, SR, PARAMS);
        assertEquals(PitchDetector.Verdict.FAILED, r.verdict);
    }

    @Test
    public void toneThatSwellsBetweenFramesIsRejected() {
        // Stable pitch, decaying overall, but clearly swelling in the middle (like a new syllable)
        double[] x = silence();
        int start = (int) (0.8 * SR);
        for (int i = start; i < x.length; i++) {
            double t = (i - start) / (double) SR;
            double env = Math.min(1.0, t / 0.001) * Math.exp(-t / 0.15)
                    * (1 + 8 * Math.exp(-sq((t - 0.17) / 0.03)));
            x[i] += 0.15 * env * Math.sin(2 * Math.PI * 420 * t);
        }
        addNoise(x, 0.0005);
        PitchDetector.Report r = PitchDetector.analyze(x, SR, PARAMS);
        assertEquals(PitchDetector.Verdict.FAILED, r.verdict);
    }

    @Test
    public void pluckWithWeakBeatingNeighbourIsAccepted() {
        // Neighbouring spoke rings along, 5 Hz apart, 30% amplitude: mild "beating" (~5 dB).
        // The "must not rise more than 6 dB" criterion must NOT reject this case.
        double[] x = silence();
        addPluck(x, 0.8, 420, 0.15, 0.4);
        addPluck(x, 0.8, 425, 0.045, 0.4);
        addNoise(x, 0.0005);
        PitchDetector.Report r = PitchDetector.analyze(x, SR, PARAMS);
        assertEquals(420, r.finalHz, 420 * 0.005);
    }

    @Test
    public void quietPluckInQuietRoomIsDetected() {
        // The UNPROCESSED source is often quiet: pluck RMS below 0.003 (version 0.2's fixed threshold)
        double[] x = silence();
        addPluck(x, 0.8, 420, 0.004, 0.4);
        addNoise(x, 0.00003);
        PitchDetector.Report r = PitchDetector.analyze(x, SR, PARAMS);
        assertEquals(420, r.finalHz, 420 * 0.005);
    }

    @Test
    public void stiffnessCorrectionGivesLowerTensionRange() {
        double ideal = SoundTensionCalculator.tensionKgf(346, 29, 2.0, 7900);
        double pinned = SoundTensionCalculator.tensionKgfPinned(346, 29, 2.0, 7900, 200e9);
        double clamped = SoundTensionCalculator.tensionKgfClamped(346, 29, 2.0, 7900, 200e9);
        System.out.printf(java.util.Locale.US,
                "346 Hz, L=29 cm: ideal %.1f, pinned %.1f, clamped %.1f kgf%n", ideal, pinned, clamped);
        // Pinned: just subtract the constant K = π³·E·d⁴/(64·L²) ≈ 1.9 kgf
        assertEquals(ideal - 1.88, pinned, 0.05);
        // Clamped: a much larger correction
        org.junit.Assert.assertTrue(clamped < pinned - 5);
        org.junit.Assert.assertTrue(clamped > 0.6 * ideal);
    }

    // ─────────────────────────── simulated signals ───────────────────────────

    /** Sine wave rising within 1 ms, then decaying with time constant tau (seconds). */
    private static void addTone(double[] x, double startS, double f0, double amp, double tau) {
        int start = (int) (startS * SR);
        for (int i = start; i < x.length; i++) {
            double t = (i - start) / (double) SR;
            x[i] += amp * Math.min(1.0, t / 0.001) * Math.exp(-t / tau) * Math.sin(2 * Math.PI * f0 * t);
        }
    }

    private static double[] silence() {
        return new double[(int) (SR * SECONDS)];
    }

    /** Spoke sound: rises within 1 ms, decays with a 0.3 s time constant. */
    private static void addPluck(double[] x, double startS, double f0, double amp, double secondHarmonic) {
        int start = (int) (startS * SR);
        double[] harmonics = {1.0, secondHarmonic, 0.15};
        for (int i = start; i < x.length; i++) {
            double t = (i - start) / (double) SR;
            double env = Math.min(1.0, t / 0.001) * Math.exp(-t / 0.3);
            double s = 0;
            for (int h = 0; h < harmonics.length; h++) {
                s += harmonics[h] * Math.exp(-t * h * 2) * Math.sin(2 * Math.PI * f0 * (h + 1) * t);
            }
            x[i] += amp * env * s;
        }
    }

    /** Speech: pitch 150–260 Hz constantly changing, 15 harmonics, 3 formant regions. */
    private static void addSpeech(double[] x, double fromS, double toS, double amp, long seed) {
        Random rnd = new Random(seed);
        double phaseRate = 3 + rnd.nextDouble() * 2;   // intonation rate (Hz)
        double phase = 0;
        for (int i = (int) (fromS * SR); i < Math.min(x.length, (int) (toS * SR)); i++) {
            double t = i / (double) SR;
            double f0 = 200 + 50 * Math.sin(2 * Math.PI * phaseRate * t) + 10 * Math.sin(2 * Math.PI * 11 * t);
            phase += 2 * Math.PI * f0 / SR;
            double syllable = 0.5 - 0.5 * Math.cos(2 * Math.PI * 4 * t);   // 4 syllables per second
            double s = 0;
            for (int h = 1; h <= 15; h++) {
                double fh = f0 * h;
                double formant = Math.exp(-sq((fh - 500) / 200)) + 0.6 * Math.exp(-sq((fh - 1500) / 300))
                        + 0.3 * Math.exp(-sq((fh - 2500) / 400));
                s += (0.2 + formant) / h * Math.sin(h * phase);
            }
            x[i] += amp * syllable * s / 2;
        }
    }

    private static void addNoise(double[] x, double amp) {
        Random rnd = new Random(42);
        for (int i = 0; i < x.length; i++) x[i] += amp * rnd.nextGaussian();
    }

    private static double sq(double v) { return v * v; }
}
