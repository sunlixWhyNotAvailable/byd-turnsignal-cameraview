package com.byd.extend.ui.motion

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.ViewTreeObserver
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.core.content.ContextCompat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import kotlin.math.abs
import java.util.Locale
import com.byd.extend.MotionConfig
import com.byd.extend.MotionRuntime
import com.byd.extend.CameraChargeSettings
import com.byd.extend.CameraModeSettings
import com.byd.extend.R
import com.byd.extend.ui.ActionButton as ProductionActionButton
import com.byd.extend.ui.ChoiceField
import com.byd.extend.ui.ImeDismissalEffect
import com.byd.extend.ui.MirrorBorderColorLine
import com.byd.extend.ui.MirrorBorderColorPicker
import com.byd.extend.ui.Section
import com.byd.extend.ui.Segmented
import com.byd.extend.ui.SwitchLine
import com.byd.extend.ui.FormScope
import com.byd.extend.ui.FormSection
import com.byd.extend.ui.icons.Palette
import com.byd.extend.ui.icons.Visibility
import com.byd.extend.ui.icons.Videocam
import com.byd.extend.ui.UiLanguage
import com.byd.extend.ui.UiPalette
import com.byd.extend.ui.UiStrings
import com.byd.extend.ui.palette

@Composable
internal fun FormScope.MotionIntegrationScreen(
    colors: UiPalette,
    strings: UiStrings,
    cameraHost: @Composable (com.byd.extend.ui.CameraHostSlot) -> Unit,
    archiveState: MotionArchiveState,
    uiSession: com.byd.extend.ui.RuntimeUiSession.Session,
): FormScope {
    val context = LocalContext.current
    val store = remember(context) { MotionTemplateStore(context) }
    val config = remember(context) { MotionConfig.read(context) }
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    var library by remember { mutableStateOf(store.read()) }
    var enabled by rememberSaveable { mutableStateOf(config.warningEnabled) }
    var anyModeEnabled by remember(context) { mutableStateOf(config.anyEnabled()) }
    var lock by rememberSaveable { mutableIntStateOf(config.lockCondition) }
    var recordingSensitivity by remember(context) { mutableIntStateOf(config.recordingSensitivity) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(store, lifecycle, context) {
        val main = Handler(Looper.getMainLooper())
        val reloadSettings = {
            val latest = MotionConfig.read(context)
            enabled = latest.warningEnabled
            anyModeEnabled = latest.anyEnabled()
            lock = MotionConfig.normalizeLockMode(latest.lockCondition)
            recordingSensitivity = latest.recordingSensitivity
        }
        val preferenceListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "enabled" || key == "lock" || key == CameraChargeSettings.KEY_REMOTE_VIEWING_ENABLED
                || key == MotionConfig.KEY_RECORDING_SENSITIVITY)
                main.post { reloadSettings() }
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) reloadSettings()
        }
        val modePreferences = listOf(store.preferences,
            context.getSharedPreferences(MotionConfig.RECORDING_PREFS, Context.MODE_PRIVATE),
            context.getSharedPreferences(CameraChargeSettings.PREFS, Context.MODE_PRIVATE))
        modePreferences.forEach { it.registerOnSharedPreferenceChangeListener(preferenceListener) }
        lifecycle.addObserver(observer)
        onDispose {
            modePreferences.forEach { it.unregisterOnSharedPreferenceChangeListener(preferenceListener) }
            lifecycle.removeObserver(observer)
        }
    }
    val activationMode = MotionConfig.normalizeLockMode(lock)
    LaunchedEffect(store) {
        val storedLock = store.preferences.getInt("lock", 1)
        val normalized = MotionConfig.normalizeLockMode(storedLock)
        if (!store.preferences.contains("lock") || storedLock != normalized)
            store.preferences.edit().putInt("lock", normalized).apply()
    }
    LaunchedEffect(lock) { if (lock != activationMode) lock = activationMode }
    var dialog by rememberSaveable { mutableIntStateOf(0) }
    var draft by rememberSaveable { mutableStateOf<MotionTemplate?>(null) }
    var importing by remember { mutableStateOf(false) }
    var tab by remember(uiSession) { mutableIntStateOf(uiSession.motionCameraTab()) }
    var archiveRefreshKey by remember { mutableIntStateOf(0) }
    var checkupOpen by rememberSaveable { mutableStateOf(false) }
    val chargeState = rememberCameraChargeState(context)
    var socThreshold by remember(context) {
        mutableIntStateOf(CameraChargeSettings.getSocThresholdPercent(context).coerceIn(10, 90))
    }
    var voltageThreshold by remember(context) {
        mutableFloatStateOf(CameraChargeSettings.getVoltageThresholdVolts(context).coerceIn(12f, 13f))
    }
    val battery: @Composable () -> Unit = {
        CameraChargeSettingsSection(colors, strings, chargeState, socThreshold, voltageThreshold,
            enabled = anyModeEnabled,
            onSocThreshold = {
                socThreshold = it
                CameraChargeSettings.setSocThresholdPercent(context, it)
            },
            onVoltageThreshold = {
                voltageThreshold = it
                CameraChargeSettings.setVoltageThresholdVolts(context, it)
            })
    }
    val archivePlayerOpen = archiveState.playerEntry != null
    DisposableEffect(archiveState) {
        onDispose {
            archiveState.playerEntry = null
            archiveState.archiveOpen = false
        }
    }
    LaunchedEffect(lifecycle, archiveState, archiveState.archiveOpen, archivePlayerOpen, tab, archiveRefreshKey) {
        if ((tab != 1 && !archiveState.archiveOpen) || archivePlayerOpen) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            archiveState.refreshSnapshot()
            while (isActive) {
                delay(5_000)
                archiveState.refreshSnapshot(backgroundOnly = true)
            }
        }
    }
    fun persist(next: MotionTemplates): Boolean = try {
        store.write(next)
        library = next
        true
    } catch (_: Exception) {
        Toast.makeText(context, strings.motionText(R.string.motion_save_error), Toast.LENGTH_LONG).show()
        false
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            importing = true
            try {
                val image = withContext(Dispatchers.IO) { store.import(uri) }
                draft = MotionTemplate("", strings.motionText(R.string.motion_own), image)
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) {
                Toast.makeText(context, strings.motionText(R.string.motion_import_error), Toast.LENGTH_LONG).show()
            } finally { importing = false }
        }
    }
    row("camera-checkup") {
        MotionCheckupSection(colors, strings, parkedBatteryNotice = tab != 2) { checkupOpen = true }
        Spacer(Modifier.height(12.dp))
    }
    row("motion-shared-archive") {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            if (tab != 2) Section(strings.motionText(R.string.motion_recording_sensitivity), colors,
                Modifier.weight(1f).fillMaxHeight()) {
                Segmented(listOf(R.string.motion_sensitivity_low, R.string.motion_sensitivity_medium,
                    R.string.motion_sensitivity_high).map { strings.motionText(it) },
                    recordingSensitivity, colors, Modifier.fillMaxWidth().semantics {
                        contentDescription = strings.motionText(R.string.motion_recording_sensitivity)
                    }) { next ->
                    if (next != recordingSensitivity) {
                        store.preferences.edit().putInt(MotionConfig.KEY_RECORDING_SENSITIVITY, next).apply()
                        recordingSensitivity = next
                        MotionRuntime.settingsChanged(context)
                    }
                }
            }
            Section(strings.motionText(R.string.motion_archive), colors, Modifier.weight(1f).fillMaxHeight()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(strings.motionText(R.string.motion_archive_hint), color = colors.muted, fontSize = 13.sp,
                        modifier = Modifier.weight(1f))
                    ActionButton(strings.motionText(R.string.motion_archive), Icons.Outlined.Videocam,
                        colors) { archiveState.archiveOpen = true }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
    }
    row("camera-tabs") {
        val tabOrder = listOf(2, 0, 1)
        Segmented(listOf(R.string.cameras_viewing, R.string.cameras_tablet, R.string.cameras_recording)
            .map { strings.motionText(it) }, tabOrder.indexOf(tab), colors, Modifier.fillMaxWidth()) {
            focus.clearFocus()
            tab = tabOrder[it]
            uiSession.selectMotionCameraTab(tab)
        }
        Spacer(Modifier.height(12.dp))
    }
    if (tab == 0) {
        row("motion-notice") {
            Text(strings.motionText(R.string.motion_notice), color = colors.muted, fontSize = 13.sp)
            Spacer(Modifier.height(12.dp))
        }
        row("motion-tablet-settings") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Section(strings.motionText(R.string.preview), colors, Modifier.weight(1f)) {
                    Text(strings.motionText(R.string.motion_active), color = colors.text, fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold)
                    Text(templateName(library.active(), strings), color = colors.muted, fontSize = 14.sp)
                    MotionTemplateCanvas(library.active(), store, strings,
                        Modifier.fillMaxWidth().aspectRatio(16f / 9f))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ActionButton(strings.motionText(R.string.motion_edit), Icons.Outlined.Edit, colors,
                            Modifier.weight(1f), enabled = enabled) { draft = library.active().let { it.copy(name = templateName(it, strings)) } }
                        ActionButton(strings.motionText(R.string.motion_show), Icons.Outlined.PlayArrow, colors,
                            Modifier.weight(1f), enabled = enabled) { MotionFullscreenActivity.show(context) }
                    }
                }
                Section(strings.motionText(R.string.settings), colors, Modifier.weight(.8f)) {
                    SwitchLine(strings.motionText(R.string.motion_enable), strings.motionText(R.string.motion_timing), enabled, {
                        try {
                            MotionRuntime.setModeEnabled(context, CameraModeSettings.MODE_TABLET, it)
                            enabled = MotionConfig.read(context).warningEnabled
                        } catch (failure: Exception) {
                            android.util.Log.e("MotionIntegration", "tablet_mode_update_failed", failure)
                            enabled = MotionConfig.read(context).warningEnabled
                            Toast.makeText(context, strings.motionText(R.string.motion_save_error), Toast.LENGTH_LONG).show()
                        }
                    }, colors)
                    ChoiceField(strings.motionText(R.string.motion_lock), listOf(R.string.motion_locked, R.string.motion_any)
                        .map { strings.motionText(it) }, if (activationMode == 1) 0 else 1, { selected ->
                        lock = if (selected == 0) 1 else 0; store.preferences.edit().putInt("lock", lock).apply()
                        MotionRuntime.settingsChanged(context)
                    }, colors, enabled = enabled)
                    ActionButton(strings.motionText(R.string.motion_bases), Icons.Outlined.Palette, colors,
                        Modifier.fillMaxWidth(), enabled = enabled) { dialog = 1 }
                    ActionButton(strings.motionText(R.string.motion_saved), Icons.Outlined.Visibility, colors,
                        Modifier.fillMaxWidth(), enabled = enabled) { dialog = 2 }
                    ActionButton(strings.motionText(if (importing) R.string.motion_importing else R.string.motion_import),
                        palette = colors, modifier = Modifier.fillMaxWidth(), enabled = enabled && !importing) {
                        try { picker.launch(arrayOf("image/png", "image/jpeg", "image/webp")) }
                        catch (_: android.content.ActivityNotFoundException) {
                            Toast.makeText(context, strings.motionText(R.string.motion_import_error), Toast.LENGTH_LONG).show()
                        }
                    }
                    Text(strings.motionText(R.string.motion_import_hint), color = colors.muted, fontSize = 13.sp)
                    Text(strings.motionText(R.string.motion_reference_notice), color = colors.muted, fontSize = 12.sp)
                }
            }
        }
        row("motion-tablet-gap") { Spacer(Modifier.height(12.dp)) }
        row("motion-tablet-battery") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f)) { battery() }
                Spacer(Modifier.weight(.8f))
            }
        }
    }
    CompositionLocalProvider(LocalMotionCameraHost provides cameraHost) {
        MotionRecordingScreen(colors, strings, archiveState, showContent = tab == 1, battery = battery,
            onArchiveRefresh = { archiveRefreshKey++ })
    }
    if (tab == 2) CameraViewingScreen(colors, strings, chargeState)

    if (archiveState.archiveOpen) MotionArchiveDialog(archiveState, colors, strings, onClose = {
        archiveState.playerEntry = null
        archiveState.archiveOpen = false
    })
    if (checkupOpen) MotionCheckupDialog(colors, strings) { checkupOpen = false }
    if (enabled && (dialog == 1 || dialog == 2)) MotionLibraryDialog(dialog == 1, library, store, colors, strings,
        onClose = { dialog = 0 }, onUse = { if (persist(library.copy(activeId = it.id))) dialog = 0 },
        onEdit = { dialog = 0; draft = it.copy(name = templateName(it, strings)) }, onDelete = {
            val remaining = library.delete(it.id)
            if (persist(remaining)) store.discardUnused(it.background, remaining)
        })
    if (enabled) draft?.let { initial ->
        MotionEditor(initial, store, colors, strings, onChange = { draft = it }, onClose = {
            store.discardUnused(initial.background, library); draft = null
        },
            onSave = { if (persist(library.save(it.forSave()))) draft = null })
    }
    return this
}

