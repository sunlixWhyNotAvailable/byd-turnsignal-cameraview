package com.byd.extend.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.CompareArrows
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.LocalParking
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.window.DialogProperties
import com.byd.extend.R
import com.byd.extend.ReleaseNotesMarkdown
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first

private val rootIcons: List<ImageVector> = listOf(
    Icons.AutoMirrored.Outlined.CompareArrows, Icons.Outlined.Visibility, Icons.Outlined.LocalParking,
    Icons.Outlined.Videocam, Icons.Outlined.DirectionsCar, Icons.Outlined.Settings,
    Icons.Outlined.BugReport,
)

internal fun bottomNavigationEqualWidth(barWidth: androidx.compose.ui.unit.Dp): androidx.compose.ui.unit.Dp =
    (barWidth - 12.dp - 8.dp * (RootTab.entries.size - 1)) / RootTab.entries.size

/**
 * Production UI shell. Native camera content is supplied by the Activity and never enters Compose
 * state; all mutations leave the shell as typed actions.
 */
@Composable
@OptIn(ExperimentalComposeUiApi::class)
fun BydExtendApp(
    state: BydExtendUiState,
    onAction: (BydExtendUiAction) -> Unit,
    cameraHost: @Composable (CameraHostSlot) -> Unit,
    onPreview: (NumberTarget, String, Long) -> String? = { _, value, _ -> value },
) {
    val context = LocalContext.current
    val strings = remember(state.language, context) { UiStrings(state.language, context) }
    val colors = remember(state.theme) { palette(state.theme) }
    val scrollKey = state.scrollKey()
    val savedScrollOffset = remember(scrollKey) { RuntimeUiSession.INSTANCE.scrollOffset(scrollKey) }
    val primaryScroll = remember(scrollKey) {
        // ScrollState clamps its initial value before the first real content measurement.  The
        // saved offset is restored explicitly below once a non-empty range exists.
        ScrollState(savedScrollOffset)
    }
    var scrollRestored by remember(scrollKey, savedScrollOffset) {
        mutableStateOf(savedScrollOffset == 0)
    }
    LaunchedEffect(primaryScroll, scrollKey, savedScrollOffset) {
        if (savedScrollOffset > 0) {
            // ScrollState starts with Int.MAX_VALUE before its first layout.  It is a sentinel,
            // not a measured scroll range; applying the saved value against it can then be
            // clamped back to zero by the first real measurement. A zero range can also be a
            // temporary short layout before runtime status arrives. Keep the saved offset dormant
            // until scrolling is possible instead of arming persistence with a placeholder zero.
            val measuredMax = primaryScroll.maxValue.takeIf { RuntimeUiSession.canRestoreScroll(it) }
                ?: snapshotFlow { primaryScroll.maxValue }
                    .filter { RuntimeUiSession.canRestoreScroll(it) }.first()
            primaryScroll.scrollTo(savedScrollOffset.coerceAtMost(measuredMax))
        }
        scrollRestored = true
    }
    LaunchedEffect(primaryScroll, scrollKey) {
        snapshotFlow { primaryScroll.value to primaryScroll.maxValue }.collect { (value, max) ->
            if (scrollRestored && max > 0 && max != Int.MAX_VALUE) {
                RuntimeUiSession.INSTANCE.rememberScrollOffset(scrollKey, value, max)
            }
        }
    }
    CompositionLocalProvider(LocalPrimaryScroll provides primaryScroll) {
        Box(Modifier.fillMaxSize().background(colors.background).semantics { testTagsAsResourceId = true }) {
            Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                AppHeader(state, strings, colors, onAction)
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when (state.activeTab) {
                        RootTab.Signals -> SignalsScreen(state.signals, strings, colors, onAction)
                        RootTab.Blind -> BlindScreen(state.blind, strings, colors, onAction, cameraHost, onPreview)
                        RootTab.Parking -> ParkingScreen(state.parking, strings, colors, onAction, cameraHost, onPreview)
                        RootTab.Reverse -> ReverseScreen(state.reverse, strings, colors, onAction, cameraHost, onPreview)
                        RootTab.Mirror -> MirrorScreen(state.mirror, strings, colors, onAction, cameraHost, onPreview)
                        RootTab.Settings -> SettingsScreen(state.settings, state.legacyRuntimeBlocked,
                            strings, colors, onAction, onPreview)
                        RootTab.Debug -> DebugScreen(state.debug, state.signals.guard.enabled, strings, colors, onAction, cameraHost)
                    }
                }
                BottomNavigation(state.activeTab, strings, colors) { onAction(BydExtendUiAction.Navigate(it)) }
            }
            state.dialog?.let { AppDialog(it, strings, colors, onAction) }
        }
    }
}

