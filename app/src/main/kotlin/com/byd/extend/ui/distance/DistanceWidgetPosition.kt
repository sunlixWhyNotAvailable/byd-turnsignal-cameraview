package com.byd.extend.ui.distance

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.byd.extend.*
import com.byd.extend.R
import com.byd.extend.ui.*
import kotlin.math.roundToInt

@Composable
internal fun DistanceDisplayPreview(settings: DistanceWidgetSettings, radius: Float, frame: DistanceFrame,
    palette: UiPalette, strings: UiStrings, modifier: Modifier, onMove: ((DistanceWidgetProfile) -> Unit)? = null) {
    val context = LocalContext.current
    val display = DistanceDisplayGeometry.read(context, settings.target)
    val density = LocalDensity.current.density
    val current by rememberUpdatedState(settings.profile)
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        if (display == null) {
            Text(strings.distanceText(R.string.distance_display_unavailable), color = palette.muted)
        } else {
            val scale = minOf(maxWidth.value / display.first, maxHeight.value / display.second)
            val width = display.first * scale; val height = display.second * scale
            val bounds = settings.profile.frame(display.first.toFloat(), display.second.toFloat())
            Box(Modifier.size(width.dp,height.dp).clip(RoundedCornerShape(7.dp)).background(palette.panelAlt)
                .border(1.dp,palette.borderStrong,RoundedCornerShape(7.dp))
                .pointerInput(display, scale, onMove != null) {
                    var dragging = false
                    var origin = current
                    var travel = Offset.Zero
                    detectDragGestures(onDragStart = { point ->
                        origin = current; travel = Offset.Zero
                        val b=origin.backgroundFrame(display.first.toFloat(),display.second.toFloat())
                        val p=point/(scale*density)
                        dragging=onMove!=null && p.x in b.x..b.x+b.width && p.y in b.y..b.y+b.height
                    }, onDragEnd = {dragging=false}, onDragCancel = {dragging=false}) { change, amount ->
                        if(dragging) {
                            change.consume(); travel+=amount/(scale*density)
                            val b=origin.frame(display.first.toFloat(),display.second.toFloat())
                            onMove?.invoke(origin.copy(
                                x=if(display.first>b.width) (origin.x+travel.x/(display.first-b.width)*100).coerceIn(0f,100f) else origin.x,
                                y=if(display.second>b.height) (origin.y+travel.y/(display.second-b.height)*100).coerceIn(0f,100f) else origin.y))
                        }
                    }
                }) {
                DistanceWidgetSample(settings,radius,Modifier.offset {
                    IntOffset((bounds.x*scale*density).roundToInt(),(bounds.y*scale*density).roundToInt())
                }.size((bounds.width*scale).dp,(bounds.height*scale).dp),frame,scale*density)
            }
        }
    }
}

@Composable
internal fun DistancePositionDialog(settings: DistanceWidgetSettings, radius: Float, palette: UiPalette,
    strings: UiStrings, onDismiss: () -> Unit, onApply: (DistanceWidgetProfile) -> Unit) {
    var draft by remember { mutableStateOf(settings.profile) }
    val available = DistanceDisplayGeometry.read(LocalContext.current, settings.target) != null
    Dialog(onDismissRequest=onDismiss,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Column(Modifier.fillMaxWidth(.94f).fillMaxHeight(.9f).clip(RoundedCornerShape(10.dp))
            .background(palette.surface).padding(14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(strings.distanceText(R.string.distance_position),color=palette.text,fontSize=20.sp)
            Text(strings.distanceText(R.string.distance_position_hint),color=palette.muted,fontSize=12.sp)
            DistanceDisplayPreview(settings.withProfile(draft),radius,DistanceWidgetUiFeed.frame,palette,strings,
                Modifier.fillMaxWidth().weight(1f),onMove={ draft=it })
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(20.dp)) {
                Column(Modifier.weight(1f)) { DistanceNumericSetting("X",distanceCoordinateText(draft.x),
                    {draft=draft.copy(x=distanceCoordinateCommit(draft.x,it))},
                    "%",palette,0f..100f,adjustable=true,labelWidth=24.dp) }
                Column(Modifier.weight(1f)) { DistanceNumericSetting("Y",distanceCoordinateText(draft.y),
                    {draft=draft.copy(y=distanceCoordinateCommit(draft.y,it))},
                    "%",palette,0f..100f,adjustable=true,labelWidth=24.dp) }
            }
            Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                ActionButton(strings.distanceText(R.string.distance_car_use),palette,Modifier.weight(1f),primary=true,
                    enabled=available) {onApply(draft)}
                ActionButton(strings.distanceText(R.string.cancel),palette,Modifier.weight(1f),onClick=onDismiss)
            }
        }
    }
}

internal fun distanceCoordinateText(value: Float): String =
    java.lang.String.format(java.util.Locale.US, "%.1f", value.coerceIn(0f,100f)).trimEnd('0').trimEnd('.')

// NumericSetting commits on blur too: displaying rounded text must not move an untouched widget.
internal fun distanceCoordinateCommit(current: Float, text: String): Float =
    if (text == distanceCoordinateText(current)) current else text.toFloat().coerceIn(0f,100f)
