package com.byd.extend.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Returns the diagnostic preview frame aspect without guessing vendor AVM dimensions.
 * Direct pano_h has a fixed source geometry; AVM uses the resolved production display bounds.
 */
internal fun diagnosticCameraFrameAspect(
    direct: Boolean,
    displayGeometry: CameraDisplayGeometry,
): Float = if (direct) SOURCE_CAMERA_ASPECT else displayGeometry.aspect.takeIf {
    it.isFinite() && it > 0f
} ?: SOURCE_CAMERA_ASPECT

internal fun diagnosticGroupTitle(direct: Boolean, orientation: AvmOrientation): String =
    if (direct) "pano_h" else if (orientation == AvmOrientation.Horizontal) "VIEW_GROUP_H" else "VIEW_GROUP_V"

@Composable
internal fun DebugScreen(
    state: DebugUiState,
    guardEnabled: Boolean,
    strings: UiStrings,
    colors: UiPalette,
    onAction: (BydExtendUiAction) -> Unit,
    cameraHost: @Composable (CameraHostSlot) -> Unit,
) {
    val modeIndicatorPosition by animateFloatAsState(
        state.mode.ordinal.toFloat(), tween(durationMillis = 180, delayMillis = 0),
        label = "debugModeIndicator",
    )
    ScreenSurface(colors, scroll = false) {
        when (state.mode) {
            DiagnosticMode.Signals -> Row(
                Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                DebugHeader(state, modeIndicatorPosition, strings, colors, onAction)
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.TopEnd) {
                    SignalDiagnostics(state, guardEnabled, strings, colors, onAction,
                        Modifier.widthIn(max = 460.dp).fillMaxWidth())
                }
            }
            DiagnosticMode.Direct -> CameraDiagnostics(
                true, state, modeIndicatorPosition, strings, colors, onAction, cameraHost)
            DiagnosticMode.Avm -> CameraDiagnostics(
                false, state, modeIndicatorPosition, strings, colors, onAction, cameraHost)
        }
    }
}

@Composable
private fun DebugHeader(
    state: DebugUiState,
    modeIndicatorPosition: Float,
    strings: UiStrings,
    colors: UiPalette,
    onAction: (BydExtendUiAction) -> Unit,
) {
    Column(Modifier.width(400.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PageTitle(strings.tabs[5], strings.text("Ручні перевірки поворотників та камер",
            "Manual turn-signal and camera checks"), colors)
        Panel(colors, Modifier.fillMaxWidth()) {
            Segmented(strings.debugModes, state.mode.ordinal, colors, Modifier.fillMaxWidth(), modeIndicatorPosition) {
                onAction(BydExtendUiAction.Select(SelectionTarget.Simple(SelectionId.DiagnosticMode), it))
            }
        }
    }
}

@Composable
private fun SignalDiagnostics(
    state: DebugUiState,
    guardEnabled: Boolean,
    strings: UiStrings,
    colors: UiPalette,
    onAction: (BydExtendUiAction) -> Unit,
    modifier: Modifier,
) {
    Section(strings.text("Ручне керування • тільки P", "Manual control • P only"), colors,
        modifier) {
        Text(strings.text("Доступно із вимкненим захистом поворотників та селектором у P.",
            "Available with the turn-signal guard disabled and the selector in P."), color = colors.muted, fontSize = 13.sp)
        val commands = listOf(
            strings.text("Лівий", "Left") to CommandId.SignalLeft,
            strings.text("Правий", "Right") to CommandId.SignalRight,
            strings.text("Аварійка", "Hazard") to CommandId.SignalHazard,
            strings.text("Скинути", "Reset") to CommandId.SignalReset,
        )
        commands.chunked(2).forEach { rowCommands ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowCommands.forEach { (label, command) ->
                    ActionButton(label, colors, Modifier.weight(1f),
                        enabled = state.manualSignalsAllowed && !guardEnabled) {
                        onAction(BydExtendUiAction.Run(command))
                    }
                }
            }
        }
        StatusText(state.manualSignalStatus, colors)
    }
}

@Composable
private fun CameraDiagnostics(
    direct: Boolean,
    state: DebugUiState,
    modeIndicatorPosition: Float,
    strings: UiStrings,
    colors: UiPalette,
    onAction: (BydExtendUiAction) -> Unit,
    cameraHost: @Composable (CameraHostSlot) -> Unit,
) {
    val selection = if (direct) state.directSelection else state.avmSelection
    val operation = if (direct) state.directOperation else state.avmOperation
    val modes = if (direct) {
        listOf("Index 0 • all", "Index 1 • rear", "Index 2 • left", "Index 3 • right", "Index 4 • front")
    } else AvmModeNames.mapIndexed { index, name ->
        "${(index + 1).toString().padStart(2, '0')} • $name"
    }
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.width(400.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            DebugHeader(state, modeIndicatorPosition, strings, colors, onAction)
            Section(diagnosticGroupTitle(direct, state.avmOrientation), colors,
                Modifier.weight(1f).fillMaxWidth()) {
            if (!direct) {
                Segmented(listOf(strings.text("Горизонтально", "Horizontal"),
                    strings.text("Вертикально", "Vertical")), state.avmOrientation.ordinal,
                    colors, Modifier.fillMaxWidth()) {
                    onAction(BydExtendUiAction.Select(SelectionTarget.Simple(SelectionId.AvmOrientation), it))
                }
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                itemsIndexed(modes, key = { index, _ -> index }) { index, label ->
                    ActionButton(label, colors, Modifier.fillMaxWidth(), primary = selection == index,
                        enabled = operation.enabled && !operation.pending, maxLines = 2) {
                        onAction(BydExtendUiAction.Select(SelectionTarget.Simple(
                            if (direct) SelectionId.DirectMode else SelectionId.AvmMode), index))
                    }
                }
            }
            ActionButton(strings.text("Стоп", "Stop"), colors, Modifier.fillMaxWidth(),
                icon = Icons.Outlined.Stop,
                enabled = selection != null && operation.enabled && !operation.pending) {
                onAction(BydExtendUiAction.Run(CommandId.StopDiagnosticCamera))
            }
            }
        }
        Section(if (direct) strings.text("Попередній перегляд", "Preview")
            else diagnosticGroupTitle(false, state.avmOrientation), colors,
            Modifier.weight(1f).fillMaxHeight(),
            trailing = { CameraStatusPill(operation.status, strings, colors) }) {
            if (selection != null) {
                // Direct pano_h has a known 1920x1300 source. AVM's SDK output dimensions are
                // vehicle-configured at runtime, so the resolved tablet display is the only
                // authoritative geometry available to this Compose layer; do not invent a mode
                // aspect here. The Java host owns clipping/filling this frame.
                val frameAspect = diagnosticCameraFrameAspect(direct, state.displayGeometry)
                CameraStageFrame(strings.text("Кадр", "Frame"), frameAspect, colors, Modifier.fillMaxSize()) {
                    cameraHost(CameraHostSlot(if (direct) CameraHostKind.Direct else CameraHostKind.Avm,
                        sourceIndex = if (direct) selection else null,
                        modeIndex = if (direct) null else selection))
                }
            } else EmptyCamera(strings.text("Виберіть режим камери", "Select a camera mode"), colors)
        }
    }
}

@Composable
private fun EmptyCamera(message: String, colors: UiPalette) {
    Box(Modifier.fillMaxSize().clip(RoundedCornerShape(7.dp)).background(Color.Black),
        contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Outlined.Videocam, null, tint = colors.borderStrong, modifier = Modifier.size(54.dp))
            Text(message, color = colors.muted, fontSize = 16.sp)
        }
    }
}
