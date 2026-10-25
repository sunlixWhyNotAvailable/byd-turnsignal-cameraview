package com.byd.extend.ui.motion

import android.Manifest
import android.app.DatePickerDialog
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.LocaleList
import android.util.Log
import android.view.ContextThemeWrapper
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.byd.extend.MotionConfig
import com.byd.extend.CameraModeClient
import com.byd.extend.CameraModeSettings
import com.byd.extend.MotionDirectionIcon
import com.byd.extend.MotionMediaLibrary
import com.byd.extend.MotionRuntime
import com.byd.extend.AppLanguage
import com.byd.extend.R
import com.byd.extend.ui.ActionButton as ProductionActionButton
import com.byd.extend.ui.NumericSetting
import com.byd.extend.ui.ChoiceField
import com.byd.extend.ui.Segmented
import com.byd.extend.ui.Section
import com.byd.extend.ui.SwitchLine
import com.byd.extend.ui.FormScope
import com.byd.extend.ui.FormSection
import com.byd.extend.ui.UiPalette
import com.byd.extend.ui.UiStrings
import com.byd.extend.ui.icons.DirectionsCar
import com.byd.extend.ui.icons.Checklist
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale
import kotlin.math.roundToInt

private const val MOTION_GB = 1_000_000_000L
private data class RecordingOptions(
    val enabled: Boolean,
    val before: Int,
    val after: Int,
    val audio: Boolean,
    val quality: Int,
    val sd: Boolean,
    val fallback: Boolean,
    val internalLimit: Int,
    val sdLimit: Int,
    val internalKeepFree: Boolean,
    val sdKeepFree: Boolean,
    val internalFreeGb: Int,
    val sdFreeGb: Int,
    val oemCleanup: Boolean,
    val oemQuota: Int,
    val cameraOrder: List<Int>,
) {
    fun write(preferences: android.content.SharedPreferences) = preferences.edit()
        .putInt("before", before).putInt("after", after)
        .putBoolean("audio", audio).putInt("quality", quality).putBoolean("sd", sd)
        .putBoolean("fallback", fallback).putInt("internalLimit", internalLimit).putInt("sdLimit", sdLimit)
        .putBoolean("internalKeepFree", internalKeepFree).putBoolean("sdKeepFree", sdKeepFree)
        .putInt("internalFreeGb", internalFreeGb).putInt("sdFreeGb", sdFreeGb)
        .putBoolean("oemCleanup", oemCleanup).putInt("oemQuota", oemQuota)
        .putString("order", cameraOrder.joinToString(",")).apply()

    companion object {
        fun from(config: MotionConfig) = RecordingOptions(
            config.recordingEnabled, config.beforeSeconds, config.afterSeconds, config.audioEnabled,
            config.quality, config.primarySd, config.fallbackEnabled, config.internalLimitGb,
            config.sdLimitGb, config.internalKeepFree, config.sdKeepFree, config.internalFreeGb,
            config.sdFreeGb, config.oemCleanup, config.oemLimitGb, config.cameraOrder.toList())
    }
}

