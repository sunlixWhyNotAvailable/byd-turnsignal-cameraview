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

    /**
     * Camera section changes are separate from profile/source changes.  Implementations can
     * update the existing host in place and reserve close/reopen for a real calibration entry.
     */
    fun onProductionCameraSectionChanged(
        tab: RootTab, previous: CameraSection, next: CameraSection,
    ) = Unit

    /** Reverse element selection changes editor focus only; the composition host stays warm. */
    fun onProductionReverseElementFocusChanged(
        section: CameraSection, previous: ReverseElement, next: ReverseElement,
    ) = Unit

    /** Persist and propagate one Reverse pane visibility bit without a full state reload. */
    fun onProductionReverseVisibilityChanged(
        element: ReverseElement, visible: Boolean,
    ) = Unit

    /**
     * Applies a transient numeric preview to the live host.  Returning null rejects the value;
     * returning a string supplies the canonical accepted value for the UI state.  The default
     * rejects previews so an Activity that has not opted into a live host cannot falsely accept.
     */
    fun onProductionUiPreview(target: NumberTarget, value: String): String? = null

    fun obtainProductionCameraHost(slot: CameraHostSlot): View
    fun updateProductionCameraHost(view: View, slot: CameraHostSlot)
    fun releaseProductionCameraHost(view: View, slot: CameraHostSlot)
    fun automaticStartEnabled(): Boolean
    fun legacyAccessRestoreVisible(): Boolean
    fun runtimeBlockedByLegacy(): Boolean

    /** Activity supplies real display pixels and tablet chrome insets when available. */
    fun productionDisplayGeometry(target: DisplayTarget): CameraDisplayGeometry =
        CameraDisplayGeometry.default(target)
}

