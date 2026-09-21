package com.byd.extend;

import java.util.ArrayList;
import java.util.List;

/** Edges only: initial/unknown snapshots are silent. No playback delay is introduced here. */
final class AvasEventPolicy {
    static final long CONCURRENT_LOCK_WINDOW_MS = 1_000;
    static final long AUTO_UNLOCK_WINDOW_MS = CONCURRENT_LOCK_WINDOW_MS;
    private int power = -1;
    private int lock = -1;
    private long powerEventAt = -1;
    private String powerProfile = "";
    private String suppressedProfile = "";
    private String suppressionPowerProfile = "";
    private long suppressionDeltaMs = -1;

    List<String> sample(long now, int rawPower, int rawLock, boolean offReady) {
        return sample(now, rawPower, rawLock, false, offReady);
    }

    List<String> sample(long now, int rawPower, int rawLock,
            boolean onReady, boolean offReady) {
        List<String> result = new ArrayList<>(2);
        clearSuppressionResult();
        int nextPower = normalizedPower(rawPower);
        int nextLock = rawLock == 1 || rawLock == 2 ? rawLock : -1;
        if (nextPower < 0 || nextLock < 0) {
            reset();
            return result;
        }
        invalidateIneligible(onReady, offReady);
        if (powerEventAt > now || (powerEventAt >= 0
                && now - powerEventAt > CONCURRENT_LOCK_WINDOW_MS)) clearToken();
        // Power first when both values change in one observation.
        if (nextPower >= 0 && power >= 0 && nextPower != power) {
            String profile = nextPower == 0 ? "power_off" : "power_on";
            result.add(profile);
            clearToken();
            if ((nextPower == 0 && offReady) || (nextPower != 0 && onReady)) {
                powerEventAt = now;
                powerProfile = profile;
            }
        }
        if (nextLock >= 0 && lock >= 0 && nextLock != lock) {
            String profile = nextLock == 2 ? "lock" : "unlock";
            if (powerEventAt >= 0 && now >= powerEventAt
                    && now - powerEventAt <= CONCURRENT_LOCK_WINDOW_MS) {
                suppressedProfile = profile;
                suppressionPowerProfile = powerProfile;
                suppressionDeltaMs = now - powerEventAt;
            } else {
                result.add(profile);
            }
            clearToken();
        }
        power = nextPower;
        lock = nextLock;
        return result;
    }

    boolean wasUnlockSuppressed() { return "unlock".equals(suppressedProfile); }
    boolean wasEventSuppressed() { return !suppressedProfile.isEmpty(); }
    String suppressedProfile() { return suppressedProfile; }
    String suppressionPowerProfile() { return suppressionPowerProfile; }
    long suppressionDeltaMs() { return suppressionDeltaMs; }

    void invalidateIneligible(boolean onReady, boolean offReady) {
        if (("power_on".equals(powerProfile) && !onReady)
                || ("power_off".equals(powerProfile) && !offReady)) clearToken();
    }

    void reset() {
        power = -1;
        lock = -1;
        clearSuppressionResult();
        clearToken();
    }

    static int normalizedPower(int raw) { return raw == 0 ? 0 : raw >= 1 && raw <= 4 ? 1 : -1; }

    private void clearToken() { powerEventAt = -1; powerProfile = ""; }

    private void clearSuppressionResult() {
        suppressedProfile = "";
        suppressionPowerProfile = "";
        suppressionDeltaMs = -1;
    }
}