/** Semantic viewport identity; persisted selections remain separate from this process-only state. */
private fun BydExtendUiState.scrollKey(): String = when (activeTab) {
    RootTab.Signals -> "signals"
    RootTab.Blind -> "blind:${blind.selectedGroup}:${blind.selectedSide}:${blind.section}"
    RootTab.Parking -> "parking:${parking.selectedView}:${parking.section}"
    RootTab.Reverse -> "reverse:${reverse.selectedElement}:${reverse.selectedSource}:${reverse.section}"
    RootTab.Mirror -> "mirror:${mirror.target}:${mirror.section}"
    RootTab.Settings -> "settings:${settings.category}"
    RootTab.Debug -> "debug:${debug.mode}"
}

@Composable
private fun AppHeader(
    state: BydExtendUiState,
    strings: UiStrings,
    colors: UiPalette,
    onAction: (BydExtendUiAction) -> Unit,
) {
    Panel(colors, Modifier.fillMaxWidth(), padding = 0.dp) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Image(painterResource(R.drawable.byd_extend_mark), "BYD Extend",
                Modifier.size(54.dp).clip(RoundedCornerShape(10.dp)).testTag("app-artwork"),
                contentScale = ContentScale.Crop)
            Column(Modifier.weight(1f)) {
                Text("BYD Extend", color = colors.text, fontSize = 23.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                Text(strings.subtitle, color = colors.muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                HeaderStatusPill(state.header.adb, "ADB", strings, colors)
                if (state.header.location.visible || state.header.weatherEnabled) {
                    HeaderStatusPill(state.header.location, strings.text("Геолокація", "Location"), strings, colors)
                }
                Segmented(when (state.language) {
                    UiLanguage.Ukrainian -> listOf("Укр", "Англ", "中文")
                    UiLanguage.Chinese -> listOf("У克", "英", "中文")
                    UiLanguage.English -> listOf("UA", "ENG", "中文")
                }, state.language.ordinal, colors, Modifier.width(190.dp)) {
                    onAction(BydExtendUiAction.SetLanguage(UiLanguage.entries[it]))
                }
                Segmented(listOf(strings.text("Темна", "Dark"), strings.text("Світла", "Light")),
                    if (state.theme == UiTheme.Dark) 0 else 1, colors, Modifier.width(138.dp)) {
                    onAction(BydExtendUiAction.SetTheme(if (it == 0) UiTheme.Dark else UiTheme.Light))
                }
            }
        }
    }
}

@Composable
private fun HeaderStatusPill(
    state: StatusUiState,
    label: String,
    strings: UiStrings,
    colors: UiPalette,
) {
    if (!state.visible) return
    val suffix = when (state.tone) {
        StatusTone.Ok -> strings.text("ОК", "OK")
        StatusTone.Error -> strings.text("Помилка", "Error")
        StatusTone.Warning -> strings.text("Очікування", "Pending")
        StatusTone.Neutral -> strings.text("—", "—")
    }
    StatusPill(state.copy(text = "$label: $suffix"), label, colors)
}

