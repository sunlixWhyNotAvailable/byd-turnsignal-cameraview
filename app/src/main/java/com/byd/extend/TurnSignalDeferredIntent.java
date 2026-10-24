package com.byd.extend;

/** A real stalk activation waiting for the speed limit and its matching indicator. */
final class TurnSignalDeferredIntent {
    static final long BLINK_START_TIMEOUT_MS = 2_000;
    private int direction;
    private boolean blinkSeen;
    private long startedAt;

    void start(int direction, boolean blinkSeen, long now) {
        this.direction = direction;
        this.blinkSeen = blinkSeen;
        startedAt = now;
    }

    int direction() { return direction; }
    boolean blinkSeen() { return blinkSeen; }

    boolean expired(long now) {
        return direction != 0 && !blinkSeen && now >= startedAt
                && now - startedAt >= BLINK_START_TIMEOUT_MS;
    }

    String observeBlink(int blink, long now) {
        if (direction == 0) return null;
        if (expired(now)) return "blink_start_timeout";
        if (blink == direction) {
            blinkSeen = true;
            return null;
        }
        if (blinkSeen || blink == 6 || blink == 3 || blink == 5 || blink >= 7) {
            return "blink_changed";
        }
        return null;
    }

    void clear() {
        direction = 0;
        blinkSeen = false;
        startedAt = 0;
    }
}
