package com.spoketension.meter.data;

import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;

import java.util.HashMap;
import java.util.Map;

/** Central place for all Firestore access. Wheel and spoke reading data will be added here later. */
public class FirestoreRepository {

    public interface Callback {
        /** @param documentId ID of the test document; @param message value read back from it */
        void onSuccess(String documentId, String message);
        void onError(Exception e);
    }

    private static final String COLLECTION_TEST = "connection_test";

    private final FirebaseFirestore db = FirebaseFirestore.getInstance();

    /** Writes a test document and reads it back to verify both read and write access. */
    public void testConnection(final Callback callback) {
        Map<String, Object> data = new HashMap<>();
        data.put("message", "hello from Spoke Tension app");
        data.put("createdAt", FieldValue.serverTimestamp());

        db.collection(COLLECTION_TEST)
                .add(data)
                .addOnSuccessListener(ref -> ref.get()
                        .addOnSuccessListener(snap -> callback.onSuccess(
                                ref.getId(), snap.getString("message")))
                        .addOnFailureListener(callback::onError))
                .addOnFailureListener(callback::onError);
    }
}
