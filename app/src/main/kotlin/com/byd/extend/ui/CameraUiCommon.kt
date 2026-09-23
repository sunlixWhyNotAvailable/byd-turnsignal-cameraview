package com.byd.extend.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp

@Composable
internal fun CameraPageHeader(tabIndex: Int, strings: UiStrings, colors: UiPalette) {
    PageTitle(strings.tabs[tabIndex], strings.text("Окремі параметри, розташування та калібрування",
        "Independent parameters, placement, and calibration"), colors)
}

@Composable
internal fun CameraWorkspace(
    pageTab: Int,
    section: CameraSection,
    reverse: Boolean,
    calibrationEnabled: Boolean,
    previewTitle: String,
    strings: UiStrings,
    colors: UiPalette,
    onSection: (CameraSection) -> Unit,
    profileStatus: StatusUiState = StatusUiState(),
    panoramaStatus: StatusUiState? = null,
    profileControls: @Composable FormScope.() -> FormScope,
    controls: @Composable FormScope.() -> FormScope,
    preview: @Composable ColumnScope.() -> Unit,
) {
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        LazyForm(Modifier.width(400.dp).fillMaxHeight(), LocalPrimaryLazyList.current) {
            row("page-header") { CameraPageHeader(pageTab, strings, colors); Spacer(Modifier.height(8.dp)) }
            FormSection(strings.text("Профіль", "Profile"), colors,
                Modifier.testTag("camera-profile"), key = "profile", content = profileControls)
            row("section-gap") { Spacer(Modifier.height(8.dp)) }
            FormSection("", colors, Modifier.testTag("camera-settings"), key = "settings-${section.name}", header = {
                Segmented(if (reverse) strings.reverseSections else strings.cameraSections, section.ordinal, colors,
                    Modifier.fillMaxWidth().padding(start = 6.dp, top = 6.dp, end = 6.dp),
                    enabled = { it != CameraSection.Calibration.ordinal || calibrationEnabled }) {
                    onSection(CameraSection.entries[it])
                }
            }, content = controls)
        }
        Section(previewTitle, colors, Modifier.weight(1f).fillMaxHeight().testTag("camera-frame"),
            titleTrailing = if (panoramaStatus != null) {
                { PanoramaStatusPill(panoramaStatus, strings, colors) }
            } else null,
            trailing = { CameraStatusPill(profileStatus, strings, colors) }, content = preview)
    }
}

/** Camera lifecycle status occupies a stable header slot and exposes only Opening/Error. */
@Composable
internal fun CameraStatusPill(state: StatusUiState, strings: UiStrings, colors: UiPalette) {
    val normalized = cameraStatusForDisplay(state, strings)
    StatusPillSlot(normalized, strings.text("Статус", "Status"), colors)
}

@Composable
internal fun PanoramaStatusPill(state: StatusUiState, strings: UiStrings, colors: UiPalette) {
    StatusPillSlot(panoramaStatusForDisplay(state), strings.text("Статус", "Status"), colors)
}

@Composable
private fun StatusPillSlot(state: StatusUiState, fallback: String, colors: UiPalette) {
    val statusSlotHeight = with(LocalDensity.current) { 18.sp.toDp() } + 12.dp
    Box(Modifier.height(statusSlotHeight), contentAlignment = Alignment.CenterEnd) {
        StatusPill(state, fallback, colors)
    }
}

internal fun cameraStatusForDisplay(state: StatusUiState, strings: UiStrings): StatusUiState = when {
    // This slot is lifecycle-only: Warning means opening regardless of the localized/raw text.
    state.visible && state.tone == StatusTone.Warning ->
        state.copy(text = strings.text("Відкриття...", "Opening..."))
    state.visible && state.tone == StatusTone.Error ->
        state.copy(text = strings.text("Помилка", "Error"))
    else -> StatusUiState()
}

