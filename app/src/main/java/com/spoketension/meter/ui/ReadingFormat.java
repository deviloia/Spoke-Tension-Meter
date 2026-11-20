package com.spoketension.meter.ui;

import android.content.Context;
import android.graphics.Color;

import com.spoketension.meter.R;
import com.spoketension.meter.calc.SafetyJudge;
import com.spoketension.meter.data.model.Wheel;

/** Display helpers shared by MeasureActivity and ResultActivity. */
final class ReadingFormat {

    private ReadingFormat() { }

    /** Rear wheel: right = gear (drive) side. Front wheel: left = disc side. Stored codes stay L/R. */
    static String sideName(Context c, Wheel w, String side) {
        boolean rear = w != null && "rear".equals(w.position);
        boolean right = "R".equals(side);
        return c.getString(rear ? (right ? R.string.side_right_rear : R.string.side_left_rear)
                : (right ? R.string.side_right_front : R.string.side_left_front));
    }

    /** Icon colour: yellow uneven, orange loose, red danger, green ok. */
    static int color(SafetyJudge.Status status) {
        switch (status) {
            case DANGER: return Color.rgb(229, 57, 53);
            case LOOSE: return Color.rgb(251, 140, 0);
            case UNEVEN: return Color.rgb(251, 192, 45);
            default: return Color.rgb(67, 160, 71);
        }
    }

    /** Why a spoke got its status, for the badge popup. */
    static String reason(Context c, SafetyJudge.Status status, double kgf, double reference, double rimMax) {
        double pct = (kgf / reference - 1) * 100;
        switch (status) {
            case DANGER: return c.getString(R.string.why_danger, kgf, rimMax);
            case LOOSE: return c.getString(R.string.why_loose, kgf, -pct, reference,
                    SafetyJudge.LOOSE_RATIO * 100);
            case UNEVEN: return c.getString(R.string.why_uneven, kgf, pct, reference,
                    SafetyJudge.UNEVEN_RATIO * 100);
            default: return "";
        }
    }
}
