package com.byd.extend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbReminderAppearanceTest {
    @Test fun defaultsMatchApprovedContract() {
        val value = AdbReminderAppearance()
        assertTrue(value.widgetEnabled)
        assertEquals(5, value.delaySeconds)
        assertEquals(90, value.opacityPercent)
        assertEquals(.9f, value.alpha)
        assertTrue(value.borderEnabled)
        assertEquals(0xFFF2C34E.toInt(), value.borderArgb)
        assertEquals(2, value.borderThicknessDp)
        assertEquals(60, value.widthPercent)
        assertEquals(14, value.cornerRadiusDp)
        assertEquals(.5f, value.x)
        assertEquals(.5f, value.y)
    }

    @Test fun normalizationPreservesValidValuesAndClampsOnlyInvalidBounds() {
        val valid = AdbReminderAppearance(false, 37, 42, false, 0xFF123456.toInt(),
            7, 83, 31, .27f, .81f)
        assertEquals(valid, valid.normalized())
        val clamped = AdbReminderAppearance(false, -2, 101, false, 0x00123456,
            0, 100, 99, Float.NaN, Float.POSITIVE_INFINITY).normalized()
        assertEquals(0, clamped.delaySeconds)
        assertEquals(100, clamped.opacityPercent)
        assertEquals(1, clamped.borderThicknessDp)
        assertEquals(95, clamped.widthPercent)
        assertEquals(48, clamped.cornerRadiusDp)
        assertEquals(.5f, clamped.x)
        assertEquals(.5f, clamped.y)
        assertEquals(0xFF123456.toInt(), clamped.borderArgb)
        assertFalse(clamped.widgetEnabled)
    }
}
