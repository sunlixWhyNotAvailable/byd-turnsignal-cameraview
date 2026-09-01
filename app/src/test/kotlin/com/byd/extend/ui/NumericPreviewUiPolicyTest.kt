package com.byd.extend.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class NumericPreviewUiPolicyTest {
    @Test
    fun sliderTicksNormalizeToIntegerAndStayInRange() {
        assertEquals(6f, normalizeSliderValue(5.6f, 5f..60f), 0f)
        assertEquals(5f, normalizeSliderValue(4.4f, 5f..60f), 0f)
        assertEquals(60f, normalizeSliderValue(60.4f, 5f..60f), 0f)
        assertEquals("-12", sliderText(-12f))
    }

    @Test
    fun previewActionKeepsStableTargetIdentity() {
        val first = NumberTarget.Profile(
            CameraProfileId.Blind(CameraGroup.Rear, CameraSide.Left), ProfileNumber.Size)
        val second = NumberTarget.Profile(
            CameraProfileId.Blind(CameraGroup.Rear, CameraSide.Left), ProfileNumber.Size)
        assertEquals(first, second)
        assertEquals(BydExtendUiAction.PreviewNumber(first, "24"),
            BydExtendUiAction.PreviewNumber(second, "24"))
    }

    @Test
    fun previewSessionFinishesOnceAndConsecutiveGesturesGetFreshIds() {
        val session = NumericPreviewSession()
        val first = session.begin()
        assertEquals(true, session.finish(first))
        assertEquals(false, session.finish(first))
        val second = session.begin()
        assertEquals(true, second > first)
        assertEquals(true, session.finish(second))

        val disposed = NumericPreviewSession()
        val disposedId = disposed.begin()
        disposed.dispose()
        assertEquals(false, disposed.finish(disposedId))
    }
}
