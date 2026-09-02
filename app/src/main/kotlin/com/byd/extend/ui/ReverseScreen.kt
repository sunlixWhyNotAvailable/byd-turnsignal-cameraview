package com.byd.extend.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

@Composable
internal fun ReverseScreen(
    state: ReverseUiState,
    strings: UiStrings,
    colors: UiPalette,
    onAction: (BydExtendUiAction) -> Unit,
    cameraHost: @Composable (CameraHostSlot) -> Unit,
    onPreview: (NumberTarget, String, Long) -> String?,
) {
    val selected = state.selectedElement
    val cameraElement = selected in listOf(ReverseElement.Rear, ReverseElement.RearLeft, ReverseElement.RearRight)
    val profileId = CameraProfileId.Reverse(selected, state.selectedSource)
    val profile = state.profiles[profileId] ?: CameraProfileUiState()
    val sourceIndex = reverseSourceIndex(selected, state.selectedSource)
    val profileControls: @Composable ColumnScope.() -> Unit = {
        ChoiceField(strings.text("Елемент", "Element"), strings.reverseElements, selected.ordinal,
            { onAction(BydExtendUiAction.Select(SelectionTarget.Simple(SelectionId.ReverseElement), it)) }, colors)
        SwitchLine(strings.text("Покращений задній вид", "Enhanced reverse view"), "", state.enabled,
            { onAction(BydExtendUiAction.Toggle(ToggleTarget.Simple(ToggleId.ReverseEnabled), it)) }, colors)
        if (cameraElement) {
            SwitchLine(
                if (selected == ReverseElement.Rear) strings.text("Інтеграція передньої камери", "Integrate front camera")
                else strings.text("Інтеграція передніх камер", "Integrate front cameras"), "",
                state.frontIntegration[selected] == true,
                { onAction(BydExtendUiAction.Toggle(
                    ToggleTarget.Reverse(ToggleId.ReverseFrontIntegration, selected), it)) }, colors,
            )
            if (state.section == CameraSection.Calibration) {
                Segmented(listOf(strings.text("Задня", "Rear"), strings.text("Передня", "Front")),
                    state.selectedSource.ordinal, colors, Modifier.fillMaxWidth()) {
                    onAction(BydExtendUiAction.Select(SelectionTarget.Simple(SelectionId.ReverseSource), it))
                }
            }
            ProfilePresetButtons(profileId, profile.presetAvailable,
                selected == ReverseElement.RearLeft || selected == ReverseElement.RearRight,
                strings, colors, onAction)
        }
    }
    ScreenSurface(colors, scroll = false, compact = true) {
        CameraPageHeader(3, strings, colors)
        if (state.section == CameraSection.Calibration && cameraElement) {
            CameraWorkspace(
                state.section, reverse = true, calibrationEnabled = true,
                previewTitle = strings.text("Калібрування", "Calibration"), strings = strings, colors = colors,
                onSection = {
                    onAction(BydExtendUiAction.Select(SelectionTarget.Simple(SelectionId.CameraSection), it.ordinal))
                },
                profileStatus = profile.operation.status,
                profileControls = profileControls,
                controls = {
                    CameraProfileControls(profileId, profile, state.section, false, strings, colors, onAction,
                        onPreview = onPreview,
                        parameters = {})
                },
                preview = { CameraProfilePreview(
                    profileId, sourceIndex, profile, state.section, strings, colors, onAction, cameraHost) },
            )
        } else {
            CameraWorkspace(
                state.section, reverse = true, calibrationEnabled = cameraElement,
                previewTitle = strings.text("Композиція заднього ходу", "Reverse composition"),
                strings = strings, colors = colors,
                onSection = {
                    onAction(BydExtendUiAction.Select(SelectionTarget.Simple(SelectionId.CameraSection), it.ordinal))
                },
                profileStatus = profile.operation.status,
                profileControls = profileControls,
                controls = {
                    if (state.section == CameraSection.Parameters) {
                        ReverseElement.entries.forEach { element ->
                            val visible = state.geometry[element]?.visible ?: true
                            SwitchLine(strings.reverseElements[element.ordinal], "", visible,
                                { onAction(BydExtendUiAction.Toggle(
                                    ToggleTarget.Reverse(ToggleId.ReverseElementVisible, element), it)) },
                                colors)
                        }
                    } else {
                        ReverseCompositionControls(state, selected, cameraElement, strings, colors, onAction)
                    }
                },
                preview = {
                    ReverseCompositionFrame(state, cameraHost, selected)
                },
            )
        }
    }
}

