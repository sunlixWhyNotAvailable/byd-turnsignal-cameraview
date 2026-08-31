package com.byd.extend.ui

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
import org.junit.Test

class DropdownPositionTest {
    @Test
    fun placesBelowThenAboveAndClampsWhenNeitherSideFits() {
        val window = IntSize(800, 600)
        fun position(anchor: IntRect, menu: IntSize, direction: LayoutDirection = LayoutDirection.Ltr) =
            DropdownPosition.calculatePosition(anchor, window, direction, menu)

        assertEquals(IntOffset(100, 140), position(IntRect(100, 100, 320, 140), IntSize(220, 160)))
        assertEquals(IntOffset(100, 340), position(IntRect(100, 500, 320, 540), IntSize(220, 160)))
        assertEquals(IntOffset(100, 100), position(IntRect(100, 250, 320, 290), IntSize(220, 500)))
        assertEquals(IntOffset(0, 0), position(IntRect(100, 250, 320, 290), IntSize(900, 700)))
        assertEquals(IntOffset(580, 140), position(IntRect(700, 100, 790, 140), IntSize(220, 160)))
        assertEquals(IntOffset(70, 140), position(IntRect(100, 100, 290, 140), IntSize(220, 160), LayoutDirection.Rtl))
    }
}
