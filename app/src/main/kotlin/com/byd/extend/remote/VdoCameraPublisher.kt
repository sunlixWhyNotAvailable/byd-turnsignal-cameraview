package com.byd.extend.remote

import android.content.Context
import android.util.Log
import android.view.Surface
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import ninja.vdo.sdk.PublisherPeerAdmission
import ninja.vdo.sdk.VdoNinja
import ninja.vdo.sdk.core.Crypto
import ninja.vdo.sdk.core.Protocol
import ninja.vdo.sdk.core.TurnMode
import ninja.vdo.sdk.core.VdoEvent
import ninja.vdo.sdk.core.VdoEventListener
import ninja.vdo.sdk.core.VdoEvents
import ninja.vdo.sdk.core.VdoNinjaOptions
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.EglBase
import org.webrtc.HardwareVideoEncoderFactory
import org.webrtc.PeerConnectionFactory
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCodecInfo
import org.webrtc.VideoCodecStatus
import org.webrtc.VideoEncoder
import org.webrtc.VideoEncoderFactory
import org.webrtc.VideoSink
import org.webrtc.VideoSource
import org.webrtc.VideoTrack

/** One authenticated VDO.Ninja publisher. Video is attached only after the owner passes its safety gate. */
internal class VdoCameraPublisher(
    context: Context,
    private val streamId: String,
    private val password: String,
    private val listener: Listener,
    private val acquireViewer: (sessionId: String) -> Boolean = { true },
    private val releaseViewer: (sessionId: String) -> Unit = {},
) : AutoCloseable {
    interface Listener {
        fun onReady(viewerUrl: String)
        fun onViewerConnected(sessionId: String)
        fun onViewerDisconnected(sessionId: String)
        fun onSessionError(sessionId: String, stage: String, failure: Throwable)
        fun onError(encoderUnavailable: Boolean, networkFailure: Boolean)
    }

    private data class Resources(
        val egl: EglBase,
        val factory: PeerConnectionFactory,
        val sdk: VdoNinja,
    )

    private data class VideoSession(
        val helper: SurfaceTextureHelper,
        val source: VideoSource,
        val track: VideoTrack,
        val surface: Surface,
    )

    private data class CloseResources(
        val sdk: VdoNinja?,
        val factory: PeerConnectionFactory?,
        val egl: EglBase?,
    )

    private val appContext = context.applicationContext
    private val lock = Any()
    private val opener = Executors.newSingleThreadExecutor { task ->
        Thread(task, "vdo-camera-publisher").apply { isDaemon = true }
    }
    private val eventExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "vdo-camera-events").apply { isDaemon = true }
    }

    @Volatile private var closed = false
    private var accessStopped = false
    private var startRequested = false
    private var opening = false
    private var ready = false
    private var readyCallbackPending = false
    private var candidateSessionId: String? = null
    private var acceptedSessionId: String? = null
    private val authenticatedSessions = LinkedHashSet<String>()
    private val connectedPending = LinkedHashSet<String>()
    private val connectedNotified = HashSet<String>()
    private var resources: Resources? = null
    private var video: VideoSession? = null
    private var sdkDisposedByClose = false

    private val publisherAdmission = object : PublisherPeerAdmission {
        override fun tryReserve(uuid: String, sessionId: String): Boolean = synchronized(lock) {
            if (closed || accessStopped || candidateSessionId != null) return@synchronized false
            candidateSessionId = sessionId
            true
        }

        override fun authenticate(sessionId: String): Boolean {
            val isCandidate = synchronized(lock) {
                !closed && !accessStopped && candidateSessionId == sessionId
            }
            if (!isCandidate) return false

            val acquired = try { acquireViewer(sessionId) } catch (failure: Throwable) {
                CameraViewingDiagnostics.failure("viewer_admission", "vdo", failure, password)
                throw failure
            }
            if (!acquired) return false

            var previous: String? = null
            val committed = synchronized(lock) {
                if (closed || accessStopped || candidateSessionId != sessionId) false
                else {
                    previous = acceptedSessionId
                    candidateSessionId = null
                    acceptedSessionId = sessionId
                    authenticatedSessions.add(sessionId)
                    true
                }
            }
            if (!committed) {
                releaseViewer(sessionId)
                return false
            }

            CameraViewingDiagnostics.detail(
                "vdo_owner_admission", "vdo",
                "session=${sessionHash(sessionId)} outcome=admitted replaced_session=${previous?.let(::sessionHash) ?: "none"}",
                password,
            )

            // The app callback can also schedule this exact-session close during lease transfer;
            // repeating it is harmless and cannot touch a newer peer generation.
            previous?.takeIf { it != sessionId }?.let { old ->
                resources?.sdk?.disconnectPublisherSession(old)
            }
            return true
        }

        override fun release(sessionId: String) {
            val shouldRelease = synchronized(lock) {
                val wasCandidate = candidateSessionId == sessionId
                if (wasCandidate) {
                    candidateSessionId = null
                }
                wasCandidate || sessionId in authenticatedSessions
            }
            if (shouldRelease) releaseViewer(sessionId)
        }
    }

    private val sdkListener = VdoEventListener(::handleEvent)

    fun start() {
        synchronized(lock) {
            if (closed || startRequested) return
            startRequested = true
            opening = true
        }
        try {
            opener.execute(::open)
        } catch (_: RejectedExecutionException) {
            synchronized(lock) { opening = false }
            notifyErrorUnlessClosed(encoderUnavailable = false)
        }
    }

    /** Returns the WebRTC input surface for the existing camera/GPU composer. */
    fun startVideo(): Surface = synchronized(lock) {
        check(!closed && !accessStopped && ready) { "VDO publisher is not ready" }
        check(acceptedSessionId != null) { "No authenticated viewer" }
        check(video == null) { "VDO video is already running" }
        val active = checkNotNull(resources) { "VDO publisher is unavailable" }

        val helper = SurfaceTextureHelper.create("vdo-camera-capture", active.egl.eglBaseContext)
            ?: error("SurfaceTextureHelper unavailable")
        var source: VideoSource? = null
        var track: VideoTrack? = null
        var surface: Surface? = null
        var started = false
        try {
            val videoSource = active.factory.createVideoSource(false)
            source = videoSource
            videoSource.setIsScreencast(false)
            videoSource.adaptOutputFormat(WIDTH, HEIGHT, FPS)
            helper.setTextureSize(WIDTH, HEIGHT)
            val observer = videoSource.capturerObserver
            observer.onCapturerStarted(true)
            started = true
            helper.startListening(VideoSink(observer::onFrameCaptured))
            val videoTrack = active.factory.createVideoTrack("byd-camera-video", videoSource)
            track = videoTrack
            val captureSurface = Surface(helper.surfaceTexture)
            surface = captureSurface
            active.sdk.addTrack(videoTrack)
            VideoSession(helper, videoSource, videoTrack, captureSurface).also { video = it }
            CameraViewingDiagnostics.detail(
                "vdo_video_source_attached", "vdo",
                "session=${sessionHash(checkNotNull(acceptedSessionId))} track=video",
                password,
            )
            captureSurface
        } catch (failure: Throwable) {
            cleanupAfterFailure(failure, "start_video_capturer_stop") {
                if (started) source?.capturerObserver?.onCapturerStopped()
            }
            cleanupAfterFailure(failure, "start_video_remove_track") { track?.let(active.sdk::removeTrack) }
            cleanupAfterFailure(failure, "start_video_helper_stop") { helper.stopListening() }
            cleanupAfterFailure(failure, "start_video_surface_release") { surface?.release() }
            cleanupAfterFailure(failure, "start_video_track_dispose") { track?.dispose() }
            cleanupAfterFailure(failure, "start_video_source_dispose") { source?.dispose() }
            cleanupAfterFailure(failure, "start_video_helper_dispose") { helper.dispose() }
            throw failure
        }
    }

    /** Revoke old camera frames before detaching a Surface still owned by the composer. */
    fun pauseVideo() {
        try {
            synchronized(lock) { video?.let { check(it.track.setEnabled(false)) { "VDO track disable failed" } } }
        } catch (failure: Throwable) {
            try { stopAccess() } catch (secondary: Throwable) { failure.addSuppressed(secondary) }
            throw failure
        }
    }

    /** Removes only the video track and capture resources; authenticated data peers stay connected. */
    fun stopVideo() {
        synchronized(lock) { stopVideoLocked() }
    }

    private fun stopVideoLocked() {
        video?.let { current ->
            video = null
            cleanup("stop_video_remove_track") { resources?.sdk?.removeTrack(current.track) }
            cleanup("stop_video_capturer_stop") { current.source.capturerObserver.onCapturerStopped() }
            cleanup("stop_video_helper_stop") { current.helper.stopListening() }
            cleanup("stop_video_surface_release") { current.surface.release() }
            cleanup("stop_video_track_dispose") { current.track.dispose() }
            cleanup("stop_video_source_dispose") { current.source.dispose() }
            cleanup("stop_video_helper_dispose") { current.helper.dispose() }
        }
    }

    /** Cut off viewers before detaching the camera, without releasing its still-attached Surface. */
    fun stopAccess() {
        var trackFailure: Throwable? = null
        val active = synchronized(lock) {
            accessStopped = true
            try { video?.track?.setEnabled(false) } catch (failure: Throwable) { trackFailure = failure }
            Triple(resources?.sdk, candidateSessionId, acceptedSessionId)
        }
        val (sdk, candidate, accepted) = active
        try {
            eventExecutor.execute {
                candidate?.let { sdk?.cancelPublisherSession(it) }
                accepted?.let { sdk?.disconnectPublisherSession(it) }
            }
        } catch (_: RejectedExecutionException) {
            // The publisher is already closing; close() disposes all remaining SDK sessions.
        }
        trackFailure?.let { throw it }
    }

    /** Schedules termination of the exact authenticated peer; the shared source remains untouched. */
    fun endSession(sessionId: String): Boolean {
        val sdk = synchronized(lock) {
            if (closed || sessionId !in authenticatedSessions) null else resources?.sdk
        } ?: return false
        return try {
            eventExecutor.execute { sdk.disconnectPublisherSession(sessionId) }
            true
        } catch (_: RejectedExecutionException) {
            false
        }
    }

    /** Runtime rechecks this on its owner thread immediately before replacing the viewer lease. */
    fun canAdmitSession(sessionId: String): Boolean = synchronized(lock) {
        !closed && !accessStopped && candidateSessionId == sessionId
    }

    override fun close() {
        val toClose = synchronized(lock) {
            if (closed) return
            closed = true
            stopVideoLocked()
            candidateSessionId = null
            acceptedSessionId = null
            authenticatedSessions.clear()
            connectedPending.clear()
            connectedNotified.clear()
            ready = false
            readyCallbackPending = false

            val active = resources
            resources = null
            val sdkToClose = active?.sdk
            if (sdkToClose != null) {
                sdkToClose.removeListener(sdkListener)
                sdkDisposedByClose = true
            }
            if (opening) {
                // connect() is blocking; disposing its signaling client wakes it. The opener releases
                // the factory and EGL resources after that call unwinds.
                CloseResources(sdkToClose, null, null)
            } else {
                CloseResources(sdkToClose, active?.factory, active?.egl)
            }
        }
        opener.shutdownNow()
        eventExecutor.shutdownNow()
        cleanup("publisher_close_sdk_dispose") { toClose.sdk?.dispose() }
        cleanup("publisher_close_factory_dispose") { toClose.factory?.dispose() }
        cleanup("publisher_close_egl_release") { toClose.egl?.release() }
    }

    private fun open() {
        var local: Resources? = null
        var eglOwned: EglBase? = null
        var factoryOwned: PeerConnectionFactory? = null
        var sdkOwned: VdoNinja? = null
        var published = false
        var completed = false
        try {
            check(!closed) { "Publisher closed" }
            val viewerUrl = CameraViewingEdits.vdoViewerUrl(streamId)
            require(password.isNotBlank()) { "Viewing password required" }

            ensureWebRtcInitialized(appContext)
            val egl = EglBase.create()
            eglOwned = egl
            val encoderFactory = HardwareH264EncoderFactory(egl.eglBaseContext) {
                notifyErrorUnlessClosed(encoderUnavailable = true)
            }
            if (encoderFactory.getSupportedCodecs().isEmpty()) throw HardwareEncoderUnavailable()
            val factory = PeerConnectionFactory.builder()
                // app_process is not an AMS application; the default monitor registers app receivers.
                .setOptions(PeerConnectionFactory.Options().apply { disableNetworkMonitor = true })
                .setVideoEncoderFactory(encoderFactory)
                .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
                .createPeerConnectionFactory()
            factoryOwned = factory
            val sdk = VdoNinja(
                appContext,
                VdoNinjaOptions(
                    password = password,
                    noPassword = false,
                    salt = Protocol.DEFAULT_SALT,
                    turnMode = TurnMode.AUTO,
                ),
                factory,
                egl,
                publisherAdmission,
            )
            sdkOwned = sdk
            local = Resources(egl, factory, sdk)
            sdk.addListener(sdkListener)

            synchronized(lock) {
                check(!closed) { "Publisher closed" }
                resources = local
                published = true
            }

            sdk.connect()
            check(!closed) { "Publisher closed" }
            val publishedId = sdk.announce(streamId)
            check(publishedId == streamId) { "Invalid stream name" }

            synchronized(lock) {
                check(!closed) { "Publisher closed" }
                ready = true
                readyCallbackPending = true
            }
            notifyReadyUnlessClosed(viewerUrl)
            val connected = synchronized(lock) {
                readyCallbackPending = false
                if (closed) emptyList() else connectedPending.toList().also {
                    connectedPending.clear()
                    connectedNotified.addAll(it)
                }
            }
            connected.forEach(::notifyViewerConnectedUnlessClosed)
            completed = true
        } catch (failure: HardwareEncoderUnavailable) {
            CameraViewingDiagnostics.failure("publisher_open", "vdo", failure, password)
            notifyErrorUnlessClosed(encoderUnavailable = true)
        } catch (failure: Exception) {
            CameraViewingDiagnostics.failure("publisher_open", "vdo", failure, password)
            notifyErrorUnlessClosed(encoderUnavailable = false, networkFailure =
                generateSequence<Throwable>(failure) { it.cause }.take(16).any {
                    it is java.net.SocketException || it is java.net.UnknownHostException || it is java.net.SocketTimeoutException
                })
        } finally {
            val disposeSdk: VdoNinja?
            val disposeFactory: PeerConnectionFactory?
            val disposeEgl: EglBase?
            synchronized(lock) {
                opening = false
                if (completed && !closed) {
                    disposeSdk = null
                    disposeFactory = null
                    disposeEgl = null
                } else {
                    val current = local
                    val stillOwnedHere = current != null && resources === current
                    if (stillOwnedHere) resources = null
                    disposeSdk = if (stillOwnedHere || !published && !sdkDisposedByClose)
                        current?.sdk ?: sdkOwned else null
                    disposeFactory = current?.factory ?: factoryOwned
                    disposeEgl = current?.egl ?: eglOwned
                }
            }
            disposeSdk?.let { sdk ->
                cleanup("publisher_open_sdk_remove_listener") { sdk.removeListener(sdkListener) }
                cleanup("publisher_open_sdk_dispose") { sdk.dispose() }
            }
            cleanup("publisher_open_factory_dispose") { disposeFactory?.dispose() }
            cleanup("publisher_open_egl_release") { disposeEgl?.release() }
        }
    }

    private fun handleEvent(event: VdoEvent) {
        if (closed) return
        try {
            eventExecutor.execute { handleEventSerially(event) }
        } catch (_: RejectedExecutionException) {
            // Close can race with the SDK's final connection callback.
        }
    }

    private fun handleEventSerially(event: VdoEvent) {
        if (closed) return
        when (event.type) {
            VdoEvents.PEER_CONNECTED -> {
                if (event.connectionType != "publisher") return
                val sessionId = event.sessionId?.takeIf(String::isNotBlank) ?: return
                val notifyViewer = synchronized(lock) {
                    if (closed || sessionId !in authenticatedSessions || sessionId in connectedNotified) return@synchronized false
                    if (ready && !readyCallbackPending) {
                        connectedNotified.add(sessionId)
                        true
                    } else {
                        connectedPending.add(sessionId)
                        false
                    }
                }
                if (notifyViewer) notifyViewerConnectedUnlessClosed(sessionId)
            }
            VdoEvents.PEER_DISCONNECTED -> {
                val sessionId = event.sessionId?.takeIf(String::isNotBlank) ?: return
                val notifyViewer = synchronized(lock) {
                    val wasAuthenticated = authenticatedSessions.remove(sessionId)
                    connectedPending.remove(sessionId)
                    connectedNotified.remove(sessionId)
                    if (acceptedSessionId == sessionId) acceptedSessionId = null
                    wasAuthenticated
                }
                if (notifyViewer) notifyViewerDisconnectedUnlessClosed(sessionId)
            }
            VdoEvents.CONNECTION_FAILED -> {
                if (event.connectionType != "publisher") return
                val sessionId = event.sessionId?.takeIf(String::isNotBlank) ?: return
                val stage = event.stage?.takeIf(String::isNotBlank) ?: "connection_failed"
                val failure = event.failure ?: IllegalStateException(event.message ?: "publisher session failed")
                CameraViewingDiagnostics.failure("vdo_${stage}_${sessionHash(sessionId)}", "vdo", failure, password)
                runCatching { listener.onSessionError(sessionId, stage, failure) }
            }
            VdoEvents.SESSION_DIAGNOSTIC -> {
                if (event.connectionType != "publisher") return
                val sessionId = event.sessionId?.takeIf(String::isNotBlank) ?: return
                val stage = event.stage?.takeIf(String::isNotBlank) ?: "session_diagnostic"
                val diagnosticStage = "vdo_${stage}_${sessionHash(sessionId)}"
                if (event.failure != null) {
                    CameraViewingDiagnostics.failure(diagnosticStage, "vdo", event.failure, password)
                } else {
                    CameraViewingDiagnostics.detail(diagnosticStage, "vdo", "session=${sessionHash(sessionId)} ${event.message.orEmpty()}", password)
                }
            }
            VdoEvents.DATA_CHANNEL_OPEN, VdoEvents.DATA_CHANNEL_CLOSE -> {
                if (event.connectionType != "publisher") return
                val sessionId = event.sessionId?.takeIf(String::isNotBlank) ?: return
                val stage = if (event.type == VdoEvents.DATA_CHANNEL_OPEN) {
                    "control_data_channel_open"
                } else {
                    "control_data_channel_closed"
                }
                CameraViewingDiagnostics.detail(
                    "vdo_${stage}_${sessionHash(sessionId)}", "vdo", "session=${sessionHash(sessionId)}", password,
                )
            }
            VdoEvents.RECONNECT_FAILED -> {
                val cause = event.failure ?: IllegalStateException("signaling reconnect exhausted")
                CameraViewingDiagnostics.failure("signaling_reconnect", "vdo",
                    cause, password)
                notifyErrorUnlessClosed(encoderUnavailable = false, networkFailure =
                    generateSequence(cause) { it.cause }.take(16).any {
                        it is java.net.SocketException || it is java.net.UnknownHostException ||
                            it is java.net.SocketTimeoutException
                    })
            }
            VdoEvents.ERROR -> CameraViewingDiagnostics.failure("sdk_event", "vdo",
                IllegalStateException(event.message ?: "SDK error without message"), password)
        }
    }

    fun networkChanged() {
        synchronized(lock) {
            if (!closed && !accessStopped) resources?.sdk?.networkChanged()
        }
    }

    private fun notifyReadyUnlessClosed(url: String) {
        if (!closed) runCatching { listener.onReady(url) }
    }

    private fun notifyViewerConnectedUnlessClosed(sessionId: String) {
        if (!closed) runCatching { listener.onViewerConnected(sessionId) }
    }

    private fun notifyViewerDisconnectedUnlessClosed(sessionId: String) {
        if (!closed) runCatching { listener.onViewerDisconnected(sessionId) }
    }

    private fun notifyErrorUnlessClosed(encoderUnavailable: Boolean, networkFailure: Boolean = false) {
        if (!closed) runCatching { listener.onError(encoderUnavailable, networkFailure) }
    }

    private fun sessionHash(sessionId: String): String = Crypto.generateHash(sessionId, 16)

    private fun cleanupAfterFailure(primary: Throwable, stage: String, action: () -> Unit) {
        try {
            action()
        } catch (cleanupFailure: Throwable) {
            if (cleanupFailure !== primary) primary.addSuppressed(cleanupFailure)
            CameraViewingDiagnostics.failure("vdo_$stage", "vdo", cleanupFailure, password)
        }
    }

    private fun cleanup(stage: String, action: () -> Unit) {
        try {
            action()
        } catch (failure: Throwable) {
            CameraViewingDiagnostics.failure("vdo_$stage", "vdo", failure, password)
        }
    }

    private class HardwareH264EncoderFactory(
        sharedContext: EglBase.Context,
        private val onUnavailable: () -> Unit,
    ) : VideoEncoderFactory {
        private val hardware = try {
            HardwareVideoEncoderFactory(sharedContext, false, false)
        } catch (failure: Exception) {
            CameraViewingDiagnostics.failure("encoder_factory", "vdo", failure)
            onUnavailable()
            throw HardwareEncoderUnavailable(failure)
        }
        private val h264Codecs = try {
            hardware.supportedCodecs
                .filter { it.name.equals("H264", ignoreCase = true) }
                .toTypedArray()
        } catch (failure: Exception) {
            CameraViewingDiagnostics.failure("encoder_codecs", "vdo", failure)
            onUnavailable()
            throw HardwareEncoderUnavailable(failure)
        }

        override fun getSupportedCodecs(): Array<VideoCodecInfo> = h264Codecs.copyOf()

        override fun createEncoder(codecInfo: VideoCodecInfo): VideoEncoder? {
            if (!codecInfo.name.equals("H264", ignoreCase = true) || h264Codecs.isEmpty()) return null
            val encoder = try { hardware.createEncoder(codecInfo) } catch (failure: Exception) {
                CameraViewingDiagnostics.failure("encoder_create", "vdo", failure); null
            }
            if (encoder == null) {
                onUnavailable()
                return null
            }
            return HardwareEncoder(encoder, onUnavailable)
        }
    }

    private class HardwareEncoder(
        private val delegate: VideoEncoder,
        private val onUnavailable: () -> Unit,
    ) : VideoEncoder by delegate {
        override fun initEncode(
            settings: VideoEncoder.Settings,
            callback: VideoEncoder.Callback,
        ): VideoCodecStatus {
            val result = try { delegate.initEncode(settings, callback) } catch (failure: Exception) {
                CameraViewingDiagnostics.failure("encoder_init", "vdo", failure)
                VideoCodecStatus.ERROR
            }
            if (result != VideoCodecStatus.OK) {
                Log.w("CameraViewing", "stage=encoder_init provider=vdo status=$result")
                onUnavailable()
            }
            return result
        }
    }

    private class HardwareEncoderUnavailable(cause: Throwable? = null) : Exception("Hardware H264 unavailable", cause)

    companion object {
        const val WIDTH = 1280
        const val HEIGHT = 720
        const val FPS = 15

        private val initializationLock = Any()
        @Volatile private var webRtcInitialized = false

        private fun ensureWebRtcInitialized(context: Context) {
            if (webRtcInitialized) return
            synchronized(initializationLock) {
                if (webRtcInitialized) return
                PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(context)
                        .setNativeLibraryLoader { name ->
                            try {
                                System.load(java.io.File(context.applicationInfo.nativeLibraryDir,
                                    "lib$name.so").absolutePath)
                                true
                            } catch (failure: UnsatisfiedLinkError) {
                                CameraViewingDiagnostics.failure("webrtc_library_load", "vdo", failure)
                                false
                            }
                        }.createInitializationOptions(),
                )
                webRtcInitialized = true
            }
        }
    }
}
