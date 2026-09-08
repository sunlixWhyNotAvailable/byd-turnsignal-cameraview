package com.byd.extend.ui

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role

/** Production rearview-mirror editor. All controls dispatch typed backend intents. */
@Composable
internal fun MirrorScreen(
    state: MirrorUiState,
    strings: UiStrings,
    colors: UiPalette,
    onAction: (BydExtendUiAction) -> Unit,
    cameraHost: @Composable (CameraHostSlot) -> Unit,
    onPreview: (NumberTarget, String, Long) -> String? = { _, value, _ -> value },
) {
    val profile = CameraProfileId.Mirror
    var pickingBorderColor by rememberSaveable { mutableStateOf(false) }
    ScreenSurface(colors, scroll = false, compact = true) {
        CameraWorkspace(
            pageTab = RootTab.Mirror.pageIndex(),
            section = state.section,
            reverse = false,
            calibrationEnabled = true,
            previewTitle = strings.text("ПЕРЕГЛЯД • ДЗЕРКАЛО ЗАДНЬОГО ВИДУ", "PREVIEW • REARVIEW MIRROR"),
            strings = strings,
            colors = colors,
            onSection = { onAction(BydExtendUiAction.Select(
                SelectionTarget.Simple(SelectionId.CameraSection), it.ordinal)) },
            profileStatus = state.profile.operation.status,
            profileControls = {
                MirrorProfileHeader(state, strings, colors, onAction)
            },
            controls = {
                CameraProfileControls(profile, state.profile, state.section, true, strings, colors, onAction,
                    onPreview = onPreview,
                    parameters = {
                        MirrorParameters(state, strings, colors, onAction,
                            onPickBorderColor = { pickingBorderColor = true })
                    })
            },
            preview = {
                if (state.section != CameraSection.Calibration) {
                    CameraPlacementPreview(profile, 1, state.profile, colors, cameraHost,
                        onMove = { x, y -> onAction(BydExtendUiAction.MoveProfile(profile, x, y)) },
                        onResize = { x, y, width, height ->
                            onAction(BydExtendUiAction.SetMirrorGeometry(MirrorGeometryUiState(
                                x = percent(x), y = percent(y), width = percent(width), height = percent(height))))
                        },
                        editable = state.section == CameraSection.Placement)
                } else {
                    CameraProfilePreview(profile, 1, state.profile, state.section, strings, colors, onAction, cameraHost)
                }
            },
        )
    }
    if (pickingBorderColor) MirrorBorderColorPicker(
        colors = colors,
        strings = strings,
        initial = state.borderArgb,
        onDismiss = { pickingBorderColor = false },
        onSelect = { color ->
            onAction(BydExtendUiAction.SetMirrorBorderColor(color))
            pickingBorderColor = false
        },
    )
}

@Composable
private fun ColumnScope.MirrorProfileHeader(
    state: MirrorUiState,
    strings: UiStrings,
    colors: UiPalette,
    onAction: (BydExtendUiAction) -> Unit,
) {
    SwitchLine(strings.text("Дзеркало заднього виду", "Rearview mirror"),
        strings.text("Окремий віджет задньої камери", "Independent rear-camera widget"),
        state.enabled,
        { onAction(BydExtendUiAction.Toggle(ToggleTarget.Simple(ToggleId.MirrorEnabled), it)) },
        colors, pending = state.operation.pending, enabled = state.operation.enabled,
        compactSwitch = false)
    SwitchLine(
        strings.text(
            "Не показувати під час панорамного виду",
            "Do not show while panorama is open",
            "全景视图开启时不显示",
        ), "", state.suppressWhilePanorama,
        { onAction(BydExtendUiAction.Toggle(
            ToggleTarget.Simple(ToggleId.MirrorSuppressWhilePanorama), it)) }, colors,
    )
    ProfilePresetButtons(CameraProfileId.Mirror, state.presetAvailable, true, strings, colors, onAction)
}

