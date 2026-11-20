package com.spoketension.meter.audio;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * NEW METHOD: track FFT peaks across SEVERAL consecutive frames.
 *
 * Instead of one large FFT and taking the loudest peak, the audio after the pluck is
 * split into up to 8 overlapping frames. Every peak in the first frame is a "candidate"
 * and is followed through the later frames. A candidate is accepted only if it behaves
 * like a spoke:
 *
 *   (1) Frequency stays almost constant   -> speech pitch rises and falls with intonation
 *   (2) Amplitude decays steadily         -> speech gets louder and softer irregularly
 *   (3) Peak stands out from the spectrum -> not background noise
 *   (4) Present in most frames            -> not a transient sound
 *
 * Then a HARMONIC check: if there is a valid candidate at exactly 1/2 of the chosen
 * frequency, that one is the real fundamental.
 *
 * The thresholds below are reasonable starting values and MUST be tuned with real data.
 * No Android dependencies.
 */
public final class PitchTracker {

    private PitchTracker() { }

    public static final int FRAME = 4096;          // ~93 ms per frame
    public static final int HOP = 1024;            // each frame starts ~23 ms after the previous one
    public static final int FFT_SIZE = 16384;      // 4x zero-padding -> ~2.7 Hz per bin
    public static final int MIN_FRAMES = 4;
    public static final int MAX_FRAMES = 8;        // 8 frames = about 0.26 s of audio
    public static final int SEEDS = 6;             // number of candidates taken from the first frame

    /** At each step, the peak may move at most 1.5% from the previous frame. */
    public static final double TRACK_TOLERANCE = 0.015;
    /**
     * (1) Maximum frequency variation (standard deviation / mean): 0.5%.
     * Main speech/hum filter; MatrixPencil would agree with a steady hum, so do not loosen without data
     * (a rejected pluck now reports "pitch drifts x%" when this rule is the cause).
     */
    public static final double MAX_FREQ_VARIATION = 0.005;
    /**
     * (2a) Amplitude must decay: the slope (dB/second) must be below this value.
     * Version 0.2 used 0.0, so a tone that barely fades (humming, machine noise) still passed.
     * Do NOT make it much stricter: when a neighbouring spoke rings along, "beating" makes
     * the slope measured over 0.26 s only about -3 dB/s even though the spoke decays ~30 dB/s
     * (see test pluckWithWeakBeatingNeighbourIsAccepted). Must be tuned with real data.
     */
    public static final double MAX_DECAY_DB_PER_S = -1.5;
    /**
     * (2b) Amplitude must not RISE more than 6 dB above the lowest point seen before.
     * Speech gets louder with every new syllable; a spoke only decays.
     * 6 dB (not 0) because beating with a neighbouring spoke makes the amplitude wobble slightly.
     */
    public static final double MAX_RISE_DB = 6.0;
    /** (3) The peak must be at least 12 dB above the median level of the band. */
    public static final double MIN_PROMINENCE_DB = 12.0;
    /** (4) The peak must be found in at least 75% of the frames. */
    public static final double MIN_FOUND_FRACTION = 0.75;
    /** Harmonic check: two frequencies count as "double" if they differ by less than 3%. */
    public static final double OCTAVE_TOLERANCE = 0.03;

    /** Information about one candidate, even when rejected (so the reason can be shown). */
    public static final class Candidate {
        public double frequencyHz;         // median frequency across frames
        public double variation;           // frequency variation (ratio, 0.005 = 0.5%)
        public double decayDbPerSecond;    // negative = decaying
        public double maxRiseDb;           // largest rise above the lowest earlier frame
        public double prominenceDb;
        public double meanMagnitude;
        public int framesFound;
        public int framesTotal;
        public boolean accepted;
        public String rejectReason = "";
    }

    public static final class Result {
        public double frequencyHz = Double.NaN;
        public Candidate chosen;
        public boolean octaveCorrected;
        public final List<Candidate> candidates = new ArrayList<>();
        public String message = "";

        public boolean found() { return Double.isFinite(frequencyHz); }
    }

