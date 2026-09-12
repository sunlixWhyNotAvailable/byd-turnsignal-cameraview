package com.byd.extend

import org.junit.Assert.assertEquals
import org.junit.Test

class UpdateHintAppearanceTest {
    @Test
    fun productionDefaultsAndRangesMatchApprovedAppearance() {
        val defaults = UpdateHintAppearance.defaults()

        assertEquals(0, defaults.transparencyPercent)
        assertEquals(18, defaults.cornerRadiusDp)
        assertEquals(1, defaults.borderWidthDp)
        assertEquals(0xFFF2C34E.toInt(), defaults.borderArgb)
        assertEquals(100, defaults.sizePercent)
        assertEquals(0..100, UpdateHintAppearance.TRANSPARENCY_RANGE)
        assertEquals(0..40, UpdateHintAppearance.CORNER_RANGE)
        assertEquals(0..16, UpdateHintAppearance.BORDER_RANGE)
        assertEquals(50..150, UpdateHintAppearance.SIZE_RANGE)
    }

    @Test
    fun saveReadNormalizesWithoutPreviewColorMigration() {
        val preferences = TestSharedPreferences()
        UpdateHintAppearance(-2, 99, 22, 0x00FF8C8C, 200).save(preferences)

        assertEquals(UpdateHintAppearance(0, 40, 16, 0xFFFF8C8C.toInt(), 150),
            UpdateHintAppearance.read(preferences))
        assertEquals(0xFFFF8C8C.toInt(), preferences.getInt("border_color", 0))
        assertEquals(false, preferences.contains("yellow_default_applied"))
    }
}
