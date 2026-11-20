package com.spoketension.meter.ui;

import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.spoketension.meter.R;
import com.spoketension.meter.calc.RelativeTension;
import com.spoketension.meter.calc.SafetyJudge;
import com.spoketension.meter.data.FirestoreRepository;
import com.spoketension.meter.data.model.Reading;
import com.spoketension.meter.data.model.Wheel;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Whole-wheel table: Hz, %, kgf and a coloured safety status per spoke. */
public class ResultActivity extends AppCompatActivity {

    public static final String EXTRA_SESSION_ID = "sessionId";

    private final FirestoreRepository repository = new FirestoreRepository();
    private TextView tvStatus;
    private ArrayAdapter<Reading> adapter;
    private Wheel wheel;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_result);
        tvStatus = findViewById(R.id.tvStatus);
        ListView list = findViewById(R.id.listReadings);
        adapter = new ArrayAdapter<Reading>(this, android.R.layout.simple_list_item_1, new ArrayList<>()) {
            @NonNull @Override
            public View getView(int position, View convertView, @NonNull ViewGroup parent) {
                TextView tv = (TextView) super.getView(position, convertView, parent);
                Reading r = getItem(position);
                tv.setText(ReadingFormat.rowText(ResultActivity.this, wheel, r)
                        + String.format(Locale.US, "%nvs side average: %+.1f %%", r.relativePercent));
                tv.setBackgroundColor(ReadingFormat.color(r.status));
                return tv;
            }
        };
        list.setAdapter(adapter);

        String wheelId = getIntent().getStringExtra(MeasureActivity.EXTRA_WHEEL_ID);
        String sessionId = getIntent().getStringExtra(EXTRA_SESSION_ID);
        tvStatus.setText(R.string.loading);
        repository.loadWheel(wheelId, w -> repository.loadReadings(wheelId, sessionId, readings -> {
            wheel = w;
            judgeAll(readings, wheel);
            adapter.clear();
            adapter.addAll(readings);
            tvStatus.setText(getString(R.string.result_summary, readings.size(), wheel.spokeCount));
            // Persist the side-relative values computed now that the whole wheel is known.
            for (Reading r : readings)
                repository.saveReading(wheelId, sessionId, r, unused -> { }, e ->
                        tvStatus.setText(getString(R.string.save_failed, e.getMessage())));
        }, this::loadFailed), this::loadFailed);
    }

    private void loadFailed(Exception e) {
        tvStatus.setText(getString(R.string.load_failed, e.getMessage()));
    }

    /** Fills relativePercent and status using each side's average. */
    static void judgeAll(List<Reading> readings, Wheel wheel) {
        for (String side : new String[]{"L", "R"}) {
            List<Double> hz = new ArrayList<>();
            double kgfSum = 0;
            for (Reading r : readings) if (side.equals(r.side)) { hz.add(r.freqMedianHz); kgfSum += r.tensionKgf; }
            if (hz.isEmpty()) continue;
            double avgHz = RelativeTension.sideAverageHz(hz);
            double avgKgf = kgfSum / hz.size();
            for (Reading r : readings) {
                if (!side.equals(r.side)) continue;
                r.relativePercent = RelativeTension.percent(r.freqMedianHz, avgHz);
                r.status = SafetyJudge.judge(r.tensionKgf, wheel.rimMaxKgf, avgKgf).name().toLowerCase(Locale.US);
            }
        }
    }

}