internal fun panoramaStatusForDisplay(state: StatusUiState): StatusUiState = when {
    state.visible && (state.tone == StatusTone.Warning || state.tone == StatusTone.Error) -> state
    else -> StatusUiState()
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
    val saveCommand = if (profile == CameraProfileId.Mirror) CommandId.MirrorSavePreset
        else CommandId.SaveProfilePreset
    val loadCommand = if (profile == CameraProfileId.Mirror) CommandId.MirrorLoadPreset
        else CommandId.LoadProfilePreset
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf(
            Triple(strings.text("Зберегти\nпресет", "Save\npreset"), saveCommand, true),
            Triple(strings.text("Завантажити\nпресет", "Load\npreset"), loadCommand, available),
            Triple(if (profile == CameraProfileId.Mirror)
                strings.text("Перенести на\nпередню камеру", "Copy to\nfront camera", "复制到\n前摄像头")
                else strings.text("Перенести на\nпротилежну камеру", "Transfer to\nopposite camera"),
                if (profile == CameraProfileId.Mirror) CommandId.MirrorCopyRearToFront
                else CommandId.TransferProfilePreset, canTransfer),
        ).forEach { (label, command, enabled) ->
            ActionButton(label, colors, Modifier.weight(1f), enabled = enabled, height = 44.dp, maxLines = 2) {
                onAction(BydExtendUiAction.Run(command, profile))
            }
        }
    }
}

