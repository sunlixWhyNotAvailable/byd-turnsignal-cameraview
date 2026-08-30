package com.byd.extend.ui

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun DebugScreen(
    state: DebugUiState,
    guardEnabled: Boolean,
    strings: UiStrings,
    colors: UiPalette,
    onAction: (BydExtendUiAction) -> Unit,
    cameraHost: @Composable (CameraHostSlot) -> Unit,
) {
    ScreenSurface(colors, scroll = false) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            PageTitle(strings.tabs[5], strings.text("Ручні перевірки поворотників та камер",
                "Manual turn-signal and camera checks"), colors, Modifier.weight(1f))
            Segmented(strings.debugModes, state.mode.ordinal, colors, Modifier.width(540.dp)) {
                onAction(BydExtendUiAction.Select(SelectionTarget.Simple(SelectionId.DiagnosticMode), it))
            }
        }
        when (state.mode) {
            DiagnosticMode.Signals -> SignalDiagnostics(state, guardEnabled, strings, colors, onAction)
            DiagnosticMode.Direct -> CameraDiagnostics(true, state, strings, colors, onAction, cameraHost)
            DiagnosticMode.Avm -> CameraDiagnostics(false, state, strings, colors, onAction, cameraHost)
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
) {
    Section(strings.text("Ручне керування • тільки P", "Manual control • P only"), colors,
        Modifier.fillMaxWidth().fillMaxHeight().padding(top = 10.dp)) {
        Text(strings.text("Доступно із вимкненим захистом поворотників та селектором у P.",
            "Available with the turn-signal guard disabled and the selector in P."), color = colors.muted, fontSize = 13.sp)
        val commands = listOf(
            strings.text("Лівий", "Left") to CommandId.SignalLeft,
            strings.text("Правий", "Right") to CommandId.SignalRight,
            strings.text("Аварійка", "Hazard") to CommandId.SignalHazard,
            strings.text("Скинути", "Reset") to CommandId.SignalReset,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            commands.forEach { (label, command) ->
                ActionButton(label, colors, Modifier.weight(1f),
                    enabled = state.manualSignalsAllowed && !guardEnabled) {
                    onAction(BydExtendUiAction.Run(command))
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
    Row(Modifier.fillMaxSize().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Section(if (direct) "pano_h" else "VIEW_GROUP_H", colors, Modifier.weight(.34f).fillMaxHeight()) {
            if (!direct) {
                Segmented(listOf(strings.text("Горизонтально", "Horizontal"),
                    strings.text("Вертикально", "Vertical")), state.avmOrientation.ordinal,
                    colors, Modifier.fillMaxWidth()) {
                    onAction(BydExtendUiAction.Select(SelectionTarget.Simple(SelectionId.AvmOrientation), it))
                }
                SwitchLine(strings.text("Показати RAW", "Show raw"), "", state.avmShowRaw,
                    { onAction(BydExtendUiAction.Toggle(ToggleTarget.Simple(ToggleId.AvmShowRaw), it)) }, colors)
                SwitchLine(strings.text("Корекція", "Dewarp"), "", state.avmDewarp,
                    { onAction(BydExtendUiAction.Toggle(ToggleTarget.Simple(ToggleId.AvmDewarp), it)) }, colors)
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
        Section(strings.text("Попередній перегляд", "Preview"), colors,
            Modifier.weight(.66f).fillMaxHeight(), trailing = {
                StatusPill(operation.status, if (selection != null) "LIVE" else "IDLE", colors)
            }) {
            if (selection != null) {
                cameraHost(CameraHostSlot(if (direct) CameraHostKind.Direct else CameraHostKind.Avm,
                    sourceIndex = if (direct) selection else null,
                    modeIndex = if (direct) null else selection))
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
