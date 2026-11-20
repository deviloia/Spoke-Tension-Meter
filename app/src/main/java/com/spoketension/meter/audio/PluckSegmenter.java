package com.spoketension.meter.audio;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Splits a live PCM stream into one analysis segment per fresh pluck. The same samples are never
 * scanned a second time. A continuing ring is therefore not treated as a new recording onset.
 * Owned by the capture thread; call reset() on that thread whenever the selected spoke changes.
 */
public final class PluckSegmenter {
    private static final int FRAME = TapDetector.HOP;
    private static final int HISTORY_FRAMES = 32;
    private static final int PRE_FRAMES = 8;

    public static final class Segment {
        public final double[] samples;
        /** Onset position in samples, relative to samples[] (not the entire live stream). */
        public final int onsetSample;
        public final double jumpRatio;

        private Segment(double[] samples, int onsetSample, double jumpRatio) {
            this.samples = samples;
            this.onsetSample = onsetSample;
            this.jumpRatio = jumpRatio;
        }
    }

    private final int postSamples;
    private final double[] frame = new double[FRAME];
    private final double[] history = new double[HISTORY_FRAMES];
    private final double[] preRoll = new double[PRE_FRAMES * FRAME];
    private int frameSize, historySize, historyNext, preNext;
    private double[] pending;
    private int pendingSize;
    private double pendingJump;

    public PluckSegmenter(int sampleRate) {
        if (sampleRate <= 0) throw new IllegalArgumentException("sampleRate must be positive");
        // Enough samples after the strike for the 40 ms skip and all eight pitch-tracking frames.
        int needed = (int) (TapDetector.SKIP_SECONDS * sampleRate)
                + PitchTracker.FRAME + (PitchTracker.MAX_FRAMES - 1) * PitchTracker.HOP;
        postSamples = ((needed + FRAME - 1) / FRAME + 1) * FRAME;
    }

    /** Discards pending audio and warms up on four new frames before accepting another attack. */
    public void reset() {
        frameSize = historySize = historyNext = preNext = pendingSize = 0;
        pending = null;
        Arrays.fill(history, 0);
        Arrays.fill(preRoll, 0);
    }

    /**
     * Accepts exactly count fresh PCM samples. Returned segments are independent arrays and may be
     * passed to PitchDetector.analyzeAtOnset(). Partial input frames are retained across calls.
     */
    public List<Segment> accept(short[] pcm, int count) {
        if (pcm == null || count < 0 || count > pcm.length)
            throw new IllegalArgumentException("Invalid PCM sample count");
        List<Segment> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            frame[frameSize++] = pcm[i] / 32768.0;
            if (frameSize == FRAME) {
                processFrame(out);
                frameSize = 0;
            }
        }
        return out;
    }

    private void processFrame(List<Segment> out) {
        double sum = 0;
        for (double value : frame) sum += value * value;
        double rms = Math.sqrt(sum / FRAME);

        if (pending != null) {
            System.arraycopy(frame, 0, pending, pendingSize, FRAME);
            pendingSize += FRAME;
            if (pendingSize == pending.length) {
                out.add(new Segment(pending, preRoll.length, pendingJump));
                pending = null;
                pendingSize = 0;
            }
        } else if (historySize > TapDetector.LOOKBACK_FRAMES) {
            double before = 0;
            // i = 2..: skip the previous frame, which may already hold the start of this attack.
            for (int i = 2; i <= TapDetector.LOOKBACK_FRAMES + 1; i++)
                before = Math.max(before, history[(historyNext - i + HISTORY_FRAMES) % HISTORY_FRAMES]);
            double[] levels = Arrays.copyOf(history, historySize);
            Arrays.sort(levels);
            int middle = levels.length / 2;
            double noise = levels.length % 2 == 1 ? levels[middle]
                    : (levels[middle - 1] + levels[middle]) / 2;
            double jump = rms / (before + 1e-4);
            if (rms >= Math.max(TapDetector.MIN_RMS, TapDetector.NOISE_FACTOR * noise)
                    && jump >= TapDetector.MIN_JUMP_RATIO) {
                pending = new double[preRoll.length + postSamples];
                // Pre-roll is copied chronologically, including zero padding during initial warm-up.
                int tail = preRoll.length - preNext;
                System.arraycopy(preRoll, preNext, pending, 0, tail);
                System.arraycopy(preRoll, 0, pending, tail, preNext);
                System.arraycopy(frame, 0, pending, preRoll.length, FRAME);
                pendingSize = preRoll.length + FRAME;
                pendingJump = jump;
            }
        }

        history[historyNext] = rms;
        historyNext = (historyNext + 1) % HISTORY_FRAMES;
        historySize = Math.min(HISTORY_FRAMES, historySize + 1);
        System.arraycopy(frame, 0, preRoll, preNext, FRAME);
        preNext = (preNext + FRAME) % preRoll.length;
    }
}
