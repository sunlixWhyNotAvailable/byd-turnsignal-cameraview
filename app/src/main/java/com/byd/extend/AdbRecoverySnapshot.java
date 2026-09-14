package com.byd.extend;

import java.util.Objects;

/** Immutable app-side recovery state; safe to map into UI without Android dependencies. */
public final class AdbRecoverySnapshot {
    public enum ReadyOutcome { NONE, AVAILABLE, RESTORED }

    public enum Stage {
        DISABLED,
        CHECKING_5555,
        PREPARING,
        WAITING_FOR_WIFI,
        REQUESTING_TLS,
        DISCOVERING_TLS,
        SWITCHING_TO_5555,
        VERIFYING_5555,
        CLEANING_UP,
        READY,
        BLOCKED
    }

    private final Stage stage;
    private final boolean enabled;
    private final boolean authenticated5555;
    private final boolean wifiConnected;
    private final long waitStartedElapsedMs;
    private final long cycleId;
    private final boolean hintSuppressedForCycle;
    private final ReadyOutcome readyOutcome;

    AdbRecoverySnapshot(Stage stage, boolean enabled, boolean authenticated5555,
            boolean wifiConnected, long waitStartedElapsedMs, long cycleId,
            boolean hintSuppressedForCycle, ReadyOutcome readyOutcome) {
        this.stage = Objects.requireNonNull(stage);
        this.enabled = enabled;
        this.authenticated5555 = authenticated5555;
        this.wifiConnected = wifiConnected;
        this.waitStartedElapsedMs = waitStartedElapsedMs;
        this.cycleId = cycleId;
        this.hintSuppressedForCycle = hintSuppressedForCycle;
        this.readyOutcome = Objects.requireNonNull(readyOutcome);
    }

    public Stage stage() { return stage; }
    public boolean enabled() { return enabled; }
    public boolean authenticated5555() { return authenticated5555; }
    public boolean wifiConnected() { return wifiConnected; }
    public long waitStartedElapsedMs() { return waitStartedElapsedMs; }
    public long cycleId() { return cycleId; }
    public boolean hintSuppressedForCycle() { return hintSuppressedForCycle; }
    public ReadyOutcome readyOutcome() { return readyOutcome; }

    @Override public boolean equals(Object value) {
        if (!(value instanceof AdbRecoverySnapshot)) return false;
        AdbRecoverySnapshot other = (AdbRecoverySnapshot) value;
        return stage == other.stage && enabled == other.enabled
                && authenticated5555 == other.authenticated5555
                && wifiConnected == other.wifiConnected
                && waitStartedElapsedMs == other.waitStartedElapsedMs
                && cycleId == other.cycleId
                && hintSuppressedForCycle == other.hintSuppressedForCycle
                && readyOutcome == other.readyOutcome;
    }

    @Override public int hashCode() {
        return Objects.hash(stage, enabled, authenticated5555, wifiConnected,
                waitStartedElapsedMs, cycleId, hintSuppressedForCycle, readyOutcome);
    }
}
