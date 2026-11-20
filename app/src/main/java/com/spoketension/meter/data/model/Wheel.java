package com.spoketension.meter.data.model;

import com.google.firebase.Timestamp;
import com.google.firebase.firestore.DocumentId;
import com.google.firebase.firestore.ServerTimestamp;

/** One wheel. Stored at wheels/{id}. */
public class Wheel {
    @DocumentId public String id;     // filled by Firestore, not written
    public String name;
    public int spokeCount;
    public String position;           // "front" or "rear"
    public double lengthLeftMm;
    public double lengthRightMm;
    public double diameterMm;
    public String material;           // Material enum name, e.g. "STAINLESS"
    public double rimMaxKgf;
    public double expectLeftKgf;      // target tension per side (0 on wheels made before this field)
    public double expectRightKgf;
    @ServerTimestamp public Timestamp createdAt;

    public Wheel() { }                // required by Firestore

    public double lengthMm(String side) {
        return "R".equals(side) ? lengthRightMm : lengthLeftMm;
    }

    public double expectKgf(String side) {
        return "R".equals(side) ? expectRightKgf : expectLeftKgf;
    }

    /** Spokes on one side; numbering restarts at 1 on each side. */
    public int spokesPerSide() {
        return spokeCount / 2;
    }
}
