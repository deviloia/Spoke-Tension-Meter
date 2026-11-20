package com.spoketension.meter.audio;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * MatrixPencil(New): a plucked spoke is modelled as a sum of damped sinusoids.
 * NRPE (Non-convex Robust Parameter Estimation, Xie/Liu/Mansour/Boufounos, MERL TR2020-052, Algorithm 2)
 * demixes the Hankel matrix into low-rank (damped exponentials) + sparse spikes (clicks), then the classical
 * matrix pencil with order r gives each tone's frequency, damping and amplitude. Chosen over SAMP: on
 * synthetic 2 s recordings it held up with a beating neighbour spoke and with speech, where SAMP's
 * noise threshold (taken from the pre-onset audio) rejected the spoke.
 * Input is real audio, so every real damped tone appears as a conjugate pole pair: the order counts both,
 * reports list only the positive-frequency twin, and its real amplitude is 2|b|.
 * Conditioning: band-pass around the tension band, then decimate, so matrices stay phone-sized.
 * Selection: poles inside the band and the damping gate; the largest amplitude wins, then the octave check
 * walks down to a qualifying f/2 or f/3. No pole -> NO_RESULT.
 */
public final class MatrixPencil {
    private MatrixPencil() {}

    // Calibration knobs.
    public static final int DECIMATE = 12;            // 44100/12 = 3675 Hz, Nyquist 1837 Hz
    static final int MAX_N = 640;              // cap on decimated samples (cost ~N^3; ~0.16 s desktop per window)
    static final int BPF_TAPS = 961;           // ~150 Hz transition width at 44.1 kHz
    static final double BPF_LO_FACTOR = 0.8, BPF_HI_FACTOR = 1.2, BPF_MAX_HZ = 1650;
    static final int NRPE_RANK = 20;           // r = 10 real damped tones (conjugate pairs)
    static final int NRPE_MAX_ITER = 30;
    static final double NRPE_TOL = 1e-4;
    static final double NRPE_SPIKE_K = 3;      // lambda2 = K * robust sigma of the first low-rank residual
    // Damping gate in 1/s (dB/s = 8.686 * alpha). Min: a spoke only decays; 0.173/s = 1.5 dB/s, the same
    // "must decay" rule as Sally's PitchTracker, so steady hum and growing (speech-like) poles are rejected.
    // Max: 15/s (tau >= ~67 ms). First real plucks: spoke ring alpha ~3 (tau ~0.3 s); strike/rim thumps
    // alpha 35-45 (tau 20-30 ms) were winning on initial amplitude. Tune from the CSV's alpha column.
    public static final double alphaMin = 0.173, alphaMax = 15;
    // Octave check: a qualifying pole at f/2 or f/3 (within 1%, >= 10% of the amplitude) is the fundamental.
    // 1%, not 3%: a stiff spoke's 2nd partial sits only ~0.5% sharp, and 3% let speech poles near f/2 through.
    public static final double OCTAVE_TOL = 0.01, OCTAVE_MIN_REL_AMP = 0.1;

    /** Try each pluck window (steepest attack first) until one gives a pole. */
    public static Report measure(List<SignalAnalyzer.Window> windows, int fs, double lo, double hi) {
        Report first = null;
        for (SignalAnalyzer.Window w : windows) {
            Report r = nrpe(w.samples, fs, lo, hi);
            r.window = w;
            if (Double.isFinite(r.freqHz)) return r;
            if (first == null) first = r;
        }
        return first != null ? first : fail(new Report(), "no pluck detected");
    }

    static final class Poles {
        double[] re, im, bRe, bIm;
    }

    public static final class PoleRow {
        public double freqHz, alphaPerS, tauS, amp, ampRel, share;
        public boolean selected;
        public String reason = "";
    }

    public static final class Report {
        public double freqHz = Double.NaN, score = Double.NaN;  // score = selected pole's energy share
        public String status = "";
        public double fsDecimated, bandLo, bandHi, filterLo, filterHi, lambda2 = Double.NaN;
        public double octaveFromHz = Double.NaN;   // largest-amplitude pole before the octave check moved it
        public SignalAnalyzer.Window window;
        public int n, pencilL, order, nrpeP = -1, nrpeRank = -1;
        public final List<PoleRow> poles = new ArrayList<>();
    }

