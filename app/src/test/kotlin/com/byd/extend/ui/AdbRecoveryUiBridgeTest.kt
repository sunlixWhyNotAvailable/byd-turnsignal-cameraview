package com.byd.extend

import com.byd.extend.ui.AdbRecoveryStage
import com.byd.extend.ui.AdbRecoveryUiBridge
import com.byd.extend.ui.UiLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbRecoveryUiBridgeTest {
    @Test fun nullSnapshotUsesPersistedDefaultsWithoutInventingRuntimeStatus() {
        val state = AdbRecoveryUiBridge.state(null,
            AdbReminderSettings(TestSharedPreferences()))
        assertTrue(state.enabled)
        assertFalse(state.authenticated5555)
        assertEquals(AdbRecoveryStage.PREPARING, state.stage)
        assertEquals(AdbReminderAppearance(), state.appearance)
    }

    @Test fun waitingSnapshotMapsToUiAndOverlayWithoutStartingAnotherTimerModel() {
        val snapshot = AdbRecoverySnapshot(AdbRecoverySnapshot.Stage.WAITING_FOR_WIFI,
            true, false, false, 1234L, 17L, false)
        val settings = AdbReminderSettings(TestSharedPreferences())
        val state = AdbRecoveryUiBridge.state(snapshot, settings)
        assertEquals(AdbRecoveryStage.WAIT_WIFI, state.stage)
        assertEquals(1234L, state.waitStartedElapsedMs)
        assertEquals(17L, state.cycleId)
        val overlay = AdbRecoveryUiBridge.overlay(snapshot, settings,
            UiLanguage.Chinese, darkTheme = false)
        assertTrue(overlay.waitWifi)
        assertFalse(overlay.wifiConnected)
        assertEquals(1234L, overlay.waitStartedElapsedMs)
        assertEquals(UiLanguage.Chinese, overlay.language)
        assertFalse(overlay.darkTheme)
    }

    @Test fun readyAndBlockedSnapshotsDoNotRequestOverlay() {
        listOf(
            AdbRecoverySnapshot.Stage.READY to AdbRecoveryStage.RESTORED,
            AdbRecoverySnapshot.Stage.BLOCKED to AdbRecoveryStage.FAILED,
        ).forEach { (backendStage, uiStage) ->
            val snapshot = AdbRecoverySnapshot(backendStage, true,
                backendStage == AdbRecoverySnapshot.Stage.READY, true, 0L, 8L, true)
            val settings = AdbReminderSettings(TestSharedPreferences())
            assertEquals(uiStage, AdbRecoveryUiBridge.state(snapshot, settings).stage)
            assertFalse(AdbRecoveryUiBridge.overlay(snapshot, settings,
                UiLanguage.English, true).waitWifi)
        }
    }

    @Test fun persistedOffWinsDuringStaleBackendSnapshot() {
        val preferences = TestSharedPreferences().apply {
            edit().putBoolean(AdbReminderSettings.PREF_RECOVERY_ENABLED, false).apply()
        }
        val settings = AdbReminderSettings(preferences)
        val stale = AdbRecoverySnapshot(AdbRecoverySnapshot.Stage.WAITING_FOR_WIFI,
            true, false, false, 500L, 3L, false)
        assertFalse(AdbRecoveryUiBridge.state(stale, settings).enabled)
        assertFalse(AdbRecoveryUiBridge.overlay(stale, settings,
            UiLanguage.English, true).waitWifi)
    }
}
