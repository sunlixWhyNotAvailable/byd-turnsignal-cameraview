package com.byd.extend.ui.distance

import com.byd.extend.*
import com.byd.extend.R
import com.byd.extend.ui.*
import com.byd.extend.ui.avasSwitchRow as switchRow
import com.byd.extend.ui.ActionButton as ProductionActionButton
import com.byd.extend.ui.NumericSetting as ProductionNumericSetting
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.viewinterop.AndroidView

import android.view.KeyEvent
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import com.byd.extend.ui.icons.Palette
import com.byd.extend.ui.icons.DirectionsCar
import com.byd.extend.ui.icons.OpenInFull
import androidx.compose.material3.Text
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlin.math.roundToInt
import kotlin.math.hypot

private const val DISTANCE_WIDGET_WIDTH = 500f
private const val DISTANCE_WIDGET_HEIGHT = 620f
private const val DISTANCE_WIDGET_RATIO = DISTANCE_WIDGET_WIDTH / DISTANCE_WIDGET_HEIGHT

@Composable
internal fun DistanceWidgetScreen(
    palette: UiPalette, strings: UiStrings, onAction: (BydExtendUiAction) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val preferences = remember(context) { context.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE) }
    var revision by remember { mutableIntStateOf(0) }
    DisposableEffect(context) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> revision++ }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) CameraHelperService.distancePreview(context, true)
            if (event == androidx.lifecycle.Lifecycle.Event.ON_PAUSE) CameraHelperService.distancePreview(context, false)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED))
            CameraHelperService.distancePreview(context, true)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            preferences.unregisterOnSharedPreferenceChangeListener(listener)
            CameraHelperService.distancePreview(context, false)
        }
    }
    val settings = remember(revision) { DistanceWidgetStore.read(preferences) }
    val frame = DistanceWidgetUiFeed.frame
    val cornerRadius = DistanceDisplayGeometry.radius(context)
    val profile = settings.profile
    fun update(next: DistanceWidgetSettings, notify: Boolean = false) {
        DistanceWidgetStore.save(context, next)
        if (notify) Toast.makeText(context, strings.distanceText(R.string.settings_applied), Toast.LENGTH_SHORT).show()
    }
    var colorTarget by rememberSaveable { mutableStateOf<Int?>(null) }
    var adding by rememberSaveable { mutableStateOf(false) }
    var choosingCar by rememberSaveable { mutableStateOf(false) }
    var positioning by rememberSaveable { mutableStateOf(false) }
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = LocalTextStyle.current.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    val sliderLabelWidth = with(LocalDensity.current) {
        listOf(R.string.distance_widget_size, R.string.distance_widget_background_size,
            R.string.distance_widget_transparency, R.string.mirror_border_width).maxOf {
            textMeasurer.measure(strings.distanceText(it), labelStyle, softWrap = false).size.width
        }.toDp() + 4.dp
    }
    ScreenSurface(palette, scroll = false, compact = true) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val previewWidth = minOf(maxHeight * DISTANCE_WIDGET_RATIO + 28.dp, maxWidth * .38f)
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LazyForm(Modifier.weight(1f).fillMaxHeight(), viewport = LocalPrimaryLazyList.current) {
                    FormSection(strings.distanceText(R.string.distance_widget_title), palette, key = "distance-widget",
                        headerModifier = Modifier.switchRow(settings.enabled,
                            { update(settings.copy(enabled = it), true) }, palette),
                        trailing = {
                            AppSwitch(settings.enabled, { update(settings.copy(enabled = it), true) }, palette,
                                clearSemantics = true, label = strings.distanceText(R.string.distance_widget_title))
                        }) {
                        row("output-display") {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(strings.distanceText(R.string.distance_output_display), color = palette.text,
                                    fontWeight = FontWeight.SemiBold, maxLines = 1)
                                Segmented(listOf(strings.distanceText(R.string.tablet), strings.distanceText(R.string.cluster)),
                                    settings.target.ordinal, palette, Modifier.weight(1f)) {
                                    update(settings.copy(target = DistanceTarget.entries[it]), true)
                                }
                            }
                        }
                        row("position") {
                            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                DistanceActionButton(strings.distanceText(R.string.distance_position), Icons.Outlined.OpenInFull, palette,
                                    Modifier.weight(1f), maxLines = 2) { positioning = true }
                                val car = DistanceCarCatalog.resolve(settings.carModel, settings.carColor)
                                DistanceActionButton(strings.distanceText(R.string.distance_car_choose) + " · " + car.model.uppercase() +
                                    " · " + strings.distanceText(car.colorLabel), Icons.Outlined.DirectionsCar, palette,
                                    Modifier.weight(1f), maxLines = 2) { choosingCar = true }
                            }
                        }
                        row("display-format") {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(strings.distanceText(R.string.distance_display_format), color = palette.text,
                                    fontWeight = FontWeight.SemiBold, maxLines = 1)
                                Segmented(listOf(strings.distanceText(R.string.distance_format_always), strings.distanceText(R.string.distance_format_obstacles)),
                                    settings.format.ordinal, palette, Modifier.weight(1f)) {
                                    update(settings.copy(format = DistanceDisplayFormat.entries[it]), true)
                                }
                            }
                        }
                        row("visibility-binding") {
                            MirrorBindingRow(strings.distanceText(R.string.distance_visibility_button),
                                CameraButtonBindings.Action.DistanceVisibility,
                                CameraButtonBindings.load(preferences, CameraButtonBindings.Action.DistanceVisibility),
                                true, strings, palette, onAction)
                        }
                        row("widget-size") {
                            DistanceNumericSetting(strings.distanceText(R.string.distance_widget_size), profile.sizePercent.toString(),
                                { update(settings.withProfile(profile.copy(sizePercent = it.toFloat().roundToInt()))) }, "%", palette,
                                range = 20f..100f, adjustable = true, slider = true,
                                labelWidth = sliderLabelWidth, inputWidth = 64.dp, suffixWidth = 24.dp)
                        }
                        row("background-size") {
                            DistanceNumericSetting(strings.distanceText(R.string.distance_widget_background_size), profile.backgroundPercent.toString(),
                                { update(settings.withProfile(profile.copy(backgroundPercent = it.toFloat().roundToInt()))) }, "%", palette,
                                range = 70f..100f, adjustable = true, slider = true,
                                validate = { it % 1f == 0f },
                                labelWidth = sliderLabelWidth, inputWidth = 64.dp, suffixWidth = 24.dp)
                        }
                        row("transparency") {
                            DistanceNumericSetting(strings.distanceText(R.string.distance_widget_transparency), profile.transparency.toString(),
                                { update(settings.withProfile(profile.copy(transparency = it.toFloat().roundToInt()))) }, "%", palette,
                                range = 0f..100f, adjustable = true, slider = true,
                                labelWidth = sliderLabelWidth, inputWidth = 64.dp, suffixWidth = 24.dp)
                        }
                        row("border-width") {
                            DistanceNumericSetting(strings.distanceText(R.string.mirror_border_width), profile.borderDp.toString(),
                                { update(settings.withProfile(profile.copy(borderDp = it.toFloat().roundToInt()))) }, "dp", palette,
                                range = 0f..16f, adjustable = true, slider = true,
                                labelWidth = sliderLabelWidth, inputWidth = 64.dp, suffixWidth = 24.dp)
                        }
                        row("border-color") {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(strings.distanceText(R.string.mirror_border_color), color = palette.text, modifier = Modifier.weight(1f))
                                DistanceZoneColorButtons(profile.borderColor, palette, strings) { colorTarget = -1 }
                            }
                        }
                        row("show-distance") {
                            SwitchLine(strings.distanceText(R.string.distance_widget_show_distance), "", settings.showDistance,
                                { update(settings.copy(showDistance = it)) }, palette)
                        }
                        row("presets") {
                            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                DistanceActionButton(strings.distanceText(R.string.save_preset), palette = palette,
                                    modifier = Modifier.weight(1f), maxLines = 2) {
                                    DistanceWidgetStore.savePreset(preferences)
                                    Toast.makeText(context, strings.distanceText(R.string.preset_saved), Toast.LENGTH_SHORT).show()
                                }
                                DistanceActionButton(strings.distanceText(R.string.load_preset), palette = palette,
                                    modifier = Modifier.weight(1f), enabled = preferences.contains(DistanceWidgetStore.PRESET), maxLines = 2) {
                                    DistanceWidgetStore.applyPreset(context, false)
                                    Toast.makeText(context, strings.distanceText(R.string.preset_loaded), Toast.LENGTH_SHORT).show()
                                }
                                DistanceActionButton(strings.distanceText(R.string.reset_settings), palette = palette,
                                    modifier = Modifier.weight(1f), maxLines = 2) {
                                    DistanceWidgetStore.applyPreset(context, true)
                                    Toast.makeText(context, strings.distanceText(R.string.settings_reset), Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                        this
                    }
                    row("zones-gap") { Spacer(Modifier.height(10.dp)) }
                    FormSection(strings.distanceText(R.string.distance_widget_zones), palette, key = "distance-zones") {
                        settings.zones.forEachIndexed { index, zone ->
                            val lower = settings.zones.getOrNull(index - 1)?.endCm ?: 0
                            val upper = (settings.zones.getOrNull(index + 1)?.endCm ?: 10000) - 1
                            row("zone-${zone.id}") {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text(strings.distanceText(R.string.distance_widget_zone, index + 1),
                                        color = palette.text, fontWeight = FontWeight.SemiBold, fontStyle = FontStyle.Italic,
                                        modifier = Modifier.weight(1f))
                                    Text(strings.distanceText(if (index == 0) R.string.distance_widget_first_range else R.string.distance_widget_range,
                                        lower, zone.endCm), color = palette.muted, fontSize = 13.sp, fontStyle = FontStyle.Italic)
                                    if (zone.id >= 4) {
                                        Spacer(Modifier.width(8.dp))
                                        DistanceActionButton("", Icons.Outlined.Delete, palette, Modifier.size(36.dp)
                                            .semantics { contentDescription = strings.distanceText(R.string.distance_widget_remove, index + 1) }, destructive = true,
                                            onClick = { update(settings.removeZone(zone.id)) })
                                    }
                                }
                                Spacer(Modifier.height(8.dp))
                                DistanceNumericSetting(strings.distanceText(R.string.distance_widget_limit), zone.endCm.toString(),
                                    { update(settings.withBoundary(zone.id, it.toFloat().roundToInt())) }, strings.distanceText(R.string.cm),
                                    palette, range = (lower + 1).toFloat()..upper.toFloat(), adjustable = true,
                                    validate = { it % 1f == 0f }, beforeInput = {
                                        DistanceZoneColorButtons(zone.color, palette, strings) { colorTarget = zone.id }
                                    })
                                if (index != settings.zones.lastIndex) {
                                    Spacer(Modifier.height(8.dp))
                                    Box(Modifier.fillMaxWidth().height(1.dp).background(palette.border))
                                    Spacer(Modifier.height(8.dp))
                                }
                            }
                        }
                        row("add-zone") {
                            DistanceActionButton(strings.distanceText(R.string.distance_widget_add), Icons.Outlined.Add, palette,
                                Modifier.fillMaxWidth(), enabled = settings.zones.size < 9999) { adding = true }
                        }
                        this
                    }
                    this
                }
                Section(strings.distanceText(R.string.preview), palette, Modifier.width(previewWidth).fillMaxHeight()) {
                    if (!frame.fresh(android.os.SystemClock.elapsedRealtime())) Text(
                        strings.distanceText(R.string.distance_data_unavailable), color = palette.muted, fontSize = 12.sp)
                    DistanceDisplayPreview(settings, cornerRadius, frame, palette, strings, Modifier.fillMaxWidth().weight(1f))
                }
            }
        }
    }
    colorTarget?.let { id ->
        val color = if (id == -1) profile.borderColor else settings.zones.first { it.id == id }.color
        MirrorBorderColorPicker(palette, strings, color, onDismiss = { colorTarget = null }, onSelect = { selected ->
            update(if (id == -1) settings.withProfile(profile.copy(borderColor = selected))
                else settings.copy(zones = settings.zones.map { if (it.id == id) it.copy(color = selected) else it }))
            colorTarget = null
        }, title = strings.distanceText(if (id == -1) R.string.mirror_border_color else R.string.distance_widget_color))
    }
    if (adding) AddDistanceZoneDialog(settings, palette, strings, onDismiss = { adding = false }, onAdd = { distance, color ->
        update(settings.addZone(distance, color)); adding = false
    })
    if (choosingCar) DistanceCarDialog(settings, palette, strings, onDismiss = { choosingCar = false }, onSelect = { car ->
        update(settings.copy(carModel = car.model, carColor = car.color)); choosingCar = false
    })
    if (positioning) DistancePositionDialog(settings, cornerRadius, palette, strings, onDismiss = { positioning = false },
        onApply = { update(settings.withProfile(it), true); positioning = false })
}

