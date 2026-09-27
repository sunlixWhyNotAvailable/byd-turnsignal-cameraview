package com.byd.extend;

/** Read-only classification; it never participates in turn-guard control or latch writes. */
final class BlindShortTurnSignal {
    private static final int UNKNOWN = 0, SHORT = 1, LONG = 2;
    private int stalk = -1;
    private int blink = -1;
    private int mode;
    private int pendingDirection;
    private int pendingMode;
    private long endedAt;
    private int endedDirection;

    void reset() {
        stalk = blink = -1;
        mode = pendingDirection = pendingMode = endedDirection = 0;
        endedAt = 0;
    }

    boolean observe(int nextStalk, int nextBlink, TurnSignalTelemetryController.Source source,
            int liveMask, boolean conflict, long now) {
        if (stalk < 0 || source == TurnSignalTelemetryController.Source.INITIAL
                || source == TurnSignalTelemetryController.Source.RECOVERY_SEED || conflict
                || nextBlink != 1 && nextBlink != 2 && nextBlink != 4) {
            reset();
            stalk = nextStalk;
            blink = nextBlink;
            return false;
        }
        boolean fallback = source == TurnSignalTelemetryController.Source.FALLBACK;
        boolean liveStalk = fallback || source == TurnSignalTelemetryController.Source.CALLBACK
                && (liveMask & TurnSignalTelemetryController.LIVE_STALK) != 0;
        if (liveStalk && nextStalk != stalk && nextStalk >= 2 && nextStalk <= 5) {
            int direction = nextStalk <= 3 ? 2 : 4;
            int nextMode = nextStalk == 3 || nextStalk == 5 ? LONG : SHORT;
            if (direction == blink) {
                // A short cancel/repeated nudge cannot turn a long episode into a short one.
                if (mode == UNKNOWN || nextMode == LONG) mode = nextMode;
            } else {
                pendingDirection = direction;
                pendingMode = nextMode;
            }
        }
        stalk = nextStalk;
        boolean ended = false;
        if (nextBlink != blink) {
            if (mode == SHORT && (blink == 2 || blink == 4)) {
                endedAt = now;
                endedDirection = blink;
                ended = true;
            }
            mode = nextBlink == pendingDirection ? pendingMode : UNKNOWN;
            if (nextBlink == pendingDirection) pendingDirection = pendingMode = 0;
            blink = nextBlink;
        }
        // An unused neutral nudge must not classify an unrelated future activation.
        if (source == TurnSignalTelemetryController.Source.RECONCILE
                && nextStalk == 1 && nextBlink == 1) pendingDirection = pendingMode = 0;
        return ended;
    }

    long endedAt() { return endedAt; }
    int endedDirection() { return endedDirection; }
}
