package com.spoketension.meter.audio;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Finds the moment the SPOKE IS PLUCKED in a recording.
 *
 * DIFFERENCE FROM THE OLD VERSION:
 *  - The old version took "the first time the volume crosses a threshold". If someone spoke
 *    before the pluck, the speech crossed the threshold first -> the app analysed the speech.
 *  - The new version looks for the places where the volume RISES MOST SUDDENLY. A pluck reaches
 *    full volume within a few milliseconds, while speech builds up gradually. It returns several
 *    candidates, sorted by "steepness"; PitchDetector tries them one by one.
 *
 * No Android dependencies.
 */
public final class TapDetector {

    private TapDetector() { }

    /** Each volume-measuring frame is 256 samples long (about 5.8 ms at 44.1 kHz). */
    public static final int HOP = 256;
    /**
     * Compare the current frame with 4 earlier frames (about 23 ms), skipping the frame right
     * before it, which may already hold the start of an attack split across two frames.
     */
    public static final int LOOKBACK_FRAMES = 4;
    /**
     * Volume must rise at least 3x (about +9.5 dB) to count as a pluck.
     * ponytail: was 4x; tune with real recordings if quiet plucks are still missed.
     */
    public static final double MIN_JUMP_RATIO = 3.0;
    /**
     * ABSOLUTE minimum volume of a pluck (RMS, 0..1 scale).
     * Version 0.2 used a fixed 0.003: with the UNPROCESSED audio source (no gain),
     * a light pluck could fall below it and be missed. It is now lower, and a RELATIVE
     * threshold based on the recording's own background level is added (NOISE_FACTOR).
     */
    public static final double MIN_RMS = 0.0005;
    /** A pluck must be at least 3x louder than the background (median RMS of the whole recording). */
    public static final double NOISE_FACTOR = 3.0;
    /** Skip the first 40 ms: the "click" of the finger/tool, not the spoke tone yet. */
    public static final double SKIP_SECONDS = 0.040;
    /** Two candidates must be at least 150 ms apart (avoid counting one pluck several times). */
    public static final double MIN_GAP_SECONDS = 0.150;

    public static final class Tap {
        public final int onsetSample;      // position of the pluck
        public final int analysisStart;    // where analysis starts (after skipping 40 ms)
        public final double jumpRatio;     // how many times the volume increased

        Tap(int onsetSample, int analysisStart, double jumpRatio) {
            this.onsetSample = onsetSample;
            this.analysisStart = analysisStart;
            this.jumpRatio = jumpRatio;
        }
    }

    /**
     * @param maxCandidates maximum number of candidates to return
     * @return possible plucks, steepest first; empty if there are none
     */
    public static List<Tap> findCandidates(double[] x, int sampleRate, int maxCandidates) {
        // Step 1: volume (RMS) of each 256-sample frame
        int frames = x.length / HOP;
        double[] rms = new double[frames];
        for (int f = 0; f < frames; f++) {
            double sum = 0;
            for (int i = f * HOP; i < (f + 1) * HOP; i++) sum += x[i] * x[i];
            rms[f] = Math.sqrt(sum / HOP);
        }

        // Volume threshold: the larger of the absolute level and the level relative to the background
        double minRms = Math.max(MIN_RMS, NOISE_FACTOR * median(rms));

        // Step 2: compare each frame with the loudest of frames f-5..f-2 (f-1 skipped, see LOOKBACK_FRAMES)
        List<Tap> all = new ArrayList<>();
        int skip = (int) (SKIP_SECONDS * sampleRate);
        for (int f = LOOKBACK_FRAMES + 1; f < frames; f++) {
            if (rms[f] < minRms) continue;
            double before = 0;
            // Skip frame f-1: it may already hold the start of an attack split across two frames.
            for (int b = f - LOOKBACK_FRAMES - 1; b < f - 1; b++) before = Math.max(before, rms[b]);
            double ratio = rms[f] / (before + 1e-4);   // 1e-4: avoid dividing by 0 in a very quiet room
            if (ratio >= MIN_JUMP_RATIO) {
                // The attack may have started in the skipped frame f-1; the 40 ms skip covers the extra frame.
                int onset = (f - 1) * HOP;
                all.add(new Tap(onset, onset + skip, ratio));
            }
        }

        // Step 3: sort by steepness (descending), drop candidates that are too close together
        Collections.sort(all, (a, b) -> Double.compare(b.jumpRatio, a.jumpRatio));
        int minGap = (int) (MIN_GAP_SECONDS * sampleRate);
        List<Tap> chosen = new ArrayList<>();
        for (Tap t : all) {
            boolean tooClose = false;
            for (Tap c : chosen) {
                if (Math.abs(c.onsetSample - t.onsetSample) < minGap) { tooClose = true; break; }
            }
            if (!tooClose) chosen.add(t);
            if (chosen.size() >= maxCandidates) break;
        }
        return chosen;
    }

    private static double median(double[] v) {
        if (v.length == 0) return 0;
        double[] copy = v.clone();
        java.util.Arrays.sort(copy);
        int m = copy.length / 2;
        return copy.length % 2 == 1 ? copy[m] : 0.5 * (copy[m - 1] + copy[m]);
    }
}
