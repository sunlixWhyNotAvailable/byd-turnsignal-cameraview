package com.byd.extend

import android.view.View
import com.byd.extend.ui.BydExtendUiAction
import com.byd.extend.ui.CameraHostSlot
import com.byd.extend.ui.CameraProfileId
import com.byd.extend.ui.CameraGroup
import com.byd.extend.ui.CameraSide
import com.byd.extend.ui.CommandId
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductionUiControllerTest {
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
    fun handoverAllowsUpdateConfirmationButRejectsPresetLoad() {
        val preferences = TestSharedPreferences()
        val backend = FakeBackend(preferences).also { it.blocked = true }
        val controller = ProductionUiController(preferences, backend)

        controller.dispatch(BydExtendUiAction.Run(CommandId.CheckForUpdates))
        assertTrue(controller.state.dialog != null)
        controller.dispatch(BydExtendUiAction.Run(CommandId.ConfirmDialog))
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
        assertEquals("80", controller.state.blind.profiles[profile]?.x)
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
            assertEquals("$command $profile", "80", actual?.x)
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

    private class FakeBackend(private val preferences: TestSharedPreferences) : ProductionUiBackend {
        var blocked = false
        val actions = mutableListOf<BydExtendUiAction>()
        var effect: (BydExtendUiAction) -> Unit = {}

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
