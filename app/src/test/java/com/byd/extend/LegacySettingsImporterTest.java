package com.byd.extend;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;

import java.util.Collections;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

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
                "am force-stop --user 0 com.byd.turnsignalguard.capture",
                LegacySettingsImporter.legacyForceStopCommand());
        assertTrue(LegacySettingsImporter.legacyHelperCleanupCommand()
                .contains("pidof bydturnguard_helper"));
        assertTrue(LegacySettingsImporter.legacyHelperCleanupCommand()
                .contains("pidof bydturnguard_camera"));
        assertFalse(LegacySettingsImporter.legacyHelperCleanupCommand().contains("killall"));
        assertFalse(LegacySettingsImporter.legacyHelperCleanupCommand().contains("turnsignalguard"));
    }

    @Test
    public void legacyCompatibilityRequiresVersionDebuggableAndMatchingSignature() {
        assertTrue(LegacySettingsImporter.isCompatibleLegacy("0.52.1", 96, true, true));
        assertTrue(LegacySettingsImporter.isCompatibleLegacy("0.52.2", 97, true, true));
        assertFalse(LegacySettingsImporter.isCompatibleLegacy("0.52.0", 96, true, true));
        assertFalse(LegacySettingsImporter.isCompatibleLegacy("0.52.1", 95, true, true));
        assertFalse(LegacySettingsImporter.isCompatibleLegacy("0.52.1", 97, true, true));
        assertFalse(LegacySettingsImporter.isCompatibleLegacy("0.52.2", 96, true, true));
        assertFalse(LegacySettingsImporter.isCompatibleLegacy("0.52.1", 96, false, true));
        assertFalse(LegacySettingsImporter.isCompatibleLegacy("0.52.1", 96, true, false));
    }

    @Test
    public void startupOfferRequiresCompatibleUnhandledIncompleteImport() {
        assertTrue(LegacySettingsImporter.shouldOfferImport(true, false, false));
        assertFalse(LegacySettingsImporter.shouldOfferImport(false, false, false));
        assertFalse(LegacySettingsImporter.shouldOfferImport(true, true, false));
        assertFalse(LegacySettingsImporter.shouldOfferImport(true, false, true));
    }

    @Test
    public void runtimeGateUsesOnlyTheStagedPreferenceMarkerAndNeverReadsPackageState() {
        TestSharedPreferences settings = new TestSharedPreferences();
        Context context = contextWithPreferences(settings);
        assertFalse(LegacySettingsImporter.blocksRuntime(context));
        settings.putBoolean(LegacySettingsImporter.PREF_HANDOVER_COMPLETE, true);
        settings.putBoolean(LegacySettingsImporter.PREF_IMPORT_OFFER_HANDLED, true);
        assertFalse(LegacySettingsImporter.blocksRuntime(context));
        settings.putBoolean(LegacySettingsImporter.PREF_HANDOVER_BLOCKED, true);
        assertTrue(LegacySettingsImporter.blocksRuntime(context));
        settings.putBoolean(LegacySettingsImporter.PREF_HANDOVER_COMPLETE, false);
        settings.putBoolean(LegacySettingsImporter.PREF_IMPORT_OFFER_HANDLED, false);
        assertTrue(LegacySettingsImporter.blocksRuntime(context));
        settings.putBoolean(LegacySettingsImporter.PREF_HANDOVER_BLOCKED, false);
        assertFalse(LegacySettingsImporter.blocksRuntime(context));
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
                        @Override public boolean stopLegacy() { return true; }
                        @Override public boolean enableLegacy() { return true; }
                        @Override public boolean stopHelpers() { return true; }
                        @Override public boolean finalStopLegacy() { return true; }
                        @Override public boolean verifyLegacyStopped() { return true; }
                    }, null);
        });
        assertFalse(settings.getBoolean("guard_enabled", true));
        assertFalse(settings.contains(LegacySettingsImporter.PREF_HANDOVER_BLOCKED));
        LegacySettingsImporter.requirePrimaryUser(true);
    }

    @Test
    public void stopFailureLeavesStagedStateRetryable() {
        TestSharedPreferences settings = new TestSharedPreferences();
        settings.putBoolean("guard_enabled", false);
        LegacySettingsImporter.HandoverOps disableFails = new LegacySettingsImporter.HandoverOps() {
            @Override public boolean pause() { return true; }
            @Override public boolean stopLegacy() { return false; }
            @Override public boolean enableLegacy() { return true; }
            @Override public boolean stopHelpers() { return true; }
            @Override public boolean finalStopLegacy() { return true; }
            @Override public boolean verifyLegacyStopped() { return true; }
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
                    @Override public boolean stopLegacy() { return true; }
                    @Override public boolean enableLegacy() { return true; }
                    @Override public boolean stopHelpers() { return true; }
                    @Override public boolean finalStopLegacy() { return true; }
                    @Override public boolean verifyLegacyStopped() { return true; }
                }, null);
        assertFalse(settings.getBoolean(LegacySettingsImporter.PREF_HANDOVER_BLOCKED, true));
        assertTrue(settings.getBoolean(LegacySettingsImporter.PREF_HANDOVER_COMPLETE, false));
    }

    @Test
    public void helperFailureAndFinalizeFailureRemainBlocked() {
        TestSharedPreferences settings = new TestSharedPreferences();
        LegacySettingsImporter.HandoverOps helperFails = new LegacySettingsImporter.HandoverOps() {
            @Override public boolean pause() { return true; }
            @Override public boolean stopLegacy() { return true; }
            @Override public boolean enableLegacy() { return true; }
            @Override public boolean stopHelpers() { return false; }
            @Override public boolean finalStopLegacy() { return true; }
            @Override public boolean verifyLegacyStopped() { return true; }
        };
        try {
            LegacySettingsImporter.applyAndHandover(settings,
                    Collections.singletonMap("guard_enabled", true), helperFails, null);
        } catch (IllegalStateException expected) {
        }
        assertTrue(settings.getBoolean(LegacySettingsImporter.PREF_HANDOVER_BLOCKED, false));

        LegacySettingsImporter.HandoverOps finalizeFails = new LegacySettingsImporter.HandoverOps() {
            @Override public boolean pause() { return true; }
            @Override public boolean stopLegacy() { return true; }
            @Override public boolean enableLegacy() { return true; }
            @Override public boolean stopHelpers() { return true; }
            @Override public boolean finalStopLegacy() { return true; }
            @Override public boolean verifyLegacyStopped() { return true; }
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

    @Test
    public void shutdownHandoverStopsHelpersBeforeFinalStopAndVerification() {
        TestSharedPreferences settings = new TestSharedPreferences();
        List<String> calls = new ArrayList<>();
        LegacySettingsImporter.HandoverOps ops = new LegacySettingsImporter.HandoverOps() {
            @Override public boolean pause() { calls.add("pause"); return true; }
            @Override public boolean stopLegacy() { calls.add("stop"); return true; }
            @Override public boolean enableLegacy() { calls.add("enable"); return true; }
            @Override public boolean stopHelpers() { calls.add("helpers"); return true; }
            @Override public boolean finalStopLegacy() { calls.add("final_stop"); return true; }
            @Override public boolean verifyLegacyStopped() { calls.add("verify"); return true; }
            @Override public boolean finalizeSettings(android.content.SharedPreferences ignored) {
                calls.add("finalize");
                return LegacySettingsImporter.HandoverOps.super.finalizeSettings(ignored);
            }
        };
        LegacySettingsImporter.applyAndHandover(
                settings, Collections.singletonMap("guard_enabled", true), ops, null);
        assertEquals(
                Arrays.asList("pause", "stop", "helpers", "final_stop", "verify", "finalize"),
                calls);
        assertFalse(settings.getBoolean(LegacySettingsImporter.PREF_HANDOVER_BLOCKED, true));
        assertTrue(settings.getBoolean(LegacySettingsImporter.PREF_HANDOVER_COMPLETE, false));
    }

    @Test
    public void finalStopAndVerificationFailuresRemainStaged() {
        for (int failure = 0; failure < 2; failure++) {
            final int caseFailure = failure;
            TestSharedPreferences settings = new TestSharedPreferences();
            LegacySettingsImporter.HandoverOps ops = new LegacySettingsImporter.HandoverOps() {
                @Override public boolean pause() { return true; }
                @Override public boolean stopLegacy() { return true; }
                @Override public boolean enableLegacy() { return true; }
                @Override public boolean stopHelpers() { return true; }
                @Override public boolean finalStopLegacy() { return caseFailure != 0; }
                @Override public boolean verifyLegacyStopped() { return caseFailure != 1; }
            };
            try {
                LegacySettingsImporter.applyAndHandover(
                        settings, Collections.singletonMap("guard_enabled", true), ops, null);
            } catch (IllegalStateException expected) {
                // The staged settings remain blocked and can be retried.
            }
            assertTrue(settings.getBoolean(LegacySettingsImporter.PREF_HANDOVER_BLOCKED, false));
            assertFalse(settings.getBoolean(LegacySettingsImporter.PREF_HANDOVER_COMPLETE, false));
        }
    }

    @Test
    public void confirmedAccessRepairKeepsUserSettingsAndCompletedMarker() {
        TestSharedPreferences settings = new TestSharedPreferences();
        settings.putBoolean(LegacySettingsImporter.PREF_HANDOVER_COMPLETE, true);
        settings.putBoolean("weather_enabled", true);
        settings.putFloat("camera_left_scale_percent", 73.5f);
        List<String> calls = new ArrayList<>();
        LegacySettingsImporter.HandoverOps ops = new LegacySettingsImporter.HandoverOps() {
            @Override public boolean pause() { calls.add("pause"); return true; }
            @Override public boolean stopLegacy() { calls.add("stop"); return true; }
            @Override public boolean enableLegacy() { calls.add("enable"); return true; }
            @Override public boolean stopHelpers() { calls.add("helpers"); return true; }
            @Override public boolean finalStopLegacy() { calls.add("final_stop"); return true; }
            @Override public boolean verifyLegacyStopped() { calls.add("verify"); return true; }
        };
        assertTrue(LegacySettingsImporter.restoreLegacyAccess(settings, ops, null));
        assertEquals(Arrays.asList("pause", "enable", "helpers", "final_stop", "verify"), calls);
        assertTrue(settings.getBoolean(LegacySettingsImporter.PREF_HANDOVER_COMPLETE, false));
        assertFalse(settings.getBoolean(LegacySettingsImporter.PREF_HANDOVER_BLOCKED, true));
        assertTrue(settings.getBoolean("weather_enabled", false));
        assertEquals(73.5f, settings.getFloat("camera_left_scale_percent", 0f), 0f);
    }

    @Test
    public void failedAccessRepairRetainsBlockAndUserSettingsForRetry() {
        TestSharedPreferences settings = new TestSharedPreferences();
        settings.putBoolean(LegacySettingsImporter.PREF_HANDOVER_COMPLETE, true);
        settings.putBoolean("guard_enabled", true);
        LegacySettingsImporter.HandoverOps ops = new LegacySettingsImporter.HandoverOps() {
            @Override public boolean pause() { return true; }
            @Override public boolean stopLegacy() { return true; }
            @Override public boolean enableLegacy() { return true; }
            @Override public boolean stopHelpers() { return false; }
            @Override public boolean finalStopLegacy() {
                throw new AssertionError("must stop after helper failure");
            }
            @Override public boolean verifyLegacyStopped() {
                throw new AssertionError("must not verify after helper failure");
            }
        };
        assertFalse(LegacySettingsImporter.restoreLegacyAccess(settings, ops, null));
        assertTrue(settings.getBoolean(LegacySettingsImporter.PREF_HANDOVER_BLOCKED, false));
        assertTrue(settings.getBoolean(LegacySettingsImporter.PREF_HANDOVER_COMPLETE, false));
        assertTrue(settings.getBoolean("guard_enabled", false));
    }

    private static Context contextWithPreferences(SharedPreferences settings) {
        return new ContextWrapper(null) {
            @Override
            public SharedPreferences getSharedPreferences(String name, int mode) {
                return settings;
            }

            @Override
            public PackageManager getPackageManager() {
                throw new AssertionError("runtime gate must not inspect legacy package state");
            }
        };
    }
}
