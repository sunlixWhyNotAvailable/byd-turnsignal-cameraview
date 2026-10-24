package com.byd.extend;

/** Pure state for correlating stalk gestures with recent blink transitions. */
final class TurnSignalGestureState {
    static final int NEUTRAL = 1;
    static final int LEFT = 2;
    static final int RIGHT = 4;
    private static final int UNKNOWN = -1;
    private static final long DEBOUNCE_MS = 150;
    private static final long CORRELATION_MS = 250;

    enum Event { NONE, NEW_CYCLE, PROMOTION }

    enum Rejection {
        NONE,
        DUPLICATE,
        DOWNGRADE,
        INVALID_STALK,
        INVALID_BLINK,
        AWAITING_NEUTRAL,
        ACTIVE_TURN_CANCELLATION,
        RECENT_CANCELLATION,
        BLINK_DIRECTION_ACTIVE,
        BLINK_ONSET_UNAVAILABLE,
        BLINK_STATE_MISMATCH
    }

    static final class Result {
        final Event event;
        final int direction;
        final int stalk;
        final boolean longMode;
        final boolean activation;
        final Rejection rejection;

        private Result(Event event, int direction, int stalk, boolean longMode,
                boolean activation, Rejection rejection) {
            this.event = event;
            this.direction = direction;
            this.stalk = stalk;
            this.longMode = longMode;
            this.activation = activation;
            this.rejection = rejection;
        }
    }

    private boolean awaitingNeutral = true;
    private int lastStalk = UNKNOWN;
    private boolean cycleOpen;
    private int cycleDirection;
    private long cycleStartedAt;
    private boolean cycleLongSeen;
    private boolean cycleNeutralSeen;
    private boolean cycleActivation;
    private Rejection cycleRejection = Rejection.NONE;
    private boolean recentGestureKnown;
    private int recentGestureDirection;
    private long recentGestureAt;
    private int recentActiveDirection;
    private long recentActiveAt;

    private boolean blinkKnown;
    private int lastBlink = UNKNOWN;
    private int transitionFromDirection;
    private int transitionDirection;
    private long transitionAt;
    private Transition transition = Transition.NONE;
    private boolean onsetUsed;

    private enum Transition { NONE, ONSET, CANCELLATION, DIRECTION_CHANGE }

    Result observeStalk(int raw, long receiptMs, int currentBlink, int activeTurnDirection) {
        if (raw < 1 || raw > 5) {
            return result(Event.NONE, 0, raw, false, false, Rejection.INVALID_STALK);
        }
        if (raw == NEUTRAL) {
            observeNeutral(receiptMs);
            return result(Event.NONE, 0, raw, false, false, Rejection.NONE);
        }
        if (awaitingNeutral) {
            lastStalk = raw;
            return result(Event.NONE, direction(raw), raw, isLong(raw), false,
                    Rejection.AWAITING_NEUTRAL);
        }
        if (raw == lastStalk) {
            return result(Event.NONE, direction(raw), raw, isLong(raw), false,
                    Rejection.DUPLICATE);
        }

        lastStalk = raw;
        int direction = direction(raw);
        boolean longMode = isLong(raw);
        if (cycleOpen && !cycleNeutralSeen && direction == cycleDirection) {
            if (longMode && !cycleLongSeen) {
                cycleLongSeen = true;
                return result(Event.PROMOTION, direction, raw, true,
                        cycleActivation, cycleRejection);
            }
            return result(Event.NONE, direction, raw, longMode, false,
                    longMode ? Rejection.DUPLICATE : Rejection.DOWNGRADE);
        }

        cycleOpen = true;
        cycleDirection = direction;
        cycleStartedAt = receiptMs;
        cycleLongSeen = longMode;
        cycleNeutralSeen = false;
        cycleActivation = false;
        if (isDirection(activeTurnDirection)) {
            rememberActive(activeTurnDirection, receiptMs);
        }
        int knownActiveDirection = isDirection(activeTurnDirection)
                ? activeTurnDirection
                : withinWindow(receiptMs, recentActiveAt) ? recentActiveDirection : 0;
        cycleRejection = classify(direction, receiptMs, currentBlink, knownActiveDirection);
        cycleActivation = cycleRejection == Rejection.NONE;
        if ((transition == Transition.ONSET || transition == Transition.DIRECTION_CHANGE)
                && withinWindow(receiptMs, transitionAt)) {
            onsetUsed = true;
        }
        recentGestureKnown = true;
        recentGestureDirection = direction;
        recentGestureAt = receiptMs;
        return result(Event.NEW_CYCLE, direction, raw, longMode,
                cycleActivation, cycleRejection);
    }

