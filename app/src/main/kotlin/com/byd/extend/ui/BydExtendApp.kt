package com.byd.extend.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.LocalParking
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.findViewTreeLifecycleOwner
import com.byd.extend.R
import com.byd.extend.ReleaseNotesMarkdown
import kotlinx.coroutines.flow.first

private val rootIcons: List<ImageVector> = listOf(
    Icons.Outlined.Extension, Icons.Outlined.Visibility, Icons.Outlined.LocalParking,
    Icons.Outlined.Videocam, Icons.Outlined.DirectionsCar, Icons.Outlined.Settings,
    Icons.Outlined.BugReport,
)

internal fun bottomNavigationEqualWidth(barWidth: androidx.compose.ui.unit.Dp): androidx.compose.ui.unit.Dp =
    (barWidth - 12.dp - 8.dp * (RootTab.entries.size - 1) - 48.dp) / (RootTab.entries.size - 1)

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
    contentReady: Boolean = true,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalView.current.findViewTreeLifecycleOwner()
    val strings = remember(state.language, context) { UiStrings(state.language, context) }
    val colors = remember(state.theme) { palette(state.theme) }
    val uiSession = remember {
        RuntimeUiSession.INSTANCE.getOrCreate(RuntimeUiSelections.from(state))
    }
    val scrollKey = state.scrollKey()
    val sidebarKey = state.sidebarScrollKey()
    val viewportPositions = rememberSaveableStateHolder()
    val processMainViewport = uiSession.viewport(scrollKey, RuntimeViewportKind.Main)
    // Save only viewport state. The screen and its camera hosts stay outside saveable containers.
    val primaryScroll = rememberSavedScrollState(viewportPositions, "main-scroll:$scrollKey",
        processMainViewport.offset)
    val primaryLazyList = rememberSavedLazyListState(viewportPositions, "main-lazy:$scrollKey",
        processMainViewport)
    // Freeze the process fallback for this LazyListState lifetime. Session capture updates the
    // stored bookmark while scrolling; it must not recreate the restorer and cancel a drag/fling.
    val initialLazyFallback = remember(primaryLazyList) { processMainViewport }
    val primaryLazyViewport = remember(primaryLazyList, contentReady) {
        RetainedLazyViewport(primaryLazyList, initialLazyFallback, contentReady)
    }
    val sidebarScroll = rememberSavedScrollState(viewportPositions, "sidebar:$sidebarKey",
        uiSession.viewport(sidebarKey, RuntimeViewportKind.Sidebar).offset)
    // Android-restored state takes precedence over the process-only fallback.
    val mainScrollViewport = rememberSessionScrollRestore(primaryScroll, scrollKey, contentReady)
    val sidebarViewport = rememberSessionScrollRestore(sidebarScroll, sidebarKey, true)
    val lazyMain = state.usesLazyMainViewport()
    val imeDismissalPolicy = remember { ImeDismissalPolicy() }
    val numericDraftStore = remember { NumericDraftStore() }
    val latestState by rememberUpdatedState(state)
    fun captureUiSession() {
        uiSession.select(RuntimeUiSelections.from(latestState))
        uiSession.recordViewport(scrollKey, RuntimeViewportKind.Main,
            if (lazyMain) primaryLazyViewport.position() else mainScrollViewport.position())
        uiSession.recordViewport(sidebarKey, RuntimeViewportKind.Sidebar, sidebarViewport.position())
    }
    val latestCaptureUiSession by rememberUpdatedState(::captureUiSession)
    val dispatchAction: (BydExtendUiAction) -> Unit = {
        // Snapshot the outgoing viewport synchronously before navigation disposes its state.
        latestCaptureUiSession()
        onAction(it)
    }
    LaunchedEffect(uiSession, mainScrollViewport, primaryLazyViewport, sidebarViewport, scrollKey, sidebarKey,
        lazyMain) {
        snapshotFlow {
            Triple(
                RuntimeUiSelections.from(latestState),
                if (lazyMain) primaryLazyViewport.position() else mainScrollViewport.position(),
                sidebarViewport.position(),
            )
        }.collect { latestCaptureUiSession() }
    }
    DisposableEffect(uiSession, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP ||
                event == Lifecycle.Event.ON_DESTROY) latestCaptureUiSession()
        }
        lifecycleOwner?.lifecycle?.addObserver(observer)
        onDispose {
            latestCaptureUiSession()
            lifecycleOwner?.lifecycle?.removeObserver(observer)
        }
    }
    CompositionLocalProvider(
        LocalPrimaryScroll provides primaryScroll,
        LocalPrimaryLazyList provides primaryLazyViewport,
        LocalNumericDraftStore provides numericDraftStore,
        LocalImeDismissalPolicy provides imeDismissalPolicy,
    ) {
        Box(Modifier.fillMaxSize().background(colors.background).semantics { testTagsAsResourceId = true }) {
            Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                AppHeader(state, strings, colors, dispatchAction)
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when (state.activeTab) {
                        RootTab.Signals -> SignalsScreen(
                            state.signals, state.avas, state.adbRecovery, strings, colors, dispatchAction,
                            sidebarScroll)
                        RootTab.Blind -> BlindScreen(state.blind, strings, colors, dispatchAction, cameraHost, onPreview)
                        RootTab.Parking -> ParkingScreen(state.parking, strings, colors, dispatchAction, cameraHost, onPreview)
                        RootTab.Reverse -> ReverseScreen(state.reverse, strings, colors, dispatchAction, cameraHost, onPreview)
                        RootTab.Mirror -> MirrorScreen(state.mirror, strings, colors, dispatchAction, cameraHost, onPreview)
                        RootTab.Settings -> SettingsScreen(state.settings, state.legacyRuntimeBlocked,
                            state.language, state.theme == UiTheme.Dark, strings, colors, dispatchAction,
                            sidebarScroll, onPreview)
                        RootTab.Debug -> DebugScreen(state.debug, state.signals.guard.enabled, strings, colors, dispatchAction, cameraHost)
                    }
                }
                BottomNavigation(state.activeTab, strings, colors) { dispatchAction(BydExtendUiAction.Navigate(it)) }
            }
            state.dialog?.let { AppDialog(it, strings, colors, dispatchAction) }
        }
    }
}

