package com.byd.extend.ui

import android.content.SharedPreferences
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.viewinterop.AndroidView
import com.byd.extend.readProductionUiState

/** Activity-owned effects. Compose never receives Binder, Surface, Bitmap or preferences. */
interface ProductionUiBackend {
    fun onProductionUiAction(action: BydExtendUiAction)
    fun obtainProductionCameraHost(slot: CameraHostSlot): View
    fun updateProductionCameraHost(view: View, slot: CameraHostSlot)
    fun releaseProductionCameraHost(view: View, slot: CameraHostSlot)
    fun automaticStartEnabled(): Boolean
    fun legacyAccessRestoreVisible(): Boolean
}

/** Main-thread state boundary between the production runtime and Compose. */
class ProductionUiController(
    private val preferences: SharedPreferences,
    internal val backend: ProductionUiBackend,
) {
    private var pendingDialogCommand: CommandId? = null

    var state: BydExtendUiState by mutableStateOf(readState())
        private set

    fun dispatch(action: BydExtendUiAction) {
        if (action is BydExtendUiAction.Run && interceptDialogCommand(action.command)) return
        when (action) {
            is BydExtendUiAction.Navigate -> {
                state = state.copy(activeTab = action.tab)
                preferences.edit().putInt("selected_tab", action.tab.legacyTab()).apply()
            }
            is BydExtendUiAction.SetLanguage -> {
                preferences.edit().putString(PREF_LANGUAGE, action.language.wire()).apply()
                state = state.copy(language = action.language)
            }
            is BydExtendUiAction.SetTheme -> {
                preferences.edit().putBoolean(PREF_DARK_THEME, action.theme == UiTheme.Dark).apply()
                state = state.copy(theme = action.theme)
            }
            is BydExtendUiAction.Select -> if (action.target.isLocalSelection()) {
                applyLocalSelection(action.target, action.index)
            }
            is BydExtendUiAction.Toggle -> if (
                action.target == ToggleTarget.Simple(ToggleId.AutoStart)
            ) {
                state = state.copy(settings = state.settings.copy(automaticStart = action.value))
            }
            is BydExtendUiAction.Run -> applyLocalCommand(action.command)
            else -> Unit
        }
        backend.onProductionUiAction(action)
        if (action is BydExtendUiAction.Toggle &&
                action.target != ToggleTarget.Simple(ToggleId.AutoStart) ||
            action is BydExtendUiAction.CommitNumber ||
            action is BydExtendUiAction.MoveProfile ||
            action is BydExtendUiAction.Select && !action.target.isLocalSelection()) reload()
    }

    /** Re-read validated values while preserving navigation and live runtime feedback. */
    fun reload() {
        val old = state
        val fresh = readState()
        state = fresh.copy(
            header = fresh.header.copy(adb = old.header.adb, location = old.header.location),
            signals = fresh.signals.copy(
                guard = fresh.signals.guard.copy(operation = old.signals.guard.operation),
                music = fresh.signals.music.copy(
                    operation = old.signals.music.operation, journal = old.signals.music.journal),
                weather = fresh.signals.weather.copy(
                    operation = old.signals.weather.operation, refresh = old.signals.weather.refresh),
            ),
            blind = fresh.blind.copy(
                section = old.blind.section,
                selectedGroup = old.blind.selectedGroup,
                selectedSide = old.blind.selectedSide,
                profiles = mergeProfileOperations(fresh.blind.profiles, old.blind.profiles),
            ),
            parking = fresh.parking.copy(
                section = old.parking.section,
                selectedView = old.parking.selectedView,
                views = fresh.parking.views.mapValues { (view, value) -> value.copy(
                    profile = value.profile.copy(
                        operation = old.parking.views[view]?.profile?.operation
                            ?: value.profile.operation)) },
            ),
            reverse = fresh.reverse.copy(
                section = old.reverse.section,
                selectedElement = old.reverse.selectedElement,
                selectedSource = old.reverse.selectedSource,
                showFront = old.reverse.showFront,
                profiles = mergeProfileOperations(fresh.reverse.profiles, old.reverse.profiles),
            ),
            settings = fresh.settings.copy(
                category = old.settings.category,
                automaticStartOperation = old.settings.automaticStartOperation,
                adbOperation = old.settings.adbOperation,
                updateOperation = old.settings.updateOperation,
                logOperation = old.settings.logOperation,
                compatibilityOperation = old.settings.compatibilityOperation,
                presetOperation = old.settings.presetOperation,
                importOperation = old.settings.importOperation,
                feedback = old.settings.feedback,
            ),
            debug = fresh.debug.copy(
                mode = old.debug.mode,
                manualSignalsAllowed = old.debug.manualSignalsAllowed,
                manualSignalStatus = old.debug.manualSignalStatus,
                directSelection = old.debug.directSelection,
                directOperation = old.debug.directOperation,
                avmSelection = old.debug.avmSelection,
                avmOperation = old.debug.avmOperation,
            ),
            dialog = old.dialog,
        )
    }

    fun setHeader(header: HeaderUiState) { state = state.copy(header = header) }

    fun setGuardStatus(status: StatusUiState) {
        state = state.copy(signals = state.signals.copy(
            guard = state.signals.guard.copy(
                operation = state.signals.guard.operation.copy(status = status))))
    }

    fun setMusicStatus(status: StatusUiState) {
        state = state.copy(signals = state.signals.copy(
            music = state.signals.music.copy(
                operation = state.signals.music.operation.copy(status = status))))
    }

    fun appendMusicJournal(line: String) {
        state = state.copy(signals = state.signals.copy(music = state.signals.music.copy(
            journal = (state.signals.music.journal + line).takeLast(20))))
    }

    fun setWeatherStatus(status: StatusUiState, pending: Boolean = false) {
        state = state.copy(signals = state.signals.copy(weather = state.signals.weather.copy(
            refresh = state.signals.weather.refresh.copy(status = status, pending = pending))))
    }

    fun setSettingsFeedback(status: StatusUiState) {
        state = state.copy(settings = state.settings.copy(feedback = status))
    }

    fun setSettingsOperation(
        operation: SettingsOperation,
        status: StatusUiState,
        pending: Boolean,
    ) {
        fun next() = OperationUiState(enabled = !pending, pending = pending, status = status)
        state = state.copy(settings = when (operation) {
            SettingsOperation.AutoStart -> state.settings.copy(automaticStartOperation = next())
            SettingsOperation.Adb -> state.settings.copy(adbOperation = next())
            SettingsOperation.Update -> state.settings.copy(updateOperation = next())
            SettingsOperation.Logs -> state.settings.copy(logOperation = next())
            SettingsOperation.Compatibility -> state.settings.copy(compatibilityOperation = next())
            SettingsOperation.Preset -> state.settings.copy(presetOperation = next())
            SettingsOperation.Import -> state.settings.copy(importOperation = next())
        })
    }

    fun showDialog(dialog: DialogUiState?) { state = state.copy(dialog = dialog) }

    fun setDiagnosticStatus(direct: Boolean, status: StatusUiState, pending: Boolean = false) {
        state = state.copy(debug = if (direct) state.debug.copy(
            directOperation = state.debug.directOperation.copy(status = status, pending = pending))
        else state.debug.copy(
            avmOperation = state.debug.avmOperation.copy(status = status, pending = pending)))
    }

    fun setProfileStatus(profile: CameraProfileId, status: StatusUiState, pending: Boolean = false) {
        val operation = OperationUiState(enabled = true, pending = pending, status = status)
        state = when (profile) {
            is CameraProfileId.Blind -> state.copy(blind = state.blind.copy(
                profiles = state.blind.profiles + (profile to
                    (state.blind.profiles[profile] ?: CameraProfileUiState()).copy(operation = operation))))
            is CameraProfileId.Parking -> {
                val current = state.parking.views[profile.view] ?: ParkingViewUiState()
                state.copy(parking = state.parking.copy(views = state.parking.views +
                    (profile.view to current.copy(profile = current.profile.copy(operation = operation)))))
            }
            is CameraProfileId.Reverse -> state.copy(reverse = state.reverse.copy(
                profiles = state.reverse.profiles + (profile to
                    (state.reverse.profiles[profile] ?: CameraProfileUiState()).copy(operation = operation))))
        }
    }

    private fun readState() = readProductionUiState(
        preferences, backend.automaticStartEnabled(), backend.legacyAccessRestoreVisible())

    private fun applyLocalSelection(target: SelectionTarget, index: Int) {
        val simple = target as? SelectionTarget.Simple ?: return
        when (simple.id) {
            SelectionId.CameraSection -> when (state.activeTab) {
                RootTab.Blind -> selectSection(UiSelectionPreferences.BLIND_SECTION, index) {
                    state = state.copy(blind = state.blind.copy(section = it))
                }
                RootTab.Parking -> selectSection(UiSelectionPreferences.PARKING_SECTION, index) {
                    state = state.copy(parking = state.parking.copy(section = it))
                }
                RootTab.Reverse -> selectSection(UiSelectionPreferences.REVERSE_SECTION, index) {
                    state = state.copy(reverse = state.reverse.copy(section = it))
                }
                else -> Unit
            }
            SelectionId.BlindGroup -> state = state.copy(blind = state.blind.copy(
                selectedGroup = CameraGroup.entries.getOrElse(index) { CameraGroup.Rear }))
            SelectionId.BlindSide -> state = state.copy(blind = state.blind.copy(
                selectedSide = CameraSide.entries.getOrElse(index) { CameraSide.Left }))
            SelectionId.ParkingView -> state = state.copy(parking = state.parking.copy(
                selectedView = ParkingView.entries.getOrElse(index) { ParkingView.FrontLeft }))
            SelectionId.ReverseElement -> {
                val value = ReverseElement.entries.getOrElse(index) { ReverseElement.RearLeft }
                preferences.edit().putInt(UiSelectionPreferences.REVERSE_ELEMENT, value.ordinal).apply()
                state = state.copy(reverse = state.reverse.copy(selectedElement = value))
            }
            SelectionId.ReverseSource -> {
                val value = ReverseSource.entries.getOrElse(index) { ReverseSource.Rear }
                preferences.edit().putInt(UiSelectionPreferences.REVERSE_SOURCE, value.ordinal).apply()
                state = state.copy(reverse = state.reverse.copy(
                    selectedSource = value, showFront = value == ReverseSource.Front))
            }
            SelectionId.SettingsCategory -> {
                val value = SettingsCategory.entries.getOrElse(index) { SettingsCategory.Permissions }
                preferences.edit().putInt(UiSelectionPreferences.SETTINGS_CATEGORY, value.ordinal).apply()
                state = state.copy(settings = state.settings.copy(category = value))
            }
            SelectionId.DiagnosticMode -> {
                val value = DiagnosticMode.entries.getOrElse(index) { DiagnosticMode.Signals }
                preferences.edit().putInt(UiSelectionPreferences.DEBUG_MODE, value.ordinal).apply()
                state = state.copy(debug = state.debug.copy(mode = value))
            }
            SelectionId.DirectMode -> state = state.copy(debug = state.debug.copy(
                directSelection = index.coerceIn(0, 4), avmSelection = null))
            SelectionId.AvmMode -> state = state.copy(debug = state.debug.copy(
                avmSelection = index.coerceIn(0, 50), directSelection = null))
            SelectionId.AvmOrientation -> state = state.copy(debug = state.debug.copy(
                avmOrientation = if (index == 0) AvmOrientation.Horizontal else AvmOrientation.Vertical))
            else -> Unit
        }
    }

    private inline fun selectSection(
        key: String, index: Int, apply: (CameraSection) -> Unit,
    ) {
        val value = CameraSection.entries.getOrElse(index) { CameraSection.Parameters }
        preferences.edit().putInt(key, value.ordinal).apply()
        apply(value)
    }

    private fun applyLocalCommand(command: CommandId) {
        when (command) {
            CommandId.StopDiagnosticCamera -> state = state.copy(debug = state.debug.copy(
                directSelection = null, avmSelection = null))
            else -> Unit
        }
    }

    private fun interceptDialogCommand(command: CommandId): Boolean {
        val english = state.language == UiLanguage.English
        val dialog = when (command) {
            CommandId.OpenMusicJournal -> DialogUiState(
                DialogKind.MusicJournal,
                if (english) "Music journal" else "Журнал музики",
                "",
            )
            CommandId.OpenBackgroundSettings -> DialogUiState(
                DialogKind.Background,
                if (english) "DiLink background start" else "Фоновий запуск DiLink",
                if (english) "This action opens the system background-start settings."
                else "Ця дія відкриє системні налаштування фонового запуску.",
                cancellable = false,
            )
            CommandId.CheckForUpdates -> DialogUiState(
                DialogKind.Update,
                if (english) "Application update" else "Оновлення застосунку",
                if (english) "Check whether a newer BYD Extend version is available?"
                else "Перевірити, чи доступна новіша версія BYD Extend?",
                cancellable = false,
            )
            CommandId.Shutdown -> DialogUiState(
                DialogKind.Shutdown,
                "Shutdown",
                if (english) "Services will stop until the application is opened again."
                else "Служби буде зупинено до наступного ручного відкриття застосунку.",
            )
            else -> null
        }
        if (dialog != null) {
            pendingDialogCommand = if (command == CommandId.OpenMusicJournal) null else command
            state = state.copy(dialog = dialog)
            return true
        }
        if (command == CommandId.DismissDialog) {
            pendingDialogCommand = null
            state = state.copy(dialog = null)
            return true
        }
        if (command == CommandId.ConfirmDialog) {
            val pending = pendingDialogCommand
            pendingDialogCommand = null
            state = state.copy(dialog = null)
            if (pending != null) backend.onProductionUiAction(BydExtendUiAction.Run(pending))
            return true
        }
        if (command == CommandId.CancelOperation) {
            pendingDialogCommand = null
            state = state.copy(dialog = null)
            backend.onProductionUiAction(BydExtendUiAction.Run(command))
            return true
        }
        return false
    }

    companion object {
        const val PREF_LANGUAGE = "ui_language"
        const val PREF_DARK_THEME = "ui_dark_theme"
    }
}

