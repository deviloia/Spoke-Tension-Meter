package com.spoketension.meter.data.model;

import java.util.List;

/** One spoke's measurement result. Stored at wheels/{id}/sessions/{id}/readings/{spokeNo}. */
public class Reading {
    public int spokeNo;
    public String side;            // "L" or "R"
    public List<Double> freqHz;    // raw frequencies (up to three plucks), Sally method
    public List<Double> fftHz;     // same plucks, single-window FFT (NaN = not found)
    public List<Double> mpHz;      // same plucks, MatrixPencil (NaN = not found)
    public double freqMedianHz;
    public double relativePercent; // method 1: e.g. -11.1
    public double tensionKgf;      // method 2
    public String status;          // "ok", "uneven", "loose", "danger"
    public Double meterKgf;        // tension meter, filled in later (nullable)

    public Reading() { }           // required by Firestore
}
