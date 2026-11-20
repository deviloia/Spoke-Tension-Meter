package com.spoketension.meter.ui;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
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
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Manual measurements, or one fresh pluck and an automatic save for each remaining spoke.
 * Every accepted pluck is analysed with all three methods (Sally = PitchDetector, FFT, MatrixPencil)
 * on the same onset; Sally's value drives kgf and status, the other two are stored for comparison.
 */
public class MeasureActivity extends AppCompatActivity {

    public static final String EXTRA_WHEEL_ID = "wheelId";
    private static final int REQUEST_MIC = 101;
    private static final int PLUCKS = 3;
    private static final int MANUAL_SECONDS = 2;
    private static final int FS = SignalAnalyzer.SAMPLE_RATE;
    /** Indexes into one pluck: {Sally, FFT, MatrixPencil} Hz. */
    private static final int SALLY = 0, FFT = 1, MP = 2;
    private static final String STATE_SESSION = "sessionId";
    private static final String STATE_SESSION_CREATED = "sessionCreated";
    private static final String STATE_LABEL = "sessionLabel";
    private static final String STATE_SPOKE = "spokeNo";
    private static final String STATE_ANCHOR_SPOKE = "anchorSpoke";
    private static final String STATE_ANCHOR_SIDE = "anchorSide";
    private static final String STATE_RESUME = "resumeSpoke";
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
        final double[] band;   // FFT / MatrixPencil search band, same 30–200 kgf range as Sally