@Composable
private fun DistanceCarDialog(
    settings: DistanceWidgetSettings, palette: UiPalette, strings: UiStrings,
    onDismiss: () -> Unit, onSelect: (DistanceCarArt) -> Unit,
) {
    var model by rememberSaveable { mutableStateOf(settings.carModel) }
    var color by rememberSaveable { mutableStateOf(settings.carColor) }
    val car = DistanceCarCatalog.resolve(model, color)
    val colors = DistanceCarCatalog.variants.filter { it.model == car.model }
    val bitmap = ImageBitmap.imageResource(car.drawable)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.widthIn(max = 800.dp).fillMaxWidth(.92f).fillMaxHeight(.86f)
            .clip(RoundedCornerShape(10.dp)).background(palette.surface).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(strings.distanceText(R.string.distance_car_choose), color = palette.text, fontSize = 22.sp)
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(strings.distanceText(R.string.distance_car_model), color = palette.text, fontWeight = FontWeight.SemiBold)
                    ChoiceField("", DistanceCarCatalog.models.map { it.uppercase() },
                        DistanceCarCatalog.models.indexOf(car.model), { index ->
                            val next = DistanceCarCatalog.resolve(DistanceCarCatalog.models[index], car.color)
                            model = next.model; color = next.color
                        }, palette)
                    Text(strings.distanceText(R.string.distance_car_color), color = palette.text, fontWeight = FontWeight.SemiBold)
                    ChoiceField("", colors.map { strings.distanceText(it.colorLabel) }, colors.indexOf(car),
                        { color = colors[it].color }, palette)
                }
                Canvas(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(8.dp)).background(Color.Black)
                    .semantics { contentDescription = car.model.uppercase() + ", " + strings.distanceText(car.colorLabel) }) {
                    drawDistanceCar(bitmap, car, Offset(12f, 12f), Size(size.width - 24f, size.height - 24f))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                DistanceActionButton(strings.distanceText(R.string.distance_car_use), palette = palette, primary = true,
                    modifier = Modifier.weight(1f)) { onSelect(car) }
                DistanceActionButton(strings.distanceText(R.string.cancel), palette = palette, modifier = Modifier.weight(1f), onClick = onDismiss)
            }
        }
    }
}