@Composable
internal fun FormScope.CameraProfileControls(
    profile: CameraProfileId,
    state: CameraProfileUiState,
    section: CameraSection,
    clusterAllowed: Boolean,
    strings: UiStrings,
    colors: UiPalette,
    onAction: (BydExtendUiAction) -> Unit,
    onPreview: (NumberTarget, String, Long) -> String? = { _, value, _ -> value },
    identityFor: (NumberTarget) -> Any = { it },
    placementExtra: @Composable FormScope.() -> FormScope = { this },
    parameters: @Composable FormScope.() -> FormScope,
): FormScope {
    var stage by rememberSaveable(profile) { mutableStateOf(CalibrationStage.Original) }
    val calibration = state.calibration
    fun profileNumber(field: ProfileNumber, value: String, sessionId: Long? = null) {
        onAction(BydExtendUiAction.CommitNumber(NumberTarget.Profile(profile, field), value, sessionId))
    }
    fun profilePreview(field: ProfileNumber, value: String, sessionId: Long): String? {
        return onPreview(NumberTarget.Profile(profile, field), value, sessionId)
    }
    fun placementTarget(field: ProfileNumber, mirrorField: MirrorNumber): NumberTarget =
        if (profile == CameraProfileId.Mirror) NumberTarget.Mirror(mirrorField, state.target)
        else NumberTarget.Profile(profile, field, state.target)
    fun placementNumber(field: ProfileNumber, mirrorField: MirrorNumber, value: String) {
        onAction(BydExtendUiAction.CommitNumber(placementTarget(field, mirrorField), value))
    }
    fun command(generic: CommandId, mirror: CommandId): CommandId =
        if (profile == CameraProfileId.Mirror) mirror else generic
    when (section) {
        CameraSection.Parameters -> {
            parameters()
            row("profile-border") { CameraBorderControls(profile, state, strings, colors, onAction,
                identityFor(NumberTarget.Profile(profile, ProfileNumber.Fov)) to "profile-border") }
        }
        CameraSection.Placement -> {
            row("placement-target") { Segmented(if (clusterAllowed) listOf(strings.text("Планшет", "Tablet"), strings.text("Приборка", "Cluster"))
                else listOf(strings.text("Планшет", "Tablet")), if (clusterAllowed) state.target.ordinal else 0,
                colors, Modifier.fillMaxWidth()) {
                onAction(BydExtendUiAction.Select(SelectionTarget.Profile(SelectionId.ProfileTarget, profile), it))
            } }
            if (profile is CameraProfileId.Blind || profile is CameraProfileId.Mirror) {
                // Blind and Mirror use independent whole-display rectangles.  The old scalar
                // Size remains readable for legacy Parking and Reverse only.
                row("placement-position") { CoordinatePair(state.x, state.y, colors,
                    { placementNumber(ProfileNumber.X, MirrorNumber.X, it) },
                    { placementNumber(ProfileNumber.Y, MirrorNumber.Y, it) },
                    placementTarget(ProfileNumber.X, MirrorNumber.X),
                    placementTarget(ProfileNumber.Y, MirrorNumber.Y),
                    maxX = (100f - (state.width.toFloatOrNull() ?: 5f)).coerceAtLeast(0f),
                    maxY = (100f - (state.height.toFloatOrNull() ?: 5f)).coerceAtLeast(0f),
                    horizontalTitle = strings.text("Горизонталь", "Horizontal"),
                    verticalTitle = strings.text("Вертикаль", "Vertical")) }
                row("placement-size") { GeometryPair(strings.text("Ширина", "Width"), state.width,
                    { placementNumber(ProfileNumber.Width, MirrorNumber.Width, it) },
                    strings.text("Висота", "Height"), state.height,
                    { placementNumber(ProfileNumber.Height, MirrorNumber.Height, it) },
                    colors, 5f..100f, "size-pair",
                    secondRange = 5f..100f,
                    identityFirst = placementTarget(ProfileNumber.Width, MirrorNumber.Width),
                    identitySecond = placementTarget(ProfileNumber.Height, MirrorNumber.Height)) }
            } else {
                row("placement-size") { NumericSetting(strings.text("Розмір", "Size"), state.size, "%", colors,
                    { profileNumber(ProfileNumber.Size, it) }, 5f..60f, adjustable = true, slider = true,
                    identity = identityFor(NumberTarget.Profile(profile, ProfileNumber.Size)),
                    onPreview = { value, session -> profilePreview(ProfileNumber.Size, value, session) },
                    onCommitSession = { value, session -> profileNumber(ProfileNumber.Size, value, session) }) }
            }
            placementExtra()
            row("placement-reset") { ResetProfileButton(strings.text("Скинути розташування", "Reset placement"),
                command(CommandId.ResetProfilePlacement, CommandId.MirrorResetPlacement), profile,
                colors, onAction) }
        }
        CameraSection.Calibration -> {
            row("calibration-stage") { Segmented(strings.calibrationStages, stage.ordinal, colors, Modifier.fillMaxWidth()) {
                stage = CalibrationStage.entries[it]
            } }
            when (stage) {
                CalibrationStage.Original -> {
                    row("calibration-original-crop") { CropControls(profile, calibration.original, ProfileNumber.OriginalX, ProfileNumber.OriginalY,
                        ProfileNumber.OriginalWidth, ProfileNumber.OriginalHeight, strings, colors, onAction,
                        identityFor, enabled = true) }
                    row("calibration-original-reset") { ResetProfileButton(strings.text("Скинути область", "Reset area"),
                        command(CommandId.ResetProfileOriginal, CommandId.MirrorResetOriginal), profile,
                        colors, onAction) }
                }
                CalibrationStage.Correction -> {
                    row("calibration-correction-enabled") { SwitchLine(strings.text("Корекція «риб’ячого ока»", "Fisheye correction"),
                        if (calibration.rawFallback) strings.text("Тимчасово використовується RAW", "RAW fallback is active") else "",
                        calibration.correctionEnabled,
                        { onAction(BydExtendUiAction.Toggle(ToggleTarget.Profile(ToggleId.ProfileCorrection, profile), it)) },
                        colors) }
                    row("calibration-fov") { NumericSetting(strings.text("Огляд", "FOV"), calibration.fov, "°", colors,
                        { profileNumber(ProfileNumber.Fov, it) }, 60f..170f, adjustable = true, slider = true,
                        enabled = true,
                        identity = identityFor(NumberTarget.Profile(profile, ProfileNumber.Fov)),
                        onPreview = { value, session -> profilePreview(ProfileNumber.Fov, value, session) },
                        onCommitSession = { value, session -> profileNumber(ProfileNumber.Fov, value, session) }) }
                    row("calibration-projection") { ChoiceField(strings.text("Проєкція", "Projection"),
                        listOf(strings.text("Прямолінійна", "Rectilinear"), strings.text("Циліндрична", "Cylindrical")),
                        calibration.projection, { onAction(BydExtendUiAction.Select(
                            SelectionTarget.Profile(SelectionId.ProfileProjection, profile), it)) }, colors,
                        enabled = true) }
                    row("calibration-corrected-crop") { CropControls(profile, calibration.corrected, ProfileNumber.CorrectedX, ProfileNumber.CorrectedY,
                        ProfileNumber.CorrectedWidth, ProfileNumber.CorrectedHeight, strings, colors, onAction,
                        identityFor, enabled = !calibration.rawFallback) }
                    row("calibration-correction-reset") { ResetProfileButton(strings.text("Скинути корекцію", "Reset correction"),
                        command(CommandId.ResetProfileCorrection, CommandId.MirrorResetCorrection), profile,
                        colors, onAction) }
                }
                CalibrationStage.Output -> {
                    row("calibration-mirror") { SwitchLine(strings.text("Віддзеркалити", "Mirror"), "", calibration.mirrored,
                        { onAction(BydExtendUiAction.Toggle(ToggleTarget.Profile(ToggleId.ProfileMirror, profile), it)) }, colors) }
                    row("calibration-output-mode") { ChoiceField(strings.text("Режим повороту", "Rotation mode"),
                        listOf(strings.text("Вписати", "Fit"), strings.text("Заповнити", "Fill"),
                            strings.text("Розтягнути", "Stretch")), calibration.outputMode,
                        { onAction(BydExtendUiAction.Select(
                            SelectionTarget.Profile(SelectionId.ProfileOutputMode, profile), it)) }, colors) }
                    row("calibration-rotation") { NumericSetting(strings.text("Поворот", "Rotation"), calibration.rotation, "°", colors,
                        { profileNumber(ProfileNumber.Rotation, it) }, -180f..180f, adjustable = true, slider = true,
                        identity = identityFor(NumberTarget.Profile(profile, ProfileNumber.Rotation)),
                        onPreview = { value, session -> profilePreview(ProfileNumber.Rotation, value, session) },
                        onCommitSession = { value, session -> profileNumber(ProfileNumber.Rotation, value, session) }) }
                    row("calibration-border") { CameraBorderControls(profile, state, strings, colors, onAction,
                        identityFor(NumberTarget.Profile(profile, ProfileNumber.Fov)) to "calibration-border") }
                    row("calibration-output-reset") { ResetProfileButton(strings.text("Скинути вивід", "Reset output"),
                        command(CommandId.ResetProfileOutput, CommandId.MirrorResetOutput), profile,
                        colors, onAction) }
                }
            }
        }
    }
    return this
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
        CameraSection.Parameters -> CameraStageFrame(
            strings.text("Попередній перегляд", "Preview"), state.frameAspect, colors) {
            cameraHost(CameraHostSlot(
                if (profile == CameraProfileId.Mirror) CameraHostKind.Mirror else CameraHostKind.Placement,
                profile, sourceIndex = sourceIndex))
        }
        CameraSection.Placement -> if (
            profile is CameraProfileId.Blind || profile is CameraProfileId.Parking ||
                profile is CameraProfileId.Mirror
        ) CameraPlacementPreview(profile, sourceIndex, state, colors, cameraHost, onMove = { x, y ->
            onAction(BydExtendUiAction.MoveProfile(profile, x, y, state.target))
        }, onResize = { x, y, width, height ->
            if (profile is CameraProfileId.Blind) {
                onAction(BydExtendUiAction.SetProfileGeometry(profile, MirrorGeometryUiState(
                    placementPercent(x), placementPercent(y),
                    placementPercent(width), placementPercent(height)), state.target))
            }
        }) else CameraStageFrame(strings.text("Розташування", "Placement"), state.displayGeometry.aspect, colors) {
            cameraHost(CameraHostSlot(
                if (profile == CameraProfileId.Mirror) CameraHostKind.Mirror else CameraHostKind.Placement,
                profile, sourceIndex = sourceIndex, editable = true))
        }
        CameraSection.Calibration -> Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            key(CameraHostKind.CalibrationOriginal) {
                CameraStageFrame(strings.text("Оригінал", "Original"), SOURCE_CAMERA_ASPECT, colors, Modifier.weight(1f)) {
                    cameraHost(CameraHostSlot(CameraHostKind.CalibrationOriginal, profile, sourceIndex = sourceIndex))
                }
            }
            if (state.calibration.correctionEnabled) key(CameraHostKind.CalibrationCorrected) {
                CameraStageFrame(strings.text("Корекція", "Correction"), SOURCE_CAMERA_ASPECT,
                    colors, Modifier.weight(1f)) {
                    cameraHost(CameraHostSlot(CameraHostKind.CalibrationCorrected, profile, sourceIndex = sourceIndex))
                }
            }
            key(CameraHostKind.CalibrationOutput) {
                CameraStageFrame(strings.text("Вивід", "Output"), state.frameAspect, colors, Modifier.weight(1f)) {
                    cameraHost(CameraHostSlot(CameraHostKind.CalibrationOutput, profile, sourceIndex = sourceIndex))
                }
            }
        }
    }
}

