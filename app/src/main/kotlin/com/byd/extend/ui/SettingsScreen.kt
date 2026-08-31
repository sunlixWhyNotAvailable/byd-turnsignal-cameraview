package com.byd.extend.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun SettingsScreen(
    state: SettingsUiState,
    legacyRuntimeBlocked: Boolean,
    strings: UiStrings,
    colors: UiPalette,
    onAction: (BydExtendUiAction) -> Unit,
) {
    val focus = LocalFocusManager.current
    ScreenSurface(colors, scroll = false) {
        PageTitle(strings.tabs[4], strings.text("Дозволи, параметри виводу камер та логи",
            "Permissions, camera output settings, and logs"), colors)
        Row(Modifier.fillMaxWidth().weight(1f).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.width(260.dp).fillMaxHeight().clip(RoundedCornerShape(12.dp))
                .border(1.dp, colors.border, RoundedCornerShape(12.dp)).background(colors.panelAlt)
                .padding(horizontal = 8.dp, vertical = 10.dp)) {
                Text(strings.text("КАТЕГОРІЇ", "CATEGORIES"), color = colors.muted, fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
                Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).selectableGroup(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val icons = listOf(Icons.Outlined.Security, Icons.Outlined.Videocam, Icons.Outlined.BugReport)
                    strings.settingsCategories.forEachIndexed { index, title ->
                        val selected = state.category.ordinal == index
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                            .background(if (selected) colors.accent.copy(alpha = .14f) else Color.Transparent)
                            .selectable(selected, role = Role.Tab) {
                                focus.clearFocus()
                                onAction(BydExtendUiAction.Select(
                                    SelectionTarget.Simple(SelectionId.SettingsCategory), index))
                            }.padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Icon(icons[index], null, tint = if (selected) colors.accent else colors.muted,
                                modifier = Modifier.size(22.dp))
                            Spacer(Modifier.width(12.dp))
                            Text(title, color = if (selected) colors.accent else colors.muted, fontSize = 14.sp,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, maxLines = 2)
                        }
                    }
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(12.dp))
                .border(1.dp, colors.border, RoundedCornerShape(12.dp)).background(colors.surface)
                .padding(12.dp).verticalScroll(LocalPrimaryScroll.current)) {
                if (legacyRuntimeBlocked) {
                    StatusText(StatusUiState(strings.text(
                        "Керування заблоковано до завершення переходу налаштувань.",
                        "Runtime controls are locked while settings transfer completes."),
                        StatusTone.Warning, true), colors)
                }
                when (state.category) {
                    SettingsCategory.Permissions -> PermissionsSettings(state, strings, colors, onAction)
                    SettingsCategory.CameraOutput -> CameraOutputSettings(state, strings, colors, onAction)
                    SettingsCategory.Logs -> LogSettings(state, strings, colors, onAction)
                }
            }
        }
    }
}

@Composable
private fun PermissionsSettings(state: SettingsUiState, strings: UiStrings, colors: UiPalette,
    onAction: (BydExtendUiAction) -> Unit) {
    Section(strings.settingsCategories[0], colors, bodyPadding = 0.dp) {
        Box(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
            SwitchLine(strings.text("Авто-запуск", "Automatic start"),
                strings.text("Відновлювати служби після boot та quickboot", "Restore services after boot and quickboot"),
                state.automaticStart,
                { onAction(BydExtendUiAction.Toggle(ToggleTarget.Simple(ToggleId.AutoStart), it)) }, colors,
                pending = state.automaticStartOperation.pending, enabled = state.automaticStartOperation.enabled)
        }
        Divider(colors)
        SettingsActionRow(strings.text("Робота у фоні", "Background operation"),
            strings.text("Керування фоновим запуском DiLink", "Manage DiLink background startup"), colors) {
            ActionButton(strings.text("Робота у фоні", "Background settings"), colors, Modifier.width(190.dp)) {
                onAction(BydExtendUiAction.Run(CommandId.OpenBackgroundSettings))
            }
        }
        Divider(colors)
        SettingsActionRow(strings.text("Дозволи ADB", "ADB permissions"),
            strings.text("Безпечна локальна авторизація та видача потрібних дозволів",
                "Safe local authorization and required permission grants"), colors) {
            ActionButton(strings.text("Видати ADB", "Grant ADB"), colors, Modifier.width(190.dp), primary = true,
                enabled = state.adbOperation.enabled && !state.adbOperation.pending) {
                onAction(BydExtendUiAction.Run(CommandId.GrantAdb))
            }
        }
    }
}

