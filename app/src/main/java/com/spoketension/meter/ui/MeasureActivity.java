package com.spoketension.meter.ui;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.ColorUtils;

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

import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Measures one side of a wheel, spoke by spoke, with the microphone listening the whole time.
 * Each pluck: Sally + MatrixPencil (FFT is stored for comparison only).
 *  grey   = no clean spoke tone heard -> keep listening for the same spoke
 *  red    = a tone was heard but Sally and MP disagree (or MP found nothing) -> pluck again
 *  green  = saved, shown for 1 s, then the next unmeasured spoke
 *  orange = saved but above the rim limit (likely a mis-read), shown 1 s, then the next spoke;
 *           the user goes back with ◀ to measure it again
 * Every pluck shows Sally, MP and FFT values so a rejection can be judged.
 */
public class MeasureActivity extends AppCompatActivity {

    public static final String EXTRA_WHEEL_ID = "wheelId";
    private static final String EXTRA_SESSION_ID = "sessionId";
    private static final String EXTRA_SESSION_LABEL = "sessionLabel";
    private static final String EXTRA_SIDE = "side";
    private static final String EXTRA_SPOKE = "spokeNo";
    private static final String STATE_SPOKE = "spokeNo";
    private static final int REQUEST_MIC = 101;
    private static final int FS = SignalAnalyzer.SAMPLE_RATE;
    private static final long RESULT_MS = 1000;

    /** Panel backgrounds: faded versions of the status palette in ReadingFormat. */
    private static final int GREEN = faded(SafetyJudge.Status.OK), ORANGE = faded(SafetyJudge.Status.LOOSE),
            RED = faded(SafetyJudge.Status.DANGER), NEUTRAL = Color.rgb(238, 238, 238);

    private static int faded(SafetyJudge.Status s) {
        return ColorUtils.setAlphaComponent(ReadingFormat.color(s), 80);
    }

    static Intent intent(Context c, String wheelId, String sessionId, String label, String side, int spokeNo) {
        return new Intent(c, MeasureActivity.class).putExtra(EXTRA_WHEEL_ID, wheelId)
                .putExtra(EXTRA_SESSION_ID, sessionId).putExtra(EXTRA_SESSION_LABEL, label)
                .putExtra(EXTRA_SIDE, side).putExtra(EXTRA_SPOKE, spokeNo);
    }

    /** Immutable target: a late result can never be assigned to a different spoke. */
    private static final class Target {
        final int spokeNo;
        final PitchDetector.Params params;
        final double[] band;   // MatrixPencil search band, same 30–200 kgf range as Sally

        Target(int spokeNo, PitchDetector.Params params, double[] band) {
            this.spokeNo = spokeNo;
            this.params = params;
            this.band = band;
        }
    }

    private final FirestoreRepository repository = new FirestoreRepository();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    /** MatrixPencil runs here so the capture thread keeps draining the microphone. */
    private final ExecutorService analysisExecutor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable nextSpoke = this::next;
    /** Spoke being listened for; null while a pluck is analysed, saved, shown or awaiting a choice. */
    private final AtomicReference<Target> target = new AtomicReference<>();
    /** Spoke numbers of this side already saved in the session (UI thread only). */
    private final Set<Integer> measured = new HashSet<>();

    // Also read by the capture thread.
    private volatile int generation;
    private volatile boolean listening;
    private volatile AudioCapture.Stream activeStream;

    private String wheelId, sessionId, sessionLabel, side;
    private Wheel wheel;
    private int spokeNo;
    private boolean sessionSaved, visible, destroyed;
    /** Reading whose save failed, waiting for "Retry save". */
    private Reading pending;