    public static Report nrpe(double[] samples, int fs, double lo, double hi) {
        Report r = new Report();
        double[] x = condition(samples, fs, lo, hi, r);
        if (x == null) return fail(r, "window too short for band-pass + decimation");
        r.nrpeP = x.length / 6;
        r.nrpeRank = NRPE_RANK;
        r.pencilL = x.length / 3;
        r.order = NRPE_RANK;
        Poles p = pencil(nrpeClean(x, NRPE_RANK, r), r.pencilL, NRPE_RANK);
        if (p == null) return fail(r, "silent window");
        return select(r, p);
    }

    private static Report fail(Report r, String why) {
        r.status = "NO_RESULT: " + why;
        return r;
    }

    // ---------------------------------------------------------------- pipeline pieces

    static double[] condition(double[] in, int fs, double lo, double hi, Report r) {
        r.bandLo = lo;
        r.bandHi = hi;
        r.filterLo = lo * BPF_LO_FACTOR;
        r.filterHi = Math.min(hi * BPF_HI_FACTOR, BPF_MAX_HZ);
        r.fsDecimated = (double) fs / DECIMATE;
        double[] x = bandpassDecimate(in, fs, r.filterLo, r.filterHi);
        r.n = x == null ? 0 : x.length;
        return x;
    }

    /** Windowed-sinc band-pass (unit gain at band centre) evaluated only at the kept samples; mean removed. */
    static double[] bandpassDecimate(double[] in, int fs, double fl, double fh) {
        if (in == null) return null;
        int n = Math.min(MAX_N, (in.length - BPF_TAPS) / DECIMATE);
        if (n < 64) return null;
        int half = BPF_TAPS / 2;
        double a = fl / fs, b = fh / fs, mid = (a + b) / 2, gr = 0, gi = 0;
        double[] h = new double[BPF_TAPS];
        for (int k = 0; k < BPF_TAPS; k++) {
            int m = k - half;
            double bp = m == 0 ? 2 * (b - a)
                    : (Math.sin(2 * Math.PI * b * m) - Math.sin(2 * Math.PI * a * m)) / (Math.PI * m);
            h[k] = bp * (0.54 - 0.46 * Math.cos(2 * Math.PI * k / (BPF_TAPS - 1)));
            gr += h[k] * Math.cos(2 * Math.PI * mid * k);
            gi -= h[k] * Math.sin(2 * Math.PI * mid * k);
        }
        double gain = Math.hypot(gr, gi);
        double[] out = new double[n];
        double mean = 0;
        for (int i = 0; i < n; i++) {
            double acc = 0;
            for (int k = 0, base = i * DECIMATE; k < BPF_TAPS; k++) acc += h[k] * in[base + k];
            out[i] = acc / gain;
            mean += out[i];
        }
        mean /= n;
        for (int i = 0; i < n; i++) out[i] -= mean;
        return out;
    }

    /** NRPE Algorithm 2: coordinate descent on 1/2||Y-H(x)-S||^2 + l2||S||_1 s.t. rank H(x) <= r. */
    static double[] nrpeClean(double[] y, int r, Report rep) {
        int n = y.length, p = n / 6, rows = n - p, cols = p + 1;
        double[][] s = new double[rows][cols];
        double[] x = y;
        double lambda = -1, prevLoss = 0;
        for (int it = 0; it < NRPE_MAX_ITER; it++) {
            double[][] ys = new double[rows][cols];
            for (int i = 0; i < rows; i++)
                for (int j = 0; j < cols; j++) ys[i][j] = y[i + j] - s[i][j];
            x = revDM(lowRank(hankel(revDM(ys, n), rows, cols), r), n);
            if (lambda < 0) {
                double[] abs = new double[n];
                for (int i = 0; i < n; i++) abs[i] = Math.abs(y[i] - x[i]);
                lambda = NRPE_SPIKE_K * 1.4826 * median(abs) + 1e-12;
            }
            double loss = 0;
            for (int i = 0; i < rows; i++)
                for (int j = 0; j < cols; j++) {
                    double v = y[i + j] - x[i + j];
                    s[i][j] = Math.signum(v) * Math.max(Math.abs(v) - lambda, 0);
                    double e = v - s[i][j];
                    loss += 0.5 * e * e + lambda * Math.abs(s[i][j]);
                }
            if (it > 0 && Math.abs(loss - prevLoss) <= NRPE_TOL * Math.abs(loss)) break;
            prevLoss = loss;
        }
        rep.lambda2 = lambda;
        return x;
    }

