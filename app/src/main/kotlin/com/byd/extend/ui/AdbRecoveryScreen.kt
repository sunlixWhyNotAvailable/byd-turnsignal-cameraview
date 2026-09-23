package com.byd.extend.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.OpenInFull
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.byd.extend.AdbReminderAppearance
import kotlin.math.roundToInt

@Composable
internal fun FormScope.AdbRecoveryScreen(
    state: AdbRecoveryUiState,
    strings: UiStrings,
    colors: UiPalette,
    onAction: (AdbRecoveryUiAction) -> Unit,
): FormScope {
    var editAppearance by remember { mutableStateOf(false) }
    var chooseColor by remember { mutableStateOf(false) }
    val update: (AdbReminderAppearance) -> Unit = {
        onAction(AdbRecoveryUiAction.UpdateReminderAppearance(it.normalized()))
    }
    FormSection(strings.text("Відновлення ADB", "ADB recovery", "ADB 恢复"), colors,
        key = "adb-recovery") {
        row("notice") { Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
            .background(colors.accent.copy(alpha = if (colors.dark) .22f else .12f))
            .border(1.dp, colors.yellow.copy(alpha = .55f), RoundedCornerShape(8.dp))
            .padding(14.dp)) {
            Text(strings.text(
                "Відновлення працює лише для ADB, який уже було відкрито й авторизовано. Воно не може виконати перше відкриття або першу авторизацію ADB.",
                "Recovery works only for ADB that was already opened and authorized. It cannot perform the first ADB opening or authorization.",
                "恢复仅适用于先前已开启并授权的 ADB，无法完成首次开启或首次授权。",
            ), color = if (colors.dark) colors.yellow else colors.text, fontSize = 16.sp,
                lineHeight = 22.sp)
        } }
        row("enabled") { SwitchLine(
            strings.text("Автоматичне відновлення", "Automatic recovery", "自动恢复"),
            strings.text("Відновлювати локальне підключення ADB на порту 5555",
                "Restore the local ADB connection on port 5555", "恢复端口 5555 上的本地 ADB 连接"),
            state.enabled,
            { onAction(AdbRecoveryUiAction.SetRecoveryEnabled(it)) },
            colors,
        ) }
        row("status") { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(strings.text("Стан", "Status", "状态"), color = colors.muted, fontSize = 13.sp)
            StatusPill(stageState(state, strings), "ADB", colors)
        } }
        row("retry") { ActionButton(
            strings.text("Повторити запит Wi-Fi", "Retry Wi-Fi request", "重试 Wi-Fi 请求"),
            colors, Modifier.fillMaxWidth(), icon = Icons.Outlined.Refresh,
            enabled = state.enabled && !state.authenticated5555,
        ) { onAction(AdbRecoveryUiAction.Retry) } }
    }
    row("adb-gap") { Spacer(Modifier.height(12.dp)) }
    FormSection(strings.text("Нагадування про Wi-Fi", "Wi-Fi reminder", "Wi-Fi 提醒"), colors,
        key = "adb-reminder") {
        row("enabled") { SwitchLine(
            strings.text("Показувати нагадування", "Show reminder", "显示提醒"),
            strings.text("Показати віджет під час очікування Wi-Fi",
                "Show the widget while waiting for Wi-Fi", "等待 Wi-Fi 时显示悬浮窗"),
            state.appearance.widgetEnabled,
            { onAction(AdbRecoveryUiAction.SetReminderEnabled(it)) }, colors,
        ) }
        val reminderEnabled = state.appearance.widgetEnabled
        row("delay") { NumericSetting(strings.text("Затримка", "Delay", "延迟"),
            state.appearance.delaySeconds.toString(), strings.text("с", "s", "秒"), colors,
            { update(state.appearance.copy(delaySeconds = it.toFloat().roundToInt())) },
            0f..120f, enabled = reminderEnabled, adjustable = true,
            identity = "adb-reminder-delay") }
        row("opacity") { NumericSetting(strings.text("Непрозорість", "Opacity", "不透明度"),
            state.appearance.opacityPercent.toString(), "%", colors,
            { update(state.appearance.copy(opacityPercent = it.toFloat().roundToInt())) },
            0f..100f, enabled = reminderEnabled, slider = true,
            identity = "adb-reminder-opacity") }
        row("width") { NumericSetting(strings.text("Ширина", "Width", "宽度"),
            state.appearance.widthPercent.toString(), "%", colors,
            { update(state.appearance.copy(widthPercent = it.toFloat().roundToInt())) },
            30f..95f, enabled = reminderEnabled, slider = true,
            identity = "adb-reminder-width") }
        row("radius") { NumericSetting(strings.text("Радіус кутів", "Corner radius", "圆角半径"),
            state.appearance.cornerRadiusDp.toString(), "dp", colors,
            { update(state.appearance.copy(cornerRadiusDp = it.toFloat().roundToInt())) },
            0f..48f, enabled = reminderEnabled, slider = true,
            identity = "adb-reminder-radius") }
        row("frame") { SwitchLine(strings.text("Рамка", "Frame", "边框"), "", state.appearance.borderEnabled,
            { update(state.appearance.copy(borderEnabled = it)) }, colors,
            enabled = reminderEnabled) }
        val frameEnabled = reminderEnabled && state.appearance.borderEnabled
        row("frame-width") { NumericSetting(strings.text("Товщина рамки", "Frame thickness", "边框粗细"),
            state.appearance.borderThicknessDp.toString(), "dp", colors,
            { update(state.appearance.copy(borderThicknessDp = it.toFloat().roundToInt())) },
            1f..12f, enabled = frameEnabled, slider = true,
            identity = "adb-reminder-border-width") }
        row("frame-color") { ReminderColorLine(strings, colors, state.appearance.borderArgb,
            enabled = frameEnabled) { chooseColor = true } }
        row("editor") { ActionButton(strings.text("Вигляд і розташування віджета",
            "Widget appearance and placement", "悬浮窗外观和位置"), colors,
            Modifier.fillMaxWidth(), icon = Icons.Outlined.OpenInFull,
            enabled = state.enabled && reminderEnabled) { editAppearance = true } }
    }
    if (chooseColor) ReminderColorPicker(strings, colors, state.appearance.borderArgb,
        onDismiss = { chooseColor = false }) {
        update(state.appearance.copy(borderArgb = it)); chooseColor = false
    }
    if (editAppearance && state.enabled && state.appearance.widgetEnabled) {
        AdbReminderEditor(strings, colors, state.appearance,
            onChange = update, onDismiss = { editAppearance = false })
    }
    return this
}