@Composable
internal fun FormScope.MotionRecordingScreen(
    colors: UiPalette,
    strings: UiStrings,
    archiveState: MotionArchiveState,
    showContent: Boolean,
    battery: @Composable () -> Unit,
    onArchiveRefresh: () -> Unit,
): FormScope {
    val context = LocalContext.current
    val preferences = remember(context) {
        context.getSharedPreferences(MotionConfig.RECORDING_PREFS, Context.MODE_PRIVATE)
    }
    var options by remember(context) { mutableStateOf(RecordingOptions.from(MotionConfig.read(context))) }
    val stats = archiveState.stats
    var composition by rememberSaveable { mutableStateOf(false) }
    var microphoneGranted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
    }
    var storageReadGranted by remember {
        mutableStateOf(Build.VERSION.SDK_INT < 23 || ContextCompat.checkSelfPermission(
            context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED)
    }
    var storageWriteGranted by remember {
        mutableStateOf(Build.VERSION.SDK_INT < 23 || ContextCompat.checkSelfPermission(
            context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED)
    }
    var confirmOemCleanup by rememberSaveable { mutableStateOf(false) }
    var pendingRecordingEnable by rememberSaveable { mutableStateOf(false) }
    var pendingOemEnable by rememberSaveable { mutableStateOf(false) }

    fun save(next: RecordingOptions) {
        val latestEnabled = MotionConfig.read(context).recordingEnabled
        val nextWithoutModeChange = next.copy(enabled = latestEnabled)
        options = nextWithoutModeChange
        nextWithoutModeChange.write(preferences)
        MotionRuntime.settingsChanged(context)
        onArchiveRefresh()
    }

    fun setRecordingMode(enabled: Boolean) {
        try {
            MotionRuntime.setModeEnabled(context, CameraModeSettings.MODE_RECORDING, enabled)
            options = RecordingOptions.from(MotionConfig.read(context))
            onArchiveRefresh()
        } catch (failure: CameraModeClient.ModeException) {
            Log.e("MotionRecording", "recording_mode_update_failed code=${failure.code()}", failure)
            options = RecordingOptions.from(MotionConfig.read(context))
            val message = if (failure.code() == "permission_required")
                R.string.motion_record_permission_denied else R.string.motion_save_error
            Toast.makeText(context, strings.motionText(message), Toast.LENGTH_LONG).show()
        } catch (failure: Exception) {
            Log.e("MotionRecording", "recording_mode_update_failed", failure)
            options = RecordingOptions.from(MotionConfig.read(context))
            Toast.makeText(context, strings.motionText(R.string.motion_save_error), Toast.LENGTH_LONG).show()
        }
    }

    LaunchedEffect(microphoneGranted, options.audio) {
        if (options.audio && !microphoneGranted) save(options.copy(audio = false))
    }

    val microphonePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        microphoneGranted = granted
        if (granted) save(options.copy(audio = true))
        else Toast.makeText(context, strings.motionText(R.string.motion_audio_permission_denied), Toast.LENGTH_LONG).show()
    }
    fun refreshStoragePermissions(): Pair<Boolean, Boolean> {
        val read = Build.VERSION.SDK_INT < 23 || ContextCompat.checkSelfPermission(
            context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        val write = Build.VERSION.SDK_INT < 23 || ContextCompat.checkSelfPermission(
            context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        storageReadGranted = read
        storageWriteGranted = write
        return read to write
    }
    val recordingStoragePermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val (read, write) = refreshStoragePermissions()
        onArchiveRefresh()
        if (pendingRecordingEnable) {
            pendingRecordingEnable = false
            if (motionStoragePermissionsGranted(motionStoragePermissionNeeds(Build.VERSION.SDK_INT), read, write)) {
                setRecordingMode(true)
            } else Toast.makeText(context, strings.motionText(R.string.motion_record_permission_denied), Toast.LENGTH_LONG).show()
        }
    }
    val oemStoragePermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val (read, write) = refreshStoragePermissions()
        onArchiveRefresh()
        if (pendingOemEnable) {
            pendingOemEnable = false
            if (read && write) save(options.copy(oemCleanup = true))
            else Toast.makeText(context, strings.motionText(R.string.motion_oem_permission_denied), Toast.LENGTH_LONG).show()
        }
    }

    fun setRecordingEnabled(enabled: Boolean) {
        if (!enabled) {
            pendingRecordingEnable = false
            setRecordingMode(false)
            return
        }
        val needs = motionStoragePermissionNeeds(Build.VERSION.SDK_INT)
        val (read, write) = refreshStoragePermissions()
        if (motionStoragePermissionsGranted(needs, read, write)) {
            setRecordingMode(true)
        } else {
            pendingRecordingEnable = true
            val missing = buildList {
                if (needs.read && !read) add(Manifest.permission.READ_EXTERNAL_STORAGE)
                if (needs.write && !write) add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
            recordingStoragePermissions.launch(missing.toTypedArray())
        }
    }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, context) {
        val main = Handler(Looper.getMainLooper())
        val reloadSettings = {
            options = RecordingOptions.from(MotionConfig.read(context))
        }
        val preferenceListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            main.post { reloadSettings() }
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val (read, write) = refreshStoragePermissions()
                val current = RecordingOptions.from(MotionConfig.read(context))
                options = current
                val recordingDenied = current.enabled && !motionStoragePermissionsGranted(
                    motionStoragePermissionNeeds(Build.VERSION.SDK_INT), read, write)
                val oemDenied = current.oemCleanup && (!read || !write)
                if (recordingDenied || oemDenied) {
                    if (recordingDenied) setRecordingMode(false)
                    if (oemDenied) save(RecordingOptions.from(MotionConfig.read(context)).copy(oemCleanup = false))
                    val message = if (recordingDenied) R.string.motion_record_permission_denied
                        else R.string.motion_oem_permission_denied
                    Toast.makeText(context, strings.motionText(message), Toast.LENGTH_LONG).show()
                }
            }
        }
        preferences.registerOnSharedPreferenceChangeListener(preferenceListener)
        lifecycle.addObserver(observer)
        onDispose {
            preferences.unregisterOnSharedPreferenceChangeListener(preferenceListener)
            lifecycle.removeObserver(observer)
        }
    }
    if (showContent) {
        FormSection(strings.motionText(R.string.motion_video_title), colors, key = "motion-recording") {
            row("enable") {
                SwitchLine(strings.motionText(R.string.motion_video_enable), strings.motionText(R.string.motion_video_preview),
                    options.enabled, ::setRecordingEnabled, colors)
            }
            row("options") {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        RecordingNumber(strings.motionText(R.string.motion_before), options.before,
                            strings.motionText(R.string.motion_seconds), colors, options.enabled) {
                            save(options.copy(before = it))
                        }
                        RecordingNumber(strings.motionText(R.string.motion_after), options.after,
                            strings.motionText(R.string.motion_seconds), colors, options.enabled) {
                            save(options.copy(after = it))
                        }
                        Text(strings.motionText(R.string.motion_buffer_hint), color = colors.muted, fontSize = 13.sp)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        SwitchLine(strings.motionText(R.string.motion_audio), strings.motionText(R.string.motion_audio_hint),
                            options.audio && microphoneGranted, { enabled ->
                                if (!enabled) save(options.copy(audio = false))
                                else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                    microphoneGranted = true
                                    save(options.copy(audio = true))
                                } else microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
                            }, colors, enabled = options.enabled)
                        if (!microphoneGranted) {
                            Text(strings.motionText(R.string.motion_audio_permission_denied), color = colors.yellow, fontSize = 12.sp)
                        }
                        Text(strings.motionText(R.string.motion_quality), color = colors.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Segmented(listOf(R.string.motion_quality_low, R.string.motion_quality_medium,
                            R.string.motion_quality_high).map { strings.motionText(it) }, options.quality,
                            colors, Modifier.fillMaxWidth(), enabled = { options.enabled }) {
                            save(options.copy(quality = it))
                        }
                        Text(strings.motionText(R.string.motion_quality_hint,
                            listOf(1280, 1920, 2560)[options.quality], listOf(2, 4, 8)[options.quality]),
                            color = colors.muted, fontSize = 13.sp)
                        ActionButton(strings.motionText(R.string.motion_composition_edit), Icons.Outlined.DirectionsCar,
                            colors, Modifier.fillMaxWidth()) { composition = true }
                    }
                }
            }
            this
        }

        row("motion-video-gap") { Spacer(Modifier.height(12.dp)) }
        FormSection(strings.motionText(R.string.motion_storage), colors, key = "motion-storage") {
            row("primary") {
                Text(strings.motionText(R.string.motion_storage_primary), color = colors.text, fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold)
                Segmented(listOf(strings.motionText(R.string.motion_storage_internal), strings.motionText(R.string.motion_storage_sd)),
                    if (options.sd) 1 else 0, colors, Modifier.fillMaxWidth(),
                    enabled = { index -> val volume = if (index == 0) stats?.primary else stats?.secondary
                        volume?.mounted == true && !volume.readOnly }) { save(options.copy(sd = it == 1)) }
            }
            row("fallback") {
                val backup = strings.motionText(if (options.sd) R.string.motion_storage_internal else R.string.motion_storage_sd)
                SwitchLine(strings.motionText(R.string.motion_fallback, backup), strings.motionText(R.string.motion_fallback_hint),
                    options.fallback, { save(options.copy(fallback = it)) }, colors)
            }
            row("storage-hint") { Text(strings.motionText(R.string.motion_storage_hint), color = colors.muted, fontSize = 13.sp) }
            row("volumes") {
                MotionStorageCards(stats, options, colors, strings,
                    onInternalLimit = { save(options.copy(internalLimit = it)) },
                    onInternalKeep = { save(options.copy(internalKeepFree = it)) },
                    onInternalFree = { save(options.copy(internalFreeGb = it)) },
                    onSdLimit = { save(options.copy(sdLimit = it)) },
                    onSdKeep = { save(options.copy(sdKeepFree = it)) },
                    onSdFree = { save(options.copy(sdFreeGb = it)) })
            }
            row("oem-cleanup") {
                SwitchLine(strings.motionText(R.string.motion_oem_cleanup), strings.motionText(R.string.motion_oem_cleanup_hint),
                    options.oemCleanup, { enabled ->
                        if (enabled) confirmOemCleanup = true else save(options.copy(oemCleanup = false))
                    }, colors)
            }
            row("oem-quota") {
                val oemMaxGb = stats?.volumes.orEmpty().filter { it.mounted && it.capacityBytes > 0 }
                    .maxOfOrNull { (it.capacityBytes / MOTION_GB).toInt().coerceAtLeast(1) }
                RecordingLimit(strings.motionText(R.string.motion_oem_quota), options.oemQuota, oemMaxGb,
                    strings.motionText(R.string.motion_gb), colors, strings, enabled = true, identity = "motion-oem-limit",
                    onChange = { save(options.copy(oemQuota = it)) })
                Text(strings.motionText(R.string.motion_oem_cleanup_unavailable), color = colors.muted, fontSize = 12.sp)
            }
            this
        }

        row("motion-storage-gap") { Spacer(Modifier.height(12.dp)) }
        row("motion-recording-battery") {
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Column(Modifier.weight(1f).fillMaxHeight()) { battery() }
                Spacer(Modifier.weight(.8f))
            }
        }
    }

    if (showContent && composition) MotionCompositionDialog(options.cameraOrder, colors, strings,
        onClose = { composition = false }, onSave = {
            save(options.copy(cameraOrder = it)); composition = false
        })
    if (showContent && confirmOemCleanup) Dialog(onDismissRequest = { confirmOemCleanup = false }) {
        Column(Modifier.fillMaxWidth(.9f).clip(RoundedCornerShape(10.dp)).background(colors.surface)
            .border(1.dp, colors.borderStrong, RoundedCornerShape(10.dp)).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(strings.motionText(R.string.motion_oem_enable_title), color = colors.text,
                fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text(strings.motionText(R.string.motion_oem_enable_confirm), color = colors.muted, fontSize = 14.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ActionButton(strings.motionText(R.string.motion_oem_enable), colors = colors,
                    modifier = Modifier.weight(1f), primary = true) {
                    confirmOemCleanup = false
                    if (storageReadGranted && storageWriteGranted) save(options.copy(oemCleanup = true))
                    else {
                        pendingOemEnable = true
                        oemStoragePermissions.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE,
                            Manifest.permission.WRITE_EXTERNAL_STORAGE))
                    }
                }
                ActionButton(strings.motionText(R.string.cancel), colors = colors,
                    modifier = Modifier.weight(1f)) { confirmOemCleanup = false }
            }
        }
    }
    return this
}