private fun DrawScope.drawDistanceCar(bitmap: ImageBitmap, car: DistanceCarArt, origin: Offset, area: Size) {
    val scale = minOf(area.width / car.width, area.height / car.height).coerceAtLeast(0f)
    val target = IntSize((car.width * scale).roundToInt().coerceAtLeast(1), (car.height * scale).roundToInt().coerceAtLeast(1))
    drawImage(bitmap, srcOffset = IntOffset(car.left, car.top), srcSize = IntSize(car.width, car.height),
        dstOffset = IntOffset((origin.x + (area.width - target.width) / 2).roundToInt(),
            (origin.y + (area.height - target.height) / 2).roundToInt()),
        dstSize = target, filterQuality = FilterQuality.Medium)
}

@Composable
private fun AddDistanceZoneDialog(
    settings: DistanceWidgetSettings, palette: UiPalette, strings: UiStrings,
    onDismiss: () -> Unit, onAdd: (Int, Int) -> Unit,
) {
    val suggested = remember {
        val ends = listOf(0) + settings.zones.map { it.endCm }
        val gap = ends.zipWithNext().maxBy { (a, b) -> b - a }
        if (gap.second - gap.first > 1) (gap.first + gap.second) / 2 else (ends.last() + 1).coerceAtMost(9999)
    }
    var distance by rememberSaveable { mutableStateOf(suggested.toString()) }
    var color by rememberSaveable { mutableIntStateOf(settings.zoneAt(suggested.toDouble())?.color ?: settings.zones.last().color) }
    var picking by rememberSaveable { mutableStateOf(false) }
    var valid by rememberSaveable { mutableStateOf(true) }
    fun available(value: Float?) = value != null && value.isFinite() && value in 1f..9999f &&
        value % 1f == 0f && settings.zones.none { it.endCm == value.toInt() }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.widthIn(max = 540.dp).fillMaxWidth().padding(18.dp)
            .clip(RoundedCornerShape(10.dp)).background(palette.surface)
            .verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(strings.distanceText(R.string.distance_widget_add), color = palette.text, fontSize = 22.sp)
            DistanceNumericSetting(strings.distanceText(R.string.distance_widget_limit), distance, { distance = it }, strings.distanceText(R.string.cm),
                palette, range = 1f..9999f, adjustable = true, inputWidth = 64.dp,
                beforeInput = { DistanceZoneColorButtons(color, palette, strings) { picking = true } },
                validate = { available(it) },
                onDraftChange = { valid = available(it.toFloatOrNull()) }, onCommit = { valid = true })
            Text(strings.distanceText(R.string.distance_widget_contiguous), color = palette.muted, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                DistanceActionButton(strings.distanceText(R.string.distance_widget_add), palette = palette, primary = true,
                    enabled = valid && available(distance.toFloatOrNull()), modifier = Modifier.weight(1f)) {
                    distance.toFloatOrNull()?.takeIf { available(it) }?.let { onAdd(it.roundToInt(), color) }
                }
                DistanceActionButton(strings.distanceText(R.string.cancel), palette = palette, modifier = Modifier.weight(1f), onClick = onDismiss)
            }
        }
    }
    if (picking) MirrorBorderColorPicker(palette, strings, color, onDismiss = { picking = false },
        onSelect = { color = it; picking = false }, title = strings.distanceText(R.string.distance_widget_color))
}