private fun stageState(state: AdbRecoveryUiState, strings: UiStrings): StatusUiState {
    if (!state.enabled) return StatusUiState(
        strings.text("Сервіс вимкнено", "Service disabled", "服务已关闭"), StatusTone.Neutral, true)
    val (text, tone) = when (state.stage) {
        AdbRecoveryStage.PREPARING -> strings.text("Підготовка", "Preparing", "正在准备") to StatusTone.Warning
        AdbRecoveryStage.AVAILABLE -> strings.text("ADB доступний", "ADB available", "ADB 可用") to StatusTone.Ok
        AdbRecoveryStage.WAIT_WIFI -> strings.text("Очікування Wi-Fi", "Waiting for Wi-Fi", "等待 Wi-Fi") to StatusTone.Warning
        AdbRecoveryStage.RESTORING -> strings.text("Відновлення", "Restoring", "正在恢复") to StatusTone.Warning
        AdbRecoveryStage.RESTORED -> strings.text("Відновлено", "Restored", "已恢复") to StatusTone.Ok
        AdbRecoveryStage.FAILED -> strings.text("Потрібна повторна спроба", "Retry required", "需要重试") to StatusTone.Warning
    }
    return StatusUiState(text, tone, true)
}

@Composable
private fun AdbReminderEditor(
    strings: UiStrings,
    colors: UiPalette,
    appearance: AdbReminderAppearance,
    onChange: (AdbReminderAppearance) -> Unit,
    onDismiss: () -> Unit,
) {
    var showRetry by rememberSaveable { mutableStateOf(false) }
    var hidden by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxWidth(.94f).fillMaxHeight(.94f).clip(RoundedCornerShape(12.dp))
            .background(colors.surface).border(1.dp, colors.borderStrong, RoundedCornerShape(12.dp))
            .padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(strings.text("Вигляд і розташування віджета", "Widget appearance and placement",
                "悬浮窗外观和位置"), color = colors.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Text(strings.text("Перетягніть віджет. Утримуйте 1 секунду, щоб приховати.",
                "Drag the widget. Hold for 1 second to hide it.", "拖动悬浮窗。长按 1 秒可隐藏。"),
                color = colors.muted, fontSize = 13.sp)
            SwitchLine(strings.text("Показати висувну кнопку повтору (редактор)",
                "Show expanding Retry action (editor)", "显示展开式重试按钮（编辑器）"),
                "", showRetry, { showRetry = it }, colors)
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().clipToBounds(),
                contentAlignment = Alignment.Center) {
                if (!hidden) {
                    val logicalWidth = 960.dp
                    val logicalHeight = 540.dp
                    val scale = minOf(1f, maxWidth / logicalWidth, maxHeight / logicalHeight)
                    val density = LocalDensity.current
                    CompositionLocalProvider(LocalDensity provides Density(
                        density.density * scale, density.fontScale)) {
                        ReminderCanvas(strings, colors, appearance, logicalWidth, showRetry, onChange) {
                            hidden = true
                        }
                    }
                } else Text(strings.text("Нагадування приховано", "Reminder hidden", "提醒已隐藏"),
                    color = colors.muted)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ActionButton(strings.text("Скинути розташування", "Reset placement", "重置位置"),
                    colors, Modifier.weight(1f), icon = Icons.Outlined.Refresh, mainBackground = true) {
                    onChange(appearance.copy(x = .5f, y = .5f)); hidden = false
                }
                ActionButton(strings.text("Закрити", "Close", "关闭"), colors, Modifier.weight(1f),
                    onClick = onDismiss)
            }
        }
    }
}

