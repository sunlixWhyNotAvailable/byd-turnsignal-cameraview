package com.byd.extend.remote

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.byd.extend.CameraChargeSettings
import com.byd.extend.AppLanguage
import com.byd.extend.CameraModeSettings
import com.byd.extend.CameraViewingShellClient
import com.byd.extend.DiagnosticLogPolicy
import com.byd.extend.MotionRuntime
import com.byd.extend.RemoteCameraView
import com.byd.extend.R
import com.byd.extend.remote.security.KeystoreSecretStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.Executors

data class CameraViewingConfig(val enabled: Boolean = false, val transport: String = "vdo",
    val tailscalePublic: Boolean = false, val streamId: String = "", val interiorCameras: Int = 0,
    val mirrorCabin: Boolean = false)
data class CameraViewingState(val config: CameraViewingConfig, val phase: String = "idle",
    val viewerUrl: String? = null, val authorizationUrl: String? = null, val error: String? = null,
    val passwordConfigured: Boolean = false, val tokenConfigured: Boolean = false,
    val authorizationRequest: Long = 0, val authorized: Boolean = false,
    val revocationPending: Boolean = false, val identity: String? = null,
    val busy: Boolean = false, val fieldError: String? = null, val accessChangesPending: Boolean = false)

/** UI/draft facade; providers and camera lifetimes belong to the independent shell process. */
class CameraViewingController private constructor(private val context: Context) {
    private var configDeliveryGeneration = 0L
    private val preferences = context.getSharedPreferences("camera_viewing", Context.MODE_PRIVATE)
    private val displaySettings = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val displayObserver = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == AppLanguage.KEY || key == "ui_dark_theme" || key == RemoteCameraView.PREFERENCE_KEY
            || key == RemoteCameraView.MIRROR_CABIN_PREFERENCE_KEY) execute("viewer_presentation") {
            refresh()
            client?.let { accept(it.request("configure", presentationPayload()
                .put("enabled", CameraChargeSettings.isRemoteViewingEnabled(context)))) }
        }
    }
    private val secrets by lazy { KeystoreSecretStore(context) }
    private val owner = Executors.newSingleThreadExecutor { Thread(it, "viewing-app-client") }
    private val backend = Executors.newSingleThreadExecutor { Thread(it, "viewing-backend-setup") }
    private var backendSetup: CameraViewingShellClient? = null
    private var backendResetRequested = false
    private var backendError: String? = null
    private var mirrorApplyError: String? = null
    private val main = Handler(Looper.getMainLooper())
    private val mutable = MutableStateFlow(CameraViewingState(readConfig()))
    val state: StateFlow<CameraViewingState> = mutable.asStateFlow()
    private var client: CameraViewingShellClient? = null
    private var runtimeActive = false
    private var helperRecoveryAvailable = true
    private var initialAttachRecoveryPending = false
    private val snapshots = linkedMapOf<String, JSONObject>()
    init {
        CameraViewingDiagnostics.initializeApp(context)
        displaySettings.registerOnSharedPreferenceChangeListener(displayObserver)
        execute("initial_state") { refresh() }
    }

    private fun presentationPayload() = JSONObject().put("locale", AppLanguage.read(displaySettings))
        .put("theme", if (displaySettings.getBoolean("ui_dark_theme", true)) "dark" else "light")
        .put("interiorCameras", interiorCameras()).put("mirrorCabin", cabinMirrored())
        .put("extendedLogs", extendedLogsEnabled())

    private fun extendedLogsEnabled() = displaySettings.getBoolean(DiagnosticLogPolicy.PREF_ENABLED, false)

    private fun interiorCameras() = displaySettings.getInt(RemoteCameraView.PREFERENCE_KEY, 0)
        .also(RemoteCameraView::validateMask)

    private fun cabinMirrored() = displaySettings.getBoolean(RemoteCameraView.MIRROR_CABIN_PREFERENCE_KEY, false)

    fun setCabinMirrored(value: Boolean) = execute("mirror_cabin") {
        CameraModeSettings.persistSettingPreference(context,
            CameraModeSettings.SETTING_MIRROR_CABIN, value)
        refresh()
    }

    fun setInteriorCameras(value: Int) = execute("interior_cameras") {
        RemoteCameraView.validateMask(value)
        check(displaySettings.edit().putInt(RemoteCameraView.PREFERENCE_KEY, value).commit()) {
            "interior_camera_setting_commit_failed"
        }
        refresh()
    }

    private fun selected() = preferences.getString("transport", "vdo")?.takeIf { it in METHODS } ?: "vdo"
    private fun draftName(method: String) = preferences.getString("draft_${method}_name",
        preferences.getString("${method}_name", "")) ?: ""
    private fun publicMode() = preferences.getBoolean("draft_tailscale_public", preferences.getBoolean("tailscale_public", false))
    private fun readConfig() = selected().let { CameraViewingConfig(
        CameraChargeSettings.isRemoteViewingEnabled(context), it, publicMode(), draftName(it), interiorCameras(),
        cabinMirrored()) }
    private fun secret(method: String, field: String): String {
        val bytes = secrets.get("draft.camera.$method.$field") ?: secrets.get("camera.$method.$field") ?: return ""
        return try { String(bytes, Charsets.UTF_8) } finally { bytes.fill(0) }
    }
    fun getPassword() = secret(selected(), "password")
    fun getToken() = secret(selected(), "token")
    private fun shadow(method: String, field: String) = preferences.getBoolean("${method}_$field", false)
    private fun locked(method: String) = snapshots[method]?.let {
        it.optBoolean("authorized") || it.optBoolean("revocationPending")
    } ?: (shadow(method, "authorized") || shadow(method, "revocationPending"))

    private fun refresh() {
        val config = readConfig()
        val method = config.transport
        val current = snapshots[method]
        val authorized = current?.optBoolean("authorized") ?: shadow(method, "authorized")
        val pending = current?.optBoolean("revocationPending") ?: shadow(method, "revocationPending")
        val phase = current?.optString("phase", "idle") ?: "idle"
        val accessChangesPending = authorized && (preferences.getBoolean("draft_${method}_access_changed", false) ||
            method == "tailscale" && current != null && config.tailscalePublic != current.optBoolean("public"))
        mutable.value = CameraViewingState(
            config = if ((authorized || pending) && current != null) config.copy(
                streamId = current.optString("name")) else config,
            phase = phase, viewerUrl = if (accessChangesPending) null else current?.nullable("viewerUrl"),
            authorizationUrl = current?.nullable("authorizationUrl"),
            error = (current?.nullable("error") ?: mirrorApplyError ?: backendError)
                ?.let { context.getString(errorResource(it)) },
            passwordConfigured = secret(method, "password").isNotEmpty(),
            tokenConfigured = secret(method, "token").isNotEmpty(),
            authorizationRequest = current?.optLong("authorizationRequest") ?: 0,
            authorized = authorized, revocationPending = pending,
            identity = current?.nullable("identity") ?: if (authorized || pending)
                preferences.getString("${method}_identity", null) else null,
            busy = phase in setOf("authorizing", "starting", "approval_pending", "connecting", "revoking"),
            accessChangesPending = accessChangesPending)
    }

    /** Focus loss, keyboard dismissal and provider selection commit drafts only. */
    fun update(edits: CameraViewingEdits, enabled: Boolean? = null, transport: String? = null,
               tailscalePublic: Boolean? = null, authorize: Boolean = false, regenerate: Boolean = false,
               onSaved: () -> Unit = {}) = execute(if (authorize) "authorize" else "update_draft", edits.transport) {
        require(edits.transport in METHODS && (transport == null || transport in METHODS))
        val method = edits.transport
        val wasLocked = locked(method)
        val editor = preferences.edit()
        if (!wasLocked) {
            edits.streamId?.let { editor.putString("draft_${method}_name", it) }
            if (regenerate) editor.putString("draft_${method}_name", CameraViewingEdits.newName(method))
        }
        if (snapshots[method]?.optBoolean("revocationPending") != true) {
            tailscalePublic?.let { editor.putBoolean("draft_tailscale_public", it) }
            for ((field, value) in listOf("password" to edits.password, "token" to edits.token))
                if (value != null && (!wasLocked || field == "password")) {
                if (wasLocked && field == "password" && value != secret(method, field))
                    editor.putBoolean("draft_${method}_access_changed", true)
                val bytes = value.toByteArray()
                try { secrets.put("draft.camera.$method.$field", bytes) } finally { bytes.fill(0) }
            }
        }
        transport?.let { editor.putString("transport", it) }
        check(editor.commit())
        if (enabled == false) initialAttachRecoveryPending = false
        if (enabled != null) MotionRuntime.setRemoteViewingEnabled(context, enabled)
        refresh()
        main.post(onSaved)
        if (enabled != null) deliverEnabled(++configDeliveryGeneration)
        if (authorize) {
            helperRecoveryAvailable = true
            val payload = profilePayload(method)
            if (wasLocked) payload.put("name", snapshots[method]?.optString("name") ?: state.value.identity)
            run {
                val authorizationEdits = CameraViewingEdits(method, payload.getString("name"),
                    payload.getString("password"), payload.getString("token"))
                val validation = authorizationEdits.authorizationError(payload.getBoolean("public"))
                if (validation != null) {
                    mutable.value = state.value.copy(fieldError = context.getString(validation))
                    return@execute
                }
                val name = checkNotNull(authorizationEdits.validatedName())
                payload.put("name", name)
                check(preferences.edit().putString("draft_${method}_name", name).commit())
                refresh()
            }
            accept(ensureClient().request(if (wasLocked) "reconnect" else "authorize",
                JSONObject().put("transport", method).put("profile", payload)
                    .put("extendedLogs", extendedLogsEnabled())))
            // A successful request acknowledges applying this draft, not provider readiness.
            check(preferences.edit().putBoolean("draft_${method}_access_changed", false).commit())
            refresh()
            client?.let { prepareBackend(it) }
        }
    }

    private fun profilePayload(method: String) = JSONObject().put("name", draftName(method))
        .put("password", secret(method, "password")).put("token", secret(method, "token"))
        .put("public", method == "tailscale" && publicMode())

    private fun deliverEnabled(generation: Long, attempt: Int = 0) {
        if (generation != configDeliveryGeneration) return
        val desired = CameraChargeSettings.isRemoteViewingEnabled(context)
        if (!desired && !preferences.getBoolean("helper_provisioned", false)) return
        try {
            val connected = ensureClient(desired)
            accept(connected.request("configure", presentationPayload().put("enabled", desired)))
            if (desired) prepareBackend(connected)
        } catch (failure: Exception) {
            if (attempt < 2) main.postDelayed({ execute("config_retry") {
                deliverEnabled(generation, attempt + 1)
            } }, if (attempt == 0) 1_000L else 3_000L)
            throw failure
        }
    }

    private fun ensureClient(restoreEnabled: Boolean = CameraChargeSettings.isRemoteViewingEnabled(context)): CameraViewingShellClient {
        client?.let {
            if (it.isConnected) { initialAttachRecoveryPending = false; return it }
            it.close(); client = null
        }
        val connected = CameraViewingShellClient(context)
        val observer = object : CameraViewingShellClient.Listener {
            override fun onSnapshot(snapshot: JSONObject) = execute("snapshot") { if (client === connected) accept(snapshot) }
            override fun onHelperDied() = execute("helper_died") {
                if (client === connected) {
                    client = null
                    snapshots.values.forEach { it.put("phase", "error").put("error", "helper_unavailable")
                        .put("viewerUrl", JSONObject.NULL).put("authorizationUrl", JSONObject.NULL) }
                    refresh()
                    connected.close()
                    if (runtimeActive && helperRecoveryAvailable && CameraChargeSettings.isRemoteViewingEnabled(context)) {
                        helperRecoveryAvailable = false
                        CameraViewingDiagnostics.detail("helper_death_recovery", "shared", "attempt=1")
                        prepareBackend(ensureClient())
                    }
                }
            }
        }
        try {
            connected.ensureConnected()
            connected.setListener(observer)
            val profiles = JSONArray()
            for (method in METHODS) {
                val keyName = "viewing.$method.data_key"
                val key = secrets.get(keyName) ?: ByteArray(32).also { SecureRandom().nextBytes(it); secrets.put(keyName, it) }
                val migration = secrets.snapshot("tunnel.$method.")
                try {
                    val entries = JSONObject()
                    migration.forEach { (name, bytes) -> entries.put(name, Base64.getEncoder().encodeToString(bytes)) }
                    profiles.put(JSONObject().put("transport", method).put("key", Base64.getEncoder().encodeToString(key))
                        .put("migration", entries).put("initial", profilePayload(method)))
                } finally { key.fill(0); migration.values.forEach { it.fill(0) } }
            }
            client = connected
            accept(connected.request("provision", presentationPayload().put("profiles", profiles)
                .put("enabled", restoreEnabled)))
            check(preferences.edit().putBoolean("helper_provisioned", true).commit())
            initialAttachRecoveryPending = false
            CameraViewingDiagnostics.detail("helper_provisioned", "shared", "enabled=$restoreEnabled")
            if (restoreEnabled) prepareBackend(connected)
            return connected
        } catch (failure: Exception) {
            if (client === connected) client = null
            connected.close()
            throw failure
        }
    }

    private fun prepareBackend(connected: CameraViewingShellClient, reset: Boolean = false) {
        if (backendSetup === connected) {
            backendResetRequested = backendResetRequested || reset
            return
        }
        backendSetup = connected
        backendResetRequested = false
        backend.execute {
            val failure = runCatching {
                connected.prepareCameraBackend()
                connected.request("reconfigured", JSONObject().put("restartVideo", reset)
                    .put("extendedLogs", extendedLogsEnabled()))
            }.exceptionOrNull()
            execute("backend_result") {
                if (backendSetup === connected) backendSetup = null
                if (client !== connected) return@execute
                backendError = if (failure == null) null else "camera_backend_unavailable"
                if (failure != null) CameraViewingDiagnostics.failure("backend_setup", "shared", failure)
                refresh()
                if (backendResetRequested) {
                    backendResetRequested = false
                    prepareBackend(connected, true)
                }
            }
        }
    }

    private fun accept(snapshot: JSONObject) {
        if (snapshot.has("mirrorApplyState")) {
            mirrorApplyError = if (snapshot.optString("mirrorApplyState") == "error")
                snapshot.optString("mirrorApplyError", "mirror_apply_failed") else null
        }
        val profiles = snapshot.optJSONArray("profiles") ?: return
        val editor = preferences.edit()
        for (i in 0 until profiles.length()) {
            val item = profiles.getJSONObject(i)
            val method = item.getString("transport")
            require(method in METHODS)
            val authorized = item.optBoolean("authorized")
            val pending = item.optBoolean("revocationPending")
            if (item.optLong("revocationEpoch") > preferences.getLong("${method}_revocationEpoch", 0)) {
                secrets.removePrefix("draft.camera.$method."); secrets.removePrefix("camera.$method.")
                secrets.removePrefix("tunnel.$method.")
                editor.putString("draft_${method}_name", item.optString("name"))
                    .putBoolean("draft_${method}_access_changed", false)
                    .putLong("${method}_revocationEpoch", item.optLong("revocationEpoch"))
            }
            snapshots[method] = JSONObject(item.toString())
            editor.putBoolean("${method}_authorized", authorized).putBoolean("${method}_revocationPending", pending)
                .putString("${method}_identity", item.nullable("identity"))
        }
        check(editor.commit())
        refresh()
    }

    fun runtimeStarted() = execute("runtime_started") {
        runtimeActive = true
        helperRecoveryAvailable = true
        initialAttachRecoveryPending = CameraChargeSettings.isRemoteViewingEnabled(context)
        deliverEnabled(++configDeliveryGeneration)
    }
    fun runtimeStopped() = execute("runtime_stopped") {
        runtimeActive = false; initialAttachRecoveryPending = false
        client?.close(); client = null; backendError = null
    }
    fun explicitShutdown() = explicitShutdown(Runnable {})
    fun explicitShutdown(onComplete: Runnable) = execute("shutdown") {
        configDeliveryGeneration++
        runtimeActive = false
        initialAttachRecoveryPending = false
        try { (client ?: if (preferences.getBoolean("helper_provisioned", false)) ensureClient(false) else null)
            ?.request("shutdown", JSONObject()) }
        finally { client?.close(); client = null; main.post(onComplete) }
    }
    fun workerReconfigured() = execute("worker_reconfigured") {
        if (!runtimeActive) return@execute
        val connected = client
        if (!CameraChargeSettings.isRemoteViewingEnabled(context)) deliverEnabled(++configDeliveryGeneration)
        else if (connected != null) prepareBackend(connected, true)
        else if (initialAttachRecoveryPending && runtimeActive && CameraChargeSettings.isRemoteViewingEnabled(context)) {
            // Existing camera/Motion readiness events give a failed initial attach one recovery attempt.
            initialAttachRecoveryPending = false
            CameraViewingDiagnostics.detail("initial_attach_recovery", "shared", "source=worker_reconfigured attempt=1")
            ensureClient()
        }
    }
    fun authorizationOpened(request: Long) = execute("authorization_opened") {
        client?.let { accept(it.request("authorization_opened", JSONObject().put("transport", selected()).put("request", request))) }
    }
    fun diagnosticSettingsChanged() = execute("diagnostic_settings") {
        val current = client
        val target = current ?: CameraViewingShellClient(context)
        try { target.configureDiagnosticsIfConnected(extendedLogsEnabled()) }
        finally { if (target !== current) target.close() }
    }
    fun revoke() = execute("revoke") { accept(ensureClient().request("revoke", JSONObject().put("transport", selected()))) }
    fun cancel() = execute("cancel") { accept(ensureClient().request("cancel", JSONObject().put("transport", selected()))) }

    private fun execute(operation: String = "state_update", method: String? = null, action: () -> Unit) {
        owner.execute {
            try { action() }
            catch (failure: Exception) {
                val provider = method ?: "shared"
                val knownSecrets = runCatching { (method?.let { listOf(it) } ?: METHODS)
                    .flatMap { listOf(secret(it, "password"), secret(it, "token")) }.toTypedArray() }
                    .getOrDefault(emptyArray())
                CameraViewingDiagnostics.failure("app_$operation", provider, failure, *knownSecrets)
                mutable.value = state.value.copy(busy = false, error = context.getString(
                    if (failure is SecurityException) R.string.cameras_error_secret else R.string.cameras_error_helper))
            }
        }
    }
    companion object {
        private val METHODS = listOf("vdo", "tailscale", "zrok")
        @Volatile private var instance: CameraViewingController? = null
        @JvmStatic fun get(context: Context): CameraViewingController = instance ?: synchronized(this) {
            instance ?: CameraViewingController(context.applicationContext).also { instance = it }
        }
        @JvmStatic fun hasAuthorizedProfiles(context: Context): Boolean {
            val p = context.getSharedPreferences("camera_viewing", Context.MODE_PRIVATE)
            return METHODS.any { p.getBoolean("${it}_authorized", false) || p.getBoolean("${it}_revocationPending", false) }
        }
        private fun JSONObject.nullable(name: String): String? = if (isNull(name)) null else optString(name).takeIf { it.isNotEmpty() }
        private fun errorResource(code: String) = when (code) {
            "invalid_name" -> R.string.cameras_error_name
            "credential_required" -> R.string.cameras_error_token_required
            "password_required" -> R.string.cameras_error_password_required
            "not_authorized", "login_required", "revoke_required" -> R.string.cameras_error_authorization_required
            "secure_store_error" -> R.string.cameras_error_secret
            "native_unavailable", "runtime_error", "invalid_provider" -> R.string.cameras_error_tunnel_runtime
            "invalid_port", "private_credential_missing", "invalid_request" -> R.string.cameras_error_local_viewer
            "tailscale_https_failed" -> R.string.cameras_error_tailscale_https
            "tailscale_funnel_failed" -> R.string.cameras_error_tailscale_funnel
            "tailscale_funnel_not_allowed" -> R.string.cameras_error_tailscale_funnel_permission
            "zrok_enable_failed" -> R.string.cameras_error_zrok_enable
            "zrok_share_failed" -> R.string.cameras_error_zrok_share
            "zrok_endpoint_failed" -> R.string.cameras_error_endpoint
            "zrok_identity_listener_failed" -> R.string.cameras_error_identity
            "zrok_environment_missing" -> R.string.cameras_error_environment
            "listener_failed" -> R.string.cameras_error_listener
            "tailscale_operation_timeout", "zrok_operation_timeout" -> R.string.cameras_error_timeout
            "tailscale_start_failed", "tailscale_up_failed", "tailscale_status_failed",
            "tailscale_local_client_failed", "tailscale_backend_stopped", "tailscale_backend_state_unknown" -> R.string.cameras_error_provider_start
            "camera_unavailable" -> R.string.cameras_error_camera_unavailable
            "camera_backend_unavailable", "helper_unavailable" -> R.string.cameras_error_helper
            "mirror_apply_failed" -> R.string.cameras_error_helper
            "encoder_unavailable" -> R.string.cameras_error_encoder_unavailable
            else -> R.string.cameras_error_connection
        }
    }
}