private fun templateName(template: MotionTemplate, strings: UiStrings): String =
    if (!template.isBase) template.name else strings.motionText(when (template.background) {
        "lens" -> R.string.motion_lens
        "iris" -> R.string.motion_iris
        else -> R.string.motion_owl
    })

private fun blockText(block: MotionText, strings: UiStrings) = when (block.defaultCaption) {
    1 -> strings.motionText(R.string.motion_caption_security)
    2 -> strings.motionText(R.string.motion_caption_movement)
    else -> block.text
}

@Composable
private fun MotionModal(title: String, palette: UiPalette, onClose: () -> Unit,
    content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize(.94f).clip(RoundedCornerShape(10.dp))
                .background(palette.surface).border(1.dp, palette.borderStrong, RoundedCornerShape(10.dp))
                .padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(title, color = palette.text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                content()
        }
    }
}

@Composable
private fun MotionLibraryDialog(base: Boolean, library: MotionTemplates, store: MotionTemplateStore,
    palette: UiPalette, strings: UiStrings, onClose: () -> Unit, onUse: (MotionTemplate) -> Unit,
    onEdit: (MotionTemplate) -> Unit, onDelete: (MotionTemplate) -> Unit) {
    val choices = if (base) remember { MotionTemplate.bases() } else library.saved
    var selectedId by rememberSaveable(base) { mutableStateOf(library.initialChoice(choices)?.id) }
    var deleting by rememberSaveable { mutableStateOf(false) }
    val selected = choices.firstOrNull { it.id == selectedId } ?: library.initialChoice(choices)
    MotionModal(strings.motionText(if (base) R.string.motion_bases else R.string.motion_saved), palette, onClose) {
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(.6f).fillMaxHeight().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (choices.isEmpty()) Text(strings.motionText(R.string.motion_empty), color = palette.muted, fontSize = 16.sp)
                choices.forEach { template ->
                    ActionButton((if (library.activeId == template.id) "✓ " else "") + templateName(template, strings),
                        palette = palette, modifier = Modifier.fillMaxWidth(), primary = library.activeId == template.id,
                        height = 58.dp, maxLines = 2) { selectedId = template.id; deleting = false }
                }
            }
            Column(Modifier.weight(1.4f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (selected != null) {
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        MotionTemplateCanvas(selected, store, strings, Modifier.fillMaxSize())
                    }
                    if (deleting) {
                        Text(strings.motionText(R.string.motion_delete_confirm), color = palette.text, fontSize = 14.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            ActionButton(strings.motionText(R.string.motion_delete), palette = palette, destructive = true,
                                modifier = Modifier.weight(1f)) { onDelete(selected); deleting = false }
                            ActionButton(strings.motionText(R.string.cancel), palette = palette, modifier = Modifier.weight(1f)) { deleting = false }
                        }
                    } else Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ActionButton(strings.motionText(R.string.motion_use), palette = palette, primary = true,
                            modifier = Modifier.weight(1f)) { onUse(selected) }
                        ActionButton(strings.motionText(R.string.motion_edit), palette = palette, modifier = Modifier.weight(1f)) { onEdit(selected) }
                        if (!base) ActionButton(strings.motionText(R.string.motion_delete), Icons.Outlined.Delete,
                            palette, Modifier.size(44.dp), destructive = true) { deleting = true }
                    }
                }
            }
        }
        ActionButton(strings.motionText(R.string.motion_close), palette = palette, modifier = Modifier.fillMaxWidth(), onClick = onClose)
    }
}

