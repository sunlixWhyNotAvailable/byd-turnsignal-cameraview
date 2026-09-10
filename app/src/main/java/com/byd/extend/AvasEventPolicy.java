package com.byd.extend;

import java.util.ArrayList;
import java.util.List;

/** Edges only: initial/unknown snapshots are silent. No playback delay is introduced here. */
final class AvasEventPolicy {
    static final long AUTO_UNLOCK_WINDOW_MS = 1_000;
    private int power = -1;
    private int lock = -1;
    private long offAt = -1;
    private boolean suppressNextUnlock;
    private boolean unlockSuppressed;

    List<String> sample(long now, int rawPower, int rawLock, boolean offReady) {
        List<String> result = new ArrayList<>(2);
        unlockSuppressed = false;
        int nextPower = normalizedPower(rawPower);
        int nextLock = rawLock == 1 || rawLock == 2 ? rawLock : -1;
        if (nextPower < 0 || nextLock < 0) {
            reset();
            return result;
        }
        if (!offReady || nextPower < 0 || offAt > now
                || (offAt >= 0 && now - offAt > AUTO_UNLOCK_WINDOW_MS)) clearOverride();
        // Power first when both values change in one observation.
        if (nextPower >= 0 && power >= 0 && nextPower != power) {
            result.add(nextPower == 0 ? "power_off" : "power_on");
            clearOverride();
            if (nextPower == 0 && offReady) {
                offAt = now;
                suppressNextUnlock = true;
            }
        }
        if (nextLock >= 0 && lock >= 0 && nextLock != lock) {
            if (nextLock == 2) {
                result.add("lock");
                clearOverride();
            } else {
                unlockSuppressed = suppressNextUnlock;
                if (!unlockSuppressed) result.add("unlock");
                clearOverride();
            }
        }
        power = nextPower;
        lock = nextLock;
        return result;
    }

    boolean wasUnlockSuppressed() { return unlockSuppressed; }

    void reset() {
        power = -1;
        lock = -1;
        unlockSuppressed = false;
        clearOverride();
    }

    static int normalizedPower(int raw) { return raw == 0 ? 0 : raw >= 1 && raw <= 4 ? 1 : -1; }

    private void clearOverride() { offAt = -1; suppressNextUnlock = false; }
}
