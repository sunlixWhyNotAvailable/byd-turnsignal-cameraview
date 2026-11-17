package com.byd.extend;

/** Counts fresh instrument-sound 1 -> 2 cycles for one owned hazard request. */
final class HazardThanksSequence {
    static final long NO_CYCLE_TIMEOUT_MS = 2_000;
    static final int SOUND_UNCHANGED = 0;
    static final int SOUND_EDGE = 1;
    static final int SOUND_CYCLE = 2;
    static final int SOUND_TARGET_REACHED = 3;
    static final int SOUND_INVALID = -1;
    static final int SOUND_AMBIGUOUS = -2;

    private enum Phase { IDLE, WAITING, FLASHING, RETURNING }
    private Phase phase = Phase.IDLE;
    private int originalDirection, mode, stalk;
    private int target;
    private int completed;
    private Integer lastSound;
    private boolean cycleStarted;
    private boolean hazardsModeSeen;
    private long activatedAtMs;
    private long deadlineAt;
    private long generation;
    private final ObservationOrder modeOrder = new ObservationOrder();
    private final ObservationOrder stalkOrder = new ObservationOrder();

    /** Returns null when started, or the exact preflight rejection reason. */
    String start(int mode, int stalk, Integer sound, int flashes, long activatedAtMs) {
        if (active()) return "active_retrigger_ignored";
        if (flashes < 1 || flashes > 3) return "invalid_flash_count";
        if (mode < 1 || mode > 9) return "mode_invalid";
        if (mode != 1 && mode != 2 && mode != 4) return "mode_not_idle_or_direction";
        if (stalk < 1 || stalk > 5) return "stalk_invalid";
        if (stalk != 1 && (mode == 1 || (stalk <= 3 ? 2 : 4) != mode))
            return "stalk_conflicts_with_mode";
        if (!knownSound(sound)) return sound == null ? "sound_unavailable" : "sound_invalid";
        if (mode == 1 && sound != 3) return "sound_not_idle";

        phase = Phase.WAITING;
        this.mode = mode;
        this.stalk = stalk;
        originalDirection = mode == 1 ? 0 : mode;
        generation++;
        target = flashes;
        completed = 0;
        lastSound = sound;
        cycleStarted = false;
        hazardsModeSeen = false;
        this.activatedAtMs = activatedAtMs;
        modeOrder.reset(activatedAtMs);
        stalkOrder.reset(activatedAtMs);
        deadlineAt = activatedAtMs + NO_CYCLE_TIMEOUT_MS;
        return null;
    }

    boolean active() { return phase != Phase.IDLE; }
    boolean waiting() { return phase == Phase.WAITING; }
    boolean returning() { return phase == Phase.RETURNING; }
    String timeoutReason() {
        return waiting() ? "pause_timeout" : returning() ? "return_timeout"
                : "no_complete_cycle_within_timeout";
    }
    int originalDirection() { return originalDirection; }
    boolean readyToActivate() {
        return waiting() && (originalDirection == 0
                ? mode == 1 && Integer.valueOf(3).equals(lastSound)
                : mode == originalDirection && Integer.valueOf(2).equals(lastSound));
    }
    void activate(long now) {
        if (!readyToActivate()) throw new IllegalStateException("No observed pause for hazard start");
        phase = Phase.FLASHING;
        activatedAtMs = now;
        modeOrder.reset(now);
        stalkOrder.reset(now);
        cycleStarted = false;
        deadlineAt = now + NO_CYCLE_TIMEOUT_MS;
    }
    void awaitReturn(long now) {
        if (originalDirection == 0) throw new IllegalStateException("No original direction");
        phase = Phase.RETURNING;
        generation++;
        activatedAtMs = now;
        modeOrder.reset(now);
        stalkOrder.reset(now);
        mode = 6;
        cycleStarted = false;
        deadlineAt = now + NO_CYCLE_TIMEOUT_MS;
    }
    boolean returnObserved() { return returning() && mode != 6; }
    boolean directionReturned() { return returnObserved() && mode == originalDirection; }
    boolean expectedOwnHazards(int observedStalk, int observedMode) {
        return active() && !waiting() && observedMode == 6 && observedStalk == stalk;
    }
    int target() { return target; }
    int completed() { return completed; }
    Integer lastSound() { return lastSound; }
    long deadlineAt() { return deadlineAt; }
    long generation() { return generation; }

