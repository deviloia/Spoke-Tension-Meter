package com.spoketension.meter.audio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Live-stream boundaries are exercised with PCM chunks, then the real FFT/YIN acceptance pipeline. */
public class PluckSegmenterTest {
    private static final int SR = 44100;
    private static final PitchDetector.Params PARAMS = new PitchDetector.Params(20.5, 2.0, 7850);

    @Test public void onePluckProducesOneUsableSegmentEvenAcrossUnevenChunks() {
        double[] x = new double[SR * 3];
        pluck(x, 0.10, 420, 0.15);
        noise(x);
        List<PluckSegmenter.Segment> segments = feed(new PluckSegmenter(SR), x, 0, x.length, 159);
        assertEquals(1, segments.size());
        assertUsable(420, segments.get(0));
    }

    @Test public void firstPluckCanBeDetectedSoonAfterBaselineWarmup() {
        double[] x = new double[SR];
        pluck(x, 0.035, 420, 0.15);
        noise(x);
        List<PluckSegmenter.Segment> segments = feed(new PluckSegmenter(SR), x, 0, x.length, 1024);
        assertEquals(1, segments.size());
        assertUsable(420, segments.get(0));
    }

    @Test public void consecutivePlucksDoNotNeedTwoSecondRecordingWindows() {
        double[] x = new double[SR * 2];
        pluck(x, 0.10, 420, 0.15);
        pluck(x, 0.70, 480, 0.15);
        noise(x);
        List<PluckSegmenter.Segment> segments = feed(new PluckSegmenter(SR), x, 0, x.length, 1024);
        assertEquals(2, segments.size());
        assertUsable(420, segments.get(0));
        assertUsable(480, segments.get(1));
    }

    @Test public void resetDuringRingingNeedsANewAttackEvenForTheSameFrequency() {
        double[] x = new double[SR * 3];
        pluck(x, 0.10, 420, 0.15);
        pluck(x, 1.30, 420, 0.15);
        noise(x);
        PluckSegmenter segmenter = new PluckSegmenter(SR);
        int resetAt = SR / 2;
        List<PluckSegmenter.Segment> first = feed(segmenter, x, 0, resetAt, 1024);
        assertEquals(1, first.size());
        segmenter.reset();
        List<PluckSegmenter.Segment> next = feed(segmenter, x, resetAt, x.length, 1024);
        assertEquals(1, next.size());
        assertUsable(420, next.get(0));
    }

    @Test public void resetDiscardsAPendingPluckInsteadOfSavingItForTheNextSpoke() {
        double[] x = new double[SR * 2];
        pluck(x, 0.10, 420, 0.15);
        noise(x);
        PluckSegmenter segmenter = new PluckSegmenter(SR);
        int resetAt = SR / 5;
        assertTrue(feed(segmenter, x, 0, resetAt, 1024).isEmpty());
        segmenter.reset();
        assertTrue(feed(segmenter, x, resetAt, x.length, 1024).isEmpty());
    }

    @Test public void beatingNeighbourDoesNotCreateExtraPlucks() {
        double[] x = new double[SR * 3];
        pluck(x, 0.10, 420, 0.15);
        pluck(x, 0.10, 425, 0.045);
        noise(x);
        List<PluckSegmenter.Segment> segments = feed(new PluckSegmenter(SR), x, 0, x.length, 1024);
        assertEquals(1, segments.size());
        assertUsable(420, segments.get(0));
    }

    @Test public void existingToneAndQuietNoiseDoNotCreateAnOnsetAtStreamStart() {
        double[] x = new double[SR * 2];
        for (int i = 0; i < x.length; i++) x[i] = 0.10 * Math.sin(2 * Math.PI * 420 * i / SR);
        noise(x);
        assertTrue(feed(new PluckSegmenter(SR), x, 0, x.length, 1024).isEmpty());
    }

    @Test public void shortOnsetAnalysisReturnsFailedInsteadOfReadingPastTheBuffer() {
        PitchDetector.Report report = PitchDetector.analyzeAtOnset(new double[100], SR, PARAMS, 50);
        assertEquals(PitchDetector.Verdict.FAILED, report.verdict);
        assertTrue(Double.isNaN(report.finalHz));
    }

    private static void assertUsable(double hz, PluckSegmenter.Segment segment) {
        PitchDetector.Report report = PitchDetector.analyzeAtOnset(
                segment.samples, SR, PARAMS, segment.onsetSample);
        assertEquals(report.explanation, hz, report.finalHz, hz * 0.005);
        assertNotEquals(report.explanation, PitchDetector.Verdict.FAILED, report.verdict);
        assertNotEquals(report.explanation, PitchDetector.Verdict.DISAGREE, report.verdict);
        assertTrue(report.tracked.chosen.framesTotal == PitchTracker.MAX_FRAMES);
    }

    private static List<PluckSegmenter.Segment> feed(PluckSegmenter segmenter, double[] x,
                                                    int from, int to, int chunkSize) {
        List<PluckSegmenter.Segment> segments = new ArrayList<>();
        short[] block = new short[chunkSize];
        for (int offset = from; offset < to; offset += chunkSize) {
            int count = Math.min(chunkSize, to - offset);
            for (int i = 0; i < count; i++)
                block[i] = (short) Math.max(Short.MIN_VALUE,
                        Math.min(Short.MAX_VALUE, Math.round(x[offset + i] * 32768)));
            segments.addAll(segmenter.accept(block, count));
        }
        return segments;
    }

    private static void pluck(double[] x, double start, double hz, double amplitude) {
        int onset = (int) (start * SR);
        for (int i = onset; i < x.length; i++) {
            double t = (i - onset) / (double) SR;
            double envelope = Math.min(1, t / 0.001) * Math.exp(-t / 0.3);
            x[i] += amplitude * envelope * (Math.sin(2 * Math.PI * hz * t)
                    + 0.4 * Math.exp(-2 * t) * Math.sin(4 * Math.PI * hz * t));
        }
    }

    private static void noise(double[] x) {
        Random random = new Random(42);
        for (int i = 0; i < x.length; i++) x[i] += 0.00003 * random.nextGaussian();
    }
}
