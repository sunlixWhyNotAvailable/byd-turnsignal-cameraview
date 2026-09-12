package com.byd.extend.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.byd.extend.UpdateHintAppearance
import com.byd.extend.UpdateHintCardView

@Composable
internal fun UpdateHintSettingsButton(strings: UiStrings, colors: UiPalette, onClick: () -> Unit) {
    val press = rememberPressFeedback()
    val visualClick = rememberVisualFirstClick(onClick)
    Box(Modifier.size(42.dp).clip(RoundedCornerShape(7.dp))
        .border(1.dp, colors.accent.copy(alpha = .85f), RoundedCornerShape(7.dp))
        .background(pressBackground(colors.accent.copy(alpha = if (colors.dark) .20f else .12f),
            colors, press.pressed)).then(press.modifier)
        .clickable(interactionSource = press.interactionSource, indication = null,
            role = Role.Button, onClick = visualClick).padding(6.dp), contentAlignment = Alignment.Center) {
        Icon(Icons.Outlined.Settings,
            strings.text("Налаштування віджету-підказки", "Update hint widget settings", "更新提示悬浮窗设置"),
            tint = if (press.pressed) Color.White else colors.accent, modifier = Modifier.size(28.dp))
    }
}

@Composable
internal fun UpdateHintSettingsDialog(
    strings: UiStrings,
    colors: UiPalette,
    language: UiLanguage,
    darkTheme: Boolean,
    appearance: UpdateHintAppearance,
    onAppearanceChange: (UpdateHintAppearance) -> Unit,
    onClose: () -> Unit,
) {
    var choosingColor by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().padding(18.dp), contentAlignment = Alignment.Center) {
            Column(Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)).background(colors.surface)
                .border(1.dp, colors.borderStrong, RoundedCornerShape(8.dp)).padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(strings.text("Налаштування віджету-підказки", "Update hint widget settings", "更新提示悬浮窗设置"),
                    color = colors.text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    Column(Modifier.weight(.4f).fillMaxHeight().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        UpdateHintNumber(
                            strings.text("Прозорість", "Transparency", "透明度"),
                            strings.text("0% — видимий, 100% — невидимий",
                                "0% visible, 100% invisible", "0% 可见，100% 不可见"),
                            appearance.transparencyPercent, UpdateHintAppearance.TRANSPARENCY_RANGE, "%",
                            colors, false, "hint-transparency",
                        ) { onAppearanceChange(appearance.copy(transparencyPercent = it)) }
                        UpdateHintNumber(strings.text("Заокруглення країв", "Corner rounding", "圆角"), "",
                            appearance.cornerRadiusDp, UpdateHintAppearance.CORNER_RANGE, "dp", colors, true,
                            "hint-corner") { onAppearanceChange(appearance.copy(cornerRadiusDp = it)) }
                        UpdateHintNumber(
                            strings.text("Ширина рамки", "Border width", "边框宽度"),
                            strings.text("0 — прибрати рамку", "0 removes the border", "0 表示无边框"),
                            appearance.borderWidthDp, UpdateHintAppearance.BORDER_RANGE, "dp", colors, true,
                            "hint-border") { onAppearanceChange(appearance.copy(borderWidthDp = it)) }
                        UpdateHintColorLine(strings, colors, appearance.borderArgb) { choosingColor = true }
                        UpdateHintNumber(strings.text("Розмір віджету", "Widget size", "悬浮窗大小"), "",
                            appearance.sizePercent, UpdateHintAppearance.SIZE_RANGE, "%", colors, false,
                            "hint-size") { onAppearanceChange(appearance.copy(sizePercent = it)) }
                    }
                    Column(Modifier.weight(.6f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(strings.text("Передпоказ · 1:1", "Preview · 1:1", "预览 · 1:1"),
                            color = colors.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        UpdateHintSample(language, darkTheme, colors, appearance,
                            Modifier.weight(1f).fillMaxWidth())
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    ActionButton(strings.text("Закрити", "Close", "关闭"), colors,
                        Modifier.width(160.dp), onClick = onClose)
                }
            }
        }
    }
    if (choosingColor) UpdateHintColorPicker(strings, colors, appearance.borderArgb,
        onDismiss = { choosingColor = false },
        onSelect = {
            onAppearanceChange(appearance.copy(borderArgb = it))
            choosingColor = false
        })
}

@Composable
private fun UpdateHintNumber(
    title: String,
    hint: String,
    value: Int,
    range: IntRange,
    suffix: String,
    colors: UiPalette,
    ticks: Boolean,
    identity: String,
    onChange: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (hint.isNotBlank()) Text(hint, color = colors.muted, fontSize = 12.sp)
        CompositionLocalProvider(LocalCompactControls provides true) {
            NumericSetting(title, value.toString(), suffix, colors,
                onCommit = { it.toFloatOrNull()?.toInt()?.coerceIn(range)?.let(onChange) },
                range = range.first.toFloat()..range.last.toFloat(), adjustable = true, slider = true,
                sliderDots = ticks, identity = identity,
                onPreview = { raw, _ ->
                    raw.toFloatOrNull()?.toInt()?.coerceIn(range)?.let(onChange)
                    raw
                })
        }
    }
}

