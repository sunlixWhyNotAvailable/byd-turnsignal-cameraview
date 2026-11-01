package com.byd.extend;

/** GET may precede OEM callback delivery. Only the callback can settle an observed edge. */
final class TurnSignalReconciliation {
    private int mask, stalk, blink;
    private long deadline;

    void expect(int changedMask, int nextStalk, int nextBlink, long now, long freshnessDeadline) {
        if (changedMask == 0) return;
        if (mask == 0) deadline = Math.min(now + 1_250, freshnessDeadline);
        else deadline = Math.min(deadline, freshnessDeadline);
        if ((changedMask & 1) != 0) stalk = nextStalk;
        if ((changedMask & 4) != 0) blink = nextBlink;
        mask |= changedMask;
    }

    void callback(int signalMask, int value) {
        if ((signalMask == 1 && value == stalk) || (signalMask == 4 && value == blink))
            mask &= ~signalMask;
    }

    boolean pending() { return mask != 0; }
    boolean expired(long now, boolean fresh) { return pending() && (!fresh || now >= deadline); }
    int mask() { return mask; }
    int stalk() { return stalk; }
    int blink() { return blink; }
    long deadline() { return deadline; }
    void clear() { mask = 0; deadline = 0; }
}