@Composable
private fun ColumnScope.MirrorParameters(
    state: MirrorUiState,
    strings: UiStrings,
    colors: UiPalette,
    onAction: (BydExtendUiAction) -> Unit,
    onPickBorderColor: () -> Unit,
) {
    NumericSetting(strings.text("Товщина рамки", "Border width", "边框宽度"), state.borderWidth, "dp", colors,
        { onAction(BydExtendUiAction.CommitNumber(NumberTarget.Mirror(MirrorNumber.BorderWidth), it)) },
        0f..16f, adjustable = true, slider = true,
        identity = NumberTarget.Mirror(MirrorNumber.BorderWidth))
    Text(strings.text("0 — без рамки", "0 removes the border", "0 表示无边框"),
        color = colors.muted, fontSize = 12.sp)
    MirrorBorderColorLine(colors, strings, state.borderArgb, onPickBorderColor)
    Text(strings.text(
        "Постійний віджет задньої камери поза BYD Extend. Поки застосунок відкритий, віджет приховано.",
        "A persistent rear-camera widget outside BYD Extend. It is hidden while the application is open.",
        "在 BYD Extend 外持续显示后摄像头悬浮窗。应用打开时隐藏悬浮窗。"),
        color = colors.text, fontSize = 13.sp)
    Text(strings.text(
        "Перетягніть, щоб перемістити. Утримуйте, щоб приховати до наступного відкриття застосунку. Натискання крізь камеру не проходять.",
        "Drag to move. Touch and hold to hide until the next app opening. Taps do not pass through the camera.",
        "拖动可移动，长按可隐藏，直到下次打开应用。点击不会穿透摄像头窗口。"),
        color = colors.muted, fontSize = 13.sp)
    if (!state.overlayPermissionGranted) {
        ActionButton(strings.text("Дозволити показ поверх інших застосунків",
            "Allow display over other apps", "允许显示在其他应用上层"), colors,
            Modifier.fillMaxWidth(), maxLines = 2) {
            onAction(BydExtendUiAction.RequestMirrorOverlayPermission)
        }
    }
    if (state.target == DisplayTarget.Cluster && !state.clusterAvailable) {
        Text(strings.text(
            "Другий дисплей недоступний. Розташування на приборці показане лише тут; віджет не переноситься на планшет.",
            "No secondary display is available. Cluster placement is shown here only; the widget will not move to the tablet.",
            "无可用的第二显示屏。这里只模拟仪表屏布局，悬浮窗不会改在中控屏显示。"),
            color = colors.yellow, fontSize = 12.sp)
    }
}

@Composable
private fun MirrorBorderColorLine(
    colors: UiPalette,
    strings: UiStrings,
    argb: Int,
    onPick: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(strings.text("Колір рамки", "Border color", "边框颜色"), color = colors.text,
            fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Box(Modifier.size(52.dp, 44.dp).clip(RoundedCornerShape(7.dp))
            .background(colors.field).border(1.dp, colors.borderStrong, RoundedCornerShape(7.dp)),
            contentAlignment = Alignment.Center) {
            Box(Modifier.size(28.dp).background(Color(argb), RoundedCornerShape(4.dp))
                .border(1.dp, colors.borderStrong, RoundedCornerShape(4.dp)))
        }
        ActionButton("", colors, Modifier.size(44.dp).semantics {
            contentDescription = strings.text("Колір рамки", "Border color", "边框颜色")
        }, icon = Icons.Outlined.Palette, onClick = onPick)
    }
}

