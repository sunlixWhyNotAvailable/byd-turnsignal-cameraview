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
}
