package com.byd.extend.ui.motion

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.byd.extend.CameraChargePolicy
import com.byd.extend.CameraChargeSettings
import com.byd.extend.CameraChargeTelemetry
import com.byd.extend.R
import com.byd.extend.remote.CameraViewingController
import com.byd.extend.remote.CameraViewingEdits
import com.byd.extend.ui.ActionButton
import com.byd.extend.ui.ChoiceField
import com.byd.extend.ui.FormScope
import com.byd.extend.ui.AppSwitch
import com.byd.extend.ui.avasSwitchRow as switchRow
import com.byd.extend.ui.FormSection
import com.byd.extend.ui.ImeDismissalEffect
import com.byd.extend.ui.NumericSetting
import com.byd.extend.ui.Section
import com.byd.extend.ui.StatusPill
import com.byd.extend.ui.StatusTone
import com.byd.extend.ui.StatusUiState
import com.byd.extend.ui.Segmented
import com.byd.extend.ui.SwitchLine
import com.byd.extend.ui.UiPalette
import com.byd.extend.ui.UiStrings
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.awaitCancellation
import java.util.Locale
import kotlin.math.roundToInt

@Composable
internal fun rememberCameraChargeState(context: Context): CameraChargePolicy.State {
    val telemetry = remember(context) { CameraChargeTelemetry.get(context) }
    val state by produceState(initialValue = telemetry.snapshot(), telemetry) {
        val output = this
        val listener = object : CameraChargeTelemetry.Listener {
            override fun onState(next: CameraChargePolicy.State) {
                output.value = next
            }
        }
        val observation = telemetry.observe(listener)
        try {
            awaitCancellation()
        } finally {
            observation.close()
        }
    }
    return state
}

@Composable
internal fun CameraChargeSettingsSection(
    colors: UiPalette,
    strings: UiStrings,
    state: CameraChargePolicy.State,
    socThreshold: Int,
    voltageThreshold: Float,
    enabled: Boolean,
    onSocThreshold: (Int) -> Unit,
    onVoltageThreshold: (Float) -> Unit,
) {
    Section(strings.motionText(R.string.cameras_battery_title), colors) {
        NumericSetting(
            "SOC", socThreshold.toString(), "%", colors,
            onCommit = { raw -> raw.toIntOrNull()?.let { onSocThreshold(it.coerceIn(10, 90)) } },
            range = 10f..90f, adjustable = true, enabled = enabled, identity = "camera-charge-soc",
        )
        NumericSetting(
            strings.motionText(R.string.cameras_low_voltage),
            String.format(Locale.US, "%.1f", voltageThreshold),
            strings.motionText(R.string.cameras_volts), colors,
            onCommit = { raw -> raw.toFloatOrNull()?.let { onVoltageThreshold((it * 10f).roundToInt() / 10f) } },
            range = 12f..13f, adjustable = true, adjustmentStep = 0.1f, decimalPlaces = 1,
            identity = "camera-charge-voltage", enabled = enabled,
        )
        Text(strings.motionText(R.string.cameras_battery_shared), color = colors.muted, fontSize = 13.sp)
        if (state.isBlocked()) {
            Text(strings.motionText(R.string.cameras_charge_paused), color = colors.yellow, fontSize = 13.sp)
        } else if (state.shouldWarnUnavailable()) {
            Text(chargeUnavailableText(strings, state),
                color = colors.yellow, fontSize = 13.sp)
        }
    }
}

