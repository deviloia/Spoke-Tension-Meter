package com.spoketension.meter.ui;

import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.spoketension.meter.R;
import com.spoketension.meter.calc.SafetyJudge;
import com.spoketension.meter.data.FirestoreRepository;
import com.spoketension.meter.data.model.Reading;
import com.spoketension.meter.data.model.Session;
import com.spoketension.meter.data.model.Wheel;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Wheel hub: radar chart of both sides against their expected tension, plus a collapsible
 * list per side. Each row has a status badge (tap = why) and a measure / re-measure button.
 */
public class ResultActivity extends AppCompatActivity {

    private final FirestoreRepository repository = new FirestoreRepository();
    private String wheelId, sessionId, sessionLabel;
    private Wheel wheel;
    private boolean destroyed;

    private TextView tvSession, tvStatus, headerL, headerR;
    private LinearLayout listL, listR;
    private RadarView radar;
    private Button btnMeasureL, btnMeasureR;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_result);
        wheelId = getIntent().getStringExtra(MeasureActivity.EXTRA_WHEEL_ID);
        tvSession = findViewById(R.id.tvSession);
        tvStatus = findViewById(R.id.tvStatus);
        headerL = findViewById(R.id.headerL);
        headerR = findViewById(R.id.headerR);
        listL = findViewById(R.id.listL);
        listR = findViewById(R.id.listR);
        radar = findViewById(R.id.radar);
        btnMeasureL = findViewById(R.id.btnMeasureL);
        btnMeasureR = findViewById(R.id.btnMeasureR);
        headerL.setOnClickListener(v -> toggle(listL));
        headerR.setOnClickListener(v -> toggle(listR));
        findViewById(R.id.btnNewSession).setOnClickListener(v -> askNewSession());
    }

    @Override
    protected void onResume() {
        super.onResume();
        load();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        super.onDestroy();
    }

    private void load() {
        tvStatus.setText(R.string.loading);
        repository.loadWheel(wheelId, w -> {
            if (destroyed) return;
            if (w == null) {
                tvStatus.setText(R.string.wheel_not_found);
                return;
            }
            wheel = w;
            setTitle(w.name);
            if (sessionId != null) loadReadings();
            else repository.loadLatestSession(wheelId, s -> {
                if (destroyed) return;
                if (s != null) {
                    sessionId = s.id;
                    sessionLabel = s.label;
                } else startSession("before");
                loadReadings();
            }, this::loadFailed);
        }, this::loadFailed);
    }

    /** New session id; it is written to Firestore with the first saved spoke. */
    private void startSession(String label) {
        sessionId = UUID.randomUUID().toString();
        sessionLabel = label;
    }

    private void loadReadings() {
        tvSession.setText(getString(R.string.session_title, sessionLabel));
        repository.loadReadings(wheelId, sessionId, readings -> {
            if (!destroyed) render(readings);
        }, this::loadFailed);
    }

    private void loadFailed(Exception e) {
        if (!destroyed) tvStatus.setText(getString(R.string.load_failed, e.getMessage()));
    }

    private void askNewSession() {
        EditText et = new EditText(this);
        et.setText(R.string.after);
        new AlertDialog.Builder(this)
                .setTitle(R.string.new_session)
                .setView(et)
                .setPositiveButton(android.R.string.ok, (d, which) -> {
                    String label = et.getText().toString().trim();
                    startSession(label.isEmpty() ? getString(R.string.after) : label);
                    // Persist now so it stays the latest session even before a spoke is saved.
                    repository.saveSession(wheelId, sessionId, new Session(sessionLabel),
                            unused -> { if (!destroyed) load(); }, this::loadFailed);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void render(List<Reading> readings) {
        int n = wheel.spokesPerSide();
        Reading[] l = new Reading[n], r = new Reading[n];
        for (Reading x : readings) {
            if (x.legacyNumbering() || x.spokeNo < 1 || x.spokeNo > n) continue;
            if ("R".equals(x.side)) r[x.spokeNo - 1] = x; else l[x.spokeNo - 1] = x;
        }
        double max = Math.max(wheel.rimMaxKgf, Math.max(wheel.expectLeftKgf, wheel.expectRightKgf));
        for (Reading x : l) if (x != null) max = Math.max(max, x.tensionKgf);
        for (Reading x : r) if (x != null) max = Math.max(max, x.tensionKgf);
        radar.setData(kgf(l), kgf(r), wheel.expectLeftKgf, wheel.expectRightKgf, max * 1.1);
        tvStatus.setText(getString(R.string.result_summary, count(l) + count(r), 2 * n));

        String nameL = ReadingFormat.sideName(this, wheel, "L"), nameR = ReadingFormat.sideName(this, wheel, "R");
        legend(R.id.tvLegendL, nameL, RadarView.LEFT_COLOR);
        legend(R.id.tvLegendR, nameR, RadarView.RIGHT_COLOR);
        setupSide("L", nameL, l, btnMeasureL, headerL, listL);
        setupSide("R", nameR, r, btnMeasureR, headerR, listR);
    }

    private void legend(int id, String name, int color) {
        TextView tv = findViewById(id);
        tv.setText("● " + name);
        tv.setTextColor(color);
    }

    private static double[] kgf(Reading[] side) {
        double[] out = new double[side.length];
        Arrays.fill(out, Double.NaN);
        for (int i = 0; i < side.length; i++) if (side[i] != null) out[i] = side[i].tensionKgf;
        return out;
    }

    private static int count(Reading[] side) {
        int c = 0;
        for (Reading x : side) if (x != null) c++;
        return c;
    }

    /** Expected tension of the side; older wheels without one fall back to the side's average. */
    private double reference(String side, Reading[] readings) {
        double expect = wheel.expectKgf(side);
        if (expect > 0) return expect;
        double sum = 0;
        int c = 0;
        for (Reading x : readings) if (x != null) { sum += x.tensionKgf; c++; }
        return c == 0 ? Double.NaN : sum / c;
    }

    private void setupSide(String side, String name, Reading[] readings,
                           Button quick, TextView header, LinearLayout list) {
        int n = readings.length;
        int firstMissing = 1;
        for (int i = 0; i < n; i++) if (readings[i] == null) { firstMissing = i + 1; break; }
        final int start = firstMissing;
        quick.setText(getString(R.string.measure_side, name));
        quick.setOnClickListener(v -> measure(side, start));
        header.setTag(name + "  " + getString(R.string.measured_count, count(readings), n));
        updateHeader(header, list);

        double ref = reference(side, readings);
        float d = getResources().getDisplayMetrics().density;
        list.removeAllViews();
        for (int i = 0; i < n; i++) {
            Reading x = readings[i];
            final int no = i + 1;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);

            TextView tvNo = new TextView(this);
            tvNo.setText(String.valueOf(no));
            tvNo.setTypeface(Typeface.DEFAULT_BOLD);
            row.addView(tvNo, new LinearLayout.LayoutParams((int) (32 * d), ViewGroup.LayoutParams.WRAP_CONTENT));

            TextView tvValue = new TextView(this);
            row.addView(tvValue, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

            TextView icon = new TextView(this);
            icon.setGravity(Gravity.CENTER);
            icon.setTypeface(Typeface.DEFAULT_BOLD);
            int size = (int) (28 * d);
            row.addView(icon, new LinearLayout.LayoutParams(size, size));

            Button btn = new Button(this, null, androidx.appcompat.R.attr.borderlessButtonStyle);
            btn.setOnClickListener(v -> measure(side, no));
            row.addView(btn);

            if (x == null) {
                tvValue.setText("—");
                btn.setText(R.string.measure);
            } else {
                SafetyJudge.Status status = SafetyJudge.judge(x.tensionKgf, wheel.rimMaxKgf, ref);
                String pct = ref > 0 ? String.format(Locale.US, "  %+.0f%%", (x.tensionKgf / ref - 1) * 100) : "";
                tvValue.setText(String.format(Locale.US, "%.1f kgf  ·  %.1f Hz%s", x.tensionKgf, x.freqMedianHz, pct));
                btn.setText(R.string.remeasure);
                badge(icon, status, ReadingFormat.reason(this, status, x.tensionKgf, ref, wheel.rimMaxKgf));
            }
            list.addView(row);
        }
    }

    private void badge(TextView icon, SafetyJudge.Status status, String why) {
        int color = ReadingFormat.color(status);
        if (status == SafetyJudge.Status.OK) {
            icon.setText("✓");
            icon.setTextColor(color);
            return;
        }
        GradientDrawable oval = new GradientDrawable();
        oval.setShape(GradientDrawable.OVAL);
        oval.setColor(color);
        icon.setBackground(oval);
        icon.setText("!");
        icon.setTextColor(Color.WHITE);
        icon.setContentDescription(why);
        icon.setOnClickListener(v -> showWhy(v, why));
    }

    /** Small bubble under the badge; tapping anywhere else closes it. */
    private void showWhy(View anchor, String why) {
        float d = getResources().getDisplayMetrics().density;
        TextView tv = new TextView(this);
        tv.setText(why);
        tv.setTextColor(Color.WHITE);
        tv.setMaxWidth((int) (260 * d));
        tv.setPadding((int) (12 * d), (int) (8 * d), (int) (12 * d), (int) (8 * d));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.rgb(50, 50, 50));
        bg.setCornerRadius(8 * d);
        PopupWindow popup = new PopupWindow(tv, ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(bg);
        popup.setOutsideTouchable(true);
        tv.setOnClickListener(v -> popup.dismiss());
        popup.showAsDropDown(anchor);
    }

    private void toggle(LinearLayout list) {
        list.setVisibility(list.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
        updateHeader(list == listL ? headerL : headerR, list);
    }

    private void updateHeader(TextView header, LinearLayout list) {
        boolean open = list.getVisibility() == View.VISIBLE;
        header.setText((open ? "▾ " : "▸ ") + header.getTag());
    }

    private void measure(String side, int spokeNo) {
        startActivity(MeasureActivity.intent(this, wheelId, sessionId, sessionLabel, side, spokeNo));
    }
}