    public static Result track(double[] x, int start, int sampleRate, double minHz, double maxHz) {
        Result result = new Result();

        int available = (x.length - start - FRAME) / HOP + 1;
        int n = Math.min(MAX_FRAMES, available);
        if (start < 0 || n < MIN_FRAMES) {
            result.message = "audio after the tap is too short";
            return result;
        }

        // Spectrum of each frame
        double[][] mags = new double[n][];
        for (int j = 0; j < n; j++) {
            mags[j] = FftPitch.magnitudeSpectrum(x, start + j * HOP, FRAME, FFT_SIZE);
        }

        int minBin = Math.max(2, (int) Math.floor(FftPitch.hzToBin(minHz, FFT_SIZE, sampleRate)));
        int maxBin = Math.min(FFT_SIZE / 2 - 3, (int) Math.ceil(FftPitch.hzToBin(maxHz, FFT_SIZE, sampleRate)));
        double noiseFloor = median(mags[0], minBin, maxBin);

        // Every peak in the first frame is a candidate
        for (int seed : FftPitch.topPeakBins(mags[0], minBin, maxBin, SEEDS)) {
            Candidate c = follow(mags, seed, sampleRate);
            c.prominenceDb = 20 * Math.log10(mags[0][seed] / (noiseFloor + 1e-12));
            judge(c);
            result.candidates.add(c);
        }

        // Among the valid candidates, pick the one with the largest mean amplitude
        Candidate best = null;
        for (Candidate c : result.candidates) {
            if (c.accepted && (best == null || c.meanMagnitude > best.meanMagnitude)) best = c;
        }
        if (best == null) {
            Candidate loudest = null;
            for (Candidate c : result.candidates)
                if (loudest == null || c.meanMagnitude > loudest.meanMagnitude) loudest = c;
            result.message = loudest == null ? "no tone in the spoke's frequency band"
                    : String.format(java.util.Locale.US, "no stable tone (strongest %.0f Hz: %s)",
                    loudest.frequencyHz, loudest.rejectReason);
            return result;
        }

        // Harmonic check: is there a valid candidate at 1/2 the frequency that is not too weak (> -20 dB)?
        // No need to check the 3rd harmonic: the search band for 30–200 kgf has maxHz/minHz
        // = sqrt(200/30) ≈ 2.58 < 3. If the fundamental is inside the band, the 3rd harmonic
        // is always OUTSIDE it, so it can never be picked by mistake.
        for (Candidate c : result.candidates) {
            if (!c.accepted || c == best) continue;
            double ratioError = Math.abs(2 * c.frequencyHz - best.frequencyHz) / best.frequencyHz;
            if (ratioError < OCTAVE_TOLERANCE && c.meanMagnitude >= 0.1 * best.meanMagnitude) {
                best = c;
                result.octaveCorrected = true;
                break;
            }
        }

        result.chosen = best;
        result.frequencyHz = best.frequencyHz;
        result.message = "ok";
        return result;
    }

    /** Follow one peak from the first frame through the following frames. */
    private static Candidate follow(double[][] mags, int seedBin, int sampleRate) {
        int n = mags.length;
        double[] freqs = new double[n];
        double[] amps = new double[n];
        double[] times = new double[n];
        int found = 0;

        double prevHz = FftPitch.binToHz(FftPitch.interpolateBin(mags[0], seedBin), FFT_SIZE, sampleRate);
        freqs[found] = prevHz;
        amps[found] = mags[0][seedBin];
        times[found] = 0;
        found++;

        for (int j = 1; j < n; j++) {
            double[] mag = mags[j];
            int lo = (int) Math.floor(FftPitch.hzToBin(prevHz * (1 - TRACK_TOLERANCE), FFT_SIZE, sampleRate));
            int hi = (int) Math.ceil(FftPitch.hzToBin(prevHz * (1 + TRACK_TOLERANCE), FFT_SIZE, sampleRate));
            lo = Math.max(1, Math.min(lo, mag.length - 2));
            hi = Math.max(lo, Math.min(hi, mag.length - 2));

            int bestBin = lo;
            for (int k = lo; k <= hi; k++) if (mag[k] > mag[bestBin]) bestBin = k;

            // Only count it as "found" if it is a real peak, not a slope at the edge of the search window
            if (FftPitch.isLocalMax(mag, bestBin)) {
                prevHz = FftPitch.binToHz(FftPitch.interpolateBin(mag, bestBin), FFT_SIZE, sampleRate);
                freqs[found] = prevHz;
                amps[found] = mag[bestBin];
                times[found] = j * (double) HOP / sampleRate;
                found++;
            }
        }

        Candidate c = new Candidate();
        c.framesFound = found;
        c.framesTotal = n;
        double[] f = Arrays.copyOf(freqs, found);
        c.frequencyHz = median(f, 0, found - 1);
        c.variation = std(f) / mean(f);
        c.meanMagnitude = mean(Arrays.copyOf(amps, found));
        c.decayDbPerSecond = found >= 2 ? decaySlope(times, amps, found) : 0;
        c.maxRiseDb = maxRiseDb(amps, found);
        return c;
    }