@Composable
internal fun FormScope.CameraViewingScreen(
    colors: UiPalette,
    strings: UiStrings,
    chargeState: CameraChargePolicy.State,
): FormScope {
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val controller = remember(context) { CameraViewingController.get(context) }
    val state by controller.state.collectAsState()
    var streamIdDraft by remember { mutableStateOf(state.config.streamId) }
    var streamIdDirty by remember { mutableStateOf(false) }
    var passwordDraft by remember { mutableStateOf("") }
    var tokenDraft by remember { mutableStateOf("") }
    var passwordDirty by remember { mutableStateOf(false) }
    var tokenDirty by remember { mutableStateOf(false) }
    var passwordLoaded by remember { mutableStateOf(false) }
    var tokenLoaded by remember { mutableStateOf(false) }
    var revealPassword by remember { mutableStateOf(false) }
    var revealToken by remember { mutableStateOf(false) }
    var showQr by remember { mutableStateOf(false) }
    var revokeSubmitted by remember { mutableStateOf(false) }
    var suppressFocusCommit by remember { mutableStateOf(false) }
    val transport = state.config.transport
    val isZrok = transport == "zrok"

    fun clearLocalSecrets() {
        passwordDraft = ""
        tokenDraft = ""
        passwordDirty = false
        tokenDirty = false
        passwordLoaded = false
        tokenLoaded = false
        revealPassword = false
        revealToken = false
    }

    fun submit(
        enabled: Boolean? = null,
        nextTransport: String? = null,
        public: Boolean? = null,
        authorize: Boolean = false,
    ) {
        val edits = CameraViewingEdits(transport,
            streamIdDraft.takeIf { streamIdDirty },
            passwordDraft.takeIf { passwordLoaded && passwordDirty },
            tokenDraft.takeIf { tokenLoaded && tokenDirty })
        controller.update(edits, enabled = enabled, transport = nextTransport,
            tailscalePublic = public, authorize = authorize, onSaved = {
            if (controller.state.value.config.transport == edits.transport) {
                if (edits.streamId != null && streamIdDraft == edits.streamId) {
                    streamIdDirty = false
                    streamIdDraft = controller.state.value.config.streamId
                }
                if (edits.password != null && passwordDraft == edits.password) passwordDirty = false
                if (edits.token != null && tokenDraft == edits.token) tokenDirty = false
            }
        })
    }

    fun commitEdits() {
        if (!suppressFocusCommit && (streamIdDirty || passwordDirty || tokenDirty)) submit()
    }

    fun clearFocusForAction() {
        suppressFocusCommit = true
        focus.clearFocus()
        suppressFocusCommit = false
    }

    LaunchedEffect(transport, state.config.tailscalePublic, state.authorized,
        state.revocationPending, state.passwordConfigured, state.tokenConfigured) {
        clearFocusForAction()
        streamIdDraft = state.config.streamId
        streamIdDirty = false
        clearLocalSecrets()
        showQr = false
        val needsPassword = transport != "tailscale" || state.config.tailscalePublic
        if (needsPassword) {
            passwordDraft = runCatching { controller.getPassword() }.getOrDefault("")
            passwordLoaded = true
        }
        if (isZrok) {
            tokenDraft = runCatching { controller.getToken() }.getOrDefault("")
            tokenLoaded = true
        } else {
            tokenDraft = ""
            tokenLoaded = true
        }
    }
    LaunchedEffect(state.config.streamId) {
        if (!streamIdDirty) streamIdDraft = state.config.streamId
    }
    LaunchedEffect(state.authorizationRequest) {
        val request = state.authorizationRequest
        if (request != 0L) state.authorizationUrl?.let { url ->
            controller.authorizationOpened(request)
            openAuthorization(context, url, strings)
        }
    }
    val commitLatest by rememberUpdatedState(::commitEdits)
    DisposableEffect(controller) {
        onDispose {
            commitLatest()
            passwordDraft = ""
            tokenDraft = ""
            passwordDirty = false
            tokenDirty = false
            revealPassword = false
            revealToken = false
        }
    }

    val providerIndex = when (transport) {
        "tailscale" -> 1
        "zrok" -> 2
        else -> 0
    }
    val phase = state.phase
    val blocked = chargeState.isBlocked()
    val fieldsLocked = !state.config.enabled || state.authorized || state.revocationPending || state.busy
    val accessSettingsLocked = !state.config.enabled || state.revocationPending || state.busy
    LaunchedEffect(state.revocationPending, phase, state.error) {
        if (!state.revocationPending || phase == "revoke_pending" || state.error != null) {
            revokeSubmitted = false
        }
    }
    val revokeInProgress = state.revocationPending && phase == "revoking"
    val canRevoke = state.config.enabled && !revokeSubmitted && !revokeInProgress && (!state.busy || state.authorized)
    val passwordRequired = transport == "vdo" || transport == "zrok" ||
        (transport == "tailscale" && state.config.tailscalePublic)
    val authInputsLoaded = (!passwordRequired || passwordLoaded) && (transport != "zrok" || tokenLoaded)
    val editsForAuthorization = CameraViewingEdits(transport, streamIdDraft,
        passwordDraft.takeIf { passwordLoaded }, tokenDraft.takeIf { tokenLoaded })
    val validationError = if (authInputsLoaded)
        editsForAuthorization.authorizationError(state.config.tailscalePublic) else null
    val canAuthorize = state.config.enabled && !state.busy && !state.revocationPending &&
        authInputsLoaded && validationError == null
    val canCancel = state.config.enabled && state.busy && !state.revocationPending
    val pendingAccess = state.accessChangesPending || state.authorized && passwordDirty
    val shareable = state.config.enabled && !pendingAccess && !streamIdDirty && !passwordDirty && !tokenDirty && !state.viewerUrl.isNullOrBlank()
    val phaseStatus = when {
        state.revocationPending -> strings.motionText(R.string.cameras_revoke_pending)
        pendingAccess -> strings.motionText(R.string.cameras_access_changes_pending)
        blocked || phase == "low_battery" -> strings.motionText(R.string.cameras_status_low_battery)
        phase == "approval_pending" -> strings.motionText(R.string.cameras_status_approval_pending)
        phase == "authorizing" || phase == "starting" -> strings.motionText(R.string.cameras_status_authorizing)
        phase == "connecting" -> strings.motionText(R.string.cameras_status_connecting)
        phase == "ready" -> strings.motionText(R.string.cameras_status_ready)
        phase == "preparing" -> strings.motionText(R.string.cameras_status_preparing)
        phase == "connected" -> strings.motionText(R.string.cameras_status_connected)
        phase == "error" -> strings.motionText(R.string.cameras_status_error)
        else -> strings.motionText(R.string.cameras_not_connected)
    }
    val phaseTone = when {
        state.revocationPending -> StatusTone.Warning
        pendingAccess -> StatusTone.Warning
        blocked || phase in setOf("low_battery", "authorizing", "starting", "approval_pending", "connecting", "preparing") -> StatusTone.Warning
        phase == "connected" -> StatusTone.Ok
        phase == "error" -> StatusTone.Error
        phase == "ready" -> StatusTone.Ok
        else -> StatusTone.Neutral
    }

    val toggleViewing: (Boolean) -> Unit = { enabled -> clearFocusForAction(); submit(enabled = enabled) }
    FormSection(strings.motionText(R.string.cameras_viewing), colors, key = "cameras-viewing",
        headerModifier = Modifier.switchRow(state.config.enabled, toggleViewing, colors),
        trailing = { AppSwitch(state.config.enabled, toggleViewing, colors, compact = true, clearSemantics = true) }) {
        row("hint") {
            Text(strings.motionText(R.string.cameras_on_demand), color = colors.muted, fontSize = 13.sp)
        }
        row("method") {
            ChoiceField(strings.motionText(R.string.cameras_connection_method),
                listOf("VDO.Ninja", "Tailscale", "Zrok"), providerIndex, { selected ->
                    clearFocusForAction()
                    submit(nextTransport = listOf("vdo", "tailscale", "zrok")[selected])
                }, colors, enabled = state.config.enabled)
        }
        row("interior-cameras") {
            Text(strings.motionText(R.string.cameras_internal), color = colors.text, fontSize = 16.sp)
            Box(Modifier.fillMaxWidth().heightIn(min = 42.dp), contentAlignment = Alignment.CenterStart) {
                if (transport == "vdo") {
                    Segmented(listOf(R.string.cameras_internal_none, R.string.cameras_internal_cabin,
                        R.string.cameras_internal_driver, R.string.cameras_internal_both)
                        .map { strings.motionText(it) }, state.config.interiorCameras, colors, Modifier.fillMaxWidth(),
                        enabled = { state.config.enabled }) {
                        controller.setInteriorCameras(it)
                    }
                } else {
                    Text(strings.motionText(R.string.cameras_internal_viewer_hint), color = colors.muted, fontSize = 13.sp)
                }
            }
        }
        this
    }
    row("cameras-provider-gap") { Spacer(Modifier.height(12.dp)) }

    val providerTitle = when (transport) {
        "tailscale" -> strings.motionText(R.string.cameras_tailscale_title)
        "zrok" -> "Zrok"
        else -> "VDO.Ninja"
    }
    FormSection(providerTitle, colors, key = "cameras-provider-$transport") {
        when (transport) {
            "tailscale" -> {
                row("mode") {
                    Segmented(listOf(strings.motionText(R.string.cameras_private),
                        strings.motionText(R.string.cameras_public)),
                        if (state.config.tailscalePublic) 1 else 0, colors, Modifier.fillMaxWidth(),
                        enabled = { !accessSettingsLocked }) {
                        clearFocusForAction()
                        submit(public = it == 1)
                    }
                }
                row("security") {
                    Text(strings.motionText(if (state.config.tailscalePublic)
                        R.string.cameras_public_security else R.string.cameras_private_security),
                        color = colors.muted, fontSize = 13.sp)
                }
                row("setup") {
                    Text(strings.motionText(if (state.config.tailscalePublic)
                        R.string.cameras_tailscale_public_setup else R.string.cameras_tailscale_private_setup),
                        color = colors.muted, fontSize = 13.sp)
                }
                row("name") {
                    ConnectionField(strings.motionText(R.string.cameras_device_name), streamIdDraft,
                        colors, enabled = !fieldsLocked, onValueChange = {
                            streamIdDraft = it
                            streamIdDirty = true
                        }, onCommit = ::commitEdits,
                        hint = strings.motionText(R.string.cameras_connection_name_hint))
                }
                if (state.config.tailscalePublic) row("password") {
                    ConnectionField(strings.motionText(R.string.cameras_viewer_password), passwordDraft,
                        colors, secret = true, revealed = revealPassword,
                        enabled = passwordLoaded && !accessSettingsLocked,
                        revealDescription = strings.motionText(if (revealPassword) R.string.cameras_secret_hide
                            else R.string.cameras_secret_show),
                        onReveal = { revealPassword = !revealPassword }, onValueChange = {
                            passwordDraft = it
                            passwordDirty = true
                        }, onCommit = ::commitEdits)
                }
            }
            "zrok" -> {
                row("hint") {
                    Text(strings.motionText(R.string.cameras_zrok_hint), color = colors.muted, fontSize = 13.sp)
                }
                row("token") {
                    ConnectionField(strings.motionText(R.string.cameras_account_token), tokenDraft,
                        colors, secret = true, revealed = revealToken,
                        enabled = tokenLoaded && !fieldsLocked,
                        revealDescription = strings.motionText(if (revealToken) R.string.cameras_secret_hide
                            else R.string.cameras_secret_show),
                        onReveal = { revealToken = !revealToken }, onValueChange = {
                            tokenDraft = it
                            tokenDirty = true
                        }, onCommit = ::commitEdits)
                }
                row("name") {
                    ConnectionField(strings.motionText(R.string.cameras_connection_name), streamIdDraft,
                        colors, enabled = !fieldsLocked, onValueChange = {
                            streamIdDraft = it
                            streamIdDirty = true
                        }, onCommit = ::commitEdits,
                        hint = strings.motionText(R.string.cameras_connection_name_hint))
                }
                row("password") {
                    ConnectionField(strings.motionText(R.string.cameras_viewer_password), passwordDraft,
                        colors, secret = true, revealed = revealPassword,
                        enabled = passwordLoaded && !accessSettingsLocked,
                        revealDescription = strings.motionText(if (revealPassword) R.string.cameras_secret_hide
                            else R.string.cameras_secret_show),
                        onReveal = { revealPassword = !revealPassword }, onValueChange = {
                            passwordDraft = it
                            passwordDirty = true
                        }, onCommit = ::commitEdits)
                }
            }
            else -> {
                row("hint") {
                    Text(strings.motionText(R.string.cameras_vdo_hint), color = colors.muted, fontSize = 13.sp)
                }
                row("id") {
                    ConnectionField(strings.motionText(R.string.cameras_stream_id), streamIdDraft,
                        colors, enabled = !fieldsLocked, onValueChange = {
                            streamIdDraft = it
                            streamIdDirty = true
                        }, onCommit = ::commitEdits,
                        hint = strings.motionText(R.string.cameras_vdo_id_hint))
                }
                row("password") {
                    ConnectionField(strings.motionText(R.string.cameras_viewer_password), passwordDraft,
                        colors, secret = true, revealed = revealPassword,
                        enabled = passwordLoaded && !accessSettingsLocked,
                        revealDescription = strings.motionText(if (revealPassword) R.string.cameras_secret_hide
                            else R.string.cameras_secret_show),
                        onReveal = { revealPassword = !revealPassword }, onValueChange = {
                            passwordDraft = it
                            passwordDirty = true
                        }, onCommit = ::commitEdits,
                        hint = strings.motionText(R.string.cameras_vdo_password_hint))
                }
            }
        }
        if (!state.revocationPending && (!state.authorized || pendingAccess || phase == "error" || phase == "idle" || canCancel)) row("provider-actions") {
            Column(horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp,
                    Alignment.CenterHorizontally)) {
                    if (transport == "vdo" && !state.authorized) {
                        ActionButton(strings.motionText(R.string.cameras_regenerate), colors,
                            Modifier.weight(1f), enabled = !fieldsLocked) {
                            clearFocusForAction()
                            streamIdDraft = CameraViewingEdits.newName("vdo")
                            streamIdDirty = true
                            submit()
                        }
                    }
                    ActionButton(strings.motionText(if (canCancel) R.string.cancel
                        else if (pendingAccess) R.string.cameras_apply_connection
                        else if (state.authorized) R.string.cameras_retry_connection
                        else R.string.cameras_authorize), colors, Modifier.weight(1f),
                        enabled = canAuthorize || canCancel) {
                        clearFocusForAction()
                        if (canCancel) controller.cancel() else submit(authorize = true)
                    }
                }
                validationError?.let {
                    Text(strings.motionText(it), color = colors.red, fontSize = 13.sp)
                }
                state.fieldError?.takeIf { it.isNotBlank() }?.let {
                    Text(it, color = colors.red, fontSize = 13.sp)
                }
            }
        }
        this
    }
    row("cameras-access-gap") { Spacer(Modifier.height(12.dp)) }

    FormSection(strings.motionText(R.string.cameras_access), colors, key = "cameras-access") {
        row("status") {
            StatusPill(StatusUiState(phaseStatus, phaseTone, true), phaseStatus, colors)
            state.error?.takeIf { it.isNotBlank() }?.let { Text(it, color = colors.red, fontSize = 13.sp) }
        }
        state.identity?.takeIf { it.isNotBlank() }?.let { identity ->
            if (state.authorized || state.revocationPending) row("identity") {
                Text(strings.motionText(R.string.cameras_access_identity, identity),
                    color = colors.muted, fontSize = 13.sp)
            }
        }
        if (state.authorized && state.identity.isNullOrBlank()) row("identity") {
            Text(strings.motionText(R.string.cameras_access_authorized), color = colors.muted, fontSize = 13.sp)
        }
        state.viewerUrl?.takeUnless { pendingAccess }?.let { viewerUrl -> row("viewer-url") {
            Text(viewerUrl, color = colors.muted, fontSize = 12.sp)
        } }
        if (state.viewerUrl.isNullOrBlank()) row("link-pending") {
            Text(strings.motionText(if (transport == "vdo") R.string.cameras_vdo_link_pending
                else R.string.cameras_link_pending), color = colors.muted, fontSize = 13.sp)
        }
        row("link-actions") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp,
                Alignment.CenterHorizontally)) {
                ActionButton(strings.motionText(R.string.cameras_copy_link), colors,
                    Modifier.weight(1f), enabled = shareable) {
                    state.viewerUrl?.let { copyViewerUrl(context, it, strings) }
                }
                ActionButton(strings.motionText(R.string.cameras_qr), colors, Modifier.weight(1f),
                    enabled = shareable) { showQr = true }
                if (state.authorized || state.revocationPending || state.viewerUrl != null ||
                    state.passwordConfigured || state.tokenConfigured) {
                    ActionButton(strings.motionText(if (state.revocationPending)
                        R.string.cameras_retry_revoke else R.string.cameras_revoke), colors,
                        Modifier.weight(1f), destructive = true, enabled = canRevoke) {
                    clearFocusForAction()
                    revokeSubmitted = true
                    controller.revoke()
                    showQr = false
                    }
                }
            }
        }
        if (state.revocationPending) row("revoke-pending") {
            Text(strings.motionText(R.string.cameras_revoke_pending), color = colors.yellow, fontSize = 13.sp)
        }
        if (blocked) row("charge-blocked") {
            Text(strings.motionText(R.string.cameras_charge_paused), color = colors.yellow, fontSize = 13.sp)
        } else if (chargeState.shouldWarnUnavailable()) row("charge-unavailable") {
            Text(chargeUnavailableText(strings, chargeState), color = colors.yellow, fontSize = 13.sp)
        }
        row("session-hint") {
            Text(strings.motionText(R.string.cameras_session_hint), color = colors.muted, fontSize = 13.sp)
        }
        this
    }

    if (state.config.enabled && showQr) state.viewerUrl?.let { url -> CameraViewerQrDialog(url, colors, strings) { showQr = false } }
    return this
}

