package com.spoketension.meter.ui;

import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.spoketension.meter.R;
import com.spoketension.meter.calc.Material;
import com.spoketension.meter.data.FirestoreRepository;
import com.spoketension.meter.data.model.Wheel;

/** Enter a wheel: name, spoke count, front/rear, left/right length, diameter, material, rim limit, expected tension per side. */
public class WheelSetupActivity extends AppCompatActivity {

    private final FirestoreRepository repository = new FirestoreRepository();
    private EditText etName, etSpokes, etLenL, etLenR, etDiameter, etRimMax, etExpL, etExpR;
    private RadioGroup rgPosition;
    private Spinner spMaterial;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_wheel_setup);
        etName = findViewById(R.id.etName);
        etSpokes = findViewById(R.id.etSpokes);
        etLenL = findViewById(R.id.etLengthLeft);
        etLenR = findViewById(R.id.etLengthRight);
        etDiameter = findViewById(R.id.etDiameter);
        etRimMax = findViewById(R.id.etRimMax);
        etExpL = findViewById(R.id.etExpectLeft);
        etExpR = findViewById(R.id.etExpectRight);
        rgPosition = findViewById(R.id.rgPosition);
        spMaterial = findViewById(R.id.spMaterial);
        spMaterial.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, Material.values()));
        findViewById(R.id.btnSave).setOnClickListener(v -> save());
    }

    private void save() {
        Wheel w = new Wheel();
        try {
            w.name = etName.getText().toString().trim();
            w.spokeCount = Integer.parseInt(etSpokes.getText().toString());
            w.lengthLeftMm = Double.parseDouble(etLenL.getText().toString());
            w.lengthRightMm = Double.parseDouble(etLenR.getText().toString());
            w.diameterMm = Double.parseDouble(etDiameter.getText().toString());
            w.rimMaxKgf = Double.parseDouble(etRimMax.getText().toString());
            w.expectLeftKgf = Double.parseDouble(etExpL.getText().toString());
            w.expectRightKgf = Double.parseDouble(etExpR.getText().toString());
            if (w.name.isEmpty() || w.spokeCount < 2 || w.spokeCount % 2 != 0
                    || !(w.expectLeftKgf > 0) || !(w.expectRightKgf > 0) || !(w.lengthLeftMm > 0) || !(w.lengthRightMm > 0)
                    || !(w.diameterMm > 0) || !(w.rimMaxKgf > 0))
                throw new NumberFormatException();
        } catch (NumberFormatException e) {
            Toast.makeText(this, R.string.invalid_input, Toast.LENGTH_LONG).show();
            return;
        }
        w.position = rgPosition.getCheckedRadioButtonId() == R.id.rbRear ? "rear" : "front";
        w.material = ((Material) spMaterial.getSelectedItem()).name();
        findViewById(R.id.btnSave).setEnabled(false);
        repository.addWheel(w, id -> finish(), e -> {
            findViewById(R.id.btnSave).setEnabled(true);
            Toast.makeText(this, getString(R.string.save_failed, e.getMessage()), Toast.LENGTH_LONG).show();
        });
    }
}
