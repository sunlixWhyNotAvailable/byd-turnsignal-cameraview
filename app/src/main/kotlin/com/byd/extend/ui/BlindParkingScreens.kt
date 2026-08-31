package com.byd.extend.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun BlindScreen(
    state: BlindUiState,
    strings: UiStrings,
    colors: UiPalette,
    onAction: (BydExtendUiAction) -> Unit,
    cameraHost: @Composable (CameraHostSlot) -> Unit,
) {
    val profileId = CameraProfileId.Blind(state.selectedGroup, state.selectedSide)
    val profile = state.profiles[profileId] ?: CameraProfileUiState()
    val rules = state.rules[state.selectedGroup] ?: BlindRuleUiState()
    val sourceIndex = if (state.selectedSide == CameraSide.Left) 2 else 3
    val label = strings.text(
        (if (state.selectedGroup == CameraGroup.Rear) "Задня " else "Передня ") +
            if (state.selectedSide == CameraSide.Left) "ліва" else "права",
        "${state.selectedGroup.name} ${state.selectedSide.name}",
    )
    ScreenSurface(colors, scroll = false, compact = true) {
        CameraPageHeader(1, strings, colors)
        CameraWorkspace(
            state.section,
            reverse = false,
            calibrationEnabled = true,
            previewTitle = strings.text("Попередній перегляд • $label", "Preview • $label"),
            strings = strings,
            colors = colors,
            onSection = {
                onAction(BydExtendUiAction.Select(SelectionTarget.Simple(SelectionId.CameraSection), it.ordinal))
            },
            profileControls = {
                Row(Modifier.height(40.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Segmented(listOf(strings.text("Задні", "Rear"), strings.text("Передні", "Front")),
                        state.selectedGroup.ordinal, colors, Modifier.weight(1f)) {
                        onAction(BydExtendUiAction.Select(SelectionTarget.Simple(SelectionId.BlindGroup), it))
                    }
                    Segmented(listOf(strings.text("Ліва", "Left"), strings.text("Права", "Right")),
                        state.selectedSide.ordinal, colors, Modifier.weight(1f)) {
                        onAction(BydExtendUiAction.Select(SelectionTarget.Simple(SelectionId.BlindSide), it))
                    }
                }
                val groupEnabled = if (state.selectedGroup == CameraGroup.Rear) state.rearEnabled else state.frontEnabled
                SwitchLine(
                    strings.text(
                        if (state.selectedGroup == CameraGroup.Rear) "Включити задні камери" else "Включити передні камери",
                        if (state.selectedGroup == CameraGroup.Rear) "Enable rear cameras" else "Enable front cameras",
                    ), "", groupEnabled,
                    {
                        onAction(BydExtendUiAction.Toggle(ToggleTarget.Blind(
                            if (state.selectedGroup == CameraGroup.Rear) ToggleId.BlindRear else ToggleId.BlindFront,
                            state.selectedGroup), it))
                    }, colors,
                )
                ProfilePresetButtons(profileId, profile.presetAvailable, true, strings, colors, onAction)
            },
            controls = {
                CameraProfileControls(profileId, profile, state.section, true, strings, colors, onAction,
                    parameters = { BlindParameters(state.selectedGroup, rules, strings, colors, onAction) })
            },
            preview = { CameraProfilePreview(
                profileId, sourceIndex, profile, state.section, strings, colors, onAction, cameraHost) },
        )
    }
}

@Composable
private fun BlindParameters(
    group: CameraGroup,
    rules: BlindRuleUiState,
    strings: UiStrings,
    colors: UiPalette,
    onAction: (BydExtendUiAction) -> Unit,
) {
    NumericSetting(strings.text("Мінімальна швидкість", "Minimum speed"), rules.minimumSpeed,
        strings.text("км/год", "km/h"), colors,
        { onAction(BydExtendUiAction.CommitNumber(NumberTarget.Blind(group, BlindNumber.MinimumSpeed), it)) }, 0f..300f,
        identity = NumberTarget.Blind(group, BlindNumber.MinimumSpeed))
    NumericSetting(strings.text("Максимальна швидкість", "Maximum speed"), rules.maximumSpeed,
        strings.text("км/год", "km/h"), colors,
        { onAction(BydExtendUiAction.CommitNumber(NumberTarget.Blind(group, BlindNumber.MaximumSpeed), it)) }, 0f..300f,
        identity = NumberTarget.Blind(group, BlindNumber.MaximumSpeed))
    NumericSetting(
        if (group == CameraGroup.Rear) strings.text("Кут різкого повороту", "Sharp-turn angle")
        else strings.text("Мінімальний кут керма", "Minimum steering angle"),
        rules.steeringAngle, "°", colors,
        { onAction(BydExtendUiAction.CommitNumber(NumberTarget.Blind(group, BlindNumber.SteeringAngle), it)) }, 0f..780f,
        identity = NumberTarget.Blind(group, BlindNumber.SteeringAngle))
    if (group == CameraGroup.Rear) {
        SwitchLine(strings.text("Протилежна камера різкого повороту", "Opposite camera on sharp turns"), "",
            rules.sharpTurnEnabled,
            { onAction(BydExtendUiAction.Toggle(ToggleTarget.Blind(ToggleId.BlindSharpTurn, group), it)) }, colors)
        SwitchLine(strings.text("Показ лише за об’єкта у сліпій зоні", "Show only with a blind-spot object"), "",
            rules.blindSpotOnly,
            { onAction(BydExtendUiAction.Toggle(ToggleTarget.Blind(ToggleId.BlindObjectOnly, group), it)) }, colors)
        ChoiceField(strings.text("Підсвітка об’єкта у сліпій зоні", "Blind-spot object highlight"),
            listOf(strings.text("Вимкнена", "Off"), strings.text("Постійно", "Steady"),
                strings.text("Пульсація", "Pulse")), rules.warningMode,
            { onAction(BydExtendUiAction.Select(SelectionTarget.Simple(SelectionId.BlindWarningMode), it)) }, colors)
    } else {
        SwitchLine(strings.text("Обов'язково поворотник", "Turn signal required"), "", rules.turnRequired,
            { onAction(BydExtendUiAction.Toggle(ToggleTarget.Blind(ToggleId.BlindTurnRequired, group), it)) }, colors)
    }
}

@Composable
internal fun ParkingScreen(
    state: ParkingUiState,
    strings: UiStrings,
    colors: UiPalette,
    onAction: (BydExtendUiAction) -> Unit,
    cameraHost: @Composable (CameraHostSlot) -> Unit,
) {
    val view = state.selectedView
    val viewState = state.views[view] ?: ParkingViewUiState()
    val profileId = CameraProfileId.Parking(view)
    val label = strings.parkingViews[view.ordinal]
    ScreenSurface(colors, scroll = false, compact = true) {
        CameraPageHeader(2, strings, colors)
        CameraWorkspace(
            state.section,
            reverse = false,
            calibrationEnabled = true,
            previewTitle = strings.text("Попередній перегляд • $label", "Preview • $label"),
            strings = strings,
            colors = colors,
            onSection = {
                onAction(BydExtendUiAction.Select(SelectionTarget.Simple(SelectionId.CameraSection), it.ordinal))
            },
            profileControls = {
                ChoiceField(strings.text("Вид камери", "Camera view"), strings.parkingViews, view.ordinal,
                    { onAction(BydExtendUiAction.Select(SelectionTarget.Simple(SelectionId.ParkingView), it)) }, colors)
                SwitchLine(strings.text("Увімкнути камеру", "Enable camera"), "", viewState.enabled,
                    { onAction(BydExtendUiAction.Toggle(ToggleTarget.Parking(ToggleId.ParkingView, view), it)) }, colors)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionButton(strings.text("Увімкнути всі камери", "Enable all cameras"), colors,
                        Modifier.weight(1f), height = 32.dp) {
                        onAction(BydExtendUiAction.Run(CommandId.EnableAllParking))
                    }
                    ActionButton(strings.text("Вимкнути всі камери", "Disable all cameras"), colors,
                        Modifier.weight(1f), height = 32.dp) {
                        onAction(BydExtendUiAction.Run(CommandId.DisableAllParking))
                    }
                }
                ProfilePresetButtons(profileId, viewState.profile.presetAvailable, true, strings, colors, onAction)
            },
            controls = {
                CameraProfileControls(profileId, viewState.profile, state.section, false, strings, colors, onAction,
                    placementExtra = {
                        SwitchLine(strings.text("Синхронізувати розмір", "Synchronize size"), "",
                            state.synchronizeSize,
                            { onAction(BydExtendUiAction.Toggle(
                                ToggleTarget.Parking(ToggleId.ParkingSynchronizeSize), it)) }, colors)
                    },
                    parameters = { ParkingParameters(state, view, viewState, strings, colors, onAction) })
            },
            preview = { CameraProfilePreview(
                profileId, view.sourceIndex, viewState.profile, state.section, strings,
                colors, onAction, cameraHost) },
        )
    }
}

