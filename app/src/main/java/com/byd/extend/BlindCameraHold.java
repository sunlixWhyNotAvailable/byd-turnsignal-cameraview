package com.byd.extend;

import java.util.Arrays;

/** Per-pane elapsed-realtime deadlines, separate from normal speed/angle/BSD eligibility. */
final class BlindCameraHold {
    static final long DURATION_MS = 3_000;
    private final long[] until = new long[CameraProfile.COUNT];
    private long lastEndAt;
    private int lastBlink = -1;

    void acceptEnd(long endedAt, int direction, int previousBlink, int shownNormalMask, long now) {
        if (endedAt <= lastEndAt || endedAt > now) return;
        lastEndAt = endedAt;
        if (endedAt + DURATION_MS <= now || direction != previousBlink
                || direction != 2 && direction != 4) return;
        for (CameraProfile profile : CameraProfile.values()) {
            // Include a rear sharp-turn companion, but not an unrelated angle-only front view.
            boolean attributable = profile.rear() || profile.right() == (direction == 4);
            if (attributable && (shownNormalMask & profile.bit()) != 0 && until[profile.id] <= now) {
                // A companion in the opposite turn must not extend an existing hold.
                until[profile.id] = endedAt + DURATION_MS;
            }
        }
    }

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
