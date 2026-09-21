package com.byd.extend.ui

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