@Composable
private fun rememberSavedScrollState(
    holder: androidx.compose.runtime.saveable.SaveableStateHolder,
    key: String,
    processOffset: Int,
): ScrollState {
    var initialized: ScrollState? = null
    holder.SaveableStateProvider(key) {
        // Android-restored state wins; the process value is only the initial fallback.
        initialized = rememberScrollState(processOffset)
    }
    return checkNotNull(initialized)
}

@Composable
private fun rememberSavedLazyListState(
    holder: androidx.compose.runtime.saveable.SaveableStateHolder,
    key: String,
    processPosition: RuntimeViewport,
): LazyListState {
    var initialized: LazyListState? = null
    holder.SaveableStateProvider(key) {
        // LazyListState's Saver restores Android state before these process-only initial values.
        initialized = rememberLazyListState(processPosition.index, processPosition.offset)
    }
    return checkNotNull(initialized)
}

private class SessionScrollState(
    val scroll: ScrollState,
    private val restored: () -> Boolean,
) {
    fun position(): RuntimeViewport? = if (restored() && scroll.maxValue != Int.MAX_VALUE) {
        RuntimeViewport(scroll.value)
    } else null
}

@Composable
private fun rememberSessionScrollRestore(
    scroll: ScrollState,
    key: String,
    contentReady: Boolean,
): SessionScrollState {
    // rememberScrollState's Android saveable value takes precedence over the process fallback.
    val savedOffset = remember(scroll) { scroll.value }
    var restored by remember(scroll, key) { mutableStateOf(savedOffset == 0) }
    LaunchedEffect(scroll, key, savedOffset, contentReady) {
        if (contentReady && !restored) {
            // Int.MAX_VALUE is ScrollState's pre-layout sentinel. A measured zero is valid final
            // content, but must only be allowed to clamp after its caller declares content ready.
            snapshotFlow { scroll.maxValue }.first { it != Int.MAX_VALUE }
            scroll.scrollTo(savedOffset.coerceAtMost(scroll.maxValue))
            restored = true
        }
    }
    return remember(scroll) { SessionScrollState(scroll) { restored } }
}