@Composable
private fun UpdateHintSample(
    language: UiLanguage,
    darkTheme: Boolean,
    colors: UiPalette,
    appearance: UpdateHintAppearance,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val width = with(LocalDensity.current) { UpdateHintCardView.widthPx(context, appearance).toDp() }
    Box(modifier.clip(RoundedCornerShape(8.dp)).background(colors.background)
        .border(1.dp, colors.border, RoundedCornerShape(8.dp))) {
        Box(Modifier.fillMaxSize().horizontalScroll(rememberScrollState()).verticalScroll(rememberScrollState())) {
            AndroidView(factory = { UpdateHintCardView(it) }, update = {
                it.bind("1.2.2", language, darkTheme, appearance)
            }, modifier = Modifier.padding(18.dp).width(width))
        }
    }
}

@Composable
private fun UpdateHintColorLine(
    strings: UiStrings,
    colors: UiPalette,
    argb: Int,
    onPick: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(strings.text("Колір рамки", "Border color", "边框颜色"), color = colors.text,
            fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(7.dp)).background(colors.field)
            .border(1.dp, colors.borderStrong, RoundedCornerShape(7.dp)), contentAlignment = Alignment.Center) {
            Box(Modifier.size(28.dp).background(Color(argb), RoundedCornerShape(4.dp))
                .border(1.dp, colors.borderStrong, RoundedCornerShape(4.dp)))
        }
        Spacer(Modifier.width(8.dp))
        ActionButton("", colors, Modifier.size(44.dp).semantics {
            contentDescription = strings.text("Колір рамки", "Border color", "边框颜色")
        }, icon = Icons.Outlined.Palette, onClick = onPick)
    }
}

@Composable
private fun UpdateHintColorPicker(
    strings: UiStrings,
    colors: UiPalette,
    initial: Int,
    onDismiss: () -> Unit,
    onSelect: (Int) -> Unit,
) {
    fun hex(value: Int) = "%06X".format(java.util.Locale.ROOT, value and 0xFFFFFF)
    var picked by remember(initial) { mutableIntStateOf(initial or 0xFF000000.toInt()) }
    var hexDraft by remember(initial) { mutableStateOf(hex(initial)) }
    var hexFocused by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    fun update(value: Int) {
        picked = value or 0xFF000000.toInt()
        hexDraft = hex(picked)
    }
    fun finish() {
        hexDraft.takeIf { it.length == 6 }?.toIntOrNull(16)?.let(::update)
            ?: run { hexDraft = hex(picked) }
        hexFocused = false
        keyboard?.hide()
        focusManager.clearFocus()
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        ImeDismissalEffect(hexFocused, ::finish)
        Column(Modifier.width(560.dp).heightIn(max = 600.dp).clip(RoundedCornerShape(8.dp))
            .background(colors.surface).border(1.dp, colors.borderStrong, RoundedCornerShape(8.dp))
            .verticalScroll(rememberScrollState()).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(strings.text("Колір рамки", "Border color", "边框颜色"), color = colors.text,
                fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.size(48.dp).background(Color(picked), RoundedCornerShape(7.dp))
                    .border(1.dp, colors.borderStrong, RoundedCornerShape(7.dp)))
                BasicTextField(hexDraft, { raw ->
                    hexDraft = raw.removePrefix("#")
                        .filter { it.isDigit() || it.uppercaseChar() in 'A'..'F' }.take(6)
                        .uppercase(java.util.Locale.ROOT)
                    if (hexDraft.length == 6) hexDraft.toIntOrNull(16)?.let { picked = it or 0xFF000000.toInt() }
                }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { finish() }), cursorBrush = SolidColor(colors.accent),
                    textStyle = TextStyle(color = colors.text, fontSize = 16.sp,
                        fontFamily = FontFamily.Monospace),
                    modifier = Modifier.width(112.dp).height(44.dp).clip(RoundedCornerShape(7.dp))
                        .background(colors.field).border(1.dp, colors.borderStrong, RoundedCornerShape(7.dp))
                        .onFocusChanged { hexFocused = it.isFocused }.padding(horizontal = 10.dp),
                    decorationBox = { field -> Row(Modifier.fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
                        Text("#", color = colors.muted, fontSize = 16.sp); field()
                    } })
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
                listOf(colors.accent, colors.green, colors.yellow, colors.red,
                    Color.Gray, Color.White, Color.Black).forEach { color ->
                    val press = rememberPressFeedback()
                    Box(Modifier.size(36.dp).then(press.modifier).clip(RoundedCornerShape(6.dp))
                        .background(pressBackground(color, colors, press.pressed))
                        .border(if (picked == color.toArgb()) 3.dp else 1.dp, colors.borderStrong,
                            RoundedCornerShape(6.dp)).semantics { contentDescription = "#${hex(color.toArgb())}" }
                        .clickable(interactionSource = press.interactionSource, indication = null,
                            role = Role.Button) { update(color.toArgb()) })
                }
            }
            CompositionLocalProvider(LocalCompactControls provides true) {
                listOf(16 to "R", 8 to "G", 0 to "B").forEach { (shift, label) ->
                    NumericSetting(label, ((picked ushr shift) and 255).toString(), "", colors,
                        { value -> update((picked and (255 shl shift).inv()) or
                            ((value.toFloatOrNull()?.toInt() ?: 0).coerceIn(0, 255) shl shift)) },
                        range = 0f..255f, adjustable = true, slider = true,
                        identity = "hint-color-$label-$initial")
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ActionButton(strings.text("Обрати", "Select", "选择"), colors, Modifier.weight(1f),
                    primary = true) { onSelect(picked) }
                ActionButton(strings.text("Скасувати", "Cancel", "取消"), colors, Modifier.weight(1f),
                    onClick = onDismiss)
            }
        }
    }
}