@Composable
private fun MotionEditor(template: MotionTemplate, store: MotionTemplateStore, palette: UiPalette,
    strings: UiStrings, onChange: (MotionTemplate) -> Unit, onClose: () -> Unit, onSave: (MotionTemplate) -> Unit) {
    var selectedId by rememberSaveable { mutableStateOf(template.texts.firstOrNull()?.id) }
    var pickingColor by rememberSaveable { mutableStateOf(false) }
    val selected = template.texts.firstOrNull { it.id == selectedId }
    val fontFamilies by produceState<List<FontFamily>>(emptyList(), store) {
        value = withContext(Dispatchers.IO) { store.fonts.map { FontFamily(it) } }
    }
    val focus = LocalFocusManager.current
    fun change(block: MotionText) { onChange(template.withoutBlankTexts(block.id).replace(block)) }
    fun select(id: String?) {
        focus.clearFocus()
        if (id != selectedId) onChange(template.withoutBlankTexts(id))
        selectedId = id
    }
    fun add(point: Offset = Offset(.3f, .45f)) {
        focus.clearFocus()
        val added = MotionText(width = .4f).placed(point.x, point.y)
        onChange(template.withoutBlankTexts().let { it.copy(texts = it.texts + added) })
        selectedId = added.id
    }
    MotionModal(strings.motionText(R.string.motion_editor), palette, onClose) {
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1.5f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(strings.motionText(R.string.motion_editor_hint), color = palette.muted, fontSize = 13.sp)
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    MotionTemplateCanvas(template, store, strings, Modifier.fillMaxSize(), selectedId,
                        onSelect = { select(it) }, onMove = { change(it) }, onAdd = { add(it) })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionButton(strings.motionText(R.string.motion_add_text), palette = palette, modifier = Modifier.weight(1f)) {
                        add()
                    }
                    ActionButton(strings.motionText(R.string.motion_delete_text), palette = palette, destructive = true,
                        modifier = Modifier.weight(1f), enabled = selected != null) {
                        onChange(template.copy(texts = template.texts.filterNot { it.id == selectedId })); selectedId = null
                    }
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                MotionInput(strings.motionText(R.string.motion_name), template.name, palette, singleLine = true) { onChange(template.copy(name = it)) }
                Segmented(listOf(strings.motionText(R.string.motion_fit), strings.motionText(R.string.motion_fill)),
                    if (template.fill) 1 else 0, palette, Modifier.fillMaxWidth()) { onChange(template.copy(fill = it == 1)) }
                Text(strings.motionText(if (template.fill) R.string.motion_fill_hint else R.string.motion_fit_hint),
                    color = palette.muted, fontSize = 13.sp)
                ChoiceField(strings.motionText(R.string.motion_block),
                    listOf(strings.motionText(R.string.motion_select_text)) + template.texts.indices.map { "${it + 1}" },
                    template.texts.indexOfFirst { it.id == selectedId } + 1,
                    { select(template.texts.getOrNull(it - 1)?.id) }, palette)
                if (selected != null) {
                    MotionInput(strings.motionText(R.string.motion_text), blockText(selected, strings), palette,
                        onEditingFinished = {
                            if (selected.defaultCaption == 0 && selected.text.isBlank()) {
                                onChange(template.withoutBlankTexts()); selectedId = null
                            }
                        }) {
                        change(selected.copy(defaultCaption = 0, text = it))
                    }
                    ChoiceField(strings.motionText(R.string.motion_font), listOf(R.string.motion_sans, R.string.motion_serif,
                        R.string.motion_mono, R.string.motion_carlito, R.string.motion_roboto, R.string.motion_opensans,
                        R.string.motion_oswald, R.string.motion_lobster).map { strings.motionText(it) }, selected.font,
                        { change(selected.copy(font = it)) }, palette, optionFonts = fontFamilies)
                    MotionSlider(strings.motionText(R.string.motion_size), selected.size, 16..96, palette) { change(selected.copy(size = it)) }
                    MotionSlider(strings.motionText(R.string.motion_width), (selected.width * 100).roundToInt(), 20..100, palette) {
                        change(selected.copy(width = it / 100f).placed(selected.x, selected.y))
                    }
                    MirrorBorderColorLine(palette, strings, selected.color, { pickingColor = true },
                        strings.motionText(R.string.motion_color))
                    SwitchLine(strings.motionText(R.string.motion_bold), "", selected.bold, { change(selected.copy(bold = it)) }, palette)
                    SwitchLine(strings.motionText(R.string.motion_italic), "", selected.italic, { change(selected.copy(italic = it)) }, palette)
                    ChoiceField(strings.motionText(R.string.motion_alignment), listOf(R.string.motion_left, R.string.motion_center,
                        R.string.motion_right, R.string.motion_justify).map { strings.motionText(it) }, selected.alignment,
                        { change(selected.copy(alignment = it)) }, palette)
                    ChoiceField(strings.motionText(R.string.motion_effect), listOf(R.string.motion_none, R.string.motion_shadow,
                        R.string.motion_outline).map { strings.motionText(it) }, selected.effect, { change(selected.copy(effect = it)) }, palette)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ActionButton(strings.motionText(R.string.motion_save_use), palette = palette, primary = true,
                modifier = Modifier.weight(1f), enabled = template.name.isNotBlank()) { onSave(template.copy(name = template.name.trim())) }
            ActionButton(strings.motionText(R.string.cancel), palette = palette, mainBackground = true,
                modifier = Modifier.weight(1f), onClick = onClose)
        }
    }
    if (pickingColor && selected != null) MirrorBorderColorPicker(palette, strings, selected.color,
        onDismiss = { pickingColor = false },
        onSelect = { change(selected.copy(color = it)); pickingColor = false },
        title = strings.motionText(R.string.motion_color))
}

