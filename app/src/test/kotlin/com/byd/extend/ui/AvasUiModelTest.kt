package com.byd.extend.ui

import com.byd.extend.CameraButtonBindings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AvasUiModelTest {
    private val asset = AvasAssetUiState("asset-uuid", "sound.mp3", ready = true)

    @Test
    fun fixedProfilesAndDefaultsMatchProductionContract() {
        assertEquals(listOf("lock", "unlock", "power_off", "power_on"), AvasProfileIds.ALL)
        assertTrue(AvasUiState().profiles.all { it.volume == 15 })
        assertTrue(AvasUiState().profiles.all { it.skipConcurrentLockUnlock })
        assertEquals(AvasAuditionUiState(), AvasUiState().audition)
    }

    @Test
    fun engineDefaultsAndControlsFollowBackendState() {
        val defaults = AvasEngineUiState()

        assertFalse(defaults.enabled)
        assertEquals("ferrari_v8", defaults.packId)
        assertTrue(defaults.exteriorEnabled)
        assertEquals(15, defaults.exteriorVolume)
        assertFalse(defaults.interiorEnabled)
        assertEquals(15, defaults.interiorVolume)
        assertFalse(defaults.startAllowed)
        assertFalse(defaults.stopAllowed)

        val enabled = defaults.copy(enabled = true)
        assertTrue(enabled.startAllowed)
        assertFalse(enabled.copy(exteriorEnabled = false).startAllowed)
        assertTrue(enabled.copy(state = "starting").busy)
        assertTrue(enabled.copy(state = "starting").startAllowed)
        assertFalse(enabled.copy(state = "starting").stopAllowed)
        assertTrue(enabled.copy(state = "active").startAllowed)
        assertFalse(enabled.copy(state = "active").stopAllowed)
        assertFalse(enabled.copy(state = "starting", testActive = true).startAllowed)
        assertTrue(enabled.copy(state = "starting", testActive = true).stopAllowed)
        assertTrue(enabled.copy(state = "active", testActive = true).stopAllowed)
        assertTrue(enabled.copy(state = "stopping").busy)
        assertFalse(enabled.copy(state = "stopping").startAllowed)
        assertFalse(enabled.copy(state = "error", error = "unavailable").busy)
        assertTrue(enabled.copy(state = "error", error = "unavailable").startAllowed)

        val toggleErrorDuringTest = enabled.copy(state = "error", error = "toggle failed",
            testActive = true)
        assertFalse(toggleErrorDuringTest.startAllowed)
        assertTrue(toggleErrorDuringTest.stopAllowed)
    }

    @Test
    fun engineBindingIsSeparateFromRuntimeState() {
        val binding = CameraButtonBindings.Binding(88, CameraButtonBindings.Press.Double)
        val configured = AvasEngineUiState(enabled = true, binding = binding)

        assertEquals(CameraButtonBindings.Binding(-1, CameraButtonBindings.Press.Single),
            AvasEngineUiState().binding)
        assertEquals(binding, configured.copy(state = "active", testActive = true).binding)
        assertEquals(binding, configured.copy(state = "stopped", testActive = false).binding)
    }

    @Test
    fun engineActionsCarryFixedPackAndIndependentOutputValues() {
        val pack = AvasBackendAction("engine", AvasActionKind.SetEnginePack,
            stringValue = "huracan_v10")
        val exterior = AvasBackendAction("engine", AvasActionKind.SetEngineExterior,
            booleanValue = false)
        val interiorVolume = AvasBackendAction("engine", AvasActionKind.SetEngineInteriorVolume,
            intValue = 63)

        assertEquals("huracan_v10", pack.stringValue)
        assertEquals(false, exterior.booleanValue)
        assertEquals(63, interiorVolume.intValue)
    }

    @Test
    fun skipSwitchActionAndProfileValuesAreIndependent() {
        val on = AvasProfileUiState(id = AvasProfileIds.POWER_ON,
            skipConcurrentLockUnlock = false)
        val off = AvasProfileUiState(id = AvasProfileIds.POWER_OFF)
        val action = AvasBackendAction(on.id,
            AvasActionKind.SetSkipConcurrentLockUnlock, booleanValue = true)

        assertFalse(on.skipConcurrentLockUnlock)
        assertTrue(off.skipConcurrentLockUnlock)
        assertTrue(action.booleanValue == true)
    }

    @Test
    fun manualStartRequiresReadySelectionAndNoOwnManualRequest() {
        val ready = AvasProfileUiState(id = AvasProfileIds.LOCK, assets = listOf(asset),
            selectedAssetId = asset.id)
        assertTrue(ready.manualStartAllowed)
        assertTrue(ready.copy(enabled = true).manualStartAllowed)
        assertFalse(ready.copy(selectedAssetId = "missing").manualStartAllowed)
        assertFalse(ready.copy(playback = AvasPlaybackUiState.ManualQueued).manualStartAllowed)
        assertFalse(ready.copy(playback = AvasPlaybackUiState.ManualPlaying).manualStartAllowed)
        assertTrue(ready.copy(playback = AvasPlaybackUiState.AutomaticPlaying).manualStartAllowed)
    }

    @Test
    fun manualStopNeverTargetsAutomaticPlayback() {
        val profile = AvasProfileUiState(id = AvasProfileIds.LOCK)
        assertFalse(profile.manualStopAllowed)
        assertTrue(profile.copy(playback = AvasPlaybackUiState.ManualQueued).manualStopAllowed)
        assertTrue(profile.copy(playback = AvasPlaybackUiState.ManualPlaying).manualStopAllowed)
        assertFalse(profile.copy(playback = AvasPlaybackUiState.AutomaticPlaying).manualStopAllowed)
    }

    @Test
    fun auditionIdentityAndAssetMetadataAreIndependentFromSelection() {
        val builtin = AvasAssetUiState("builtin", "test.wav", ready = true,
            builtin = true, durationMs = 1_001)
        val audition = AvasAuditionUiState(AvasProfileIds.UNLOCK, builtin.id, "session-7", "playing")

        assertTrue(builtin.builtin)
        assertEquals(1_001L, builtin.durationMs)
        assertTrue(audition.active)
        assertFalse(audition.copy(state = "idle").active)
        assertEquals("0:02", avasDurationLabel(builtin.durationMs))
        assertEquals("—", avasDurationLabel(null))
    }

    @Test
    fun auditionActionsCarryAssetOrSessionIdentity() {
        val start = AvasBackendAction(AvasProfileIds.LOCK, AvasActionKind.StartAudition,
            stringValue = "asset-id")
        val stop = AvasBackendAction(AvasProfileIds.LOCK, AvasActionKind.StopAudition,
            stringValue = "session-id")
        val delete = AvasBackendAction(AvasProfileIds.LOCK, AvasActionKind.DeleteAsset,
            stringValue = "asset-id")

        assertEquals("asset-id", start.stringValue)
        assertEquals("session-id", stop.stringValue)
        assertEquals("asset-id", delete.stringValue)
    }
}
