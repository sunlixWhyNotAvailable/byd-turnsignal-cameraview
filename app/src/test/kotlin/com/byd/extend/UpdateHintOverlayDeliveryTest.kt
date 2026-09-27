package com.byd.extend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdateHintOverlayDeliveryTest {
    @Test
    fun postAttachSetupFailureIsReportedAsFailedOnlyOnce() {
        val attempt = UpdateHintOverlay.DeliveryAttempt("event", 1L, 1L, 1L)

        assertEquals(UpdateHintOverlay.DeliveryOutcome.FAILED,
            attempt.complete(UpdateHintOverlay.DeliveryOutcome.FAILED))
        assertNull(attempt.complete(UpdateHintOverlay.DeliveryOutcome.SHOWN))
        assertEquals(UpdateHintOverlay.DeliveryOutcome.FAILED, attempt.outcome)
    }

    @Test
    fun successfulDeliveryCannotBeReplayedAsCancelledAfterUserDismissal() {
        val attempt = UpdateHintOverlay.DeliveryAttempt("event", 1L, 1L, 1L)

        assertEquals(UpdateHintOverlay.DeliveryOutcome.SHOWN,
            attempt.complete(UpdateHintOverlay.DeliveryOutcome.SHOWN))
        assertNull(attempt.complete(UpdateHintOverlay.DeliveryOutcome.CANCELLED))
        assertEquals(UpdateHintOverlay.DeliveryOutcome.SHOWN, attempt.outcome)
    }
}