@Composable
private fun RecordingNumber(title: String, value: Int, suffix: String, colors: UiPalette,
    enabled: Boolean = true, onChange: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, color = if (enabled) colors.text else colors.muted, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        NumericSetting(title, value.toString(), suffix, colors, { raw -> raw.toIntOrNull()?.let(onChange) },
            1f..30f, enabled = enabled, adjustable = true, slider = true, showLabel = false,
            identity = "motion-recording-$title")
    }
}

@Composable
private fun MotionStorageCards(
    stats: MotionMediaLibrary.Stats?,
    options: RecordingOptions,
    colors: UiPalette,
    strings: UiStrings,
    onInternalLimit: (Int) -> Unit,
    onInternalKeep: (Boolean) -> Unit,
    onInternalFree: (Int) -> Unit,
    onSdLimit: (Int) -> Unit,
    onSdKeep: (Boolean) -> Unit,
    onSdFree: (Int) -> Unit,
) {
    val internal = stats?.primary
    val sd = stats?.secondary
    val internalMax = internal?.capacityBytes?.takeIf { it > 0 }?.div(MOTION_GB)?.toInt()?.coerceAtLeast(1)
    val sdMax = sd?.capacityBytes?.takeIf { it > 0 }?.div(MOTION_GB)?.toInt()?.coerceAtLeast(1)
    val surfaceShape = RoundedCornerShape(8.dp)
    Box(Modifier.fillMaxWidth()) {
        Row(Modifier.matchParentSize(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            repeat(2) {
                Box(Modifier.weight(1f).fillMaxHeight().clip(surfaceShape).background(colors.surface)
                    .border(1.dp, colors.borderStrong, surfaceShape))
            }
        }
        Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            StoragePairRow({
                Text(strings.motionText(R.string.motion_storage_internal), color = colors.text, fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold)
            }, {
                Text(strings.motionText(R.string.motion_storage_sd), color = colors.text, fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold)
            })
            StoragePairRow({ MotionStorageStatus(internal, stats != null, colors, strings) },
                { MotionStorageStatus(sd, stats != null, colors, strings) })
            StoragePairRow({ MotionStorageSummary(internal, colors, strings) },
                { MotionStorageSummary(sd, colors, strings) })
            StoragePairRow({ MotionStorageBar(internal, options.enabled && (!options.sd || options.fallback),
                options.oemCleanup, colors) }, { MotionStorageBar(sd, options.enabled && (options.sd || options.fallback),
                options.oemCleanup, colors) })
            StoragePairRow({ MotionStorageLegend(internal, colors, strings) },
                { MotionStorageLegend(sd, colors, strings) })
            StoragePairRow({ RecordingLimit(strings.motionText(R.string.motion_quota), options.internalLimit, internalMax,
                strings.motionText(R.string.motion_gb), colors, strings, enabled = true, identity = "motion-tablet-limit",
                onChange = onInternalLimit) },
                { RecordingLimit(strings.motionText(R.string.motion_quota), options.sdLimit, sdMax,
                    strings.motionText(R.string.motion_gb), colors, strings, enabled = true, identity = "motion-sd-limit",
                    onChange = onSdLimit) })
            StoragePairRow({ SwitchLine(strings.motionText(R.string.motion_keep_free), strings.motionText(R.string.motion_keep_free_hint),
                options.internalKeepFree, onInternalKeep, colors) }, { SwitchLine(strings.motionText(R.string.motion_keep_free),
                strings.motionText(R.string.motion_keep_free_hint), options.sdKeepFree, onSdKeep, colors) })
            StoragePairRow({ RecordingLimit(strings.motionText(R.string.motion_free_amount), options.internalFreeGb, internalMax,
                strings.motionText(R.string.motion_gb), colors, strings, enabled = true, identity = "motion-tablet-keep-free",
                onChange = onInternalFree) },
                { RecordingLimit(strings.motionText(R.string.motion_free_amount), options.sdFreeGb, sdMax,
                    strings.motionText(R.string.motion_gb), colors, strings, enabled = true, identity = "motion-sd-keep-free",
                    onChange = onSdFree) })
        }
    }
}

@Composable
private fun StoragePairRow(left: @Composable ColumnScope.() -> Unit,
    right: @Composable ColumnScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(Modifier.weight(1f).padding(horizontal = 12.dp).fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(6.dp), content = left)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp).fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(6.dp), content = right)
    }
}

@Composable
private fun MotionStorageStatus(volume: MotionMediaLibrary.VolumeStats?, statsAvailable: Boolean,
    colors: UiPalette, strings: UiStrings) {
    val status = when {
        !statsAvailable -> R.string.motion_storage_loading
        volume == null || !volume.mounted -> R.string.motion_storage_missing
        volume.capacityBytes <= 0 -> R.string.motion_storage_no_data
        else -> R.string.motion_storage_ready
    }
    Text(strings.motionText(status), color = colors.muted, fontSize = 13.sp)
    if (volume?.mounted == true && volume.readOnly) {
        Text(strings.motionText(R.string.motion_storage_readonly), color = colors.yellow, fontSize = 12.sp)
    }
}

@Composable
private fun MotionStorageSummary(volume: MotionMediaLibrary.VolumeStats?, colors: UiPalette, strings: UiStrings) {
    fun gb(value: Long) = String.format(Locale.US, "%.1f", value.toDouble() / MOTION_GB)
    val capacity = volume?.capacityBytes?.takeIf { it > 0 }
    if (volume == null || capacity == null) {
        Text(strings.motionText(R.string.motion_storage_numbers_unknown), color = colors.muted, fontSize = 13.sp)
        return
    }
    val own = volume.ownBytes.takeIf { it >= 0 }
    val oem = volume.oemRecorderBytes.takeIf { it >= 0 }
    val other = volume.otherBytes.takeIf { it >= 0 }
    val free = volume.freeBytes.takeIf { it >= 0 }?.let(::gb) ?: "—"
    val usedKnown = own != null && oem != null && other != null
    val knownRecording = listOfNotNull(own, oem).sum()
    val message = if (usedKnown) R.string.motion_storage_numbers else R.string.motion_storage_numbers_partial
    val used = if (usedKnown) gb(knownRecording + other!!) else if (own != null || oem != null) gb(knownRecording) else "—"
    Text(strings.motionText(message, used, free, gb(capacity)), color = colors.muted, fontSize = 13.sp)
}

