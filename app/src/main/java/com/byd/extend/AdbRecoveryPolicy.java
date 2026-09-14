package com.byd.extend;

/** Side-effect-free decisions kept separate from Android scheduling and settings I/O. */
final class AdbRecoveryPolicy {
    static final long CONSENT_RETRY_MS = 15_000L;
    private AdbRecoveryPolicy() { }

    static boolean shouldWriteWifiOne(boolean manual, int current) {
        return manual || current == 0;
    }

    static boolean shouldScheduleConsentRetry(boolean enabled, boolean wifiConnected,
            boolean authenticated5555, boolean tlsTransitionActive) {
        return enabled && wifiConnected && !authenticated5555 && !tlsTransitionActive;
    }

    static boolean mayCleanupOwnedTls(boolean tlsOwned, boolean freshClassicProof) {
        return tlsOwned && freshClassicProof;
    }

    static boolean isNewBoot(int storedBootCount, int currentBootCount,
            long storedElapsedMs, long currentElapsedMs) {
        if (storedBootCount >= 0 && currentBootCount >= 0) {
            return storedBootCount != currentBootCount;
        }
        return storedElapsedMs >= 0L && currentElapsedMs + 60_000L < storedElapsedMs;
    }
}