    /** Records an OEM blink callback. Seed/GET values must use observeHealthySnapshot. */
    void observeBlink(int raw, long receiptMs) {
        observeBlink(raw, receiptMs, 0);
    }

    /** Captures the active direction before the runtime handles a blink-driven cancellation. */
    void observeBlink(int raw, long receiptMs, int activeDirection) {
        if (raw < 1 || raw > 9) return;
        boolean hasActiveDirection = isDirection(activeDirection);
        if (!blinkKnown) {
            blinkKnown = true;
            lastBlink = raw;
            clearRecentGesture();
            clearRecentActive();
            if (hasActiveDirection) rememberActive(activeDirection, receiptMs);
            return;
        }
        if (hasActiveDirection) rememberActive(activeDirection, receiptMs);
        if (raw == lastBlink) return;

        int previous = lastBlink;
        lastBlink = raw;
        transition = Transition.NONE;
        transitionFromDirection = 0;
        transitionDirection = 0;
        onsetUsed = false;
        if (previous == NEUTRAL && isDirection(raw)) {
            transition = Transition.ONSET;
            transitionDirection = raw;
            transitionAt = receiptMs;
            if (recentGestureKnown && recentGestureDirection == raw
                    && withinWindow(receiptMs, recentGestureAt)) {
                onsetUsed = true;
            }
        } else if (isDirection(previous) && raw == NEUTRAL) {
            transition = Transition.CANCELLATION;
            transitionDirection = previous;
            transitionAt = receiptMs;
        } else if (isDirection(previous) && isDirection(raw)) {
            transition = Transition.DIRECTION_CHANGE;
            transitionFromDirection = previous;
            transitionDirection = raw;
            transitionAt = receiptMs;
        }
    }

    /** Applies a validated baseline or GET without manufacturing a gesture or blink edge. */
    void observeHealthySnapshot(int stalk, int blink, long receiptMs) {
        if (stalk >= 1 && stalk <= 5) {
            if (stalk == NEUTRAL) {
                lastStalk = NEUTRAL;
                awaitingNeutral = false;
                observeNeutral(receiptMs);
            } else if (awaitingNeutral || lastStalk == UNKNOWN || stalk != lastStalk) {
                clearCycle();
                clearRecentGesture();
                clearRecentActive();
                awaitingNeutral = true;
                lastStalk = stalk;
            }
        }
        if (blink >= 1 && blink <= 9) {
            if (!blinkKnown || blink != lastBlink) {
                transition = Transition.NONE;
                transitionFromDirection = 0;
                transitionDirection = 0;
                onsetUsed = false;
                clearRecentGesture();
                clearRecentActive();
            }
            blinkKnown = true;
            lastBlink = blink;
        }
    }

    /** Requires a new healthy neutral observation and discards pre-recovery blink history. */
    void resetForRecovery() {
        awaitingNeutral = true;
        lastStalk = UNKNOWN;
        clearCycle();
        clearRecentGesture();
        blinkKnown = false;
        lastBlink = UNKNOWN;
        transition = Transition.NONE;
        transitionFromDirection = 0;
        transitionDirection = 0;
        transitionAt = 0;
        onsetUsed = false;
        clearRecentActive();
    }

