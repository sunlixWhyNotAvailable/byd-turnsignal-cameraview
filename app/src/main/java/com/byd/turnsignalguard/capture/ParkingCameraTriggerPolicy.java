package com.byd.turnsignalguard.capture;

/** Pure parking-trigger decisions.  This class performs no vehicle I/O. */
public final class ParkingCameraTriggerPolicy {
    public static final long DEFAULT_RADAR_STALE_MS = 1_000L;
    public static final long DEFAULT_SPEED_STALE_MS = 500L;
    public static final long CLOSE_DELAY_MS = 500L;

    private ParkingCameraTriggerPolicy() {}

    /** Timestamp policy with the contract's separate radar (1 s) and speed (500 ms) limits. */
    public static int desiredMask(
            ParkingCameraSettings.Rule[] rules, int maxSpeedKph,
            int[] radarRaw, boolean[] radarValid, long[] radarTimestampMs,
            float speedKph, boolean speedValid, long speedTimestampMs,
            long nowMs) {
        if (!hasRules(rules) || !Float.isFinite(speedKph) || !speedValid
                || !isFresh(nowMs, speedTimestampMs, DEFAULT_SPEED_STALE_MS)
                || !isSpeedAllowed(speedValid, speedKph, maxSpeedKph)) return 0;
        int mask = 0;
        for (ParkingCameraProfile profile : ParkingCameraProfile.values()) {
            ParkingCameraSettings.Rule rule = rules[profile.id];
            if (rule == null || !rule.enabled) continue;
            DistanceSample sample = distanceFor(profile, radarRaw, radarValid, radarTimestampMs,
                    nowMs, DEFAULT_RADAR_STALE_MS);
            if (sample.valid && sample.distanceCm <= rule.distanceCm) mask |= profile.bit();
        }
        return applyAdditiveCentral(mask, rules);
    }

    public static boolean isDistanceTriggered(boolean valid, int distanceCm, int thresholdCm) {
        return valid && distanceCm >= ParkingCameraProfile.RADAR_RAW_MIN
                && distanceCm <= ParkingCameraProfile.RADAR_RAW_MAX
                && thresholdCm >= ParkingCameraSettings.MIN_DISTANCE_CM
                && thresholdCm <= ParkingCameraSettings.MAX_DISTANCE_CM
                && distanceCm <= thresholdCm;
    }

    public static boolean isSpeedAllowed(boolean valid, float speedKph, int maxSpeedKph) {
        return valid && Float.isFinite(speedKph)
                && maxSpeedKph >= ParkingCameraSettings.MIN_MAX_SPEED_KPH
                && maxSpeedKph <= ParkingCameraSettings.MAX_MAX_SPEED_KPH
                && speedKph >= 0.0f && speedKph <= maxSpeedKph;
    }

    public static boolean isFresh(long nowMs, long timestampMs, long staleMs) {
        if (staleMs < 0L || timestampMs < 0L || nowMs < timestampMs) return false;
        return nowMs - timestampMs <= staleMs;
    }

    /** Adds each requested corner's central view once, only when native central is inactive. */
    public static int applyAdditiveCentral(
            int nativeMask, ParkingCameraSettings.Rule[] rules) {
        if (!hasRules(rules)) return nativeMask;
        int mask = nativeMask;
        for (ParkingCameraProfile corner : ParkingCameraProfile.values()) {
            if (!corner.corner() || (nativeMask & corner.bit()) == 0) continue;
            ParkingCameraSettings.Rule rule = rules[corner.id];
            if (rule == null || !rule.addCentral) continue;
            int centralId = corner.additiveCentralId();
            if (centralId < 0 || (nativeMask & (1 << centralId)) != 0) continue;
            mask |= 1 << centralId;
        }
        return mask;
    }

    private static boolean hasRules(ParkingCameraSettings.Rule[] rules) {
        return rules != null && rules.length >= ParkingCameraProfile.COUNT;
    }

    private static DistanceSample distanceFor(
            ParkingCameraProfile profile, int[] radarRaw, boolean[] radarValid,
            long[] timestamps, long nowMs, long staleMs) {
        if (radarRaw == null || radarValid == null) return DistanceSample.INVALID;
        int[] fids = profile.radarFids();
        int best = Integer.MAX_VALUE;
        boolean found = false;
        for (int fid : fids) {
            int index = radarIndex(fid);
            if (index < 0 || index >= radarRaw.length || index >= radarValid.length
                    || !radarValid[index] || !ParkingCameraProfile.isValidRadarRaw(radarRaw[index])) {
                continue;
            }
            if (timestamps != null
                    && (index >= timestamps.length || !isFresh(nowMs, timestamps[index], staleMs))) {
                continue;
            }
            found = true;
            best = Math.min(best, radarRaw[index]);
        }
        return found ? new DistanceSample(true, best) : DistanceSample.INVALID;
    }

