package com.spoketension.meter.ui;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.spoketension.meter.audio.AudioCapture;
import com.spoketension.meter.audio.FftPitch;
import com.spoketension.meter.audio.MatrixPencil;
import com.spoketension.meter.audio.PitchTracker;
import com.spoketension.meter.audio.PitchDetector;
import com.spoketension.meter.audio.SignalAnalyzer;
import com.spoketension.meter.audio.WavWriter;
import com.spoketension.meter.calc.SoundTensionCalculator;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** One 2 s recording, three methods: FFT, MatrixPencil(New) and Sally (the zip's full pipeline). */
public class LabActivity extends Activity {
    private static final int REQUEST_MIC = 101;
    private static final int SAMPLE_RATE = SignalAnalyzer.SAMPLE_RATE;
    private static final double RECORD_SECONDS = 2;
    // Rejection rule, not a confidence score: methods disagreeing by more than this -> re-pluck.
    private static final double SPREAD_MAX_HZ = 10;
    private static final String[] NAMES = {"FFT", "MP(New)", "Sally"};
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private EditText lengthField, diameterField, densityField, tMinField, tMaxField;
    private Button recordButton;
    private TextView resultView;
    private boolean recording = false;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ScrollView scroller = new ScrollView(this);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        int p = (int) (20 * getResources().getDisplayMetrics().density);
        panel.setPadding(p, p, p, p);
        scroller.addView(panel);
        TextView title = new TextView(this);
        title.setText("Spoke Tension Lab — FFT / MatrixPencil(New) / Sally");
        title.setTextSize(22);
        panel.addView(title);
        TextView info = new TextView(this);
        info.setText("Tap Record, pluck one spoke firmly within 2 seconds, keep quiet. FFT and MatrixPencil search "
                + "the band implied by the tension range below; Sally uses its own 30–200 kgf band.");
        panel.addView(info);
        lengthField = addField(panel, "Effective vibrating length (cm)", "20.5");
        diameterField = addField(panel, "Diameter (mm)", "2.0");
        densityField = addField(panel, "Density (kg/m³)", "7850");
        tMinField = addField(panel, "Expected tension min (kgf) — sets search band", "30");
        tMaxField = addField(panel, "Expected tension max (kgf) — sets search band", "200");
        recordButton = new Button(this);
        recordButton.setText("Record 2 seconds & analyze");
        panel.addView(recordButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        resultView = new TextView(this);
        resultView.setTextSize(13);
        resultView.setTypeface(Typeface.MONOSPACE);
        resultView.setTextIsSelectable(true);
        resultView.setText("Ready. Allow microphone permission when asked.");
        panel.addView(resultView);
        setContentView(scroller);
        recordButton.setOnClickListener(v -> startIfPermitted());
    }

    private EditText addField(LinearLayout panel, String label, String value) {
        TextView tv = new TextView(this);
        tv.setText(label);
        panel.addView(tv);
        EditText field = new EditText(this);
        field.setSingleLine(true);
        field.setInputType(android.text.InputType.TYPE_CLASS_NUMBER |
                android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        field.setText(value);
        panel.addView(field);
        return field;
    }

    private void startIfPermitted() {
        if (recording) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_MIC);
            return;
        }
        final double length, diameter, density, tMin, tMax;
        final double[] band;
        try {
            length = Double.parseDouble(lengthField.getText().toString());
            diameter = Double.parseDouble(diameterField.getText().toString());
            density = Double.parseDouble(densityField.getText().toString());
            tMin = Double.parseDouble(tMinField.getText().toString());
            tMax = Double.parseDouble(tMaxField.getText().toString());
            if (!(length > 0 && diameter > 0 && density > 0 && tMin > 0 && tMax > tMin))
                throw new NumberFormatException();
            band = SignalAnalyzer.bandFromTension(tMin, tMax, length, diameter, density);
        } catch (NumberFormatException e) {
            resultView.setText("Enter valid positive values (tension max > min).");
            return;
        }
        if (!(band[1] - band[0] >= 10)) {
            resultView.setText(String.format(Locale.US, "Tension range maps to %.0f-%.0f Hz, outside the "
                    + "supported %.0f-%.0f Hz. Check length/diameter/tension.", band[0], band[1],
                    SignalAnalyzer.MIN_HZ, SignalAnalyzer.MAX_HZ));
            return;
        }
        recording = true;
        recordButton.setEnabled(false);
        resultView.setText("Recording 2 seconds. Pluck a spoke NOW...");
        executor.execute(() -> {
            try {
                AudioCapture.Recording rec = AudioCapture.record(this, SAMPLE_RATE, RECORD_SECONDS);
                show("Analyzing (MatrixPencil takes a moment)...");
                analyze(rec, length, diameter, density, tMin, tMax, band);
            } catch (Exception e) {
                show("Recording/analysis failed: " + e);
            } finally {
                main.post(() -> { recording = false; recordButton.setEnabled(true); });
            }
        });
    }

    private void analyze(AudioCapture.Recording rec, double length, double diameter, double density,
                         double tMin, double tMax, double[] band) {
        double[] x = AudioCapture.toDouble(rec.pcm);
        List<SignalAnalyzer.Window> windows = SignalAnalyzer.windows(x, SAMPLE_RATE);
        SignalAnalyzer.Window w0 = windows.isEmpty() ? null : windows.get(0);
        FftPitch.Peak fft = w0 == null ? null
                : FftPitch.dominantPeak(w0.samples, 0, w0.samples.length, SAMPLE_RATE, band[0], band[1]);
        MatrixPencil.Report mp = MatrixPencil.measure(windows, SAMPLE_RATE, band[0], band[1]);
        PitchDetector.Report sally = PitchDetector.analyze(x, SAMPLE_RATE, new PitchDetector.Params(length, diameter, density));

        double[] hz = {fft == null ? Double.NaN : fft.frequencyHz, mp.freqHz, sally.finalHz};
        String[] status = {
                w0 == null ? "NO_RESULT: no pluck detected" : fft == null ? "NO_RESULT: nothing in band" : "OK",
                mp.status + (Double.isFinite(mp.octaveFromHz)
                        ? String.format(Locale.US, " (octave-corrected from %.1f Hz)", mp.octaveFromHz) : ""),
                sally.verdict + ": " + sally.explanation};
        double lo = Double.POSITIVE_INFINITY, hi = Double.NEGATIVE_INFINITY;
        int found = 0;
        for (double f : hz) if (Double.isFinite(f)) { lo = Math.min(lo, f); hi = Math.max(hi, f); found++; }
        double spread = found >= 2 ? hi - lo : Double.NaN;
        String verdict = found < 2 ? "TOO FEW RESULTS — re-pluck"
                : spread > SPREAD_MAX_HZ ? "INCONSISTENT — re-pluck" : "consistent";

        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.US, "Mic source: %s%nBand %.1f–%.1f Hz (from %.0f–%.0f kgf); Sally %.1f–%.1f Hz%n%n",
                rec.sourceName, band[0], band[1], tMin, tMax, sally.minHz, sally.maxHz));
        sb.append("method   frequency   ideal kgf   with stiffness (clamped–pinned)\n");
        for (int i = 0; i < NAMES.length; i++)
            sb.append(String.format(Locale.US, "%-8s %-11s %-11s %s%n   %s%n", NAMES[i], fmtHz(hz[i]),
                    fmtKgf(SoundTensionCalculator.tensionKgf(hz[i], length, diameter, density)),
                    stiffRange(hz[i], length, diameter, density), status[i]));
        sb.append(String.format(Locale.US, "%nSpread (max-min): %s → %s (limit %.0f Hz)%n",
                Double.isFinite(spread) ? String.format(Locale.US, "%.2f Hz", spread) : "n/a", verdict, SPREAD_MAX_HZ));
        if (w0 != null)
            sb.append(String.format(Locale.US, "FFT window: pluck at %.3f s (attack x%.1f), %d samples (%.0f ms)%n",
                    w0.onset / (double) SAMPLE_RATE, w0.jumpRatio, w0.samples.length,
                    1000.0 * w0.samples.length / SAMPLE_RATE));
        appendMpDiag(sb, mp);
        appendSallyDiag(sb, sally);

        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        File dir = getExternalFilesDir(null);
        if (dir == null) dir = getFilesDir();
        sb.append('\n');
        try {
            File wav = new File(dir, "spoke_" + stamp + ".wav");
            WavWriter.write(wav, rec.pcm, SAMPLE_RATE);
            sb.append("WAV: ").append(wav.getAbsolutePath()).append('\n');
        } catch (IOException e) {
            sb.append("Saving WAV failed: ").append(e.getMessage()).append('\n');
        }
        try {
            sb.append("CSV: ").append(writeCsv(new File(dir, "spoke_" + stamp + ".csv"), stamp, rec.sourceName,
                    length, diameter, density, tMin, tMax, band, hz, status, spread, verdict, mp, sally)
                    .getAbsolutePath());
        } catch (IOException e) {
            sb.append("Saving CSV failed: ").append(e.getMessage());
        }
        sb.append("\n\nResearch prototype only. Do NOT use this to certify riding "
                + "safety. Validate against a calibrated spoke tension meter.");
        show(sb.toString());
    }

    private static String stiffRange(double hz, double length, double diameter, double density) {
        double e = SoundTensionCalculator.STEEL_YOUNG_MODULUS_PA;
        double c = SoundTensionCalculator.tensionKgfClamped(hz, length, diameter, density, e);
        double p = SoundTensionCalculator.tensionKgfPinned(hz, length, diameter, density, e);
        if (!Double.isFinite(p)) return "—";
        // Clamped model is invalid at low tension (B > 0.1): still show the pinned bound.
        return Double.isFinite(c) ? String.format(Locale.US, "%.1f–%.1f kgf", c, p)
                : String.format(Locale.US, "≤%.1f kgf (pinned)", p);
    }

    private static void appendMpDiag(StringBuilder sb, MatrixPencil.Report r) {
        sb.append("\n— MatrixPencil(New) = NRPE —\n");
        if (r.window != null)
            sb.append(String.format(Locale.US, "window: pluck at %.3f s (attack x%.1f)%n",
                    r.window.onset / (double) SignalAnalyzer.SAMPLE_RATE, r.window.jumpRatio));
        sb.append(String.format(Locale.US, "fs %.1f Hz (decimation %d), N %d, band-pass %.0f–%.0f Hz%n",
                r.fsDecimated, MatrixPencil.DECIMATE, r.n, r.filterLo, r.filterHi));
        sb.append(String.format(Locale.US, "Hankel p=%d, rank r=%d, pencil L=%d, λ2 %.3g%n",
                r.nrpeP, r.nrpeRank, r.pencilL, r.lambda2));
        sb.append(String.format(Locale.US, "damping gate %.3f..%s 1/s, octave check f/2,f/3 ±%.0f%%%n",
                MatrixPencil.alphaMin, Double.isInfinite(MatrixPencil.alphaMax) ? "+inf"
                        : String.format(Locale.US, "%.1f", MatrixPencil.alphaMax), MatrixPencil.OCTAVE_TOL * 100));
        sb.append("status: ").append(r.status).append('\n');
        sb.append("   f Hz    α 1/s   τ s     amp     /max  share  reason\n");
        for (MatrixPencil.PoleRow p : r.poles)
            sb.append(String.format(Locale.US, "%s%7.1f %7.1f %6.3f %8.2e %5.2f %5.2f  %s%n",
                    p.selected ? "✓" : " ", p.freqHz, p.alphaPerS, p.tauS, p.amp, p.ampRel, p.share, p.reason));
    }

    private static void appendSallyDiag(StringBuilder sb, PitchDetector.Report r) {
        sb.append("\n— Sally (zip: TapDetector + multi-frame PitchTracker + YIN check) —\n");
        if (r.tap != null)
            sb.append(String.format(Locale.US, "pluck at %.3f s (attack x%.1f)%n",
                    r.tap.onsetSample / (double) SignalAnalyzer.SAMPLE_RATE, r.tap.jumpRatio));
        if (r.yin != null)
            sb.append(String.format(Locale.US, "YIN cross-check: %s%n", fmtHz(r.yin.frequencyHz)));
        if (r.tracked != null)
            for (PitchTracker.Candidate c : r.tracked.candidates)
                sb.append(String.format(Locale.US, "%s%7.1f Hz  drift %.2f%%  %+.1f dB/s  %d/%d frames  %s%n",
                        c == r.tracked.chosen ? "✓" : " ", c.frequencyHz, c.variation * 100, c.decayDbPerSecond,
                        c.framesFound, c.framesTotal, c.accepted ? "accepted" : c.rejectReason));
    }

    private static File writeCsv(File file, String stamp, String source, double length, double diameter,
                                 double density, double tMin, double tMax, double[] band, double[] hz,
                                 String[] status, double spread, String verdict, MatrixPencil.Report mp,
                                 PitchDetector.Report sally) throws IOException {
        try (Writer out = new FileWriter(file)) {
            out.write("section,name,pole,freq_hz,tension_kgf,alpha_per_s,tau_s,amp,amp_rel_max,share,score,selected,note\n");
            meta(out, "time", stamp);
            meta(out, "audio_source", source);
            meta(out, "length_cm", length);
            meta(out, "diameter_mm", diameter);
            meta(out, "density_kg_m3", density);
            meta(out, "tension_min_kgf", tMin);
            meta(out, "tension_max_kgf", tMax);
            meta(out, "band_lo_hz", band[0]);
            meta(out, "band_hi_hz", band[1]);
            meta(out, "window_samples", SignalAnalyzer.ANALYSIS_SAMPLES);
            meta(out, "fs_hz", SAMPLE_RATE);
            meta(out, "spread_hz", spread);
            meta(out, "spread_max_hz", SPREAD_MAX_HZ);
            meta(out, "verdict", verdict);
            meta(out, "mp_onset_s", mp.window == null ? Double.NaN : mp.window.onset / (double) SAMPLE_RATE);
            meta(out, "mp_window_start_s", mp.window == null ? Double.NaN : mp.window.start / (double) SAMPLE_RATE);
            meta(out, "mp_fs_decimated", mp.fsDecimated);
            meta(out, "mp_n", mp.n);
            meta(out, "mp_filter_lo_hz", mp.filterLo);
            meta(out, "mp_filter_hi_hz", mp.filterHi);
            meta(out, "mp_pencil_L", mp.pencilL);
            meta(out, "mp_p", mp.nrpeP);
            meta(out, "mp_rank_r", mp.nrpeRank);
            meta(out, "mp_lambda2", mp.lambda2);
            meta(out, "mp_damping_gate_min", MatrixPencil.alphaMin);
            meta(out, "mp_damping_gate_max", MatrixPencil.alphaMax);
            meta(out, "mp_octave_from_hz", mp.octaveFromHz);
            meta(out, "sally_band_lo_hz", sally.minHz);
            meta(out, "sally_band_hi_hz", sally.maxHz);
            meta(out, "sally_onset_s", sally.tap == null ? Double.NaN : sally.tap.onsetSample / (double) SAMPLE_RATE);
            meta(out, "sally_verdict", sally.verdict);
            meta(out, "sally_yin_hz", sally.yin == null ? Double.NaN : sally.yin.frequencyHz);
            meta(out, "sally_stiff_kgf_clamped", sally.stiffKgfClamped);
            meta(out, "sally_stiff_kgf_pinned", sally.stiffKgfPinned);
            for (int i = 0; i < NAMES.length; i++)
                out.write(row("result", NAMES[i], "", hz[i],
                        SoundTensionCalculator.tensionKgf(hz[i], length, diameter, density), Double.NaN, Double.NaN,
                        Double.NaN, Double.NaN, Double.NaN, i == 1 ? mp.score : Double.NaN, "", status[i]));
            int k = 0;
            for (MatrixPencil.PoleRow p : mp.poles)
                out.write(row("pole", "MP(New)", String.valueOf(k++), p.freqHz,
                        SoundTensionCalculator.tensionKgf(p.freqHz, length, diameter, density), p.alphaPerS, p.tauS,
                        p.amp, p.ampRel, p.share, Double.NaN, p.selected ? "1" : "0", p.reason));
            if (sally.tracked != null) {
                k = 0;
                for (PitchTracker.Candidate c : sally.tracked.candidates)
                    out.write(row("candidate", "Sally", String.valueOf(k++), c.frequencyHz,
                            SoundTensionCalculator.tensionKgf(c.frequencyHz, length, diameter, density),
                            -c.decayDbPerSecond / 8.686, Double.NaN, c.meanMagnitude, Double.NaN, Double.NaN,
                            c.prominenceDb, c == sally.tracked.chosen ? "1" : "0",
                            c.accepted ? "accepted" : c.rejectReason));
            }
        }
        return file;
    }

    private static void meta(Writer out, String key, Object value) throws IOException {
        out.write("meta," + csv(key) + ",,,,,,,,,,," + csv(String.valueOf(value)) + "\n");
    }

    private static String row(String section, String name, String pole, double f, double kgf, double alpha,
                              double tau, double amp, double rel, double share, double score,
                              String selected, String note) {
        return String.join(",", section, csv(name), pole, num(f), num(kgf), num(alpha), num(tau), num(amp),
                num(rel), num(share), num(score), selected, csv(note)) + "\n";
    }

    private static String num(double v) {
        return Double.isNaN(v) ? "" : String.format(Locale.US, "%.6g", v);
    }

    private static String csv(String s) {
        return s.contains(",") || s.contains("\"") ? "\"" + s.replace("\"", "\"\"") + "\"" : s;
    }

    private static String fmtHz(double v) {
        return Double.isFinite(v) ? String.format(Locale.US, "%.2f Hz", v) : "—";
    }
    private static String fmtKgf(double v) {
        return Double.isFinite(v) ? String.format(Locale.US, "%.2f kgf", v) : "—";
    }
    private void show(String s) { main.post(() -> resultView.setText(s)); }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(requestCode, permissions, grants);
        if (requestCode == REQUEST_MIC) {
            if (grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED)
                startIfPermitted();
            else resultView.setText("Microphone permission is required.");
        }
    }
    @Override protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }
}