    // Millisecond-equal callbacks may have arrived before this sequence was activated.
    boolean observationIsAfterActivation(long observedAtMs) {
        return active() && observedAtMs > activatedAtMs;
    }

    /** Negative read start identifies a callback; overlapping callbacks need the next normal GET. */
    String modeObservationRejection(long readStartedMs, long observedAtMs) {
        if (!observationIsAfterActivation(observedAtMs)) return "before_activation";
        return modeOrder.reject(readStartedMs, observedAtMs);
    }

    String stalkObservationRejection(long readStartedMs, long observedAtMs) {
        if (!observationIsAfterActivation(observedAtMs)) return "before_activation";
        return stalkOrder.reject(readStartedMs, observedAtMs);
    }

    /** Manual stalk inputs always take priority over the owned hazard request. */
    String observeStalk(int stalk) {
        if (!active()) return null;
        if (stalk < 1 || stalk > 5) return "stalk_invalid";
        int previous = this.stalk;
        this.stalk = stalk;
        return stalk == 1 || stalk == previous ? null : "manual_stalk_" + stalk;
    }

    /** Mode 1 before the first observed 6 is the expected SET1 propagation delay. */
    String observeMode(int mode) {
        if (!active()) return null;
        if (mode < 1 || mode > 9) return "mode_invalid";
        this.mode = mode;
        if (returning()) return null;
        if (waiting()) return mode == (originalDirection == 0 ? 1 : originalDirection)
                ? null : "mode_changed_while_waiting_" + mode;
        if (mode == 6) {
            hazardsModeSeen = true;
            return null;
        }
        if (!hazardsModeSeen && (mode == 1 || mode == originalDirection)) return null;
        return hazardsModeSeen ? "mode_left_hazards_" + mode
                : "unexpected_mode_before_hazards_" + mode;
    }

    /** Returns a SOUND_* event code; duplicates do not create edges or cycles. */
    int observeSound(Integer sound, long now) {
        if (!active()) return SOUND_INVALID;
        if (!knownSound(sound)) {
            lastSound = null;
            cycleStarted = false;
            return SOUND_INVALID;
        }
        if (sound.equals(lastSound)) return SOUND_UNCHANGED;
        if (waiting() || returning()) {
            lastSound = sound;
            return SOUND_EDGE;
        }

        boolean cycle = lastSound != null && lastSound == 1 && sound == 2 && cycleStarted;
        if (lastSound != null && (lastSound == 2 || lastSound == 3) && sound == 1) {
            if (!hazardsModeSeen || mode != 6) return SOUND_AMBIGUOUS;
            cycleStarted = true;
        }
        if (cycle) {
            if (mode != 6) return SOUND_AMBIGUOUS;
            completed++;
            cycleStarted = false;
            deadlineAt = now + NO_CYCLE_TIMEOUT_MS;
        }
        if (sound == 3) cycleStarted = false;
        lastSound = sound;
        if (completed >= target) return SOUND_TARGET_REACHED;
        return cycle ? SOUND_CYCLE : SOUND_EDGE;
    }

    boolean timeoutDue(long expectedGeneration, long now) {
        return active() && expectedGeneration == generation && now >= deadlineAt;
    }

    static boolean inputOccurredDuring(long requestedAt, long startAt, long endAt) {
        return startAt > 0 && endAt >= startAt
                && requestedAt >= startAt && requestedAt <= endAt;
    }

    /** Invalidates queued work from this sequence before the caller releases its request. */
    void cancel() {
        if (!active()) return;
        phase = Phase.IDLE;
        generation++;
        cycleStarted = false;
    }

    private static boolean knownSound(Integer sound) {
        return sound != null && sound >= 1 && sound <= 3;
    }

    private static final class ObservationOrder {
        private long lastAt, readStart, readEnd;
        void reset(long now) { lastAt = readStart = readEnd = now; }
        String reject(long started, long observed) {
            if (started >= 0 && started <= lastAt) return "get_overlaps_newer_observation";
            if (started < 0 && observed <= readEnd)
                return observed < readStart ? "before_newer_get" : "overlaps_get";
            if (observed <= lastAt) return "older_or_equal_observation";
            if (started >= 0) { readStart = started; readEnd = observed; }
            lastAt = observed;
            return null;
        }
    }
}
