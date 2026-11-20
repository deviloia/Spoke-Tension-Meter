package com.spoketension.meter.data;

import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.firebase.firestore.CollectionReference;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.Query;
import com.spoketension.meter.data.model.Reading;
import com.spoketension.meter.data.model.Session;
import com.spoketension.meter.data.model.Wheel;

import java.util.List;

/** All Firestore access lives here; screens never touch Firestore directly. */
public class FirestoreRepository {

    private final FirebaseFirestore db = FirebaseFirestore.getInstance();

    private CollectionReference wheels() {
        return db.collection("wheels");
    }

    private CollectionReference readings(String wheelId, String sessionId) {
        return wheels().document(wheelId).collection("sessions").document(sessionId).collection("readings");
    }

    public void loadWheels(OnSuccessListener<List<Wheel>> onDone, OnFailureListener onError) {
        wheels().orderBy("createdAt", Query.Direction.DESCENDING).get()
                .addOnSuccessListener(snap -> onDone.onSuccess(snap.toObjects(Wheel.class)))
                .addOnFailureListener(onError);
    }

    public void loadWheel(String wheelId, OnSuccessListener<Wheel> onDone, OnFailureListener onError) {
        wheels().document(wheelId).get()
                .addOnSuccessListener(snap -> onDone.onSuccess(snap.toObject(Wheel.class)))
                .addOnFailureListener(onError);
    }

    /** Create a wheel -> returns its generated id. */
    public void addWheel(Wheel wheel, OnSuccessListener<String> onDone, OnFailureListener onError) {
        wheels().add(wheel)
                .addOnSuccessListener(ref -> onDone.onSuccess(ref.getId()))
                .addOnFailureListener(onError);
    }

    /** Start a measurement session under a wheel -> returns its generated id. */
    public void addSession(String wheelId, Session session,
                           OnSuccessListener<String> onDone, OnFailureListener onError) {
        wheels().document(wheelId).collection("sessions").add(session)
                .addOnSuccessListener(ref -> onDone.onSuccess(ref.getId()))
                .addOnFailureListener(onError);
    }

    /** Save one spoke; the spoke number is the document id, so re-measuring overwrites it. */
    public void saveReading(String wheelId, String sessionId, Reading reading,
                            OnSuccessListener<Void> onDone, OnFailureListener onError) {
        readings(wheelId, sessionId).document(String.valueOf(reading.spokeNo)).set(reading)
                .addOnSuccessListener(onDone)
                .addOnFailureListener(onError);
    }

    /** All readings of a session, ordered by spoke number. */
    public void loadReadings(String wheelId, String sessionId,
                             OnSuccessListener<List<Reading>> onDone, OnFailureListener onError) {
        readings(wheelId, sessionId).orderBy("spokeNo").get()
                .addOnSuccessListener(snap -> onDone.onSuccess(snap.toObjects(Reading.class)))
                .addOnFailureListener(onError);
    }
}