private fun placementPercent(value: Float): String {
    val bounded = (value * 100f).coerceIn(0f, 100f)
    return if (bounded == bounded.toInt().toFloat()) bounded.toInt().toString()
    else java.lang.String.format(java.util.Locale.US, "%.1f", bounded)
        .trimEnd('0').trimEnd('.')
}

@Composable
internal fun CoordinatePair(x: String, y: String, colors: UiPalette,
    onX: (String) -> Unit, onY: (String) -> Unit,
    identityX: Any = Unit, identityY: Any = Unit, enabled: Boolean = true,
    maxX: Float = 100f, maxY: Float = 100f,
    horizontalTitle: String = "Горизонталь", verticalTitle: String = "Вертикаль") {
    GeometryPair(horizontalTitle, x, onX, verticalTitle, y, onY, colors,
        0f..maxX.coerceIn(0f, 100f), "coordinate-pair",
        secondRange = 0f..maxY.coerceIn(0f, 100f),
        identityFirst = identityX, identitySecond = identityY, enabled = enabled)
}

@Composable
internal fun GeometryPair(
    firstTitle: String, firstValue: String, onFirstChange: (String) -> Unit,
    secondTitle: String, secondValue: String, onSecondChange: (String) -> Unit,
    colors: UiPalette, range: ClosedFloatingPointRange<Float>, tag: String,
    secondRange: ClosedFloatingPointRange<Float> = range,
    identityFirst: Any = Unit, identitySecond: Any = Unit, enabled: Boolean = true,
) {
    Row(Modifier.fillMaxWidth().testTag(tag), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        GeometryField(firstTitle, firstValue, onFirstChange, colors, range,
            Modifier.weight(1f), identityFirst, enabled)
        GeometryField(secondTitle, secondValue, onSecondChange, colors, secondRange,
            Modifier.weight(1f), identitySecond, enabled)
    }
}

