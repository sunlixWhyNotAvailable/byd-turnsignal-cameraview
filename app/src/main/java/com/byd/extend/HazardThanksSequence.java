package com.byd.extend;

/** Counts fresh instrument-sound 1 -> 2 cycles for one owned hazard request. */
final class HazardThanksSequence {
    static final long NO_CYCLE_TIMEOUT_MS = 2_000;
    static final int SOUND_UNCHANGED = 0;
    static final int SOUND_EDGE = 1;
    static final int SOUND_CYCLE = 2;
    static final int SOUND_TARGET_REACHED = 3;
    static final int SOUND_INVALID = -1;

    private boolean active;
    private int target;
    private int completed;
    private Integer lastSound;
    private boolean cycleStarted;
    private boolean hazardsModeSeen;
    private long activatedAtMs;
    private long deadlineAt;
    private long generation;

    /** Returns null when started, or the exact preflight rejection reason. */
    String start(int mode, int stalk, Integer sound, int flashes, long activatedAtMs) {
        if (active) return "active_retrigger_ignored";
        if (flashes < 1 || flashes > 3) return "invalid_flash_count";
        if (mode < 1 || mode > 9) return "mode_invalid";
        if (mode != 1) return "mode_not_idle";
        if (stalk < 1 || stalk > 5) return "stalk_invalid";
        if (stalk != 1) return "stalk_not_neutral";
        if (!knownSound(sound)) return sound == null ? "sound_unavailable" : "sound_invalid";
        if (sound != 3) return "sound_not_idle";

        active = true;
        generation++;
        target = flashes;
        completed = 0;
        lastSound = sound;
        cycleStarted = false;
        hazardsModeSeen = false;
        this.activatedAtMs = activatedAtMs;
        deadlineAt = activatedAtMs + NO_CYCLE_TIMEOUT_MS;
        return null;
    }

    boolean active() { return active; }
    int target() { return target; }
    int completed() { return completed; }
    Integer lastSound() { return lastSound; }
    long deadlineAt() { return deadlineAt; }
    long generation() { return generation; }

    // Millisecond-equal callbacks may have arrived before this sequence was activated.
    boolean observationIsAfterActivation(long observedAtMs) {
        return active && observedAtMs > activatedAtMs;
    }

    /** Manual stalk inputs always take priority over the owned hazard request. */
    String observeStalk(int stalk) {
        if (!active) return null;
        if (stalk < 1 || stalk > 5) return "stalk_invalid";
        return stalk == 1 ? null : "manual_stalk_" + stalk;
    }

    /** Mode 1 before the first observed 6 is the expected SET1 propagation delay. */
    String observeMode(int mode) {
        if (!active) return null;
        if (mode < 1 || mode > 9) return "mode_invalid";
        if (mode == 6) {
            hazardsModeSeen = true;
            return null;
        }
        if (mode == 1 && !hazardsModeSeen) return null;
        return hazardsModeSeen ? "mode_left_hazards_" + mode
                : "unexpected_mode_before_hazards_" + mode;
    }

    /** Returns a SOUND_* event code; duplicates do not create edges or cycles. */
    int observeSound(Integer sound, long now) {
        if (!active) return SOUND_INVALID;
        if (!knownSound(sound)) {
            lastSound = null;
            cycleStarted = false;
            return SOUND_INVALID;
        }
        if (sound.equals(lastSound)) return SOUND_UNCHANGED;

        boolean cycle = lastSound != null && lastSound == 1 && sound == 2 && cycleStarted;
        if (lastSound != null && (lastSound == 2 || lastSound == 3) && sound == 1) {
            cycleStarted = true;
        }
        if (cycle) {
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
        return active && expectedGeneration == generation && now >= deadlineAt;
    }

    static boolean inputOccurredDuring(long requestedAt, long startAt, long endAt) {
        return startAt > 0 && endAt >= startAt
                && requestedAt >= startAt && requestedAt <= endAt;
    }

    /** Invalidates queued work from this sequence before the caller releases its request. */
    void cancel() {
        if (!active) return;
        active = false;
        generation++;
        cycleStarted = false;
    }

    private static boolean knownSound(Integer sound) {
        return sound != null && sound >= 1 && sound <= 3;
    }
}
