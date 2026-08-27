package com.byd.turnsignalguard.capture;

import java.util.Collections;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public final class LegacySettingsImporterTest {
    @Test
    public void adbCommandsAreFixedToLegacyPackageAndFile() {
        assertEquals(
                "run-as com.byd.turnsignalguard.capture cat shared_prefs/settings.xml",
                LegacySettingsImporter.legacyReadCommandForTest());
        assertEquals(
                "pm disable-user --user 0 com.byd.turnsignalguard.capture",
                LegacySettingsImporter.legacyDisableCommandForTest());
        assertTrue(TurnSignalController.stopKnownHelpersCommand()
                .contains("pidof " + TurnSignalShellProtocol.PROCESS_NAME));
        assertTrue(TurnSignalController.stopKnownHelpersCommand()
                .contains("pidof " + CameraShellProtocol.PROCESS_NAME));
        assertFalse(TurnSignalController.stopKnownHelpersCommand().contains("killall"));
    }

    @Test
    public void legacyCompatibilityRequiresVersionDebuggableAndMatchingSignature() {
        assertTrue(LegacySettingsImporter.isCompatibleLegacy("0.52.1", 96, true, true));
        assertFalse(LegacySettingsImporter.isCompatibleLegacy("0.52.0", 96, true, true));
        assertFalse(LegacySettingsImporter.isCompatibleLegacy("0.52.1", 95, true, true));
        assertFalse(LegacySettingsImporter.isCompatibleLegacy("0.52.1", 96, false, true));
        assertFalse(LegacySettingsImporter.isCompatibleLegacy("0.52.1", 96, true, false));
    }

    @Test
    public void unsupportedAndroidUserFailsPreflightWithoutStaging() {
        TestSharedPreferences settings = new TestSharedPreferences();
        settings.putBoolean("guard_enabled", false);
        assertThrows(IllegalStateException.class, () -> {
            LegacySettingsImporter.requirePrimaryUser(false);
            LegacySettingsImporter.applyAndHandover(settings,
                    Collections.singletonMap("guard_enabled", true), new LegacySettingsImporter.HandoverOps() {
                        @Override public boolean pause() { return true; }
                        @Override public boolean disable() { return true; }
                        @Override public boolean stopHelpers() { return true; }
                    }, null);
        });
        assertFalse(settings.getBoolean("guard_enabled", true));
        assertFalse(settings.contains(LegacySettingsImporter.PREF_HANDOVER_BLOCKED));
        LegacySettingsImporter.requirePrimaryUser(true);
    }

    @Test
    public void disableAndStopFailuresLeaveStagedStateRetryable() {
        TestSharedPreferences settings = new TestSharedPreferences();
        settings.putBoolean("guard_enabled", false);
        LegacySettingsImporter.HandoverOps disableFails = new LegacySettingsImporter.HandoverOps() {
            @Override public boolean pause() { return true; }
            @Override public boolean disable() { return false; }
            @Override public boolean stopHelpers() { return true; }
        };
        try {
            LegacySettingsImporter.applyAndHandover(settings,
                    Collections.singletonMap("guard_enabled", true), disableFails, null);
        } catch (IllegalStateException expected) {
            // Staged settings intentionally remain blocked for retry.
        }
        assertTrue(settings.getBoolean(LegacySettingsImporter.PREF_HANDOVER_BLOCKED, false));
        assertTrue(settings.getBoolean("guard_enabled", false));

        LegacySettingsImporter.applyAndHandover(settings,
                Collections.singletonMap("guard_enabled", true), new LegacySettingsImporter.HandoverOps() {
                    @Override public boolean pause() { return true; }
                    @Override public boolean disable() { return true; }
                    @Override public boolean stopHelpers() { return true; }
                }, null);
        assertFalse(settings.getBoolean(LegacySettingsImporter.PREF_HANDOVER_BLOCKED, true));
        assertTrue(settings.getBoolean(LegacySettingsImporter.PREF_HANDOVER_COMPLETE, false));
    }

    @Test
    public void helperFailureAndFinalizeFailureRemainBlocked() {
        TestSharedPreferences settings = new TestSharedPreferences();
        LegacySettingsImporter.HandoverOps helperFails = new LegacySettingsImporter.HandoverOps() {
            @Override public boolean pause() { return true; }
            @Override public boolean disable() { return true; }
            @Override public boolean stopHelpers() { return false; }
        };
        try {
            LegacySettingsImporter.applyAndHandover(settings,
                    Collections.singletonMap("guard_enabled", true), helperFails, null);
        } catch (IllegalStateException expected) {
        }
        assertTrue(settings.getBoolean(LegacySettingsImporter.PREF_HANDOVER_BLOCKED, false));

        LegacySettingsImporter.HandoverOps finalizeFails = new LegacySettingsImporter.HandoverOps() {
            @Override public boolean pause() { return true; }
            @Override public boolean disable() { return true; }
            @Override public boolean stopHelpers() { return true; }
            @Override public boolean finalizeSettings(android.content.SharedPreferences ignored) {
                return false;
            }
        };
        try {
            LegacySettingsImporter.applyAndHandover(settings,
                    Collections.singletonMap("guard_enabled", true), finalizeFails, null);
        } catch (IllegalStateException expected) {
        }
        assertTrue(settings.getBoolean(LegacySettingsImporter.PREF_HANDOVER_BLOCKED, false));
    }
}
