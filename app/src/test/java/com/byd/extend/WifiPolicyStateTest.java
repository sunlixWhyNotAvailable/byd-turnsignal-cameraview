package com.byd.extend;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class WifiPolicyStateTest {
    @Test public void snapshotCodecPreservesAbsenceAndExactPolicyStrings() {
        WifiPolicyState.Snapshot original = new WifiPolicyState.Snapshot(null, "007");

        WifiPolicyState.Snapshot decoded = WifiPolicyState.Snapshot.decode(original.encode());

        assertNull(decoded.sleepPolicy);
        assertEquals("007", decoded.offSwitch);
    }

    @Test public void snapshotCodecRejectsMissingOrUnsafeValues() {
        assertInvalidSnapshot(null);
        assertInvalidSnapshot("{\"sleep\":null}");
        assertInvalidSnapshot("{\"sleep\":\"2\",\"off\":\"1; reboot\"}");
    }

    @Test public void rapidDisableEnableRetainsOriginalUntilRestoreCompletes() {
        WifiPolicyState.Journal initial = WifiPolicyState.Journal.initial();
        WifiPolicyState.Journal apply = initial.toggle(true);
        assertEquals(1L, apply.generation);
        assertEquals(WifiPolicyState.APPLY, apply.operation);
        WifiPolicyState.Journal captured = apply.capture(apply.request(),
                new WifiPolicyState.Snapshot("0", null));
        assertNotNull(captured);

        WifiPolicyState.Journal restore = captured.toggle(false);
        assertEquals(WifiPolicyState.RESTORE, restore.operation);
        WifiPolicyState.Journal reapplied = restore.toggle(true);

        assertEquals(WifiPolicyState.APPLY, reapplied.operation);
        assertEquals(3L, reapplied.generation);
        assertTrue(reapplied.captured);
        assertEquals(captured.snapshot, reapplied.snapshot);
        assertEquals("0", WifiPolicyState.Snapshot.decode(reapplied.snapshot).sleepPolicy);
    }

    @Test public void retryIntervalIsDurableAndElapsedClockResetAllowsRetry() {
        WifiPolicyState.Journal attempted = WifiPolicyState.Journal.initial()
                .toggle(true).attempted(10_000L);

        assertFalse(attempted.mayAttempt(69_999L));
        assertTrue(attempted.mayAttempt(70_000L));
        assertTrue(attempted.mayAttempt(100L));
    }

    @Test public void finishingApplyKeepsOriginalAndFinishingRestoreClearsIt() {
        WifiPolicyState.Journal apply = WifiPolicyState.Journal.initial().toggle(true);
        WifiPolicyState.Journal pendingApply = apply.capture(apply.request(),
                new WifiPolicyState.Snapshot("0", "1"));
        WifiPolicyState.Journal applied = pendingApply.finish(pendingApply.request());
        assertEquals(WifiPolicyState.IDLE, applied.operation);
        assertTrue(applied.captured);
        assertEquals("0", WifiPolicyState.Snapshot.decode(applied.snapshot).sleepPolicy);

        WifiPolicyState.Journal pendingRestore = applied.toggle(false);
        WifiPolicyState.Journal restored = pendingRestore.finish(pendingRestore.request());
        assertEquals(WifiPolicyState.IDLE, restored.operation);
        assertFalse(restored.captured);
        assertNull(restored.snapshot);
    }

    @Test public void supersededAttemptCannotCaptureOrFinishNewIntent() {
        WifiPolicyState.Journal apply = WifiPolicyState.Journal.initial().toggle(true);
        WifiPolicyState.Request obsolete = apply.request();
        WifiPolicyState.Journal next = apply.toggle(false).toggle(true);

        assertFalse(next.isCurrent(obsolete));
        assertNull(next.capture(obsolete, new WifiPolicyState.Snapshot("2", null)));
        assertNull(next.finish(obsolete));
        assertTrue(next.pending());
        assertFalse(next.captured);
    }

    private static void assertInvalidSnapshot(String value) {
        try {
            WifiPolicyState.Snapshot.decode(value);
            fail("Expected invalid Wi-Fi policy snapshot");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("snapshot")
                    || expected.getMessage().contains("policy"));
        }
    }
}