@Composable
private fun MirrorBorderColorPicker(
    colors: UiPalette,
    strings: UiStrings,
    initial: Int,
    onDismiss: () -> Unit,
    onSelect: (Int) -> Unit,
) {
    fun hex(value: Int) = "%06X".format(java.util.Locale.ROOT, value and 0xFFFFFF)
    var picked by remember(initial) { mutableIntStateOf(initial or 0xFF000000.toInt()) }
    var hexDraft by remember(initial) { mutableStateOf(hex(initial)) }
    fun update(value: Int) {
        picked = value or 0xFF000000.toInt()
        hexDraft = hex(picked)
    }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.width(560.dp).heightIn(max = 560.dp).clip(RoundedCornerShape(8.dp))
            .background(colors.surface).border(1.dp, colors.borderStrong, RoundedCornerShape(8.dp))
            .verticalScroll(rememberScrollState()).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(strings.text("Колір рамки", "Border color", "边框颜色"), color = colors.text,
                fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.size(48.dp).background(Color(picked), RoundedCornerShape(7.dp))
                    .border(1.dp, colors.borderStrong, RoundedCornerShape(7.dp)))
                BasicTextField(hexDraft, { raw ->
                    val filtered = raw.removePrefix("#")
                        .filter { it in '0'..'9' || it.uppercaseChar() in 'A'..'F' }.take(6).uppercase()
                    hexDraft = filtered
                    if (filtered.length == 6) filtered.toIntOrNull(16)?.let { picked = it or 0xFF000000.toInt() }
                }, singleLine = true,
                    textStyle = TextStyle(color = colors.text, fontSize = 16.sp, fontFamily = FontFamily.Monospace),
                    modifier = Modifier.width(112.dp).height(44.dp).clip(RoundedCornerShape(7.dp))
                        .background(colors.field).border(1.dp, colors.borderStrong, RoundedCornerShape(7.dp))
                        .semantics { contentDescription = strings.text("Колір HEX", "Hex color", "十六进制颜色") }
                        .padding(horizontal = 10.dp),
                    decorationBox = { field -> Row(Modifier.fillMaxHeight(),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("#", color = colors.muted, fontSize = 16.sp); field()
                    } })
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
                listOf(colors.accent, colors.green, colors.yellow, colors.red,
                    Color.Gray, Color.White, Color.Black).forEach { color ->
                    val press = rememberPressFeedback()
                    val click = rememberVisualFirstClick { update(color.toArgb()) }
                    Box(Modifier.size(36.dp).then(press.modifier).clip(RoundedCornerShape(6.dp))
                        .background(pressBackground(color, colors, press.pressed))
                        .border(if (picked == color.toArgb()) 3.dp else 1.dp, colors.borderStrong,
                            RoundedCornerShape(6.dp))
                        .semantics { contentDescription = "#${hex(color.toArgb())}" }
                        .clickable(interactionSource = press.interactionSource, indication = null,
                            role = Role.Button, onClick = click))
                }
            }
            CompositionLocalProvider(LocalCompactControls provides true) {
                listOf(16 to "R", 8 to "G", 0 to "B").forEach { (shift, label) ->
                    NumericSetting(label, ((picked ushr shift) and 255).toString(), "", colors,
                        { value -> update((picked and (255 shl shift).inv()) or
                            (value.toFloat().toInt().coerceIn(0, 255) shl shift)) },
                        range = 0f..255f, adjustable = true, slider = true,
                        identity = "mirror-color-$label-${initial}")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ActionButton(strings.text("Обрати", "Select", "选择"), colors,
                    primary = true, modifier = Modifier.weight(1f)) { onSelect(picked) }
                ActionButton(strings.text("Скасувати", "Cancel", "取消"), colors,
                    modifier = Modifier.weight(1f), onClick = onDismiss)
            }
        }
    }
}

private fun percent(value: Float): String {
    val bounded = (value * 100f).coerceIn(0f, 100f)
    return if (bounded == bounded.toInt().toFloat()) bounded.toInt().toString()
    else java.lang.String.format(java.util.Locale.US, "%.1f", bounded).trimEnd('0').trimEnd('.')
}

private fun RootTab.pageIndex(): Int = when (this) {
    RootTab.Signals -> 0
    RootTab.Blind -> 1
    RootTab.Parking -> 2
    RootTab.Reverse -> 3
    RootTab.Mirror -> 4
    RootTab.Settings -> 5
    RootTab.Debug -> 6
}
