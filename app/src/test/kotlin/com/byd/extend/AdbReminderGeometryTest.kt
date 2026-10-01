package com.byd.extend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbReminderGeometryTest {
    @Test fun centeredDefaultsUseSixtyPercentAndAttachRetryBelow() {
        val result = AdbReminderGeometry.calculate(0, 0, 1_000, 600, 90, 44,
            AdbReminderAppearance())
        assertEquals(600, result.bodyWidth)
        assertEquals(200, result.bodyLeft)
        assertEquals(255, result.bodyTop)
        assertEquals(255, result.windowTop)
        assertEquals(134, result.windowHeight)
        assertFalse(result.retryAbove)
        assertEquals(0, result.bodyTopInWindow)
    }

    @Test fun bottomPlacementAttachesRetryAboveWithoutMovingBody() {
        val appearance = AdbReminderAppearance(x = 1f, y = 1f)
        val result = AdbReminderGeometry.calculate(10, 20, 1_000, 600, 90, 44, appearance)
        assertEquals(410, result.bodyLeft)
        assertEquals(530, result.bodyTop)
        assertEquals(486, result.windowTop)
        assertEquals(result.bodyTop, result.windowTop + result.bodyTopInWindow)
        assertTrue(result.retryAbove)
        assertEquals(44, result.bodyTopInWindow)
    }

    @Test fun geometryNeverExceedsSmallAvailableArea() {
        val result = AdbReminderGeometry.calculate(5, 7, 12, 8, 90, 44,
            AdbReminderAppearance(widthPercent = 95, x = 1f, y = 1f))
        assertTrue(result.bodyWidth in 1..12)
        assertTrue(result.bodyHeight in 1..8)
        assertEquals(0, result.retryHeight)
        assertEquals(result.bodyHeight, result.windowHeight)
    }

    @Test fun wrappedBodyAndRetryRemainInsideInsetsEvenWhenNeitherSideInitiallyFits() {
        for (bodyHeight in listOf(140, 380, 580)) {
            for (position in listOf(0f, .5f, 1f)) {
                val result = AdbReminderGeometry.calculate(12, 24, 960, 600, bodyHeight, 44,
                    AdbReminderAppearance(widthPercent = 30, y = position))
                assertEquals(bodyHeight, result.bodyHeight)
                assertTrue(result.windowTop >= 24)
                assertTrue(result.windowTop + result.windowHeight <= 624)
                assertEquals(result.bodyTop, result.windowTop + result.bodyTopInWindow)
            }
        }
    }
}