        CaptureTarget(int spokeNo, String side, PitchDetector.Params params, double[] band) {
            this.spokeNo = spokeNo;
            this.side = side;
            this.params = params;
            this.band = band;
        }
    }

    private final FirestoreRepository repository = new FirestoreRepository();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    /** FFT + MatrixPencil run here so the capture thread keeps draining the microphone. */
    private final ExecutorService analysisExecutor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    /** Accepted plucks for the current spoke, each {Sally, FFT, MatrixPencil} Hz. */
    private final List<double[]> plucks = new ArrayList<>();
    /** Spokes saved in this session, by spoke number. */
    private final TreeMap<Integer, Reading> measured = new TreeMap<>();
    private final AtomicReference<CaptureTarget> autoTarget = new AtomicReference<>();

    // These fields are also read by the capture thread. Everything else belongs to the UI thread.
    private volatile int audioGeneration;
    private volatile boolean autoRunning;
    private volatile AudioCapture.Stream activeStream;

    private String wheelId, sessionId, sessionLabel;
    private Wheel wheel;
    private int spokeNo = 1;
    /** The side the user chose for anchorSpoke; neighbours alternate from it. */
    private int anchorSpoke = 1;
    private String anchorSide = "L";
    /** Where "Continue" picks up: the spoke after the last newly measured one. */
    private int resumeSpoke = 1;
    private boolean recording, audioTaskActive, saving, sessionCreated, complete;
    private boolean visible, destroyed, settingSide;
    private Reading pendingSave;
    private PermissionAction permissionAction = PermissionAction.NONE;

    private EditText etSession;
    private TextView tvSpoke, tvResult, tvAutoStatus, tvMeasuredHeader;
    private LinearLayout measuredList;
    private RadioGroup rgSide;
    private Button btnListen, btnSave, btnAuto, btnPrev, btnNext, btnResults, btnContinue;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_measure);
        wheelId = getIntent().getStringExtra(EXTRA_WHEEL_ID);
        etSession = findViewById(R.id.etSession);
        tvSpoke = findViewById(R.id.tvSpoke);
        tvResult = findViewById(R.id.tvResult);
        tvAutoStatus = findViewById(R.id.tvAutoStatus);
        tvMeasuredHeader = findViewById(R.id.tvMeasuredHeader);
        measuredList = findViewById(R.id.measuredList);
        rgSide = findViewById(R.id.rgSide);
        // This subtree is restored explicitly below, together with its captured frequencies.
        rgSide.setSaveFromParentEnabled(false);
        btnListen = findViewById(R.id.btnListen);
        btnSave = findViewById(R.id.btnSave);
        btnAuto = findViewById(R.id.btnAutoMeasure);
        btnPrev = findViewById(R.id.btnPrev);
        btnNext = findViewById(R.id.btnNext);
        btnResults = findViewById(R.id.btnResults);
        btnContinue = findViewById(R.id.btnContinue);

        if (state != null) {
            sessionId = state.getString(STATE_SESSION);
            sessionCreated = state.getBoolean(STATE_SESSION_CREATED);
            sessionLabel = state.getString(STATE_LABEL);
            spokeNo = state.getInt(STATE_SPOKE, 1);
            anchorSpoke = state.getInt(STATE_ANCHOR_SPOKE, 1);
            anchorSide = state.getString(STATE_ANCHOR_SIDE, "L");
            resumeSpoke = state.getInt(STATE_RESUME, 1);
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
        btnContinue.setOnClickListener(v -> jumpTo(nextUnmeasured(resumeSpoke)));
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
            if (settingSide || recording || autoRunning || saving) return;
            // The user corrected the side: re-anchor so later spokes alternate from here.
            anchorSpoke = spokeNo;
            anchorSide = side();
            clearReading();
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
            ((RadioButton) findViewById(R.id.rbLeft)).setText(ReadingFormat.sideName(this, w, "L"));
            ((RadioButton) findViewById(R.id.rbRight)).setText(ReadingFormat.sideName(this, w, "R"));
            selectSpoke(Math.max(1, Math.min(spokeNo, w.spokeCount)));
            if (state != null) restore(state);
        }, e -> {
            if (!destroyed) tvResult.setText(getString(R.string.load_failed, e.getMessage()));
        });
    }

    private void restore(Bundle state) {
        double[] flat = state.getDoubleArray(STATE_PLUCKS);
        if (flat != null)
            for (int i = 0; i + 2 < flat.length; i += 3) plucks.add(new double[]{flat[i], flat[i + 1], flat[i + 2]});
        complete = state.getBoolean(STATE_COMPLETE);
        if (!plucks.isEmpty()) showManualReading();
        if (complete) tvAutoStatus.setText(getString(R.string.auto_complete, wheel.spokeCount));
        else if (state.getBoolean(STATE_INTERRUPTED)) tvAutoStatus.setText(R.string.auto_stopped);
        boolean retrySave = state.getBoolean(STATE_SAVING) && !plucks.isEmpty();
        if (retrySave) {
            // Keep controls locked until the list is back and the interrupted save is re-issued.
            saving = true;
            tvAutoStatus.setText(R.string.auto_stopped_saving);
        }
        Runnable afterList = () -> {
            refreshMeasured();
            // The old instance may still finish its write. Reusing the same ids and values makes
            // recovery idempotent. Done after the list loads so re-measure vs. new is judged right.
            if (retrySave) saveReading(reading(spokeNo, side()), -1);
            updateControls();
        };
        if (!sessionCreated) {
            afterList.run();
            return;
        }
        repository.loadReadings(wheelId, sessionId, readings -> {
            if (destroyed) return;
            for (Reading r : readings) measured.put(r.spokeNo, r);
            afterList.run();
        }, e -> {
            if (!destroyed) afterList.run();
        });
        updateControls();
    }

    /** Side of a spoke: alternates spoke by spoke, starting from the side chosen for anchorSpoke. */
    private String sideFor(int no) {
        Reading saved = measured.get(no);
        if (saved != null && saved.side != null) return saved.side;   // re-measure keeps its side
        boolean same = Math.floorMod(no - anchorSpoke, 2) == 0;
        return same ? anchorSide : ("L".equals(anchorSide) ? "R" : "L");
    }

    private void selectSpoke(int no) {
        if (wheel == null || no < 1 || no > wheel.spokeCount) return;
        spokeNo = no;
        settingSide = true;
        rgSide.check("R".equals(sideFor(no)) ? R.id.rbRight : R.id.rbLeft);
        settingSide = false;
        tvSpoke.setText(getString(R.string.spoke_n_of, spokeNo, wheel.spokeCount));
        clearReading();
        if (!autoRunning) tvAutoStatus.setText(R.string.auto_hint);
        refreshMeasured();
    }

    /** Re-measure an old spoke or continue; in auto mode the microphone keeps running. */
    private void jumpTo(int no) {
        if (wheel == null || no < 1 || saving || recording || (audioTaskActive && !autoRunning)) return;
        // An accepted pluck is being analysed/saved (target already taken): its save would move on
        // to the next spoke and override this jump, so ignore taps until it is done.
        if (autoRunning && autoTarget.get() == null) return;
        selectSpoke(no);
        if (autoRunning) {
            autoTarget.set(target());
            tvAutoStatus.setText(getString(R.string.auto_waiting, spokeNo, wheel.spokeCount));
            tvResult.setText(R.string.listening);
        }
        updateControls();
    }

    /** First spoke without a saved reading, scanning from start and wrapping around; -1 if none. */
    private int nextUnmeasured(int start) {
        if (wheel == null) return -1;
        int n = wheel.spokeCount;
        for (int i = 0; i < n; i++) {
            int no = Math.floorMod(start - 1 + i, n) + 1;
            if (!measured.containsKey(no)) return no;
        }
        return -1;
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
        double lengthCm = wheel.lengthMm(side) / 10.0;
        double density = material().densityKgM3;
        return new CaptureTarget(spokeNo, side,
                new PitchDetector.Params(lengthCm, wheel.diameterMm, density),
                SignalAnalyzer.bandFromTension(SoundTensionCalculator.MIN_TENSION_KGF,
                        SoundTensionCalculator.MAX_TENSION_KGF, lengthCm, wheel.diameterMm, density));
    }

    /** FFT and MatrixPencil on the window after the onset Sally used, so all three share one pluck. */
    private static double[] otherMethods(double[] x, int onset, CaptureTarget target) {
        return SignalAnalyzer.fftAndMatrixPencil(SignalAnalyzer.windowAt(x, FS, onset),
                FS, target.band[0], target.band[1]);
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
                double[] x = AudioCapture.toDouble(pcm);
                PitchDetector.Report report = PitchDetector.analyze(x, FS, target.params);
                // Recording is finished, so the slower methods no longer compete with the mic.
                double[] other = usable(report) && report.tap != null
                        ? otherMethods(x, report.tap.onsetSample, target) : null;
                main.post(() -> {
                    if (destroyed || !visible || generation != audioGeneration) return;
                    if (other != null) {
                        addPluck(new double[]{report.finalHz, other[0], other[1]});
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

    private void addPluck(double[] pluck) {
        if (plucks.size() == PLUCKS) plucks.remove(0);
        plucks.add(pluck);
    }

    /** One method's values across the current plucks. */
    private List<Double> column(int method) {
        List<Double> out = new ArrayList<>();
        for (double[] p : plucks) out.add(p[method]);
        return out;
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
                                // Off the capture thread: MatrixPencil is slow and the mic must keep draining.
                                analysisExecutor.execute(() -> {
                                    double[] other = otherMethods(segment.samples, segment.onsetSample, current);
                                    main.post(() -> acceptAuto(generation, current,
                                            new double[]{report.finalHz, other[0], other[1]}));
                                });
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

    private void acceptAuto(int generation, CaptureTarget target, double[] pluck) {
        if (destroyed || !visible || !autoRunning || generation != audioGeneration || saving) return;
        plucks.clear();
        plucks.add(pluck);
        Reading reading = reading(target.spokeNo, target.side);
        tvResult.setText(getString(R.string.auto_detected, pluck[SALLY], reading.tensionKgf,
                reading.status.toUpperCase(Locale.US), ReadingFormat.methodsLine(pluck[SALLY], pluck[FFT], pluck[MP])));
        tvAutoStatus.setText(getString(R.string.auto_saving, reading.spokeNo));
        saveReading(reading, generation);
    }

    private void showManualReading() {
        double hz = ReadingFormat.median(column(SALLY));
        double kgf = kgf(hz, side());
        SafetyJudge.Status status = SafetyJudge.judge(kgf, wheel.rimMaxKgf, Double.NaN);
        StringBuilder raw = new StringBuilder();
        for (double[] p : plucks) raw.append(String.format(Locale.US, "%.1f  ", p[SALLY]));
        tvResult.setText(getString(R.string.measure_result, raw.toString().trim(), plucks.size(), PLUCKS,
                hz, kgf, status.name(), ReadingFormat.methodsLine(hz,
                        ReadingFormat.medianFinite(column(FFT)), ReadingFormat.medianFinite(column(MP)))));
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
        r.freqHz = column(SALLY);
        r.fftHz = column(FFT);
        r.mpHz = column(MP);
        r.freqMedianHz = ReadingFormat.median(r.freqHz);
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
            boolean remeasure = measured.containsKey(reading.spokeNo);
            measured.put(reading.spokeNo, reading);
            int after = reading.spokeNo % wheel.spokeCount + 1;
            if (!remeasure) resumeSpoke = after;
            // New spoke: go on to the next one. Re-measured old spoke: return to where we left off.
            int next = nextUnmeasured(remeasure ? resumeSpoke : after);
            if (next < 0) {
                cancelAudio();
                complete = true;
                plucks.clear();
                tvAutoStatus.setText(getString(R.string.auto_complete, wheel.spokeCount));
                refreshMeasured();
            } else {
                selectSpoke(next);
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

    /** One row per saved spoke; tapping a row measures that spoke again. */
    private void refreshMeasured() {
        if (wheel == null) return;
        measuredList.removeAllViews();
        tvMeasuredHeader.setText(getString(R.string.measured_header, measured.size(), wheel.spokeCount));
        int pad = (int) (8 * getResources().getDisplayMetrics().density);
        for (Reading r : measured.values()) {
            TextView row = new TextView(this);
            row.setText(ReadingFormat.rowText(this, wheel, r));
            row.setPadding(pad, pad, pad, pad);
            row.setBackgroundColor(ReadingFormat.color(r.status));
            if (r.spokeNo == spokeNo) row.setTypeface(Typeface.DEFAULT_BOLD);
            final int no = r.spokeNo;
            row.setOnClickListener(v -> jumpTo(no));
            measuredList.addView(row);
        }
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
        // Jumping (row tap / Continue) also works while auto mode is listening, but not mid-save.
        boolean canJump = idle || (autoRunning && !saving);
        btnAuto.setText(autoRunning ? R.string.auto_stop : R.string.auto_start);
        btnAuto.setEnabled(autoRunning || idle);
        btnListen.setEnabled(idle);
        btnSave.setEnabled(idle && !plucks.isEmpty());
        btnPrev.setEnabled(idle && spokeNo > 1);
        btnNext.setEnabled(idle && wheel != null && spokeNo < wheel.spokeCount);
        btnResults.setEnabled(idle && sessionCreated);
        etSession.setEnabled(idle && sessionId == null);
        for (int i = 0; i < rgSide.getChildCount(); i++) rgSide.getChildAt(i).setEnabled(idle);
        for (int i = 0; i < measuredList.getChildCount(); i++) measuredList.getChildAt(i).setEnabled(canJump);
        int next = nextUnmeasured(resumeSpoke);
        boolean showContinue = !measured.isEmpty() && next > 0 && next != spokeNo;
        btnContinue.setVisibility(showContinue ? View.VISIBLE : View.GONE);
        if (showContinue) btnContinue.setText(getString(R.string.continue_from, next));
        btnContinue.setEnabled(canJump);
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
        out.putInt(STATE_ANCHOR_SPOKE, anchorSpoke);
        out.putString(STATE_ANCHOR_SIDE, anchorSide);
        out.putInt(STATE_RESUME, resumeSpoke);
        double[] flat = new double[plucks.size() * 3];
        for (int i = 0; i < plucks.size(); i++) System.arraycopy(plucks.get(i), 0, flat, i * 3, 3);
        out.putDoubleArray(STATE_PLUCKS, flat);
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
        analysisExecutor.shutdownNow();
        super.onDestroy();
    }
}
