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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

internal const val PLACEMENT_TABLET_ASPECT = 16f / 9f
internal const val PLACEMENT_CLUSTER_ASPECT = 1920f / 720f
internal const val PLACEMENT_PARKING_FRAME_ASPECT = 4f / 3f

internal data class PlacementFractions(
    val width: Float,
    val height: Float,
    val left: Float,
    val top: Float,
)

internal fun calculatePlacementFractions(
    sizePercent: Float,
    frameAspect: Float,
    canvasAspect: Float,
    x: Float,
    y: Float,
): PlacementFractions {
    var width = (sizePercent / 100f).coerceIn(.05f, .60f)
    val safeAspect = frameAspect.takeIf { it.isFinite() && it > 0f } ?: PLACEMENT_TABLET_ASPECT
    val safeCanvasAspect = canvasAspect.takeIf { it.isFinite() && it > 0f }
        ?: PLACEMENT_TABLET_ASPECT
    var height = width * safeCanvasAspect / safeAspect
    if (height > 1f) {
        width /= height
        height = 1f
    }
    val safeX = x.coerceIn(0f, 1f)
    val safeY = y.coerceIn(0f, 1f)
    return PlacementFractions(
        width = width,
        height = height,
        left = (1f - width) * safeX,
        top = (1f - height) * safeY,
    )
}

@Composable
internal fun CameraPlacementPreview(
    profile: CameraProfileId,
    sourceIndex: Int,
    state: CameraProfileUiState,
    colors: UiPalette,
    cameraHost: @Composable (CameraHostSlot) -> Unit,
    onMove: (Float, Float) -> Unit,
) {
    val storedX = state.x.toFloatOrNull()?.div(100f)?.coerceIn(0f, 1f) ?: 0f
    val storedY = state.y.toFloatOrNull()?.div(100f)?.coerceIn(0f, 1f) ?: 0f
    var dragX by remember(profile, state.x) { mutableFloatStateOf(storedX) }
    var dragY by remember(profile, state.y) { mutableFloatStateOf(storedY) }
    val canvasAspect = if (state.target == DisplayTarget.Cluster) {
        PLACEMENT_CLUSTER_ASPECT
    } else PLACEMENT_TABLET_ASPECT
    val placement = calculatePlacementFractions(
        state.size.toFloatOrNull() ?: 30f, state.frameAspect, canvasAspect, dragX, dragY)
    val density = LocalDensity.current

    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val canvasWidth = minOf(maxWidth, maxHeight * canvasAspect)
        val canvasHeight = canvasWidth / canvasAspect
        val canvasWidthPx = with(density) { canvasWidth.toPx() }
        val canvasHeightPx = with(density) { canvasHeight.toPx() }
        Box(
            Modifier.size(canvasWidth, canvasHeight)
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
                    canvasWidth * placement.width,
                    canvasHeight * placement.height,
                ).clip(RoundedCornerShape(8.dp))
                    .border(2.dp, colors.accent, RoundedCornerShape(8.dp))
                    .pointerInput(profile, placement.width, placement.height) {
                        detectDragGestures(
                            onDragEnd = { onMove(dragX, dragY) },
                            onDragCancel = {
                                dragX = storedX
                                dragY = storedY
                            },
                        ) { change, amount ->
                            change.consume()
                            val remainingX = canvasWidthPx * (1f - placement.width)
                            val remainingY = canvasHeightPx * (1f - placement.height)
                            dragX = if (remainingX > 0f) {
                                (dragX + amount.x / remainingX).coerceIn(0f, 1f)
                            } else 0f
                            dragY = if (remainingY > 0f) {
                                (dragY + amount.y / remainingY).coerceIn(0f, 1f)
                            } else 0f
                        }
                    }.testTag("placement-frame"),
            ) {
                cameraHost(CameraHostSlot(
                    CameraHostKind.Placement,
                    profile,
                    sourceIndex = sourceIndex,
                    editable = true,
                ))
            }
        }
    }
}
