package com.byd.extend;

import java.util.Arrays;

/** Session-only conversion of signed motor values to road-speed-equivalent input. */
final class AvasEngineMotorMapping {
    private static final int WINDOW = 5;
    private static final int MIN_SAMPLES = 3;
    // ponytail: 10 km/h avoids near-stop ratios; lower it only with low-speed calibration evidence.
    private static final float MIN_CALIBRATION_SPEED_KPH = 10.0f;
    // ponytail: 5 s bounds pair skew; tighten only when OEM source cadence is established.
    private static final long MAX_PAIR_AGE_MS = 5_000L;

    static final class Scale {
        private final double[] ratios = new double[WINDOW];
        private int count;
        private int next;
        private long lastSpeedRevision = Long.MIN_VALUE;
        private long lastMotorRevision = Long.MIN_VALUE;

        void observe(float speedKph, boolean speedValid, long speedAt, long speedRevision,
                int motorRaw, boolean motorValid, long motorAt, long motorRevision) {
            if (speedRevision == lastSpeedRevision && motorRevision == lastMotorRevision) return;
            lastSpeedRevision = speedRevision;
            lastMotorRevision = motorRevision;
            if (!speedValid || !motorValid || isKnownSdkError(motorRaw)
                    || !Float.isFinite(speedKph)
                    || speedKph < MIN_CALIBRATION_SPEED_KPH || motorRaw == 0
                    || Math.abs(speedAt - motorAt) > MAX_PAIR_AGE_MS) return;
            double ratio = Math.abs((long) motorRaw) / (double) speedKph;
            if (!Double.isFinite(ratio) || ratio <= 0.0) return;
            ratios[next] = ratio;
            next = (next + 1) % WINDOW;
            count = Math.min(WINDOW, count + 1);
        }

        boolean usable() { return count >= MIN_SAMPLES; }
        int sampleCount() { return count; }

        void reset() {
            Arrays.fill(ratios, 0.0);
            count = 0;
            next = 0;
            lastSpeedRevision = Long.MIN_VALUE;
            lastMotorRevision = Long.MIN_VALUE;
        }

        float rawPerKph() {
            if (!usable()) return Float.NaN;
            double[] sorted = Arrays.copyOf(ratios, count);
            Arrays.sort(sorted);
            int middle = count / 2;
            return (float) (count % 2 == 0
                    ? (sorted[middle - 1] + sorted[middle]) / 2.0
                    : sorted[middle]);
        }

        float speedKph(int motorRaw, boolean motorValid) {
            if (!motorValid || isKnownSdkError(motorRaw) || !usable()) return Float.NaN;
            return (float) (Math.abs((long) motorRaw) / (double) rawPerKph());
        }
    }

    static final class Motion {
        final float speedKph;
        final String source;

        Motion(float speedKph, String source) {
            this.speedKph = speedKph;
            this.source = source;
        }
    }

    static Motion select(float roadSpeedKph, boolean roadSpeedValid,
            int frontRaw, boolean frontValid, Scale frontScale,
            int rearRaw, boolean rearValid, Scale rearScale) {
        float front = bounded(frontScale.speedKph(frontRaw, frontValid));
        float rear = bounded(rearScale.speedKph(rearRaw, rearValid));
        boolean frontUsable = Float.isFinite(front);
        boolean rearUsable = Float.isFinite(rear);
        if (frontUsable && rearUsable) {
            return Math.abs((long) frontRaw) >= Math.abs((long) rearRaw)
                    ? new Motion(front, "motor_front")
                    : new Motion(rear, "motor_rear");
        }
        if (Float.isFinite(front)) return new Motion(front, "motor_front");
        if (Float.isFinite(rear)) return new Motion(rear, "motor_rear");
        return roadSpeedValid && Float.isFinite(roadSpeedKph) && roadSpeedKph >= 0.0f
                ? new Motion(roadSpeedKph, "speed")
                : new Motion(Float.NaN, "unavailable");
    }

    private static float bounded(float speedKph) {
        return Float.isFinite(speedKph) && speedKph >= 0.0f && speedKph <= 300.0f
                ? speedKph : Float.NaN;
    }

    static boolean isKnownSdkError(int raw) {
        return raw >= -2_147_482_648 && raw <= -2_147_482_644;
    }
}