    private LinearLayout panel;
    private TextView tvSide, tvSpokeBig, tvOfN, tvBig, tvSmall;
    private Button btnPrev, btnNext, btnListen, btnRetry, btnRetrySave;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_measure);
        wheelId = getIntent().getStringExtra(EXTRA_WHEEL_ID);
        sessionId = getIntent().getStringExtra(EXTRA_SESSION_ID);
        sessionLabel = getIntent().getStringExtra(EXTRA_SESSION_LABEL);
        side = getIntent().getStringExtra(EXTRA_SIDE);
        spokeNo = state != null ? state.getInt(STATE_SPOKE) : getIntent().getIntExtra(EXTRA_SPOKE, 1);

        panel = findViewById(R.id.panel);
        tvSide = findViewById(R.id.tvSide);
        tvSpokeBig = findViewById(R.id.tvSpokeBig);
        tvOfN = findViewById(R.id.tvOfN);
        tvBig = findViewById(R.id.tvBig);
        tvSmall = findViewById(R.id.tvSmall);
        btnPrev = findViewById(R.id.btnPrev);
        btnNext = findViewById(R.id.btnNext);
        btnListen = findViewById(R.id.btnListen);
        btnRetry = findViewById(R.id.btnRetry);
        btnRetrySave = findViewById(R.id.btnRetrySave);

        btnPrev.setOnClickListener(v -> selectSpoke(spokeNo - 1));
        btnNext.setOnClickListener(v -> selectSpoke(spokeNo + 1));
        btnListen.setOnClickListener(v -> {
            if (listening) stopListening(); else requestListening();
        });
        btnRetry.setOnClickListener(v -> selectSpoke(spokeNo));
        btnRetrySave.setOnClickListener(v -> {
            Reading r = pending;
            pending = null;
            if (r != null) save(r);
        });
        findViewById(R.id.btnDone).setOnClickListener(v -> finish());

        updateControls();
        show(NEUTRAL, getString(R.string.loading), "");
        repository.loadWheel(wheelId, w -> {
            if (destroyed) return;
            if (w == null) {
                show(RED, getString(R.string.wheel_not_found), "");
                return;
            }
            if (w.spokesPerSide() < 1) {   // wheels saved before the even-count check
                show(RED, getString(R.string.invalid_wheel), "");
                return;
            }
            repository.loadReadings(wheelId, sessionId, readings -> {
                if (destroyed) return;
                wheel = w;
                setTitle(w.name);
                tvSide.setText(ReadingFormat.sideName(this, w, side));
                tvOfN.setText(getString(R.string.of_n, w.spokesPerSide()));
                sessionSaved = !readings.isEmpty();
                for (Reading r : readings) if (side.equals(r.side) && !r.legacyNumbering()) measured.add(r.spokeNo);
                selectSpoke(spokeNo);
                requestListening();
            }, this::loadFailed);
        }, this::loadFailed);
    }

    private void loadFailed(Exception e) {
        if (!destroyed) show(RED, getString(R.string.load_failed, e.getMessage()), "");
    }

    /** Listen for this spoke; also "measure again" for the current one. */
    private void selectSpoke(int no) {
        if (wheel == null) return;
        spokeNo = Math.floorMod(no - 1, wheel.spokesPerSide()) + 1;
        pending = null;
        main.removeCallbacks(nextSpoke);   // cancel a pending green -> next, nothing else
        tvSpokeBig.setText(String.valueOf(spokeNo));
        String already = measured.contains(spokeNo) ? getString(R.string.already_measured) : "";
        if (listening) {
            target.set(newTarget());
            show(NEUTRAL, getString(R.string.pluck_now), already);
        } else show(NEUTRAL, getString(R.string.tap_listen), already);
        updateControls();
    }

    private Target newTarget() {
        double lengthCm = wheel.lengthMm(side) / 10.0;
        double density = Material.valueOf(wheel.material).densityKgM3;
        return new Target(spokeNo, new PitchDetector.Params(lengthCm, wheel.diameterMm, density),
                SignalAnalyzer.bandFromTension(SoundTensionCalculator.MIN_TENSION_KGF,
                        SoundTensionCalculator.MAX_TENSION_KGF, lengthCm, wheel.diameterMm, density));
    }

    private void requestListening() {
        if (!visible || wheel == null || listening) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_MIC);
            return;
        }
        listening = true;
        final int gen = ++generation;
        selectSpoke(spokeNo);
        executor.execute(() -> captureLoop(gen));
    }

    private void captureLoop(int gen) {
        try (AudioCapture.Stream stream = AudioCapture.openStream(getApplicationContext(), FS)) {
            activeStream = stream;
            PluckSegmenter segmenter = new PluckSegmenter(FS);
            Target previous = null;
            short[] buffer = new short[1024];
            while (gen == generation && listening) {
                Target beforeRead = target.get();
                int count = stream.read(buffer);
                if (count < 0) break;
                Target current = target.get();
                if (current != previous || current != beforeRead) {
                    // Target changed: drop boundary audio so it is never assigned to the new spoke.
                    segmenter.reset();
                    previous = current;
                    continue;
                }
                if (current == null) continue;   // busy: keep draining the mic, queue nothing
                for (PluckSegmenter.Segment seg : segmenter.accept(buffer, count)) {
                    if (gen != generation || target.get() != current) break;
                    PitchDetector.Report report = PitchDetector.analyzeAtOnset(
                            seg.samples, FS, current.params, seg.onsetSample);
                    if (!(report.finalHz > 0)) {
                        // No candidate at all (noise, bump): keep listening, skip the slow MP.
                        main.post(() -> noPluck(gen, current, report.explanation));
                        continue;
                    }
                    if (!target.compareAndSet(current, null)) break;
                    analysisExecutor.execute(() -> {
                        double[] fftMp = SignalAnalyzer.fftAndMatrixPencil(
                                SignalAnalyzer.windowAt(seg.samples, FS, seg.onsetSample),
                                FS, current.band[0], current.band[1]);
                        main.post(() -> analysed(gen, current, report, fftMp[0], fftMp[1]));
                    });
                    break;
                }
            }
        } catch (Exception e) {
            main.post(() -> {
                if (destroyed || gen != generation) return;
                stopListening();
                show(RED, getString(R.string.record_failed, String.valueOf(e)), "");
            });
        } finally {
            activeStream = null;
            main.post(() -> {
                if (!destroyed && gen == generation && listening) stopListening();
            });
        }
    }

    private static boolean usable(PitchDetector.Report report) {
        return Double.isFinite(report.finalHz) && report.finalHz > 0
                && report.verdict != PitchDetector.Verdict.FAILED
                && report.verdict != PitchDetector.Verdict.DISAGREE;
    }

    private boolean live(int gen) {
        return listening && gen == generation;
    }

    /** Sally heard no spoke tone: neutral (not an error), the same target keeps listening. */
    private void noPluck(int gen, Target t, String why) {
        if (destroyed || !live(gen) || target.get() != t) return;
        show(NEUTRAL, getString(R.string.no_pluck), why);
    }

    /** Red: show why and listen for the same spoke again. */
    private void rejected(int gen, Target t, String why) {
        if (destroyed || t.spokeNo != spokeNo) return;
        show(RED, getString(R.string.measure_again), why);
        if (live(gen)) target.set(newTarget());
    }

    /** "Sally 346.1 · MP 345.9 · FFT 346.0 Hz"; — = not found. */
    private static String methods(double sally, double mp, double fft) {
        return String.format(Locale.US, "Sally %s · MP %s · FFT %s Hz", fmt(sally), fmt(mp), fmt(fft));
    }

    private static String fmt(double hz) {
        return hz > 0 ? String.format(Locale.US, "%.1f", hz) : "—";
    }

    private void analysed(int gen, Target t, PitchDetector.Report report, double fft, double mp) {
        if (destroyed || t.spokeNo != spokeNo) return;   // kept even if Stop was tapped meanwhile
        double sally = report.finalHz;
        String values = methods(sally, mp, fft);
        // Sally's FFT and YIN disagree, but MatrixPencil confirms the FFT value -> two methods agree, accept.
        // (On DISAGREE Sally's finalHz is its multi-frame FFT value.)
        boolean mpBacksFft = report.verdict == PitchDetector.Verdict.DISAGREE
                && !Double.isNaN(SafetyJudge.combineHz(sally, mp));
        if (!usable(report) && !mpBacksFft) {
            rejected(gen, t, report.explanation + "\n" + values);
            return;
        }
        double hz = SafetyJudge.combineHz(sally, mp);
        if (Double.isNaN(hz)) {
            rejected(gen, t, (Double.isFinite(mp)
                    ? getString(R.string.why_disagree, sally, mp, Math.abs(sally - mp),
                            SafetyJudge.spread(sally, mp) * 100, SafetyJudge.AGREE_RATIO * 100)
                    : getString(R.string.why_no_mp, sally)) + "\n" + values);
            return;
        }
        Reading r = new Reading();
        r.spokeNo = t.spokeNo;
        r.side = side;
        r.freqHz = Collections.singletonList(sally);
        r.fftHz = Collections.singletonList(fft);
        r.mpHz = Collections.singletonList(mp);
        r.freqMedianHz = hz;
        r.tensionKgf = SoundTensionCalculator.kgf(hz, wheel.lengthMm(side) / 1000.0,
                wheel.diameterMm / 1000.0, Material.valueOf(wheel.material));
        double expect = wheel.expectKgf(side);
        r.relativePercent = expect > 0 ? (r.tensionKgf / expect - 1) * 100 : Double.NaN;
        r.status = SafetyJudge.judge(r.tensionKgf, wheel.rimMaxKgf, wheel.expectKgf(side))
                .name().toLowerCase(Locale.US);
        save(r);
    }

    private void save(Reading r) {
        target.set(null);   // no second pluck for this spoke while its save is in flight
        show(NEUTRAL, getString(R.string.saving), "");
        updateControls();
        if (sessionSaved) persist(r);
        else repository.saveSession(wheelId, sessionId, new Session(sessionLabel), unused -> {
            sessionSaved = true;
            persist(r);
        }, e -> saveFailed(r, e));
    }

    /** Saved: green, or orange when above the rim limit (likely a mis-read; the user can go back). */
    private void persist(Reading r) {
        repository.saveReading(wheelId, sessionId, r, unused -> {
            if (destroyed) return;
            measured.add(r.spokeNo);
            if (r.spokeNo != spokeNo) return;   // the user already moved to another spoke
            boolean over = r.tensionKgf > wheel.rimMaxKgf;
            String small = getString(R.string.spoke_saved, r.spokeNo) + "\n"
                    + methods(r.freqHz.get(0), r.mpHz.get(0), r.fftHz.get(0))
                    + (over ? "\n" + getString(R.string.why_over_limit, r.tensionKgf, wheel.rimMaxKgf) : "");
            show(over ? ORANGE : GREEN,
                    String.format(Locale.US, "%.1f kgf\n%.1f Hz", r.tensionKgf, r.freqMedianHz), small);
            main.postDelayed(nextSpoke, RESULT_MS);
        }, e -> saveFailed(r, e));
    }

    private void saveFailed(Reading r, Exception e) {
        if (destroyed) return;
        if (r.spokeNo != spokeNo) selectSpoke(r.spokeNo);   // go back so the failure is not missed
        target.set(null);   // hold the mic until the user retries or measures again
        pending = r;   // "Retry save" saves it again
        show(RED, getString(R.string.save_failed, e.getMessage()), "");
        updateControls();
    }

    /** Next unmeasured spoke after the current one, wrapping; the side is done when none is left. */
    private void next() {
        int n = wheel.spokesPerSide();
        for (int i = 1; i <= n; i++) {
            int no = Math.floorMod(spokeNo - 1 + i, n) + 1;
            if (!measured.contains(no)) {
                selectSpoke(no);
                return;
            }
        }
        stopListening();
        show(GREEN, getString(R.string.side_done), getString(R.string.side_done_hint));
    }

    private void stopListening() {
        ++generation;
        listening = false;
        target.set(null);
        AudioCapture.Stream s = activeStream;
        if (s != null) s.stop();
        updateControls();
    }

    private void show(int color, String big, String small) {
        panel.setBackgroundColor(color);
        tvBig.setText(big);
        tvSmall.setText(small);
        tvSmall.setVisibility(small.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void updateControls() {
        boolean choice = pending != null;
        btnRetry.setVisibility(choice ? View.VISIBLE : View.GONE);
        btnRetrySave.setVisibility(choice ? View.VISIBLE : View.GONE);
        btnListen.setText(listening ? R.string.stop : R.string.listen);
        btnListen.setEnabled(wheel != null);
        btnPrev.setEnabled(wheel != null);
        btnNext.setEnabled(wheel != null);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(requestCode, permissions, grants);
        if (requestCode != REQUEST_MIC || destroyed) return;
        if (grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED) requestListening();
        else show(RED, getString(R.string.mic_required), "");
    }

    @Override
    protected void onStart() {
        super.onStart();
        visible = true;
    }

    @Override
    protected void onStop() {
        visible = false;
        if (listening) {
            stopListening();
            // Only replace the live prompt; keep red reasons, Saving… and green results.
            if (getString(R.string.pluck_now).contentEquals(tvBig.getText()))
                show(NEUTRAL, getString(R.string.tap_listen), "");
        }
        super.onStop();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        // ponytail: an orange reading awaiting a choice is dropped on rotation; pluck that spoke again.
        out.putInt(STATE_SPOKE, spokeNo);
        super.onSaveInstanceState(out);
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        main.removeCallbacksAndMessages(null);
        stopListening();
        executor.shutdownNow();
        analysisExecutor.shutdownNow();
        super.onDestroy();
    }
}
