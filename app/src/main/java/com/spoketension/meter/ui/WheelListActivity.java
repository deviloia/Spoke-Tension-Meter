package com.spoketension.meter.ui;

import android.content.Intent;
import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.spoketension.meter.R;
import com.spoketension.meter.data.FirestoreRepository;
import com.spoketension.meter.data.model.Wheel;

import java.util.ArrayList;
import java.util.List;

/** Home: lists all wheels from Firestore; "+" adds one, tapping one opens its results / measuring hub. */
public class WheelListActivity extends AppCompatActivity {

    private final FirestoreRepository repository = new FirestoreRepository();
    private final List<Wheel> wheels = new ArrayList<>();
    private ArrayAdapter<String> adapter;
    private TextView tvStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_wheel_list);
        tvStatus = findViewById(R.id.tvStatus);
        ListView list = findViewById(R.id.listWheels);
        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, new ArrayList<>());
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) ->
                startActivity(new Intent(this, ResultActivity.class)
                        .putExtra(MeasureActivity.EXTRA_WHEEL_ID, wheels.get(position).id)));
        findViewById(R.id.btnAddWheel).setOnClickListener(v ->
                startActivity(new Intent(this, WheelSetupActivity.class)));
        findViewById(R.id.btnLab).setOnClickListener(v ->
                startActivity(new Intent(this, LabActivity.class)));
    }

    @Override
    protected void onResume() {
        super.onResume();
        tvStatus.setText(R.string.loading);
        repository.loadWheels(result -> {
            wheels.clear();
            wheels.addAll(result);
            adapter.clear();
            for (Wheel w : result)
                adapter.add(getString(R.string.wheel_row, w.name, w.position, w.spokeCount, w.material));
            tvStatus.setText(result.isEmpty() ? getString(R.string.no_wheels) : "");
        }, e -> tvStatus.setText(getString(R.string.load_failed, e.getMessage())));
    }
}