@Composable
private fun SignalsScreen(
    state: SignalsUiState,
    strings: UiStrings,
    colors: UiPalette,
    onAction: (BydExtendUiAction) -> Unit,
) {
    ScreenSurface(colors) {
        PageTitle(strings.tabs[0], strings.text("Захист поворотника та додаткові функції",
            "Turn-signal guard and additional functions"), colors)
        Row(Modifier.fillMaxWidth().padding(top = 10.dp).height(IntrinsicSize.Max),
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Section(strings.text("Захист поворотника", "Turn-signal guard"), colors,
                Modifier.weight(1f).fillMaxHeight(), trailing = {
                    AppSwitch(state.guard.enabled,
                        { onAction(BydExtendUiAction.Toggle(ToggleTarget.Simple(ToggleId.Guard), it)) }, colors,
                        pending = state.guard.operation.pending, enabled = state.guard.operation.enabled,
                        label = strings.text("Захист поворотника", "Turn-signal guard"))
                }) {
                GuardNumber.entries.forEach { field ->
                    val value = when (field) {
                        GuardNumber.OutwardAngle -> state.guard.outwardAngle
                        GuardNumber.CentreTolerance -> state.guard.centreTolerance
                        GuardNumber.CorrectionDelayMs -> state.guard.correctionDelayMs
                        GuardNumber.MaximumSpeed -> state.guard.maximumSpeed
                    }
                    val title = when (field) {
                        GuardNumber.OutwardAngle -> strings.text("Поворот у напрямку", "Outward angle")
                        GuardNumber.CentreTolerance -> strings.text("Повернення до центру ±", "Return to centre ±")
                        GuardNumber.CorrectionDelayMs -> strings.text("Затримка корекції", "Correction delay")
                        GuardNumber.MaximumSpeed -> strings.text("Максимальна швидкість", "Maximum speed")
                    }
                    val suffix = when (field) {
                        GuardNumber.OutwardAngle, GuardNumber.CentreTolerance -> "°"
                        GuardNumber.CorrectionDelayMs -> strings.text("мс", "ms")
                        GuardNumber.MaximumSpeed -> strings.text("км/год", "km/h")
                    }
                    val range = when (field) {
                        GuardNumber.OutwardAngle -> 0f..360f
                        GuardNumber.CentreTolerance -> 0f..45f
                        GuardNumber.CorrectionDelayMs -> 0f..1000f
                        GuardNumber.MaximumSpeed -> 0f..300f
                    }
                    NumericSetting(title, value, suffix, colors,
                        { onAction(BydExtendUiAction.CommitNumber(NumberTarget.Guard(field), it)) }, range,
                        enabled = state.guard.operation.enabled && !state.guard.operation.pending,
                        identity = NumberTarget.Guard(field))
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Section(strings.text("Музика та підсвітка", "Music and lighting"), colors) {
                    SwitchLine(strings.text("Підсвітка та метадані музики", "Ambient lighting and music metadata"),
                        strings.text("Штатна підсвітка під час відтворення", "Stock ambient lighting during playback"),
                        state.music.enabled,
                        { onAction(BydExtendUiAction.Toggle(ToggleTarget.Simple(ToggleId.Music), it)) }, colors,
                        pending = state.music.operation.pending, enabled = state.music.operation.enabled)
                }
                Section(strings.text("Погода", "Weather"), colors, Modifier.weight(1f)) {
                    SwitchLine(strings.text("Локальна погода", "Local weather"),
                        strings.text("Погода за координатами у штатній картці BYD",
                            "Coordinate-based weather in the stock BYD card"), state.weather.enabled,
                        { onAction(BydExtendUiAction.Toggle(ToggleTarget.Simple(ToggleId.Weather), it)) }, colors,
                        pending = state.weather.operation.pending, enabled = state.weather.operation.enabled)
                    NumericSetting(strings.text("Інтервал оновлення", "Refresh interval"), state.weather.refreshMinutes,
                        strings.text("хв", "min"), colors,
                        { onAction(BydExtendUiAction.CommitNumber(NumberTarget.WeatherInterval, it)) }, 5f..180f,
                        enabled = state.weather.enabled && !state.weather.operation.pending,
                        identity = NumberTarget.WeatherInterval,
                        compactSuffix = true,
                        beforeInput = {
                            ActionButton(strings.text("Оновити зараз", "Refresh now"), colors, Modifier.width(170.dp),
                                icon = Icons.Outlined.Refresh,
                                enabled = state.weather.enabled && state.weather.refresh.enabled && !state.weather.refresh.pending) {
                                onAction(BydExtendUiAction.Run(CommandId.WeatherRefresh))
                            }
                        })
                    StatusText(state.weather.refresh.status, colors)
                    ActionButton(
                        strings.text("Дані погоди: Open-Meteo", "Weather data: Open-Meteo"),
                        colors,
                        Modifier.width(230.dp),
                    ) { onAction(BydExtendUiAction.Run(CommandId.OpenWeatherAttribution)) }
                }
            }
        }
    }
}

@Composable
private fun BottomNavigation(active: RootTab, strings: UiStrings, colors: UiPalette, onSelect: (RootTab) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth().height(60.dp).clip(RoundedCornerShape(8.dp))
        .border(1.dp, colors.border, RoundedCornerShape(8.dp)).background(colors.panel)) {
        val equalWidth = bottomNavigationEqualWidth(maxWidth)
        Row(Modifier.fillMaxSize().padding(6.dp).selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RootTab.entries.forEachIndexed { index, tab ->
                val selected = active == tab
                val press = rememberPressFeedback()
                val visualClick = rememberVisualFirstClick { onSelect(tab) }
                val width = when (tab) {
                    RootTab.Signals -> Modifier.weight(1f)
                    RootTab.Debug -> Modifier.width(48.dp)
                    else -> Modifier.width(equalWidth)
                }
                Column(width.fillMaxHeight()
                    .clip(RoundedCornerShape(6.dp))
                    .border(1.dp, if (selected) colors.accent else Color.Transparent, RoundedCornerShape(6.dp))
                    .background(pressBackground(if (selected) colors.active else Color.Transparent, colors, press.pressed))
                    .then(press.modifier)
                    .semantics { if (tab == RootTab.Debug) contentDescription = strings.tabs[index] }
                    .selectable(selected = selected, interactionSource = press.interactionSource,
                        indication = null, role = Role.Tab, onClick = visualClick),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center) {
                    Icon(rootIcons[index], null, tint = if (selected) colors.text else colors.muted,
                        modifier = Modifier.size(20.dp))
                    if (tab != RootTab.Debug) {
                        Text(strings.tabs[index], color = if (selected) colors.text else colors.muted,
                            fontSize = 14.sp, lineHeight = 14.sp, fontWeight = FontWeight.SemiBold,
                            maxLines = 2, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun AppDialog(
    state: DialogUiState,
    strings: UiStrings,
    colors: UiPalette,
    onAction: (BydExtendUiAction) -> Unit,
) {
    val captureDialog = state.kind == DialogKind.ReverseButtonCapture
    val dismissCommand = if (state.kind == DialogKind.Progress) CommandId.CancelOperation
        else CommandId.DismissDialog
    val markdownText = remember(state.markdown) { releaseNotesText(state.markdown) }
    val notesScroll = rememberScrollState()
    Dialog(onDismissRequest = {
        if (state.cancellable) onAction(BydExtendUiAction.Run(dismissCommand))
    }, properties = DialogProperties(
        usePlatformDefaultWidth = false,
        dismissOnClickOutside = captureDialog,
    )) {
        val focusRequester = remember { FocusRequester() }
        val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
        if (captureDialog) {
            SideEffect { dialogWindow?.setDimAmount(if (colors.dark) .48f else .32f) }
        }
        LaunchedEffect(captureDialog) {
            if (captureDialog) focusRequester.requestFocus()
        }
        Column(Modifier.widthIn(max = 560.dp).fillMaxWidth()
            .then(if (state.updatePresentation) Modifier.height(430.dp) else Modifier.heightIn(max = 530.dp))
            .clip(RoundedCornerShape(8.dp))
            .background(colors.surface).border(1.dp, colors.borderStrong, RoundedCornerShape(8.dp)).padding(18.dp)
            .then(if (captureDialog) Modifier.semantics { testTagsAsResourceId = true }
            .testTag("reverse-key-dialog")
            .focusRequester(focusRequester).focusable() else Modifier),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(state.title, color = colors.text,
                fontSize = if (state.kind == DialogKind.Background) 20.sp else 22.sp,
                fontWeight = if (captureDialog) FontWeight.SemiBold else FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (state.kind == DialogKind.Background) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                        .background(colors.accent.copy(alpha = if (colors.dark) .22f else .12f))
                        .border(1.dp, colors.yellow.copy(alpha = .55f), RoundedCornerShape(8.dp)).padding(14.dp)) {
                        Text(strings.text("Установіть Disable background Apps -> BYD Extend = OFF",
                            "Set Disable background Apps -> BYD Extend = OFF"),
                            color = if (colors.dark) colors.yellow else colors.text,
                            fontWeight = FontWeight.Bold, fontSize = 16.sp, lineHeight = 20.sp)
                    }
                    Text(state.message, color = colors.muted, fontSize = 14.sp, lineHeight = 19.sp)
                }
            } else if (captureDialog) {
                Text(state.message, color = colors.muted, fontSize = 13.sp, lineHeight = 19.sp)
            } else Column(Modifier.fillMaxWidth().weight(1f, fill = state.updatePresentation)
                .clip(RoundedCornerShape(8.dp)).background(colors.field)
                .border(1.dp, colors.border, RoundedCornerShape(8.dp))
                .verticalScroll(notesScroll).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!state.updatePresentation || state.kind != DialogKind.Progress)
                    Text(state.message, color = colors.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                if (state.markdown.isNotEmpty()) {
                    Text(markdownText, color = colors.text,
                        fontSize = 14.sp, lineHeight = 21.sp)
                }
                if (!state.updatePresentation) state.progress?.let { progress ->
                    Text("${(progress.coerceIn(0f, 1f) * 100).toInt()}%", color = colors.muted, fontSize = 13.sp)
                }
            }
            if (state.updatePresentation && state.kind == DialogKind.Progress) {
                Text(state.message, color = colors.muted, fontSize = 13.sp)
                LinearProgressIndicator(progress = { state.progress ?: 0f }, Modifier.fillMaxWidth(),
                    color = colors.accent, trackColor = colors.field)
            }
            if (captureDialog) {
                if (state.cancellable) ActionButton(
                    strings.text("Скасувати", "Cancel"), colors,
                    Modifier.fillMaxWidth().testTag("reverse-key-cancel"),
                ) { onAction(BydExtendUiAction.Run(CommandId.DismissDialog)) }
            } else if (!state.managed) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
                if (state.kind == DialogKind.Background) ActionButton(strings.text("Відкрити", "Open"), colors,
                    Modifier.width(138.dp), primary = true) {
                    onAction(BydExtendUiAction.Run(CommandId.ConfirmDialog))
                }
                if (state.kind == DialogKind.Background) ActionButton(strings.text("Зрозуміло", "Got it"), colors,
                    Modifier.width(138.dp)) {
                    onAction(BydExtendUiAction.Run(CommandId.DismissDialog))
                }
                if (state.kind != DialogKind.Background && state.cancellable) ActionButton(strings.text("Скасувати", "Cancel"), colors, Modifier.width(138.dp)) {
                    onAction(BydExtendUiAction.Run(if (state.kind == DialogKind.Progress) CommandId.CancelOperation
                    else CommandId.DismissDialog))
                }
                if (state.kind != DialogKind.Background) ActionButton(
                    if (state.kind == DialogKind.Shutdown) strings.text("Зупинити", "Stop")
                    else strings.text("Готово", "Done"),
                    colors,
                    Modifier.width(138.dp),
                    primary = state.kind != DialogKind.Shutdown,
                    destructive = state.kind == DialogKind.Shutdown,
                    enabled = state.confirmEnabled,
                ) { onAction(BydExtendUiAction.Run(CommandId.ConfirmDialog)) }
            } else if ((state.cancellable && state.dismissLabel != null) ||
                (state.confirmVisible && state.confirmLabel != null)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
                    if (state.cancellable && state.dismissLabel != null) ActionButton(
                        state.dismissLabel, colors, Modifier.width(138.dp),
                    ) { onAction(BydExtendUiAction.Run(dismissCommand)) }
                    if (state.confirmVisible && state.confirmLabel != null) ActionButton(
                        state.confirmLabel,
                        colors,
                        Modifier.width(138.dp),
                        primary = state.kind != DialogKind.Shutdown,
                        destructive = state.kind == DialogKind.Shutdown,
                        enabled = state.confirmEnabled,
                    ) { onAction(BydExtendUiAction.Run(CommandId.ConfirmDialog)) }
                }
            }
        }
    }
}

private fun releaseNotesText(markdown: String): AnnotatedString = buildAnnotatedString {
    ReleaseNotesMarkdown.parseBlocks(markdown).forEachIndexed { index, block ->
        if (index > 0) append('\n')
        val headingStyle = when (block.level()) {
            1 -> SpanStyle(fontWeight = FontWeight.Bold, fontSize = 19.sp)
            2 -> SpanStyle(fontWeight = FontWeight.Bold, fontSize = 17.sp)
            3 -> SpanStyle(fontWeight = FontWeight.Bold, fontSize = 15.sp)
            else -> null
        }
        val marker = when (block.type()) {
            ReleaseNotesMarkdown.BlockType.BULLET -> "• "
            ReleaseNotesMarkdown.BlockType.ORDERED -> "${block.marker()} "
            ReleaseNotesMarkdown.BlockType.DIVIDER -> "────────"
            else -> ""
        }
        append(marker)
        if (block.type() != ReleaseNotesMarkdown.BlockType.DIVIDER) {
            if (headingStyle == null) appendMarkdownInline(block.text())
            else withStyle(headingStyle) { appendMarkdownInline(block.text()) }
        }
    }
}

private fun AnnotatedString.Builder.appendMarkdownInline(text: String) {
    ReleaseNotesMarkdown.parseInline(text).forEach { inline ->
        when (inline.type()) {
            ReleaseNotesMarkdown.InlineType.BOLD ->
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(inline.text()) }
            ReleaseNotesMarkdown.InlineType.CODE ->
                withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(inline.text()) }
            else -> append(inline.text())
        }
    }
}
