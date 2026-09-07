package com.byd.extend.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeUiSessionTest {
    @Test
    fun unmeasuredSentinelsAndZeroPlaceholdersDoNotEraseTheRememberedOffset() {
        val session = RuntimeUiSession()
        session.rememberScrollOffset("blind/rear-left", 420, 900)

        session.rememberScrollOffset("blind/rear-left", 0, Int.MAX_VALUE)
        session.rememberScrollOffset("blind/rear-left", 0, 0)

        assertEquals(420, session.scrollOffset("blind/rear-left"))
    }

    @Test
    fun measuredScrollUpdatesAndKeysRemainIndependent() {
        val session = RuntimeUiSession()
        session.rememberScrollOffset("blind/rear-left", 420, 900)
        session.rememberScrollOffset("blind/front-right", 180, 600)

        session.rememberScrollOffset("blind/rear-left", 260, 900)

        assertEquals(260, session.scrollOffset("blind/rear-left"))
        assertEquals(180, session.scrollOffset("blind/front-right"))
    }

    @Test
    fun zeroRangeDoesNotArmRestorationBeforeRuntimeContentGrows() {
        val session = RuntimeUiSession()
        session.rememberScrollOffset("settings/logs", 420, 900)
        assertFalse(RuntimeUiSession.canRestoreScroll(Int.MAX_VALUE))
        assertFalse(RuntimeUiSession.canRestoreScroll(0))
        session.rememberScrollOffset("settings/logs", 0, 0)
        assertEquals(420, session.scrollOffset("settings/logs"))

        assertTrue(RuntimeUiSession.canRestoreScroll(900))
        val restored = session.scrollOffset("settings/logs").coerceAtMost(900)
        session.rememberScrollOffset("settings/logs", restored, 900)
        assertEquals(420, session.scrollOffset("settings/logs"))
    }
}
