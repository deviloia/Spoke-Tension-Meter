package com.spoketension.meter.ui;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.spoketension.meter.R;
import com.spoketension.meter.audio.AudioCapture;
import com.spoketension.meter.audio.PitchDetector;
import com.spoketension.meter.audio.SignalAnalyzer;
import com.spoketension.meter.calc.Material;
import com.spoketension.meter.calc.SafetyJudge;
import com.spoketension.meter.calc.SoundTensionCalculator;
import com.spoketension.meter.data.FirestoreRepository;
import com.spoketension.meter.data.model.Reading;
import com.spoketension.meter.data.model.Session;
import com.spoketension.meter.data.model.Wheel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Pick a spoke -> Listen (up to 3 plucks) -> Hz / kgf -> Save -> next spoke. */
public class MeasureActivity extends AppCompatActivity {

    public static final String EXTRA_WHEEL_ID = "wheelId";
    private static final int REQUEST_MIC = 101;
    private static final double RECORD_SECONDS = 2;
    private static final int PLUCKS = 3;

    private final FirestoreRepository repository = new FirestoreRepository();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Double> plucks = new ArrayList<>();

    private String wheelId, sessionId;
    private Wheel wheel;
    private int spokeNo = 1;
    private boolean recording;

    private EditText etSession;
    private TextView tvSpoke, tvResult;
    private RadioGroup rgSide;
    private Button btnListen, btnSave;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_measure);
        wheelId = getIntent().getStringExtra(EXTRA_WHEEL_ID);
        etSession = findViewById(R.id.etSession);
        tvSpoke = findViewById(R.id.tvSpoke);
        tvResult = findViewById(R.id.tvResult);
        rgSide = findViewById(R.id.rgSide);
        btnListen = findViewById(R.id.btnListen);
        btnSave = findViewById(R.id.btnSave);

        findViewById(R.id.btnPrev).setOnClickListener(v -> selectSpoke(spokeNo - 1));
        findViewById(R.id.btnNext).setOnClickListener(v -> selectSpoke(spokeNo + 1));
        btnListen.setOnClickListener(v -> listen());
        btnSave.setOnClickListener(v -> save());
        findViewById(R.id.btnResults).setOnClickListener(v -> {
            if (sessionId == null) {
                Toast.makeText(this, R.string.nothing_saved, Toast.LENGTH_SHORT).show();
                return;
            }
            startActivity(new Intent(this, ResultActivity.class)
                    .putExtra(EXTRA_WHEEL_ID, wheelId)
                    .putExtra(ResultActivity.EXTRA_SESSION_ID, sessionId));
        });

        btnListen.setEnabled(false);
        tvResult.setText(R.string.loading);
        repository.loadWheel(wheelId, w -> {
            wheel = w;
            setTitle(w.name);
            btnListen.setEnabled(true);
            selectSpoke(1);
        }, e -> tvResult.setText(getString(R.string.load_failed, e.getMessage())));
    }

    private void selectSpoke(int no) {
        if (wheel == null || no < 1 || no > wheel.spokeCount) return;
        spokeNo = no;
        // ponytail: assumes odd = left, even = right; the radio button can override it
        rgSide.check(no % 2 == 1 ? R.id.rbLeft : R.id.rbRight);
        tvSpoke.setText(getString(R.string.spoke_n_of, spokeNo, wheel.spokeCount));
        plucks.clear();
        btnSave.setEnabled(false);
        tvResult.setText(R.string.pluck_hint);
    }

    private String side() {
        return rgSide.getCheckedRadioButtonId() == R.id.rbRight ? "R" : "L";
    }

    private Material material() {
        return Material.valueOf(wheel.material);
    }

    private void listen() {
        if (recording) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_MIC);
            return;
        }
        recording = true;
        btnListen.setEnabled(false);
        tvResult.setText(R.string.listening);
        final PitchDetector.Params params = new PitchDetector.Params(
                wheel.lengthMm(side()) / 10.0, wheel.diameterMm, material().densityKgM3);
        executor.execute(() -> {
            String msg;
            try {
                AudioCapture.Recording rec = AudioCapture.record(this, SignalAnalyzer.SAMPLE_RATE, RECORD_SECONDS);
                PitchDetector.Report r = PitchDetector.analyze(
                        AudioCapture.toDouble(rec.pcm), SignalAnalyzer.SAMPLE_RATE, params);
                boolean usable = Double.isFinite(r.finalHz) && r.verdict != PitchDetector.Verdict.FAILED
                        && r.verdict != PitchDetector.Verdict.DISAGREE;
                msg = usable ? null : r.explanation;
                if (usable) main.post(() -> addPluck(r.finalHz));
            } catch (Exception e) {
                msg = getString(R.string.record_failed, String.valueOf(e));
            }
            final String error = msg;
            main.post(() -> {
                recording = false;
                btnListen.setEnabled(true);
                if (error != null) tvResult.setText(error);
            });
        });
    }

    private void addPluck(double hz) {
        if (plucks.size() == PLUCKS) plucks.remove(0);
        plucks.add(hz);
        double median = median(plucks);
        double kgf = kgf(median);
        SafetyJudge.Status status = SafetyJudge.judge(kgf, wheel.rimMaxKgf, Double.NaN);
        StringBuilder raw = new StringBuilder();
        for (double f : plucks) raw.append(String.format(Locale.US, "%.1f  ", f));
        tvResult.setText(getString(R.string.measure_result, raw.toString().trim(), plucks.size(), PLUCKS,
                median, kgf, status.name()));
        btnSave.setEnabled(true);
    }

    private double kgf(double hz) {
        return SoundTensionCalculator.kgf(hz, wheel.lengthMm(side()) / 1000.0,
                wheel.diameterMm / 1000.0, material());
    }

    private void save() {
        btnSave.setEnabled(false);
        if (sessionId == null) {
            String label = etSession.getText().toString().trim();
            repository.addSession(wheelId, new Session(label.isEmpty() ? "before" : label), id -> {
                sessionId = id;
                etSession.setEnabled(false);
                save();
            }, this::saveFailed);
            return;
        }
        Reading r = new Reading();
        r.spokeNo = spokeNo;
        r.side = side();
        r.freqHz = new ArrayList<>(plucks);
        r.freqMedianHz = median(plucks);
        r.tensionKgf = kgf(r.freqMedianHz);
        r.relativePercent = Double.NaN;   // needs the whole side; ResultActivity fills it in
        r.status = SafetyJudge.judge(r.tensionKgf, wheel.rimMaxKgf, Double.NaN).name().toLowerCase(Locale.US);
        repository.saveReading(wheelId, sessionId, r, unused -> {
            Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show();
            if (spokeNo < wheel.spokeCount) selectSpoke(spokeNo + 1);
        }, this::saveFailed);
    }

    private void saveFailed(Exception e) {
        btnSave.setEnabled(true);
        Toast.makeText(this, getString(R.string.save_failed, e.getMessage()), Toast.LENGTH_LONG).show();
    }

    static double median(List<Double> v) {
        List<Double> s = new ArrayList<>(v);
        Collections.sort(s);
        int m = s.size() / 2;
        return s.size() % 2 == 1 ? s.get(m) : (s.get(m - 1) + s.get(m)) / 2;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(requestCode, permissions, grants);
        if (requestCode == REQUEST_MIC) {
            if (grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED) listen();
            else tvResult.setText(R.string.mic_required);
        }
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }
}