@Composable
private fun DistanceZoneColorButtons(color: Int, palette: UiPalette, strings: UiStrings, onPick: () -> Unit) {
    val label = strings.distanceText(R.string.distance_widget_color)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(7.dp)).background(palette.field)
            .border(1.dp, palette.borderStrong, RoundedCornerShape(7.dp))
            .semantics { contentDescription = label }
            .clickable(onClick = onPick), contentAlignment = Alignment.Center) {
            Box(Modifier.size(22.dp).background(Color(color), RoundedCornerShape(4.dp)))
        }
        DistanceActionButton("", Icons.Outlined.Palette, palette, Modifier.size(36.dp)
            .semantics { contentDescription = label }, onClick = onPick)
    }
}

@Composable
internal fun DistanceWidgetSample(settings: DistanceWidgetSettings, radius: Float, modifier: Modifier,
    frame: DistanceFrame = DistanceWidgetUiFeed.frame, displayScale: Float = 1f) {
    AndroidView(factory = { DistanceWidgetView(it) }, modifier = modifier,
        update = { it.update(settings, radius, frame, displayScale) })
}

@Composable
private fun DistanceActionButton(text: String, icon: ImageVector? = null, palette: UiPalette,
    modifier: Modifier = Modifier, primary: Boolean = false, destructive: Boolean = false,
    enabled: Boolean = true, maxLines: Int = 1, onClick: () -> Unit) =
    ProductionActionButton(text, palette, modifier, icon, primary, destructive, enabled, maxLines = maxLines, onClick = onClick)

@Composable
internal fun DistanceNumericSetting(title: String, value: String, onChange: (String) -> Unit,
    suffix: String, palette: UiPalette, range: ClosedFloatingPointRange<Float>, adjustable: Boolean = false,
    slider: Boolean = false, labelWidth: Dp? = null, inputWidth: Dp? = null, suffixWidth: Dp? = null,
    beforeInput: (@Composable () -> Unit)? = null, validate: (Float) -> Boolean = { true },
    onDraftChange: ((String) -> Unit)? = null, onCommit: (() -> Unit)? = null) {
    ProductionNumericSetting(title, value, suffix, palette,
        { raw -> if (raw.toFloatOrNull()?.let(validate) == true) { onChange(raw); onCommit?.invoke() } },
        range, adjustable = adjustable, slider = slider, inlineLabel = true,
        beforeInput = beforeInput, labelWidth = labelWidth, inputWidth = inputWidth,
        compactSuffix = true, onDraftChange = onDraftChange)
}

internal fun UiStrings.distanceText(id: Int, vararg args: Any): String =
    resource(id).let { if (args.isEmpty()) it else String.format(java.util.Locale.getDefault(), it, *args) }
