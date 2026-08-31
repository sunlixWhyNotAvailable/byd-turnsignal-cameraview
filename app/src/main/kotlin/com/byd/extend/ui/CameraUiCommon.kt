package com.byd.extend.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

@Composable
internal fun CameraPageHeader(tabIndex: Int, strings: UiStrings, colors: UiPalette) {
    PageTitle(strings.tabs[tabIndex], strings.text("Окремі параметри, розташування та калібрування",
        "Independent parameters, placement, and calibration"), colors)
}

@Composable
internal fun CameraWorkspace(
    section: CameraSection,
    reverse: Boolean,
    calibrationEnabled: Boolean,
    previewTitle: String,
    strings: UiStrings,
    colors: UiPalette,
    onSection: (CameraSection) -> Unit,
    profileControls: @Composable ColumnScope.() -> Unit,
    controls: @Composable ColumnScope.() -> Unit,
    preview: @Composable ColumnScope.() -> Unit,
) {
    Row(Modifier.fillMaxSize().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.width(400.dp).fillMaxHeight().verticalScroll(LocalPrimaryScroll.current),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Section(strings.text("Профіль", "Profile"), colors, Modifier.testTag("camera-profile"), content = profileControls)
            Section("", colors, Modifier.testTag("camera-settings"), header = {
                Segmented(if (reverse) strings.reverseSections else strings.cameraSections, section.ordinal, colors,
                    Modifier.fillMaxWidth().padding(start = 6.dp, top = 6.dp, end = 6.dp),
                    enabled = { it != CameraSection.Calibration.ordinal || calibrationEnabled }) {
                    onSection(CameraSection.entries[it])
                }
            }, content = controls)
        }
        Section(previewTitle, colors, Modifier.weight(1f).fillMaxHeight().testTag("camera-frame"), content = preview)
    }
}

@Composable
internal fun ProfilePresetButtons(
    profile: CameraProfileId,
    available: Boolean,
    canTransfer: Boolean,
    strings: UiStrings,
    colors: UiPalette,
    onAction: (BydExtendUiAction) -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf(
            Triple(strings.text("Зберегти\nпресет", "Save\npreset"), CommandId.SaveProfilePreset, true),
            Triple(strings.text("Завантажити\nпресет", "Load\npreset"), CommandId.LoadProfilePreset, available),
            Triple(strings.text("Перенести на\nпротилежну камеру", "Transfer to\nopposite camera"),
                CommandId.TransferProfilePreset, canTransfer),
        ).forEach { (label, command, enabled) ->
            ActionButton(label, colors, Modifier.weight(1f), enabled = enabled, height = 44.dp, maxLines = 2) {
                onAction(BydExtendUiAction.Run(command, profile))
            }
        }
    }
}