    /** Apply the criteria; record the reason if the candidate is rejected. */
    private static void judge(Candidate c) {
        if (c.framesFound < Math.ceil(MIN_FOUND_FRACTION * c.framesTotal)) {
            c.rejectReason = "lost after " + c.framesFound + "/" + c.framesTotal + " frames";
        } else if (c.variation > MAX_FREQ_VARIATION) {
            c.rejectReason = String.format(java.util.Locale.US,
                    "pitch drifts %.2f%% (speech-like)", c.variation * 100);
        } else if (c.decayDbPerSecond >= MAX_DECAY_DB_PER_S) {
            c.rejectReason = String.format(java.util.Locale.US,
                    "not decaying (%.1f dB/s)", c.decayDbPerSecond);
        } else if (c.maxRiseDb > MAX_RISE_DB) {
            c.rejectReason = String.format(java.util.Locale.US,
                    "gets louder by %.1f dB (speech-like)", c.maxRiseDb);
        } else if (c.prominenceDb < MIN_PROMINENCE_DB) {
            c.rejectReason = String.format(java.util.Locale.US,
                    "weak peak (%.1f dB above floor)", c.prominenceDb);
        } else {
            c.accepted = true;
        }
    }

    /** Slope (dB/second) of the amplitude over time, by linear regression. */
    private static double decaySlope(double[] t, double[] amp, int count) {
        double mt = 0, md = 0;
        double[] db = new double[count];
        for (int i = 0; i < count; i++) {
            db[i] = 20 * Math.log10(amp[i] + 1e-12);
            mt += t[i];
            md += db[i];
        }
        mt /= count;
        md /= count;
        double num = 0, den = 0;
        for (int i = 0; i < count; i++) {
            num += (t[i] - mt) * (db[i] - md);
            den += (t[i] - mt) * (t[i] - mt);
        }
        return den > 0 ? num / den : 0;
    }

    /**
     * Largest rise (dB) above the lowest point seen EARLIER; 0 if the amplitude only falls.
     * Accumulated over several frames (not just two adjacent ones), because the 93 ms frames
     * smear things out: a new syllable 15 dB louder may rise only ~5 dB per 23 ms step.
     */
    private static double maxRiseDb(double[] amp, int count) {
        double worst = 0, lowest = Double.POSITIVE_INFINITY;
        for (int i = 0; i < count; i++) {
            double db = 20 * Math.log10(amp[i] + 1e-12);
            if (i > 0) worst = Math.max(worst, db - lowest);
            lowest = Math.min(lowest, db);
        }
        return worst;
    }

    private static double mean(double[] v) {
        double s = 0;
        for (double d : v) s += d;
        return s / v.length;
    }

    private static double std(double[] v) {
        double m = mean(v), s = 0;
        for (double d : v) s += (d - m) * (d - m);
        return Math.sqrt(s / v.length);
    }

    /** Median of v[from..to] (both ends inclusive). */
    private static double median(double[] v, int from, int to) {
        double[] copy = Arrays.copyOfRange(v, from, to + 1);
        Arrays.sort(copy);
        int m = copy.length / 2;
        return copy.length % 2 == 1 ? copy[m] : 0.5 * (copy[m - 1] + copy[m]);
    }
}