object ProductionUiInstaller {
    @JvmStatic
    fun install(activity: ComponentActivity, controller: ProductionUiController) {
        activity.setContent {
            BydExtendApp(controller.state, controller::dispatch) { slot ->
                ProductionCameraHost(controller, slot)
            }
        }
    }
}

@Composable
private fun ProductionCameraHost(controller: ProductionUiController, slot: CameraHostSlot) {
    AndroidView(
        factory = { controller.backend.obtainProductionCameraHost(slot) },
        modifier = Modifier.fillMaxSize(),
        update = { controller.backend.updateProductionCameraHost(it, slot) },
        onRelease = { controller.backend.releaseProductionCameraHost(it, slot) },
    )
}

private fun RootTab.legacyTab() = when (this) {
    RootTab.Signals -> 0
    RootTab.Blind -> 1
    RootTab.Debug -> 2
    RootTab.Reverse -> 5
    RootTab.Settings -> 7
    RootTab.Parking -> 8
}

private fun UiLanguage.wire() = if (this == UiLanguage.English) "en" else "uk"

private fun SelectionTarget.isLocalSelection() = this is SelectionTarget.Simple && id in setOf(
    SelectionId.CameraSection, SelectionId.BlindGroup, SelectionId.BlindSide,
    SelectionId.ParkingView, SelectionId.ReverseElement, SelectionId.ReverseSource,
    SelectionId.SettingsCategory, SelectionId.DiagnosticMode, SelectionId.DirectMode,
    SelectionId.AvmMode, SelectionId.AvmOrientation,
)

private fun <K> mergeProfileOperations(
    fresh: Map<K, CameraProfileUiState>, old: Map<K, CameraProfileUiState>,
) = fresh.mapValues { (key, value) -> value.copy(
    operation = old[key]?.operation ?: value.operation) }