    private static int radarIndex(int fid) {
        int[] all = ParkingCameraProfile.allRadarFids();
        for (int i = 0; i < all.length; i++) if (all[i] == fid) return i;
        return -1;
    }

    private static final class DistanceSample {
        static final DistanceSample INVALID = new DistanceSample(false, -1);
        final boolean valid;
        final int distanceCm;
        DistanceSample(boolean valid, int distanceCm) {
            this.valid = valid;
            this.distanceCm = distanceCm;
        }
    }

    /** Stateful normal-close debounce. Hard invalid/overspeed states clear immediately. */
    public static final class DelayedCloseState {
        private final long closeDelayMs;
        private boolean active;
        private long closeAtMs = -1L;

        public DelayedCloseState() {
            this(CLOSE_DELAY_MS);
        }

        public DelayedCloseState(long closeDelayMs) {
            if (closeDelayMs < 0L) throw new IllegalArgumentException("negative close delay");
            this.closeDelayMs = closeDelayMs;
        }

        public boolean update(long nowMs, boolean conditionValid, boolean conditionActive,
                              boolean immediateFalse) {
            if (immediateFalse || !conditionValid) {
                active = false;
                closeAtMs = -1L;
                return false;
            }
            if (conditionActive) {
                active = true;
                closeAtMs = -1L;
                return true;
            }
            if (!active) return false;
            if (closeAtMs < 0L) closeAtMs = nowMs + closeDelayMs;
            if (nowMs < closeAtMs) return true;
            active = false;
            closeAtMs = -1L;
            return false;
        }

        public boolean isActive() { return active; }
        public long closeAtMs() { return closeAtMs; }
        public void reset() { active = false; closeAtMs = -1L; }
    }

    /** Six independent debounce states for normal false transitions. */
    public static final class State {
        private final DelayedCloseState[] corners = new DelayedCloseState[ParkingCameraProfile.COUNT];

        public State() {
            for (int i = 0; i < corners.length; i++) corners[i] = new DelayedCloseState();
        }

        public int update(
                ParkingCameraSettings.Rule[] rules,
                int[] radarRaw, boolean[] radarValid, long[] radarTimestampMs,
                float speedKph, boolean speedValid, long speedTimestampMs,
                long nowMs, long staleMs) {
            return update(rules, ParkingCameraSettings.DEFAULT_MAX_SPEED_KPH,
                    radarRaw, radarValid, radarTimestampMs,
                    speedKph, speedValid, speedTimestampMs, nowMs, staleMs, staleMs);
        }

        public int update(
                ParkingCameraSettings.Rule[] rules, int maxSpeedKph,
                int[] radarRaw, boolean[] radarValid, long[] radarTimestampMs,
                float speedKph, boolean speedValid, long speedTimestampMs,
                long nowMs, long staleMs) {
            return update(rules, maxSpeedKph, radarRaw, radarValid, radarTimestampMs,
                    speedKph, speedValid, speedTimestampMs, nowMs, staleMs, staleMs);
        }

        public int update(
                ParkingCameraSettings.Rule[] rules, int maxSpeedKph,
                int[] radarRaw, boolean[] radarValid, long[] radarTimestampMs,
                float speedKph, boolean speedValid, long speedTimestampMs,
                long nowMs, long radarStaleMs, long speedStaleMs) {
            if (!hasRules(rules)) return 0;
            boolean speedFresh = speedValid && Float.isFinite(speedKph)
                    && isFresh(nowMs, speedTimestampMs, speedStaleMs);
            boolean overspeed = !speedFresh || !isSpeedAllowed(speedValid, speedKph, maxSpeedKph);
            int nativeMask = 0;
            for (ParkingCameraProfile profile : ParkingCameraProfile.values()) {
                ParkingCameraSettings.Rule rule = rules[profile.id];
                if (rule == null || !rule.enabled) {
                    corners[profile.id].reset();
                    continue;
                }
                DistanceSample sample = distanceFor(profile, radarRaw, radarValid,
                        radarTimestampMs, nowMs, radarStaleMs);
                boolean active = sample.valid && sample.distanceCm <= rule.distanceCm
                        && !overspeed;
                boolean valid = sample.valid && speedFresh;
                if (corners[profile.id].update(nowMs, valid, active, overspeed)
                        && !overspeed) nativeMask |= profile.bit();
            }
            return applyAdditiveCentral(nativeMask, rules);
        }

        public int update(
                ParkingCameraSettings.Rule[] rules, int maxSpeedKph,
                int[] radarRaw, boolean[] radarValid, long[] radarTimestampMs,
                float speedKph, boolean speedValid, long speedTimestampMs,
                long nowMs) {
            return update(rules, maxSpeedKph, radarRaw, radarValid, radarTimestampMs,
                    speedKph, speedValid, speedTimestampMs, nowMs,
                    DEFAULT_RADAR_STALE_MS, DEFAULT_SPEED_STALE_MS);
        }

        public void reset() {
            for (DelayedCloseState state : corners) state.reset();
        }
    }
}