private fun chargeUnavailableText(strings: UiStrings, state: CameraChargePolicy.State): String {
    val missing = buildList {
        if (state.isSocUnavailable()) add("SOC")
        if (state.isVoltageUnavailable()) add(strings.motionText(R.string.cameras_low_voltage))
    }.joinToString(" / ")
    return strings.motionText(R.string.cameras_charge_unavailable, missing)
}

@Composable
private fun ConnectionField(
    title: String,
    value: String,
    colors: UiPalette,
    secret: Boolean = false,
    revealed: Boolean = false,
    enabled: Boolean = true,
    revealDescription: String = title,
    onReveal: () -> Unit = {},
    onValueChange: (String) -> Unit,
    onCommit: () -> Unit = {},
    hint: String? = null,
) {
    val focus = LocalFocusManager.current
    var focused by remember { mutableStateOf(false) }
    ImeDismissalEffect(focused) { focus.clearFocus() }
    Text(title, color = colors.text, fontSize = 16.sp)
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(7.dp))
        .background(colors.field).border(1.dp, colors.borderStrong, RoundedCornerShape(7.dp))
        .padding(start = 12.dp, end = if (secret) 4.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        BasicTextField(
            value = value,
            onValueChange = { if (enabled) onValueChange(it) },
            enabled = enabled,
            singleLine = true,
            visualTransformation = if (secret && !revealed) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(
                keyboardType = if (secret) KeyboardType.Password else KeyboardType.Text,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
            textStyle = TextStyle(color = if (enabled) colors.text else colors.muted,
                fontSize = 16.sp, fontWeight = FontWeight.Normal),
            cursorBrush = SolidColor(colors.accent),
            modifier = Modifier.weight(1f).onFocusChanged {
                val wasFocused = focused
                focused = it.isFocused
                if (wasFocused && !focused) onCommit()
            }
                .semantics { contentDescription = title }.padding(vertical = 12.dp),
        )
        if (secret) {
            IconButton(onClick = onReveal, enabled = enabled, modifier = Modifier.size(38.dp)
                .semantics { contentDescription = revealDescription }) {
                Canvas(Modifier.size(24.dp)) {
                    drawOval(colors.muted,
                        androidx.compose.ui.geometry.Offset(size.width * .12f, size.height * .28f),
                        androidx.compose.ui.geometry.Size(size.width * .76f, size.height * .44f),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
                    drawCircle(colors.muted, radius = size.width * .1f)
                    if (!revealed) {
                        drawLine(colors.muted, androidx.compose.ui.geometry.Offset(size.width * .16f, size.height * .82f),
                            androidx.compose.ui.geometry.Offset(size.width * .84f, size.height * .18f), strokeWidth = 2.dp.toPx())
                    }
                }
            }
        }
    }
    if (hint != null) Text(hint, color = colors.muted, fontSize = 13.sp)
}

@Composable
private fun CameraViewerQrDialog(url: String, colors: UiPalette, strings: UiStrings, onClose: () -> Unit) {
    val bitmap = remember(url) {
        runCatching {
            val matrix = QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, 480, 480,
                mapOf(EncodeHintType.MARGIN to 4))
            val pixels = IntArray(matrix.width * matrix.height) { index ->
                if (matrix.get(index % matrix.width, index / matrix.width)) AndroidColor.BLACK else AndroidColor.WHITE
            }
            Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.ARGB_8888).apply {
                setPixels(pixels, 0, matrix.width, 0, 0, matrix.width, matrix.height)
            }
        }.getOrNull()
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val qrSide = minOf(240.dp, (maxHeight - 88.dp).coerceAtLeast(0.dp))
            Column(Modifier.widthIn(max = 320.dp).fillMaxWidth().heightIn(max = maxHeight)
                .verticalScroll(rememberScrollState()).clip(RoundedCornerShape(10.dp))
                .background(colors.surface).border(1.dp, colors.borderStrong, RoundedCornerShape(10.dp))
                .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(strings.motionText(R.string.cameras_qr), color = colors.text, fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    IconButton(onClick = onClose, modifier = Modifier.size(48.dp)
                        .semantics { contentDescription = strings.motionText(R.string.motion_close) }) {
                        Canvas(Modifier.size(20.dp)) {
                            val inset = size.width * .25f
                            drawLine(colors.muted, androidx.compose.ui.geometry.Offset(inset, inset),
                                androidx.compose.ui.geometry.Offset(size.width - inset, size.height - inset),
                                strokeWidth = 2.dp.toPx())
                            drawLine(colors.muted, androidx.compose.ui.geometry.Offset(size.width - inset, inset),
                                androidx.compose.ui.geometry.Offset(inset, size.height - inset),
                                strokeWidth = 2.dp.toPx())
                        }
                    }
                }
                if (bitmap != null) Image(bitmap.asImageBitmap(),
                    contentDescription = strings.motionText(R.string.cameras_qr),
                    modifier = Modifier.size(qrSide).background(Color.White))
                else Text(strings.motionText(R.string.cameras_qr_failed), color = colors.red, fontSize = 14.sp)
            }
        }
    }
}

private fun openAuthorization(context: Context, url: String, strings: UiStrings) {
    try {
        val uri = Uri.parse(url)
        if (uri.scheme !in setOf("https", "http")) return
        context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Exception) {
        Toast.makeText(context, strings.motionText(R.string.cameras_open_authorization_failed), Toast.LENGTH_LONG).show()
    }
}

private fun copyViewerUrl(context: Context, url: String, strings: UiStrings) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(strings.motionText(R.string.cameras_viewing), url))
    Toast.makeText(context, strings.motionText(R.string.cameras_copied), Toast.LENGTH_SHORT).show()
}

private fun UiStrings.motionText(id: Int, vararg args: Any): String {
    val value = resource(id)
    return if (args.isEmpty()) value else String.format(Locale.getDefault(), value, *args)
}
