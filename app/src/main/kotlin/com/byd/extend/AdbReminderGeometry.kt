package com.byd.extend

import kotlin.math.roundToInt

/** Pixel geometry shared by the real overlay and host-side tests. */
data class AdbReminderGeometry(
    val bodyLeft: Int,
    val bodyTop: Int,
    val bodyWidth: Int,
    val bodyHeight: Int,
    val windowLeft: Int,
    val windowTop: Int,
    val windowWidth: Int,
    val windowHeight: Int,
    val retryAbove: Boolean,
    val retryHeight: Int,
) {
    val bodyTopInWindow: Int get() = if (retryAbove) retryHeight else 0

    companion object {
        @JvmStatic
        fun calculate(
            availableLeft: Int,
            availableTop: Int,
            availableWidth: Int,
            availableHeight: Int,
            bodyMeasuredHeight: Int,
            retryHeight: Int,
            appearance: AdbReminderAppearance,
        ): AdbReminderGeometry {
            val value = appearance.normalized()
            val width = (availableWidth.coerceAtLeast(1) * value.widthPercent / 100f)
                .roundToInt().coerceIn(1, availableWidth.coerceAtLeast(1))
            val height = bodyMeasuredHeight.coerceIn(1, availableHeight.coerceAtLeast(1))
            val travelX = (availableWidth - width).coerceAtLeast(0)
            val travelY = (availableHeight - height).coerceAtLeast(0)
            val bodyLeft = availableLeft + (travelX * value.x).roundToInt()
            val requestedBodyTop = availableTop + (travelY * value.y).roundToInt()
            val extension = retryHeight.coerceIn(0, (availableHeight - height).coerceAtLeast(0))
            val retryAbove = requestedBodyTop + height + extension > availableTop + availableHeight
            val windowTop = (if (retryAbove) requestedBodyTop - extension else requestedBodyTop)
                .coerceAtLeast(availableTop)
            val bodyTop = windowTop + if (retryAbove) extension else 0
            return AdbReminderGeometry(
                bodyLeft, bodyTop, width, height, bodyLeft, windowTop, width,
                height + extension, retryAbove, extension,
            )
        }
    }
}
