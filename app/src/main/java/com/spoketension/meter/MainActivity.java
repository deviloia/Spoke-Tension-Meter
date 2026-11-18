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
<<<<<<< Updated upstream
            tvStatus.setText("connect…");
=======
            tvStatus.setText(R.string.status_connecting);
>>>>>>> Stashed changes
            repository.testConnection(new FirestoreRepository.Callback() {
                @Override
                public void onSuccess(String documentId, String message) {
                    tvStatus.setText(getString(R.string.status_success, documentId, message));
                }

                @Override
                public void onError(Exception e) {
                    Log.e(TAG, "Firestore test failed", e);
<<<<<<< Updated upstream
                    tvStatus.setText("connect fail：" + e.getMessage());
=======
                    tvStatus.setText(getString(R.string.status_failed, e.getMessage()));
>>>>>>> Stashed changes
                }
            });
        });
    }
}
