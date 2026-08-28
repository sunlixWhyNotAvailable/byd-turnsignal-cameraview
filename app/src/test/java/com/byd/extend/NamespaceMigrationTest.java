package com.byd.extend;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class NamespaceMigrationTest {
    @Test
    public void successfulHelperCleanupRunsOnceAndKeepsUserSettings() {
        TestSharedPreferences settings = new TestSharedPreferences();
        settings.putBoolean("weather_enabled", true);
        AtomicInteger runs = new AtomicInteger();
        for (int i = 0; i < 2; i++) {
            assertTrue(TurnSignalController.migrateHelperNamespace(settings, () -> {
                runs.incrementAndGet();
                return LocalAdbClient.Result.ok("", 0, "test", false);
            }));
        }
        assertEquals(1, runs.get());
        assertTrue(settings.getBoolean("weather_enabled", false));
    }

    @Test
    public void failedHelperCleanupIsNotMarkedCompleteAndCanRetry() {
        TestSharedPreferences settings = new TestSharedPreferences();
        assertFalse(TurnSignalController.migrateHelperNamespace(settings,
                () -> LocalAdbClient.Result.failed("cleanup_failed", "", 1, "test")));
        assertFalse(settings.getBoolean(TurnSignalController.KEY_HELPER_NAMESPACE_MIGRATED, false));
        assertTrue(TurnSignalController.migrateHelperNamespace(settings,
                () -> LocalAdbClient.Result.ok("", 0, "test", false)));
    }

    @Test
    public void namespaceCleanupUsesOnlyLegacyHelpersAndPreservesAwakeState() {
        String command = TurnSignalController.helperNamespaceMigrationCommand();
        assertTrue(command.contains("pidof bydturnguard_helper"));
        assertTrue(command.contains("pidof bydturnguard_camera"));
        assertFalse(command.contains("pidof bydextend_"));
        assertFalse(command.contains("pm disable"));
        assertFalse(command.contains("adb_keys"));
        assertTrue(command.contains("if [ ! -e /data/local/tmp/bydextend_awake_session ]"));
        assertTrue(command.contains("cp /data/local/tmp/bydturnguard_awake_session"));
        assertTrue(command.contains(".migrating && mv "));
    }

    @Test
    public void manifestKeepsOneLegacyLauncherAliasForExistingPins() throws Exception {
        Path manifest = Path.of("src/main/AndroidManifest.xml");
        if (!Files.exists(manifest)) manifest = Path.of("app/src/main/AndroidManifest.xml");
        String xml = new String(Files.readAllBytes(manifest),
                java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(xml.contains("android:targetActivity=\"com.byd.extend.CameraProbeActivity\""));
        assertTrue(xml.contains("android:name=\"com.byd.turnsignalguard.capture.CameraProbeActivity\""));
        assertEquals(1, xml.split("android.intent.category.LAUNCHER", -1).length - 1);
        assertTrue(xml.contains("<package android:name=\"com.byd.turnsignalguard.capture\""));
    }
}
