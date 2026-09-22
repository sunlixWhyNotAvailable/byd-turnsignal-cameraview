package com.byd.extend;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class AdbRecoveryPolicyTest {
    @Test public void automaticConsentWritesOnlyWhenZero() {
        assertTrue(AdbRecoveryPolicy.shouldWriteWifiOne(false, 0));
        assertFalse(AdbRecoveryPolicy.shouldWriteWifiOne(false, 1));
    }

    @Test public void manualConsentAlwaysRequestsOneWithoutForcingZero() {
        assertTrue(AdbRecoveryPolicy.shouldWriteWifiOne(true, 0));
        assertTrue(AdbRecoveryPolicy.shouldWriteWifiOne(true, 1));
    }

    @Test public void recurringRetryRunsOnlyDuringConnectedUnresolvedConsent() {
        assertTrue(AdbRecoveryPolicy.shouldScheduleConsentRetry(true, true, false, false));
        assertFalse(AdbRecoveryPolicy.shouldScheduleConsentRetry(false, true, false, false));
        assertFalse(AdbRecoveryPolicy.shouldScheduleConsentRetry(true, false, false, false));
        assertFalse(AdbRecoveryPolicy.shouldScheduleConsentRetry(true, true, true, false));
        assertFalse(AdbRecoveryPolicy.shouldScheduleConsentRetry(true, true, false, true));
    }

    @Test public void cleanupRequiresBothOwnershipAndFreshClassicProof() {
        assertTrue(AdbRecoveryPolicy.mayCleanupOwnedTls(true, true));
        assertFalse(AdbRecoveryPolicy.mayCleanupOwnedTls(true, false));
        assertFalse(AdbRecoveryPolicy.mayCleanupOwnedTls(false, true));
    }

    @Test public void bootCountOrElapsedResetDetectsNewBoot() {
        assertTrue(AdbRecoveryPolicy.isNewBoot(10, 11, 500_000L, 100_000L));
        assertFalse(AdbRecoveryPolicy.isNewBoot(10, 10, 500_000L, 100_000L));
        assertTrue(AdbRecoveryPolicy.isNewBoot(-1, -1, 500_000L, 100_000L));
        assertFalse(AdbRecoveryPolicy.isNewBoot(-1, -1, 100_000L, 500_000L));
    }
}