@Composable
internal fun ColumnScope.CameraProfileControls(
    profile: CameraProfileId,
    state: CameraProfileUiState,
    section: CameraSection,
    clusterAllowed: Boolean,
    strings: UiStrings,
    colors: UiPalette,
    onAction: (BydExtendUiAction) -> Unit,
    placementExtra: @Composable ColumnScope.() -> Unit = {},
    parameters: @Composable ColumnScope.() -> Unit,
) {
    var stage by rememberSaveable(profile) { mutableStateOf(CalibrationStage.Original) }
    val calibration = state.calibration
    fun profileNumber(field: ProfileNumber, value: String) {
        onAction(BydExtendUiAction.CommitNumber(NumberTarget.Profile(profile, field), value))
    }
    if (state.operation.status.visible) StatusText(state.operation.status, colors)
    when (section) {
        CameraSection.Parameters -> parameters()
        CameraSection.Placement -> {
            Segmented(if (clusterAllowed) listOf(strings.text("Планшет", "Tablet"), strings.text("Приборка", "Cluster"))
                else listOf(strings.text("Планшет", "Tablet")), if (clusterAllowed) state.target.ordinal else 0,
                colors, Modifier.fillMaxWidth()) {
                onAction(BydExtendUiAction.Select(SelectionTarget.Profile(SelectionId.ProfileTarget, profile), it))
            }
            NumericSetting(strings.text("Розмір", "Size"), state.size, "%", colors,
                { profileNumber(ProfileNumber.Size, it) }, 5f..60f, adjustable = true, slider = true,
                identity = NumberTarget.Profile(profile, ProfileNumber.Size))
            placementExtra()
            CoordinatePair(state.x, state.y, colors,
                { profileNumber(ProfileNumber.X, it) }, { profileNumber(ProfileNumber.Y, it) },
                NumberTarget.Profile(profile, ProfileNumber.X), NumberTarget.Profile(profile, ProfileNumber.Y))
            ResetProfileButton(strings.text("Скинути розташування", "Reset placement"),
                CommandId.ResetProfilePlacement, profile, colors, onAction)
        }
        CameraSection.Calibration -> {
            Segmented(strings.calibrationStages, stage.ordinal, colors, Modifier.fillMaxWidth()) {
                stage = CalibrationStage.entries[it]
            }
            when (stage) {
                CalibrationStage.Original -> {
                    CropControls(profile, calibration.original, ProfileNumber.OriginalX, ProfileNumber.OriginalY,
                        ProfileNumber.OriginalWidth, ProfileNumber.OriginalHeight, strings, colors, onAction,
                        enabled = true)
                    ResetProfileButton(strings.text("Скинути область", "Reset area"), CommandId.ResetProfileOriginal,
                        profile, colors, onAction)
                }
                CalibrationStage.Correction -> {
                    SwitchLine(strings.text("Корекція «риб’ячого ока»", "Fisheye correction"),
                        if (calibration.rawFallback) strings.text("Тимчасово використовується RAW", "RAW fallback is active") else "",
                        calibration.correctionEnabled,
                        { onAction(BydExtendUiAction.Toggle(ToggleTarget.Profile(ToggleId.ProfileCorrection, profile), it)) },
                        colors)
                    NumericSetting(strings.text("Огляд", "FOV"), calibration.fov, "°", colors,
                        { profileNumber(ProfileNumber.Fov, it) }, 60f..170f, adjustable = true, slider = true,
                        enabled = !calibration.rawFallback,
                        identity = NumberTarget.Profile(profile, ProfileNumber.Fov))
                    ChoiceField(strings.text("Проєкція", "Projection"),
                        listOf(strings.text("Прямолінійна", "Rectilinear"), strings.text("Циліндрична", "Cylindrical")),
                        calibration.projection, { onAction(BydExtendUiAction.Select(
                            SelectionTarget.Profile(SelectionId.ProfileProjection, profile), it)) }, colors,
                        enabled = !calibration.rawFallback)
                    CropControls(profile, calibration.corrected, ProfileNumber.CorrectedX, ProfileNumber.CorrectedY,
                        ProfileNumber.CorrectedWidth, ProfileNumber.CorrectedHeight, strings, colors, onAction,
                        enabled = !calibration.rawFallback)
                    ResetProfileButton(strings.text("Скинути корекцію", "Reset correction"),
                        CommandId.ResetProfileCorrection, profile, colors, onAction)
                }
                CalibrationStage.Output -> {
                    SwitchLine(strings.text("Віддзеркалити", "Mirror"), "", calibration.mirrored,
                        { onAction(BydExtendUiAction.Toggle(ToggleTarget.Profile(ToggleId.ProfileMirror, profile), it)) }, colors)
                    ChoiceField(strings.text("Режим повороту", "Rotation mode"),
                        listOf(strings.text("Вписати", "Fit"), strings.text("Заповнити", "Fill"),
                            strings.text("Розтягнути", "Stretch")), calibration.outputMode,
                        { onAction(BydExtendUiAction.Select(
                            SelectionTarget.Profile(SelectionId.ProfileOutputMode, profile), it)) }, colors)
                    NumericSetting(strings.text("Поворот", "Rotation"), calibration.rotation, "°", colors,
                        { profileNumber(ProfileNumber.Rotation, it) }, -180f..180f, adjustable = true, slider = true,
                        identity = NumberTarget.Profile(profile, ProfileNumber.Rotation))
                    ResetProfileButton(strings.text("Скинути вивід", "Reset output"), CommandId.ResetProfileOutput,
                        profile, colors, onAction)
                }
            }
        }
    }
}