@Composable
private fun ReminderCanvas(
    strings: UiStrings,
    colors: UiPalette,
    appearance: AdbReminderAppearance,
    canvasWidth: androidx.compose.ui.unit.Dp,
    showRetry: Boolean,
    onChange: (AdbReminderAppearance) -> Unit,
    onHide: () -> Unit,
) {
    val density = LocalDensity.current.density
    val latestChange by rememberUpdatedState(onChange)
    val latestHide by rememberUpdatedState(onHide)
    val latestAppearance by rememberUpdatedState(appearance)
    val normalViewConfiguration = LocalViewConfiguration.current
    val holdConfiguration = remember(normalViewConfiguration) { object : ViewConfiguration by normalViewConfiguration {
        override val longPressTimeoutMillis = 1_000L
    } }
    Box(Modifier.size(canvasWidth, canvasWidth / (16f / 9f)).clip(RoundedCornerShape(8.dp))
        .background(colors.panelAlt).border(1.dp, colors.borderStrong, RoundedCornerShape(8.dp))) {
        val width = canvasWidth * appearance.widthPercent / 100f
        var measuredHeight by remember { mutableIntStateOf(0) }
        var draft by remember(appearance) { mutableStateOf(appearance) }
        val bodyHeight = with(LocalDensity.current) { measuredHeight.toDp() }
        val retryHeight = 44.dp
        val travelX = (canvasWidth - width).value.coerceAtLeast(0f)
        val travelY = (canvasWidth / (16f / 9f) - bodyHeight).value.coerceAtLeast(0f)
        val x = travelX * draft.x
        val y = travelY * draft.y
        val retryAbove = y + bodyHeight.value + retryHeight.value > canvasWidth.value / (16f / 9f)
        val bodyShape = RoundedCornerShape(appearance.cornerRadiusDp.dp)
        val retryOverlap = appearance.cornerRadiusDp.dp.coerceAtMost(bodyHeight / 2)
            .coerceAtMost(width / 2)
        val retryY = if (retryAbove) (y - retryHeight.value).coerceAtLeast(0f)
            else y + bodyHeight.value - retryOverlap.value
        val retryShape = if (retryAbove) RoundedCornerShape(
            topStart = appearance.cornerRadiusDp.dp, topEnd = appearance.cornerRadiusDp.dp)
        else RoundedCornerShape(bottomStart = appearance.cornerRadiusDp.dp,
            bottomEnd = appearance.cornerRadiusDp.dp)
        AnimatedVisibility(showRetry,
            modifier = Modifier.offset { IntOffset((x * density).roundToInt(), (retryY * density).roundToInt()) },
            enter = slideInVertically(tween(220)) { if (retryAbove) it else -it },
            exit = slideOutVertically(tween(220)) { if (retryAbove) it else -it }) {
            Box(Modifier.width(width).height(retryHeight + retryOverlap).clipToBounds()
                .clip(retryShape)
                .background(colors.background.copy(alpha = appearance.alpha))
                .drawWithContent {
                    drawContent()
                    if (appearance.borderEnabled) {
                        val stroke = (appearance.borderThicknessDp.dp * .5f).toPx()
                        val inset = stroke / 2f
                        val radius = (appearance.cornerRadiusDp.dp.toPx() - inset).coerceAtLeast(0f)
                            .coerceAtMost((size.width - stroke) / 2f)
                            .coerceAtMost(size.height - stroke)
                        val left = inset
                        val right = size.width - inset
                        val edge = if (retryAbove) inset else size.height - inset
                        val cornerY = if (retryAbove) edge + radius else edge - radius
                        val join = if (retryAbove) size.height else 0f
                        val cornerTop = if (retryAbove) edge else edge - 2f * radius
                        val cornerBottom = cornerTop + 2f * radius
                        val outline = Path().apply {
                            moveTo(left, join); lineTo(left, cornerY)
                            if (radius > 0f) arcTo(Rect(left, cornerTop, left + 2f * radius,
                                cornerBottom), 180f, if (retryAbove) 90f else -90f, false)
                            lineTo(right - radius, edge)
                            if (radius > 0f) arcTo(Rect(right - 2f * radius, cornerTop, right,
                                cornerBottom), if (retryAbove) 270f else 90f,
                                if (retryAbove) 90f else -90f, false)
                            lineTo(right, join)
                        }
                        drawPath(outline, Color(appearance.borderArgb), style = Stroke(stroke))
                    }
                }.clickable(role = Role.Button) {},
                contentAlignment = if (retryAbove) Alignment.TopCenter else Alignment.BottomCenter) {
                Row(Modifier.height(retryHeight), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Icon(Icons.Outlined.Refresh, null, tint = colors.text, modifier = Modifier.size(18.dp))
                    Text(strings.text("Повторити", "Retry", "重试"), color = colors.text,
                        fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        CompositionLocalProvider(LocalViewConfiguration provides holdConfiguration) {
            Box(Modifier.offset { IntOffset((x * density).roundToInt(), (y * density).roundToInt()) }
                .width(width).heightIn(min = 76.dp).onSizeChanged { measuredHeight = it.height }
                .clip(bodyShape).background(colors.background.copy(alpha = appearance.alpha))
                .then(if (appearance.borderEnabled) Modifier.border(appearance.borderThicknessDp.dp,
                    Color(appearance.borderArgb), bodyShape) else Modifier)
                .pointerInput(travelX, travelY, density) {
                    var origin = latestAppearance
                    var total = Offset.Zero
                    detectDragGestures(
                        onDragStart = { origin = latestAppearance; total = Offset.Zero },
                        onDragEnd = { latestChange(draft.normalized()) },
                    ) { change, drag ->
                        change.consume(); total += drag / density
                        draft = origin.copy(
                            x = if (travelX > 0) (origin.x + total.x / travelX).coerceIn(0f, 1f) else .5f,
                            y = if (travelY > 0) (origin.y + total.y / travelY).coerceIn(0f, 1f) else .5f,
                        )
                    }
                }.pointerInput(Unit) { detectTapGestures(onLongPress = { latestHide() }) }
                .padding(16.dp), contentAlignment = Alignment.Center) {
                Text(strings.text("Очікування Wi-Fi для відновлення ADB",
                    "Waiting for Wi-Fi to restore ADB", "正在等待 Wi-Fi 以恢复 ADB"),
                    color = colors.text, fontSize = 16.sp, lineHeight = 22.sp,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            }
        }
        Text(strings.text("Планшет", "Tablet", "平板"), color = colors.muted, fontSize = 12.sp,
            modifier = Modifier.align(Alignment.BottomStart).padding(8.dp))
    }
}

@Composable
private fun ReminderColorLine(
    strings: UiStrings,
    colors: UiPalette,
    color: Int,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val press = rememberPressFeedback(enabled)
    Row(Modifier.fillMaxWidth().then(press.modifier).clickable(
        interactionSource = press.interactionSource, indication = null, enabled = enabled,
        onClick = onClick)
        .padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(strings.text("Колір рамки", "Frame color", "边框颜色"),
            color = if (enabled) colors.text else colors.muted.copy(alpha = .62f),
            fontSize = if (LocalCompactControls.current) 14.sp else 16.sp,
            fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Box(Modifier.size(30.dp).clip(RoundedCornerShape(6.dp)).background(Color(color))
            .border(1.dp, colors.borderStrong, RoundedCornerShape(6.dp)))
    }
}

@Composable
private fun ReminderColorPicker(
    strings: UiStrings,
    colors: UiPalette,
    selected: Int,
    onDismiss: () -> Unit,
    onSelect: (Int) -> Unit,
) {
    val choices = listOf(0xFFF2C34E.toInt(), 0xFF2F86F6.toInt(), 0xFF54D898.toInt(),
        0xFFFF8C8C.toInt(), 0xFFFFFFFF.toInt(), 0xFF121A23.toInt())
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.clip(RoundedCornerShape(12.dp)).background(colors.surface)
            .border(1.dp, colors.borderStrong, RoundedCornerShape(12.dp)).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(strings.text("Колір рамки", "Frame color", "边框颜色"), color = colors.text,
                fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                choices.forEach { choice ->
                    Box(Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)).background(Color(choice))
                        .border(if (choice == selected) 3.dp else 1.dp,
                            if (choice == selected) colors.accent else colors.borderStrong,
                            RoundedCornerShape(8.dp)).clickable { onSelect(choice) })
                }
            }
            ActionButton(strings.text("Скасувати", "Cancel", "取消"), colors,
                Modifier.fillMaxWidth(), onClick = onDismiss)
        }
    }
}