@Composable
private fun CameraOutputSettings(state: SettingsUiState, strings: UiStrings, colors: UiPalette,
    onAction: (BydExtendUiAction) -> Unit) {
    Section(strings.settingsCategories[1], colors) {
        Text(strings.text("Якість зображення", "Image quality"), color = colors.text,
            fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Segmented(listOf(strings.text("Швидкодія", "Performance"), strings.text("Баланс", "Balanced"),
            strings.text("Якість", "Quality"), strings.text("Оригінал", "Original")),
            state.cameraOutput.quality, colors, Modifier.fillMaxWidth()) {
            onAction(BydExtendUiAction.Select(SelectionTarget.Simple(SelectionId.CameraQuality), it))
        }
        NumericSetting(strings.text("Заокруглення камер", "Camera corner radius"),
            state.cameraOutput.cornerRadius, "dp", colors,
            { onAction(BydExtendUiAction.CommitNumber(NumberTarget.Output(OutputNumber.CornerRadius), it)) },
            0f..48f, adjustable = true, slider = true, sliderDots = true,
            identity = NumberTarget.Output(OutputNumber.CornerRadius))
        NumericSetting(strings.text("Прозорість камер", "Camera transparency"),
            state.cameraOutput.transparency, "%", colors,
            { onAction(BydExtendUiAction.CommitNumber(NumberTarget.Output(OutputNumber.Transparency), it)) },
            0f..100f, adjustable = true, slider = true,
            identity = NumberTarget.Output(OutputNumber.Transparency))
    }
}

@Composable
private fun LogSettings(state: SettingsUiState, strings: UiStrings, colors: UiPalette,
    onAction: (BydExtendUiAction) -> Unit) {
    Section(strings.settingsCategories[2], colors, bodyPadding = 0.dp) {
        if (state.feedback.visible) Box(Modifier.padding(14.dp)) { StatusText(state.feedback, colors) }
        SettingsActionRow(strings.text("Перевіряти оновлення", "Check for updates"),
            strings.text("Перевірка нової версії та пропозиція оновлення",
                "Check for a new version and offer an update"), colors) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ActionButton(strings.text("Перевірити оновлення", "Check for updates"), colors, Modifier.width(190.dp),
                    enabled = state.updateOperation.enabled && !state.updateOperation.pending) {
                    onAction(BydExtendUiAction.Run(CommandId.CheckForUpdates))
                }
                AppSwitch(state.automaticUpdate,
                    { onAction(BydExtendUiAction.Toggle(ToggleTarget.Simple(ToggleId.AutomaticUpdate), it)) },
                    colors, compact = true)
            }
        }
        Divider(colors)
        SettingsActionRow(strings.text("Діагностичні логи", "Diagnostic logs"),
            strings.text("Поділитися або очистити локальну історію", "Share or clear local history"), colors) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton(strings.text("Поділитись", "Share"), colors, Modifier.width(190.dp),
                    icon = Icons.Outlined.Share, enabled = state.logOperation.enabled && !state.logOperation.pending) {
                    onAction(BydExtendUiAction.Run(CommandId.ShareLogs))
                }
                ActionButton(strings.text("Очистити логи", "Clear logs"), colors, Modifier.width(190.dp),
                    enabled = state.logOperation.enabled && !state.logOperation.pending) {
                    onAction(BydExtendUiAction.Run(CommandId.ClearLogs))
                }
            }
        }
        Divider(colors)
        SettingsActionRow(strings.text("Пакет сумісності", "Compatibility package"),
            strings.text("Дані системи для перевірки сумісності", "System details for compatibility checks"), colors) {
            ActionButton(strings.text("Поділитись", "Share"), colors, Modifier.width(190.dp),
                icon = Icons.Outlined.Share,
                enabled = state.compatibilityOperation.enabled && !state.compatibilityOperation.pending) {
                onAction(BydExtendUiAction.Run(CommandId.ShareCompatibilityPackage))
            }
        }
        Divider(colors)
        SettingsActionRow(strings.text("Пресети камер", "Camera presets"),
            strings.text("Експорт і завантаження налаштувань камер", "Export and load camera settings"), colors) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton(strings.text("Вивантажити", "Export"), colors, Modifier.width(190.dp),
                    enabled = state.presetOperation.enabled && !state.presetOperation.pending) {
                    onAction(BydExtendUiAction.Run(CommandId.ExportCameraPresets))
                }
                ActionButton(strings.text("Завантажити", "Load"), colors, Modifier.width(190.dp),
                    enabled = state.presetOperation.enabled && !state.presetOperation.pending) {
                    onAction(BydExtendUiAction.Run(CommandId.LoadCameraPresets))
                }
            }
        }
        Divider(colors)
        SettingsActionRow(strings.text("Імпортувати налаштування", "Import settings"),
            strings.text("Перенести всі налаштування з попереднього застосунку",
                "Transfer all settings from the previous application"), colors) {
            ActionButton(strings.text("Імпортувати", "Import"), colors, Modifier.width(190.dp),
                enabled = state.importOperation.enabled && !state.importOperation.pending) {
                onAction(BydExtendUiAction.Run(CommandId.ImportLegacySettings))
            }
        }
        if (state.restoreLegacyAccessVisible) {
            Divider(colors)
            SettingsActionRow(strings.text("Відновити доступ", "Restore access"),
                strings.text("Відновити доступ без повторного імпорту", "Restore access without importing again"), colors) {
                ActionButton(strings.text("Відновити", "Restore"), colors, Modifier.width(190.dp)) {
                    onAction(BydExtendUiAction.Run(CommandId.RestoreLegacyAccess))
                }
            }
        }
        Divider(colors)
        SettingsActionRow(strings.text("Вимкнути", "Shutdown"),
            strings.text("Завершити роботу застосунку до наступного відкриття",
                "Stop the app until it is opened again"), colors) {
            ActionButton(strings.text("Вимкнути", "Shutdown"), colors, Modifier.width(190.dp),
                icon = Icons.Outlined.PowerSettingsNew, destructive = true) {
                onAction(BydExtendUiAction.Run(CommandId.Shutdown))
            }
        }
    }
}