/** Main-thread state boundary between the production runtime and Compose. */
class ProductionUiController(
    private val preferences: SharedPreferences,
    internal val backend: ProductionUiBackend,
) {
    private var pendingDialogCommand: CommandId? = null
    /** Last accepted slider previews, keyed by stable target identity until final commit. */
    private val previewValues = linkedMapOf<NumberTarget, String>()
    private val previewSessions = linkedMapOf<NumberTarget, Long>()
    /** Values already finalized by navigation; suppresses a late disposed-slider finish callback. */
    private val flushedPreviewValues = linkedMapOf<NumberTarget, String>()
    private val flushedPreviewSessions = linkedMapOf<NumberTarget, Long>()

    var state: BydExtendUiState by mutableStateOf(readState())
        private set

    fun dispatch(action: BydExtendUiAction) {
        syncLegacyRuntimeBlock()
        if (state.legacyRuntimeBlocked && !allowedWhileLegacyBlocked(action)) {
            showLegacyHandoverBlock()
            return
        }
        if (action is BydExtendUiAction.Run && interceptDialogCommand(action.command)) return

        // A gesture can leave the slider before the next navigation/selection event.  Flush the
        // accepted preview exactly once before that event; the final CommitNumber path removes
        // its own target below and therefore does not duplicate the write.
        if (action !is BydExtendUiAction.PreviewNumber &&
            action !is BydExtendUiAction.CommitNumber) flushPreviewValues()
        if (action is BydExtendUiAction.CommitNumber) {
            val sessionId = action.sessionId
            val closedSession = flushedPreviewSessions[action.target]
            if (sessionId != null && closedSession != null && sessionId <= closedSession) {
                // Already finalized token; do not disturb a newer gesture for this target.
                return
            }
            if (sessionId != null && previewSessions[action.target] != sessionId) return
            val hadPreview = previewValues.remove(action.target) != null
            if (!hadPreview && sessionId == null && flushedPreviewValues[action.target] == action.value) {
                // The old slider can report onValueChangeFinished after its host was disposed by
                // navigation.  Its value was already committed synchronously by flushPreviewValues.
                flushedPreviewValues.remove(action.target)
                return
            }
            // Keep the just-finished token closed until a newer gesture starts.  A disposed
            // slider can still deliver a late preview callback after its final commit; accepting
            // that old token would reopen the preview map and cause a second durable write.
            if (sessionId != null) flushedPreviewSessions[action.target] = sessionId
            previewSessions.remove(action.target)
            flushedPreviewValues.remove(action.target)
        }

        if (action is BydExtendUiAction.PreviewNumber) {
            preview(action.target, action.value, action.sessionId)
            return
        }
        var typedBackendHandled = false
        when (action) {
            is BydExtendUiAction.Navigate -> {
                state = state.copy(activeTab = action.tab)
                preferences.edit().putInt("selected_tab", action.tab.legacyTab()).apply()
            }
            is BydExtendUiAction.SetLanguage -> {
                preferences.edit().putString(PREF_LANGUAGE, action.language.wire()).apply()
                state = state.copy(
                    language = action.language,
                    settings = if (state.legacyRuntimeBlocked &&
                        state.settings.feedback.text == handoverReason(state.language)) {
                        state.settings.copy(feedback = StatusUiState(
                            handoverReason(action.language), StatusTone.Warning, true))
                    } else state.settings,
                )
            }
            is BydExtendUiAction.SetTheme -> {
                preferences.edit().putBoolean(PREF_DARK_THEME, action.theme == UiTheme.Dark).apply()
                state = state.copy(theme = action.theme)
            }
            is BydExtendUiAction.Select -> if (action.target.isLocalSelection()) {
                val simple = action.target as? SelectionTarget.Simple
                val previousSection = if (simple?.id == SelectionId.CameraSection) {
                    cameraSection(state.activeTab)
                } else null
                val previousElement = if (simple?.id == SelectionId.ReverseElement &&
                    state.activeTab == RootTab.Reverse) state.reverse.selectedElement else null
                applyLocalSelection(action.target, action.index)
                when {
                    simple?.id == SelectionId.CameraSection && previousSection != null -> {
                        // CameraSection is fully typed, including no-op selections; never fall
                        // through to the legacy close/reopen profile path.
                        typedBackendHandled = true
                        val next = cameraSection(state.activeTab)
                        if (next != null && next != previousSection) {
                            backend.onProductionCameraSectionChanged(
                                state.activeTab, previousSection, next)
                        }
                    }
                    simple?.id == SelectionId.ReverseElement && previousElement != null -> {
                        typedBackendHandled = true
                        val next = state.reverse.selectedElement
                        if (next != previousElement) {
                            backend.onProductionReverseElementFocusChanged(
                                state.reverse.section, previousElement, next)
                        }
                    }
                    simple?.id == SelectionId.ReverseElement -> typedBackendHandled = true
                }
            }
            is BydExtendUiAction.Toggle -> when {
                action.target == ToggleTarget.Simple(ToggleId.AutoStart) -> {
                    state = state.copy(settings = state.settings.copy(
                        automaticStart = action.value))
                }
                action.target is ToggleTarget.Reverse &&
                    (action.target as ToggleTarget.Reverse).id == ToggleId.ReverseElementVisible -> {
                    val target = action.target as ToggleTarget.Reverse
                    val element = target.element
                    if (element != null && state.activeTab == RootTab.Reverse) {
                        // Visibility is a typed in-place operation even when the requested value
                        // already matches state; avoid generic reload/close handling in either case.
                        typedBackendHandled = true
                        val current = state.reverse.geometry[element] ?: ReverseGeometryUiState()
                        if (current.visible != action.value) {
                            state = state.copy(reverse = state.reverse.copy(
                                geometry = state.reverse.geometry +
                                    (element to current.copy(visible = action.value))))
                            backend.onProductionReverseVisibilityChanged(element, action.value)
                        }
                    }
                }
            }
            is BydExtendUiAction.Run -> applyLocalCommand(action.command)
            else -> Unit
        }
        if (!typedBackendHandled) backend.onProductionUiAction(action)
        if (action is BydExtendUiAction.Run && action.command.reloadsValidatedState() ||
            action is BydExtendUiAction.Toggle &&
                action.target != ToggleTarget.Simple(ToggleId.AutoStart) && !typedBackendHandled ||
            action is BydExtendUiAction.CommitNumber ||
            action is BydExtendUiAction.MoveProfile ||
            action is BydExtendUiAction.Select && !action.target.isLocalSelection()) reload()
    }

    /** Synchronous preview entry point used by slider controls to restore rejected values locally. */
    fun preview(target: NumberTarget, value: String, sessionId: Long? = null): String? {
        syncLegacyRuntimeBlock()
        if (state.legacyRuntimeBlocked) return null
        if (sessionId != null && sessionId <= 0L) return null
        val closedSession = flushedPreviewSessions[target]
        if (sessionId != null && closedSession != null) {
            if (sessionId <= closedSession) return null
            // Keep the watermark so callbacks from any older disposed gesture remain stale
            // while this newer gesture is active; a successful final replaces it below.
        }
        if (sessionId != null && previewSessions[target]?.let { sessionId < it } == true) return null
        if (sessionId != null && previewSessions[target] != null && previewSessions[target] != sessionId) {
            val previousSession = previewSessions[target]
            val previousValue = previewValues.remove(target)
            if (previousSession != null && previousValue != null && sessionId > previousSession) {
                // A new pointer gesture for the same target implicitly finishes an abandoned
                // prior gesture.  Preserve its last accepted value before switching identity.
                flushedPreviewSessions[target] = previousSession
                backend.onProductionUiAction(BydExtendUiAction.CommitNumber(
                    target, previousValue, previousSession))
            }
            previewSessions.remove(target)
        }
        if (sessionId != null) previewSessions[target] = sessionId
        flushedPreviewValues.remove(target)
        if (previewValues[target] == value) return value
        val accepted = backend.onProductionUiPreview(target, value) ?: return null
        previewValues[target] = accepted
        state = applyNumberValue(state, target, accepted)
        return accepted
    }

    /** Re-read validated values while preserving navigation and live runtime feedback. */
    fun reload() {
        syncLegacyRuntimeBlock()
        val old = state
        val fresh = readState()
        state = applyPreviewValues(fresh.copy(
            activeTab = if (fresh.legacyRuntimeBlocked) RootTab.Settings else old.activeTab,
            legacyRuntimeBlocked = fresh.legacyRuntimeBlocked,
            header = fresh.header.copy(adb = old.header.adb, location = old.header.location),
            signals = fresh.signals.copy(
                guard = fresh.signals.guard.copy(operation = old.signals.guard.operation),
                music = fresh.signals.music.copy(operation = old.signals.music.operation),
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
                            ?: value.profile.operation,
                        calibration = value.profile.calibration.copy(
                            rawFallback = old.parking.views[view]?.profile?.calibration?.rawFallback == true),
                    )) },
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
                feedback = if (fresh.legacyRuntimeBlocked) fresh.settings.feedback
                    else old.settings.feedback,
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
        ))
    }

    fun setHeader(header: HeaderUiState) { state = state.copy(header = header) }

    /** Canvas selection updates controls without restarting the running camera. */
    fun setReverseEditorSelection(element: ReverseElement) {
        val previous = state.reverse.selectedElement
        if (previous == element) return
        applyLocalSelection(SelectionTarget.Simple(SelectionId.ReverseElement), element.ordinal)
        // Editor taps arrive through the Android host rather than a Compose Select action. Keep
        // them on the same typed focus seam so Activity can update the existing editor in place
        // without falling through to the legacy close/reopen path.
        if (state.activeTab == RootTab.Reverse) {
            backend.onProductionReverseElementFocusChanged(
                state.reverse.section, previous, element)
        }
    }

    /** Updates the legacy-handover gate and keeps the Activity's selected tab in sync. */
    fun setLegacyRuntimeBlocked(blocked: Boolean) {
        val wasBlocked = state.legacyRuntimeBlocked
        if (!blocked) {
            if (!wasBlocked) return
            val feedback = state.settings.feedback
            val cleared = if (feedback.text == handoverReason(state.language)) {
                StatusUiState()
            } else feedback
            state = state.copy(legacyRuntimeBlocked = false,
                settings = state.settings.copy(feedback = cleared))
            return
        }
        if (wasBlocked && state.activeTab == RootTab.Settings) return

        val previousTab = state.activeTab
        state = state.copy(
            legacyRuntimeBlocked = true,
            activeTab = RootTab.Settings,
            settings = state.settings.copy(
                feedback = StatusUiState(handoverReason(state.language), StatusTone.Warning, true)),
        )
        preferences.edit().putInt("selected_tab", RootTab.Settings.legacyTab()).apply()
        if (previousTab != RootTab.Settings) {
            backend.onProductionUiAction(BydExtendUiAction.Navigate(RootTab.Settings))
        }
    }

    /** Publishes the runtime-derived manual P diagnostics gate and status. */
    fun setManualDiagnostics(allowed: Boolean, status: StatusUiState) {
        state = state.copy(debug = state.debug.copy(
            manualSignalsAllowed = allowed, manualSignalStatus = status))
    }

    fun setGuardStatus(status: StatusUiState) {
        state = state.copy(signals = state.signals.copy(
            guard = state.signals.guard.copy(
                operation = state.signals.guard.operation.copy(status = status))))
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
        val operation = OperationUiState(enabled = !pending, pending = pending, status = status)
        state = state.copy(debug = if (direct) state.debug.copy(
            directOperation = operation)
        else state.debug.copy(
            avmOperation = operation))
    }

    fun setProfileStatus(profile: CameraProfileId, status: StatusUiState, pending: Boolean = false) {
        val operation = OperationUiState(enabled = !pending, pending = pending, status = status)
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

    /** Publishes the transient RAW fallback without changing persisted correction settings. */
    fun setCalibrationRawFallback(profile: CameraProfileId, active: Boolean) {
        fun update(value: CameraProfileUiState) = value.copy(
            calibration = value.calibration.copy(rawFallback = active))
        state = when (profile) {
            is CameraProfileId.Blind -> state.copy(blind = state.blind.copy(
                profiles = state.blind.profiles + (profile to update(
                    state.blind.profiles[profile] ?: CameraProfileUiState()))))
            is CameraProfileId.Parking -> {
                val view = state.parking.views[profile.view] ?: ParkingViewUiState()
                state.copy(parking = state.parking.copy(views = state.parking.views +
                    (profile.view to view.copy(profile = update(view.profile)))))
            }
            is CameraProfileId.Reverse -> state.copy(reverse = state.reverse.copy(
                profiles = state.reverse.profiles + (profile to update(
                    state.reverse.profiles[profile] ?: CameraProfileUiState()))))
        }
    }

    private fun flushPreviewValues() {
        if (previewValues.isEmpty()) return
        val pending = previewValues.toList()
        previewValues.clear()
        pending.forEach { (target, value) ->
            flushedPreviewValues[target] = value
            previewSessions[target]?.let { flushedPreviewSessions[target] = it }
            previewSessions.remove(target)
            backend.onProductionUiAction(BydExtendUiAction.CommitNumber(target, value))
        }
    }

    private fun applyPreviewValues(base: BydExtendUiState): BydExtendUiState =
        previewValues.entries.fold(base) { current, (target, value) ->
            applyNumberValue(current, target, value)
        }

    /** Updates only the in-memory state used by Compose; persistence remains CommitNumber-owned. */
    private fun applyNumberValue(
        source: BydExtendUiState,
        target: NumberTarget,
        value: String,
    ): BydExtendUiState = when (target) {
        is NumberTarget.Guard -> source.copy(signals = source.signals.copy(guard = when (target.field) {
            GuardNumber.OutwardAngle -> source.signals.guard.copy(outwardAngle = value)
            GuardNumber.CentreTolerance -> source.signals.guard.copy(centreTolerance = value)
            GuardNumber.CorrectionDelayMs -> source.signals.guard.copy(correctionDelayMs = value)
            GuardNumber.MaximumSpeed -> source.signals.guard.copy(maximumSpeed = value)
        }))
        NumberTarget.WeatherInterval -> source.copy(signals = source.signals.copy(weather =
            source.signals.weather.copy(refreshMinutes = value)))
        is NumberTarget.Blind -> source.copy(blind = source.blind.copy(
            rules = source.blind.rules + (target.group to (source.blind.rules[target.group]
                ?: BlindRuleUiState()).let { rule ->
                when (target.field) {
                    BlindNumber.MinimumSpeed -> rule.copy(minimumSpeed = value)
                    BlindNumber.MaximumSpeed -> rule.copy(maximumSpeed = value)
                    BlindNumber.SteeringAngle -> rule.copy(steeringAngle = value)
                }
            })))
        is NumberTarget.Parking -> if (target.view == null) source.copy(
            parking = source.parking.copy(maximumSpeed = value)) else {
            val view = target.view ?: return source
            val current = source.parking.views[view] ?: ParkingViewUiState()
            source.copy(parking = source.parking.copy(views = source.parking.views +
                (view to if (target.field == ParkingNumber.TriggerDistance) {
                    current.copy(triggerDistance = value)
                } else current)))
        }
        is NumberTarget.Profile -> updateProfileValue(source, target.profile, target.field, value)
        is NumberTarget.ReverseGeometry -> {
            val current = source.reverse.geometry[target.element] ?: ReverseGeometryUiState()
            source.copy(reverse = source.reverse.copy(geometry = source.reverse.geometry +
                (target.element to when (target.field) {
                    ReverseGeometryNumber.X -> current.copy(x = value)
                    ReverseGeometryNumber.Y -> current.copy(y = value)
                    ReverseGeometryNumber.Width -> current.copy(width = value)
                    ReverseGeometryNumber.Height -> current.copy(height = value)
                })))
        }
        is NumberTarget.Output -> source.copy(settings = source.settings.copy(cameraOutput =
            when (target.field) {
                OutputNumber.CornerRadius -> source.settings.cameraOutput.copy(cornerRadius = value)
                OutputNumber.Transparency -> source.settings.cameraOutput.copy(transparency = value)
            }))
    }

    private fun updateProfileValue(
        source: BydExtendUiState,
        profile: CameraProfileId,
        field: ProfileNumber,
        value: String,
    ): BydExtendUiState {
        fun update(current: CameraProfileUiState): CameraProfileUiState = when (field) {
            ProfileNumber.Size -> current.copy(size = value)
            ProfileNumber.X -> current.copy(x = value)
            ProfileNumber.Y -> current.copy(y = value)
            ProfileNumber.OriginalX -> current.copy(calibration = current.calibration.copy(
                original = current.calibration.original.copy(x = value)))
            ProfileNumber.OriginalY -> current.copy(calibration = current.calibration.copy(
                original = current.calibration.original.copy(y = value)))
            ProfileNumber.OriginalWidth -> current.copy(calibration = current.calibration.copy(
                original = current.calibration.original.copy(width = value)))
            ProfileNumber.OriginalHeight -> current.copy(calibration = current.calibration.copy(
                original = current.calibration.original.copy(height = value)))
            ProfileNumber.Fov -> current.copy(calibration = current.calibration.copy(fov = value))
            ProfileNumber.CorrectedX -> current.copy(calibration = current.calibration.copy(
                corrected = current.calibration.corrected.copy(x = value)))
            ProfileNumber.CorrectedY -> current.copy(calibration = current.calibration.copy(
                corrected = current.calibration.corrected.copy(y = value)))
            ProfileNumber.CorrectedWidth -> current.copy(calibration = current.calibration.copy(
                corrected = current.calibration.corrected.copy(width = value)))
            ProfileNumber.CorrectedHeight -> current.copy(calibration = current.calibration.copy(
                corrected = current.calibration.corrected.copy(height = value)))
            ProfileNumber.Rotation -> current.copy(calibration = current.calibration.copy(rotation = value))
        }
        return when (profile) {
            is CameraProfileId.Blind -> source.copy(blind = source.blind.copy(profiles =
                source.blind.profiles + (profile to update(source.blind.profiles[profile]
                    ?: CameraProfileUiState()))))
            is CameraProfileId.Parking -> {
                val view = profile.view
                val current = source.parking.views[view] ?: ParkingViewUiState()
                source.copy(parking = source.parking.copy(views = source.parking.views +
                    (view to current.copy(profile = update(current.profile)))))
            }
            is CameraProfileId.Reverse -> source.copy(reverse = source.reverse.copy(profiles =
                source.reverse.profiles + (profile to update(source.reverse.profiles[profile]
                    ?: CameraProfileUiState()))))
        }
    }

    private fun readState() = readProductionUiState(
        preferences, backend.automaticStartEnabled(), backend.legacyAccessRestoreVisible(),
        backend::productionDisplayGeometry).let { fresh ->
        if (!backend.runtimeBlockedByLegacy()) fresh
        else fresh.copy(
            activeTab = RootTab.Settings,
            legacyRuntimeBlocked = true,
            settings = fresh.settings.copy(
                feedback = StatusUiState(handoverReason(fresh.language), StatusTone.Warning, true)),
        )
    }

    private fun syncLegacyRuntimeBlock() {
        val blocked = backend.runtimeBlockedByLegacy()
        if (blocked != state.legacyRuntimeBlocked) setLegacyRuntimeBlocked(blocked)
    }

    private fun showLegacyHandoverBlock() {
        if (!state.legacyRuntimeBlocked || state.activeTab != RootTab.Settings) {
            setLegacyRuntimeBlocked(true)
        }
        if (state.legacyRuntimeBlocked) {
            state = state.copy(settings = state.settings.copy(
                feedback = StatusUiState(handoverReason(state.language), StatusTone.Warning, true)))
        }
    }

    private fun allowedWhileLegacyBlocked(action: BydExtendUiAction): Boolean = when (action) {
        is BydExtendUiAction.Navigate -> action.tab == RootTab.Settings
        is BydExtendUiAction.SetLanguage, is BydExtendUiAction.SetTheme -> true
        is BydExtendUiAction.Select -> action.target is SelectionTarget.Simple &&
            action.target.id == SelectionId.SettingsCategory
        is BydExtendUiAction.Toggle -> action.target == ToggleTarget.Simple(ToggleId.AutoStart) ||
            action.target == ToggleTarget.Simple(ToggleId.AutomaticUpdate)
        is BydExtendUiAction.Run -> when (action.command) {
            CommandId.OpenBackgroundSettings,
            CommandId.GrantAdb,
            CommandId.CheckForUpdates,
            CommandId.ShareLogs,
            CommandId.ShareCompatibilityPackage,
            CommandId.ExportCameraPresets,
            CommandId.ImportLegacySettings,
            CommandId.RestoreLegacyAccess,
            CommandId.OpenWeatherAttribution,
            CommandId.Shutdown,
            CommandId.DismissDialog,
            CommandId.CancelOperation -> true
            CommandId.ConfirmDialog -> pendingDialogCommand == null ||
                pendingDialogCommand!!.allowedInLegacyHandover()
            else -> false
        }
        is BydExtendUiAction.PreviewNumber, is BydExtendUiAction.CommitNumber,
        is BydExtendUiAction.MoveProfile -> false
    }

    private fun handoverReason(language: UiLanguage) = if (language == UiLanguage.English) {
        "Runtime controls are locked while settings transfer completes."
    } else "Керування заблоковано до завершення переходу налаштувань."

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

    private fun cameraSection(tab: RootTab): CameraSection? = when (tab) {
        RootTab.Blind -> state.blind.section
        RootTab.Parking -> state.parking.section
        RootTab.Reverse -> state.reverse.section
        else -> null
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
        // Update availability/results already provide the meaningful confirmation surface.
        // Dispatch the check directly and keep the explanatory background dialog below.
        if (command == CommandId.CheckForUpdates) {
            pendingDialogCommand = null
            state = state.copy(dialog = null)
            backend.onProductionUiAction(BydExtendUiAction.Run(command))
            return true
        }
        val english = state.language == UiLanguage.English
        val dialog = when (command) {
            CommandId.OpenBackgroundSettings -> DialogUiState(
                DialogKind.Background,
                if (english) "Background work" else "Робота у фоні",
                if (english) "Check this after every install or update, otherwise DiLink can stop HUD while the app is in the background."
                else "Це потрібно перевірити після кожного встановлення або оновлення, інакше DiLink може зупинити HUD у фоні.",
                cancellable = false,
            )
            else -> null
        }
        if (dialog != null) {
            pendingDialogCommand = command
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
            if (pending != null && (!state.legacyRuntimeBlocked || pending.allowedInLegacyHandover())) {
                backend.onProductionUiAction(BydExtendUiAction.Run(pending))
            } else if (pending != null) {
                showLegacyHandoverBlock()
            }
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
            // Keep the host lambda explicitly composable; otherwise Kotlin may infer a regular
            // lambda and reject the ProductionCameraHost invocation during composition.
            val host: @Composable (CameraHostSlot) -> Unit = { slot ->
                ProductionCameraHost(controller, slot)
            }
            BydExtendApp(
                state = controller.state,
                onAction = controller::dispatch,
                cameraHost = host,
                onPreview = { target, value, sessionId ->
                    controller.preview(target, value, sessionId)
                },
            )
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

private fun CommandId.reloadsValidatedState() = this == CommandId.LoadProfilePreset ||
    this == CommandId.TransferProfilePreset ||
    this == CommandId.ResetProfilePlacement ||
    this == CommandId.ResetProfileOriginal ||
    this == CommandId.ResetProfileCorrection ||
    this == CommandId.ResetProfileOutput ||
    this == CommandId.EnableAllParking ||
    this == CommandId.DisableAllParking

private fun CommandId.allowedInLegacyHandover() = this == CommandId.OpenBackgroundSettings ||
    this == CommandId.GrantAdb ||
    this == CommandId.CheckForUpdates ||
    this == CommandId.Shutdown ||
    this == CommandId.ShareLogs ||
    this == CommandId.ShareCompatibilityPackage ||
    this == CommandId.ExportCameraPresets ||
    this == CommandId.ImportLegacySettings ||
    this == CommandId.RestoreLegacyAccess ||
    this == CommandId.OpenWeatherAttribution

private fun SelectionTarget.isLocalSelection() = this is SelectionTarget.Simple && id in setOf(
    SelectionId.CameraSection, SelectionId.BlindGroup, SelectionId.BlindSide,
    SelectionId.ParkingView, SelectionId.ReverseElement, SelectionId.ReverseSource,
    SelectionId.SettingsCategory, SelectionId.DiagnosticMode, SelectionId.DirectMode,
    SelectionId.AvmMode, SelectionId.AvmOrientation,
)

private fun <K> mergeProfileOperations(
    fresh: Map<K, CameraProfileUiState>, old: Map<K, CameraProfileUiState>,
) = fresh.mapValues { (key, value) -> value.copy(
    operation = old[key]?.operation ?: value.operation,
    calibration = value.calibration.copy(rawFallback = old[key]?.calibration?.rawFallback == true),
) }
