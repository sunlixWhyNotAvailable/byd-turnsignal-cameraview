package com.byd.extend.ui

import androidx.compose.runtime.Immutable
import com.byd.extend.AdbReminderAppearance
import com.byd.extend.AdbRecoverySnapshot

enum class AdbRecoveryStage { PREPARING, AVAILABLE, WAIT_WIFI, RESTORING, RESTORED, FAILED }

@Immutable
data class AdbRecoveryUiState @JvmOverloads constructor(
    val enabled: Boolean = true,
    val authenticated5555: Boolean = false,
    val wifiConnected: Boolean = false,
    val stage: AdbRecoveryStage = AdbRecoveryStage.PREPARING,
    val waitStartedElapsedMs: Long = 0L,
    val cycleId: Long = 0L,
    val hintSuppressedForCycle: Boolean = false,
    val appearance: AdbReminderAppearance = AdbReminderAppearance(),
) {
    companion object {
        @JvmStatic fun fromSnapshot(
            snapshot: AdbRecoverySnapshot,
            appearance: AdbReminderAppearance,
        ) = AdbRecoveryUiState(
            enabled = snapshot.enabled(),
            authenticated5555 = snapshot.authenticated5555(),
            wifiConnected = snapshot.wifiConnected(),
            stage = when (snapshot.stage()) {
                AdbRecoverySnapshot.Stage.DISABLED,
                AdbRecoverySnapshot.Stage.CHECKING_5555,
                AdbRecoverySnapshot.Stage.PREPARING -> AdbRecoveryStage.PREPARING
                AdbRecoverySnapshot.Stage.WAITING_FOR_WIFI -> AdbRecoveryStage.WAIT_WIFI
                AdbRecoverySnapshot.Stage.REQUESTING_TLS,
                AdbRecoverySnapshot.Stage.DISCOVERING_TLS,
                AdbRecoverySnapshot.Stage.SWITCHING_TO_5555,
                AdbRecoverySnapshot.Stage.VERIFYING_5555,
                AdbRecoverySnapshot.Stage.CLEANING_UP -> AdbRecoveryStage.RESTORING
                AdbRecoverySnapshot.Stage.READY -> AdbRecoveryStage.RESTORED
                AdbRecoverySnapshot.Stage.BLOCKED -> AdbRecoveryStage.FAILED
            },
            waitStartedElapsedMs = snapshot.waitStartedElapsedMs(),
            cycleId = snapshot.cycleId(),
            hintSuppressedForCycle = snapshot.hintSuppressedForCycle(),
            appearance = appearance.normalized(),
        )
    }
}

sealed interface AdbRecoveryUiAction {
    @Immutable data class SetRecoveryEnabled(val enabled: Boolean) : AdbRecoveryUiAction
    data object Retry : AdbRecoveryUiAction
    @Immutable data class SetReminderEnabled(val enabled: Boolean) : AdbRecoveryUiAction
    @Immutable data class UpdateReminderAppearance(
        val appearance: AdbReminderAppearance,
    ) : AdbRecoveryUiAction
    data object DismissReminder : AdbRecoveryUiAction
}
