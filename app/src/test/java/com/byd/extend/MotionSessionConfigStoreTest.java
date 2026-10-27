package com.byd.extend;

import org.junit.Test;
import java.io.File;
import java.nio.file.Files;
import static org.junit.Assert.*;

public class MotionSessionConfigStoreTest {
    @Test public void oemQuotaDoesNotRotateCamerasAndSurvivesCameraOffAndShutdown() throws Exception {
        File root = Files.createTempDirectory("motion-independent-retention").toFile();
        File retentionFile = new File(root, "oem-retention.json");
        try {
            MotionSessionConfigStore store = new MotionSessionConfigStore(root, 10_123);
            MotionSessionConfigStore.Snapshot first = store.write("{\"enabled\":true}");
            MotionConfig changed = MotionConfig.parse("{\"enabled\":true,\"oemCleanup\":true,\"oemQuota\":12}");
            MotionOemRetentionState.from(changed).write(retentionFile, 10_123);
            MotionSessionConfigStore.Snapshot applied = store.write(changed.toJson());
            assertEquals(first.sessionId, applied.sessionId);
            assertEquals(first.config.toJson(), applied.config.toJson());

            assertNull(store.write("{\"oemCleanup\":true,\"oemQuota\":12}"));
            assertNull(new MotionSessionConfigStore(root, 10_123).read());
            MotionOemRetentionState restored = MotionOemRetentionState.read(retentionFile, 10_123);
            assertTrue(restored.active());
            assertEquals(12, restored.limitGb);
            new MotionOemRetentionState(restored.enabled, restored.primarySd, restored.limitGb, true)
                    .write(retentionFile, 10_123);
            MotionOemRetentionState stopped = MotionOemRetentionState.read(retentionFile, 10_123);
            assertFalse(stopped.active());
            assertTrue(stopped.enabled);
            assertEquals(12, stopped.limitGb);
            assertFalse(new File(root, "oem-retention.json.tmp").exists());

            try {
                MotionOemRetentionState.read(retentionFile, 10_124);
                fail("different app owner must not restore a destructive policy");
            } catch (java.io.IOException expected) {
                assertTrue(expected.getMessage().contains("ownership"));
            }
            Files.write(retentionFile.toPath(), "{broken".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            try {
                MotionOemRetentionState.read(retentionFile, 10_123);
                fail("invalid retention state must not enable cleanup");
            } catch (java.io.IOException expected) {
                assertNotNull(expected.getCause());
            }
        } finally {
            Files.deleteIfExists(new File(root, "config.json.tmp").toPath());
            Files.deleteIfExists(new File(root, "config.json").toPath());
            Files.deleteIfExists(new File(root, "oem-retention.json.tmp").toPath());
            Files.deleteIfExists(retentionFile.toPath());
            Files.deleteIfExists(root.toPath());
        }
    }
    @Test public void modeOnlyChangesKeepSessionAcrossReopenButOtherChangesRotateIt() throws Exception {
        File root = Files.createTempDirectory("motion-mode-store").toFile();
        try {
            MotionSessionConfigStore store = new MotionSessionConfigStore(root, 10_123);
            MotionSessionConfigStore.Snapshot remoteOnly = store.write(
                    "{\"remoteViewingEnabled\":true,\"lock\":2,\"before\":7}");
            assertEquals(MotionConfig.DEFAULT_RECORDING_SENSITIVITY,
                    remoteOnly.config.recordingSensitivity);
            MotionSessionConfigStore.Snapshot enabled = store.write(
                    "{\"remoteViewingEnabled\":true,\"lock\":2,\"before\":7,\"enabled\":true}");
            assertEquals(remoteOnly.sessionId, enabled.sessionId);

            MotionSessionConfigStore afterRestart = new MotionSessionConfigStore(root, 10_123);
            MotionSessionConfigStore.Snapshot reopened = afterRestart.read();
            assertEquals(enabled.sessionId, reopened.sessionId);
            assertEquals(enabled.config.toJson(), reopened.config.toJson());

            MotionSessionConfigStore.Snapshot fullChange = afterRestart.write(
                    "{\"remoteViewingEnabled\":true,\"lock\":2,\"before\":7,\"enabled\":true,\"recordingSensitivity\":2}");
            assertNotEquals(enabled.sessionId, fullChange.sessionId);
            assertEquals(2, fullChange.config.recordingSensitivity);
            MotionSessionConfigStore.Snapshot reopenedSensitivity =
                    new MotionSessionConfigStore(root, 10_123).read();
            assertEquals(fullChange.sessionId, reopenedSensitivity.sessionId);
            assertEquals(2, reopenedSensitivity.config.recordingSensitivity);
        } finally {
            Files.deleteIfExists(new File(root, "config.json.tmp").toPath());
            Files.deleteIfExists(new File(root, "config.json").toPath());
            Files.deleteIfExists(root.toPath());
        }
    }

    @Test public void configSurvivesWorkerRecreationAndChangesSessionAtomically() throws Exception {
        File root = Files.createTempDirectory("motion-config-store").toFile();
        try {
            MotionSessionConfigStore first = new MotionSessionConfigStore(root, 10_123);
            MotionSessionConfigStore.Snapshot saved = first.write(
                    "{\"enabled\":true,\"lock\":2,\"before\":7}");
            assertNotNull(saved);
            assertTrue(MotionDetectorProtocol.validSession(saved.sessionId));
            assertEquals(1, saved.config.lockCondition);

            MotionSessionConfigStore afterRestart = new MotionSessionConfigStore(root, 10_123);
            MotionSessionConfigStore.Snapshot reopened = afterRestart.read();
            assertNotNull(reopened);
            assertEquals(saved.sessionId, reopened.sessionId);
            assertEquals(saved.config.toJson(), reopened.config.toJson());

            MotionSessionConfigStore.Snapshot changed = afterRestart.write(
                    "{\"warning\":true,\"lock\":0,\"after\":19}");
            assertNotEquals(saved.sessionId, changed.sessionId);
            assertEquals(0, changed.config.lockCondition);
            MotionSessionConfigStore.Snapshot persistedChange = first.read();
            assertEquals(changed.sessionId, persistedChange.sessionId);
            assertEquals(19, persistedChange.config.afterSeconds);
        } finally {
            Files.deleteIfExists(new File(root, "config.json.tmp").toPath());
            Files.deleteIfExists(new File(root, "config.json").toPath());
            Files.deleteIfExists(root.toPath());
        }
    }

    @Test public void persistedConfigCannotBeReadUnderDifferentInstalledOwner() throws Exception {
        File root = Files.createTempDirectory("motion-config-owner").toFile();
        try {
            new MotionSessionConfigStore(root, 10_123).write("{\"enabled\":true}");
            try {
                new MotionSessionConfigStore(root, 10_124).read();
                fail("owner mismatch must not reuse a prior app's Motion session");
            } catch (java.io.IOException expected) {
                assertTrue(expected.getMessage().contains("ownership"));
            }
        } finally {
            Files.deleteIfExists(new File(root, "config.json.tmp").toPath());
            Files.deleteIfExists(new File(root, "config.json").toPath());
            Files.deleteIfExists(root.toPath());
        }
    }
}