    /** Classical matrix pencil on x with pencil parameter L and order k. */
    static Poles pencil(double[] x, int L, int k) {
        int m = x.length - L;
        double[][] g = new double[L][L]; // Y0^T Y0, Y0[i][j] = x[i+j]
        for (int a = 0; a < L; a++)
            for (int b = a; b < L; b++) {
                double sum = 0;
                for (int i = 0; i < m; i++) sum += x[i + a] * x[i + b];
                g[a][b] = g[b][a] = sum;
            }
        double[][] v = new double[L][L];
        double[] ev = symEig(g, v);
        double[] sv = new double[L];
        for (int i = 0; i < L; i++) sv[i] = Math.sqrt(Math.max(ev[i], 0));
        if (!(sv[0] > 0)) return null;
        k = Math.max(1, Math.min(k, L));
        while (k > 1 && sv[k - 1] <= 1e-10 * sv[0]) k--;

        double[][] u = new double[m][k], y1v = new double[m][k];
        for (int i = 0; i < m; i++)
            for (int c = 0; c < k; c++) {
                double s0 = 0, s1 = 0;
                for (int j = 0; j < L; j++) {
                    s0 += x[i + j] * v[j][c];
                    s1 += x[i + j + 1] * v[j][c];
                }
                u[i][c] = s0 / sv[c];
                y1v[i][c] = s1;
            }
        double[][] a = new double[k][k]; // A = S^-1 U^T Y1 V
        for (int r = 0; r < k; r++)
            for (int c = 0; c < k; c++) {
                double sum = 0;
                for (int i = 0; i < m; i++) sum += u[i][r] * y1v[i][c];
                a[r][c] = sum / sv[r];
            }

        Poles p = new Poles();
        p.re = new double[k];
        p.im = new double[k];
        eigvals(a, p.re, p.im);
        double[][] qr = new double[k][k], qi = new double[k][k];
        for (int c = 0; c < k; c++) eigvec(a, p.re[c], p.im[c], qr, qi, c);
        double[][] ir = new double[k][k], ii = new double[k][k];
        for (int c = 0; c < k; c++) {
            double[] br = new double[k], bi = new double[k];
            br[c] = 1;
            csolve(copy(qr), copy(qi), br, bi);
            for (int r = 0; r < k; r++) { ir[r][c] = br[r]; ii[r][c] = bi[r]; }
        }
        // Amplitudes: b_c = Z[0][c] * (Q^-1 V^T)[c][0], with the first row of the left modes Z = U S Q.
        p.bRe = new double[k];
        p.bIm = new double[k];
        for (int c = 0; c < k; c++) {
            double zr = 0, zi = 0, wr = 0, wi = 0;
            for (int r = 0; r < k; r++) {
                zr += u[0][r] * sv[r] * qr[r][c];
                zi += u[0][r] * sv[r] * qi[r][c];
                wr += ir[c][r] * v[0][r];
                wi += ii[c][r] * v[0][r];
            }
            p.bRe[c] = zr * wr - zi * wi;
            p.bIm[c] = zr * wi + zi * wr;
        }
        return p;
    }

