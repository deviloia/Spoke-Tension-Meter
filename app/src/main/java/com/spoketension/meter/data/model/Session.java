package com.spoketension.meter.data.model;

import com.google.firebase.Timestamp;
import com.google.firebase.firestore.DocumentId;
import com.google.firebase.firestore.ServerTimestamp;

/** One measuring pass, e.g. "before" / "after" truing. Stored at wheels/{id}/sessions/{id}. */
public class Session {
    @DocumentId public String id;
    public String label;
    @ServerTimestamp public Timestamp createdAt;

    public Session() { }              // required by Firestore

    public Session(String label) {
        this.label = label;
    }
}