@Composable
private fun MotionInput(label: String, value: String, palette: UiPalette, singleLine: Boolean = false,
    onEditingFinished: () -> Unit = {}, onChange: (String) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    ImeDismissalEffect(focused) {
        focusManager.clearFocus()
        onEditingFinished()
    }
    Text(label, color = palette.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    BasicTextField(value, onChange, singleLine = singleLine, maxLines = if (singleLine) 1 else 5,
        textStyle = TextStyle(color = palette.text, fontSize = 16.sp), cursorBrush = SolidColor(palette.accent),
        modifier = Modifier.onFocusChanged { focused = it.isFocused }
            .fillMaxWidth().heightIn(min = 42.dp).clip(RoundedCornerShape(7.dp))
            .background(palette.field).border(1.dp, palette.borderStrong, RoundedCornerShape(7.dp))
            .padding(10.dp).semantics { contentDescription = label })
}

@Composable
private fun MotionSlider(label: String, value: Int, range: IntRange, palette: UiPalette, onChange: (Int) -> Unit) {
    Text("$label: $value", color = palette.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    Slider(value.toFloat(), { onChange(it.roundToInt()) }, valueRange = range.first.toFloat()..range.last.toFloat(),
        colors = SliderDefaults.colors(thumbColor = palette.accent, activeTrackColor = palette.accent,
            inactiveTrackColor = palette.borderStrong), modifier = Modifier.semantics { contentDescription = label })
}

private data class MotionLayout(val block: MotionText, val layout: StaticLayout, val rect: Rect)
private val MotionDesignSize = IntSize(1920, 1080)

private fun textLayout(block: MotionText, strings: UiStrings, size: IntSize, font: Typeface): MotionLayout {
    val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = block.color
        textSize = block.size * size.width / 1080f
        typeface = Typeface.create(font,
            when {
                block.bold && block.italic -> Typeface.BOLD_ITALIC
                block.bold -> Typeface.BOLD
                block.italic -> Typeface.ITALIC
                else -> Typeface.NORMAL
            })
    }
    val text = blockText(block, strings)
    val width = (block.width * size.width).roundToInt().coerceAtLeast(1)
    fun build() = StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
        .setAlignment(when (block.alignment) { 0, 3 -> Layout.Alignment.ALIGN_NORMAL; 2 -> Layout.Alignment.ALIGN_OPPOSITE; else -> Layout.Alignment.ALIGN_CENTER })
        .setJustificationMode(if (block.alignment == 3) Layout.JUSTIFICATION_MODE_INTER_WORD else Layout.JUSTIFICATION_MODE_NONE)
        .setIncludePad(true).build()
    var layout = build()
    // Long captions shrink to fit the canvas; never silently discard their ending.
    if (layout.height > size.height) {
        while (layout.height > size.height && paint.textSize > 1f) {
            paint.textSize = (paint.textSize * .85f).coerceAtLeast(1f)
            layout = build()
        }
    }
    val placed = block.placed(block.x, block.y, layout.height.toFloat() / size.height)
    val x = placed.x * size.width
    val y = placed.y * size.height
    return MotionLayout(block, layout, Rect(x, y, x + width, y + layout.height))
}

@Composable
internal fun MotionTemplateCanvas(template: MotionTemplate, store: MotionTemplateStore, strings: UiStrings,
    modifier: Modifier, selectedId: String? = null, onSelect: ((String?) -> Unit)? = null,
    onMove: ((MotionText) -> Unit)? = null, onAdd: ((Offset) -> Unit)? = null,
    readyKey: Any? = null, onFirstRendered: (() -> Unit)? = null) {
    var bitmap by remember(template.background) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(template.background) {
        bitmap = withContext(Dispatchers.IO) { store.previewBitmap(template.background) }
    }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    // Lay out once in design pixels: viewport-specific font rounding changes wrapping and bottom clamping.
    val fonts by produceState<List<Typeface>>(emptyList(), store) {
        value = withContext(Dispatchers.IO) { store.fonts }
    }
    val designLayouts = remember(template.texts, strings, fonts) {
        if (fonts.isEmpty()) emptyList() else template.texts.map { textLayout(it, strings, MotionDesignSize, fonts[it.font]) }
    }
    val layouts = remember(designLayouts, canvasSize) {
        val sx = canvasSize.width / MotionDesignSize.width.toFloat()
        val sy = canvasSize.height / MotionDesignSize.height.toFloat()
        designLayouts.map { it.copy(rect = Rect(it.rect.left * sx, it.rect.top * sy, it.rect.right * sx, it.rect.bottom * sy)) }
    }
    val currentLayouts by rememberUpdatedState(layouts)
    val currentSelected by rememberUpdatedState(selectedId)
    val move by rememberUpdatedState(onMove)
    val select by rememberUpdatedState(onSelect)
    val add by rememberUpdatedState(onAdd)
    val view = LocalView.current
    DisposableEffect(view, template.background, readyKey, bitmap, fonts.isNotEmpty(), layouts.size,
        onFirstRendered,
        canvasSize.width, canvasSize.height) {
        if ((bitmap == null && layouts.isEmpty()) || fonts.isEmpty() || canvasSize.width <= 0 ||
            canvasSize.height <= 0 || onFirstRendered == null) {
            onDispose { }
        } else {
            val observer = view.viewTreeObserver
            var active = true
            val listener = object : ViewTreeObserver.OnDrawListener {
                private var sent = false
                override fun onDraw() {
                    if (sent) return
                    sent = true
                    // Android forbids changing the listener list during draw dispatch.
                    view.post {
                        if (observer.isAlive) observer.removeOnDrawListener(this)
                        if (active) onFirstRendered.invoke()
                    }
                }
            }
            observer.addOnDrawListener(listener)
            onDispose {
                active = false
                view.post { if (observer.isAlive) observer.removeOnDrawListener(listener) }
            }
        }
    }
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val width = minOf(maxWidth, maxHeight * 16f / 9f)
        Box(Modifier.width(width).aspectRatio(16f / 9f).clipToBounds().background(Color.Black)) {
            bitmap?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(),
                contentScale = if (template.fill) ContentScale.Crop else ContentScale.Fit) }
            Canvas(Modifier.fillMaxSize().onSizeChanged { canvasSize = it }
                .semantics { contentDescription = strings.motionText(R.string.motion_canvas) }
                .then(if (onSelect == null) Modifier else Modifier
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            val hit = currentLayouts.lastOrNull { it.rect.contains(down.position) }
                            val selected = currentLayouts.firstOrNull { it.block.id == currentSelected }
                            // Select a hit immediately; the resize long-press branch must not swallow a short tap.
                            if (hit != null) { down.consume(); select?.invoke(hit.block.id) }
                            val edge = selected?.takeIf {
                                down.position.y in it.rect.top..it.rect.bottom &&
                                    minOf(abs(down.position.x - it.rect.left), abs(down.position.x - it.rect.right)) <= 14.dp.toPx()
                            }
                            if (edge != null) {
                                val held = awaitLongPressOrCancellation(down.id)
                                if (held != null) {
                                    val leftEdge = abs(down.position.x - edge.rect.left) <= abs(down.position.x - edge.rect.right)
                                    var block = edge.block.placed(edge.rect.left / canvasSize.width, edge.rect.top / canvasSize.height)
                                    drag(held.id) { change ->
                                        block = block.resized(leftEdge, change.positionChange().x / canvasSize.width)
                                        change.consume()
                                        move?.invoke(block)
                                    }
                                }
                            } else {
                                var overSlop = Offset.Zero
                                val start = awaitTouchSlopOrCancellation(down.id) { change, over ->
                                    change.consume(); overSlop = over
                                }
                                if (start == null) {
                                    val up = currentEvent.changes.firstOrNull { it.id == down.id }
                                    if (up != null && !up.pressed && !up.isConsumed) {
                                        if (hit == null) {
                                            if (currentSelected != null) select?.invoke(null)
                                            else add?.invoke(Offset(down.position.x / canvasSize.width, down.position.y / canvasSize.height))
                                        }
                                    }
                                } else {
                                    var position = hit?.rect?.topLeft ?: Offset.Zero
                                    fun moveBy(amount: Offset) {
                                        if (hit == null) return
                                        position += amount
                                        val placed = hit.block.placed(position.x / canvasSize.width, position.y / canvasSize.height,
                                            hit.rect.height / canvasSize.height)
                                        position = Offset(placed.x * canvasSize.width, placed.y * canvasSize.height)
                                        move?.invoke(placed)
                                    }
                                    moveBy(overSlop)
                                    drag(start.id) { change ->
                                        moveBy(change.positionChange()); change.consume()
                                    }
                                }
                            }
                        }
                    })) {
                val canvas = drawContext.canvas.nativeCanvas
                layouts.forEach { item ->
                    val paint = item.layout.paint
                    canvas.save()
                    canvas.translate(item.rect.left, item.rect.top)
                    canvas.scale(canvasSize.width / MotionDesignSize.width.toFloat(), canvasSize.height / MotionDesignSize.height.toFloat())
                    if (item.block.effect == 1) paint.setShadowLayer(3f, 1f, 2f, android.graphics.Color.BLACK)
                    if (item.block.effect == 2) {
                        paint.color = if (item.block.color == android.graphics.Color.BLACK) android.graphics.Color.WHITE else android.graphics.Color.BLACK
                        paint.style = Paint.Style.STROKE; paint.strokeWidth = 3f
                        item.layout.draw(canvas)
                        paint.style = Paint.Style.FILL; paint.color = item.block.color
                    }
                    item.layout.draw(canvas)
                    paint.clearShadowLayer()
                    canvas.restore()
                    if (onSelect != null && item.block.id == selectedId) {
                        val accent = Color(0xFF2F86F6)
                        drawRect(accent, item.rect.topLeft, item.rect.size, style = androidx.compose.ui.graphics.drawscope.Stroke(2f))
                        drawCircle(accent, 5.dp.toPx(), Offset(item.rect.left, item.rect.center.y))
                        drawCircle(accent, 5.dp.toPx(), Offset(item.rect.right, item.rect.center.y))
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionButton(
    text: String,
    icon: ImageVector? = null,
    palette: UiPalette,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    destructive: Boolean = false,
    enabled: Boolean = true,
    mainBackground: Boolean = false,
    height: Dp = 44.dp,
    maxLines: Int = 1,
    onClick: () -> Unit,
) = ProductionActionButton(text, palette, modifier, icon, primary, destructive, enabled,
    mainBackground, height, maxLines, onClick)

private fun UiStrings.motionText(id: Int, vararg args: Any): String {
    val value = resource(id)
    return if (args.isEmpty()) value else String.format(Locale.getDefault(), value, *args)
}
