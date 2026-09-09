package com.byd.extend

import android.view.View
import com.byd.extend.ui.BydExtendUiAction
import com.byd.extend.ui.CameraHostSlot
import com.byd.extend.ui.CameraProfileId
import com.byd.extend.ui.CameraGroup
import com.byd.extend.ui.CameraSide
import com.byd.extend.ui.CommandId
import com.byd.extend.ui.DialogKind
import com.byd.extend.ui.ProductionUiBackend
import com.byd.extend.ui.ProductionUiController
import com.byd.extend.ui.RootTab
import com.byd.extend.ui.StatusTone
import com.byd.extend.ui.StatusUiState
import com.byd.extend.ui.ToggleId
import com.byd.extend.ui.ToggleTarget
import com.byd.extend.ui.DiagnosticMode
import com.byd.extend.ui.ParkingView
import com.byd.extend.ui.SelectionId
import com.byd.extend.ui.SelectionTarget
import com.byd.extend.ui.UiSelectionPreferences
import com.byd.extend.ui.CameraDisplayGeometry
import com.byd.extend.ui.CameraSection
import com.byd.extend.ui.CameraProfileUiState
import com.byd.extend.ui.ReverseElement
import com.byd.extend.ui.ReverseSource
import com.byd.extend.ui.GuardNumber
import com.byd.extend.ui.NumberTarget
import com.byd.extend.ui.NumericDraftPolicy
import com.byd.extend.ui.ProfileNumber
import com.byd.extend.ui.SettingsOperation
import com.byd.extend.ui.MirrorBackendAction
import com.byd.extend.ui.MirrorBackendActionKind
import com.byd.extend.ui.steeringButtonLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductionUiControllerTest {
    @Test
    fun staleMirrorPlacementCallbacksCannotWriteNewDisplay() {
        val preferences = TestSharedPreferences()
        val backend = FakeBackend(preferences)
        val controller = ProductionUiController(preferences, backend)
        val tablet = com.byd.extend.ui.DisplayTarget.Tablet
        val cluster = com.byd.extend.ui.DisplayTarget.Cluster
        val staleNumber = NumberTarget.Mirror(com.byd.extend.ui.MirrorNumber.X, tablet)
        preferences.edit().putInt(RearviewMirrorSettings.PREF_TARGET, CameraDisplayTarget.CLUSTER).apply()
        controller.reload()
        val before = controller.state.mirror.placement

        controller.dispatch(BydExtendUiAction.CommitNumber(staleNumber, "50"))
        controller.dispatch(BydExtendUiAction.MoveProfile(CameraProfileId.Mirror, .5f, .5f, tablet))
        controller.dispatch(BydExtendUiAction.SetMirrorGeometry(before.copy(width = "60"), tablet))
        assertEquals(null, controller.preview(staleNumber, "50", 10L))
        assertTrue(backend.mirrorActions.isEmpty())
        assertEquals(before, controller.state.mirror.placement)

        controller.dispatch(BydExtendUiAction.SetMirrorGeometry(before.copy(width = "60"), cluster))
        assertEquals(cluster, backend.mirrorActions.single().target)
    }

    @Test
    fun staleBlindPlacementCallbacksAreRejectedForEveryProfile() {
        for (group in CameraGroup.entries) for (side in CameraSide.entries) {
            val preferences = TestSharedPreferences()
            val backend = FakeBackend(preferences)
            val controller = ProductionUiController(preferences, backend)
            val profile = CameraProfileId.Blind(group, side)
            val native = CameraProfile.of(if (group == CameraGroup.Front) {
                if (side == CameraSide.Left) CameraProfile.FRONT_LEFT else CameraProfile.FRONT_RIGHT
            } else if (side == CameraSide.Left) CameraProfile.REAR_LEFT else CameraProfile.REAR_RIGHT)
            val oldTarget = controller.state.blind.profiles.getValue(profile).target
            val next = if (oldTarget == com.byd.extend.ui.DisplayTarget.Tablet)
                CameraDisplayTarget.CLUSTER else CameraDisplayTarget.TABLET
            preferences.edit().putInt(BlindSpotOverlayController.targetKey(native), next).apply()
            controller.reload()
            val before = controller.state.blind.profiles.getValue(profile)
            controller.dispatch(BydExtendUiAction.MoveProfile(profile, .1f, .1f, oldTarget))
            controller.dispatch(BydExtendUiAction.SetProfileGeometry(profile,
                com.byd.extend.ui.MirrorGeometryUiState("10", "10", "50", "50"), oldTarget))
            controller.dispatch(BydExtendUiAction.CommitNumber(
                NumberTarget.Profile(profile, ProfileNumber.Width, oldTarget), "50"))
            assertTrue(backend.actions.isEmpty())
            assertEquals(before, controller.state.blind.profiles.getValue(profile))
        }
    }

    @Test
    fun panoramaSuppressionDefaultsOnAndRetainsExplicitFalsePerOwner() {
        val preferences = TestSharedPreferences()
        val defaults = readProductionUiState(preferences, false, false)
        assertTrue(defaults.blind.rules.getValue(CameraGroup.Rear).suppressWhilePanorama)
        assertTrue(defaults.blind.rules.getValue(CameraGroup.Front).suppressWhilePanorama)
        assertTrue(defaults.mirror.suppressWhilePanorama)

        preferences.edit()
            .putBoolean(BlindSpotOverlayController.PREF_REAR_SUPPRESS_WHILE_PANORAMA, false)
            .putBoolean(BlindSpotOverlayController.PREF_FRONT_SUPPRESS_WHILE_PANORAMA, false)
            .putBoolean(RearviewMirrorSettings.PREF_SUPPRESS_WHILE_PANORAMA, false)
            .apply()
        val disabled = readProductionUiState(preferences, false, false)
        assertFalse(disabled.blind.rules.getValue(CameraGroup.Rear).suppressWhilePanorama)
        assertFalse(disabled.blind.rules.getValue(CameraGroup.Front).suppressWhilePanorama)
        assertFalse(disabled.mirror.suppressWhilePanorama)
    }

    @Test
    fun blindPanoramaSuppressionActionsStayIndependentPerGroup() {
        val preferences = TestSharedPreferences()
        val backend = FakeBackend(preferences).apply {
            effect = { action ->
                val toggle = action as? BydExtendUiAction.Toggle
                val blind = toggle?.target as? ToggleTarget.Blind
                if (toggle != null && blind?.id == ToggleId.BlindSuppressWhilePanorama) {
                    preferences.edit().putBoolean(
                        if (blind.group == CameraGroup.Rear) {
                            BlindSpotOverlayController.PREF_REAR_SUPPRESS_WHILE_PANORAMA
                        } else BlindSpotOverlayController.PREF_FRONT_SUPPRESS_WHILE_PANORAMA,
                        toggle.value,
                    ).apply()
                }
            }
        }
        val controller = ProductionUiController(preferences, backend)

        controller.dispatch(BydExtendUiAction.Toggle(
            ToggleTarget.Blind(ToggleId.BlindSuppressWhilePanorama, CameraGroup.Rear), false))

        assertFalse(controller.state.blind.rules.getValue(CameraGroup.Rear).suppressWhilePanorama)
        assertTrue(controller.state.blind.rules.getValue(CameraGroup.Front).suppressWhilePanorama)
        assertEquals(CameraGroup.Rear,
            ((backend.actions.single() as BydExtendUiAction.Toggle).target as ToggleTarget.Blind).group)
    }

    @Test
    fun disabledGearSwitchActionPreservesSavedPreferenceAndNeverReachesBackend() {
        val preferences = TestSharedPreferences().apply {
            edit().putBoolean(ReverseCameraController.PREF_SWITCH_BY_GEAR, true).apply()
        }
        val backend = FakeBackend(preferences)
        val controller = ProductionUiController(preferences, backend)
        assertTrue(controller.state.reverse.switchByGear)

        controller.dispatch(BydExtendUiAction.Toggle(
            ToggleTarget.Simple(ToggleId.ReverseSwitchByGear), false))

        assertTrue(controller.state.reverse.switchByGear)
        assertTrue(preferences.getBoolean(ReverseCameraController.PREF_SWITCH_BY_GEAR, false))
        assertTrue(backend.actions.isEmpty())
    }

    @Test
    fun enabledGearSwitchActionUsesExistingBackendPath() {
        val preferences = TestSharedPreferences().apply {
            ReverseCameraController.saveCentralFrontIntegrated(this, true)
        }
        val backend = FakeBackend(preferences).apply {
            effect = { action ->
                if (action == BydExtendUiAction.Toggle(
                        ToggleTarget.Simple(ToggleId.ReverseSwitchByGear), true)) {
                    preferences.edit().putBoolean(ReverseCameraController.PREF_SWITCH_BY_GEAR, true).apply()
                }
            }
        }
        val controller = ProductionUiController(preferences, backend)

        controller.dispatch(BydExtendUiAction.Toggle(
            ToggleTarget.Simple(ToggleId.ReverseSwitchByGear), true))

        assertTrue(controller.state.reverse.switchByGear)
        assertEquals(1, backend.actions.size)
    }

    @Test
    fun mirrorPanoramaSuppressionUsesDedicatedTypedAction() {
        val preferences = TestSharedPreferences()
        val backend = FakeBackend(preferences)
        val controller = ProductionUiController(preferences, backend)

        controller.dispatch(BydExtendUiAction.Toggle(
            ToggleTarget.Simple(ToggleId.MirrorSuppressWhilePanorama), false))

        assertFalse(controller.state.mirror.suppressWhilePanorama)
        assertEquals(MirrorBackendActionKind.SetSuppressWhilePanorama,
            backend.mirrorActions.single().kind)
        assertFalse(backend.mirrorActions.single().enabled ?: true)
        assertTrue(backend.actions.isEmpty())
    }

    @Test
    fun exportFeedbackTracksOwnedProgressAndEveryTerminalOutcomeAcrossReload() {
        for (operation in listOf(SettingsOperation.Logs, SettingsOperation.Compatibility)) {
            for (terminal in listOf(
                StatusUiState("Ready", StatusTone.Ok, true),
                StatusUiState("Canceled", StatusTone.Warning, true),
                StatusUiState("No archive", StatusTone.Warning, true),
                StatusUiState("Export failed", StatusTone.Error, true),
                StatusUiState("Share failed", StatusTone.Error, true),
                StatusUiState("Activity inactive", StatusTone.Warning, true),
            )) {
                val preferences = TestSharedPreferences()
                val controller = ProductionUiController(preferences, FakeBackend(preferences))
                val profile = CameraProfileId.Blind(CameraGroup.Rear, CameraSide.Left)
                val cameraStatus = StatusUiState("Opening", StatusTone.Warning, true)
                controller.setProfileStatus(profile, cameraStatus, pending = true)
                val header = controller.state.header
                controller.setSettingsOperation(operation,
                    StatusUiState("Preparing", StatusTone.Warning, true), true, claimFeedback = true)
                controller.reload()
                assertEquals(operation, controller.state.settings.feedbackOperation)
                val progress = StatusUiState("Packing", StatusTone.Warning, true)
                controller.setSettingsOperation(operation, progress, true)
                assertEquals(progress, controller.state.settings.feedback)
                controller.setSettingsOperation(operation, terminal, false)
                assertEquals(terminal, controller.state.settings.feedback)
                val actual = if (operation == SettingsOperation.Logs) controller.state.settings.logOperation
                    else controller.state.settings.compatibilityOperation
                assertFalse(actual.pending)
                assertTrue(actual.enabled)
                assertEquals(terminal, actual.status)
                assertEquals(cameraStatus, controller.state.blind.profiles.getValue(profile).operation.status)
                assertEquals(header, controller.state.header)
            }
        }
    }

    @Test
    fun exportCompletionCannotOverwriteNewerFeedbackOrAnotherOwner() {
        val preferences = TestSharedPreferences()
        val controller = ProductionUiController(preferences, FakeBackend(preferences))
        val preparing = StatusUiState("Preparing", StatusTone.Warning, true)
        val done = StatusUiState("Ready", StatusTone.Ok, true)
        val other = StatusUiState("New settings feedback", StatusTone.Warning, true)
        controller.setSettingsOperation(SettingsOperation.Logs, preparing, true, claimFeedback = true)
        controller.setSettingsFeedback(other)
        controller.setSettingsOperation(SettingsOperation.Logs, done, false)
        assertEquals(other, controller.state.settings.feedback)
        assertEquals(null, controller.state.settings.feedbackOperation)
        assertEquals(done, controller.state.settings.logOperation.status)

        controller.setSettingsOperation(SettingsOperation.Compatibility, preparing, true, claimFeedback = true)
        controller.setSettingsOperation(SettingsOperation.Logs, done, false)
        assertEquals(preparing, controller.state.settings.feedback)
        assertEquals(SettingsOperation.Compatibility, controller.state.settings.feedbackOperation)
        controller.setLegacyRuntimeBlocked(true)
        val blocked = controller.state.settings.feedback
        controller.setSettingsOperation(SettingsOperation.Compatibility, done, false)
        assertEquals(blocked, controller.state.settings.feedback)
        assertEquals(null, controller.state.settings.feedbackOperation)
    }

    @Test
    fun reverseLayoutResetCarriesExactlyTheSelectedElementToBackend() {
        val preferences = TestSharedPreferences()
        val backend = FakeBackend(preferences)
        val controller = ProductionUiController(preferences, backend)
        for (element in ReverseElement.entries) {
            val action = BydExtendUiAction.Run(CommandId.ReverseResetLayout, reverseElement = element)
            controller.dispatch(action)
            assertEquals(action, backend.actions.last())
            assertEquals(element, (backend.actions.last() as BydExtendUiAction.Run).reverseElement)
        }
    }

    @Test
    fun reverseButtonBindingLoadsFromPreferencesAndCaptureDialogHasExplicitLifecycle() {
        val preferences = TestSharedPreferences().apply {
            edit().putInt(ReverseSteeringButtonPreferences.KEY_CODE, 310).apply()
        }
        val backend = FakeBackend(preferences)
        val controller = ProductionUiController(preferences, backend)

        assertEquals(310, controller.state.reverse.steeringKeyCode)
        controller.showReverseButtonCaptureDialog()
        assertEquals(DialogKind.ReverseButtonCapture, controller.state.dialog?.kind)
        assertEquals("Натисніть кнопку на кермі…", controller.state.dialog?.title)
        assertFalse(controller.state.dialog?.confirmEnabled ?: true)

        controller.dispatch(BydExtendUiAction.Run(CommandId.DismissDialog))
        assertEquals(null, controller.state.dialog)
        assertEquals(310, controller.state.reverse.steeringKeyCode)
        assertEquals(BydExtendUiAction.Run(CommandId.DismissDialog), backend.actions.last())
    }

    @Test
    fun reverseButtonCommandsRemainTypedBackendActions() {
        val preferences = TestSharedPreferences()
        val backend = FakeBackend(preferences)
        val controller = ProductionUiController(preferences, backend)

        controller.dispatch(BydExtendUiAction.Run(CommandId.ReverseLearnButton))
        controller.dispatch(BydExtendUiAction.Run(CommandId.ReverseResetButton))

        assertEquals(
            listOf(
                BydExtendUiAction.Run(CommandId.ReverseLearnButton),
                BydExtendUiAction.Run(CommandId.ReverseResetButton),
            ),
            backend.actions.takeLast(2),
        )
    }

    @Test
    fun steeringButtonLabelsKeepKnownNamesAndUnknownNumericFallback() {
        val ukrainian = com.byd.extend.ui.UiStrings(com.byd.extend.ui.UiLanguage.Ukrainian)
        val english = com.byd.extend.ui.UiStrings(com.byd.extend.ui.UiLanguage.English)
        val chinese = com.byd.extend.ui.UiStrings(com.byd.extend.ui.UiLanguage.Chinese)
        assertEquals("Круговий огляд (310)", steeringButtonLabel(310, ukrainian))
        assertEquals("Button (code 999)", steeringButtonLabel(999, english))
        assertEquals("全景影像 (310)", steeringButtonLabel(310, chinese))
        assertEquals("按键（代码 999）", steeringButtonLabel(999, chinese))
        assertEquals("", steeringButtonLabel(-1, ukrainian))
    }

    @Test
    fun reverseCaptureDialogUsesLocalizedProductionPromptWithoutPreviewOnlyCopy() {
        val preferences = TestSharedPreferences().apply {
            edit().putString("ui_language", "en").apply()
        }
        val controller = ProductionUiController(preferences, FakeBackend(preferences))

        controller.showReverseButtonCaptureDialog()

        assertEquals("Press a steering-wheel button…", controller.state.dialog?.title)
        assertTrue(controller.state.dialog?.message?.contains("Front views require enabled integration.") == true)
        assertFalse(controller.state.dialog?.message?.contains("Preview") == true)
    }

    @Test
    fun blockedRuntimeActionIsRejectedAndForcesSettings() {
        val preferences = TestSharedPreferences().apply {
            edit().putInt("selected_tab", 1).apply()
        }
        val backend = FakeBackend(preferences)
        val controller = ProductionUiController(preferences, backend)
        assertEquals(RootTab.Blind, controller.state.activeTab)

        backend.blocked = true
        controller.dispatch(BydExtendUiAction.Toggle(
            ToggleTarget.Simple(ToggleId.Guard), true))

        assertTrue(controller.state.legacyRuntimeBlocked)
        assertEquals(RootTab.Settings, controller.state.activeTab)
        assertEquals(7, preferences.getInt("selected_tab", -1))
        assertTrue(controller.state.settings.feedback.visible)
        assertTrue(backend.actions.any {
            it == BydExtendUiAction.Navigate(RootTab.Settings)
        })
        assertFalse(backend.actions.any { it is BydExtendUiAction.Toggle })
    }

    @Test
    fun handoverDispatchesUpdateDirectlyButRejectsPresetLoad() {
        val preferences = TestSharedPreferences()
        val backend = FakeBackend(preferences).also { it.blocked = true }
        val controller = ProductionUiController(preferences, backend)

        controller.dispatch(BydExtendUiAction.Run(CommandId.CheckForUpdates))
        assertEquals(null, controller.state.dialog)
        assertTrue(backend.actions.any {
            it == BydExtendUiAction.Run(CommandId.CheckForUpdates)
        })

        val before = backend.actions.size
        controller.dispatch(BydExtendUiAction.Run(
            CommandId.LoadProfilePreset,
            CameraProfileId.Blind(CameraGroup.Rear, CameraSide.Left)))
        assertEquals(before, backend.actions.size)
        assertTrue(controller.state.settings.feedback.visible)
    }

    @Test
    fun backgroundDialogUsesTheAcceptedPreviewCopyInBothLanguages() {
        val ukrainianPreferences = TestSharedPreferences().apply {
            // A pre-existing install with no language key migrates to Ukrainian.
            edit().putBoolean("guard_enabled", false).apply()
        }
        val ukrainian = ProductionUiController(
            ukrainianPreferences, FakeBackend(ukrainianPreferences))
        ukrainian.dispatch(BydExtendUiAction.Run(CommandId.OpenBackgroundSettings))
        assertEquals("Робота у фоні", ukrainian.state.dialog?.title)
        assertEquals(
            "Це потрібно перевірити після кожного встановлення або оновлення, інакше DiLink може зупинити BYD Extend у фоні.",
            ukrainian.state.dialog?.message)

        val englishPreferences = TestSharedPreferences().apply {
            edit().putString("ui_language", "en").apply()
        }
        val english = ProductionUiController(
            englishPreferences, FakeBackend(englishPreferences))
        english.dispatch(BydExtendUiAction.Run(CommandId.OpenBackgroundSettings))
        assertEquals("Background work", english.state.dialog?.title)
        assertEquals(
            "Check this after every install or update, otherwise DiLink can stop BYD Extend while the app is in the background.",
            english.state.dialog?.message)
    }

    @Test
    fun reloadMutatorReadsValidatedPreferencesAndPreservesLiveState() {
        val preferences = TestSharedPreferences().apply {
            edit().putInt("selected_tab", 7).putFloat("camera_left_x", 0.2f).apply()
        }
        val backend = FakeBackend(preferences)
        val controller = ProductionUiController(preferences, backend)
        val profile = CameraProfileId.Blind(CameraGroup.Rear, CameraSide.Left)
        val live = StatusUiState("camera live", StatusTone.Ok, true)
        controller.setProfileStatus(profile, live)

        controller.dispatch(BydExtendUiAction.Run(CommandId.LoadProfilePreset, profile))

        assertEquals(RootTab.Settings, controller.state.activeTab)
        assertEquals(55.52f, controller.state.blind.profiles[profile]?.x?.toFloat() ?: 0f, .01f)
        assertEquals(live, controller.state.blind.profiles[profile]?.operation?.status)
    }

    @Test
    fun manualDiagnosticsSetterPublishesAllowedAndStatus() {
        val preferences = TestSharedPreferences()
        val controller = ProductionUiController(preferences, FakeBackend(preferences))
        val status = StatusUiState("P ready", StatusTone.Ok, true)

        controller.setManualDiagnostics(true, status)

        assertTrue(controller.state.debug.manualSignalsAllowed)
        assertEquals(status, controller.state.debug.manualSignalStatus)
    }

    @Test
    fun diagnosticTerminalStatusReenablesSelectionAndProfileStatusIsVisible() {
        val preferences = TestSharedPreferences()
        val controller = ProductionUiController(preferences, FakeBackend(preferences))
        val opening = StatusUiState("Opening", StatusTone.Warning, true)
        controller.setDiagnosticStatus(false, opening, pending = true)
        assertTrue(controller.state.debug.avmOperation.pending)
        assertFalse(controller.state.debug.avmOperation.enabled)

        val closed = StatusUiState("Camera closed", StatusTone.Warning, true)
        controller.setDiagnosticStatus(false, closed, pending = false)
        assertFalse(controller.state.debug.avmOperation.pending)
        assertTrue(controller.state.debug.avmOperation.enabled)

        val profile = CameraProfileId.Parking(ParkingView.FrontLeft)
        controller.setProfileStatus(profile, opening, pending = true)
        assertTrue(controller.state.parking.views.getValue(profile.view).profile.operation.pending)
        assertFalse(controller.state.parking.views.getValue(profile.view).profile.operation.enabled)
    }

    @Test
    fun reversePanoramaStatusIsIndependentFromDirectFramesAndSurvivesReload() {
        val preferences = TestSharedPreferences()
        val controller = ProductionUiController(preferences, FakeBackend(preferences))
        val directProfile = CameraProfileId.Reverse(ReverseElement.Rear, ReverseSource.Rear)
        val opening = StatusUiState("Opening panorama…", StatusTone.Warning, true)
        val directReady = StatusUiState("First frame ready", StatusTone.Ok, true)
        val failed = StatusUiState("Stock camera background unavailable", StatusTone.Error, true)

        controller.setReversePanoramaStatus(opening, pending = true)
        controller.setProfileStatus(directProfile, directReady)
        controller.reload()

        assertTrue(controller.state.reverse.panoramaOperation.pending)
        assertEquals(opening, controller.state.reverse.panoramaOperation.status)
        assertEquals(directReady,
            controller.state.reverse.profiles.getValue(directProfile).operation.status)

        controller.setReversePanoramaStatus(failed)
        assertFalse(controller.state.reverse.panoramaOperation.pending)
        assertEquals(failed, controller.state.reverse.panoramaOperation.status)
        assertEquals(directReady,
            controller.state.reverse.profiles.getValue(directProfile).operation.status)

        controller.setReversePanoramaStatus(StatusUiState())
        assertFalse(controller.state.reverse.panoramaOperation.status.visible)
        assertEquals(directReady,
            controller.state.reverse.profiles.getValue(directProfile).operation.status)
    }

    @Test
    fun everyProfileRunMutatorReloadsBlindAndParkingValues() {
        val commands = listOf(CommandId.LoadProfilePreset, CommandId.TransferProfilePreset,
            CommandId.ResetProfilePlacement, CommandId.ResetProfileOriginal,
            CommandId.ResetProfileCorrection, CommandId.ResetProfileOutput)
        val blind = CameraProfileId.Blind(CameraGroup.Rear, CameraSide.Left)
        val parking = CameraProfileId.Parking(ParkingView.FrontLeft)
        for (command in commands) for (profile in listOf(blind, parking)) {
            val preferences = TestSharedPreferences()
            val backend = FakeBackend(preferences)
            val controller = ProductionUiController(preferences, backend)
            val live = StatusUiState("live", StatusTone.Ok, true)
            controller.setProfileStatus(profile, live)
            backend.effect = {
                preferences.edit().putFloat("camera_left_x", .8f)
                    .putFloat("parking_camera_fl_x", .8f).apply()
            }
            controller.dispatch(BydExtendUiAction.Run(command, profile))
            val actual = if (profile == blind) controller.state.blind.profiles[blind]
                else controller.state.parking.views[ParkingView.FrontLeft]?.profile
            if (profile == blind) {
                assertEquals("$command $profile", 55.52f, actual?.x?.toFloat() ?: 0f, .01f)
            } else {
                assertEquals("$command $profile", "80", actual?.x)
            }
            assertEquals(live, actual?.operation?.status)
        }
    }

    @Test
    fun parkingBulkMutatorsReloadEveryView() {
        val preferences = TestSharedPreferences()
        val backend = FakeBackend(preferences)
        val controller = ProductionUiController(preferences, backend)
        for (enabled in listOf(true, false)) {
            backend.effect = { ParkingCameraSettings(preferences).setAllEnabled(enabled) }
            controller.dispatch(BydExtendUiAction.Run(if (enabled) CommandId.EnableAllParking
                else CommandId.DisableAllParking))
            assertTrue(controller.state.parking.views.values.all { it.enabled == enabled })
        }
    }

    @Test
    fun reloadAdoptsLegacyBlockAndShutdownRemainsConfirmable() {
        val preferences = TestSharedPreferences().apply { edit().putInt("selected_tab", 1).apply() }
        val backend = FakeBackend(preferences)
        val controller = ProductionUiController(preferences, backend)
        controller.dispatch(BydExtendUiAction.Run(CommandId.Shutdown))
        backend.blocked = true
        controller.reload()
        assertEquals(RootTab.Settings, controller.state.activeTab)
        assertTrue(backend.actions.contains(BydExtendUiAction.Navigate(RootTab.Settings)))
        controller.dispatch(BydExtendUiAction.Run(CommandId.ConfirmDialog))
        assertTrue(backend.actions.contains(BydExtendUiAction.Run(CommandId.Shutdown)))
    }

    @Test
    fun diagnosticSelectionAndCloseUseTheSharedStatePath() {
        val preferences = TestSharedPreferences()
        val controller = ProductionUiController(preferences, FakeBackend(preferences))
        controller.dispatch(BydExtendUiAction.Navigate(RootTab.Debug))
        controller.dispatch(BydExtendUiAction.Select(
            SelectionTarget.Simple(SelectionId.DiagnosticMode), DiagnosticMode.Avm.ordinal))
        controller.dispatch(BydExtendUiAction.Select(SelectionTarget.Simple(SelectionId.AvmMode), 7))
        assertEquals(DiagnosticMode.Avm, controller.state.debug.mode)
        assertEquals(7, controller.state.debug.avmSelection)
        controller.dispatch(BydExtendUiAction.Run(CommandId.StopDiagnosticCamera))
        assertEquals(null, controller.state.debug.avmSelection)
        assertEquals(null, controller.state.debug.directSelection)
    }

    @Test
    fun reverseCanvasSelectionPersistsWithoutDispatchingCameraEffects() {
        val preferences = TestSharedPreferences()
        val backend = FakeBackend(preferences)
        val controller = ProductionUiController(preferences, backend)
        controller.setReverseEditorSelection(ReverseElement.Widget)
        controller.setReverseEditorSelection(ReverseElement.Widget)
        controller.reload()
        assertEquals(ReverseElement.Widget, controller.state.reverse.selectedElement)
        assertEquals(ReverseElement.Widget.ordinal,
            preferences.getInt(UiSelectionPreferences.REVERSE_ELEMENT, -1))
        assertTrue(backend.actions.isEmpty())
        assertEquals(ReverseElement.Widget,
            ProductionUiController(preferences, backend).state.reverse.selectedElement)
    }

    @Test
    fun reverseSectionSelectionUsesTypedHostTransitionWithoutGenericAction() {
        val preferences = TestSharedPreferences()
        val backend = FakeBackend(preferences)
        val controller = ProductionUiController(preferences, backend)

        controller.dispatch(BydExtendUiAction.Navigate(RootTab.Reverse))
        backend.actions.clear()
        controller.dispatch(BydExtendUiAction.Select(
            SelectionTarget.Simple(SelectionId.CameraSection), CameraSection.Placement.ordinal))

        assertEquals(1, backend.sectionChanges.size)
        assertEquals(CameraSection.Parameters, backend.sectionChanges.single().first)
        assertEquals(CameraSection.Placement, backend.sectionChanges.single().second)
        assertTrue(backend.actions.isEmpty())
    }

    @Test
    fun reverseVisibilityUsesTypedUpdateWithoutGenericReloadAction() {
        val preferences = TestSharedPreferences()
        val backend = FakeBackend(preferences)
        val controller = ProductionUiController(preferences, backend)

        controller.dispatch(BydExtendUiAction.Navigate(RootTab.Reverse))
        controller.dispatch(BydExtendUiAction.Toggle(
            ToggleTarget.Reverse(ToggleId.ReverseElementVisible, ReverseElement.Rear), false))

        assertEquals(listOf(ReverseElement.Rear to false), backend.visibilityChanges)
        assertFalse(controller.state.reverse.geometry.getValue(ReverseElement.Rear).visible)
        assertTrue(backend.actions.none { it is BydExtendUiAction.Toggle })
    }

    @Test
    fun reverseElementFocusUsesTypedHostCallbackWithoutGenericAction() {
        val preferences = TestSharedPreferences()
        val backend = FakeBackend(preferences)
        val controller = ProductionUiController(preferences, backend)

        controller.dispatch(BydExtendUiAction.Navigate(RootTab.Reverse))
        backend.actions.clear()
        controller.dispatch(BydExtendUiAction.Select(
            SelectionTarget.Simple(SelectionId.ReverseElement), ReverseElement.Rear.ordinal))

        assertEquals(1, backend.focusChanges.size)
        assertEquals(ReverseElement.RearLeft, backend.focusChanges.single().first)
        assertEquals(ReverseElement.Rear, backend.focusChanges.single().second)
        assertTrue(backend.actions.isEmpty())
    }

    @Test
    fun reverseEditorFocusUsesSameTypedHostSeamWithoutGenericAction() {
        val preferences = TestSharedPreferences()
        val backend = FakeBackend(preferences)
        val controller = ProductionUiController(preferences, backend)

        controller.dispatch(BydExtendUiAction.Navigate(RootTab.Reverse))
        backend.actions.clear()
        controller.setReverseEditorSelection(ReverseElement.Rear)

        assertEquals(listOf(ReverseElement.RearLeft to ReverseElement.Rear), backend.focusChanges)
        assertTrue(backend.actions.isEmpty())
    }

    @Test
    fun rawFallbackSurvivesReloadWithoutChangingSettingsOrOtherProfiles() {
        val preferences = TestSharedPreferences()
        val controller = ProductionUiController(preferences, FakeBackend(preferences))
        val profiles = listOf(
            CameraProfileId.Blind(CameraGroup.Rear, CameraSide.Left),
            CameraProfileId.Parking(ParkingView.FrontLeft),
            CameraProfileId.Reverse(ReverseElement.RearLeft, ReverseSource.Rear),
            CameraProfileId.Reverse(ReverseElement.RearLeft, ReverseSource.Front),
            CameraProfileId.Mirror,
        )
        fun profileState(profile: CameraProfileId): CameraProfileUiState = when (profile) {
            is CameraProfileId.Blind -> controller.state.blind.profiles.getValue(profile)
            is CameraProfileId.Parking -> controller.state.parking.views.getValue(profile.view).profile
            is CameraProfileId.Reverse -> controller.state.reverse.profiles.getValue(profile)
            CameraProfileId.Mirror -> controller.state.mirror.profile
        }
        val stored = HashMap(preferences.all)
        val correctionSettings = profiles.associateWith { profileState(it).calibration.correctionEnabled }
        for (active in profiles) {
            controller.setCalibrationRawFallback(active, true)
            controller.reload()
            for (profile in profiles) {
                assertEquals(profile.toString(), profile == active, profileState(profile).calibration.rawFallback)
                assertEquals(correctionSettings[profile], profileState(profile).calibration.correctionEnabled)
            }
            controller.setCalibrationRawFallback(active, false)
            controller.reload()
            assertFalse(profileState(active).calibration.rawFallback)
        }
        assertEquals(stored, preferences.all)
    }

    @Test
    fun reversePreviewUsesDestinationPixelsOnTheActualTablet() {
        val preferences = TestSharedPreferences()
        val tablet = CameraDisplayGeometry(2304, 1440)
        val state = readProductionUiState(preferences, false, false) { tablet }
        val layout = ReverseCameraController.loadRawLayout(preferences)
        val pane = layout.pane(ReverseCameraLayout.REAR_LEFT_CAMERA_INDEX)
        val projected = ReverseCameraLayout.project(pane.destination, tablet.width, tablet.height)
        val profile = state.reverse.profiles.getValue(
            CameraProfileId.Reverse(ReverseElement.RearLeft, ReverseSource.Rear))
        assertEquals(tablet, state.reverse.displayGeometry)
        assertEquals(tablet, profile.displayGeometry)
        assertEquals(projected.width.toFloat() / projected.height, profile.frameAspect, 0f)
    }

    @Test
    fun numericSubmissionKeepsCanonicalDraftOnBackendRejectionAndReloadsAcceptedValue() {
        val preferences = TestSharedPreferences()
        val backend = FakeBackend(preferences)
        val controller = ProductionUiController(preferences, backend)
        val target = NumberTarget.Guard(GuardNumber.OutwardAngle)
        val draft = NumericDraftPolicy.resolve("5", controller.state.signals.guard.outwardAngle, 0f..780f)
        assertTrue(draft.valid)
        controller.dispatch(BydExtendUiAction.CommitNumber(target, "5"))
        assertEquals("90", controller.state.signals.guard.outwardAngle)
        assertEquals("90", draft.draft)
        assertEquals(90f, draft.slider, 0f)

        backend.effect = { preferences.edit().putFloat("outward_deg", 91f).apply() }
        controller.dispatch(BydExtendUiAction.CommitNumber(target, "91.00"))
        assertEquals("91", controller.state.signals.guard.outwardAngle)
        val reloaded = NumericDraftPolicy.resolve("91.00", controller.state.signals.guard.outwardAngle, 0f..780f)
        assertEquals("91", reloaded.draft)
        assertEquals(91f, reloaded.slider, 0f)
    }

    @Test
    fun previewTicksUpdateUiWithoutDurableActionsAndFinishCommitsOnce() {
        val preferences = TestSharedPreferences().apply {
            edit().putInt(BlindSpotOverlayController.PREF_LEFT_SCALE, 24).apply()
        }
        val backend = FakeBackend(preferences).also {
            it.previewResolver = { _, value -> value.toFloatOrNull()?.let { parsed ->
                kotlin.math.round(parsed).toInt().toString()
            } }
        }
        val controller = ProductionUiController(preferences, backend)
        val profile = CameraProfileId.Blind(CameraGroup.Rear, CameraSide.Left)
        val target = NumberTarget.Profile(profile, ProfileNumber.Size)
        val original = controller.state.blind.profiles.getValue(profile).size

        assertEquals("30", controller.preview(target, "30.4", 10L))
        assertEquals("31", controller.preview(target, "31.0", 10L))
        // Normalized duplicate ticks are deduped by the UI; a repeated controller call is also
        // harmless and must not become a durable action.
        assertEquals("31", controller.preview(target, "31", 10L))
        assertEquals("31", controller.state.blind.profiles.getValue(profile).size)
        assertEquals(0, backend.actions.filterIsInstance<BydExtendUiAction.CommitNumber>().size)
        assertEquals(original.toInt(), preferences.getInt(BlindSpotOverlayController.PREF_LEFT_SCALE, -1))

        controller.dispatch(BydExtendUiAction.CommitNumber(target, "31", 10L))
        assertEquals(1, backend.actions.filterIsInstance<BydExtendUiAction.CommitNumber>().size)
    }

    @Test
    fun consecutiveGesturesCommitIndependentlyAndStaleFinishIsIgnored() {
        val preferences = TestSharedPreferences()
        val backend = FakeBackend(preferences).also { it.previewResolver = { _, value -> value } }
        val controller = ProductionUiController(preferences, backend)
        val profile = CameraProfileId.Blind(CameraGroup.Rear, CameraSide.Left)
        val target = NumberTarget.Profile(profile, ProfileNumber.Size)

        controller.preview(target, "30", 100L)
        controller.preview(target, "31", 100L)
        controller.dispatch(BydExtendUiAction.CommitNumber(target, "31", 100L))
        controller.preview(target, "32", 101L)
        controller.preview(target, "33", 101L)
        // A disposed first-slider callback arriving while the second gesture is active cannot
        // erase or finalize the second gesture's accepted value.
        controller.dispatch(BydExtendUiAction.CommitNumber(target, "31", 100L))
        assertEquals(1, backend.actions.filterIsInstance<BydExtendUiAction.CommitNumber>().size)
        controller.dispatch(BydExtendUiAction.Navigate(RootTab.Settings))
        // Navigation flushes the second gesture exactly once; its late finish remains stale.
        controller.dispatch(BydExtendUiAction.CommitNumber(target, "33", 101L))
        assertEquals(null, controller.preview(target, "30", 100L))

        assertEquals(2, backend.actions.filterIsInstance<BydExtendUiAction.CommitNumber>().size)
    }

    @Test
    fun navigationFlushesLatestPreviewForOriginalTargetAndSuppressesLateFinish() {
        val preferences = TestSharedPreferences()
        val backend = FakeBackend(preferences).also { it.previewResolver = { _, value -> value } }
        val controller = ProductionUiController(preferences, backend)
        val profile = CameraProfileId.Blind(CameraGroup.Rear, CameraSide.Left)
        val target = NumberTarget.Profile(profile, ProfileNumber.Size)

        controller.preview(target, "34", 200L)
        controller.preview(target, "35", 200L)
        controller.dispatch(BydExtendUiAction.Navigate(RootTab.Settings))
        // The old composable may still report onValueChangeFinished after navigation.
        controller.dispatch(BydExtendUiAction.CommitNumber(target, "35", 200L))

        val commits = backend.actions.filterIsInstance<BydExtendUiAction.CommitNumber>()
        assertEquals(1, commits.size)
        assertEquals(target, commits.single().target)
        assertEquals("35", commits.single().value)
        assertEquals(RootTab.Settings, controller.state.activeTab)
    }

    @Test
    fun previewRejectionLeavesCanonicalAndAcceptedNormalizationReconciles() {
        val preferences = TestSharedPreferences()
        val backend = FakeBackend(preferences).also {
            it.previewResolver = { _, value ->
                if (value == "99") null else value.toFloatOrNull()?.let { parsed ->
                    kotlin.math.round(parsed).toInt().toString()
                }
            }
        }
        val controller = ProductionUiController(preferences, backend)
        val profile = CameraProfileId.Blind(CameraGroup.Rear, CameraSide.Left)
        val target = NumberTarget.Profile(profile, ProfileNumber.Size)
        val canonical = controller.state.blind.profiles.getValue(profile).size

        assertEquals(null, controller.preview(target, "99", 300L))
        assertEquals(canonical, controller.state.blind.profiles.getValue(profile).size)
        assertEquals("37", controller.preview(target, "36.6", 300L))
        assertEquals("37", controller.state.blind.profiles.getValue(profile).size)
    }

    private class FakeBackend(private val preferences: TestSharedPreferences) : ProductionUiBackend {
        var blocked = false
        val actions = mutableListOf<BydExtendUiAction>()
        var effect: (BydExtendUiAction) -> Unit = {}
        var previewResolver: (NumberTarget, String) -> String? = { _, _ -> null }
        val previews = mutableListOf<Pair<NumberTarget, String>>()
        val sectionChanges = mutableListOf<Pair<CameraSection, CameraSection>>()
        val focusChanges = mutableListOf<Pair<ReverseElement, ReverseElement>>()
        val visibilityChanges = mutableListOf<Pair<ReverseElement, Boolean>>()
        val mirrorActions = mutableListOf<MirrorBackendAction>()

        override fun onProductionMirrorAction(action: MirrorBackendAction) {
            mirrorActions += action
        }

        override fun onProductionCameraSectionChanged(
            tab: RootTab, previous: CameraSection, next: CameraSection,
        ) {
            sectionChanges += previous to next
        }

        override fun onProductionReverseVisibilityChanged(
            element: ReverseElement, visible: Boolean,
        ) {
            visibilityChanges += element to visible
        }

        override fun onProductionReverseElementFocusChanged(
            section: CameraSection, previous: ReverseElement, next: ReverseElement,
        ) {
            focusChanges += previous to next
        }

        override fun onProductionUiPreview(target: NumberTarget, value: String): String? {
            previews += target to value
            return previewResolver(target, value)
        }

        override fun onProductionUiAction(action: BydExtendUiAction) {
            actions += action
            if (action is BydExtendUiAction.Run && action.command == CommandId.LoadProfilePreset) {
                preferences.edit().putFloat("camera_left_x", 0.8f).apply()
            }
            effect(action)
        }

        override fun obtainProductionCameraHost(slot: CameraHostSlot): View =
            throw UnsupportedOperationException()

        override fun updateProductionCameraHost(view: View, slot: CameraHostSlot) = Unit

        override fun releaseProductionCameraHost(view: View, slot: CameraHostSlot) = Unit

        override fun automaticStartEnabled() = false

        override fun legacyAccessRestoreVisible() = false

        override fun runtimeBlockedByLegacy() = blocked
    }
}
