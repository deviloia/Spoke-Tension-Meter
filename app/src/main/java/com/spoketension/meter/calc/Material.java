package com.spoketension.meter.calc;

/** Spoke material constants: density ρ (kg/m³) and Young's modulus E (Pa). */
public enum Material {
    STAINLESS(7900, 200e9),
    STEEL(7850, 210e9),
    TITANIUM(4430, 114e9);

    public final double densityKgM3;
    public final double youngPa;

    Material(double densityKgM3, double youngPa) {
        this.densityKgM3 = densityKgM3;
        this.youngPa = youngPa;
    }
}