@Composable
private fun ReverseCompositionControls(
    state: ReverseUiState,
    selected: ReverseElement,
    cameraElement: Boolean,
    strings: UiStrings,
    colors: UiPalette,
    onAction: (BydExtendUiAction) -> Unit,
) {
    val geometry = state.geometry[selected] ?: ReverseGeometryUiState()
    fun commit(field: ReverseGeometryNumber, value: String) {
        onAction(BydExtendUiAction.CommitNumber(NumberTarget.ReverseGeometry(selected, field), value))
    }
    val width = (geometry.width.toFloatOrNull() ?: 100f).coerceIn(5f, 100f)
    val height = (geometry.height.toFloatOrNull() ?: 100f).coerceIn(5f, 100f)
    val x = (geometry.x.toFloatOrNull() ?: 0f).coerceIn(0f, 100f)
    val y = (geometry.y.toFloatOrNull() ?: 0f).coerceIn(0f, 100f)
    CoordinatePair(geometry.x, geometry.y, colors,
        { commit(ReverseGeometryNumber.X, it) }, { commit(ReverseGeometryNumber.Y, it) },
        NumberTarget.ReverseGeometry(selected, ReverseGeometryNumber.X),
        NumberTarget.ReverseGeometry(selected, ReverseGeometryNumber.Y),
        maxX = 100f - width, maxY = 100f - height,
        horizontalTitle = strings.text("Горизонталь", "Horizontal"),
        verticalTitle = strings.text("Вертикаль", "Vertical"))
    GeometryPair(strings.text("Ширина", "Width"), geometry.width,
        { commit(ReverseGeometryNumber.Width, it) }, strings.text("Висота", "Height"), geometry.height,
        { commit(ReverseGeometryNumber.Height, it) }, colors,
        5f..(100f - x).coerceAtLeast(5f), "reverse-size-pair",
        secondRange = 5f..(100f - y).coerceAtLeast(5f),
        identityFirst = NumberTarget.ReverseGeometry(selected, ReverseGeometryNumber.Width),
        identitySecond = NumberTarget.ReverseGeometry(selected, ReverseGeometryNumber.Height))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ActionButton(strings.text("Нижче шар", "Lower layer"), colors, Modifier.weight(1f), height = 32.dp,
            enabled = cameraElement && state.zOrder.indexOf(selected) > 0) {
            onAction(BydExtendUiAction.Run(CommandId.ReverseLower))
        }
        ActionButton(strings.text("Вище шар", "Raise layer"), colors, Modifier.weight(1f), height = 32.dp,
            enabled = cameraElement && state.zOrder.indexOf(selected) in 0 until state.zOrder.lastIndex) {
            onAction(BydExtendUiAction.Run(CommandId.ReverseRaise))
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        ActionButton(strings.text("Скинути вигляд", "Reset layout"), colors, Modifier.width(260.dp), mainBackground = true) {
            onAction(BydExtendUiAction.Run(CommandId.ReverseResetLayout))
        }
    }
}

@Composable
private fun ReverseCompositionFrame(
    state: ReverseUiState,
    cameraHost: @Composable (CameraHostSlot) -> Unit,
    selected: ReverseElement,
) {
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        val aspect = state.displayGeometry.aspect.takeIf { it.isFinite() && it > 0f } ?: (16f / 9f)
        val width = minOf(maxWidth, maxHeight * aspect)
        Box(Modifier.size(width, width / aspect).clip(RoundedCornerShape(7.dp))) {
            cameraHost(CameraHostSlot(CameraHostKind.ReverseComposition,
                reverseElement = selected,
                editable = state.section == CameraSection.Placement))
        }
    }
}

private fun reverseSourceIndex(element: ReverseElement, source: ReverseSource): Int = when {
    source == ReverseSource.Front && element == ReverseElement.Rear -> 4
    source == ReverseSource.Front && element == ReverseElement.RearLeft -> 2
    source == ReverseSource.Front -> 3
    element == ReverseElement.Rear -> 1
    element == ReverseElement.RearLeft -> 2
    else -> 3
}
