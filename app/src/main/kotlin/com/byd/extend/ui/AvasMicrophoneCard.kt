package com.byd.extend.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.byd.extend.AvasMicrophoneSettings
import com.byd.extend.CameraButtonBindings
import com.byd.extend.R

@Composable
internal fun AvasMicrophoneCard(
    state: AvasMicrophoneUiState, strings: UiStrings, colors: UiPalette,
    onAction: (BydExtendUiAction) -> Unit, modifier: Modifier = Modifier,
) {
    fun send(kind: AvasActionKind, enabled: Boolean? = null, volume: Int? = null) {
        onAction(BydExtendUiAction.Avas(AvasBackendAction(
            AvasMicrophoneSettings.PROFILE, kind, enabled, volume)))
    }
    val title = strings.resource(R.string.avas_mic_title)
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
        Column(Modifier.fillMaxWidth().fillMaxHeight().alpha(if (state.enabled) 1f else .45f),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            MirrorBindingRow(strings.resource(R.string.avas_mic_toggle_hint),
                CameraButtonBindings.Action.AvasMicrophone, state.binding, state.enabled,
                strings, colors, onAction, showPress = false)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val volumeTitle = strings.resource(R.string.avas_volume)
                Text(volumeTitle, color = colors.text, fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold)
                NumericSetting(volumeTitle, state.volume.toString(), "%", colors,
                    { send(AvasActionKind.SetVolume, volume = it.toFloat().toInt()) },
                    0f..100f, enabled = state.enabled, adjustable = true, slider = true,
                    showLabel = false, compactSuffix = true, inputWidth = 52.dp,
                    identity = "avas-microphone-volume")
            }
            SwitchLine(strings.resource(R.string.avas_mic_noise_suppression),
                if (state.noiseSuppressionSupported) "" else strings.resource(R.string.avas_mic_effect_unsupported),
                state.noiseSuppression, { send(AvasActionKind.SetNoiseSuppression, enabled = it) },
                colors, enabled = state.enabled && state.noiseSuppressionSupported)
            SwitchLine(strings.resource(R.string.avas_mic_echo_cancellation),
                if (state.echoCancellationSupported) "" else strings.resource(R.string.avas_mic_effect_unsupported),
                state.echoCancellation, { send(AvasActionKind.SetEchoCancellation, enabled = it) },
                colors, enabled = state.enabled && state.echoCancellationSupported)
            Spacer(Modifier.weight(1f))
            Text(strings.resource(when (state.state) {
                "starting" -> R.string.avas_mic_starting
                "active" -> R.string.avas_mic_active
                "error" -> R.string.avas_mic_error
                else -> R.string.avas_mic_stopped
            }), color = if (state.state == "error") colors.red else colors.muted, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton(strings.text("Старт", "Start", "开始"), colors, Modifier.weight(1f),
                    icon = Icons.Outlined.PlayArrow, primary = true,
                    enabled = state.enabled && !state.busy) { send(AvasActionKind.StartManual) }
                ActionButton(strings.text("Стоп", "Stop", "停止"), colors, Modifier.weight(1f),
                    icon = Icons.Outlined.Stop, mainBackground = true,
                    enabled = state.enabled && state.busy) { send(AvasActionKind.StopManual) }
            }
        }
    }
}
