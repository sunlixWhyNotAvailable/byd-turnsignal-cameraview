package com.byd.extend;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AdbRecoveryPolicyTest {
    @Test public void explicitAndTransportWakeReasonsRevalidateReady() {
        assertTrue(AdbRecoveryPolicy.shouldRevalidateReady(AdbRecoveryRuntime.Reason.MANUAL));
        assertTrue(AdbRecoveryPolicy.shouldRevalidateReady(AdbRecoveryRuntime.Reason.BOOT));
        assertTrue(AdbRecoveryPolicy.shouldRevalidateReady(AdbRecoveryRuntime.Reason.WAKE));
        assertTrue(AdbRecoveryPolicy.shouldRevalidateReady(AdbRecoveryRuntime.Reason.NETWORK_CHANGE));
        assertTrue(AdbRecoveryPolicy.shouldRevalidateReady(AdbRecoveryRuntime.Reason.ADB_FAILURE));
        assertFalse(AdbRecoveryPolicy.shouldRevalidateReady(AdbRecoveryRuntime.Reason.START));
        assertFalse(AdbRecoveryPolicy.shouldRevalidateReady(AdbRecoveryRuntime.Reason.RECONFIGURE));
    }

    @Test public void wakeActionsPreserveBootAndWakeReasons() {
        assertEquals(AdbRecoveryRuntime.Reason.BOOT, AdbRecoveryPolicy.wakeReason(
                "receiver:android.intent.action.BOOT_COMPLETED"));
        assertEquals(AdbRecoveryRuntime.Reason.WAKE, AdbRecoveryPolicy.wakeReason(
                "receiver:android.intent.action.QUICKBOOT_POWERON"));
        assertEquals(AdbRecoveryRuntime.Reason.WAKE, AdbRecoveryPolicy.wakeReason(
                "receiver:android.intent.action.USER_PRESENT"));
        assertEquals(AdbRecoveryRuntime.Reason.START, AdbRecoveryPolicy.wakeReason(
                "com.byd.extend.action.SETTINGS_RELOADED"));
        assertEquals(AdbRecoveryRuntime.Reason.START, AdbRecoveryPolicy.wakeReason(null));
        assertEquals(AdbRecoveryRuntime.Reason.START, AdbRecoveryPolicy.wakeReason(""));
    }

    @Test public void coalescingKeepsTheStrongerRecoveryReason() {
        AdbRecoveryRuntime.Reason[] priority = {
                AdbRecoveryRuntime.Reason.RECONFIGURE,
                AdbRecoveryRuntime.Reason.START,
                AdbRecoveryRuntime.Reason.NETWORK_CHANGE,
                AdbRecoveryRuntime.Reason.WAKE,
                AdbRecoveryRuntime.Reason.MANUAL,
                AdbRecoveryRuntime.Reason.ADB_FAILURE,
                AdbRecoveryRuntime.Reason.BOOT
        };
        for (int first = 0; first < priority.length; first++) {
            for (int incoming = 0; incoming < priority.length; incoming++) {
                assertEquals(priority[Math.max(first, incoming)],
                        AdbRecoveryPolicy.higherPriority(priority[first], priority[incoming]));
            }
        }
    }
}
