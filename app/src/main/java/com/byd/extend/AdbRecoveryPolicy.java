package com.byd.extend;

/** Side-effect-free decisions kept separate from Android scheduling and settings I/O. */
final class AdbRecoveryPolicy {
    static final long CONSENT_RETRY_MS = 15_000L;
    static final int USB_ENABLE_PROOF_ATTEMPTS = 3;
    static final long USB_ENABLE_PROOF_INTERVAL_MS = 1_000L;
    private AdbRecoveryPolicy() { }

    enum Proof { AVAILABLE, AUTHORIZATION_REQUIRED, TRANSPORT_UNAVAILABLE, FAILED }

    static boolean shouldRevalidateReady(AdbRecoveryRuntime.Reason reason) {
        return reason == AdbRecoveryRuntime.Reason.MANUAL
                || reason == AdbRecoveryRuntime.Reason.BOOT
                || reason == AdbRecoveryRuntime.Reason.WAKE
                || reason == AdbRecoveryRuntime.Reason.NETWORK_CHANGE
                || reason == AdbRecoveryRuntime.Reason.ADB_FAILURE;
    }

    static AdbRecoveryRuntime.Reason higherPriority(AdbRecoveryRuntime.Reason first,
            AdbRecoveryRuntime.Reason incoming) {
        return priority(incoming) > priority(first) ? incoming : first;
    }

    private static int priority(AdbRecoveryRuntime.Reason reason) {
        if (reason == AdbRecoveryRuntime.Reason.BOOT) return 6;
        if (reason == AdbRecoveryRuntime.Reason.ADB_FAILURE) return 5;
        if (reason == AdbRecoveryRuntime.Reason.MANUAL) return 4;
        if (reason == AdbRecoveryRuntime.Reason.WAKE) return 3;
        if (reason == AdbRecoveryRuntime.Reason.NETWORK_CHANGE) return 2;
        if (reason == AdbRecoveryRuntime.Reason.START) return 1;
        return 0;
    }

    static AdbRecoveryRuntime.Reason wakeReason(String reason) {
        if (reason == null) return AdbRecoveryRuntime.Reason.START;
        if (reason.contains("BOOT_COMPLETED")) return AdbRecoveryRuntime.Reason.BOOT;
        if (reason.contains("QUICKBOOT_POWERON") || reason.contains("USER_PRESENT")) {
            return AdbRecoveryRuntime.Reason.WAKE;
        }
        return AdbRecoveryRuntime.Reason.START;
    }

    static boolean shouldRetryUsbEnableProof(Proof proof, int completedAttempts) {
        return proof == Proof.TRANSPORT_UNAVAILABLE
                && completedAttempts < USB_ENABLE_PROOF_ATTEMPTS;
    }

    static AdbRecoverySnapshot.Stage prerequisite(boolean authorizationPending,
            boolean authorizedIdentity, boolean hasWss) {
        if (authorizationPending || !authorizedIdentity) {
            return AdbRecoverySnapshot.Stage.WAITING_FOR_AUTHORIZATION;
        }
        return hasWss ? null : AdbRecoverySnapshot.Stage.WAITING_FOR_PERMISSIONS;
    }

    static Proof classifyProof(LocalAdbClient.Result result, String marker) {
        if (result.authorizationRequired) return Proof.AUTHORIZATION_REQUIRED;
        if (result.ok && result.output != null && result.output.contains(marker)) {
            return Proof.AVAILABLE;
        }
        return result.transportUnavailable ? Proof.TRANSPORT_UNAVAILABLE : Proof.FAILED;
    }

    static boolean shouldWriteWifiOne(boolean manual, int current) {
        return manual || current == 0;
    }

    static boolean shouldScheduleConsentRetry(boolean enabled, boolean wifiConnected,
            boolean authenticated5555, boolean tlsTransitionActive) {
        return enabled && wifiConnected && !authenticated5555 && !tlsTransitionActive;
    }

    static boolean mayCleanupOwnedTls(boolean tlsOwned, boolean adbEnabledReadbackOne,
            boolean freshClassicProof) {
        return tlsOwned && adbEnabledReadbackOne && freshClassicProof;
    }

    static boolean isNewBoot(int storedBootCount, int currentBootCount,
            long storedElapsedMs, long currentElapsedMs) {
        if (storedBootCount >= 0 && currentBootCount >= 0) {
            return storedBootCount != currentBootCount;
        }
        return storedElapsedMs >= 0L && currentElapsedMs + 60_000L < storedElapsedMs;
    }
}
