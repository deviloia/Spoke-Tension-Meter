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
import com.spoketension.meter.audio.PluckSegmenter;
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
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/** Manual measurements, or one fresh pluck and an automatic save for each remaining spoke. */
public class MeasureActivity extends AppCompatActivity {

    public static final String EXTRA_WHEEL_ID = "wheelId";
    private static final int REQUEST_MIC = 101;
    private static final int PLUCKS = 3;
    private static final int MANUAL_SECONDS = 2;
    private static final int FS = SignalAnalyzer.SAMPLE_RATE;
    private static final String STATE_SESSION = "sessionId";
    private static final String STATE_SESSION_CREATED = "sessionCreated";
    private static final String STATE_LABEL = "sessionLabel";
    private static final String STATE_SPOKE = "spokeNo";
    private static final String STATE_SIDE = "side";
    private static final String STATE_PLUCKS = "plucks";
    private static final String STATE_COMPLETE = "complete";
    private static final String STATE_INTERRUPTED = "interrupted";
    private static final String STATE_SAVING = "saving";

    private enum PermissionAction { NONE, MANUAL, AUTO }

    /** Immutable target: a late result can never be assigned to a different spoke/side. */
    private static final class CaptureTarget {
        final int spokeNo;
        final String side;
        final PitchDetector.Params params;

        CaptureTarget(int spokeNo, String side, PitchDetector.Params params) {
            this.spokeNo = spokeNo;
            this.side = side;
            this.params = params;
        }
    }

    private final FirestoreRepository repository = new FirestoreRepository();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Double> plucks = new ArrayList<>();
    private final AtomicReference<CaptureTarget> autoTarget = new AtomicReference<>();

    // These fields are also read by the capture thread. Everything else belongs to the UI thread.
    private volatile int audioGeneration;
    private volatile boolean autoRunning;
    private volatile AudioCapture.Stream activeStream;

    private String wheelId, sessionId, sessionLabel;
    private Wheel wheel;
    private int spokeNo = 1;
    private boolean recording, audioTaskActive, saving, sessionCreated, complete;
    private boolean visible, destroyed;
    private Reading pendingSave;
    private PermissionAction permissionAction = PermissionAction.NONE;

