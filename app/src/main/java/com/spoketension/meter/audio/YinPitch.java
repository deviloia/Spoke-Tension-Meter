package com.spoketension.meter.audio;

/**
 * The YIN algorithm (de Cheveigné & Kawahara, 2002): estimates the FUNDAMENTAL FREQUENCY
 * from the repeating PERIOD of the signal in the time domain.
 *
 * Idea: shift the signal by tau samples and compare it with itself. When tau equals
 * one period, the two copies almost coincide -> the difference is smallest.
 *
 * Role in the app: CROSS-CHECK the FFT result.
 *  - FFT can easily pick the 2nd harmonic (double frequency) by mistake if it is louder.
 *  - YIN looks for the period, so it is less prone to this error.
 *  - Note: YIN does NOT filter out speech, because voice is periodic too.
 *
 * No Android dependencies.
 */
public final class YinPitch {

    private YinPitch() { }

    /** Standard YIN threshold; smaller = stricter. */
    public static final double THRESHOLD = 0.15;

    public static final class Result {
        public final double frequencyHz;   // NaN if not found
        /** Periodicity score = 1 - CMNDF; close to 1 = very periodic. For reference only. */
        public final double score;

        public Result(double frequencyHz, double score) {
            this.frequencyHz = frequencyHz;
            this.score = score;
        }

        public boolean found() { return Double.isFinite(frequencyHz); }
    }

    public static Result estimate(double[] x, int offset, int length, int sampleRate,
                                  double minHz, double maxHz) {
        int frameLength = Math.min(length, x.length - offset);
        if (frameLength < 512) return new Result(Double.NaN, 0);

        double[] s = new double[frameLength];
        double mean = 0;
        for (int i = 0; i < frameLength; i++) mean += x[offset + i];
        mean /= frameLength;
        for (int i = 0; i < frameLength; i++) s[i] = x[offset + i] - mean;

        // Periods (in samples) corresponding to the frequency band being searched
        int minTau = Math.max(2, (int) Math.floor(sampleRate / maxHz));
        int maxTau = Math.min(frameLength / 2 - 1, (int) Math.ceil(sampleRate / minHz));
        if (maxTau <= minTau) return new Result(Double.NaN, 0);

        // Steps 1-2: difference function d(tau) and its cumulative mean normalised form (CMNDF)
        double[] cmnd = new double[maxTau + 1];
        cmnd[0] = 1;
        double cumulative = 0;
        int compareLength = frameLength - maxTau;      // fixed so every tau is compared fairly
        for (int tau = 1; tau <= maxTau; tau++) {
            double sum = 0;
            for (int i = 0; i < compareLength; i++) {
                double d = s[i] - s[i + tau];
                sum += d * d;
            }
            cumulative += sum;
            cmnd[tau] = cumulative > 0 ? sum * tau / cumulative : 1.0;
        }

        // Step 3: the SMALLEST tau with CMNDF below the threshold, then slide down to the bottom of that dip
        int candidate = -1;
        for (int tau = minTau; tau <= maxTau; tau++) {
            if (cmnd[tau] < THRESHOLD) {
                while (tau + 1 <= maxTau && cmnd[tau + 1] < cmnd[tau]) tau++;
                candidate = tau;
                break;
            }
        }
        // Nothing below the threshold: take the lowest point (less reliable)
        if (candidate < 0) {
            double lowest = Double.POSITIVE_INFINITY;
            for (int tau = minTau; tau <= maxTau; tau++) {
                if (cmnd[tau] < lowest) { lowest = cmnd[tau]; candidate = tau; }
            }
        }
        if (candidate < 0 || cmnd[candidate] > 0.5) return new Result(Double.NaN, 0);

        // Step 4: parabolic interpolation for a fractional period
        double tau = candidate;
        if (candidate > minTau && candidate < maxTau) {
            double a = cmnd[candidate - 1], b = cmnd[candidate], c = cmnd[candidate + 1];
            double denom = a - 2 * b + c;
            if (Math.abs(denom) > 1e-12) {
                tau += Math.max(-0.5, Math.min(0.5, 0.5 * (a - c) / denom));
            }
        }
        return new Result(sampleRate / tau, Math.max(0, 1 - cmnd[candidate]));
    }
}
