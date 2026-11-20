package com.spoketension.meter.data.model;

import com.google.firebase.firestore.DocumentId;

import java.util.List;

/** One spoke's measurement result. Stored at wheels/{id}/sessions/{id}/readings/{side}{spokeNo}, e.g. "R5". */
public class Reading {
    @DocumentId public String id;  // filled by Firestore, not written
    public int spokeNo;            // 1..spokesPerSide, counted within its side
    public String side;            // "L" or "R"
    public List<Double> freqHz;    // raw frequencies (up to three plucks), Sally method
    public List<Double> fftHz;     // same plucks, single-window FFT (NaN = not found)
    public List<Double> mpHz;      // same plucks, MatrixPencil (NaN = not found)
    public double freqMedianHz;    // final Hz: mean of Sally and MatrixPencil
    public double relativePercent; // method 1: e.g. -11.1
    public double tensionKgf;      // method 2
    public String status;          // "ok", "uneven", "loose", "danger"
    public Double meterKgf;        // tension meter, filled in later (nullable)

    public Reading() { }           // required by Firestore

    /** Saved with the old wheel-wide 1..N numbering (doc id "5" instead of "R5"). */
    public boolean legacyNumbering() {
        return id != null && !id.equals(side + spokeNo);
    }
}
