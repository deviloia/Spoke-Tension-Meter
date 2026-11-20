package com.spoketension.meter.audio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

/** Auto mode: FFT and MatrixPencil on the window after a known onset agree with the true pitch. */
public class ThreeMethodsTest {

    @Test
    public void fftAndMatrixPencilFindPluckAfterKnownOnset() {
        int fs = SignalAnalyzer.SAMPLE_RATE, onset = 8192;
        double f0 = 400;
        double[] x = new double[onset + 14000];
        for (int i = onset; i < x.length; i++) {
            double t = (i - onset) / (double) fs;
            x[i] = Math.exp(-6 * t) * (Math.sin(2 * Math.PI * f0 * t) + 0.3 * Math.sin(2 * Math.PI * 2 * f0 * t));
        }
        List<SignalAnalyzer.Window> w = SignalAnalyzer.windowAt(x, fs, onset);
        assertEquals(1, w.size());
        double[] hz = SignalAnalyzer.fftAndMatrixPencil(w, fs, 266, 688);
        assertEquals(f0, hz[0], 3);
        assertEquals(f0, hz[1], 3);
    }

    @Test
    public void tooShortAfterOnsetGivesNoWindow() {
        assertTrue(SignalAnalyzer.windowAt(new double[9000], SignalAnalyzer.SAMPLE_RATE, 8000).isEmpty());
    }
}
