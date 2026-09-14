package com.byd.extend.ui

import com.byd.extend.AdbRecoverySnapshot
import com.byd.extend.AdbReminderOverlayRequest
import com.byd.extend.AdbReminderSettings

/** Small Java-facing bridge; native owners never need to reconstruct Kotlin data classes. */
object AdbRecoveryUiBridge {
    @JvmStatic
    fun state(
        snapshot: AdbRecoverySnapshot?,
        settings: AdbReminderSettings,
    ): AdbRecoveryUiState {
        val appearance = settings.readAppearance()
        return snapshot?.let {
            AdbRecoveryUiState.fromSnapshot(it, appearance)
                .copy(enabled = settings.recoveryEnabled())
        }
            ?: AdbRecoveryUiState(
                enabled = settings.recoveryEnabled(),
                appearance = appearance,
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