    private EditText etSession;
    private TextView tvSpoke, tvResult, tvAutoStatus;
    private RadioGroup rgSide;
    private Button btnListen, btnSave, btnAuto, btnPrev, btnNext, btnResults;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_measure);
        wheelId = getIntent().getStringExtra(EXTRA_WHEEL_ID);
        etSession = findViewById(R.id.etSession);
        tvSpoke = findViewById(R.id.tvSpoke);
        tvResult = findViewById(R.id.tvResult);
        tvAutoStatus = findViewById(R.id.tvAutoStatus);
        rgSide = findViewById(R.id.rgSide);
        // This subtree is restored explicitly below, together with its captured frequencies.
        rgSide.setSaveFromParentEnabled(false);
        btnListen = findViewById(R.id.btnListen);
        btnSave = findViewById(R.id.btnSave);
        btnAuto = findViewById(R.id.btnAutoMeasure);
        btnPrev = findViewById(R.id.btnPrev);
        btnNext = findViewById(R.id.btnNext);
        btnResults = findViewById(R.id.btnResults);

        if (state != null) {
            sessionId = state.getString(STATE_SESSION);
            sessionCreated = state.getBoolean(STATE_SESSION_CREATED);
            sessionLabel = state.getString(STATE_LABEL);
            spokeNo = state.getInt(STATE_SPOKE, 1);
            complete = state.getBoolean(STATE_COMPLETE);
            if (sessionLabel != null) etSession.setText(sessionLabel);
        }
        btnPrev.setOnClickListener(v -> selectSpoke(spokeNo - 1));
        btnNext.setOnClickListener(v -> selectSpoke(spokeNo + 1));
        btnListen.setOnClickListener(v -> requestRecording(PermissionAction.MANUAL));
        btnAuto.setOnClickListener(v -> {
            if (autoRunning) {
                cancelAudio();
                tvAutoStatus.setText(saving ? R.string.auto_stopped_saving : R.string.auto_stopped);
                updateControls();
            } else requestRecording(PermissionAction.AUTO);
        });
        btnSave.setOnClickListener(v -> save());
        btnResults.setOnClickListener(v -> {
            if (sessionId == null || !sessionCreated) {
                Toast.makeText(this, R.string.nothing_saved, Toast.LENGTH_SHORT).show();
                return;
            }
            startActivity(new Intent(this, ResultActivity.class)
                    .putExtra(EXTRA_WHEEL_ID, wheelId)
                    .putExtra(ResultActivity.EXTRA_SESSION_ID, sessionId));
        });
        rgSide.setOnCheckedChangeListener((group, id) -> {
            if (!recording && !autoRunning && !saving) clearReading();
        });

        updateControls();
        tvResult.setText(R.string.loading);
        repository.loadWheel(wheelId, w -> {
            if (destroyed) return;
            if (w == null) {
                tvResult.setText(R.string.wheel_not_found);
                return;
            }
            wheel = w;
            setTitle(w.name);
            selectSpoke(Math.max(1, Math.min(spokeNo, w.spokeCount)));
            if (state != null) {
                rgSide.check("R".equals(state.getString(STATE_SIDE)) ? R.id.rbRight : R.id.rbLeft);
                double[] restored = state.getDoubleArray(STATE_PLUCKS);
                if (restored != null) for (double hz : restored) plucks.add(hz);
                complete = state.getBoolean(STATE_COMPLETE);
                if (!plucks.isEmpty()) showManualReading();
                if (complete) tvAutoStatus.setText(getString(R.string.auto_complete, spokeNo, w.spokeCount));
                else if (state.getBoolean(STATE_INTERRUPTED)) tvAutoStatus.setText(R.string.auto_stopped);
                if (state.getBoolean(STATE_SAVING) && !plucks.isEmpty()) {
                    // The old instance may still finish its write. Reusing the same ids and values
                    // makes recovery idempotent, and keeps controls locked until it is confirmed.
                    tvAutoStatus.setText(R.string.auto_stopped_saving);
                    saveReading(reading(spokeNo, side()), -1);
                }
                updateControls();
            }
        }, e -> {
            if (!destroyed) tvResult.setText(getString(R.string.load_failed, e.getMessage()));
        });
    }

    private void selectSpoke(int no) {
        if (wheel == null || no < 1 || no > wheel.spokeCount) return;
        spokeNo = no;
        // Default numbering alternates left/right; users can override it before starting.
        rgSide.check(no % 2 == 1 ? R.id.rbLeft : R.id.rbRight);
        tvSpoke.setText(getString(R.string.spoke_n_of, spokeNo, wheel.spokeCount));
        clearReading();
        if (!autoRunning) tvAutoStatus.setText(R.string.auto_hint);
    }

    private void clearReading() {
        plucks.clear();
        pendingSave = null;
        complete = false;
        tvResult.setText(R.string.pluck_hint);
        updateControls();
    }

    private String side() {
        return rgSide.getCheckedRadioButtonId() == R.id.rbRight ? "R" : "L";
    }

    private Material material() {
        return Material.valueOf(wheel.material);
    }

    private CaptureTarget target() {
        String side = side();
        return new CaptureTarget(spokeNo, side, new PitchDetector.Params(
                wheel.lengthMm(side) / 10.0, wheel.diameterMm, material().densityKgM3));
    }

    private void requestRecording(PermissionAction action) {
        if (!visible || wheel == null || recording || autoRunning || saving || audioTaskActive) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissionAction = action;
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_MIC);
            return;
        }
        permissionAction = PermissionAction.NONE;
        if (action == PermissionAction.AUTO) startAuto(); else listen();
    }

    private void listen() {
        recording = true;
        audioTaskActive = true;
        complete = false;
        final int generation = ++audioGeneration;
        final CaptureTarget target = target();
        tvResult.setText(R.string.listening);
        updateControls();
        executor.execute(() -> {
            try (AudioCapture.Stream stream = AudioCapture.openStream(getApplicationContext(), FS)) {
                activeStream = stream;
                short[] pcm = new short[FS * MANUAL_SECONDS];
                short[] buffer = new short[1024];
                int offset = 0;
                while (generation == audioGeneration && offset < pcm.length) {
                    int count = stream.read(buffer);
                    if (count < 0) return;
                    int copy = Math.min(count, pcm.length - offset);
                    System.arraycopy(buffer, 0, pcm, offset, copy);
                    offset += copy;
                }
                if (generation != audioGeneration) return;
                PitchDetector.Report report = PitchDetector.analyze(AudioCapture.toDouble(pcm), FS, target.params);
                main.post(() -> {
                    if (destroyed || !visible || generation != audioGeneration) return;
                    if (usable(report)) {
                        if (plucks.size() == PLUCKS) plucks.remove(0);
                        plucks.add(report.finalHz);
                        pendingSave = null;
                        showManualReading();
                    } else tvResult.setText(report.explanation);
                });
            } catch (Exception e) {
                reportAudioError(generation, e);
            } finally {
                finishAudioTask(generation);
            }
        });
    }

    private void startAuto() {
        clearReading();
        autoRunning = true;
        audioTaskActive = true;
        final int generation = ++audioGeneration;
        autoTarget.set(target());
        tvAutoStatus.setText(getString(R.string.auto_waiting, spokeNo, wheel.spokeCount));
        tvResult.setText(R.string.listening);
        updateControls();
        executor.execute(() -> {
            try (AudioCapture.Stream stream = AudioCapture.openStream(getApplicationContext(), FS)) {
                activeStream = stream;
                PluckSegmenter segmenter = new PluckSegmenter(FS);
                CaptureTarget previous = null;
                short[] buffer = new short[1024];
                while (generation == audioGeneration && autoRunning) {
                    CaptureTarget beforeRead = autoTarget.get();
                    int count = stream.read(buffer);
                    if (count < 0) break;
                    CaptureTarget current = autoTarget.get();
                    if (current != previous || current != beforeRead) {
                        segmenter.reset();
                        previous = current;
                        // Discard boundary audio: it may have started during the previous save.
                        continue;
                    }
                    // Drain the mic during a save; never queue plucks for the next spoke.
                    if (current == null) continue;
                    for (PluckSegmenter.Segment segment : segmenter.accept(buffer, count)) {
                        if (generation != audioGeneration || autoTarget.get() != current) break;
                        PitchDetector.Report report = PitchDetector.analyzeAtOnset(
                                segment.samples, FS, current.params, segment.onsetSample);
                        if (usable(report)) {
                            if (autoTarget.compareAndSet(current, null)) {
                                main.post(() -> acceptAuto(generation, current, report.finalHz));
                            }
                            break;
                        }
                        main.post(() -> {
                            if (!destroyed && visible && generation == audioGeneration
                                    && autoRunning && autoTarget.get() == current) {
                                tvResult.setText(getString(R.string.auto_retry, report.explanation));
                            }
                        });
                    }
                }
            } catch (Exception e) {
                reportAudioError(generation, e);
            } finally {
                finishAudioTask(generation);
            }
        });
    }

    private static boolean usable(PitchDetector.Report report) {
        return Double.isFinite(report.finalHz) && report.finalHz > 0
                && report.verdict != PitchDetector.Verdict.FAILED
                && report.verdict != PitchDetector.Verdict.DISAGREE;
    }

    private void acceptAuto(int generation, CaptureTarget target, double hz) {
        if (destroyed || !visible || !autoRunning || generation != audioGeneration || saving) return;
        plucks.clear();
        plucks.add(hz);
        Reading reading = reading(target.spokeNo, target.side);
        tvResult.setText(getString(R.string.auto_detected, hz, reading.tensionKgf,
                reading.status.toUpperCase(Locale.US)));
        tvAutoStatus.setText(getString(R.string.auto_saving, reading.spokeNo));
        saveReading(reading, generation);
    }

    private void showManualReading() {
        double hz = median(plucks);
        double kgf = kgf(hz, side());
        SafetyJudge.Status status = SafetyJudge.judge(kgf, wheel.rimMaxKgf, Double.NaN);
        StringBuilder raw = new StringBuilder();
        for (double f : plucks) raw.append(String.format(Locale.US, "%.1f  ", f));
        tvResult.setText(getString(R.string.measure_result, raw.toString().trim(), plucks.size(), PLUCKS,
                hz, kgf, status.name()));
        updateControls();
    }

    private double kgf(double hz, String side) {
        return SoundTensionCalculator.kgf(hz, wheel.lengthMm(side) / 1000.0,
                wheel.diameterMm / 1000.0, material());
    }

    private Reading reading(int no, String side) {
        Reading r = new Reading();
        r.spokeNo = no;
        r.side = side;
        r.freqHz = new ArrayList<>(plucks);
        r.freqMedianHz = median(plucks);
        r.tensionKgf = kgf(r.freqMedianHz, side);
        r.relativePercent = Double.NaN;
        r.status = SafetyJudge.judge(r.tensionKgf, wheel.rimMaxKgf, Double.NaN)
                .name().toLowerCase(Locale.US);
        return r;
    }

    private void save() {
        if (saving || recording || autoRunning || plucks.isEmpty()) return;
        saveReading(pendingSave != null ? pendingSave : reading(spokeNo, side()), -1);
    }

    private void saveReading(Reading reading, int autoGeneration) {
        // Snapshot before any asynchronous request. Retries reuse both document ids and this reading.
        pendingSave = reading;
        saving = true;
        if (sessionId == null) {
            sessionId = UUID.randomUUID().toString();
            sessionLabel = etSession.getText().toString().trim();
            if (sessionLabel.isEmpty()) sessionLabel = "before";
        }
        final String savingSession = sessionId;
        updateControls();
        if (sessionCreated) persistReading(reading, savingSession, autoGeneration);
        else repository.saveSession(wheelId, savingSession, new Session(sessionLabel), unused -> {
            sessionCreated = true;
            // Finish an already accepted save even if the screen was stopped in the meantime.
            persistReading(reading, savingSession, autoGeneration);
        }, e -> saveFailed(reading, e));
    }

    private void persistReading(Reading reading, String savingSession, int autoGeneration) {
        repository.saveReading(wheelId, savingSession, reading, unused -> {
            if (destroyed || pendingSave != reading) return;
            saving = false;
            pendingSave = null;
            if (reading.spokeNo >= wheel.spokeCount) {
                cancelAudio();
                complete = true;
                plucks.clear();
                tvAutoStatus.setText(getString(R.string.auto_complete, reading.spokeNo, wheel.spokeCount));
            } else {
                selectSpoke(reading.spokeNo + 1);
                if (visible && autoRunning && autoGeneration == audioGeneration) {
                    tvAutoStatus.setText(getString(R.string.auto_saved_waiting, reading.spokeNo,
                            reading.freqMedianHz, reading.tensionKgf, spokeNo, wheel.spokeCount));
                    tvResult.setText(R.string.listening);
                    autoTarget.set(target());
                } else if (visible) Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show();
            }
            updateControls();
        }, e -> saveFailed(reading, e));
    }

    private void saveFailed(Reading reading, Exception e) {
        if (destroyed || pendingSave != reading) return;
        saving = false;
        cancelAudio();
        tvAutoStatus.setText(getString(R.string.auto_save_failed, e.getMessage()));
        updateControls();
    }

    /** Stop is independent of Firestore: pending writes may finish, but cannot restart the microphone. */
    private void cancelAudio() {
        ++audioGeneration;
        autoRunning = false;
        recording = false;
        autoTarget.set(null);
        AudioCapture.Stream stream = activeStream;
        if (stream != null) stream.stop();
    }

    private void reportAudioError(int generation, Exception e) {
        main.post(() -> {
            if (destroyed || generation != audioGeneration) return;
            cancelAudio();
            tvAutoStatus.setText(R.string.auto_stopped);
            tvResult.setText(getString(R.string.record_failed, String.valueOf(e)));
            updateControls();
        });
    }

    private void finishAudioTask(int generation) {
        activeStream = null;
        main.post(() -> {
            if (destroyed) return;
            audioTaskActive = false;
            if (generation == audioGeneration) {
                recording = false;
                if (autoRunning) {
                    cancelAudio();
                    tvAutoStatus.setText(R.string.auto_stopped);
                }
            }
            updateControls();
        });
    }

    private void updateControls() {
        boolean busy = recording || autoRunning || saving;
        boolean idle = wheel != null && !busy && !audioTaskActive;
        btnAuto.setText(autoRunning ? R.string.auto_stop : R.string.auto_start);
        btnAuto.setEnabled(autoRunning || idle);
        btnListen.setEnabled(idle);
        btnSave.setEnabled(idle && !plucks.isEmpty());
        btnPrev.setEnabled(idle && spokeNo > 1);
        btnNext.setEnabled(idle && spokeNo < wheel.spokeCount);
        btnResults.setEnabled(idle && sessionCreated);
        etSession.setEnabled(idle && sessionId == null);
        for (int i = 0; i < rgSide.getChildCount(); i++) rgSide.getChildAt(i).setEnabled(idle);
    }

    static double median(List<Double> values) {
        List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int middle = sorted.size() / 2;
        return sorted.size() % 2 == 1 ? sorted.get(middle)
                : (sorted.get(middle - 1) + sorted.get(middle)) / 2;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(requestCode, permissions, grants);
        if (requestCode != REQUEST_MIC) return;
        PermissionAction action = permissionAction;
        permissionAction = PermissionAction.NONE;
        if (destroyed || !visible || action == PermissionAction.NONE) return;
        if (grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED) requestRecording(action);
        else tvResult.setText(R.string.mic_required);
    }

    @Override
    protected void onStart() {
        super.onStart();
        visible = true;
    }

    @Override
    protected void onStop() {
        visible = false;
        permissionAction = PermissionAction.NONE;
        boolean wasRecording = recording || autoRunning;
        cancelAudio();
        if (wasRecording) tvAutoStatus.setText(saving ? R.string.auto_stopped_saving : R.string.auto_stopped);
        updateControls();
        super.onStop();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        out.putString(STATE_SESSION, sessionId);
        out.putBoolean(STATE_SESSION_CREATED, sessionCreated);
        out.putString(STATE_LABEL, sessionLabel != null ? sessionLabel : etSession.getText().toString());
        out.putInt(STATE_SPOKE, spokeNo);
        out.putString(STATE_SIDE, side());
        double[] raw = new double[plucks.size()];
        for (int i = 0; i < raw.length; i++) raw[i] = plucks.get(i);
        out.putDoubleArray(STATE_PLUCKS, raw);
        out.putBoolean(STATE_COMPLETE, complete);
        out.putBoolean(STATE_INTERRUPTED, recording || autoRunning || saving);
        out.putBoolean(STATE_SAVING, saving);
        super.onSaveInstanceState(out);
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        cancelAudio();
        executor.shutdownNow();
        super.onDestroy();
    }
}