@Composable
internal fun CameraProfilePreview(
    profile: CameraProfileId,
    sourceIndex: Int,
    state: CameraProfileUiState,
    section: CameraSection,
    strings: UiStrings,
    colors: UiPalette,
    onAction: (BydExtendUiAction) -> Unit,
    cameraHost: @Composable (CameraHostSlot) -> Unit,
) {
    when (section) {
        CameraSection.Parameters -> CameraStageFrame(strings.text("Перегляд", "Preview"), state.frameAspect, colors) {
            cameraHost(CameraHostSlot(
                CameraHostKind.Placement, profile, sourceIndex = sourceIndex))
        }
        CameraSection.Placement -> if (
            profile is CameraProfileId.Blind || profile is CameraProfileId.Parking
        ) CameraPlacementPreview(profile, sourceIndex, state, colors, cameraHost) { x, y ->
            onAction(BydExtendUiAction.MoveProfile(profile, x, y))
        } else CameraStageFrame(strings.text("Розташування", "Placement"), state.displayGeometry.aspect, colors) {
            cameraHost(CameraHostSlot(
                CameraHostKind.Placement, profile, sourceIndex = sourceIndex, editable = true))
        }
        CameraSection.Calibration -> Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CameraStageFrame(strings.text("Оригінал", "Original"), SOURCE_CAMERA_ASPECT, colors, Modifier.weight(1f)) {
                cameraHost(CameraHostSlot(CameraHostKind.CalibrationOriginal, profile, sourceIndex = sourceIndex))
            }
            if (state.calibration.correctionEnabled) CameraStageFrame(strings.text("Корекція", "Correction"), SOURCE_CAMERA_ASPECT,
                colors, Modifier.weight(1f)) {
                cameraHost(CameraHostSlot(CameraHostKind.CalibrationCorrected, profile, sourceIndex = sourceIndex))
            }
            CameraStageFrame(strings.text("Вивід", "Output"), state.frameAspect, colors, Modifier.weight(1f)) {
                cameraHost(CameraHostSlot(CameraHostKind.CalibrationOutput, profile, sourceIndex = sourceIndex))
            }
        }
    }
}

@Composable
internal fun CoordinatePair(x: String, y: String, colors: UiPalette,
    onX: (String) -> Unit, onY: (String) -> Unit,
    identityX: Any = Unit, identityY: Any = Unit, enabled: Boolean = true) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        NumericSetting("X", x, "%", colors, onX, 0f..100f, adjustable = true, inlineLabel = true,
            enabled = enabled, identity = identityX)
        NumericSetting("Y", y, "%", colors, onY, 0f..100f, adjustable = true, inlineLabel = true,
            enabled = enabled, identity = identityY)
    }
}

@Composable
private fun CropControls(
    profile: CameraProfileId,
    crop: CropUiState,
    xField: ProfileNumber,
    yField: ProfileNumber,
    widthField: ProfileNumber,
    heightField: ProfileNumber,
    strings: UiStrings,
    colors: UiPalette,
    onAction: (BydExtendUiAction) -> Unit,
    enabled: Boolean = true,
) {
    fun commit(field: ProfileNumber, value: String) {
        onAction(BydExtendUiAction.CommitNumber(NumberTarget.Profile(profile, field), value))
    }
    CoordinatePair(crop.x, crop.y, colors, { commit(xField, it) }, { commit(yField, it) },
        NumberTarget.Profile(profile, xField), NumberTarget.Profile(profile, yField), enabled)
    NumericSetting(strings.text("Ширина", "Width"), crop.width, "%", colors,
        { commit(widthField, it) }, 1f..100f, adjustable = true, enabled = enabled,
        identity = NumberTarget.Profile(profile, widthField))
    NumericSetting(strings.text("Висота", "Height"), crop.height, "%", colors,
        { commit(heightField, it) }, 1f..100f, adjustable = true, enabled = enabled,
        identity = NumberTarget.Profile(profile, heightField))
}

/** Known direct-camera source geometry (pano_h source). */
internal const val SOURCE_CAMERA_ASPECT = 1920f / 1300f

@Composable
internal fun CameraStageFrame(
    title: String,
    aspect: Float,
    colors: UiPalette,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(modifier.fillMaxWidth().fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, color = colors.muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val safeAspect = aspect.takeIf { it.isFinite() && it > 0f } ?: SOURCE_CAMERA_ASPECT
            val width = minOf(maxWidth, maxHeight * safeAspect)
            Box(Modifier.size(width, width / safeAspect).clip(RoundedCornerShape(7.dp))
                .background(Color.Black)) { content() }
        }
    }
}

@Composable
private fun ResetProfileButton(label: String, command: CommandId, profile: CameraProfileId,
    colors: UiPalette, onAction: (BydExtendUiAction) -> Unit) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        ActionButton(label, colors, Modifier.width(260.dp), icon = Icons.Outlined.Refresh) {
            onAction(BydExtendUiAction.Run(command, profile))
        }
    }
}
