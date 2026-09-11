package com.byd.extend;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class RuntimeSettingsReplayTest {
    @Test
    public void storedConfigurationReplaysExactlyWithoutWritingPreferences() {
        TestSharedPreferences settings = new TestSharedPreferences();
        settings.putBoolean("guard_enabled", true)
                .putFloat("outward_deg", -37.5f)
                .putFloat("center_deg", 181.25f)
                .putInt("correction_delay_ms", -400)
                .putInt("max_speed_kph", 173)
                .putBoolean("music_visualizer_enabled", true);
        int transactionsBefore = settings.transactions;
        RecordingSink sink = new RecordingSink();

        CameraHelperService.RuntimeSettingsSnapshot
                .read(settings, true)
                .replay(sink);

        assertEquals(transactionsBefore, settings.transactions);
        assertTrue(settings.getBoolean("guard_enabled", false));
        assertEquals(-37.5f, sink.outward, 0f);
        assertEquals(181.25f, sink.center, 0f);
        assertEquals(-400, sink.delayMs);
        assertEquals(173, sink.maxSpeedKph);
        assertTrue(sink.guardEnabled);
        assertTrue(sink.musicEnabled);
        assertTrue(sink.parkingEnabled);
        assertEquals(3, sink.calls);
    }

    @Test
    public void absentStoredConfigurationUsesExistingDefaultsWithoutSavingThem() {
        TestSharedPreferences settings = new TestSharedPreferences();
        RecordingSink sink = new RecordingSink();

        CameraHelperService.RuntimeSettingsSnapshot
                .read(settings, false)
                .replay(sink);

        assertEquals(0, settings.transactions);
        assertFalse(sink.guardEnabled);
        assertEquals(90f, sink.outward, 0f);
        assertEquals(10f, sink.center, 0f);
        assertEquals(100, sink.delayMs);
        assertEquals(30, sink.maxSpeedKph);
        assertFalse(sink.musicEnabled);
        assertFalse(sink.parkingEnabled);
    }

    @Test
    public void replayDerivesParkingFromRulesInsteadOfCorruptedSummaryFlag() {
        TestSharedPreferences settings = new TestSharedPreferences();
        settings.putBoolean("parking_any_enabled", false);
        ParkingCameraProfile frontLeft =
                ParkingCameraProfile.of(ParkingCameraProfile.FL);
        settings.putBoolean(ParkingCameraSettings.enabledKey(frontLeft), true);
        RecordingSink sink = new RecordingSink();

        CameraHelperService.RuntimeSettingsSnapshot.read(
                settings, CameraHelperService.anyParkingEnabled(settings)).replay(sink);

        assertTrue(sink.parkingEnabled);
        assertFalse(settings.getBoolean("parking_any_enabled", true));
        assertEquals(0, settings.transactions);
    }

    @Test
    public void serviceInitializationAndReloadUseReadOnlyReplay() throws Exception {
        Path source = Path.of("app/src/main/java/com/byd/extend/CameraHelperService.java");
        if (!Files.exists(source)) {
            source = Path.of("src/main/java/com/byd/extend/CameraHelperService.java");
        }
        String text = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
        String start = text.substring(text.indexOf("private void ensureHelperStarted"),
                text.indexOf("private void stopRuntime"));
        String reload = text.substring(text.indexOf("private void reloadSettings"),
                text.indexOf("private void startHeartbeat"));
        assertTrue(start.contains("RuntimeSettingsSnapshot.read"));
        assertTrue(reload.contains("RuntimeSettingsSnapshot.read"));
        assertFalse(start.contains("helper.configureGuard"));
        assertFalse(start.contains("helper.configureMusic"));
        assertFalse(start.contains("helper.configureParkingRadar"));
        assertFalse(reload.contains("helper.configureGuard"));
        assertFalse(reload.contains("helper.configureMusic"));
        assertFalse(reload.contains("helper.configureParkingRadar"));

        Path controllerSource = source.resolveSibling("TurnSignalController.java");
        String controller = new String(
                Files.readAllBytes(controllerSource), StandardCharsets.UTF_8);
        String attach = controller.substring(controller.indexOf("private void attach(Ping ping)"),
                controller.indexOf("private void helperDied"));
        assertTrue(attach.contains("CameraHelperService.anyParkingEnabled(settings)"));
        assertFalse(attach.contains("getBoolean(\"parking_any_enabled\""));
        String parkingTransaction = controller.substring(
                controller.indexOf("private void transactParkingRadarConfig"),
                controller.indexOf("private static void transactAvasConfig"));
        assertTrue(parkingTransaction.contains("data.writeInt(anyEnabled ? 1 : 0)"));
        assertFalse(parkingTransaction.contains("getBoolean("));
    }

    private static final class RecordingSink
            implements CameraHelperService.RuntimeSettingsSink {
        boolean guardEnabled;
        float outward;
        float center;
        int delayMs;
        int maxSpeedKph;
        boolean musicEnabled;
        boolean parkingEnabled;
        int calls;

        @Override
        public void applyGuard(boolean enabled, float outward, float center,
                int delayMs, int maxSpeedKph) {
            guardEnabled = enabled;
            this.outward = outward;
            this.center = center;
            this.delayMs = delayMs;
            this.maxSpeedKph = maxSpeedKph;
            calls++;
        }

        @Override
        public void applyMusic(boolean enabled) {
            musicEnabled = enabled;
            calls++;
        }

        @Override
        public void applyParkingRadar(boolean enabled) {
            parkingEnabled = enabled;
            calls++;
        }
    }
}