@Composable
private fun RecordingLimit(title: String, value: Int, maximum: Int?, suffix: String,
    colors: UiPalette, strings: UiStrings, enabled: Boolean, identity: Any, onChange: (Int) -> Unit) {
    val upper = (maximum ?: 9999).coerceAtLeast(1)
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, color = if (enabled) colors.text else colors.muted, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        NumericSetting(title, value.coerceAtMost(upper).toString(), suffix, colors,
            { onChange(it.toFloatOrNull()?.toInt()?.coerceIn(1, upper) ?: value) },
            1f..upper.toFloat(), enabled = enabled, adjustable = true, slider = true, showLabel = false,
            identity = identity)
    }
    if (maximum == null) Text(strings.motionText(R.string.motion_storage_unknown_limit), color = colors.muted, fontSize = 12.sp)
}

@Composable
private fun MotionStorageBar(
    volume: MotionMediaLibrary.VolumeStats?,
    active: Boolean,
    oemActive: Boolean,
    colors: UiPalette,
) {
    val capacity = volume?.capacityBytes?.takeIf { it > 0 } ?: 0L
    Canvas(Modifier.fillMaxWidth().height(18.dp).clip(RoundedCornerShape(5.dp))
        .border(1.dp, colors.borderStrong, RoundedCornerShape(5.dp))) {
        if (volume == null || capacity <= 0) return@Canvas
        var x = 0f
        fun width(bytes: Long): Float = (size.width * (bytes.coerceAtLeast(0).toDouble() / capacity)).toFloat()
            .coerceAtMost(size.width - x)
        fun segment(bytes: Long, color: Color, hatch: Boolean = false) {
            val segmentWidth = width(bytes)
            if (segmentWidth <= 0f) return
            if (hatch) motionHatch(color, x, x + segmentWidth) else drawRect(color, Offset(x, 0f), Size(segmentWidth, size.height))
            x += segmentWidth
        }
        if (volume.otherBytes >= 0) segment(volume.otherBytes, MotionOtherData)
        if (volume.oemRecorderBytes >= 0) {
            segment(volume.oemRecorderBytes, MotionOemData)
            if (oemActive && volume.oemAllowanceBytes >= 0) segment(volume.oemAllowanceBytes, MotionOemData, true)
        }
        if (volume.ownBytes >= 0) segment(volume.ownBytes, colors.yellow)
        if (active && volume.ownAllowanceBytes >= 0) segment(volume.ownAllowanceBytes, colors.yellow, true)
        if (volume.unallocatedBytes >= 0) segment(volume.unallocatedBytes, Color.Transparent)
    }
}

@Composable
private fun MotionStorageLegend(volume: MotionMediaLibrary.VolumeStats?, colors: UiPalette, strings: UiStrings) {
    fun gb(value: Long) = String.format(Locale.US, "%.1f", value.toDouble() / MOTION_GB)
    val labels = listOf(R.string.motion_storage_other, R.string.motion_storage_oem, R.string.motion_storage_own)
    val legendColors = listOf(MotionOtherData, MotionOemData, colors.yellow)
    val usedValues = listOf(volume?.otherBytes, volume?.oemRecorderBytes, volume?.ownBytes)
    val freeValues = listOf(volume?.unallocatedBytes, volume?.oemAllowanceBytes, volume?.ownAllowanceBytes)
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            labels.forEachIndexed { index, label ->
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth().height(32.dp), verticalAlignment = Alignment.CenterVertically) {
                        Canvas(Modifier.size(12.dp).border(1.dp, colors.borderStrong, RoundedCornerShape(2.dp))) {
                            drawRect(legendColors[index])
                        }
                        Spacer(Modifier.width(4.dp))
                        Text(strings.motionText(label), color = colors.muted, fontSize = 11.sp, maxLines = 2)
                    }
                    StorageAmount(
                        Modifier.fillMaxWidth().height(32.dp), usedValues[index]?.takeIf { it >= 0 },
                        color = legendColors[index], isFree = false, hatched = false,
                        colors = colors, strings = strings, format = { gb(it) })
                    StorageAmount(
                        Modifier.fillMaxWidth().height(32.dp), freeValues[index]?.takeIf { it >= 0 },
                        color = if (index == 0) Color.Transparent else legendColors[index],
                        isFree = true, hatched = index != 0,
                        colors = colors, strings = strings, format = { gb(it) })
                }
            }
        }
        if (volume == null || volume.oemRecorderBytes < 0) {
            Text(strings.motionText(R.string.motion_oem_bytes_unknown), color = colors.muted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun StorageAmount(modifier: Modifier, bytes: Long?, color: Color, isFree: Boolean, hatched: Boolean,
    colors: UiPalette, strings: UiStrings, format: (Long) -> String) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(12.dp).border(1.dp, colors.borderStrong, RoundedCornerShape(2.dp))) {
            if (hatched) motionHatch(color, 0f, size.width) else if (color != Color.Transparent) drawRect(color)
        }
        Spacer(Modifier.width(4.dp))
        Text(strings.motionText(if (isFree) R.string.motion_storage_free_value else R.string.motion_storage_used_value,
            bytes?.let(format) ?: "—"), color = colors.muted, fontSize = 11.sp, maxLines = 2)
    }
}

private val MotionOtherData = Color(0xFF3A3D42)
private val MotionOemData = Color(0xFF58BCEB)

