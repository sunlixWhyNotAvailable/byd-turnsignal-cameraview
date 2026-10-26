package com.byd.extend.remote

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.view.Surface
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.muxer.BufferInfo
import androidx.media3.muxer.FragmentedMp4Muxer
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/** A viewer owns this encoder. No files, audio, software fallback or network writes on its drain thread. */
internal class CameraHlsEncoder(
    streamId: String,
    private val diagnostics: (String, Long, Long) -> Unit,
    private val ready: (CameraHlsEncoder) -> Unit,
    private val failed: (CameraHlsEncoder, Throwable) -> Unit,
) : AutoCloseable {
    val window = FragmentedVideoWindow(streamId) { ready(this) }
    private val stopped = AtomicBoolean()
    private var codec: MediaCodec? = null
    private var input: Surface? = null
    private var drain: Thread? = null
    private var muxer: FragmentedMp4Muxer? = null
    private var track = -1
    private var firstPts = -1L
    private var lastPts = -1L
    private var previousPts = -1L
    private var frames = 0L
    private var bytes = 0L
    private var lastStatisticsNs = 0L

    fun start(): Surface {
        check(codec == null)
        val selected = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull { info ->
            info.isEncoder && info.supportedTypes.any { it.equals("video/avc", true) } &&
                hardware(info) && info.getCapabilitiesForType("video/avc").colorFormats
                    .contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface) &&
                info.getCapabilitiesForType("video/avc").videoCapabilities
                    .areSizeAndRateSupported(WIDTH, HEIGHT, FPS.toDouble())
        } ?: throw IllegalStateException("hardware_h264_unavailable")
        try {
            val encoder = MediaCodec.createByCodecName(selected.name)
            codec = encoder
            val format = MediaFormat.createVideoFormat("video/avc", WIDTH, HEIGHT).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, 2_000_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
                if (Build.VERSION.SDK_INT >= 29) setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            }
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = encoder.createInputSurface()
            input = surface
            encoder.start()
            drain = Thread({ drain(encoder) }, "camera-view-encode").also { it.start() }
            return surface
        } catch (error: Throwable) {
            try { close() } catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
            throw error
        }
    }

    private fun drain(encoder: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        try {
            while (!stopped.get()) {
                when (val index = encoder.dequeueOutputBuffer(info, 20_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        check(muxer == null) { "Unexpected H.264 format change" }
                        val output = encoder.outputFormat
                        val csd = listOf("csd-0", "csd-1").mapNotNull { key ->
                            output.getByteBuffer(key)?.duplicate()?.let { data ->
                                ByteArray(data.remaining()).also(data::get)
                            }
                        }
                        check(csd.isNotEmpty()) { "Missing H.264 configuration" }
                        muxer = FragmentedMp4Muxer.Builder(window).setFragmentDurationMs(2_000).build()
                        track = muxer!!.addTrack(Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264)
                            .setWidth(WIDTH).setHeight(HEIGHT).setInitializationData(csd).build())
                    }
                    else -> if (index >= 0) {
                        try {
                            if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                val output = encoder.getOutputBuffer(index) ?: error("Missing H.264 output")
                                output.position(info.offset); output.limit(info.offset + info.size)
                                val active = muxer ?: error("H.264 payload before format")
                                if (firstPts < 0) firstPts = info.presentationTimeUs
                                val pts = info.presentationTimeUs - firstPts
                                val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                                active.writeSampleData(track, output.slice(),
                                    BufferInfo(pts, info.size, if (key) C.BUFFER_FLAG_KEY_FRAME else 0))
                                previousPts = lastPts
                                lastPts = pts
                                frames++; bytes += info.size
                                val now = android.os.SystemClock.elapsedRealtimeNanos()
                                if (frames == 1L || now - lastStatisticsNs >= 10_000_000_000L) {
                                    lastStatisticsNs = now
                                    diagnostics(if (frames == 1L) "first_encoded_frame" else "encoder_statistics", frames, bytes)
                                }
                            }
                        } finally { encoder.releaseOutputBuffer(index, false) }
                    }
                }
            }
        } catch (error: Throwable) { if (!stopped.get()) failed(this, error) }
        finally {
            runCatching {
                muxer?.let { finishMuxer(it, track, lastPts, previousPts) }
            }.onFailure { CameraViewingDiagnostics.failure("hls_muxer_close", "shared", it) }
            muxer = null
        }
    }

    /** Caller detaches its camera Surface before closing the codec. */
    override fun close() {
        stopped.set(true)
        val worker = drain
        if (worker != null && worker !== Thread.currentThread()) worker.join(3_000)
        check(worker == null || worker === Thread.currentThread() || !worker.isAlive) {
            "Video encoder has not finished draining"
        }
        drain = null
        val encoder = codec
        codec = null
        try { encoder?.stop() } finally {
            try { encoder?.release() } finally { input?.release(); input = null; window.clear() }
        }
    }

    companion object {
        const val WIDTH = 1280
        const val HEIGHT = 720
        const val FPS = 15
        fun finishMuxer(muxer: FragmentedMp4Muxer, track: Int, lastPts: Long, previousPts: Long) {
            var failure: Throwable? = null
            try {
                if (lastPts >= 0) {
                    // A one-frame final fragment has no duration unless Media3 receives an EOS timestamp.
                    val duration = if (previousPts >= 0 && lastPts > previousPts) lastPts - previousPts
                        else 1_000_000L / FPS
                    muxer.writeSampleData(track, ByteBuffer.allocate(0),
                        BufferInfo(lastPts + duration, 0, C.BUFFER_FLAG_END_OF_STREAM))
                }
            } catch (error: Throwable) { failure = error }
            try { muxer.close() } catch (cleanup: Throwable) {
                if (failure == null) failure = cleanup
                else if (failure !== cleanup) failure.addSuppressed(cleanup)
            }
            failure?.let { throw it }
        }

        private fun hardware(info: MediaCodecInfo): Boolean =
            if (Build.VERSION.SDK_INT >= 29) info.isHardwareAccelerated && !info.isSoftwareOnly
            else listOf("OMX.qcom.", "OMX.hisi.", "OMX.Exynos.", "OMX.MTK.")
                .any { info.name.startsWith(it, ignoreCase = true) }
    }
}
