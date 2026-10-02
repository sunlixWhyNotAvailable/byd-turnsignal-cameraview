package com.byd.extend.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.byd.extend.AvasEngineSettings
import com.byd.extend.CameraButtonBindings
import com.byd.extend.R
import kotlin.math.roundToInt

@Composable
internal fun AvasEngineCard(
    state: AvasEngineUiState, strings: UiStrings, colors: UiPalette,
    onAction: (BydExtendUiAction) -> Unit, modifier: Modifier = Modifier,
) {
    fun send(kind: AvasActionKind, enabled: Boolean? = null, volume: Int? = null,
        packId: String? = null) {
        onAction(BydExtendUiAction.Avas(AvasBackendAction(
            "engine", kind, enabled, volume, packId)))
    }
    val title = strings.resource(R.string.avas_engine_title)
    val packIds = AvasEngineSettings.PACK_IDS
    val packLabels = packIds.map { strings.resource(enginePackResource(it)) }
    val packIndex = packIds.indexOf(state.packId).coerceAtLeast(0)
    val hasError = state.state == "error" || state.error.isNotBlank()

    Section(title, colors, modifier, header = {
        Row(Modifier.fillMaxWidth().background(colors.panelAlt).avasSwitchRow(state.enabled,
            { send(AvasActionKind.SetEnabled, enabled = it) }, colors)
            .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(title.uppercase(), color = colors.muted, fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            AppSwitch(state.enabled, { send(AvasActionKind.SetEnabled, enabled = it) },
                colors, clearSemantics = true, label = title)
        }
    }) {
        Column(Modifier.fillMaxWidth().alpha(if (state.enabled) 1f else .45f),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(strings.resource(R.string.avas_engine_pack), color = colors.muted, fontSize = 12.sp)
            ChoiceField("", packLabels, packIndex, {
                send(AvasActionKind.SetEnginePack, packId = packIds.getOrNull(it)
                    ?: AvasEngineSettings.DEFAULT_PACK_ID)
            }, colors, Modifier.height(40.dp), enabled = state.enabled, hudCompact = true)

            SwitchLine(strings.resource(R.string.avas_engine_navigation_priority),
                strings.resource(R.string.avas_engine_navigation_priority_hint), state.navigationPriority,
                { send(AvasActionKind.SetEngineNavigationPriority, enabled = it) }, colors,
                enabled = state.enabled)

            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                .background(colors.panelAlt).border(1.dp, colors.borderStrong, RoundedCornerShape(8.dp))
                .padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SwitchLine(strings.resource(R.string.avas_engine_exterior), "", state.exteriorEnabled,
                    { send(AvasActionKind.SetEngineExterior, enabled = it) }, colors,
                    enabled = state.enabled)
                engineVolume(strings, colors, state.exteriorVolume, state.enabled && state.exteriorEnabled,
                    "avas-engine-exterior-volume") {
                    send(AvasActionKind.SetEngineExteriorVolume, volume = it.toFloat().roundToInt())
                }
            }

            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                .background(colors.panelAlt).border(1.dp, colors.borderStrong, RoundedCornerShape(8.dp))
                .padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SwitchLine(strings.resource(R.string.avas_engine_interior), "", state.interiorEnabled,
                    { send(AvasActionKind.SetEngineInterior, enabled = it) }, colors,
                    enabled = state.enabled)
                engineVolume(strings, colors, state.interiorVolume, state.enabled && state.interiorEnabled,
                    "avas-engine-interior-volume") {
                    send(AvasActionKind.SetEngineInteriorVolume, volume = it.toFloat().roundToInt())
                }
            }

            if (state.enabled && !state.hasOutput) {
                Text(strings.resource(R.string.avas_engine_no_outputs), color = colors.muted,
                    fontSize = 12.sp)
            }
            Text(strings.resource(when {
                hasError -> R.string.avas_engine_error
                state.state == "starting" -> R.string.avas_engine_starting
                state.testActive -> R.string.avas_engine_testing
                state.state == "active" -> R.string.avas_engine_active
                state.state == "stopping" -> R.string.avas_engine_stopping
                else -> R.string.avas_engine_stopped
            }), color = if (hasError) colors.red else colors.muted, fontSize = 13.sp)
            MirrorBindingRow(strings.resource(R.string.avas_engine_key_binding),
                CameraButtonBindings.Action.AvasEngine, state.binding, state.enabled,
                strings, colors, onAction)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton(strings.resource(R.string.avas_engine_start), colors,
                    Modifier.weight(1f), icon = Icons.Outlined.PlayArrow, primary = true,
                    enabled = state.startAllowed) { send(AvasActionKind.StartManual) }
                ActionButton(strings.resource(R.string.avas_engine_stop), colors,
                    Modifier.weight(1f), icon = Icons.Outlined.Stop, mainBackground = true,
                    enabled = state.stopAllowed) { send(AvasActionKind.StopManual) }
            }
        }
    }
}

@Composable
private fun engineVolume(
    strings: UiStrings,
    colors: UiPalette,
    volume: Int,
    enabled: Boolean,
    identity: String,
    onCommit: (String) -> Unit,
) {
    val volumeTitle = strings.resource(R.string.avas_volume)
    NumericSetting(volumeTitle, volume.coerceIn(0, 100).toString(), "%", colors,
        onCommit, 0f..100f, enabled = enabled, adjustable = true, slider = true,
        showLabel = false, compactSuffix = true, inputWidth = 52.dp, identity = identity)
}

private fun enginePackResource(packId: String) = when (packId) {
    AvasEngineSettings.JAGUAR_V6 -> R.string.avas_engine_pack_jaguar_v6
    AvasEngineSettings.HURACAN_V10 -> R.string.avas_engine_pack_huracan_v10
    AvasEngineSettings.GERMAN_L4 -> R.string.avas_engine_pack_german_l4
    AvasEngineSettings.MASERATI_V8 -> R.string.avas_engine_pack_maserati_v8
    AvasEngineSettings.G500_V8 -> R.string.avas_engine_pack_g500_v8
    AvasEngineSettings.GOLF_GTI_L4 -> R.string.avas_engine_pack_golf_gti_l4
    AvasEngineSettings.PORSCHE_GT3_H6 -> R.string.avas_engine_pack_porsche_gt3_h6
    AvasEngineSettings.HARLEY_VTWIN -> R.string.avas_engine_pack_harley_vtwin
    else -> R.string.avas_engine_pack_ferrari_v8
}
