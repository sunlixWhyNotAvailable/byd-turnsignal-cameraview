package com.byd.extend.remote

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.SystemClock
import com.byd.extend.CameraChargePolicy
import com.byd.extend.CameraModeClient
import com.byd.extend.RemoteCameraPreparation
import com.byd.extend.RemoteCameraSource
import com.byd.extend.RemoteCameraView
import com.byd.extend.remote.security.EncryptedFileSecretStore
import com.byd.extend.remote.tunnel.CameraTunnelProvider
import com.byd.extend.remote.tunnel.CameraTunnelResult
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeoutException
import java.util.concurrent.TimeUnit
import java.util.function.Consumer

/** Independent shell-process owner. Only the local app IPC can change profiles. */
class CameraViewingRuntime(private val context: Context, directory: File, ownerUid: Int) : AutoCloseable {
    @Volatile private var ownerThread: Thread? = null
    private val owner = Executors.newSingleThreadScheduledExecutor {
        Thread(it, "viewing-owner").also { thread -> ownerThread = thread }
    }
    private val store = EncryptedFileSecretStore(File(directory, "profiles"), "com.byd.extend.$ownerUid")
    private val nativeDirectory = File(directory, "native")
    private val nativeLibrary = File(context.applicationInfo.nativeLibraryDir, "libcamera_tunnel.so")
    private val profiles = linkedMapOf<String, Profile>()
    private val lease = CameraViewerLease()
    @Volatile private var viewer: Viewer? = null
    private var source: RemoteCameraSource? = null
    private var sourceRun: Run? = null
    private var sourceAttached = false
    private val archiveDelegate = lazy { CameraViewerArchive.create(context, ownerUid) }
    private val archive by archiveDelegate
    private val modeSettings = CameraModeClient(context)
    private var lastModeReadFailure: String? = null
    @Volatile private var locale = "en"
    @Volatile private var theme = "dark"
    @Volatile private var interiorCameras = RemoteCameraView.NONE
    private var enabled = false
    @Volatile private var closed = false
    @Volatile private var latest = "{}"
    @Volatile private var listener: Consumer<JSONObject>? = null
    private var chargeObservation: AutoCloseable? = null
    private var networkRegistered = false
    private var activeNetwork: Network? = null
    @Volatile private var chargeSnapshot = ViewerBatteryStatus(null, 0L)
    private val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            CameraTunnelProvider.notifyNetworkChanged()
            execute {
                if (activeNetwork != network) {
                    activeNetwork = network
                    profiles.values.forEach { it.run?.publisher?.networkChanged() }
                }
            }
        }
        override fun onLost(network: Network) {
            CameraTunnelProvider.notifyNetworkChanged()
            execute { if (activeNetwork == network) activeNetwork = null }
        }
        override fun onCapabilitiesChanged(network: Network, capabilities: android.net.NetworkCapabilities) {
            if (!capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
                !capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return
            execute {
                if (activeNetwork != network || !enabled) return@execute
                profiles.values.filter { it.retryOnNetwork && it.run == null && it.authorized && !it.pendingRevoke }
                    .forEach { p ->
                        p.retryOnNetwork = false
                        try { connect(p, false) } catch (failure: Exception) { failed(p, failure) }
                    }
            }
        }
        override fun onLinkPropertiesChanged(network: Network, properties: android.net.LinkProperties) {
            CameraTunnelProvider.notifyNetworkChanged()
            execute {
                if (activeNetwork == network) profiles.values.forEach { it.run?.publisher?.networkChanged() }
            }
        }
    }

    private class Profile(val method: String, var config: JSONObject) {
        @Volatile var phase = "idle"
        var error: String? = null
        var url: String? = null
        var login: String? = null
        var authorizationRequest = 0L
        var run: Run? = null
        var generation = 0L
        var retryOnNetwork = false
        val authorized get() = config.optBoolean("authorized")
        val pendingRevoke get() = config.optBoolean("revocationPending")
    }
    private class Run(val generation: Long) {
        var accessMode = "public"
        var provider: CameraTunnelProvider? = null
        var publisher: VdoCameraPublisher? = null
        var server: CameraViewerServer? = null
        @Volatile var encoder: CameraHlsEncoder? = null
        var retiringEncoder: CameraHlsEncoder? = null
        var viewer = false
        @Volatile var video = false
        var preparing = false
        var deadline = 0L
        var interactive = false
        var preparationError: String? = null
        var outputView = Int.MIN_VALUE
        var outputInteriorMask = 0
        var outputLocale = ""
        var outputExteriorReady = false
    }
    private class Viewer(val profile: Profile, val run: Run, val session: String) {
        @Volatile var mode = "pending"
        @Volatile var direction = -1
        @Volatile var request = ""
        var firstLiveFrame = false
        var firstArchiveFrame = false
    }

    fun setListener(value: Consumer<JSONObject>?) { listener = value; value?.accept(snapshot()) }
    fun snapshot(): JSONObject = JSONObject(latest)

    fun request(operation: String, payload: JSONObject): JSONObject = try { owner.submit<JSONObject> {
        check(!closed)
        try { when (operation) {
            "provision" -> provision(payload)
            "configure" -> { presentation(payload); configure(payload.getBoolean("enabled")) }
            "authorize", "reconnect" -> {
                val profile = profile(payload)
                require(!profile.pendingRevoke) { "revocation_pending" }
                require(profile.phase !in setOf("authorizing", "starting", "approval_pending", "connecting")) {
                    "authorization_in_progress"
                }
                if (!profile.authorized) {
                    require(operation == "authorize") { "not_authorized" }
                    val config = payload.getJSONObject("profile")
                    validate(profile.method, config)
                    profile.config = JSONObject(config.toString()).put("authorized", false)
                        .put("revocationPending", false)
                        .put("viewerLocale", locale).put("viewerTheme", theme)
                        .put("revocationEpoch", profile.config.optLong("revocationEpoch"))
                    save(profile)
                } else {
                    val input = payload.getJSONObject("profile")
                    val updated = JSONObject(profile.config.toString())
                        .put("public", profile.method == "tailscale" && input.optBoolean("public"))
                        .put("password", input.getString("password"))
                    validate(profile.method, updated)
                    profile.config = updated
                    save(profile)
                }
                connect(profile, true)
            }
            "revoke" -> revoke(profile(payload))
            "cancel" -> profile(payload).let { p ->
                require(!p.pendingRevoke) { "revocation_pending" }
                stop(p); p.error = null
            }
            "reconfigured" -> {
                observeCharge()
                profiles.values.forEach { p -> p.run?.let { run ->
                if (run.viewer && payload.optBoolean("restartVideo", true)) {
                    closeVideo(); run.deadline = SystemClock.elapsedRealtime() + 30_000; prepare(p, run)
                }
                } }
            }
            "authorization_opened" -> {
                val p = profile(payload)
                if (p.authorizationRequest == payload.getLong("request")) p.authorizationRequest = 0
            }
            "shutdown" -> configure(false)
            else -> throw IllegalArgumentException("unsupported_operation")
        } } catch (failure: Exception) {
            val method = payload.optString("transport").takeIf { it in METHODS }
            val config = payload.optJSONObject("profile") ?: method?.let { profiles[it]?.config }
            val reference = SystemClock.elapsedRealtimeNanos().toString(36)
            CameraViewingDiagnostics.failure("request_${operation}_$reference", method ?: "shared", failure,
                config?.optString("password"), config?.optString("token"))
            if (method != null && operation in setOf("authorize", "reconnect", "cancel", "revoke") &&
                failure.message != "authorization_in_progress" && failure.message != "revocation_pending") {
                profiles[method]?.let { failed(it, failure) }
            }
            // The source has the complete redacted chain; never send credential-bearing messages over IPC.
            val message = "viewing request=$operation ref=$reference cause=${failure.javaClass.simpleName}"
            throw when (failure) {
                is SecurityException -> SecurityException(message)
                is IllegalArgumentException -> IllegalArgumentException(message)
                else -> IllegalStateException(message)
            }
        } finally { publish() }
        snapshot()
    }.get(30, TimeUnit.SECONDS)
    } catch (failure: ExecutionException) {
        throw (failure.cause as? RuntimeException ?: IllegalStateException("viewing_request_failed", failure.cause))
    } catch (failure: TimeoutException) {
        throw IllegalStateException("viewing_request_timeout", failure)
    } catch (failure: InterruptedException) {
        Thread.currentThread().interrupt()
        throw IllegalStateException("viewing_request_interrupted", failure)
    }

    private fun profile(payload: JSONObject) = checkNotNull(profiles[payload.getString("transport")])
    private fun save(p: Profile) = store.putNative("profile.${p.method}.config", p.config.toString().toByteArray())

    private fun provision(payload: JSONObject) {
        val entries = payload.getJSONArray("profiles")
        require(entries.length() == 3)
        for (i in 0 until entries.length()) {
            val entry = entries.getJSONObject(i)
            val method = entry.getString("transport")
            require(method in METHODS)
            if (profiles.containsKey(method)) continue
            val key = Base64.getDecoder().decode(entry.getString("key"))
            val source = entry.getJSONObject("migration")
            val migration = source.keys().asSequence().associateWith { Base64.getDecoder().decode(source.getString(it)) }
            try { store.provision(method, key, migration) }
            finally { key.fill(0); migration.values.forEach { it.fill(0) } }
            val record = store.getNative("profile.$method.config")
            val config = if (record != null) try { JSONObject(String(record, Charsets.UTF_8)) }
                finally { record.fill(0) }
            else JSONObject(entry.getJSONObject("initial").toString()).put("authorized", false)
                .put("revocationPending", false).put("legacyIdentity", migration.isNotEmpty() && method != "vdo")
            val p = Profile(method, config)
            if (record == null) save(p)
            profiles[method] = p
        }
        require(profiles.keys == METHODS.toSet())
        if (!networkRegistered) {
            connectivity.registerDefaultNetworkCallback(networkCallback)
            networkRegistered = true
        }
        presentation(payload)
        configure(payload.getBoolean("enabled"))
    }

    private fun presentation(payload: JSONObject) {
        val nextInterior = payload.optInt("interiorCameras", interiorCameras)
        RemoteCameraView.validateMask(nextInterior)
        val channelsChanged = nextInterior != interiorCameras
        interiorCameras = nextInterior
        // Invalidate captured/encoded private channels before applying the new live layout.
        val active = viewer?.takeIf { channelsChanged && it.mode == "live" }
        if (active != null) {
            active.direction = if (active.profile.method == "vdo") RemoteCameraView.defaultVdoView(interiorCameras)
                else RemoteCameraView.retainAllowed(active.direction, interiorCameras)
            active.request = java.util.UUID.randomUUID().toString()
            active.firstLiveFrame = false
            closeVideo()
        }
        locale = com.byd.extend.AppLanguage.normalize(payload.optString("locale",
            profiles.values.firstOrNull()?.config?.optString("viewerLocale", "en") ?: "en")).let {
            if (it == "zh-CN") "zh" else it
        }
        theme = if (payload.optString("theme",
                profiles.values.firstOrNull()?.config?.optString("viewerTheme", "dark") ?: "dark") == "light") "light" else "dark"
        profiles.values.forEach { p ->
            if (p.config.optString("viewerLocale") != locale || p.config.optString("viewerTheme") != theme) {
                p.config.put("viewerLocale", locale).put("viewerTheme", theme); save(p)
            }
        }
        if (active != null && enabled && payload.optBoolean("enabled", enabled)) {
            active.run.deadline = SystemClock.elapsedRealtime() + 30_000
            prepare(active.profile, active.run)
        }
    }

    private fun observeCharge() {
        chargeObservation?.close()
        chargeObservation = null
        chargeSnapshot = ViewerBatteryStatus(null, 0L)
        chargeObservation = RemoteCameraPreparation.observeCharge(context) { value ->
            chargeSnapshot = ViewerBatteryStatus(value, SystemClock.elapsedRealtime())
            execute { chargeChanged(value) }
        }
    }

    private fun configure(value: Boolean) {
        enabled = value
        for (p in profiles.values) {
            try {
                if (!enabled) stop(p)
                else if (!p.pendingRevoke && (p.authorized || p.config.optBoolean("legacyIdentity")) && p.run == null)
                    connect(p, false)
            } catch (failure: Exception) { failed(p, failure) }
        }
    }

    private fun validate(method: String, config: JSONObject) {
        val name = config.getString("name")
        val password = config.optString("password")
        val token = config.optString("token")
        val edits = CameraViewingEdits(method, name, password, token)
        require(name.isNotBlank() && edits.validatedName() == name) { "invalid_name" }
        edits.validateSecrets()
        require(method == "tailscale" && !config.optBoolean("public") || password.isNotBlank()) { "password_required" }
        require(method != "zrok" || token.isNotBlank()) { "credential_required" }
    }

    private fun connect(p: Profile, interactive: Boolean) {
        require(enabled) { "viewing_disabled" }
        validate(p.method, p.config)
        stop(p)
        val run = Run(++p.generation).also { p.run = it; it.interactive = interactive }
        p.error = null
        if (p.method == "vdo") { start(p, run); return }
        val provider = CameraTunnelProvider(context, p.method, p.config.getString("name"), store,
            nativeDirectory, nativeLibrary).also { run.provider = it }
        provider.observeFailures { result -> execute(p, run) { providerResult(p, run, result) } }
        p.phase = "authorizing"
        val accept: (CameraTunnelResult) -> Unit = { result -> execute(p, run) { providerResult(p, run, result) } }
        if (p.authorized || p.config.optBoolean("legacyIdentity")) provider.status(accept)
        else provider.authorize(p.config.optString("token").ifBlank { null }, accept)
    }

    private fun providerResult(p: Profile, run: Run, result: CameraTunnelResult) {
        result.errorCode?.let { throw RuntimeFailure(it,
            IllegalStateException(result.errorDetail ?: "native provider returned $it without detail"), result.networkFailure) }
        for (address in listOfNotNull(result.address, result.loginUrl)) {
            val uri = URI(address)
            require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null)
        }
        when (result.state) {
            "login_required", "starting", "approval_pending" -> {
                p.phase = if (result.state == "login_required") "authorizing" else result.state
                p.login = result.loginUrl
                if (run.interactive && p.login != null) {
                    p.authorizationRequest = SystemClock.elapsedRealtimeNanos(); run.interactive = false
                }
                owner.schedule({ execute(p, run) {
                    run.provider?.status { next -> execute(p, run) { providerResult(p, run, next) } }
                } }, 2, TimeUnit.SECONDS)
            }
            "authorized" -> {
                p.config.put("authorized", true).put("legacyIdentity", false)
                save(p); p.login = null; p.authorizationRequest = 0
                start(p, run)
            }
            "serving" -> { p.phase = "ready"; p.url = result.address; p.login = null }
            else -> throw RuntimeFailure("not_authorized")
        }
        publish()
    }

    private fun start(p: Profile, run: Run) {
        p.phase = "connecting"; p.login = null
        val accessMode = if (p.method == "tailscale") {
            if (p.config.optBoolean("public")) "funnel" else "serve"
        } else "public"
        run.accessMode = accessMode
        CameraViewingDiagnostics.detail("provider_start", p.method,
            "run=${run.generation} accessMode=$accessMode")
        if (p.method == "vdo") {
            run.publisher = VdoCameraPublisher(context, p.config.getString("name"), p.config.getString("password"),
                object : VdoCameraPublisher.Listener {
                    override fun onReady(viewerUrl: String) = execute(p, run) {
                        p.config.put("authorized", true); save(p)
                        p.url = viewerUrl; p.phase = "ready"
                    }
                    override fun onViewerConnected(sessionId: String) = execute(p, run) {
                        if (current(run, sessionId)) live(p, run, sessionId, RemoteCameraView.defaultVdoView(interiorCameras))
                    }
                    override fun onViewerDisconnected(sessionId: String) = execute(p, run) {
                        disconnected(p, run, sessionId)
                    }
                    override fun onSessionError(sessionId: String, stage: String, failure: Throwable) = execute(p, run) {
                        CameraViewingDiagnostics.failure("vdo_session_$stage", p.method, failure,
                            p.config.optString("password"))
                        disconnected(p, run, sessionId)
                    }
                    override fun onError(encoderUnavailable: Boolean, networkFailure: Boolean) = execute(p, run) {
                        throw RuntimeFailure(if (encoderUnavailable) "encoder_unavailable" else "connection_failed", networkFailure = networkFailure)
                    }
                }, { id -> serialized { admit(p, run, id) } }, { id ->
                    execute(p, run) { disconnected(p, run, id) }
                }).also { it.start() }
        } else {
            val privateProof = if (p.method == "tailscale" && !p.config.optBoolean("public")) randomProof() else null
            val server = CameraViewerServer(p.config.optString("password"), privateProof, object : CameraViewerServer.Stream {
                override fun admitted(session: String) = serialized { admit(p, run, session) }
                override fun current(session: String) = current(run, session)
                override fun disconnected(session: String, reason: String) = execute(p, run) { disconnected(p, run, session, reason) }
                override fun failed(failure: Throwable) = execute(p, run) {
                    throw RuntimeFailure("listener_failed", failure)
                }
                override fun live(session: String, direction: Int, request: String) = execute(p, run) {
                    if (current(run, session) && RemoteCameraView.allowed(direction, interiorCameras))
                        live(p, run, session, direction, request)
                }
                override fun interiorCameras() = interiorCameras
                override fun archive(session: String, leave: Boolean) = execute(p, run) {
                    if (current(run, session)) {
                        viewer!!.mode = if (leave) "pending" else "archive"
                        closeVideo(); p.phase = "ready"
                    }
                }
                override fun status(): JSONObject {
                    val active = viewer?.takeIf { it.run === run }
                    val request = active?.request ?: ""
                    val window = run.encoder?.window
                    val playable = run.video && window != null && window.request == request && window.isReady
                    val battery = chargeSnapshot.toJson(SystemClock.elapsedRealtime())
                    return JSONObject().put("status", if (p.phase == "connected" && !playable) "preparing" else p.phase)
                        .put("missingCharge", battery.getInt("missingMetrics"))
                        .put("battery", battery)
                        .put("mode", active?.mode ?: "pending")
                        .put("view", RemoteCameraView.name(active?.direction ?: RemoteCameraView.COMPOSITION))
                        .put("interiorCameras", interiorCameras)
                        .put("request", request)
                        .put("playlist", if (playable) window!!.playlistPath else "")
                        .put("modes", readModes())
                }
                override fun setMode(session: String, mode: String, enabled: Boolean): JSONObject? = serialized {
                    if (!current(run, session) || run.server?.canControl(session) != true) return@serialized null
                    modeSettings.setMode(mode, enabled).also {
                        viewer?.let { active -> sessionEvent("mode_setting_saved", active,
                            "mode=$mode enabled=$enabled applyState=${it.optString("applyState")}") }
                    }
                }
                override fun presentation() = JSONObject().put("locale", locale).put("theme", theme).put("provider", p.method)
                    .put("accessMode", accessMode).put("run", run.generation)
                override fun firstFrame(session: String, mode: String) = execute(p, run) {
                    val active = viewer?.takeIf { current(run, session) } ?: return@execute
                    if (mode == "live" && !active.firstLiveFrame || mode == "archive" && !active.firstArchiveFrame) {
                        if (mode == "live") active.firstLiveFrame = true else active.firstArchiveFrame = true
                        sessionEvent("first_displayed_frame", active, "mode=$mode")
                    }
                }
                override fun recordings() = archive
                override fun video() = run.encoder?.window?.takeIf { window ->
                    val active = viewer
                    run.video && active?.run === run && active.mode == "live" && window.request == active.request
                }
                override fun resource(name: String) = context.assets.open("camera-view/$name").use { it.readBytes() }
            })
            run.server = server
            run.provider!!.start(server.port(), p.method == "zrok" || p.config.optBoolean("public"), privateProof) { result ->
                execute(p, run) { providerResult(p, run, result) }
            }
        }
    }

    private fun current(run: Run, id: String): Boolean {
        val active = viewer ?: return false
        return active.run === run && active.session == id && lease.owns(active)
    }

    @Synchronized private fun readModes(): JSONObject = try {
        modeSettings.snapshot().also { lastModeReadFailure = null }
    } catch (failure: Exception) {
        val diagnostic = CameraViewingDiagnostics.describe(failure)
        if (diagnostic != lastModeReadFailure) {
            lastModeReadFailure = diagnostic
            CameraViewingDiagnostics.failure("viewer_modes_read", "shared", failure)
        }
        JSONObject().put("available", false).put("applyState", "error").put("error", "modes_unavailable")
    }

    /** Only called after HTTP credentials or VDO's protected SDP + connection succeed. */
    private fun admit(p: Profile, run: Run, id: String): Boolean {
        if (!enabled || p.run !== run || p.generation != run.generation
            || run.publisher?.canAdmitSession(id) == false
            || run.server?.commitAdmission(id) == false) return false
        if (current(run, id)) return true
        val next = Viewer(p, run, id)
        val previous = lease.replace(next) as? Viewer
        viewer = next
        if (previous != null) {
            previous.run.viewer = false
            previous.run.preparing = false
            previous.run.server?.supersede(previous.session)
            previous.run.publisher?.endSession(previous.session)
            previous.profile.phase = "ready"
            sessionEvent("replaced", previous, "next_provider=${p.method}")
        }
        run.viewer = true; run.deadline = SystemClock.elapsedRealtime() + 30_000
        p.phase = "ready"
        sessionEvent("admitted", next)
        // Archive-first viewers need telemetry too, without preparing any camera source.
        if (chargeObservation == null) try { observeCharge() }
        catch (failure: Exception) { CameraViewingDiagnostics.failure("viewer_charge_observe", p.method, failure) }
        publish()
        return true
    }

    private fun live(p: Profile, run: Run, id: String, direction: Int, request: String = "") {
        val active = viewer?.takeIf { current(run, id) } ?: return
        require(RemoteCameraView.allowed(direction, interiorCameras)) { "view_not_allowed" }
        p.phase = "preparing"
        active.mode = "live"; active.direction = direction; active.request = request
        active.firstLiveFrame = false
        run.deadline = SystemClock.elapsedRealtime() + 30_000
        prepare(p, run)
    }
    private fun prepare(p: Profile, run: Run) {
        try { prepareVideo(p, run) }
        catch (failure: Exception) { streamFailure(p, run, failure) }
    }
    private fun prepareVideo(p: Profile, run: Run) {
        val active = viewer?.takeIf { it.run === run && it.mode == "live" && lease.owns(it) } ?: return
        if (p.run !== run || !enabled || !run.viewer) return
        run.preparing = false
        val charge = try {
            if (chargeObservation == null) observeCharge()
            RemoteCameraPreparation.readChargeState(context).also {
                chargeSnapshot = ViewerBatteryStatus(it, SystemClock.elapsedRealtime())
            }
        } catch (failure: Exception) {
            val diagnostic = CameraViewingDiagnostics.describe(failure,
                p.config.optString("password"), p.config.optString("token"))
            if (run.preparationError != diagnostic) {
                run.preparationError = diagnostic
                CameraViewingDiagnostics.failure("charge_prepare", p.method, failure,
                    p.config.optString("password"), p.config.optString("token"))
            }
            if (SystemClock.elapsedRealtime() >= run.deadline) throw RuntimeFailure("camera_backend_unavailable", failure)
            retryPreparation(p, run); return
        }
        if (charge.isBlocked) { closeVideo(); p.phase = "low_battery"; return }
        p.phase = "preparing"
        val camera = source ?: newSource().also { source = it }
        val ready = try { camera.prepare(active.direction, interiorCameras) } catch (failure: Exception) {
            CameraViewingDiagnostics.failure("camera_prepare", p.method, failure)
            if (SystemClock.elapsedRealtime() >= run.deadline) throw RuntimeFailure("camera_unavailable", failure)
            false
        }
        if (!ready) { retryPreparation(p, run); return }
        if (run.video && sourceRun === run && sourceAttached &&
            (run.publisher != null || run.encoder?.window?.request == active.request)) {
            if (run.outputView != active.direction || run.outputInteriorMask != interiorCameras ||
                run.outputLocale != locale || run.outputExteriorReady != camera.isExteriorReady) {
                camera.updateOutput(null, active.direction, interiorCameras, locale)
                rememberOutput(run, active, camera)
            }
            p.phase = if (run.publisher != null || run.encoder?.window?.isReady == true) "connected" else "preparing"
            continueExteriorPreparation(p, run, active, camera)
            return
        }
        val previous = sourceRun
        try {
            check(run.retiringEncoder == null) { "Previous viewer encoder is not released" }
            run.retiringEncoder = run.encoder
            val surface = run.publisher?.startVideo() ?: CameraHlsEncoder(active.request, { event, frames, bytes ->
                execute(p, run) { viewer?.takeIf { it.run === run }?.let {
                    sessionEvent(event, it, "frames=$frames bytes=$bytes")
                } }
            }, { encoder -> execute(p, run) {
                if (sourceRun === run && run.encoder === encoder && run.video && viewer?.mode == "live" &&
                    encoder.window.request == viewer?.request) {
                    p.phase = "connected"
                    viewer?.let { sessionEvent("playable_media_ready", it) }
                }
            } }) { encoder, failure ->
                execute(p, run) { if (run.encoder === encoder) streamFailure(p, run, failure) }
            }.also { run.encoder = it }.start()
            if (sourceAttached) camera.updateOutput(surface, active.direction, interiorCameras, locale)
            else {
                camera.attach(surface, CameraHlsEncoder.WIDTH, CameraHlsEncoder.HEIGHT,
                    active.direction, interiorCameras, locale)
                sourceAttached = true
            }
            sourceRun = run
            rememberOutput(run, active, camera)
            if (previous != null && previous !== run) closeEncoder(previous)
            // Only the output queue is replaced; the OEM camera lease stays attached.
            run.retiringEncoder?.close(); run.retiringEncoder = null
            run.video = true
            p.phase = if (run.publisher != null || run.encoder?.window?.isReady == true) "connected" else "preparing"
            run.encoder?.let { encoder ->
                owner.schedule({ execute(p, run) {
                    if (run.encoder === encoder && run.video && !encoder.window.isReady)
                        streamFailure(p, run, RuntimeFailure("media_start_timeout"))
                } }, (run.deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0), TimeUnit.MILLISECONDS)
            }
            sessionEvent("source_ready", active, "view=${RemoteCameraView.name(active.direction)} interiorMask=$interiorCameras")
            continueExteriorPreparation(p, run, active, camera)
        } catch (failure: Exception) {
            // A timed-out GL change may still be queued. Detach before releasing either encoder.
            try { closeVideo(); if (run !== previous) closeEncoder(run) }
            catch (cleanup: Exception) { failure.addSuppressed(cleanup) }
            throw RuntimeFailure("encoder_unavailable", failure)
        }
    }

    private fun newSource(): RemoteCameraSource {
        lateinit var created: RemoteCameraSource
        created = RemoteCameraSource(context) { failure -> execute {
            if (source === created) viewer?.let { streamFailure(it.profile, it.run, failure) }
        } }
        return created
    }

    private fun rememberOutput(run: Run, active: Viewer, camera: RemoteCameraSource) {
        run.outputView = active.direction
        run.outputInteriorMask = interiorCameras
        run.outputLocale = locale
        run.outputExteriorReady = camera.isExteriorReady
    }

    private fun continueExteriorPreparation(p: Profile, run: Run, active: Viewer, camera: RemoteCameraSource) {
        if (active.direction != RemoteCameraView.COMBINED || camera.isExteriorReady) return
        if (SystemClock.elapsedRealtime() >= run.deadline) {
            sessionEvent("exterior_preparation_still_unavailable", active, "interior_stream_retained=true")
        } else if (!run.preparing) {
            run.preparing = true
            owner.schedule({ execute(p, run) { prepare(p, run) } }, 500, TimeUnit.MILLISECONDS)
        }
    }

    private fun streamFailure(p: Profile, run: Run, failure: Throwable) {
        CameraViewingDiagnostics.failure("viewer_stream", p.method, failure,
            p.config.optString("password"), p.config.optString("token"))
        if (viewer?.run !== run) return
        try { closeVideo() }
        catch (cleanup: Exception) {
            failure.addSuppressed(cleanup)
            CameraViewingDiagnostics.failure("viewer_stream_cleanup", p.method, cleanup)
        }
        p.phase = "error"; p.error = "camera_unavailable"
        viewer?.let { run.publisher?.endSession(it.session) }
    }
    private fun retryPreparation(p: Profile, run: Run) {
        if (SystemClock.elapsedRealtime() >= run.deadline) throw RuntimeFailure("camera_unavailable")
        if (!run.preparing) {
            run.preparing = true
            owner.schedule({ execute(p, run) { prepare(p, run) } }, 500, TimeUnit.MILLISECONDS)
        }
    }
    private fun chargeChanged(charge: CameraChargePolicy.State) {
        for (p in profiles.values) p.run?.let { run ->
            if (run.viewer) {
                if (charge.isBlocked && viewer?.mode == "live") { closeVideo(); p.phase = "low_battery" }
                else if (p.phase == "low_battery") { run.deadline = SystemClock.elapsedRealtime() + 30_000; prepare(p, run) }
            }
        }
    }
    private fun disconnected(p: Profile, run: Run, id: String, reason: String = "origin=vdo_disconnect") {
        val active = viewer?.takeIf { current(run, id) } ?: return
        if (reason.startsWith("origin=playback_error")) sessionEvent("browser_playback_error", active, reason)
        sessionEvent("ended", active, reason)
        run.viewer = false
        closeVideo()
        lease.release(active); viewer = null
        p.phase = "ready"
    }
    private fun closeVideo() {
        val camera = source
        sourceRun?.video = false
        var failure: Throwable? = null
        try { sourceRun?.publisher?.pauseVideo() }
        catch (pause: Throwable) { failure = pause }
        try { camera?.close(); source = null; sourceAttached = false }
        catch (detach: Throwable) {
            if (failure == null) failure = detach else if (failure !== detach) failure.addSuppressed(detach)
        }
        finally {
            if (camera == null || camera.isVideoDetached) {
                val previous = sourceRun
                sourceRun = null
                if (previous != null) try { closeEncoder(previous) }
                catch (cleanup: Throwable) {
                    if (failure == null) failure = cleanup else if (failure !== cleanup) failure.addSuppressed(cleanup)
                }
            }
        }
        failure?.let { throw it }
    }
    private fun closeEncoder(run: Run) {
        run.preparing = false
        var failure: Exception? = null
        try { run.publisher?.stopVideo() } catch (error: Exception) { failure = error }
        try { run.encoder?.close(); run.encoder = null }
        catch (error: Exception) { if (failure == null) failure = error else failure.addSuppressed(error) }
        try { run.retiringEncoder?.close(); run.retiringEncoder = null }
        catch (error: Exception) { if (failure == null) failure = error else failure.addSuppressed(error) }
        run.video = false
        failure?.let { throw it }
    }
    private fun stop(p: Profile) {
        p.retryOnNetwork = false
        val run = p.run
        p.generation++
        if (run != null) {
            val active = viewer
            if (active?.run === run || sourceRun === run) {
                closeVideo()
                if (active?.run === run) { lease.release(active); viewer = null }
            }
            run.publisher?.stopAccess()
            run.server?.close(); run.server = null
            run.provider?.close(); run.provider = null
            // Retain ownership on failed detach; admitting another camera owner would hide a leak.
            closeEncoder(run)
            p.run = null
            run.publisher?.close()
        }
        p.phase = "idle"; p.url = null; p.login = null; p.authorizationRequest = 0
    }

    private fun revoke(p: Profile) {
        p.config.put("revocationPending", true)
        save(p)
        stop(p)
        p.phase = "revoking"; p.error = null
        if (p.method == "vdo") { clearProfile(p); return }
        val run = Run(++p.generation).also { p.run = it }
        val provider = CameraTunnelProvider(context, p.method, p.config.getString("name"), store,
            nativeDirectory, nativeLibrary).also { run.provider = it }
        provider.revoke { result -> execute(p, run) {
            result.errorCode?.let { throw RuntimeFailure(it, IllegalStateException(result.errorDetail ?: it)) }
            stop(p); clearProfile(p)
        } }
    }
    private fun clearProfile(p: Profile) {
        p.config = JSONObject().put("name", p.config.optString("name")).put("password", "").put("token", "")
            .put("public", false).put("authorized", false).put("revocationPending", false)
            .put("revocationEpoch", p.config.optLong("revocationEpoch") + 1)
        save(p); p.phase = "idle"; p.error = null
    }
    private fun publish() {
        val entries = JSONArray()
        profiles.values.forEach { p -> entries.put(JSONObject().put("transport", p.method)
            .put("name", p.config.optString("name")).put("public", p.config.optBoolean("public"))
            .put("authorized", p.authorized).put("revocationPending", p.pendingRevoke)
            .put("revocationEpoch", p.config.optLong("revocationEpoch"))
            .put("identity", if (p.authorized || p.pendingRevoke) p.config.optString("name") else JSONObject.NULL)
            .put("phase", p.phase).put("error", p.error ?: JSONObject.NULL)
            .put("viewerUrl", p.url ?: JSONObject.NULL).put("authorizationUrl", p.login ?: JSONObject.NULL)
            .put("authorizationRequest", p.authorizationRequest)
            .put("passwordConfigured", p.config.optString("password").isNotEmpty())
            .put("tokenConfigured", p.config.optString("token").isNotEmpty())) }
        latest = JSONObject().put("enabled", enabled).put("interiorCameras", interiorCameras).put("profiles", entries).toString()
        listener?.accept(JSONObject(latest))
    }
    private fun execute(p: Profile? = null, run: Run? = null, action: () -> Unit) {
        if (closed) return
        owner.execute {
            if (closed || p != null && run != null && (p.run !== run || p.generation != run.generation)) return@execute
            try { action() }
            catch (failure: Exception) {
                failed(p, failure)
            }
            publish()
        }
    }

    private fun <T> serialized(action: () -> T): T {
        if (Thread.currentThread() === ownerThread) return action()
        val pending = owner.submit<T> { action() }
        return try { pending.get(30, TimeUnit.SECONDS) }
        catch (failure: ExecutionException) { throw (failure.cause ?: failure) }
        catch (failure: TimeoutException) {
            pending.cancel(false)
            throw failure
        } catch (failure: InterruptedException) {
            pending.cancel(false)
            Thread.currentThread().interrupt()
            throw failure
        }
    }

    private fun sessionEvent(event: String, active: Viewer, extra: String = "") {
        // The authentication token is never logged; this digest correlates only diagnostics.
        val reference = java.security.MessageDigest.getInstance("SHA-256").digest(active.session.toByteArray())
            .take(6).joinToString("") { "%02x".format(it.toInt() and 255) }
        CameraViewingDiagnostics.detail(event, active.profile.method,
            "session=$reference run=${active.run.generation} accessMode=${active.run.accessMode} $extra")
    }
    private fun failed(p: Profile?, failure: Exception) {
        val code = ((failure as? RuntimeFailure)?.code ?: if (failure is SecurityException)
            "secure_store_error" else "connection_failed")
            .takeIf { it.matches(Regex("[a-z_]{1,64}")) } ?: "connection_failed"
        CameraViewingDiagnostics.failure("runtime_$code", p?.method ?: "shared", failure,
            p?.config?.optString("password"), p?.config?.optString("token"))
        if (p != null) {
            try { stop(p) } catch (cleanup: Exception) {
                CameraViewingDiagnostics.failure("cleanup_after_$code", p.method, cleanup,
                    p.config.optString("password"), p.config.optString("token"))
            }
            p.phase = if (p.pendingRevoke) "revoke_pending" else "error"
            p.error = code
            val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
            val offlineTimeout = code in setOf("tailscale_operation_timeout", "zrok_operation_timeout") &&
                capabilities?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED) != true
            p.retryOnNetwork = enabled && p.authorized && !p.pendingRevoke &&
                ((failure as? RuntimeFailure)?.networkFailure == true || offlineTimeout)
        }
    }
    override fun close() {
        if (closed) return
        owner.submit {
            configure(false)
            chargeObservation?.close(); chargeObservation = null
            if (archiveDelegate.isInitialized()) archive.close()
            modeSettings.close()
            if (networkRegistered) connectivity.unregisterNetworkCallback(networkCallback)
            networkRegistered = false; closed = true
        }.get(30, TimeUnit.SECONDS)
        owner.shutdown()
    }
    private class RuntimeFailure(val code: String, cause: Throwable? = null,
        val networkFailure: Boolean = false) : Exception(code, cause)
    companion object {
        private val METHODS = listOf("vdo", "tailscale", "zrok")
        private fun randomProof() = ByteArray(32).also(SecureRandom()::nextBytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
