package com.byd.turnsignalguard.capture;

/** Allows one desired-state reconciliation after a newer camera-shell epoch attaches. */
final class CameraShellRecoveryGate {
    private long deathEpoch;
    private long attemptedEpoch;
    private boolean pending;

    boolean isNewDeath(long epoch) {
        return epoch > 0L && epoch > deathEpoch;
    }

    boolean onDeath(long epoch, boolean recoveryWanted) {
        if (!isNewDeath(epoch)) return false;
        deathEpoch = epoch;
        pending = recoveryWanted;
        return pending;
    }

    boolean claim(long attachedEpoch, boolean recoveryStillWanted) {
        if (!pending || attachedEpoch <= deathEpoch || attachedEpoch <= attemptedEpoch) {
            return false;
        }
        pending = false;
        attemptedEpoch = attachedEpoch;
        return recoveryStillWanted;
    }

    boolean pending() {
        return pending;
    }

    void clear() {
        pending = false;
    }
}