private fun DrawScope.motionHatch(color: Color, left: Float, right: Float) {
    if (right <= left) return
    clipRect(left = left, right = right) {
        var x = left - size.height
        while (x < right) {
            drawLine(color, Offset(x, size.height), Offset(x + size.height, 0f), 1.dp.toPx())
            x += 6.dp.toPx()
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MotionArchiveDialog(
    archiveState: MotionArchiveState,
    colors: UiPalette,
    strings: UiStrings,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val locale = motionLocale(context)
    val zone = ZoneId.systemDefault()
    val pickerContext = remember(context, locale) {
        ContextThemeWrapper(context, context.theme).apply {
            applyOverrideConfiguration(Configuration().apply { setLocales(LocaleList(locale)) })
        }
    }
    val month = archiveState.month
    val selectedDate = archiveState.selectedDate
    val rangeFrom = archiveState.rangeFrom
    val rangeTo = archiveState.rangeTo
    val stats = archiveState.stats
    val sort = archiveState.sort
    var selectedForDelete by remember { mutableStateOf(emptySet<String>()) }
    var confirmDelete by remember { mutableStateOf(false) }
    var selectionMode by rememberSaveable { mutableStateOf(false) }
    var deleteResult by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<List<MotionMediaLibrary.Entry>?>(null) }
    var pendingDeletePermission by remember { mutableStateOf<List<MotionMediaLibrary.Entry>?>(null) }
    val rangeIncomplete = (rangeFrom == null) != (rangeTo == null)
    val rangeBounds = if (rangeFrom != null && rangeTo != null) motionArchiveDateRange(rangeFrom!!, rangeTo!!, zone) else null
    val rangeInvalid = rangeFrom != null && rangeTo != null && rangeBounds == null
    val loading = archiveState.loading
    val loadFailed = archiveState.loadFailed
    val snapshotEntries = archiveState.entries
    val tabletVolumeId = archiveState.tabletVolumeId
    val removableVolumeIds = archiveState.removableVolumeIds
    val storageFilter = archiveState.storageFilter
    val storageEntries = remember(snapshotEntries, storageFilter, tabletVolumeId, removableVolumeIds) {
        snapshotEntries.filter { entry ->
            motionArchiveVolumeMatches(storageFilter, entry.volumeId, tabletVolumeId, removableVolumeIds)
        }
    }
    val monthBounds = remember(month, zone) { motionArchiveBounds(month, null, null, null, zone)!! }
    val monthDays = remember(storageEntries, monthBounds, zone) {
        motionArchiveDays(storageEntries.filter { motionArchiveContains(monthBounds, it.recordedAtMillis) }, zone)
    }
    val entryBounds = remember(month, selectedDate, rangeFrom, rangeTo, zone) {
        motionArchiveBounds(month, selectedDate, rangeFrom, rangeTo, zone)
    }
    val entries = remember(snapshotEntries, entryBounds, sort, rangeIncomplete, rangeInvalid,
        storageFilter, tabletVolumeId, removableVolumeIds) {
        if (rangeIncomplete || rangeInvalid) emptyList()
        else {
            val byKey = snapshotEntries.associateBy { it.contentUri.toString() }
            val order = motionArchiveProject(
                snapshotEntries.map { MotionArchiveRecordMetadata(
                    it.contentUri.toString(), it.recordedAtMillis, it.volumeId,
                ) },
                storageFilter, tabletVolumeId, removableVolumeIds, entryBounds, oldestFirst = sort != 0,
            )
            order.mapNotNull(byKey::get)
        }
    }
    val gridKey = MotionArchiveGridKey(month, selectedDate, rangeFrom, rangeTo, storageFilter, sort)
    val savedPosition = archiveState.gridPositions[gridKey] ?: MotionArchiveGridPosition(0, 0)
    val gridState = remember(gridKey) { LazyGridState(savedPosition.index, savedPosition.offset) }
    LaunchedEffect(gridKey, gridState) {
        snapshotFlow { MotionArchiveGridPosition(gridState.firstVisibleItemIndex, gridState.firstVisibleItemScrollOffset) }
            .collect { archiveState.gridPositions[gridKey] = it }
    }
    val rangeFormatter = remember(locale, zone) {
        DateTimeFormatter.ofPattern("dd.MM.yyyy", locale)
    }
    val dayFormatter = remember(locale) { DateTimeFormatter.ofPattern("d MMM yyyy", locale) }
    var datePicker by remember { mutableStateOf<DatePickerDialog?>(null) }
    DisposableEffect(Unit) {
        onDispose { datePicker?.dismiss() }
    }
    BackHandler(enabled = selectionMode) { selectionMode = false; selectedForDelete = emptySet() }

    val legacyDeletePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val chosen = pendingDeletePermission
        pendingDeletePermission = null
        if (granted && chosen != null) pendingDelete = chosen
        else if (chosen != null) Toast.makeText(context,
            strings.motionText(R.string.motion_archive_delete_permission), Toast.LENGTH_LONG).show()
    }

    fun showRangePicker(isStart: Boolean) {
        val fallback = selectedDate ?: month.atDay(1)
        val initial = (if (isStart) rangeFrom else rangeTo) ?: fallback
        datePicker = DatePickerDialog(pickerContext, { _, year, monthIndex, day ->
            val chosen = LocalDate.of(year, monthIndex + 1, day)
            archiveState.selectedDate = null
            archiveState.month = YearMonth.from(chosen)
            if (isStart) archiveState.rangeFrom = chosen else archiveState.rangeTo = chosen
        }, initial.year, initial.monthValue - 1, initial.dayOfMonth).also { it.show() }
    }
    LaunchedEffect(pendingDelete) {
        val chosen = pendingDelete ?: return@LaunchedEffect
        try {
            val result = archiveState.deleteSelected(chosen)
            selectedForDelete = emptySet()
            deleteResult = strings.motionText(R.string.motion_archive_delete_result,
                result.deletedCount, result.failedCount, result.skippedCount)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* Owner preserves the last good snapshot and exposes the error state. */ }
        finally { pendingDelete = null }
    }

    val deletableIds = if (loading) emptySet() else entries.filter(::canDelete)
        .map { it.contentUri.toString() }.toSet()
    val visibleSelection = selectedForDelete.intersect(deletableIds)

    Dialog(onDismissRequest = {
        if (selectionMode) {
            selectionMode = false
            selectedForDelete = emptySet()
        } else onClose()
    }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize(.97f).clip(RoundedCornerShape(10.dp)).background(colors.surface)
            .border(1.dp, colors.borderStrong, RoundedCornerShape(10.dp)).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(strings.motionText(R.string.motion_archive), color = colors.text, fontSize = 22.sp,
                        fontWeight = FontWeight.SemiBold)
                    Text(strings.motionText(R.string.motion_archive_hint), color = colors.muted, fontSize = 12.sp)
                }
                ActionButton(strings.motionText(R.string.motion_close), colors = colors, onClick = onClose)
            }
            if (!archiveState.hasSnapshot) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Text(strings.motionText(if (loading) R.string.motion_storage_loading
                        else R.string.motion_storage_unreadable), color = colors.muted,
                        fontSize = 15.sp, textAlign = TextAlign.Center)
                }
            } else BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                val calendarWidth = minOf(196.dp, maxWidth * .233f)
                val columns = if (maxWidth - calendarWidth >= 780.dp) 3 else 2
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(Modifier.width(calendarWidth).fillMaxHeight(),
                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        CalendarPicker(month, selectedDate, monthDays.map { it.date }.toSet(), locale, colors, strings,
                            onMonth = {
                                archiveState.month = it
                                archiveState.selectedDate = null
                                archiveState.rangeFrom = null
                                archiveState.rangeTo = null
                            },
                            onDate = {
                                archiveState.selectedDate = it
                                archiveState.rangeFrom = null
                                archiveState.rangeTo = null
                            })
                        Text(strings.motionText(R.string.motion_archive_days_hint), color = colors.muted, fontSize = 12.sp)
                        ActionButton(strings.motionText(R.string.motion_archive_month), colors = colors,
                            modifier = Modifier.fillMaxWidth(), height = 36.dp) {
                            archiveState.selectedDate = null
                            archiveState.rangeFrom = null
                            archiveState.rangeTo = null
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            ActionButton("${strings.motionText(R.string.motion_archive_from)}\n${rangeFrom?.format(rangeFormatter) ?: "—"}",
                                colors = colors, modifier = Modifier.weight(1f), height = 48.dp, maxLines = 2) { showRangePicker(true) }
                            ActionButton("${strings.motionText(R.string.motion_archive_to)}\n${rangeTo?.format(rangeFormatter) ?: "—"}",
                                colors = colors, modifier = Modifier.weight(1f), height = 48.dp, maxLines = 2) { showRangePicker(false) }
                        }
                        ActionButton(strings.motionText(R.string.motion_archive_clear_range), colors = colors,
                            modifier = Modifier.fillMaxWidth(), height = 36.dp, enabled = rangeFrom != null || rangeTo != null) {
                            archiveState.rangeFrom = null
                            archiveState.rangeTo = null
                        }
                        if (rangeIncomplete || rangeInvalid) Text(strings.motionText(
                            if (rangeIncomplete) R.string.motion_archive_range_incomplete else R.string.motion_archive_range_invalid),
                            color = colors.yellow, fontSize = 12.sp)
                    }
                    Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Segmented(listOf(strings.motionText(R.string.motion_archive_all_short),
                                strings.motionText(R.string.tablet), strings.motionText(R.string.motion_storage_sd)),
                                archiveState.storageFilter.ordinal, colors, Modifier.weight(1f)) { index ->
                                    archiveState.storageFilter = MotionArchiveStorageFilter.values()[index]
                                }
                            ActionButton("${strings.motionText(if (sort == 0) R.string.motion_archive_newest
                                else R.string.motion_archive_oldest)} ${if (sort == 0) "↓" else "↑"}",
                                colors = colors, modifier = Modifier.width(166.dp), height = 42.dp) { archiveState.sort = 1 - sort }
                            ActionButton("", Icons.Outlined.Checklist, colors,
                                modifier = Modifier.width(42.dp).semantics {
                                    contentDescription = strings.motionText(R.string.motion_archive_multiselect)
                                    selected = selectionMode
                                }, primary = selectionMode, height = 42.dp) {
                                selectionMode = !selectionMode
                                selectedForDelete = emptySet()
                            }
                            ActionButton("", Icons.Outlined.Delete, colors,
                                Modifier.width(42.dp).semantics {
                                    contentDescription = strings.motionText(R.string.motion_delete)
                                }, destructive = true, enabled = visibleSelection.isNotEmpty(), height = 42.dp) {
                                confirmDelete = true
                            }
                        }
                        val activeRange = rangeFrom != null || rangeTo != null
                        val heading = when {
                            rangeBounds != null -> "${rangeFrom!!.format(rangeFormatter)} — ${rangeTo!!.format(rangeFormatter)}"
                            activeRange -> strings.motionText(if (rangeIncomplete) R.string.motion_archive_range_incomplete
                                else R.string.motion_archive_range_invalid)
                            selectedDate != null -> selectedDate!!.format(dayFormatter)
                            else -> month.format(DateTimeFormatter.ofPattern("LLLL yyyy", locale))
                        }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(heading, Modifier.weight(1f), color = colors.text, fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold, maxLines = 1)
                            Text(strings.motionText(if (selectionMode) R.string.motion_archive_selected
                                else R.string.motion_archive_count, if (selectionMode) visibleSelection.size else entries.size),
                                Modifier.width(130.dp), color = colors.muted, fontSize = 13.sp, textAlign = TextAlign.End)
                        }
                        selectedDate?.let { date -> monthDays.firstOrNull { it.date == date }?.let { day ->
                            Text(strings.motionText(R.string.motion_archive_events,
                                day.entryCount, day.yellowEvents, day.redEvents), color = colors.muted, fontSize = 13.sp)
                        } }
                        val mounted = stats?.volumes?.any { it.mounted } == true
                        if (!mounted && !loadFailed) Text(strings.motionText(R.string.motion_archive_no_storage),
                            color = colors.yellow, fontSize = 14.sp)
                        if (loadFailed) Text(strings.motionText(R.string.motion_storage_unreadable), color = colors.yellow, fontSize = 14.sp)
                        deleteResult?.let { Text(it, color = colors.muted, fontSize = 12.sp) }
                        if (entries.isEmpty()) {
                            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                                Text(when {
                                    loading -> strings.motionText(R.string.motion_storage_loading)
                                    rangeIncomplete -> strings.motionText(R.string.motion_archive_range_incomplete)
                                    rangeInvalid -> strings.motionText(R.string.motion_archive_range_invalid)
                                    loadFailed -> strings.motionText(R.string.motion_storage_unreadable)
                                    else -> strings.motionText(R.string.motion_archive_empty)
                                }, color = colors.muted, fontSize = 15.sp, textAlign = TextAlign.Center)
                            }
                        } else LazyVerticalGrid(GridCells.Fixed(columns), Modifier.weight(1f).fillMaxWidth(),
                            state = gridState, horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(entries, key = { it.contentUri.toString() }) { entry ->
                                val id = entry.contentUri.toString()
                                val removable = entry.volumeId in removableVolumeIds
                                MotionArchiveCard(entry, id in visibleSelection, selectionMode, archiveState,
                                    removable,
                                    colors, strings, locale, zone,
                                    onClick = {
                                        if (selectionMode) {
                                            if (canDelete(entry)) selectedForDelete = if (id in selectedForDelete)
                                                selectedForDelete - id else selectedForDelete + id
                                        } else if (entry.available) archiveState.playerEntry = entry
                                        else Toast.makeText(context, strings.motionText(R.string.motion_archive_unavailable),
                                            Toast.LENGTH_SHORT).show()
                                    }, onLongClick = {
                                        if (canDelete(entry)) {
                                            selectionMode = true
                                            selectedForDelete = selectedForDelete + id
                                        }
                                    })
                            }
                        }
                        if (confirmDelete) {
                            val chosen = entries.filter { it.contentUri.toString() in visibleSelection && canDelete(it) }
                            Dialog(onDismissRequest = { confirmDelete = false }) {
                                Column(Modifier.fillMaxWidth(.85f).clip(RoundedCornerShape(10.dp)).background(colors.surface)
                                    .border(1.dp, colors.borderStrong, RoundedCornerShape(10.dp)).padding(18.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text(strings.motionText(R.string.motion_archive_delete_confirm, chosen.size),
                                        color = colors.text, fontSize = 17.sp)
                                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        ActionButton(strings.motionText(R.string.motion_delete), colors = colors,
                                            modifier = Modifier.weight(1f), destructive = true) {
                                            confirmDelete = false
                                            if (chosen.isNotEmpty()) {
                                                if (Build.VERSION.SDK_INT < 29 && ContextCompat.checkSelfPermission(
                                                        context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                                                    pendingDeletePermission = chosen
                                                    legacyDeletePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                                                } else pendingDelete = chosen
                                            }
                                        }
                                        ActionButton(strings.motionText(R.string.cancel), colors = colors,
                                            modifier = Modifier.weight(1f)) { confirmDelete = false }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    archiveState.playerEntry?.let { entry ->
        MotionPlayerDialog(
            entries = entries.filter { it.available },
            initialEntry = entry,
            colors = colors,
            strings = strings,
            locale = locale,
            onSelectEntry = { archiveState.playerEntry = it },
            onClose = { archiveState.playerEntry = null },
        )
    }
}

private fun canDelete(entry: MotionMediaLibrary.Entry) =
    entry.status in setOf(MotionMediaLibrary.Status.COMPLETE, MotionMediaLibrary.Status.INTERRUPTED,
        MotionMediaLibrary.Status.UNKNOWN) && !entry.protectedFile

@Composable
private fun CalendarPicker(
    month: YearMonth,
    selectedDate: LocalDate?,
    activeDays: Set<LocalDate>,
    locale: Locale,
    colors: UiPalette,
    strings: UiStrings,
    onMonth: (YearMonth) -> Unit,
    onDate: (LocalDate) -> Unit,
) {
    val firstDay = WeekFields.of(locale).firstDayOfWeek
    val weeks = remember(month, firstDay) { motionCalendarWeeks(month, firstDay) }
    val yearFormatter = remember(locale) { DateTimeFormatter.ofPattern("yyyy", locale) }
    listOf(month.year.toString() to 12L, month.month.getDisplayName(TextStyle.FULL_STANDALONE, locale) to 1L)
        .forEachIndexed { index, (heading, step) ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                ActionButton("‹", colors = colors, height = 36.dp, modifier = Modifier.width(40.dp)
                    .semantics { contentDescription = strings.motionText(
                        if (index == 0) R.string.motion_archive_prev_year else R.string.motion_archive_prev_month) }) {
                    onMonth(month.minusMonths(step))
                }
                Text(if (index == 0) yearFormatter.format(month.atDay(1)) else heading,
                    Modifier.weight(1f), color = colors.text, fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                ActionButton("›", colors = colors, height = 36.dp, modifier = Modifier.width(40.dp)
                    .semantics { contentDescription = strings.motionText(
                        if (index == 0) R.string.motion_archive_next_year else R.string.motion_archive_next_month) }) {
                    onMonth(month.plusMonths(step))
                }
            }
        }
    Row(Modifier.fillMaxWidth()) {
        repeat(7) { offset ->
            Text(DayOfWeek.of((firstDay.value - 1 + offset) % 7 + 1).getDisplayName(TextStyle.SHORT, locale),
                Modifier.weight(1f), color = colors.muted, textAlign = TextAlign.Center, fontSize = 11.sp)
        }
    }
    weeks.forEach { week ->
        Row(Modifier.fillMaxWidth()) {
            week.forEach { date ->
                val active = date != null && date in activeDays
                val chosen = date != null && date == selectedDate
                Column(Modifier.weight(1f).height(32.dp).clip(RoundedCornerShape(5.dp))
                    .background(if (chosen) colors.accent else Color.Transparent)
                    .then(if (date == null || !active) Modifier else Modifier.clickable(role = Role.Button) { onDate(date) }
                        .semantics { contentDescription = date.format(DateTimeFormatter.ISO_LOCAL_DATE); selected = chosen }),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    if (date != null) {
                        Text(date.dayOfMonth.toString(), color = if (chosen) Color.White else if (active) colors.text
                            else colors.muted.copy(alpha = .5f), fontSize = 13.sp)
                        if (active) Box(Modifier.size(4.dp).background(colors.yellow, RoundedCornerShape(4.dp)))
                    }
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun MotionArchiveCard(
    entry: MotionMediaLibrary.Entry,
    selected: Boolean,
    selectionMode: Boolean,
    archiveState: MotionArchiveState,
    removableVolume: Boolean,
    colors: UiPalette,
    strings: UiStrings,
    locale: Locale,
    zone: ZoneId,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val time = remember(entry.recordedAtMillis, locale, zone) {
        DateTimeFormatter.ofPattern("dd.MM.yyyy · HH:mm", locale)
            .withZone(zone).format(java.time.Instant.ofEpochMilli(entry.recordedAtMillis))
    }
    val eventCounts = remember(entry.markers, entry.durationMs) {
        motionArchiveEventCounts(entry.markers.map {
            MotionArchiveInterval(it.positionMs, it.durationMs, it.level)
        }, entry.durationMs)
    }
    val eventLabels = listOfNotNull(
        eventCounts.yellow.takeIf { it > 0 }?.let { strings.motionText(R.string.motion_archive_yellow_count, it) },
        eventCounts.red.takeIf { it > 0 }?.let { strings.motionText(R.string.motion_archive_red_count, it) },
    )
    val status = when (entry.status) {
        MotionMediaLibrary.Status.RECORDING -> R.string.motion_archive_recording
        MotionMediaLibrary.Status.COMPLETE -> R.string.motion_archive_complete
        MotionMediaLibrary.Status.INTERRUPTED -> R.string.motion_archive_interrupted
        else -> R.string.motion_archive_unknown
    }
    val deletable = canDelete(entry)
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(7.dp))
        .background(if (selected) colors.active else colors.panelAlt)
        .border(if (selected) 2.dp else 1.dp, if (selected) colors.accent else colors.border, RoundedCornerShape(7.dp))
        .combinedClickable(enabled = !selectionMode || deletable,
            role = if (selectionMode && deletable) Role.Checkbox else Role.Button,
            onLongClickLabel = strings.motionText(R.string.motion_archive_multiselect),
            onClick = onClick, onLongClick = onLongClick)
        .semantics(mergeDescendants = true) {
            if (selectionMode && deletable) toggleableState = ToggleableState(selected) else this.selected = selected
            contentDescription = "${motionRecordingDisplayName(entry.recordedAtMillis, zone)}, $time, ${formatDuration(entry.durationMs)}, " +
                strings.motionText(status) + ", " + strings.motionText(
                    if (removableVolume) R.string.motion_storage_sd else R.string.motion_storage_internal) +
                (if (eventLabels.isEmpty()) "" else ", ${eventLabels.joinToString(", ")}") +
                if (entry.protectedFile) ", ${strings.motionText(R.string.motion_archive_protected_short)}" else ""
        }.padding(8.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(5.dp))) {
            MotionArchiveThumbnail(entry, archiveState, colors, strings,
                Modifier.fillMaxSize())
            Text(strings.motionText(status), Modifier.align(Alignment.TopStart).padding(4.dp)
                .background(Color.Black.copy(alpha = .6f), RoundedCornerShape(6.dp)).padding(4.dp),
                color = Color.White, fontSize = 11.sp, maxLines = 1)
            if (entry.protectedFile) {
                Text(strings.motionText(R.string.motion_archive_protected_short), Modifier.align(Alignment.TopEnd)
                    .padding(4.dp).background(Color.Black.copy(alpha = .6f), RoundedCornerShape(6.dp)).padding(4.dp),
                    color = colors.yellow, fontSize = 11.sp, maxLines = 1)
            } else if (selectionMode && deletable) {
                Text(if (selected) "☑" else "☐", Modifier.align(Alignment.TopEnd)
                    .padding(4.dp).background(Color.Black.copy(alpha = .6f), RoundedCornerShape(6.dp)).padding(4.dp),
                    color = Color.White, fontSize = 22.sp, lineHeight = 24.sp)
            } else if (!selectionMode && entry.available) {
                Text("▶", Modifier.align(Alignment.Center).background(Color.Black.copy(alpha = .6f), RoundedCornerShape(6.dp))
                    .padding(8.dp), color = Color.White, fontSize = 22.sp)
            }
        }
        Text(time, Modifier.fillMaxWidth().padding(top = 4.dp), color = colors.text, fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (eventLabels.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (eventCounts.yellow > 0) Text(
                    strings.motionText(R.string.motion_archive_yellow_count, eventCounts.yellow),
                    color = colors.yellow, fontSize = 12.sp, maxLines = 1,
                )
                if (eventCounts.red > 0) Text(
                    strings.motionText(R.string.motion_archive_red_count, eventCounts.red),
                    color = colors.red, fontSize = 12.sp, maxLines = 1,
                )
            }
        }
        Row(Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(formatDuration(entry.durationMs), Modifier.weight(1f), color = colors.muted, fontSize = 13.sp)
            Image(painterResource(if (removableVolume) R.drawable.ic_archive_sd_card else R.drawable.ic_archive_tablet),
                contentDescription = strings.motionText(if (removableVolume) R.string.motion_storage_sd else R.string.motion_storage_internal),
                modifier = Modifier.size(20.dp), colorFilter = ColorFilter.tint(colors.muted))
        }
    }
}

@Composable
private fun CompositionGridGlyph(colors: UiPalette, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val gap = size.minDimension * .12f
        val tile = (size.minDimension - gap) / 2f
        for (row in 0..1) for (column in 0..1) {
            drawRoundRect(colors.text,
                Offset(column * (tile + gap), row * (tile + gap)), Size(tile, tile),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(tile * .12f))
        }
    }
}

@Composable
private fun MotionCompositionDialog(
    initial: List<Int>,
    colors: UiPalette,
    strings: UiStrings,
    onClose: () -> Unit,
    onSave: (List<Int>) -> Unit,
) {
    var order by rememberSaveable { mutableStateOf(initial.joinToString(",")) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize(.93f).clip(RoundedCornerShape(10.dp)).background(colors.surface)
            .border(1.dp, colors.borderStrong, RoundedCornerShape(10.dp)).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(strings.motionText(R.string.motion_composition_edit), color = colors.text,
                fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Text(strings.motionText(R.string.motion_composition_hint), color = colors.muted, fontSize = 14.sp)
            MotionCompositionGrid(motionCameraOrder(order), colors, strings, Modifier.weight(1f).fillMaxWidth()) {
                order = it.joinToString(",")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ActionButton(strings.motionText(R.string.motion_composition_save), colors = colors,
                    primary = true, modifier = Modifier.weight(1f)) { onSave(motionCameraOrder(order)) }
                ActionButton(strings.motionText(R.string.cancel), colors = colors,
                    mainBackground = true, modifier = Modifier.weight(1f), onClick = onClose)
            }
        }
    }
}

@Composable
private fun MotionCompositionGrid(order: List<Int>, colors: UiPalette, strings: UiStrings,
    modifier: Modifier, onChange: ((List<Int>) -> Unit)? = null) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    var dragging by remember { mutableStateOf<Int?>(null) }
    var start by remember { mutableStateOf(Offset.Zero) }
    var position by remember { mutableStateOf(Offset.Zero) }
    val latestOrder by rememberUpdatedState(order)
    val update by rememberUpdatedState(onChange)
    fun tile(point: Offset) = if (size.width > 0 && size.height > 0)
        motionTileAt(point.x / size.width, point.y / size.height) else null
    val target = if (dragging != null) tile(position) else null
    Layout(modifier = modifier.onSizeChanged { size = it }.clipToBounds()
        .then(if (onChange == null) Modifier else Modifier.pointerInput(Unit) {
            detectDragGesturesAfterLongPress(onDragStart = { dragging = tile(it); start = it; position = it },
                onDrag = { change, amount -> change.consume(); position += amount },
                onDragCancel = { dragging = null }, onDragEnd = {
                    val from = dragging
                    val to = tile(position)
                    if (from != null && to != null) update?.invoke(swapMotionCameras(latestOrder, from, to))
                    dragging = null
                })
        }), content = {
                // Keep each AndroidView in the same composition slot; reorder placement, not owners.
                repeat(4) { source ->
                    val index = order.indexOf(source)
                    val label = strings.motionText(listOf(R.string.motion_camera_front, R.string.motion_camera_rear,
                        R.string.motion_camera_left, R.string.motion_camera_right)[source])
                    Box(Modifier.zIndex(if (dragging == index) 1f else 0f)
                        .graphicsLayer { if (dragging == index) {
                            translationX = position.x - start.x; translationY = position.y - start.y; alpha = .85f
                        } }.clip(RoundedCornerShape(7.dp)).background(colors.panelAlt)
                        .border(if (target == index) 2.dp else 1.dp,
                            if (target == index) colors.yellow else colors.borderStrong, RoundedCornerShape(7.dp))
                        .semantics {
                            contentDescription = label
                            if (onChange != null) customActions = (0..3).filter { it != index }.map { other ->
                                CustomAccessibilityAction(strings.motionText(R.string.motion_composition_swap,
                                    strings.motionText(listOf(R.string.motion_camera_front, R.string.motion_camera_rear,
                                        R.string.motion_camera_left, R.string.motion_camera_right)[order[other]]))) {
                                    onChange(swapMotionCameras(order, index, other)); true
                                }
                            }
                        }, contentAlignment = Alignment.Center) {
                        BoxWithConstraints(Modifier.fillMaxSize()) {
                            MotionCameraTile(source, Modifier.fillMaxSize())
                            Image(painterResource(MotionDirectionIcon.drawableFor(source)), contentDescription = null,
                                modifier = Modifier.align(Alignment.TopStart)
                                    .padding(top = maxHeight * .0083f, start = maxWidth * .0083f)
                                    .size(maxWidth * .05f))
                            Text(label, color = colors.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                                textAlign = TextAlign.Center, modifier = Modifier.align(Alignment.BottomCenter)
                                    .background(colors.surface.copy(alpha = .75f)).padding(horizontal = 8.dp, vertical = 2.dp))
                        }
                    }
                }
    }) { measurables, constraints ->
        val gap = 6.dp.roundToPx()
        val width = ((constraints.maxWidth - gap) / 2).coerceAtLeast(0)
        val height = ((constraints.maxHeight - gap) / 2).coerceAtLeast(0)
        val tiles = measurables.map { it.measure(Constraints.fixed(width, height)) }
        layout(constraints.maxWidth, constraints.maxHeight) {
            tiles.forEachIndexed { source, tile ->
                val index = order.indexOf(source)
                tile.placeRelative((index % 2) * (width + gap), (index / 2) * (height + gap))
            }
        }
    }
}

@Composable
private fun ActionButton(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    colors: UiPalette, modifier: Modifier = Modifier, primary: Boolean = false, destructive: Boolean = false,
    enabled: Boolean = true, mainBackground: Boolean = false, height: androidx.compose.ui.unit.Dp = 44.dp,
    maxLines: Int = 1, onClick: () -> Unit) =
    ProductionActionButton(text, colors, modifier, icon, primary, destructive, enabled, mainBackground,
        height = height, maxLines = maxLines, onClick = onClick)

private fun UiStrings.motionText(id: Int, vararg args: Any): String {
    val value = resource(id)
    return if (args.isEmpty()) value else String.format(Locale.getDefault(), value, *args)
}

private fun motionLocale(context: Context): Locale = Locale.forLanguageTag(
    AppLanguage.read(context.getSharedPreferences("settings", Context.MODE_PRIVATE)))

private fun formatDuration(durationMs: Long): String {
    val seconds = (durationMs / 1000).coerceAtLeast(0)
    return "%d:%02d:%02d".format(Locale.US, seconds / 3600, (seconds / 60) % 60, seconds % 60)
}
