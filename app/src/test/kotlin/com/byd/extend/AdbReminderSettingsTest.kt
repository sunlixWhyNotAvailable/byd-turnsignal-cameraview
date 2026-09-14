package com.byd.extend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbReminderSettingsTest {
    @Test fun keyContractPreservesPreviewNames() {
        assertEquals("settings", AdbReminderSettings.PREFERENCES_NAME)
        assertEquals("adb_recovery_enabled", AdbReminderSettings.PREF_RECOVERY_ENABLED)
        assertEquals("adb_reminder_enabled", AdbReminderSettings.PREF_REMINDER_ENABLED)
        assertEquals("adb_reminder_delay", AdbReminderSettings.PREF_DELAY_SECONDS)
        assertEquals("adb_reminder_opacity", AdbReminderSettings.PREF_OPACITY_PERCENT)
        assertEquals("adb_reminder_border", AdbReminderSettings.PREF_BORDER_ENABLED)
        assertEquals("adb_reminder_color", AdbReminderSettings.PREF_BORDER_ARGB)
        assertEquals("adb_reminder_thickness", AdbReminderSettings.PREF_BORDER_THICKNESS_DP)
        assertEquals("adb_reminder_size", AdbReminderSettings.PREF_WIDTH_PERCENT)
        assertEquals("adb_reminder_radius", AdbReminderSettings.PREF_CORNER_RADIUS_DP)
        assertEquals("adb_reminder_x", AdbReminderSettings.PREF_X)
        assertEquals("adb_reminder_y", AdbReminderSettings.PREF_Y)
    }

    @Test fun helperReadsDefaultsAndRoundTripsExplicitValues() {
        val preferences = TestSharedPreferences()
        val settings = AdbReminderSettings(preferences)
        assertTrue(settings.recoveryEnabled())
        assertEquals(AdbReminderAppearance(), settings.readAppearance())
        settings.setRecoveryEnabled(false)
        val expected = AdbReminderAppearance(false, 19, 73, false, 0xFF123456.toInt(),
            5, 77, 22, .2f, .8f)
        settings.saveAppearance(expected)
        assertFalse(settings.recoveryEnabled())
        assertEquals(expected, settings.readAppearance())
    }
}
