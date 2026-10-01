package com.byd.extend;

/** Pure speed/pedal/selector to virtual combustion RPM and load mapping. */
public final class AvasEngineModel {
    private static final int PARK = 1;
    private static final int REVERSE = 2;
    private static final int NEUTRAL = 3;
    private static final int DRIVE = 4;
    private static final int MANUAL = 5;
    private static final int SPORT = 6;
    // ponytail: fixed speed/RPM ratios cap realism; calibrate per pack only if measured references justify it.
    // Pedal delays shifts, but never changes the fixed speed/RPM ratio for a gear.
    private static final float[] UPSHIFT_KPH = {32.0f, 58.0f, 88.0f, 122.0f, 160.0f};
    private static final float REVERSE_RPM_PER_KPH = 58.0f;
    private static final float RPM_FRACTION_AT_UPSHIFT = 0.82f;
    private static final float DOWNSHIFT_FRACTION = 0.72f;
    private static final float TOP_GEAR_REFERENCE_MULTIPLIER = 1.25f;
    private static final float PEDAL_SHIFT_HOLD = 0.20f;
    private static final float MANUAL_SHIFT_MULTIPLIER = 1.10f;
    private static final float SPORT_SHIFT_MULTIPLIER = 1.20f;
    private static final double RPM_RISE_TAU_SECONDS = 0.12;
    private static final double RPM_FALL_TAU_SECONDS = 0.28;
    private static final double LOAD_ATTACK_TAU_SECONDS = 0.06;
    private static final double LOAD_RELEASE_TAU_SECONDS = 0.14;
    private static final long SHIFT_INTERVAL_MS = 300;

    public static final class State {
        public float rpm;
        public float load;
        public int gear;
        public boolean valid;
    }

    private final int idleRpm;
    private final int maxRpm;
    private final State state = new State();
    private float currentRpm;
    private float currentLoad;
    private long lastUpdateMs;
    private long lastShiftMs;
    private int previousSelector;
    private int virtualGear;
    private boolean initialized;

    public AvasEngineModel(int idleRpm, int maxRpm) {
        if (idleRpm <= 0 || maxRpm <= idleRpm) throw new IllegalArgumentException("invalid RPM range");
        this.idleRpm = idleRpm;
        this.maxRpm = maxRpm;
        currentRpm = idleRpm;
        state.rpm = idleRpm;
    }

    /** Returns the same mutable state object each call; confine the model to one thread. */
    public State update(long nowMs, float speedKph, boolean speedValid,
            float pedalPercent, boolean pedalValid, float brakePercent, boolean brakeValid,
            int rawSelector, boolean selectorValid) {
        boolean pedalOk = pedalValid && inPercentRange(pedalPercent);
        boolean selectorOk = selectorValid && isSelector(rawSelector);
        boolean parkOrNeutral = selectorOk && (rawSelector == PARK || rawSelector == NEUTRAL);
        boolean speedOk = speedValid && Float.isFinite(speedKph) && speedKph >= 0.0f;
        if (!selectorOk || (!parkOrNeutral && !speedOk)) {
            invalidate(nowMs);
            return state;
        }

        float pedal = pedalOk ? pedalPercent / 100.0f : 0.0f;
        boolean brakeOk = brakeValid && inPercentRange(brakePercent);
        float load = pedalOk && brakeOk ? pedal * (1.0f - brakePercent / 100.0f) : 0.0f;
        float targetRpm;
        int gear;
        if (parkOrNeutral) {
            targetRpm = idleRpm + (maxRpm - idleRpm) * pedal;
            gear = 0;
            virtualGear = 0;
        } else if (rawSelector == REVERSE) {
            targetRpm = idleRpm + speedKph * REVERSE_RPM_PER_KPH;
            gear = 1;
            virtualGear = 1;
        } else {
            if (!initialized || previousSelector != rawSelector || virtualGear < 1) {
                virtualGear = gearAtSpeed(speedKph, pedal, rawSelector);
                lastShiftMs = nowMs;
            } else {
                shiftOneStep(nowMs, speedKph, pedal, rawSelector);
            }
            float referenceKph = referenceSpeed(virtualGear, rawSelector);
            float revsAtShift = (maxRpm - idleRpm) * RPM_FRACTION_AT_UPSHIFT;
            targetRpm = idleRpm + speedKph * revsAtShift / referenceKph;
            gear = virtualGear;
        }
        targetRpm = Math.max(idleRpm, Math.min(maxRpm, targetRpm));
        smooth(nowMs, targetRpm, load);
        previousSelector = rawSelector;
        state.rpm = currentRpm;
        state.load = currentLoad;
        state.gear = gear;
        state.valid = true;
        initialized = true;
        return state;
    }

