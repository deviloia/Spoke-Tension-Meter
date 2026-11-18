package com.spoketension.meter.data;

import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;

import java.util.HashMap;
import java.util.Map;

/** 集中管理所有 Firestore 存取。之後的輪組 / 輻條讀數也會放在這裡。 */
public class FirestoreRepository {

    public interface Callback {
        void onSuccess(String message);
        void onError(Exception e);
    }

    private static final String COLLECTION_TEST = "connection_test";

    private final FirebaseFirestore db = FirebaseFirestore.getInstance();

    /** 寫入一筆測試文件再讀回來，確認讀寫都正常。 */
    public void testConnection(final Callback callback) {
        Map<String, Object> data = new HashMap<>();
        data.put("message", "hello from Spoke Tension app");
        data.put("createdAt", FieldValue.serverTimestamp());

        db.collection(COLLECTION_TEST)
                .add(data)
                .addOnSuccessListener(ref -> ref.get()
                        .addOnSuccessListener(snap -> callback.onSuccess(
                                "連線成功！\n寫入並讀回文件：" + ref.getId()
                                        + "\nmessage = " + snap.getString("message")))
                        .addOnFailureListener(callback::onError))
                .addOnFailureListener(callback::onError);
    }
}
