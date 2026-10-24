package com.byd.extend.ui

import com.byd.extend.AdbRecoverySnapshot
import com.byd.extend.AdbReminderOverlayRequest
import com.byd.extend.AdbReminderSettings
import com.byd.extend.WifiRetentionSettings

/** Small Java-facing bridge; native owners never need to reconstruct Kotlin data classes. */
object AdbRecoveryUiBridge {
    @JvmStatic
    fun state(
        snapshot: AdbRecoverySnapshot?,
        settings: AdbReminderSettings,
        wifiRetentionSettings: WifiRetentionSettings,
    ): AdbRecoveryUiState {
        val appearance = settings.readAppearance()
        return snapshot?.let {
            AdbRecoveryUiState.fromSnapshot(it, appearance)
                .copy(enabled = settings.recoveryEnabled(),
                    keepWifiCarOff = wifiRetentionSettings.enabled())
        }
            ?: AdbRecoveryUiState(
                enabled = settings.recoveryEnabled(),
                appearance = appearance,
                keepWifiCarOff = wifiRetentionSettings.enabled(),
            )
    }

    @JvmStatic
    fun overlay(
        snapshot: AdbRecoverySnapshot,
        settings: AdbReminderSettings,
        language: UiLanguage,
        darkTheme: Boolean,
    ): AdbReminderOverlayRequest = AdbReminderOverlayRequest(
        waitWifi = settings.recoveryEnabled() && snapshot.enabled() &&
            snapshot.stage() == AdbRecoverySnapshot.Stage.WAITING_FOR_WIFI,
        wifiConnected = snapshot.wifiConnected(),
        waitStartedElapsedMs = snapshot.waitStartedElapsedMs(),
        cycleId = snapshot.cycleId(),
        hintSuppressedForCycle = snapshot.hintSuppressedForCycle(),
        appearance = settings.readAppearance(),
        language = language,
        darkTheme = darkTheme,
    )
}