    private void invalidate(long nowMs) {
        currentRpm = idleRpm;
        currentLoad = 0.0f;
        lastUpdateMs = nowMs;
        virtualGear = 0;
        initialized = false;
        state.rpm = idleRpm;
        state.load = 0.0f;
        state.gear = 0;
        state.valid = false;
    }

    private void smooth(long nowMs, float targetRpm, float targetLoad) {
        if (!initialized) {
            currentRpm = targetRpm;
            currentLoad = targetLoad;
        } else {
            long elapsedMs = Math.max(0L, Math.min(100L, nowMs - lastUpdateMs));
            double seconds = elapsedMs / 1000.0;
            double rpmTau = targetRpm >= currentRpm
                    ? RPM_RISE_TAU_SECONDS : RPM_FALL_TAU_SECONDS;
            double loadTau = targetLoad >= currentLoad
                    ? LOAD_ATTACK_TAU_SECONDS : LOAD_RELEASE_TAU_SECONDS;
            currentRpm += (targetRpm - currentRpm) * (1.0 - Math.exp(-seconds / rpmTau));
            currentLoad += (targetLoad - currentLoad) * (1.0 - Math.exp(-seconds / loadTau));
        }
        lastUpdateMs = nowMs;
    }

    private void shiftOneStep(long nowMs, float speedKph, float pedal, int selector) {
        if (nowMs < lastShiftMs || nowMs - lastShiftMs < SHIFT_INTERVAL_MS) return;
        float threshold = upshiftKph(virtualGear, pedal, selector);
        if (virtualGear < 6 && speedKph >= threshold) {
            virtualGear++;
            lastShiftMs = nowMs;
        } else if (virtualGear > 1
                && speedKph <= upshiftKph(virtualGear - 1, pedal, selector)
                        * DOWNSHIFT_FRACTION) {
            virtualGear--;
            lastShiftMs = nowMs;
        }
    }

    private int gearAtSpeed(float speedKph, float pedal, int selector) {
        int gear = 1;
        while (gear < 6 && speedKph >= upshiftKph(gear, pedal, selector)) gear++;
        return gear;
    }

    private float referenceSpeed(int gear, int selector) {
        return gear < 6 ? upshiftKph(gear, 0.0f, selector)
                : upshiftKph(5, 0.0f, selector) * TOP_GEAR_REFERENCE_MULTIPLIER;
    }

    private static float upshiftKph(int gear, float pedal, int selector) {
        float mode = selector == SPORT ? SPORT_SHIFT_MULTIPLIER
                : selector == MANUAL ? MANUAL_SHIFT_MULTIPLIER : 1.0f;
        return UPSHIFT_KPH[gear - 1] * mode * (1.0f + PEDAL_SHIFT_HOLD * pedal);
    }

    private static boolean isSelector(int rawSelector) {
        return rawSelector >= PARK && rawSelector <= SPORT;
    }

    private static boolean inPercentRange(float value) {
        return Float.isFinite(value) && value >= 0.0f && value <= 100.0f;
    }
}
