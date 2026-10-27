package com.byd.extend.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.byd.extend.CameraButtonBindings
import com.byd.extend.R

@Composable
internal fun HazardThanksSettingsSection(
    state: HazardThanksUiState,
    strings: UiStrings,
    colors: UiPalette,
    onAction: (BydExtendUiAction) -> Unit,
) {
    val title = strings.resource(R.string.hazard_thanks_title)
    val onToggle: (Boolean) -> Unit = {
        onAction(BydExtendUiAction.Toggle(ToggleTarget.Simple(ToggleId.HazardThanks), it))
    }
    Section(title, colors, header = {
        Row(Modifier.fillMaxWidth().background(colors.panelAlt)
            .avasSwitchRow(state.enabled, onToggle, colors)
            .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(title.uppercase(), color = colors.muted, fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            AppSwitch(state.enabled, onToggle, colors, clearSemantics = true, label = title)
        }
    }) {
        MirrorBindingRow(null,
            CameraButtonBindings.Action.HazardThanks, state.binding, state.enabled,
            strings, colors, onAction)
        Column {
            Text(strings.resource(R.string.hazard_thanks_flashes),
                color = colors.text, fontSize = 16.sp)
            Segmented(listOf("1", "2", "3"), state.flashes - 1, colors,
                Modifier.fillMaxWidth().padding(top = 4.dp), enabled = { state.enabled }) {
                onAction(BydExtendUiAction.Select(
                    SelectionTarget.Simple(SelectionId.HazardThanksFlashes), it))
            }
        }
        Text(strings.resource(R.string.hazard_thanks_cancel),
            color = colors.muted, fontSize = 13.sp)
    }
}