/** Semantic viewport identity; saved scroll state remains separate from persisted selections. */
internal fun BydExtendUiState.scrollKey(): String = when (activeTab) {
    RootTab.Signals -> "signals:${signals.category}"
    RootTab.Blind -> "blind:${blind.selectedGroup}:${blind.selectedSide}:${blind.section}"
    RootTab.Parking -> "parking:${parking.selectedView}:${parking.section}"
    RootTab.Reverse -> "reverse:${reverse.selectedElement}:${reverse.selectedSource}:${reverse.section}"
    RootTab.Mirror -> "mirror:${mirror.target}:${mirror.activeFront}:${mirror.section}"
    RootTab.Settings -> "settings:${settings.category}"
    RootTab.Debug -> "debug:${debug.mode}"
}

internal fun BydExtendUiState.sidebarScrollKey(): String = when (activeTab) {
    RootTab.Signals -> "signals"
    RootTab.Settings -> "settings"
    else -> "none:${activeTab.name}"
}

internal fun BydExtendUiState.usesLazyMainViewport(): Boolean = when (activeTab) {
    RootTab.Blind, RootTab.Parking, RootTab.Reverse, RootTab.Mirror, RootTab.Settings -> true
    RootTab.Signals -> signals.category == SignalsCategory.Avas ||
        signals.category == SignalsCategory.AdbRecovery
    RootTab.Debug -> false
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
                HeaderStatusPill(state.header.permissions, strings.text("Права", "Permissions", "权限"), strings, colors)
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
    avas: AvasUiState,
    adbRecovery: AdbRecoveryUiState,
    strings: UiStrings,
    colors: UiPalette,
    onAction: (BydExtendUiAction) -> Unit,
    sidebarScroll: ScrollState,
) {
    val categories = listOf(
        strings.text("Поворотники", "Turn signals", "转向灯"),
        strings.text("Музика та підсвітка", "Music and lighting", "音乐与氛围灯"),
        strings.text("Погода", "Weather", "天气"),
        strings.text("AVAS (зовнішній динамік)", "AVAS (external speaker)", "AVAS（车外扬声器）"),
        strings.text("Відновлення ADB", "ADB recovery", "ADB 恢复"),
    )
    val icons = listOf(IntegrationIcons.TurnSignals, Icons.Outlined.MusicNote,
        IntegrationIcons.Weather, Icons.AutoMirrored.Outlined.VolumeUp, Icons.Outlined.Refresh)
    ScreenSurface(colors, scroll = false) {
        PageTitle(strings.tabs[0], strings.text("Виберіть інтеграцію для налаштування",
            "Choose an integration to configure", "选择要配置的集成功能"), colors)
        Row(Modifier.fillMaxWidth().weight(1f).padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            CategorySidebar(categories, icons, state.category.ordinal, colors, strings, sidebarScroll) {
                onAction(BydExtendUiAction.Select(
                    SelectionTarget.Simple(SelectionId.SignalsCategory), it))
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(8.dp))
                .border(1.dp, colors.border, RoundedCornerShape(8.dp))
                .background(colors.surface).padding(12.dp)) {
                val avasColumns = if (maxWidth >= 600.dp) 2 else 1
                when (state.category) {
                    SignalsCategory.TurnSignals -> Column(Modifier.fillMaxSize()
                        .verticalScroll(LocalPrimaryScroll.current)) { Section(strings.text("Захист поворотника", "Turn-signal guard",
                        "转向灯保护"), colors) {
                    SwitchLine(strings.text("Захист поворотника", "Turn-signal guard", "转向灯保护"), "",
                        state.guard.enabled,
                        { onAction(BydExtendUiAction.Toggle(ToggleTarget.Simple(ToggleId.Guard), it)) }, colors,
                        pending = state.guard.operation.pending, enabled = state.guard.operation.enabled,
                        compactSwitch = true)
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
                    } }
                    SignalsCategory.Music -> Column(Modifier.fillMaxSize()
                        .verticalScroll(LocalPrimaryScroll.current)) { Section(strings.text("Музика та підсвітка", "Music and lighting",
                        "音乐与氛围灯"), colors) {
                    SwitchLine(strings.text("Підсвітка та метадані музики", "Ambient lighting and music metadata"),
                        strings.text("Штатна підсвітка під час відтворення", "Stock ambient lighting during playback"),
                        state.music.enabled,
                        { onAction(BydExtendUiAction.Toggle(ToggleTarget.Simple(ToggleId.Music), it)) }, colors,
                        pending = state.music.operation.pending, enabled = state.music.operation.enabled)
                    } }
                    SignalsCategory.Weather -> Column(Modifier.fillMaxSize()
                        .verticalScroll(LocalPrimaryScroll.current)) { Section(strings.text("Погода", "Weather", "天气"), colors) {
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
                    } }
                    SignalsCategory.Avas -> LazyForm(Modifier.fillMaxSize(), LocalPrimaryLazyList.current) {
                        AvasIntegration(avas, strings, colors, onAction, avasColumns)
                    }
                    SignalsCategory.AdbRecovery -> LazyForm(
                        Modifier.fillMaxSize(), LocalPrimaryLazyList.current) {
                        AdbRecoveryScreen(adbRecovery, strings, colors) {
                            onAction(BydExtendUiAction.AdbRecovery(it))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CategorySidebar(
    categories: List<String>, icons: List<ImageVector>, selected: Int,
    colors: UiPalette, strings: UiStrings, scroll: ScrollState, onSelect: (Int) -> Unit,
) {
    Column(Modifier.width(260.dp).fillMaxHeight().clip(RoundedCornerShape(8.dp))
        .border(1.dp, colors.border, RoundedCornerShape(8.dp))
        .background(colors.panelAlt).padding(horizontal = 8.dp, vertical = 10.dp)) {
        Text(strings.text("КАТЕГОРІЇ", "CATEGORIES", "类别"), color = colors.muted,
            fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
        Spacer(Modifier.height(4.dp))
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(scroll).selectableGroup(),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            categories.forEachIndexed { index, title ->
                val active = selected == index
                val press = rememberPressFeedback()
                val visualClick = rememberVisualFirstClick { onSelect(index) }
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                    .background(pressBackground(if (active) colors.accent.copy(alpha = .14f)
                        else Color.Transparent, colors, press.pressed)).then(press.modifier)
                    .testTag("signals-category-$index")
                    .selectable(active, interactionSource = press.interactionSource, indication = null,
                        role = Role.Tab, onClick = visualClick).padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(icons[index], null, tint = if (active) colors.accent else colors.muted,
                        modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(title, color = if (active) colors.accent else colors.muted, fontSize = 14.sp,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

private fun avasTitle(id: String, strings: UiStrings) = when (id) {
    AvasProfileIds.LOCK -> strings.text("Закриття", "Lock", "上锁")
    AvasProfileIds.UNLOCK -> strings.text("Відкриття", "Unlock", "解锁")
    AvasProfileIds.POWER_OFF -> strings.text("Вимкнення", "Power off", "下电")
    AvasProfileIds.POWER_ON -> strings.text("Увімкнення", "Power on", "上电")
    else -> id
}

@Composable
private fun FormScope.AvasIntegration(
    state: AvasUiState, strings: UiStrings, colors: UiPalette,
    onAction: (BydExtendUiAction) -> Unit,
    columns: Int,
) {
    var listing by remember { mutableStateOf<String?>(null) }
    fun send(profileId: String, kind: AvasActionKind, boolean: Boolean? = null,
        number: Int? = null, text: String? = null) {
        onAction(BydExtendUiAction.Avas(AvasBackendAction(profileId, kind, boolean, number, text)))
    }
    val exteriorBusy = state.profiles.any { it.playback != AvasPlaybackUiState.Idle }
    row("avas-intro") { Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(strings.text("AVAS (зовнішній динамік)", "AVAS (external speaker)", "AVAS（车外扬声器）"),
            color = colors.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Text(strings.text("Незалежні профілі з власними файлами, налаштуваннями та ручною перевіркою.",
            "Independent profiles with their own files, settings, and manual playback.",
            "各配置独立保存文件、设置并支持手动播放。"), color = colors.muted, fontSize = 13.sp)
        Text(strings.text("Пропускання одночасного звуку відкриття/закриття налаштовується окремо для увімкнення та вимкнення авто.",
            "Skipping simultaneous lock/unlock sounds is configured separately for Power on and Power off.",
            "可分别为上电和下电设置是否跳过同时触发的解锁/锁车声音。"),
            color = colors.muted, fontSize = 13.sp)
    } }
    AvasProfileIds.ALL.map { id -> state.profiles.firstOrNull { it.id == id }
        ?: AvasProfileUiState(id = id) }
        .chunked(columns).forEachIndexed { pairIndex, pair ->
            row("avas-profile-pair-$pairIndex") {
                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        pair.forEach { profile ->
                            val importing = state.importingProfileId != null
                            val title = avasTitle(profile.id, strings)
                            val selectedAsset = profile.assets.firstOrNull {
                                it.id == profile.selectedAssetId
                            }
                            val selectedLabel = selectedAsset?.let { avasAssetLabel(it, strings) }
                                ?: profile.currentFilename
                            Section(title, colors, Modifier.weight(1f).fillMaxHeight()
                                .testTag("avas-${profile.id}"), header = {
                                Row(Modifier.fillMaxWidth().background(colors.panelAlt)
                                    .avasSwitchRow(profile.enabled, {
                                        send(profile.id, AvasActionKind.SetEnabled, boolean = it)
                                    }, colors).padding(horizontal = 14.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Text(title.uppercase(), color = colors.muted, fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                    AppSwitch(profile.enabled, {
                                        send(profile.id, AvasActionKind.SetEnabled, boolean = it)
                                    }, colors, clearSemantics = true, label = title)
                                }
                            }) {
                                if (profile.id == AvasProfileIds.POWER_OFF
                                    || profile.id == AvasProfileIds.POWER_ON) {
                                    SwitchLine(strings.text("Пропускати одночасний звук\nвідкриття/закриття",
                                        "Skip simultaneous lock/unlock sound",
                                        "跳过同时触发的解锁/锁车声音"), "",
                                        profile.skipConcurrentLockUnlock, {
                                            send(profile.id, AvasActionKind.SetSkipConcurrentLockUnlock,
                                                boolean = it)
                                        }, colors)
                                }
                                Text(strings.text("Обраний аудіофайл", "Selected audio file", "已选音频文件"),
                                    color = colors.muted, fontSize = 12.sp)
                                Text(selectedLabel ?: strings.text("Файл не обрано", "No file selected",
                                    "未选择文件"), color = if (selectedLabel == null) colors.muted else colors.text,
                                    fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                        .clip(RoundedCornerShape(7.dp)).background(colors.field)
                                        .border(1.dp, colors.borderStrong, RoundedCornerShape(7.dp)).padding(10.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    ActionButton(strings.text(if (importing) "Імпортування…" else "Додати файли…",
                                        if (importing) "Importing…" else "Add files…",
                                        if (importing) "正在导入…" else "添加文件…"), colors,
                                        Modifier.weight(1f).testTag("avas-add-${profile.id}"), enabled = !importing,
                                        maxLines = 2) { send(profile.id, AvasActionKind.ImportFiles) }
                                    ActionButton(strings.format("Аудіофайли (%1\$s)", "Audio files (%1\$s)",
                                        "音频文件（%1\$s）", profile.assets.size.toString()), colors,
                                        Modifier.weight(1f).testTag("avas-list-${profile.id}"), maxLines = 2) {
                                        listing = profile.id
                                    }
                                }
                                SwitchLine(strings.text("Випадкова мелодія", "Random melody", "随机旋律"), "",
                                    profile.random, { send(profile.id, AvasActionKind.SetRandom, boolean = it) }, colors)
                                Text(strings.text("Лише для цієї автоматизації. «Старт» відтворює вибраний файл через зовнішній динамік.",
                                    "Only for this automation. Start plays the selected file through the exterior speaker.",
                                    "仅用于此自动化。“开始”通过车外扬声器播放所选文件。"),
                                    color = colors.muted, fontSize = 12.sp)
                                Text(strings.text("Гучність", "Volume", "音量"), color = colors.text,
                                    fontSize = if (LocalCompactControls.current) 14.sp else 16.sp,
                                    fontWeight = FontWeight.SemiBold)
                                NumericSetting(strings.text("Гучність", "Volume", "音量"),
                                    profile.volume.coerceIn(0, 100).toString(), "%", colors,
                                    { send(profile.id, AvasActionKind.SetVolume, number = it.toFloat().toInt()) },
                                    0f..100f, adjustable = true, slider = true, showLabel = false,
                                    compactSuffix = true, narrowInput = true,
                                    identity = "avas-volume-${profile.id}")
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    ActionButton(strings.text("Старт", "Start", "开始"), colors,
                                        Modifier.weight(1f).testTag("avas-start-${profile.id}"),
                                        icon = Icons.Outlined.PlayArrow, primary = true,
                                        enabled = profile.manualStartAllowed) {
                                        send(profile.id, AvasActionKind.StartManual)
                                    }
                                    ActionButton(strings.text("Стоп", "Stop", "停止"), colors,
                                        Modifier.weight(1f).testTag("avas-stop-${profile.id}"),
                                        icon = Icons.Outlined.Stop, mainBackground = true,
                                        enabled = profile.manualStopAllowed) { send(profile.id, AvasActionKind.StopManual) }
                                }
                            }
                        }
                        repeat(columns - pair.size) { Spacer(Modifier.weight(1f)) }
                    }
            }
        }
    listing?.let { profileId ->
        val profile = state.profiles.firstOrNull { it.id == profileId }
        if (profile == null) listing = null else Dialog(
            onDismissRequest = { listing = null },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            val latestAudition by rememberUpdatedState(state.audition)
            DisposableEffect(profileId) {
                onDispose {
                    val active = latestAudition
                    if (active.active && active.profileId == profileId) {
                        active.sessionId?.let {
                            send(profileId, AvasActionKind.StopAudition, text = it)
                        }
                    }
                }
            }
            val window = (LocalView.current.parent as? DialogWindowProvider)?.window
            SideEffect { window?.setDimAmount(if (colors.dark) .48f else .32f) }
            Column(Modifier.widthIn(max = 660.dp).fillMaxWidth().heightIn(max = 520.dp)
                .clip(RoundedCornerShape(8.dp)).background(colors.surface)
                .border(1.dp, colors.borderStrong, RoundedCornerShape(8.dp)).padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(avasTitle(profile.id, strings), color = colors.text, fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold)
                Text(strings.text("Оберіть файл для цього профілю. Вибір не запускає звук.",
                    "Select a file for this profile. Selection does not play audio.",
                    "选择此配置的文件。选择文件不会播放声音。"), color = colors.muted, fontSize = 13.sp)
                LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).selectableGroup(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (profile.assets.isEmpty()) item(key = "empty") { Text(strings.text("Аудіофайлів ще немає. Додайте їх через системний вибір файлів.",
                        "No audio files yet. Add files using the system picker.",
                        "暂无音频文件。请通过系统文件选择器添加。"), color = colors.muted, fontSize = 14.sp) }
                    items(profile.assets, key = { it.id }, contentType = { "audio-file" }) { asset ->
                        val selected = asset.id == profile.selectedAssetId
                        val audition = state.audition
                        val playing = audition.active && audition.profileId == profile.id &&
                            audition.assetId == asset.id
                        val press = rememberPressFeedback()
                        val click = rememberVisualFirstClick {
                            send(profile.id, AvasActionKind.SelectAsset, text = asset.id)
                        }
                        Row(Modifier.fillMaxWidth().height(66.dp).testTag("avas-file-${asset.id}")
                            .clip(RoundedCornerShape(7.dp))
                            .background(pressBackground(if (selected) colors.accent.copy(alpha = .14f)
                                else colors.panelAlt, colors, press.pressed)).then(press.modifier)
                            .selectable(selected, interactionSource = press.interactionSource, indication = null,
                                role = Role.RadioButton, onClick = click).padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(avasAssetLabel(asset, strings), color = colors.text, fontSize = 15.sp,
                                modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(avasDurationLabel(asset.durationMs), color = colors.muted, fontSize = 15.sp,
                                modifier = Modifier.width(72.dp).padding(start = 8.dp),
                                textAlign = androidx.compose.ui.text.style.TextAlign.End, maxLines = 1)
                            Box(Modifier.width(34.dp).height(42.dp), contentAlignment = Alignment.CenterEnd) {
                                if (selected) Icon(Icons.Outlined.CheckCircle, null, tint = colors.accent,
                                    modifier = Modifier.size(22.dp))
                            }
                            Box(Modifier.width(54.dp).height(42.dp), contentAlignment = Alignment.CenterEnd) {
                                AvasVectorButton(if (playing) Icons.Outlined.Stop else Icons.Outlined.MusicNote,
                                    if (playing) strings.text("Зупинити прослуховування", "Stop listening", "停止试听")
                                    else strings.format("Прослухати %1\$s", "Listen to %1\$s", "试听 %1\$s",
                                        avasAssetLabel(asset, strings)), colors, colors.accent,
                                    enabled = playing || (asset.ready && !exteriorBusy)) {
                                    if (playing) audition.sessionId?.let {
                                        send(profile.id, AvasActionKind.StopAudition, text = it)
                                    } else send(profile.id, AvasActionKind.StartAudition, text = asset.id)
                                }
                            }
                            Box(Modifier.width(54.dp).height(42.dp), contentAlignment = Alignment.CenterEnd) {
                                if (!asset.builtin) AvasDeleteButton(strings.format("Видалити %1\$s",
                                    "Delete %1\$s", "删除 %1\$s", asset.filename), colors) {
                                    if (playing) audition.sessionId?.let {
                                        send(profile.id, AvasActionKind.StopAudition, text = it)
                                    }
                                    send(profile.id, AvasActionKind.DeleteAsset, text = asset.id)
                                }
                            }
                        }
                    }
                }
                ActionButton(strings.text("Закрити", "Close", "关闭"), colors,
                    Modifier.fillMaxWidth(.94f).align(Alignment.CenterHorizontally)) { listing = null }
            }
        }
    }
}

private fun avasAssetLabel(asset: AvasAssetUiState, strings: UiStrings): String =
    if (asset.builtin) strings.text("Тест (не включено у «Випадкову мелодію»)",
        "Test (not included in Random melody)", "测试（不参与随机旋律）") else asset.filename

internal fun avasDurationLabel(durationMs: Long?): String {
    if (durationMs == null || durationMs < 0) return "—"
    val seconds = durationMs / 1000 + if (durationMs % 1000 > 0) 1 else 0
    return if (seconds < 3600) "%d:%02d".format(java.util.Locale.ROOT, seconds / 60, seconds % 60)
    else "%d:%02d:%02d".format(java.util.Locale.ROOT,
        seconds / 3600, seconds / 60 % 60, seconds % 60)
}

@Composable
private fun Modifier.avasSwitchRow(
    checked: Boolean, onCheckedChange: (Boolean) -> Unit, colors: UiPalette,
): Modifier {
    val press = rememberPressFeedback()
    return background(pressBackground(Color.Transparent, colors, press.pressed))
        .then(press.modifier).toggleable(value = checked, interactionSource = press.interactionSource,
            indication = null, role = Role.Switch, onValueChange = onCheckedChange)
}

@Composable
private fun AvasVectorButton(
    icon: ImageVector, description: String, colors: UiPalette, tint: Color,
    enabled: Boolean = true, onClick: () -> Unit,
) {
    val press = rememberPressFeedback(enabled)
    val visualClick = rememberVisualFirstClick(onClick)
    Box(Modifier.size(42.dp).clip(RoundedCornerShape(7.dp))
        .border(1.dp, tint.copy(alpha = .85f), RoundedCornerShape(7.dp))
        .background(if (!enabled) colors.disabled else if (press.pressed) tint.copy(alpha = .72f)
            else tint.copy(alpha = if (colors.dark) .20f else .12f)).then(press.modifier)
        .clickable(interactionSource = press.interactionSource, indication = null,
            enabled = enabled, role = Role.Button, onClick = visualClick).padding(6.dp),
        contentAlignment = Alignment.Center) {
        Icon(icon, description, tint = if (!enabled) colors.muted.copy(alpha = .62f)
            else if (press.pressed) Color.White else tint, modifier = Modifier.size(28.dp))
    }
}

@Composable
private fun AvasDeleteButton(description: String, colors: UiPalette, onClick: () -> Unit) {
    val press = rememberPressFeedback()
    val visualClick = rememberVisualFirstClick(onClick)
    Box(Modifier.size(42.dp).clip(RoundedCornerShape(7.dp))
        .border(1.dp, colors.red.copy(alpha = .85f), RoundedCornerShape(7.dp))
        .background(if (press.pressed) colors.red.copy(alpha = .55f) else colors.red.copy(alpha = .16f))
        .then(press.modifier).clickable(interactionSource = press.interactionSource,
            indication = null, role = Role.Button, onClick = visualClick).padding(9.dp),
        contentAlignment = Alignment.Center) {
        Icon(painterResource(R.drawable.ic_delete), description, tint = colors.red,
            modifier = Modifier.size(24.dp))
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
                if (state.archiveProcessedBytes != null) {
                    if (state.progress == null) LinearProgressIndicator(Modifier.fillMaxWidth(),
                        color = colors.accent, trackColor = colors.border)
                    else LinearProgressIndicator(progress = { state.progress }, Modifier.fillMaxWidth(),
                        color = colors.accent, trackColor = colors.border)
                    Text(state.progress?.let { "${(it * 100).toInt()}%" } ?: "—",
                        color = colors.text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    val context = androidx.compose.ui.platform.LocalContext.current
                    listOf(
                        strings.text("Загальний обсяг", "Total size", "总大小") to state.archiveTotalBytes,
                        strings.text("Оброблено", "Processed", "已处理") to state.archiveProcessedBytes,
                        strings.text("Залишилось", "Remaining", "剩余") to state.archiveTotalBytes?.let {
                            (it - state.archiveProcessedBytes).coerceAtLeast(0) },
                    ).forEach { (label, bytes) ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(label, Modifier.weight(1f), color = colors.muted, fontSize = 15.sp)
                            Text(bytes?.let { android.text.format.Formatter.formatFileSize(context, it) }
                                ?: strings.text("Уточнюється…", "Determining…", "计算中…"),
                                color = colors.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                } else if (!state.updatePresentation) state.progress?.let { progress ->
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
                if (state.kind != DialogKind.Background) ActionButton(
                    if (state.kind == DialogKind.Shutdown) strings.text("Зупинити", "Stop")
                    else strings.text("Готово", "Done"),
                    colors,
                    Modifier.width(138.dp),
                    primary = state.kind != DialogKind.Shutdown,
                    destructive = state.kind == DialogKind.Shutdown,
                    enabled = state.confirmEnabled,
                ) { onAction(BydExtendUiAction.Run(CommandId.ConfirmDialog)) }
                if (state.kind != DialogKind.Background && state.cancellable) ActionButton(strings.text("Скасувати", "Cancel"), colors, Modifier.width(138.dp)) {
                    onAction(BydExtendUiAction.Run(if (state.kind == DialogKind.Progress) CommandId.CancelOperation
                    else CommandId.DismissDialog))
                }
            } else if ((state.cancellable && state.dismissLabel != null) ||
                (state.confirmVisible && state.confirmLabel != null)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
                    if (state.confirmVisible && state.confirmLabel != null) ActionButton(
                        state.confirmLabel,
                        colors,
                        Modifier.width(138.dp),
                        primary = state.kind != DialogKind.Shutdown,
                        destructive = state.kind == DialogKind.Shutdown,
                        enabled = state.confirmEnabled,
                    ) { onAction(BydExtendUiAction.Run(CommandId.ConfirmDialog)) }
                    if (state.cancellable && state.dismissLabel != null) ActionButton(
                        state.dismissLabel, colors, Modifier.width(138.dp),
                    ) { onAction(BydExtendUiAction.Run(dismissCommand)) }
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
