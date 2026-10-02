package com.byd.extend;

import org.json.JSONObject;

/** One transient OEM steering observation; it never owns or rewrites calibration. */
final class ReverseSteeringSample {
    static final long MAX_AGE_MS = 1_250L;
    static final ReverseSteeringSample UNKNOWN = new ReverseSteeringSample(
            Float.NaN, Float.NaN, Float.NaN, -1);

    final float angleDegrees;
    final float minimumDegrees;
    final float maximumDegrees;
    final long observedMs;

    ReverseSteeringSample(float angleDegrees, float minimumDegrees,
            float maximumDegrees, long observedMs) {
        this.angleDegrees = angleDegrees;
        this.minimumDegrees = minimumDegrees;
        this.maximumDegrees = maximumDegrees;
        this.observedMs = observedMs;
    }

    static ReverseSteeringSample fromEvent(JSONObject event) {
        if (!event.optBoolean("valid", false)) return UNKNOWN;
        return new ReverseSteeringSample(
                (float) event.optDouble("angle_degrees", Double.NaN),
                (float) event.optDouble("minimum_degrees", Double.NaN),
                (float) event.optDouble("maximum_degrees", Double.NaN),
                event.optLong("observed_ms", -1));
    }

    float freshAngle(long nowMs) {
        return observedMs >= 0 && nowMs >= observedMs && nowMs - observedMs <= MAX_AGE_MS
                && ReverseSteeringShift.isValidAngle(angleDegrees, minimumDegrees, maximumDegrees)
                ? angleDegrees : Float.NaN;
    }
}
