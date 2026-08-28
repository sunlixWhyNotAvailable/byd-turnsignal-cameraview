package com.byd.extend;

/** Pure parking-trigger decisions.  This class performs no vehicle I/O. */
public final class ParkingCameraTriggerPolicy {
    public static final long DEFAULT_SPEED_STALE_MS = 500L;
    public static final long CLOSE_DELAY_MS = 500L;

    private ParkingCameraTriggerPolicy() {}

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
            ParkingCameraProfile profile, int[] radarRaw, boolean[] radarValid) {
        if (radarRaw == null || radarValid == null) return DistanceSample.INVALID;
        int[] fids = profile.radarFids();
        int best = Integer.MAX_VALUE;
        boolean found = false;
        for (int fid : fids) {
            int index = radarIndex(fid);
            if (index < 0 || index >= radarRaw.length || index >= radarValid.length
                    || !radarValid[index] || !ParkingCameraProfile.isValidRadarRaw(fid, radarRaw[index])) {
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

    /** Eight independent debounce states for normal false transitions. */
    public static final class State {
        private final DelayedCloseState[] corners = new DelayedCloseState[ParkingCameraProfile.COUNT];

        public State() {
            for (int i = 0; i < corners.length; i++) corners[i] = new DelayedCloseState();
        }

        public int update(
                ParkingCameraSettings.Rule[] rules, int maxSpeedKph,
                int[] radarRaw, boolean[] radarValid,
                float speedKph, boolean speedValid, long speedTimestampMs,
                long nowMs) {
            if (!hasRules(rules)) return 0;
            boolean speedFresh = speedValid && Float.isFinite(speedKph)
                    && isFresh(nowMs, speedTimestampMs, DEFAULT_SPEED_STALE_MS);
            boolean overspeed = !speedFresh || !isSpeedAllowed(speedValid, speedKph, maxSpeedKph);
            int nativeMask = 0;
            for (ParkingCameraProfile profile : ParkingCameraProfile.values()) {
                ParkingCameraSettings.Rule rule = rules[profile.id];
                if (rule == null || !rule.enabled) {
                    corners[profile.id].reset();
                    continue;
                }
                DistanceSample sample = distanceFor(profile, radarRaw, radarValid);
                boolean active = sample.valid && sample.distanceCm <= rule.distanceCm
                        && !overspeed;
                boolean valid = sample.valid && speedFresh;
                if (corners[profile.id].update(nowMs, valid, active, overspeed)
                        && !overspeed) nativeMask |= profile.bit();
            }
            return applyAdditiveCentral(nativeMask, rules);
        }

        public void reset() {
            for (DelayedCloseState state : corners) state.reset();
        }
    }
}
