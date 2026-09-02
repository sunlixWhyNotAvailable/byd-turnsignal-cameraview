package com.byd.extend.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.CompareArrows
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.LocalParking
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.byd.extend.R

private val rootIcons: List<ImageVector> = listOf(
    Icons.AutoMirrored.Outlined.CompareArrows, Icons.Outlined.Visibility, Icons.Outlined.LocalParking,
    Icons.Outlined.Videocam, Icons.Outlined.Settings, Icons.Outlined.BugReport,
)
private val rootWeights = listOf(1.25f, 1.15f, 1.15f, 1.25f, .8f, .6f)

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
    val strings = remember(state.language) { UiStrings(state.language) }
    val colors = remember(state.theme) { palette(state.theme) }
    val primaryScroll = rememberScrollState()
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
                Segmented(if (strings.ukrainian) listOf("Укр", "Англ") else listOf("UA", "ENG"),
                    if (state.language == UiLanguage.Ukrainian) 0 else 1, colors, Modifier.width(138.dp)) {
                    onAction(BydExtendUiAction.SetLanguage(if (it == 0) UiLanguage.Ukrainian else UiLanguage.English))
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
    Row(Modifier.fillMaxWidth().height(58.dp).clip(RoundedCornerShape(8.dp))
        .border(1.dp, colors.border, RoundedCornerShape(8.dp)).background(colors.panel)
        .padding(6.dp).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        RootTab.entries.forEachIndexed { index, tab ->
            val selected = active == tab
            val press = rememberPressFeedback()
            val visualClick = rememberVisualFirstClick { onSelect(tab) }
            Row(Modifier.weight(rootWeights[index]).fillMaxHeight().clip(RoundedCornerShape(6.dp))
                .border(1.dp, if (selected) colors.accent else Color.Transparent, RoundedCornerShape(6.dp))
                .background(pressBackground(if (selected) colors.active else Color.Transparent, colors, press.pressed))
                .then(press.modifier)
                .clickable(interactionSource = press.interactionSource, indication = null,
                    role = Role.Tab) { visualClick() },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center) {
                Icon(rootIcons[index], null, tint = if (selected) colors.text else colors.muted, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(9.dp))
                Text(strings.tabs[index], color = if (selected) colors.text else colors.muted, fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun AppDialog(
    state: DialogUiState,
    strings: UiStrings,
    colors: UiPalette,
    onAction: (BydExtendUiAction) -> Unit,
) {
    Dialog(onDismissRequest = {
        if (state.cancellable) onAction(BydExtendUiAction.Run(CommandId.DismissDialog))
    }, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        Column(Modifier.widthIn(max = 560.dp).fillMaxWidth().clip(RoundedCornerShape(8.dp))
            .background(colors.surface).border(1.dp, colors.borderStrong, RoundedCornerShape(8.dp)).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(state.title, color = colors.text,
                fontSize = if (state.kind == DialogKind.Background) 20.sp else 22.sp,
                fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (state.kind == DialogKind.Background) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                        .background(colors.accent.copy(alpha = if (colors.dark) .22f else .12f))
                        .border(1.dp, colors.yellow.copy(alpha = .55f), RoundedCornerShape(8.dp)).padding(14.dp)) {
                        Text(strings.text("Установіть Disable background Apps -> BYD HUD = OFF",
                            "Set Disable background Apps -> BYD HUD = OFF"),
                            color = if (colors.dark) colors.yellow else colors.text,
                            fontWeight = FontWeight.Bold, fontSize = 16.sp, lineHeight = 20.sp)
                    }
                    Text(state.message, color = colors.muted, fontSize = 14.sp, lineHeight = 19.sp)
                }
            } else Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(colors.field)
                .border(1.dp, colors.border, RoundedCornerShape(8.dp)).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(state.message, color = colors.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                state.progress?.let { progress ->
                    Text("${(progress.coerceIn(0f, 1f) * 100).toInt()}%", color = colors.muted, fontSize = 13.sp)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
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
            }
        }
    }
}