    /** List positive-frequency poles with a reason each; pick the largest-amplitude survivor, then octave check. */
    static Report select(Report r, Poles p) {
        double fs = r.fsDecimated, maxAmp = 0, sumAmp = 0;
        List<PoleRow> cands = new ArrayList<>();
        for (int c = 0; c < p.re.length; c++) {
            if (p.im[c] < -1e-12) continue; // conjugate twin of a listed pole
            PoleRow row = new PoleRow();
            double mag = Math.hypot(p.re[c], p.im[c]);
            boolean real = Math.abs(p.im[c]) <= 1e-12;
            row.freqHz = Math.atan2(Math.abs(p.im[c]), p.re[c]) * fs / (2 * Math.PI);
            row.alphaPerS = -Math.log(mag) * fs;
            row.tauS = 1 / row.alphaPerS;
            row.amp = (real ? 1 : 2) * Math.hypot(p.bRe[c], p.bIm[c]);
            if (real) row.reason = "real pole (no oscillation)";
            else if (row.freqHz < r.bandLo || row.freqHz > r.bandHi) row.reason = "outside tension band";
            else if (row.alphaPerS < alphaMin) row.reason = "not decaying (steady hum / growing, speech-like)";
            else if (row.alphaPerS > alphaMax) row.reason = "decays too fast (click)";
            else cands.add(row);
            r.poles.add(row);
            maxAmp = Math.max(maxAmp, row.amp);
            sumAmp += row.amp;
        }
        for (PoleRow row : r.poles) {
            row.ampRel = row.amp / (maxAmp + 1e-300);
            row.share = row.amp / (sumAmp + 1e-300);
        }
        r.poles.sort(Comparator.comparingDouble(row -> row.freqHz));
        if (cands.isEmpty()) {
            boolean anyInBand = false;
            for (PoleRow row : r.poles)
                anyInBand |= !row.reason.startsWith("real") && row.freqHz >= r.bandLo && row.freqHz <= r.bandHi;
            return fail(r, anyInBand ? "every in-band pole rejected (see reasons)"
                    : String.format(java.util.Locale.US, "no pole inside %.0f-%.0f Hz", r.bandLo, r.bandHi));
        }
        // Pick by energy over the window, amp^2 * (1 - e^{-2 alpha T}) / (2 alpha), not by initial amplitude:
        // a long ring beats a loud blip, as it does for FFT and Sally.
        double win = r.n / fs;
        PoleRow best = cands.get(0);
        for (PoleRow row : cands) if (energy(row, win) > energy(best, win)) best = row;
        double loudest = best.freqHz;
        for (boolean moved = true; moved; ) { // walk down while a subharmonic f/2 or f/3 qualifies
            moved = false;
            for (int div = 2; div <= 3 && !moved; div++)
                for (PoleRow row : cands)
                    if (row != best && Math.abs(div * row.freqHz - best.freqHz) < OCTAVE_TOL * best.freqHz
                            && row.amp >= OCTAVE_MIN_REL_AMP * best.amp) { best = row; moved = true; break; }
        }
        for (PoleRow row : cands) row.reason = "not selected (amplitude / harmonic of selected)";
        best.selected = true;
        best.reason = "selected";
        if (best.freqHz != loudest) {
            r.octaveFromHz = loudest;
            best.reason = String.format(java.util.Locale.US, "selected (fundamental of loudest %.1f Hz)", loudest);
        }
        r.freqHz = best.freqHz;
        double total = 0; // score = selected pole's share of the candidates' window energy (the selection criterion)
        for (PoleRow row : cands) total += energy(row, win);
        r.score = energy(best, win) / (total + 1e-300);
        r.status = "OK";
        return r;
    }

    static double energy(PoleRow p, double windowS) {
        double a2 = 2 * p.alphaPerS;
        return p.amp * p.amp * (Math.abs(a2) < 1e-9 ? windowS : (1 - Math.exp(-a2 * windowS)) / a2);
    }

    // ---------------------------------------------------------------- Hankel helpers

    static double[][] hankel(double[] x, int rows, int cols) {
        double[][] h = new double[rows][cols];
        for (int i = 0; i < rows; i++) System.arraycopy(x, i, h[i], 0, cols);
        return h;
    }