@Composable
private fun ParkingParameters(
    state: ParkingUiState,
    view: ParkingView,
    viewState: ParkingViewUiState,
    strings: UiStrings,
    colors: UiPalette,
    onAction: (BydExtendUiAction) -> Unit,
) {
    NumericSetting(strings.text("Відстань спрацювання", "Trigger distance"), viewState.triggerDistance,
        strings.text("см", "cm"), colors,
        { onAction(BydExtendUiAction.CommitNumber(NumberTarget.Parking(view, ParkingNumber.TriggerDistance), it)) }, 0f..150f,
        identity = NumberTarget.Parking(view, ParkingNumber.TriggerDistance))
    NumericSetting(strings.text("Макс. швидкість • усі види", "Max. speed • all views"), state.maximumSpeed,
        strings.text("км/год", "km/h"), colors,
        { onAction(BydExtendUiAction.CommitNumber(NumberTarget.Parking(null, ParkingNumber.MaximumSpeed), it)) }, 0f..300f,
        identity = NumberTarget.Parking(null, ParkingNumber.MaximumSpeed))
    if (view in listOf(ParkingView.FrontLeft, ParkingView.FrontRight, ParkingView.RearRight, ParkingView.RearLeft)) {
        val central = if (view == ParkingView.FrontLeft || view == ParkingView.FrontRight) {
            strings.text("Передня", "Front")
        } else strings.text("Задня", "Rear")
        SwitchLine(strings.text("Додати центральну камеру", "Add the central camera"), central,
            viewState.addCentralCamera,
            { onAction(BydExtendUiAction.Toggle(ToggleTarget.Parking(ToggleId.ParkingAddCentral, view), it)) }, colors)
    }
    SwitchLine(strings.text("Разом із заднім ходом", "Alongside reverse"), "", state.alongsideReverse,
        { onAction(BydExtendUiAction.Toggle(ToggleTarget.Parking(ToggleId.ParkingAlongsideReverse), it)) }, colors)
}
