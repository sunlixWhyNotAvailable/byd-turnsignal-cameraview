package com.byd.extend;

import android.content.Context;
import android.content.SharedPreferences;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 33)
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

    @Test public void persistedApplyCanBeReopenedAndDisabledWithOriginalSnapshotRetained() {
        Context app = RuntimeEnvironment.getApplication();
        SharedPreferences preferences = app.getSharedPreferences("settings", Context.MODE_PRIVATE);
        assertTrue(preferences.edit().clear().commit());
        WifiRetentionSettings settings = new WifiRetentionSettings(app);

        settings.setEnabled(true);
        WifiPolicyState.Journal apply = WifiPolicyState.Journal.read(preferences);
        WifiPolicyState.Snapshot original = new WifiPolicyState.Snapshot("0", "1");
        WifiPolicyState.Journal captured = apply.capture(apply.request(), original);
        assertNotNull(captured);
        WifiPolicyState.commit(preferences, captured, apply, "capture original Wi-Fi policies");
        WifiPolicyState.Journal finished = captured.finish(captured.request());
        assertNotNull(finished);
        WifiPolicyState.commit(preferences, finished, captured, "finish Wi-Fi policy apply");

        Context reopenedApp = RuntimeEnvironment.getApplication();
        WifiRetentionSettings reopenedSettings = new WifiRetentionSettings(reopenedApp);
        assertTrue(reopenedSettings.enabled());
        SharedPreferences reopenedPreferences = reopenedApp.getSharedPreferences(
                "settings", Context.MODE_PRIVATE);
        WifiPolicyState.Journal reopened = WifiPolicyState.Journal.read(reopenedPreferences);
        assertEquals(WifiPolicyState.IDLE, reopened.operation);
        assertEquals(1L, reopened.generation);
        assertTrue(reopened.captured);
        assertEquals(original.encode(), reopened.snapshot);
        assertFalse(reopened.pending());
        assertFalse(reopened.mayAttempt(1_000L));
        assertNull(reopened.capture(reopened.request(), original));
        assertNull(reopened.finish(reopened.request()));

        reopenedSettings.setEnabled(false);
        WifiPolicyState.Journal restore = WifiPolicyState.Journal.read(reopenedPreferences);
        assertFalse(reopenedSettings.enabled());
        assertEquals(WifiPolicyState.RESTORE, restore.operation);
        assertEquals(2L, restore.generation);
        assertTrue(restore.captured);
        assertEquals(original.encode(), restore.snapshot);

        WifiPolicyState.Journal restored = restore.finish(restore.request());
        assertNotNull(restored);
        WifiPolicyState.commit(reopenedPreferences, restored, restore,
                "finish Wi-Fi policy restore");
        WifiPolicyState.Journal reopenedAfterRestore =
                WifiPolicyState.Journal.read(reopenedPreferences);
        assertEquals(WifiPolicyState.IDLE, reopenedAfterRestore.operation);
        assertFalse(reopenedAfterRestore.enabled);
        assertFalse(reopenedAfterRestore.captured);
        assertNull(reopenedAfterRestore.snapshot);
    }

    @Test public void persistedPendingApplyWithDisabledPreferenceIsRejected() {
        Context app = RuntimeEnvironment.getApplication();
        SharedPreferences preferences = app.getSharedPreferences("settings", Context.MODE_PRIVATE);
        assertTrue(preferences.edit().clear().commit());
        WifiPolicyState.Journal invalid = new WifiPolicyState.Journal(false, 1L,
                WifiPolicyState.APPLY, false, null, -1L);
        WifiPolicyState.commit(preferences, invalid, WifiPolicyState.Journal.initial(),
                "persist invalid pending Wi-Fi intent");

        try {
            WifiPolicyState.Journal.read(app.getSharedPreferences("settings", Context.MODE_PRIVATE));
            fail("Expected inconsistent pending Wi-Fi intent to be rejected");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("does not match"));
        }
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