    // An expired intent is no longer evidence that a later same-side press cancels a turn.
    void forgetExpiredIntent() { clearRecentActive(); }

    /** Completes a remembered early neutral release on the runtime's existing periodic tick. */
    void tick(long nowMs) {
        if (cycleOpen && cycleNeutralSeen && elapsedAtLeast(nowMs, cycleStartedAt, DEBOUNCE_MS)) {
            clearCycle();
        }
    }

    private Rejection classify(
            int direction, long receiptMs, int currentBlink, int activeTurnDirection) {
        if (activeTurnDirection == direction) return Rejection.ACTIVE_TURN_CANCELLATION;
        if (!blinkKnown || currentBlink < 1 || currentBlink > 9) {
            return Rejection.INVALID_BLINK;
        }
        if (lastBlink != currentBlink) return Rejection.BLINK_STATE_MISMATCH;
        if (currentBlink != NEUTRAL && currentBlink != LEFT && currentBlink != RIGHT) {
            return Rejection.INVALID_BLINK;
        }
        if (currentBlink == direction) {
            if (transition == Transition.ONSET && transitionDirection == direction
                    && !onsetUsed && withinWindow(receiptMs, transitionAt)) {
                onsetUsed = true;
                return Rejection.NONE;
            }
            if (transition == Transition.DIRECTION_CHANGE
                    && transitionFromDirection == activeTurnDirection
                    && transitionDirection == direction && activeTurnDirection != 0
                    && !onsetUsed && withinWindow(receiptMs, transitionAt)) {
                onsetUsed = true;
                return Rejection.NONE;
            }
            return Rejection.BLINK_ONSET_UNAVAILABLE;
        }
        if (activeTurnDirection != 0 && currentBlink == activeTurnDirection) {
            return Rejection.NONE;
        }
        if (currentBlink == NEUTRAL) {
            if (transition == Transition.CANCELLATION
                    && transitionDirection == direction
                    && withinWindow(receiptMs, transitionAt)) {
                return Rejection.RECENT_CANCELLATION;
            }
            return Rejection.NONE;
        }
        return Rejection.BLINK_DIRECTION_ACTIVE;
    }

    private void observeNeutral(long receiptMs) {
        awaitingNeutral = false;
        lastStalk = NEUTRAL;
        if (cycleOpen) {
            cycleNeutralSeen = true;
            if (elapsedAtLeast(receiptMs, cycleStartedAt, DEBOUNCE_MS)) clearCycle();
        }
    }

    private void clearCycle() {
        cycleOpen = false;
        cycleDirection = 0;
        cycleStartedAt = 0;
        cycleLongSeen = false;
        cycleNeutralSeen = false;
        cycleActivation = false;
        cycleRejection = Rejection.NONE;
    }

    private void clearRecentGesture() {
        recentGestureKnown = false;
        recentGestureDirection = 0;
        recentGestureAt = 0;
    }

    private void clearRecentActive() {
        recentActiveDirection = 0;
        recentActiveAt = 0;
    }

    private void rememberActive(int direction, long receiptMs) {
        recentActiveDirection = direction;
        recentActiveAt = receiptMs;
    }

    private static Result result(Event event, int direction, int stalk, boolean longMode,
            boolean activation, Rejection rejection) {
        return new Result(event, direction, stalk, longMode, activation, rejection);
    }

    private static int direction(int stalk) {
        return stalk == 2 || stalk == 3 ? LEFT : RIGHT;
    }

    private static boolean isLong(int stalk) { return stalk == 3 || stalk == 5; }

    private static boolean isDirection(int blink) { return blink == LEFT || blink == RIGHT; }

    private static boolean withinWindow(long first, long second) {
        if (first < 0 || second < 0) return false;
        long delta = first >= second ? first - second : second - first;
        return delta <= CORRELATION_MS;
    }

    private static boolean elapsedAtLeast(long now, long then, long duration) {
        return now >= then && now - then >= duration;
    }

}
