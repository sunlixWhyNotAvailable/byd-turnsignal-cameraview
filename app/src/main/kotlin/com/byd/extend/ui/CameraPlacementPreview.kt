package com.byd.extend.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.byd.extend.productionPlacementGeometry
import kotlin.math.roundToInt

internal data class PlacementFractions(
    val width: Float,
    val height: Float,
    val left: Float,
    val top: Float,
)

@Composable
internal fun CameraPlacementPreview(
    profile: CameraProfileId,
    sourceIndex: Int,
    state: CameraProfileUiState,
    colors: UiPalette,
    cameraHost: @Composable (CameraHostSlot) -> Unit,
    onMove: (Float, Float) -> Unit,
    onResize: (Float, Float, Float, Float) -> Unit = { _, _, _, _ -> },
    editable: Boolean = true,
) {
    val storedX = state.x.toFloatOrNull()?.div(100f)?.coerceIn(0f, 1f) ?: 0f
    val storedY = state.y.toFloatOrNull()?.div(100f)?.coerceIn(0f, 1f) ?: 0f
    val dragX = remember(profile, state.target) { mutableFloatStateOf(storedX) }
    val dragY = remember(profile, state.target) { mutableFloatStateOf(storedY) }
    // Existing users may have a persisted Blind window below the new 5% editor minimum. Keep
    // that geometry visible on entry; the 5% floor is applied only by an explicit resize.
    fun storedFraction(value: String, fallback: Float): Float =
        value.toFloatOrNull()?.div(100f)?.takeIf { it.isFinite() && it > 0f }
            ?.coerceAtMost(1f) ?: fallback
    val fallbackWidth = storedFraction(state.size, .3f)
    val storedWidth = storedFraction(state.width, fallbackWidth)
    val storedHeight = storedFraction(state.height,
        (storedWidth * state.displayGeometry.aspect / state.frameAspect.coerceAtLeast(.0001f))
            .coerceIn(Float.MIN_VALUE, 1f))
    val dragWidth = remember(profile, state.target) { mutableFloatStateOf(storedWidth) }
    val dragHeight = remember(profile, state.target) { mutableFloatStateOf(storedHeight) }
    val latestOnMove by rememberUpdatedState(onMove)
    val latestStoredX by rememberUpdatedState(storedX)
    val latestStoredY by rememberUpdatedState(storedY)
    val latestOnResize by rememberUpdatedState(onResize)
    LaunchedEffect(profile, state.target, state.x, state.y, state.width, state.height) {
        dragX.floatValue = storedX
        dragY.floatValue = storedY
        dragWidth.floatValue = storedWidth
        dragHeight.floatValue = storedHeight
    }
    val display = state.displayGeometry
    val independentRectangle = profile is CameraProfileId.Blind || profile is CameraProfileId.Mirror
    val geometry = if (independentRectangle) null else productionPlacementGeometry(
        profile, display, state.size.toFloatOrNull() ?: 30f, state.frameAspect,
        dragX.floatValue, dragY.floatValue)
    val canvasWidth = (geometry?.canvasWidth ?: display.width).toFloat().coerceAtLeast(1f)
    val canvasHeight = (geometry?.canvasHeight ?: display.height).toFloat().coerceAtLeast(1f)
    val placement = if (independentRectangle) PlacementFractions(
        width = dragWidth.floatValue,
        height = dragHeight.floatValue,
        left = dragX.floatValue.coerceIn(0f, 1f - dragWidth.floatValue),
        top = dragY.floatValue.coerceIn(0f, 1f - dragHeight.floatValue),
    ) else PlacementFractions(
        width = geometry!!.width / canvasWidth,
        height = geometry.height / canvasHeight,
        left = geometry.left / canvasWidth,
        top = geometry.top / canvasHeight,
    )
    val canvasAspect = canvasWidth / canvasHeight
    val density = LocalDensity.current

    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val canvasDpWidth = minOf(maxWidth, maxHeight * canvasAspect)
        val canvasDpHeight = canvasDpWidth / canvasAspect
        val canvasWidthPx = with(density) { canvasDpWidth.toPx() }
        val canvasHeightPx = with(density) { canvasDpHeight.toPx() }
        Box(
            Modifier.size(canvasDpWidth, canvasDpHeight)
                .clip(RoundedCornerShape(12.dp))
                .background(colors.field)
                .border(1.dp, colors.borderStrong, RoundedCornerShape(12.dp))
                .testTag("placement-canvas"),
        ) {
            Box(
                Modifier.offset {
                    IntOffset(
                        (canvasWidthPx * placement.left).roundToInt(),
                        (canvasHeightPx * placement.top).roundToInt(),
                    )
                }.size(
                    canvasDpWidth * placement.width,
                    canvasDpHeight * placement.height,
                ).clip(RoundedCornerShape(8.dp))
                    .border(2.dp, colors.accent, RoundedCornerShape(8.dp))
                    .then(if (!editable) Modifier else Modifier.pointerInput(profile, state.target, display, "move") {
                        var commitMove = latestOnMove
                        var startX = 0f
                        var startY = 0f
                        var totalX = 0f
                        var totalY = 0f
                        detectDragGestures(
                            onDragStart = {
                                commitMove = latestOnMove
                                startX = dragX.floatValue
                                startY = dragY.floatValue
                                totalX = 0f
                                totalY = 0f
                            },
                            onDragEnd = { commitMove(dragX.floatValue, dragY.floatValue) },
                            onDragCancel = {
                                dragX.floatValue = latestStoredX
                                dragY.floatValue = latestStoredY
                            },
                        ) { change, amount ->
                            change.consume()
                            totalX += amount.x
                            totalY += amount.y
                            if (independentRectangle) {
                                dragX.floatValue = (startX + totalX / canvasWidthPx)
                                    .coerceIn(0f, 1f - dragWidth.floatValue)
                                dragY.floatValue = (startY + totalY / canvasHeightPx)
                                    .coerceIn(0f, 1f - dragHeight.floatValue)
                                return@detectDragGestures
                            }
                            val displayScaleX = canvasWidthPx / display.width.coerceAtLeast(1)
                            val displayScaleY = canvasHeightPx / display.height.coerceAtLeast(1)
                            val horizontalMargin = if (profile is CameraProfileId.Blind) {
                                display.marginLeft
                            } else 0
                            val verticalTop = if (profile is CameraProfileId.Blind) display.marginTop else 0
                            val verticalBottom = if (profile is CameraProfileId.Blind) display.marginBottom else 0
                            val remainingX = (display.width - horizontalMargin * 2
                                - geometry!!.width).coerceAtLeast(0) * displayScaleX
                            val remainingY = (display.height - verticalTop - verticalBottom
                                - geometry.height).coerceAtLeast(0) * displayScaleY
                            dragX.floatValue = if (remainingX > 0f) {
                                (startX + totalX / remainingX).coerceIn(0f, 1f)
                            } else 0f
                            dragY.floatValue = if (remainingY > 0f) {
                                (startY + totalY / remainingY).coerceIn(0f, 1f)
                            } else 0f
                        }
                    }).testTag("placement-frame"),
            ) {
                cameraHost(CameraHostSlot(
                    if (profile == CameraProfileId.Mirror) CameraHostKind.Mirror else CameraHostKind.Placement,
                    profile,
                    sourceIndex = sourceIndex,
                    editable = editable,
                ))
                if (independentRectangle && editable) {
                    // Four visible handles keep the opposite corner fixed.  Gesture deltas are
                    // accumulated in the mutable fractions and only formatted on gesture end.
                    listOf(
                        0 to Alignment.TopStart,
                        1 to Alignment.TopEnd,
                        2 to Alignment.BottomStart,
                        3 to Alignment.BottomEnd,
                    ).forEach { (corner, alignment) ->
                        Box(Modifier.align(alignment).size(24.dp)
                            .clip(RoundedCornerShape(5.dp)).background(colors.accent)
                            .border(1.dp, colors.borderStrong, RoundedCornerShape(5.dp))
                            .pointerInput(profile, state.target, display, "resize", corner) {
                                var commitResize = latestOnResize
                                var startX = 0f
                                var startY = 0f
                                var startWidth = 0f
                                var startHeight = 0f
                                var totalX = 0f
                                var totalY = 0f
                                detectDragGestures(
                                    onDragStart = {
                                        commitResize = latestOnResize
                                        startX = dragX.floatValue
                                        startY = dragY.floatValue
                                        startWidth = dragWidth.floatValue
                                        startHeight = dragHeight.floatValue
                                        totalX = 0f
                                        totalY = 0f
                                    },
                                    onDragEnd = {
                                        commitResize(
                                            dragX.floatValue, dragY.floatValue,
                                            dragWidth.floatValue, dragHeight.floatValue,
                                        )
                                    },
                                    onDragCancel = {
                                        dragX.floatValue = latestStoredX
                                        dragY.floatValue = latestStoredY
                                        dragWidth.floatValue = storedWidth
                                        dragHeight.floatValue = storedHeight
                                    },
                                ) { change, amount ->
                                    change.consume()
                                    totalX += amount.x
                                    totalY += amount.y
                                    val dx = totalX / canvasWidthPx.coerceAtLeast(1f)
                                    val dy = totalY / canvasHeightPx.coerceAtLeast(1f)
                                    val left = corner == 0 || corner == 2
                                    val top = corner == 0 || corner == 1
                                    if (left) {
                                        val right = startX + startWidth
                                        if (right >= .05f) {
                                            val nextWidth = (startWidth - dx).coerceIn(.05f, right)
                                            dragWidth.floatValue = nextWidth
                                            dragX.floatValue = right - nextWidth
                                        }
                                    } else {
                                        val available = 1f - startX
                                        if (available >= .05f) {
                                            dragWidth.floatValue = (startWidth + dx)
                                                .coerceIn(.05f, available)
                                            dragX.floatValue = startX
                                        }
                                    }
                                    if (top) {
                                        val bottom = startY + startHeight
                                        if (bottom >= .05f) {
                                            val nextHeight = (startHeight - dy).coerceIn(.05f, bottom)
                                            dragHeight.floatValue = nextHeight
                                            dragY.floatValue = bottom - nextHeight
                                        }
                                    } else {
                                        val available = 1f - startY
                                        if (available >= .05f) {
                                            dragHeight.floatValue = (startHeight + dy)
                                                .coerceIn(.05f, available)
                                            dragY.floatValue = startY
                                        }
                                    }
                                }
                            }.testTag("placement-resize-handle-$corner"))
                    }
                }
            }
        }
    }
}