@Composable
private fun GeometryField(
    title: String, value: String, onValueChange: (String) -> Unit,
    colors: UiPalette, range: ClosedFloatingPointRange<Float>, modifier: Modifier,
    identity: Any, enabled: Boolean,
) {
    Column(modifier.clip(RoundedCornerShape(8.dp))
        .border(1.dp, colors.borderStrong, RoundedCornerShape(8.dp))
        .background(colors.panelAlt).padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, color = colors.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            fontStyle = FontStyle.Italic, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth())
        NumericSetting(title, value, "%", colors, onValueChange, range,
            enabled = enabled, adjustable = true, inlineLabel = true, showLabel = false, compactSuffix = true,
            identity = identity)
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
    identityFor: (NumberTarget) -> Any = { it },
    enabled: Boolean = true,
) {
    fun commit(field: ProfileNumber, value: String) {
        onAction(BydExtendUiAction.CommitNumber(NumberTarget.Profile(profile, field), value))
    }
    val width = (crop.width.toFloatOrNull() ?: 100f).coerceIn(1f, 100f)
    val height = (crop.height.toFloatOrNull() ?: 100f).coerceIn(1f, 100f)
    val x = (crop.x.toFloatOrNull() ?: 0f).coerceIn(0f, 100f)
    val y = (crop.y.toFloatOrNull() ?: 0f).coerceIn(0f, 100f)
    CoordinatePair(crop.x, crop.y, colors, { commit(xField, it) }, { commit(yField, it) },
        identityFor(NumberTarget.Profile(profile, xField)),
        identityFor(NumberTarget.Profile(profile, yField)), enabled,
        maxX = 100f - width, maxY = 100f - height,
        horizontalTitle = strings.text("Горизонталь", "Horizontal"),
        verticalTitle = strings.text("Вертикаль", "Vertical"))
    GeometryPair(strings.text("Ширина", "Width"), crop.width,
        { commit(widthField, it) }, strings.text("Висота", "Height"), crop.height,
        { commit(heightField, it) }, colors, 1f..(100f - x).coerceAtLeast(1f), "size-pair",
        secondRange = 1f..(100f - y).coerceAtLeast(1f),
        identityFirst = identityFor(NumberTarget.Profile(profile, widthField)),
        identitySecond = identityFor(NumberTarget.Profile(profile, heightField)), enabled = enabled)
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
        ActionButton(label, colors, Modifier.width(260.dp), icon = Icons.Outlined.Refresh,
            mainBackground = true) {
            onAction(BydExtendUiAction.Run(command, profile))
        }
    }
}
