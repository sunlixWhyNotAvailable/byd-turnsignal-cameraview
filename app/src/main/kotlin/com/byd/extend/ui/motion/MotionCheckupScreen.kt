package com.byd.extend.ui.motion

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.byd.extend.MotionCheckupClient
import com.byd.extend.R
import com.byd.extend.ui.ActionButton
import com.byd.extend.ui.Section
import com.byd.extend.ui.StatusPill
import com.byd.extend.ui.StatusTone
import com.byd.extend.ui.StatusUiState
import com.byd.extend.ui.UiPalette
import com.byd.extend.ui.UiStrings

@Composable
internal fun MotionCheckupSection(colors: UiPalette, strings: UiStrings,
    parkedBatteryNotice: Boolean = false, onRun: () -> Unit) {
    Section(strings.resource(R.string.motion_check_title), colors) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(strings.resource(R.string.motion_check_hint), color = colors.muted,
                fontSize = 13.sp, modifier = Modifier.weight(1f))
            ActionButton(strings.resource(R.string.motion_check_run), colors,
                icon = Icons.Outlined.CheckCircle, onClick = onRun)
        }
        Text(strings.resource(if (parkedBatteryNotice) R.string.cameras_battery_notice
            else R.string.cameras_viewing_battery_notice),
            color = colors.yellow, fontSize = 13.sp, minLines = 2)
    }
}

@Composable
internal fun MotionCheckupDialog(colors: UiPalette, strings: UiStrings, onClose: () -> Unit) {
    val context = LocalContext.current
    val results = remember { mutableStateMapOf<Int, MotionCheckupClient.Update>() }
    DisposableEffect(context) {
        results.clear()
        val client = MotionCheckupClient(context) { results[it.stage] = it }
        client.start()
        onDispose { client.close() }
    }
    val stages = listOf(R.string.motion_check_access, R.string.motion_check_detector,
        R.string.motion_check_frames, R.string.motion_check_recording)
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize(.94f).clip(RoundedCornerShape(10.dp)).background(colors.surface)
            .border(1.dp, colors.borderStrong, RoundedCornerShape(10.dp)).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(strings.resource(R.string.motion_check_title), color = colors.text, fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold)
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(strings.resource(R.string.motion_check_scope), color = colors.muted, fontSize = 14.sp)
                stages.forEachIndexed { index, title ->
                    val result = results[index]
                    val state = result?.state ?: MotionCheckupClient.NOT_CHECKED
                    val tone = when (state) {
                        MotionCheckupClient.OK -> StatusTone.Ok
                        MotionCheckupClient.ERROR -> StatusTone.Error
                        MotionCheckupClient.RUNNING -> StatusTone.Warning
                        else -> StatusTone.Neutral
                    }
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                        .background(colors.panel).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(strings.resource(title), color = colors.text, fontSize = 16.sp,
                                modifier = Modifier.weight(1f))
                            val status = strings.resource(when (state) {
                                MotionCheckupClient.OK -> R.string.motion_check_working
                                MotionCheckupClient.ERROR -> R.string.motion_check_error
                                MotionCheckupClient.RUNNING -> R.string.motion_check_running
                                else -> R.string.motion_check_not_checked
                            })
                            StatusPill(StatusUiState(status, tone, true), status, colors)
                        }
                        val reason = when (result?.reason) {
                            "access_ok" -> R.string.motion_check_access_ok
                            "detector_ok" -> R.string.motion_check_detector_ok
                            "frames_clear" -> R.string.motion_check_frames_clear
                            "frames_motion" -> R.string.motion_check_frames_motion
                            "recording_ok" -> R.string.motion_check_recording_ok
                            "busy" -> R.string.motion_check_busy
                            "power_not_on" -> R.string.motion_check_power
                            "cancelled" -> R.string.motion_check_cancelled
                            "cleanup_pending" -> R.string.motion_check_cleanup
                            "timeout" -> R.string.motion_check_timeout
                            "worker_unavailable" -> R.string.motion_check_worker
                            "check_failed" -> R.string.motion_check_failed
                            else -> null
                        }
                        reason?.let { Text(strings.resource(it), color = if (state == MotionCheckupClient.ERROR)
                            colors.red else colors.muted, fontSize = 14.sp) }
                        if (state == MotionCheckupClient.ERROR && !result?.detail.isNullOrBlank()) {
                            Text(result!!.detail, color = colors.muted, fontSize = 13.sp)
                        }
                    }
                }
            }
            ActionButton(strings.resource(R.string.motion_close), colors, modifier = Modifier.fillMaxWidth(), onClick = onClose)
        }
    }
}
