package com.byd.extend.ui

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class DiagnosticProgressUiTest {
    @Test fun realByteProgressSupportsLargeVolumesAndNeverFinishesBeforeClose() {
        val dialog = DialogUiState(DialogKind.Progress, "Logs", "Preparing")
        val halfway = dialog.withArchiveProgress("Logs", 10_000_000_000, 20_000_000_000, false, true, "Cancel")
        assertEquals(.5f, halfway.progress!!, .0001f)
        assertEquals(10_000_000_000L, halfway.archiveProcessedBytes)
        assertEquals(20_000_000_000L, halfway.archiveTotalBytes)
        assertEquals(.99f, dialog.withArchiveProgress("Logs", 100, 100, false, true, "Cancel").progress!!, .0001f)
        assertNull(dialog.withArchiveProgress("Closing", 100, 100, true, false, null).progress)
        assertNull(dialog.withArchiveProgress("Measuring", 42, -1, false, true, "Cancel").archiveTotalBytes)
        assertNull(dialog.withArchiveProgress("Measuring", 42, -1, false, true, "Cancel").progress)
    }

    @Test fun defaultOffRowUsesSharedSwitchAndPersistsWithoutCameraReload() {
        assertFalse(SettingsUiState().recordLogcat)
        val ui = File("src/main/kotlin/com/byd/extend/ui/SettingsScreen.kt").readText()
        assertTrue(ui.contains("Розмір файлу швидко збільшується, використовуйте з обережністю та очищайте логи після відправки."))
        assertTrue(ui.contains("ToggleTarget.Simple(ToggleId.RecordLogcat)"))
        assertTrue(ui.contains("colors, compactSwitch = false"))
        val activity = File("src/main/java/com/byd/extend/CameraProbeActivity.java").readText()
        val handler = activity.substringAfter("id == ToggleId.RecordLogcat").substringBefore("id == ToggleId.ReverseEnabled")
        assertTrue(handler.contains("putBoolean(ContinuousLogcatRecorder.PREF_ENABLED, value).apply()"))
        assertTrue(handler.contains("diagnosticSettingsChanged(this)"))
        assertFalse(handler.contains("settingsReloaded"))
    }
}
