package com.byd.extend;

import java.util.Arrays;

/** Per-pane elapsed-realtime deadlines, separate from normal speed/angle/BSD eligibility. */
final class BlindCameraHold {
    static final long DURATION_MS = 3_000;
    static final int MIN_DURATION_SECONDS = 1;
    static final int MAX_DURATION_SECONDS = 5;
    private final long[] until = new long[CameraProfile.COUNT];
    private long lastEndAt;
    private int lastBlink = -1;

    void acceptEnd(long endedAt, int direction, int previousBlink, int shownNormalMask, long now) {
        acceptEnd(endedAt, direction, previousBlink, shownNormalMask, now,
                DEFAULT_DURATION_MS, DEFAULT_DURATION_MS);
    }

    void acceptEnd(
            long endedAt, int direction, int previousBlink, int shownNormalMask, long now,
            long rearDurationMs, long frontDurationMs) {
        if (endedAt <= lastEndAt || endedAt > now) return;
        lastEndAt = endedAt;
        if (direction != previousBlink
                || direction != 2 && direction != 4) return;
        for (CameraProfile profile : CameraProfile.values()) {
            // Include a rear sharp-turn companion, but not an unrelated angle-only front view.
            boolean attributable = profile.rear() || profile.right() == (direction == 4);
            long durationMs = profile.rear() ? rearDurationMs : frontDurationMs;
            durationMs = clampDurationMs(durationMs);
            if (attributable && (shownNormalMask & profile.bit()) != 0 && until[profile.id] <= now) {
                // A companion in the opposite turn must not extend an existing hold.
                if (endedAt + durationMs > now) until[profile.id] = endedAt + durationMs;
            }
        }
    }

    static long durationMsForSeconds(int seconds) {
        int safeSeconds = Math.max(MIN_DURATION_SECONDS,
                Math.min(MAX_DURATION_SECONDS, seconds));
        return safeSeconds * 1_000L;
    }

    private static long clampDurationMs(long durationMs) {
        return Math.max(MIN_DURATION_SECONDS * 1_000L,
                Math.min(MAX_DURATION_SECONDS * 1_000L, durationMs));
    }

    private static final long DEFAULT_DURATION_MS = 3_000L;

    int retainedMask(int blink, int normalMask, int allowedHoldMask, long now) {
        boolean newDirection = blink != lastBlink && (blink == 2 || blink == 4);
        lastBlink = blink;
        int retained = 0;
        for (int id = 0; id < until.length; id++) {
            int bit = 1 << id;
            boolean sameCameraTrigger = newDirection && CameraProfile.of(id).right() == (blink == 4);
            if ((allowedHoldMask & bit) == 0 || sameCameraTrigger || until[id] <= now) {
                until[id] = 0;
            } else if ((normalMask & bit) == 0) {
                retained |= bit;
            }
        }
        return retained;
    }

    long nextDeadline() {
        long next = Long.MAX_VALUE;
        for (long deadline : until) if (deadline > 0) next = Math.min(next, deadline);
        return next == Long.MAX_VALUE ? 0 : next;
    }

    void clear() {
        Arrays.fill(until, 0);
        lastBlink = -1;
    }

    void cancel(int cameraId) { until[cameraId] = 0; }
}
