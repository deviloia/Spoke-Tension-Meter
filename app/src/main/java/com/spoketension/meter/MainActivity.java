package com.spoketension.meter;

import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.spoketension.meter.data.FirestoreRepository;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";

    private final FirestoreRepository repository = new FirestoreRepository();
    private TextView tvStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvStatus = findViewById(R.id.tvStatus);
        Button btnTest = findViewById(R.id.btnTestFirestore);

        btnTest.setOnClickListener(v -> {
            tvStatus.setText("connect…");
            repository.testConnection(new FirestoreRepository.Callback() {
                @Override
                public void onSuccess(String message) {
                    tvStatus.setText(message);
                }

                @Override
                public void onError(Exception e) {
                    Log.e(TAG, "Firestore test failed", e);
                    tvStatus.setText("connect fail：" + e.getMessage());
                }
            });
        });
    }
}