    /** Reverse diagonal mean: average each anti-diagonal back to a signal. */
    static double[] revDM(double[][] h, int n) {
        double[] x = new double[n];
        int[] cnt = new int[n];
        for (int i = 0; i < h.length; i++)
            for (int j = 0; j < h[i].length; j++) { x[i + j] += h[i][j]; cnt[i + j]++; }
        for (int i = 0; i < n; i++) x[i] /= cnt[i];
        return x;
    }

    /** Best rank-r approximation H V_r V_r^T (V from the Gram matrix; fine since only the top r are kept). */
    static double[][] lowRank(double[][] h, int r) {
        int rows = h.length, cols = h[0].length;
        double[][] g = new double[cols][cols];
        for (int a = 0; a < cols; a++)
            for (int b = a; b < cols; b++) {
                double sum = 0;
                for (int i = 0; i < rows; i++) sum += h[i][a] * h[i][b];
                g[a][b] = g[b][a] = sum;
            }
        double[][] v = new double[cols][cols];
        symEig(g, v);
        r = Math.min(r, cols);
        double[][] out = new double[rows][cols];
        double[] t = new double[r];
        for (int i = 0; i < rows; i++) {
            for (int c = 0; c < r; c++) {
                double sum = 0;
                for (int j = 0; j < cols; j++) sum += h[i][j] * v[j][c];
                t[c] = sum;
            }
            for (int j = 0; j < cols; j++) {
                double sum = 0;
                for (int c = 0; c < r; c++) sum += t[c] * v[j][c];
                out[i][j] = sum;
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- linear algebra

    /** Cyclic Jacobi on symmetric a (destroyed). Eigenvalues descending; v gets matching eigenvector columns. */
    static double[] symEig(double[][] a, double[][] v) {
        int n = a.length;
        double[][] w = new double[n][n];
        for (int i = 0; i < n; i++) w[i][i] = 1;
        double scale = 0;
        for (double[] row : a) for (double e : row) scale += e * e;
        for (int sweep = 0; sweep < 60; sweep++) {
            double off = 0;
            for (int p = 0; p < n; p++) for (int q = p + 1; q < n; q++) off += a[p][q] * a[p][q];
            if (off <= 1e-26 * scale) break;
            for (int p = 0; p < n; p++)
                for (int q = p + 1; q < n; q++) {
                    if (Math.abs(a[p][q]) < 1e-300) continue;
                    double theta = (a[q][q] - a[p][p]) / (2 * a[p][q]);
                    double t = theta == 0 ? 1 : Math.signum(theta) / (Math.abs(theta) + Math.sqrt(theta * theta + 1));
                    double c = 1 / Math.sqrt(t * t + 1), s = t * c;
                    for (int k = 0; k < n; k++) {
                        double kp = a[k][p], kq = a[k][q];
                        a[k][p] = c * kp - s * kq;
                        a[k][q] = s * kp + c * kq;
                    }
                    for (int k = 0; k < n; k++) {
                        double pk = a[p][k], qk = a[q][k];
                        a[p][k] = c * pk - s * qk;
                        a[q][k] = s * pk + c * qk;
                    }
                    for (int k = 0; k < n; k++) {
                        double kp = w[k][p], kq = w[k][q];
                        w[k][p] = c * kp - s * kq;
                        w[k][q] = s * kp + c * kq;
                    }
                }
        }
        Integer[] idx = new Integer[n];
        for (int i = 0; i < n; i++) idx[i] = i;
        Arrays.sort(idx, (i, j) -> Double.compare(a[j][j], a[i][i]));
        double[] ev = new double[n];
        for (int c = 0; c < n; c++) {
            ev[c] = a[idx[c]][idx[c]];
            for (int r = 0; r < n; r++) v[r][c] = w[r][idx[c]];
        }
        return ev;
    }

    /** Eigenvalues of a real square matrix: complex shifted QR (Givens) with deflation. Fine for K <= ~40. */
    static void eigvals(double[][] a, double[] lr, double[] li) {
        int n = a.length;
        double[][] hr = copy(a), hi = new double[n][n];
        double norm = 1e-300;
        for (double[] row : a) for (double e : row) norm += e * e;
        norm = Math.sqrt(norm);
        int[] gj = new int[n * n], gi = new int[n * n];
        double[] gc = new double[n * n], gsr = new double[n * n], gsi = new double[n * n];
        int iter = 0;
        while (n > 0) {
            if (n == 1) { lr[0] = hr[0][0]; li[0] = hi[0][0]; break; }
            double off = 0;
            for (int j = 0; j < n - 1; j++) off += hr[n - 1][j] * hr[n - 1][j] + hi[n - 1][j] * hi[n - 1][j];
            if (Math.sqrt(off) <= 1e-14 * norm || iter >= 300) {
                lr[n - 1] = hr[n - 1][n - 1];
                li[n - 1] = hi[n - 1][n - 1];
                n--;
                iter = 0;
                continue;
            }
            iter++;
            // Wilkinson shift: eigenvalue of trailing 2x2 closest to its corner.
            double ar = hr[n - 2][n - 2], ai = hi[n - 2][n - 2], br = hr[n - 2][n - 1], bi = hi[n - 2][n - 1];
            double cr = hr[n - 1][n - 2], ci = hi[n - 1][n - 2], dr = hr[n - 1][n - 1], di = hi[n - 1][n - 1];
            double hr2 = (ar - dr) / 2, hi2 = (ai - di) / 2;
            double[] sq = csqrt(hr2 * hr2 - hi2 * hi2 + br * cr - bi * ci, 2 * hr2 * hi2 + br * ci + bi * cr);
            double mr = (ar + dr) / 2, mi = (ai + di) / 2;
            double m1r = mr + sq[0], m1i = mi + sq[1], m2r = mr - sq[0], m2i = mi - sq[1];
            double mur, mui;
            if (Math.hypot(m1r - dr, m1i - di) <= Math.hypot(m2r - dr, m2i - di)) { mur = m1r; mui = m1i; }
            else { mur = m2r; mui = m2i; }
            if (iter % 30 == 0) mur += 0.75 * Math.sqrt(off); // exceptional shift
            for (int i = 0; i < n; i++) { hr[i][i] -= mur; hi[i][i] -= mui; }
            int g = 0;
            for (int j = 0; j < n - 1; j++)
                for (int i = j + 1; i < n; i++) {
                    double xr = hr[j][j], xi = hi[j][j], yr = hr[i][j], yi = hi[i][j];
                    double ax = Math.hypot(xr, xi), ay = Math.hypot(yr, yi);
                    if (ay == 0) continue;
                    double rr = Math.hypot(ax, ay), c = ax / rr;
                    double pr = ax == 0 ? 1 : xr / ax, pi = ax == 0 ? 0 : xi / ax;
                    double sr = (pr * yr + pi * yi) / rr, si = (pi * yr - pr * yi) / rr;
                    for (int k = j; k < n; k++) { // rows: j' = c j + s i ; i' = -conj(s) j + c i
                        double ujr = hr[j][k], uji = hi[j][k], uir = hr[i][k], uii = hi[i][k];
                        hr[j][k] = c * ujr + sr * uir - si * uii;
                        hi[j][k] = c * uji + sr * uii + si * uir;
                        hr[i][k] = -(sr * ujr + si * uji) + c * uir;
                        hi[i][k] = -(sr * uji - si * ujr) + c * uii;
                    }
                    gj[g] = j; gi[g] = i; gc[g] = c; gsr[g] = sr; gsi[g] = si; g++;
                }
            for (int t = 0; t < g; t++) { // R Q: cols j' = c j + conj(s) i ; i' = -s j + c i
                int j = gj[t], i = gi[t];
                double c = gc[t], sr = gsr[t], si = gsi[t];
                for (int k = 0; k < n; k++) {
                    double ujr = hr[k][j], uji = hi[k][j], uir = hr[k][i], uii = hi[k][i];
                    hr[k][j] = c * ujr + sr * uir + si * uii;
                    hi[k][j] = c * uji + sr * uii - si * uir;
                    hr[k][i] = -(sr * ujr - si * uji) + c * uir;
                    hi[k][i] = -(sr * uji + si * ujr) + c * uii;
                }
            }
            for (int i = 0; i < n; i++) { hr[i][i] += mur; hi[i][i] += mui; }
        }
    }

    /** Eigenvector for eigenvalue (lr, li) by inverse iteration, stored as column c of (qr, qi). */
    static void eigvec(double[][] a, double lr, double li, double[][] qr, double[][] qi, int c) {
        int n = a.length;
        double shift = lr + 1e-9 * (1 + Math.hypot(lr, li));
        double[] br = new double[n], bi = new double[n];
        Arrays.fill(br, 1 / Math.sqrt(n));
        for (int it = 0; it < 2; it++) {
            double[][] mr = copy(a), mi = new double[n][n];
            for (int i = 0; i < n; i++) { mr[i][i] -= shift; mi[i][i] = -li; }
            csolve(mr, mi, br, bi);
            double nn = 0;
            for (int i = 0; i < n; i++) nn += br[i] * br[i] + bi[i] * bi[i];
            nn = Math.sqrt(nn) + 1e-300;
            for (int i = 0; i < n; i++) { br[i] /= nn; bi[i] /= nn; }
        }
        for (int i = 0; i < n; i++) { qr[i][c] = br[i]; qi[i][c] = bi[i]; }
    }

    /** Complex Gaussian elimination with partial pivoting; solution overwrites (br, bi). */
    static void csolve(double[][] ar, double[][] ai, double[] br, double[] bi) {
        int n = br.length;
        for (int col = 0; col < n; col++) {
            int piv = col;
            for (int r = col + 1; r < n; r++)
                if (Math.hypot(ar[r][col], ai[r][col]) > Math.hypot(ar[piv][col], ai[piv][col])) piv = r;
            double[] t = ar[col]; ar[col] = ar[piv]; ar[piv] = t;
            t = ai[col]; ai[col] = ai[piv]; ai[piv] = t;
            double tb = br[col]; br[col] = br[piv]; br[piv] = tb;
            tb = bi[col]; bi[col] = bi[piv]; bi[piv] = tb;
            if (Math.hypot(ar[col][col], ai[col][col]) < 1e-150) { ar[col][col] = 1e-150; ai[col][col] = 0; }
            double pr = ar[col][col], pi = ai[col][col], den = pr * pr + pi * pi;
            for (int r = col + 1; r < n; r++) {
                double fr = (ar[r][col] * pr + ai[r][col] * pi) / den;
                double fi = (ai[r][col] * pr - ar[r][col] * pi) / den;
                if (fr == 0 && fi == 0) continue;
                for (int j = col; j < n; j++) {
                    double xr = ar[col][j], xi = ai[col][j];
                    ar[r][j] -= fr * xr - fi * xi;
                    ai[r][j] -= fr * xi + fi * xr;
                }
                double xr = br[col], xi = bi[col];
                br[r] -= fr * xr - fi * xi;
                bi[r] -= fr * xi + fi * xr;
            }
        }
        for (int r = n - 1; r >= 0; r--) {
            double sr = br[r], si = bi[r];
            for (int j = r + 1; j < n; j++) {
                sr -= ar[r][j] * br[j] - ai[r][j] * bi[j];
                si -= ar[r][j] * bi[j] + ai[r][j] * br[j];
            }
            double pr = ar[r][r], pi = ai[r][r], den = pr * pr + pi * pi;
            br[r] = (sr * pr + si * pi) / den;
            bi[r] = (si * pr - sr * pi) / den;
        }
    }

    private static double[] csqrt(double x, double y) {
        double r = Math.hypot(x, y), u = Math.sqrt((r + Math.abs(x)) / 2);
        if (u == 0) return new double[]{0, 0};
        return x >= 0 ? new double[]{u, y / (2 * u)} : new double[]{Math.abs(y) / (2 * u), Math.copySign(u, y)};
    }

    private static double[][] copy(double[][] a) {
        double[][] c = new double[a.length][];
        for (int i = 0; i < a.length; i++) c[i] = a[i].clone();
        return c;
    }

    private static double median(double[] v) {
        double[] s = v.clone();
        Arrays.sort(s);
        return s[s.length / 2];
    }
}
