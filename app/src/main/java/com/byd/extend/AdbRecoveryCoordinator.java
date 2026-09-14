package com.byd.extend;

/** Pure cycle state used by the Android runtime and JVM tests. */
final class AdbRecoveryCoordinator {
    interface Store {
        long cycleId();
        boolean cycleActive();
        boolean hintSuppressed();
        void save(long cycleId, boolean active, boolean hintSuppressed);
    }

    private final Store store;
    private long cycleId;
    private boolean cycleActive;
    private boolean hintSuppressed;
    private boolean enabled;
    private boolean authenticated;
    private boolean wifiConnected;
    private long waitStarted = -1L;
    private AdbRecoverySnapshot.Stage stage = AdbRecoverySnapshot.Stage.DISABLED;

    AdbRecoveryCoordinator(Store store) {
        this.store = store;
        cycleId = Math.max(0L, store.cycleId());
        cycleActive = store.cycleActive();
        hintSuppressed = store.hintSuppressed();
    }

    synchronized void configure(boolean value) {
        enabled = value;
        if (!enabled) {
            authenticated = false;
            waitStarted = -1L;
            stage = AdbRecoverySnapshot.Stage.DISABLED;
        }
    }

    synchronized void begin(boolean forceNewCycle, long elapsedMs) {
        if (!enabled) return;
        boolean newCycle = forceNewCycle || !cycleActive;
        if (newCycle) {
            cycleId++;
            cycleActive = true;
            hintSuppressed = false;
            waitStarted = -1L;
            store.save(cycleId, true, false);
        }
        authenticated = false;
        stage = AdbRecoverySnapshot.Stage.CHECKING_5555;
    }

    synchronized void stage(AdbRecoverySnapshot.Stage value) {
        if (enabled) stage = value;
    }

    synchronized void wifi(boolean connected, long elapsedMs) {
        wifiConnected = connected;
        if (!enabled || authenticated) return;
        if (connected && !hintSuppressed) {
            hintSuppressed = true;
            store.save(cycleId, cycleActive, true);
        }
        if (!connected) {
            if (waitStarted < 0L) waitStarted = elapsedMs;
            stage = AdbRecoverySnapshot.Stage.WAITING_FOR_WIFI;
        }
    }

    synchronized void suppressHint() {
        if (!cycleActive || hintSuppressed) return;
        hintSuppressed = true;
        store.save(cycleId, true, true);
    }

    synchronized void ready() {
        authenticated = true;
        cycleActive = false;
        waitStarted = -1L;
        stage = AdbRecoverySnapshot.Stage.READY;
        store.save(cycleId, false, hintSuppressed);
    }

    synchronized AdbRecoverySnapshot snapshot() {
        return new AdbRecoverySnapshot(stage, enabled, authenticated, wifiConnected,
                waitStarted, cycleId, hintSuppressed);
    }
}
