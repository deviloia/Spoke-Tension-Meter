package com.spoketension.meter.calc;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Arrays;

/** Design-document examples for calc/. */
public class CalcTest {

    @Test
    public void relativeTensionMatchesDesignDocument() {
        assertEquals(-11.1, RelativeTension.percent(330, 350), 0.05);
        assertEquals(350, RelativeTension.sideAverageHz(Arrays.asList(350.0, 350.0)), 1e-9);
    }

    @Test
    public void kgfInSiUnitsMatchesDesignDocument() {
        assertEquals(102, SoundTensionCalculator.kgf(346, 0.290, 0.002, Material.STAINLESS), 0.5);
    }

    @Test
    public void safetyJudge() {
        assertEquals(SafetyJudge.Status.DANGER, SafetyJudge.judge(125, 120, 100));
        assertEquals(SafetyJudge.Status.LOOSE, SafetyJudge.judge(60, 120, 100));
        assertEquals(SafetyJudge.Status.UNEVEN, SafetyJudge.judge(78, 120, 100));
        assertEquals(SafetyJudge.Status.OK, SafetyJudge.judge(95, 120, 100));
        assertEquals(SafetyJudge.Status.OK, SafetyJudge.judge(60, 120, Double.NaN));
    }

    @Test
    public void sallyAndMatrixPencilMustAgree() {
        assertEquals(348, SafetyJudge.combineHz(346, 350), 1e-9);
        assertEquals(Double.NaN, SafetyJudge.combineHz(346, 352), 0);
        assertEquals(Double.NaN, SafetyJudge.combineHz(346, Double.NaN), 0);
    }
}
