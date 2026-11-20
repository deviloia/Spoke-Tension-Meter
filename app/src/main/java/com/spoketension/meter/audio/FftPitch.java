package com.spoketension.meter.audio;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Shared FFT utilities.
 *
 * Two groups of functions:
 *  1. Basic tools: spectrum of one frame, peak finding, peak-position interpolation.
 *     PitchTracker uses these to track peaks across several frames.
 *  2. dominantPeak(): the OLD METHOD of the first prototype (take the largest peak
 *     in one block of audio). Kept for comparison on screen.
 *
 * No Android dependencies.
 */
public final class FftPitch {

    private FftPitch() { }

    /** A spectral peak: frequency and magnitude. */
    public static final class Peak {
        public final double frequencyHz;
        public final double magnitude;

        public Peak(double frequencyHz, double magnitude) {
            this.frequencyHz = frequencyHz;
            this.magnitude = magnitude;
        }
    }

    /**
     * Magnitude spectrum of one audio frame.
     *
     * Steps:
     *  - Subtract the mean (remove the 0 Hz component).
     *  - Apply a Hann window: tapers both ends of the frame, reducing "spectral leakage".
     *  - Zero-pad up to fftSize: appending zeros gives a smoother spectrum
     *    (no new real information, but more accurate interpolation).
     *
     * @return magnitude array; element k corresponds to frequency k * sampleRate / fftSize
     */
    public static double[] magnitudeSpectrum(double[] x, int offset, int length, int fftSize) {
        double[] re = new double[fftSize];
        double[] im = new double[fftSize];

        double mean = 0;
        for (int i = 0; i < length; i++) mean += x[offset + i];
        mean /= length;

        for (int i = 0; i < length; i++) {
            double hann = 0.5 - 0.5 * Math.cos(2.0 * Math.PI * i / (length - 1));
            re[i] = (x[offset + i] - mean) * hann;
        }

        fftInPlace(re, im);

        double[] mag = new double[fftSize / 2];
        for (int k = 0; k < mag.length; k++) mag[k] = Math.hypot(re[k], im[k]);
        return mag;
    }

    public static double binToHz(double bin, int fftSize, int sampleRate) {
        return bin * sampleRate / fftSize;
    }

    public static double hzToBin(double hz, int fftSize, int sampleRate) {
        return hz * fftSize / sampleRate;
    }

    /**
     * Parabolic interpolation around the peak at bin k, on a log scale.
     * The true peak usually lies BETWEEN two bins; interpolation gives a fractional position (e.g. 128.37).
     * Important because tension is proportional to f^2: a 1% frequency error = a 2% tension error.
     */
    public static double interpolateBin(double[] mag, int k) {
        if (k <= 0 || k >= mag.length - 1) return k;
        double a = Math.log(mag[k - 1] + 1e-20);
        double b = Math.log(mag[k] + 1e-20);
        double c = Math.log(mag[k + 1] + 1e-20);
        double denom = a - 2 * b + c;
        if (Math.abs(denom) < 1e-12) return k;
        double delta = 0.5 * (a - c) / denom;
        return k + Math.max(-0.5, Math.min(0.5, delta));
    }

    /** true if bin k is a local maximum (higher than both neighbours). */
    public static boolean isLocalMax(double[] mag, int k) {
        return k > 0 && k < mag.length - 1 && mag[k] > mag[k - 1] && mag[k] >= mag[k + 1];
    }

    /**
     * Finds up to maxCount of the largest local peaks in [minBin, maxBin],
     * sorted from largest to smallest.
     */
    public static List<Integer> topPeakBins(double[] mag, int minBin, int maxBin, int maxCount) {
        List<Integer> peaks = new ArrayList<>();
        for (int k = Math.max(1, minBin); k <= Math.min(mag.length - 2, maxBin); k++) {
            if (isLocalMax(mag, k)) peaks.add(k);
        }
        Collections.sort(peaks, (p, q) -> Double.compare(mag[q], mag[p]));
        return peaks.size() > maxCount ? new ArrayList<>(peaks.subList(0, maxCount)) : peaks;
    }

    /**
     * OLD METHOD (first prototype): one block of audio, take the largest peak in the band.
     *
     * Weakness: "largest peak" only means "loudest sound", which may be speech or the
     * spoke's 2nd harmonic. Kept for comparison with the new method.
     *
     * @return the peak found, or null if none
     */
    public static Peak dominantPeak(double[] x, int offset, int length, int sampleRate,
                                    double minHz, double maxHz) {
        int fftSize = 1;
        while (fftSize < length * 4) fftSize <<= 1;     // 4x zero-padding
        double[] mag = magnitudeSpectrum(x, offset, length, fftSize);

        int minBin = Math.max(1, (int) Math.ceil(hzToBin(minHz, fftSize, sampleRate)));
        int maxBin = Math.min(mag.length - 2, (int) Math.floor(hzToBin(maxHz, fftSize, sampleRate)));
        int best = -1;
        for (int k = minBin; k <= maxBin; k++) {
            if (best < 0 || mag[k] > mag[best]) best = k;
        }
        if (best < 0 || mag[best] < 1e-9) return null;
        double hz = binToHz(interpolateBin(mag, best), fftSize, sampleRate);
        return new Peak(hz, mag[best]);
    }

    /**
     * Radix-2 FFT (Cooley–Tukey), computed in place.
     * The array length must be a power of 2. Algorithm unchanged from the original version.
     */
    public static void fftInPlace(double[] re, double[] im) {
        int n = re.length;
        // Step 1: bit-reversal permutation
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            while ((j & bit) != 0) { j ^= bit; bit >>= 1; }
            j ^= bit;
            if (i < j) {
                double t = re[i]; re[i] = re[j]; re[j] = t;
                t = im[i]; im[i] = im[j]; im[j] = t;
            }
        }
        // Step 2: combine the "butterflies" from small to large
        for (int len = 2; len <= n; len <<= 1) {
            double angle = -2 * Math.PI / len;
            double wlRe = Math.cos(angle), wlIm = Math.sin(angle);
            for (int i = 0; i < n; i += len) {
                double wRe = 1, wIm = 0;
                for (int j = 0; j < len / 2; j++) {
                    int u = i + j, v = u + len / 2;
                    double vr = re[v] * wRe - im[v] * wIm;
                    double vi = re[v] * wIm + im[v] * wRe;
                    re[v] = re[u] - vr; im[v] = im[u] - vi;
                    re[u] += vr;        im[u] += vi;
                    double nextRe = wRe * wlRe - wIm * wlIm;
                    wIm = wRe * wlIm + wIm * wlRe;
                    wRe = nextRe;
                }
            }
        }
    }
}
