package com.byd.extend;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public final class AdbRecoveryPolicyTest {
    @Test public void recoveryNeedsPreviousIdentityAndLocalPermissionWithoutAnRsaRequest() {
        assertEquals(AdbRecoverySnapshot.Stage.WAITING_FOR_AUTHORIZATION,
                AdbRecoveryPolicy.prerequisite(false, false, false));
        assertEquals(AdbRecoverySnapshot.Stage.WAITING_FOR_AUTHORIZATION,
                AdbRecoveryPolicy.prerequisite(false, false, true));
        assertEquals(AdbRecoverySnapshot.Stage.WAITING_FOR_AUTHORIZATION,
                AdbRecoveryPolicy.prerequisite(true, true, true));
        assertEquals(AdbRecoverySnapshot.Stage.WAITING_FOR_PERMISSIONS,
                AdbRecoveryPolicy.prerequisite(false, true, false));
        // Existing authorized users retain this eligibility across a reboot/upgrade.
        assertNull(AdbRecoveryPolicy.prerequisite(false, true, true));
    }

    @Test public void onlyAnUnavailableTransportMayEnterRecovery() {
        assertEquals(AdbRecoveryPolicy.Proof.AVAILABLE, AdbRecoveryPolicy.classifyProof(
                LocalAdbClient.Result.ok("PROOF\n2000", 0, "key", false), "PROOF"));
        assertEquals(AdbRecoveryPolicy.Proof.TRANSPORT_UNAVAILABLE, AdbRecoveryPolicy.classifyProof(
                LocalAdbClient.Result.transportUnavailable("connection refused"), "PROOF"));
        for (String error : new String[]{"authorization_required", "authorization_timeout",
                "authorization_rejected"}) {
            assertEquals(AdbRecoveryPolicy.Proof.AUTHORIZATION_REQUIRED,
                    AdbRecoveryPolicy.classifyProof(
                            LocalAdbClient.Result.authorizationRequired(error, false, "key"), "PROOF"));
        }
        for (LocalAdbClient.Result result : new LocalAdbClient.Result[]{
                LocalAdbClient.Result.ok("wrong output", 0, "key", false),
                LocalAdbClient.Result.failed("protocol_error", "", -1, "key"),
                LocalAdbClient.Result.failed("shell_exit_1", "", 1, "key"),
                LocalAdbClient.Result.commandReadTimeout("timeout", "key"),
                LocalAdbClient.Result.cancelled(), LocalAdbClient.Result.superseded()}) {
            assertEquals(AdbRecoveryPolicy.Proof.FAILED,
                    AdbRecoveryPolicy.classifyProof(result, "PROOF"));
        }
    }

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
