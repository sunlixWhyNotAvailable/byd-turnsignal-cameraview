package com.byd.extend;

import android.Manifest;
import android.media.AudioManager;
import android.media.AudioRecordingConfiguration;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import android.view.Surface;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Surface-input H.264 recorder with bounded keyframe-aware pre-roll and event post-roll. */
public final class MotionVideoRecorder implements AutoCloseable {
    private static final String TAG = "MotionVideoRecorder";
    private static final int VIDEO_TRACK = 0;
    private static final int AUDIO_TRACK = 1;
    private static final long MAX_RING_AGE_MS = 32_000L;
    private static final long MAX_RING_BYTES = 40L * 1024L * 1024L;
    private static final long DETECTION_MERGE_GAP_MS = 1_000L;
    private static final long MARKER_PERSIST_INTERVAL_MS = 1_000L;
    private static final int MAX_PENDING_DETECTIONS = 64;
    private static final long AUDIO_FORMAT_WAIT_MS = 1_500L;
    private static final int AUDIO_SAMPLE_RATE = 16_000;
    private static final int AUDIO_CHANNEL_COUNT = 1;

    static final class InputSurfaceLease {
        private boolean retained;
        private boolean releaseStarted;
        private boolean releaseAcknowledged;

        synchronized void resetForNextRecording() {
            if (retained && !releaseAcknowledged)
                throw new IllegalStateException("Motion encoder surface release is still pending");
            retained = false;
            releaseStarted = false;
            releaseAcknowledged = false;
        }

        synchronized void retain() {
            if (releaseStarted || releaseAcknowledged)
                throw new IllegalStateException("Motion encoder surface release has started");
            if (retained) throw new IllegalStateException("Motion encoder surface is already retained");
            retained = true;
        }

        synchronized boolean beginRelease() {
            releaseStarted = true;
            return retained && !releaseAcknowledged;
        }

        synchronized void acknowledgeRelease() {
            releaseStarted = true;
            releaseAcknowledged = true;
            notifyAll();
        }

        void awaitReleaseAcknowledged() {
            boolean interrupted = false;
            synchronized (this) {
                while (retained && !releaseAcknowledged) {
                    try { wait(); }
                    catch (InterruptedException ignored) { interrupted = true; }
                }
            }
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private final Context context;
    private final MotionShellMediaContext shellMediaContext;
    private final String sessionId;
    private final Consumer<String> stateCallback;
    private final MotionMediaLibrary library;
    private final BlockingQueue<Detection> detections = new ArrayBlockingQueue<>(256);
    private final BlockingQueue<EncodedPacket> audioPackets = new ArrayBlockingQueue<>(512);
    private final ArrayDeque<EncodedPacket> videoPackets = new ArrayDeque<>();
    private long queuedVideoBytes;
    private final ArrayList<EncodedPacket> ring = new ArrayList<>(); // Only encoded samples; hard byte/time bounded.
    private final ArrayDeque<Detection> pendingDetections = new ArrayDeque<>();
    private final ArrayList<TimelineItem> iteration = new ArrayList<>();
    private final InputSurfaceLease inputSurfaceLease = new InputSurfaceLease();

    private volatile boolean buffering;
    private volatile boolean recording;
    private volatile boolean stopRequested;
    private volatile boolean audioRunning;
    private volatile boolean audioFailed;
    private volatile AudioRecord audioRecord;
    private volatile MediaFormat audioFormat;
    private volatile Thread workerThread;
    private volatile Thread audioThread;
    private volatile Thread videoDrainThread;
    private volatile boolean videoFailed;
    private volatile MediaCodec videoEncoder;
    private volatile Surface inputSurface;
    private final FinishState finishState = new FinishState();
    private volatile String stopReason;

    private MotionConfig config;
    private MotionMediaIndex index;
    private MotionStorage storage;
    private volatile MediaFormat videoFormat;
    private Clip activeClip;
    private long ringBytes;
    private long latestRingElapsedUs = Long.MIN_VALUE;
    private long finishDeadlineMs = Long.MIN_VALUE;
    private long outputRestartUntilElapsedMs = Long.MIN_VALUE;
    private String outputRestartVolumeId;
    private MotionFrameTimestamp frameTimestamp;
    private long eventWindowStartMs = Long.MIN_VALUE;
    private long mergeCloseDeadlineMs = Long.MIN_VALUE;
    private long chainLastVideoElapsedUs = Long.MIN_VALUE;
    private long chainLastAudioElapsedUs = Long.MIN_VALUE;
    private boolean bufferedTailUnavailable;
    private boolean mergeWaitAbandoned;
    private boolean finishRequested;
    private long videoPtsOffsetUs = Long.MIN_VALUE;
    private long audioPtsOffsetUs = Long.MIN_VALUE;
    private volatile long lastVideoElapsedUs = Long.MIN_VALUE;
    private long lastAudioElapsedUs = Long.MIN_VALUE;
    private final HashMap<Integer, Episode> episodesByLevel = new HashMap<>();
    private volatile boolean videoEos;
    private boolean closed;
    private boolean firstVideoSampleLogged;
    private boolean firstKeyframeLogged;
    private boolean firstDetectionLogged;
    private boolean checkup;
    private boolean checkupStarted;
    private volatile boolean checkupSavedAndDeleted;
    private volatile String checkupFailure;

    public MotionVideoRecorder(Context context, Consumer<String> stateCallback) {
        this.context = context.getApplicationContext();
        this.shellMediaContext = null;
        this.sessionId = "app";
        this.stateCallback = stateCallback == null ? ignored -> { } : stateCallback;
        this.library = new MotionMediaLibrary(this.context);
        this.index = library.index();
        this.storage = library.storage();
    }

    public MotionVideoRecorder(MotionShellMediaContext shellMediaContext, String sessionId,
            Consumer<String> stateCallback) {
        this(shellMediaContext, null, sessionId, stateCallback);
    }

    MotionVideoRecorder(MotionShellMediaContext shellMediaContext, MotionMediaLibrary workerLibrary,
            String sessionId, Consumer<String> stateCallback) {
        if (shellMediaContext == null) throw new IllegalArgumentException("Motion shell media context is required");
        this.context = shellMediaContext.systemContext();
        this.shellMediaContext = shellMediaContext;
        this.sessionId = sessionId == null || sessionId.isEmpty() ? "unknown" : sessionId;
        this.stateCallback = stateCallback == null ? ignored -> { } : stateCallback;
        this.library = workerLibrary == null ? new MotionMediaLibrary(shellMediaContext) : workerLibrary;
        this.index = library.index();
        this.storage = library.storage();
    }

    /** Starts the video encoder; callers must retain its input before handing it to the composer. */
    public void startBuffering(MotionConfig requestedConfig) {
        startBuffering(requestedConfig, false);
    }

    void startBuffering(MotionConfig requestedConfig, boolean temporaryCheck) {
        synchronized (this) {
            if (closed) throw new IllegalStateException("MotionVideoRecorder is closed");
            if (buffering) return;
            MotionConfig next = requestedConfig == null ? MotionConfig.read(context) : requestedConfig;
            checkup = temporaryCheck;
            checkupStarted = false;
            checkupSavedAndDeleted = false;
            checkupFailure = null;
            if (!next.recordingEnabled && !checkup) {
                notifyState("recording_disabled");
                return;
            }
            if (shellMediaContext != null && !storage.hasMotionStoragePermission()) {
                notifyState("motion_storage_permission_denied");
                return;
            }
            if (next.audioEnabled && !hasRecordAudioPermission()) {
                audioFailed = true;
                notifyState("audio_unavailable:app_or_shell_permission_denied");
                return;
            }
            inputSurfaceLease.resetForNextRecording();
            config = next;
            storage = library.storage();
            index = library.index();
            ring.clear();
            pendingDetections.clear();
            detections.clear();
            audioPackets.clear();
            synchronized (videoPackets) {
                videoPackets.clear();
                queuedVideoBytes = 0;
            }
            ringBytes = 0;
            latestRingElapsedUs = Long.MIN_VALUE;
            finishDeadlineMs = Long.MIN_VALUE;
            eventWindowStartMs = Long.MIN_VALUE;
            mergeCloseDeadlineMs = Long.MIN_VALUE;
            chainLastVideoElapsedUs = Long.MIN_VALUE;
            chainLastAudioElapsedUs = Long.MIN_VALUE;
            bufferedTailUnavailable = false;
            mergeWaitAbandoned = false;
            finishRequested = false;
            outputRestartUntilElapsedMs = Long.MIN_VALUE;
            outputRestartVolumeId = null;
            finishState.reset();
            videoPtsOffsetUs = Long.MIN_VALUE;
            audioPtsOffsetUs = Long.MIN_VALUE;
            lastVideoElapsedUs = Long.MIN_VALUE;
            lastAudioElapsedUs = Long.MIN_VALUE;
            episodesByLevel.clear();
            videoFormat = null;
            audioFormat = null;
            audioFailed = false;
            firstVideoSampleLogged = false;
            firstKeyframeLogged = false;
            firstDetectionLogged = false;
            videoEos = false;
            videoFailed = false;
            stopRequested = false;
            stopReason = null;
            frameTimestamp = MotionFrameTimestamp.capture();
            MediaCodec encoder = null;
            Surface surface = null;
            try {
                encoder = createHardwareAvcEncoder(next.width(), next.height(), next.bitrate());
                MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC,
                        next.width(), next.height());
                format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
                format.setInteger(MediaFormat.KEY_BIT_RATE, next.bitrate());
                format.setInteger(MediaFormat.KEY_FRAME_RATE, 25);
                format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
                encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                surface = encoder.createInputSurface();
                encoder.start();
                videoEncoder = encoder;
                inputSurface = surface;
                buffering = true;
                if (next.audioEnabled) startAudioCapture();
                Thread drain = new Thread(this::runVideoDrain, "motion-video-drain");
                videoDrainThread = drain;
                drain.start();
                Thread worker = new Thread(this::runVideoLoop, "motion-video-recorder");
                workerThread = worker;
                worker.start();
                logDiagnostic("encoder_started", "size=" + next.width() + "x" + next.height()
                        + " audio=" + next.audioEnabled);
            } catch (IOException | RuntimeException failure) {
                if (surface != null) try { surface.release(); } catch (RuntimeException ignored) { }
                if (encoder != null) {
                    try { encoder.stop(); } catch (RuntimeException ignored) { }
                    try { encoder.release(); } catch (RuntimeException ignored) { }
                }
                inputSurface = null;
                videoEncoder = null;
                buffering = false;
                String detail = failure.getMessage();
                notifyState("video_encoder_unavailable:" + (detail == null || detail.isEmpty()
                        ? failure.getClass().getSimpleName() : detail));
            }
        }
    }

    public Surface inputSurface() { return inputSurface; }

    /** Retains the encoder input until the camera owner and composer acknowledge detach. */
    public Surface retainInputSurface() {
        synchronized (this) {
            synchronized (inputSurfaceLease) {
                Surface surface = inputSurface;
                if (closed || !buffering || surface == null)
                    throw new IllegalStateException("Motion encoder input is unavailable");
                inputSurfaceLease.retain();
                return surface;
            }
        }
    }

    /** Acknowledges that camera and composer no longer reference the retained encoder input. */
    public void releaseInputSurface() { inputSurfaceLease.acknowledgeRelease(); }
    public int width() { return config == null ? MotionConfig.read(context).width() : config.width(); }
    public int height() { return config == null ? MotionConfig.read(context).height() : config.height(); }
    MotionFrameTimestamp frameTimestamp() { return frameTimestamp; }

    /** Records an event level 1 (yellow) or 2 (red), timestamped on elapsedRealtime. */
    public void onDetection(int level, long elapsedMs) {
        if (!buffering || stopRequested || (level != 1 && level != 2) || elapsedMs < 0) return;
        if (!firstDetectionLogged) {
            firstDetectionLogged = true;
            logDiagnostic("first_detection", "level=" + level + " elapsed_ms=" + elapsedMs);
        }
        if (!detections.offer(new Detection(level, elapsedMs))) notifyState("motion_detection_queue_full");
    }

    /** Extends or sets the current absolute elapsedRealtime deadline; event post-roll is never shortened. */
    public void finishAfter(long deadlineMs) {
        if (!buffering || stopRequested || deadlineMs < 0) return;
        finishState.requestFinish(deadlineMs);
    }

    /** Reopens event acceptance on this recorder unless terminal stop has already begun. */
    void resumeAfterFinish() {
        if (!buffering || stopRequested) return;
        finishState.requestResume();
    }

    /** Stops accepting events immediately; Surface release and codec drain complete asynchronously. */
    public synchronized void requestStop(String reason) {
        if (!buffering) return;
        if (!videoFailed && !stopRequested) stopReason = reason == null ? "stopped" : reason;
        stopRequested = true;
        audioRunning = false;
    }

    /** Stops gracefully, finalizing and publishing any complete muxed segments. */
    public void stop(String reason) {
        Thread worker;
        synchronized (this) {
            if (!buffering) return;
            requestStop(reason);
            AudioRecord recorder = audioRecord;
            if (recorder != null) {
                try { recorder.stop(); } catch (RuntimeException ignored) { }
            }
            worker = workerThread;
        }
        if (worker != null && worker != Thread.currentThread()) {
            try {
                worker.join(5_000L);
                if (worker.isAlive()) {
                    logDiagnostic("video_drain_timeout", "interrupting_worker=true");
                    worker.interrupt();
                    worker.join(1_500L);
                }
                if (worker.isAlive()) logDiagnostic("video_worker_still_alive", "reason=" + stopReason);
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    public boolean isBuffering() { return buffering; }
    public boolean isRecording() { return recording; }
    boolean checkupSavedAndDeleted() { return checkupSavedAndDeleted && checkupFailure == null; }
    String checkupDetail() {
        return checkupFailure != null ? checkupFailure : checkupSavedAndDeleted
                ? "Three-second clip saved and deleted" : "Temporary recording did not finish: " + stopReason;
    }

    private static MediaCodec createHardwareAvcEncoder(int width, int height, int bitrate) throws IOException {
        try {
            MediaCodecInfo.class.getMethod("isHardwareAccelerated");
            MediaCodecInfo.class.getMethod("isSoftwareOnly");
        } catch (NoSuchMethodException missingApi) {
            throw new IOException("hardware_encoder_classification_unavailable: "
                    + missingApi.getMessage(), missingApi);
        }
        MediaCodecList codecs = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
        String lastFailure = "no_hardware_avc_encoder_supports_requested_size_rate";
        for (MediaCodecInfo info : codecs.getCodecInfos()) {
            if (!info.isEncoder() || !info.isHardwareAccelerated() || info.isSoftwareOnly()) continue;
            boolean supportsAvc = false;
            for (String type : info.getSupportedTypes()) {
                if (MediaFormat.MIMETYPE_VIDEO_AVC.equalsIgnoreCase(type)) {
                    supportsAvc = true;
                    break;
                }
            }
            if (!supportsAvc) continue;
            try {
                MediaCodecInfo.CodecCapabilities capabilities = info.getCapabilitiesForType(
                        MediaFormat.MIMETYPE_VIDEO_AVC);
                MediaCodecInfo.VideoCapabilities video = capabilities.getVideoCapabilities();
                if (video == null || !video.isSizeSupported(width, height)
                        || !video.areSizeAndRateSupported(width, height, 25.0)) continue;
                return MediaCodec.createByCodecName(info.getName());
            } catch (IOException | RuntimeException failure) {
                // Keep scanning other hardware encoders; none are accepted without actual format support.
                lastFailure = "hardware_avc_encoder_unavailable:" + failure.getClass().getSimpleName();
            }
        }
        throw new IOException(lastFailure);
    }

    @Override public void close() {
        synchronized (this) { closed = true; }
        stop("closed");
    }

    private void startAudioCapture() {
        if (!hasRecordAudioPermission()) {
            audioFailed = true;
            requestStopForAudioPermissionRevocation();
            notifyState("audio_unavailable:app_or_shell_permission_denied");
            return;
        }
        audioRunning = true;
        Thread thread = new Thread(this::runAudioCapture, "motion-audio-capture");
        audioThread = thread;
        thread.start();
    }

    private void runAudioCapture() {
        AudioRecord recorder = null;
        MediaCodec encoder = null;
        boolean started = false;
        long basePtsUs = 0;
        long sampleCount = 0;
        try {
            assertAudioCaptureAvailable(-1);
            int minimum = AudioRecord.getMinBufferSize(AUDIO_SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (minimum <= 0) throw new IOException("audio_format_unavailable");
            AudioRecord.Builder builder = new AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.MIC)
                    .setAudioFormat(new AudioFormat.Builder()
                            .setSampleRate(AUDIO_SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                    .setBufferSizeInBytes(Math.max(minimum * 2, 6_400));
            if (shellMediaContext != null && Build.VERSION.SDK_INT >= 31) {
                // app_process defaults to package android, which does not belong to shell UID2000.
                try {
                    AudioRecord.Builder.class.getMethod("setContext", Context.class)
                            .invoke(builder, context);
                } catch (ReflectiveOperationException failure) {
                    throw new IOException("audio_record_context_failed", failure);
                }
            }
            recorder = builder.build();
            audioRecord = recorder;
            if (recorder.getState() != AudioRecord.STATE_INITIALIZED)
                throw new IOException("audio_record_not_initialized");
            MediaFormat format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC,
                    AUDIO_SAMPLE_RATE, AUDIO_CHANNEL_COUNT);
            format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            format.setInteger(MediaFormat.KEY_BIT_RATE, 32_000);
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 4_096);
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encoder.start();
            assertAudioCaptureAvailable(-1);
            recorder.startRecording();
            if (recorder.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING)
                throw new IOException("audio_record_start_failed");
            started = true;
            assertAudioCaptureAvailable(recorder.getAudioSessionId());
            basePtsUs = SystemClock.elapsedRealtimeNanos() / 1_000L;
            byte[] pcm = new byte[2_048];
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            while (audioRunning) {
                assertAudioCaptureAvailable(recorder.getAudioSessionId());
                int count = recorder.read(pcm, 0, pcm.length, AudioRecord.READ_BLOCKING);
                // Do not encode bytes returned by a read that overlapped permission revocation.
                assertAudioCaptureAvailable(recorder.getAudioSessionId());
                if (count < 0) throw new IOException("audio_capture_read_failed_" + count);
                if (count == 0) continue;
                if ((count & 1) != 0) count--;
                if (count <= 0) continue;
                int inputIndex;
                do {
                    inputIndex = encoder.dequeueInputBuffer(10_000);
                    drainAudio(encoder, info);
                } while (inputIndex < 0 && audioRunning);
                if (inputIndex < 0) break;
                ByteBuffer input = encoder.getInputBuffer(inputIndex);
                if (input == null) throw new IOException("audio_encoder_input_unavailable");
                input.clear();
                input.put(pcm, 0, count);
                long ptsUs = basePtsUs + sampleCount * 1_000_000L / AUDIO_SAMPLE_RATE;
                encoder.queueInputBuffer(inputIndex, 0, count, ptsUs, 0);
                sampleCount += count / 2L;
                drainAudio(encoder, info);
            }
            if (started) {
                int inputIndex = encoder.dequeueInputBuffer(50_000);
                if (inputIndex >= 0) {
                    encoder.queueInputBuffer(inputIndex, 0, 0,
                            basePtsUs + sampleCount * 1_000_000L / AUDIO_SAMPLE_RATE,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                }
                long deadline = SystemClock.elapsedRealtime() + 750L;
                while (SystemClock.elapsedRealtime() < deadline && drainAudio(encoder, info)) {
                    if (!audioRunning && audioPackets.isEmpty()) break;
                }
            }
        } catch (IOException | RuntimeException failure) {
            if ("record_audio_permission_revoked".equals(failure.getMessage())
                    || failure instanceof SecurityException || !hasRecordAudioPermission()) {
                audioFailed = true;
                audioRunning = false;
                requestStopForAudioPermissionRevocation();
                logDiagnostic("audio_permission_revoked", "recording_stop_requested=true");
                notifyState("record_audio_permission_revoked");
            } else if (audioRunning) {
                audioFailed = true;
                Log.w(TAG, "motion_audio_capture_failed", failure);
                String detail = failure.getMessage();
                notifyState("audio_unavailable:" + (detail == null || detail.isEmpty()
                        ? failure.getClass().getSimpleName() : detail));
            }
        } finally {
            audioRunning = false;
            audioRecord = null;
            if (recorder != null) {
                try { recorder.stop(); } catch (RuntimeException ignored) { }
                try { recorder.release(); } catch (RuntimeException ignored) { }
            }
            if (encoder != null) {
                try { encoder.stop(); } catch (RuntimeException ignored) { }
                try { encoder.release(); } catch (RuntimeException ignored) { }
            }
        }
    }

    /** Refuse or stop optional microphone capture when calls, AVAS, or another client owns input. */
    private void assertAudioCaptureAvailable(int ownAudioSessionId) throws IOException {
        if (!hasRecordAudioPermission()) throw new IOException("record_audio_permission_revoked");
        AudioManager audio = context.getSystemService(AudioManager.class);
        if (audio == null) throw new IOException("audio_manager_unavailable");
        try {
            if (audio.getMode() != AudioManager.MODE_NORMAL)
                throw new IOException("audio_in_use_by_call_or_communication");
            List<AudioRecordingConfiguration> active = audio.getActiveRecordingConfigurations();
            if (active == null) return;
            for (AudioRecordingConfiguration configuration : active) {
                if (ownAudioSessionId <= 0
                        || configuration.getClientAudioSessionId() != ownAudioSessionId) {
                    throw new IOException("audio_in_use_by_other_client");
                }
            }
        } catch (SecurityException | IllegalStateException unavailable) {
            throw new IOException("audio_state_unavailable", unavailable);
        }
    }

    private boolean hasRecordAudioPermission() {
        if (shellMediaContext != null) return shellMediaContext.hasRecordAudioPermission();
        return Build.VERSION.SDK_INT < 23
                || context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                        == PackageManager.PERMISSION_GRANTED;
    }

    private void requestStopForAudioPermissionRevocation() {
        stopReason = "record_audio_permission_revoked";
        stopRequested = true;
    }

    private boolean drainAudio(MediaCodec encoder, MediaCodec.BufferInfo info) {
        boolean any = false;
        while (true) {
            int outputIndex = encoder.dequeueOutputBuffer(info, 0);
            if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) return any;
            if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                audioFormat = encoder.getOutputFormat();
                continue;
            }
            if (outputIndex < 0) continue;
            any = true;
            ByteBuffer output = encoder.getOutputBuffer(outputIndex);
            try {
                if (output != null && info.size > 0
                        && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                    long elapsedUs = mapAudioPts(info.presentationTimeUs);
                    if (elapsedUs > lastAudioElapsedUs) {
                        byte[] bytes = copyBytes(output, info.offset, info.size);
                        audioPackets.offer(new EncodedPacket(AUDIO_TRACK, elapsedUs, info.flags, bytes));
                        lastAudioElapsedUs = elapsedUs;
                    }
                }
            } finally {
                encoder.releaseOutputBuffer(outputIndex, false);
            }
            if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return any;
        }
    }

    private void runVideoLoop() {
        long nextPermissionCheckMs = SystemClock.elapsedRealtime();
        try {
            while (!stopRequested) {
                long now = SystemClock.elapsedRealtime();
                applyFinishState();
                if (shellMediaContext != null && now >= nextPermissionCheckMs) {
                    nextPermissionCheckMs = now + 1_000L;
                    if (!storage.hasMotionStoragePermission()) {
                        stopReason = "motion_storage_permission_revoked";
                        stopRequested = true;
                        logDiagnostic("storage_permission_revoked", "recording_stop_requested=true");
                        continue;
                    }
                }
                collectTimelineItems();
                processTimelineItems();
                applyFinishState();
                tryOpenPendingDetection();
                trimRing();
                applyFinishState();
                if (activeClip != null) {
                    long finalizeNowMs = SystemClock.elapsedRealtime();
                    boolean postRollComplete = MotionRecorderTimeline.postRollRecorded(
                            activeClip.lastVideoElapsedMs, finishDeadlineMs);
                    recording = !postRollComplete;
                    boolean mergeWaitActive = !finishRequested && !checkup
                            && !mergeWaitAbandoned
                            && eventWindowStartMs != Long.MIN_VALUE
                            && mergeCloseDeadlineMs != Long.MIN_VALUE;
                    boolean mergeHorizonExpired = !finishRequested && !checkup
                            && mergeCloseDeadlineMs != Long.MIN_VALUE
                            && finalizeNowMs >= mergeCloseDeadlineMs;
                    boolean requestedFinishExpired = finishRequested
                            && finishDeadlineMs != Long.MIN_VALUE && finalizeNowMs >= finishDeadlineMs;
                    boolean finalizeAfterPostRoll = postRollComplete && (!mergeWaitActive || checkup
                            || finishRequested || bufferedTailUnavailable || mergeHorizonExpired);
                    if (mergeHorizonExpired || requestedFinishExpired || finalizeAfterPostRoll)
                        closeActiveClip();
                }
                expireOutputRestart(SystemClock.elapsedRealtime());
                Thread.sleep(5L);
            }
            stopAudioCapture();
            joinVideoDrain();
            joinAudioThread();
            collectTimelineItems();
            processTimelineItems();
            if (activeClip != null) closeActiveClip();
        } catch (InterruptedException interrupted) {
            if (!stopRequested) {
                stopReason = "video_worker_interrupted";
                Thread.currentThread().interrupt();
            }
            if (activeClip != null) closeActiveClip();
        } catch (RuntimeException failure) {
            failVideo("video_encoder_failed", failure);
            if (activeClip != null) closeActiveClip();
        } finally {
            stopRequested = true;
            stopAudioCapture();
            joinVideoDrain();
            try { joinAudioThread(); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            if (inputSurfaceLease.beginRelease()) {
                String reason = stopReason == null || stopReason.isEmpty() ? "encoder_ended" : stopReason;
                logDiagnostic("input_surface_release_wait", "reason=" + reason);
                notifyState("input_release_required:" + reason);
                inputSurfaceLease.awaitReleaseAcknowledged();
            }
            MediaCodec encoder = videoEncoder;
            videoEncoder = null;
            if (encoder != null) {
                try { encoder.stop(); } catch (RuntimeException ignored) { }
                try { encoder.release(); } catch (RuntimeException ignored) { }
            }
            Surface surface;
            synchronized (inputSurfaceLease) {
                surface = inputSurface;
                inputSurface = null;
            }
            if (surface != null) try { surface.release(); } catch (RuntimeException ignored) { }
            recording = false;
            workerThread = null;
            buffering = false;
            notifyState("stopped:" + (stopReason == null ? "encoder_ended" : stopReason));
        }
    }

    private void joinVideoDrain() {
        Thread drain = videoDrainThread;
        if (drain == null || drain == Thread.currentThread()) return;
        boolean interrupted = false;
        while (drain.isAlive()) {
            try { drain.join(); }
            catch (InterruptedException ignored) { interrupted = true; }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    private void runVideoDrain() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        try {
            while (!stopRequested && !videoEos) {
                drainVideoPackets(info);
                if (!stopRequested) Thread.sleep(5L);
            }
            if (videoEos && !stopRequested)
                failVideo("video_encoder_ended", new IllegalStateException("Unexpected video end of stream"));
            if (!videoFailed) {
                signalVideoEndOfStream();
                long until = SystemClock.elapsedRealtime() + 1_500L;
                while (!videoFailed && !videoEos && SystemClock.elapsedRealtime() < until) {
                    drainVideoPackets(info);
                    Thread.sleep(5L);
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException failure) {
            failVideo("video_encoder_read_failed", failure);
        }
    }

    private void failVideo(String reason, RuntimeException failure) {
        if (videoFailed) return;
        videoFailed = true;
        stopReason = reason;
        stopRequested = true;
        Log.e(TAG, reason, failure);
        logDiagnostic("video_encoder_failed", "reason=" + reason + " error=" + failure);
        notifyState("video_encoder_failed:" + reason);
    }

    private void joinAudioThread() throws InterruptedException {
        Thread audio = audioThread;
        if (audio == null || audio == Thread.currentThread()) return;
        audio.join(2_000L);
        if (audio.isAlive()) {
            audio.interrupt();
            audio.join(1_000L);
        }
        if (audio.isAlive()) logDiagnostic("audio_worker_still_alive", "stop_requested=" + stopRequested);
    }

    private void drainVideoPackets(MediaCodec.BufferInfo info) {
        MediaCodec encoder = videoEncoder;
        if (encoder != null) {
            for (int drained = 0; drained < 64 && !videoFailed && !videoEos; drained++) {
                int outputIndex;
                try { outputIndex = encoder.dequeueOutputBuffer(info, 0); }
                catch (RuntimeException failure) {
                    failVideo("video_encoder_read_failed", failure);
                    break;
                }
                if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) break;
                if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    videoFormat = encoder.getOutputFormat();
                    continue;
                }
                if (outputIndex < 0) continue;
                try {
                    ByteBuffer output = encoder.getOutputBuffer(outputIndex);
                    if (output != null && info.size > 0
                            && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        long elapsedUs = mapVideoPts(info.presentationTimeUs);
                        if (elapsedUs > lastVideoElapsedUs) {
                            if (!firstVideoSampleLogged) {
                                firstVideoSampleLogged = true;
                                logDiagnostic("first_video_sample", "pts_us=" + elapsedUs
                                        + " flags=" + info.flags + " bytes=" + info.size);
                            }
                            if (!firstKeyframeLogged
                                    && (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0) {
                                firstKeyframeLogged = true;
                                logDiagnostic("first_video_keyframe", "pts_us=" + elapsedUs
                                        + " bytes=" + info.size);
                            }
                            byte[] bytes = copyBytes(output, info.offset, info.size);
                            synchronized (videoPackets) {
                                if (queuedVideoBytes + bytes.length > MAX_RING_BYTES) {
                                    failVideo("video_writer_queue_full",
                                            new IllegalStateException("Motion storage is not keeping up with video"));
                                    return;
                                }
                                videoPackets.addLast(new EncodedPacket(VIDEO_TRACK, elapsedUs, info.flags, bytes));
                                queuedVideoBytes += bytes.length;
                            }
                            lastVideoElapsedUs = elapsedUs;
                        }
                    }
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) videoEos = true;
                } finally {
                    encoder.releaseOutputBuffer(outputIndex, false);
                }
            }
        }
    }

    private void collectTimelineItems() {
        iteration.clear();
        synchronized (videoPackets) {
            EncodedPacket video;
            while ((video = videoPackets.pollFirst()) != null) iteration.add(TimelineItem.packet(video));
            queuedVideoBytes = 0;
        }
        EncodedPacket audio;
        while ((audio = audioPackets.poll()) != null) iteration.add(TimelineItem.packet(audio));
        Detection detection;
        while ((detection = detections.poll()) != null) iteration.add(TimelineItem.detection(detection));
    }

    private void processTimelineItems() {
        iteration.sort(Comparator.comparingLong(TimelineItem::elapsedUs)
                .thenComparingInt(item -> item.detection == null ? 0 : 1));
        for (TimelineItem item : iteration) {
            applyFinishState();
            if (item.detection != null) {
                if (!stopRequested) processDetection(item.detection);
            }
            else handlePacket(item.packet);
        }
    }

    private void processDetection(Detection detection) {
        if (finishRequested || checkup) {
            updateEpisode(detection);
            if (activeClip != null && detection.elapsedMs >= activeClip.startElapsedMs)
                persistEpisode(activeClip, detection);
            else persistMarkerInFinishedClip(detection);
            return;
        }
        long detectionStartMs = alignedEventWindowStart(detection.elapsedMs);
        long detectionEndMs = MotionRecorderTimeline.finishDeadline(
                detection.elapsedMs, config.afterSeconds);
        long activeCoverageEndMs = activeClip == null ? Long.MIN_VALUE
                : Math.max(finishDeadlineMs, activeClip.lastVideoElapsedMs);
        boolean overlapsActive = activeClip != null && eventWindowStartMs != Long.MIN_VALUE
                && !mergeWaitAbandoned
                && MotionRecorderTimeline.windowsOverlap(eventWindowStartMs, activeCoverageEndMs,
                        detectionStartMs, detectionEndMs);
        if (activeClip != null && overlapsActive && bufferedTailUnavailable) {
            updateEpisode(detection);
            queuePendingDetection(detection);
            mergeWaitAbandoned = true;
            return;
        }
        if (activeClip != null && !overlapsActive) {
            updateEpisode(detection);
            queuePendingDetection(detection);
            mergeWaitAbandoned = true;
            if (MotionRecorderTimeline.postRollRecorded(activeClip.lastVideoElapsedMs, finishDeadlineMs))
                closeActiveClip();
            return;
        }
        updateEpisode(detection);
        if (activeClip != null) {
            if (detection.latestElapsedMs >= activeClip.startElapsedMs) persistEpisode(activeClip, detection);
            else persistMarkerInFinishedClip(detection);
            long previousDeadlineMs = finishDeadlineMs;
            extendFinishDeadline(detectionEndMs);
            eventWindowStartMs = Math.min(eventWindowStartMs, detectionStartMs);
            mergeCloseDeadlineMs = MotionRecorderTimeline.mergeCloseDeadline(
                    finishDeadlineMs, config.beforeSeconds);
            if (finishDeadlineMs > previousDeadlineMs) appendBufferedTailThroughDeadline();
            recording = !MotionRecorderTimeline.postRollRecorded(
                    activeClip == null ? Long.MIN_VALUE : activeClip.lastVideoElapsedMs, finishDeadlineMs);
            return;
        }
        if (persistMarkerInFinishedClip(detection)) return;
        queuePendingDetection(detection);
    }

    private void queuePendingDetection(Detection detection) {
        Detection pending = pendingDetections.peekLast();
        if (pending != null && pending.eventId.equals(detection.eventId)) pending.mergeLatest(detection);
        else {
            if (pendingDetections.size() >= MAX_PENDING_DETECTIONS) {
                pendingDetections.removeFirst();
                notifyState("motion_pending_event_queue_full");
            }
            pendingDetections.addLast(detection);
        }
    }

    private void tryOpenPendingDetection() {
        if (stopRequested || activeClip != null || pendingDetections.isEmpty() || videoFormat == null) return;
        Detection first = pendingDetections.peekFirst();
        long nowMs = SystemClock.elapsedRealtime();
        boolean audioRequested = config.audioEnabled && !audioFailed;
        if (audioRequested && audioFormat == null && nowMs - first.elapsedMs < AUDIO_FORMAT_WAIT_MS) return;
        long firstEventStartMs = first.elapsedMs;
        long firstEventEndMs = Math.max(firstEventStartMs, first.latestElapsedMs);
        long targetMs = MotionRecorderTimeline.eventWindowStart(firstEventStartMs, config.beforeSeconds);
        ArrayList<Long> keyframes = new ArrayList<>();
        for (EncodedPacket packet : ring) {
            if (packet.track == VIDEO_TRACK && packet.isKeyFrame()) keyframes.add(packet.elapsedUs / 1_000L);
        }
        long startMs = MotionRecorderTimeline.chooseKeyframeStart(keyframes, targetMs, firstEventStartMs);
        if (startMs < 0) {
            if (nowMs - first.elapsedMs < 2_000L) return;
            pendingDetections.removeFirst();
            notifyState("motion_no_keyframe_before_event");
            return;
        }
        long finishAt = MotionRecorderTimeline.finishDeadline(firstEventEndMs, config.afterSeconds);
        List<EncodedPacket> initialPackets = ringPacketsThroughDeadline(ringPacketsFrom(startMs), finishAt);
        finishDeadlineMs = finishAt;
        eventWindowStartMs = startMs;
        mergeCloseDeadlineMs = MotionRecorderTimeline.mergeCloseDeadline(finishAt, config.beforeSeconds);
        chainLastVideoElapsedUs = Long.MIN_VALUE;
        chainLastAudioElapsedUs = Long.MIN_VALUE;
        bufferedTailUnavailable = false;
        mergeWaitAbandoned = false;
        long futureMs = Math.max(0, finishAt - nowMs);
        long requiredBytes = estimateSegmentBytes(initialPackets, futureMs,
                config.audioEnabled && !audioFailed && audioFormat != null);
        String firstAttemptVolumeId = outputRestartActive(nowMs) ? outputRestartVolumeId : null;
        if (!openSegment(startMs, initialPackets, requiredBytes, firstAttemptVolumeId)) {
            pendingDetections.removeFirst();
            clearEventWindow();
            return;
        }
        pendingDetections.removeFirst();
        persistInitialEpisode(activeClip, first);
        while (!pendingDetections.isEmpty() && activeClip != null) {
            Detection queued = pendingDetections.peekFirst();
            long queuedStartMs = alignedEventWindowStart(queued.elapsedMs);
            long queuedEndMs = MotionRecorderTimeline.finishDeadline(
                    queued.latestElapsedMs, config.afterSeconds);
            if (!MotionRecorderTimeline.windowsOverlap(eventWindowStartMs, finishDeadlineMs,
                    queuedStartMs, queuedEndMs)) break;
            pendingDetections.removeFirst();
            long previousDeadlineMs = finishDeadlineMs;
            extendFinishDeadline(queuedEndMs);
            eventWindowStartMs = Math.min(eventWindowStartMs, queuedStartMs);
            mergeCloseDeadlineMs = MotionRecorderTimeline.mergeCloseDeadline(
                    finishDeadlineMs, config.beforeSeconds);
            if (finishDeadlineMs > previousDeadlineMs) appendBufferedTailThroughDeadline();
            persistEpisode(activeClip, queued);
        }
        recording = activeClip != null && !MotionRecorderTimeline.postRollRecorded(
                activeClip.lastVideoElapsedMs, finishDeadlineMs);
    }

    private long alignedEventWindowStart(long eventElapsedMs) {
        long requestedStartMs = MotionRecorderTimeline.eventWindowStart(eventElapsedMs,
                config.beforeSeconds);
        ArrayList<Long> keyframes = new ArrayList<>();
        for (EncodedPacket packet : ring) {
            if (packet.track == VIDEO_TRACK && packet.isKeyFrame())
                keyframes.add(packet.elapsedUs / 1_000L);
        }
        long aligned = MotionRecorderTimeline.chooseKeyframeStart(
                keyframes, requestedStartMs, eventElapsedMs);
        return aligned < 0 ? requestedStartMs : aligned;
    }

    private void handlePacket(EncodedPacket packet) {
        remember(packet);
        long packetElapsedMs = packet.elapsedUs / 1_000L;
        long nowMs = SystemClock.elapsedRealtime();
        if (checkup && !checkupStarted && !stopRequested
                && packet.track == VIDEO_TRACK && packet.isKeyFrame() && videoFormat != null
                && (!config.audioEnabled || audioFormat != null)) {
            checkupStarted = true;
            if (openSegment(packetElapsedMs, Collections.emptyList(), 0, null)) {
                finishDeadlineMs = nowMs + 3_000L;
            } else stopRequested = true;
        }
        expireOutputRestart(nowMs);
        if (!stopRequested && activeClip == null && outputRestartActive(nowMs)
                && packet.track == VIDEO_TRACK && packet.isKeyFrame()) {
            long durationMs = Math.max(0, Math.min(MotionRecorderTimeline.SEGMENT_MILLIS,
                    outputRestartUntilElapsedMs - packetElapsedMs));
            long requiredBytes = estimateSegmentBytes(Collections.emptyList(), durationMs,
                    config.audioEnabled && !audioFailed && audioFormat != null);
            openSegment(packetElapsedMs, Collections.emptyList(), requiredBytes, outputRestartVolumeId);
        }
        if (activeClip == null || packet.elapsedUs < activeClip.startElapsedMs * 1_000L) return;
        boolean postRollAlreadyRecorded = MotionRecorderTimeline.postRollRecorded(
                activeClip.lastVideoElapsedMs, finishDeadlineMs);
        if (postRollAlreadyRecorded
                && !(packet.track == AUDIO_TRACK
                    && packet.elapsedUs <= activeClip.lastVideoElapsedUs)) return;
        long contentEndUs = elapsedMicros(finishDeadlineMs);
        long audioThroughUs = postRollAlreadyRecorded
                ? Math.max(contentEndUs, activeClip.lastVideoElapsedUs) : contentEndUs;
        if (packet.track == AUDIO_TRACK && finishDeadlineMs != Long.MIN_VALUE
                && packet.elapsedUs > audioThroughUs) return;
        if (packet.track == VIDEO_TRACK && finishDeadlineMs != Long.MIN_VALUE
                && packet.elapsedUs > contentEndUs) {
            appendBufferedTailThrough(packet.elapsedUs);
            recording = activeClip != null && !MotionRecorderTimeline.postRollRecorded(
                    activeClip.lastVideoElapsedMs, finishDeadlineMs);
            return;
        }
        writePacketAcrossSegments(packet);
        recording = activeClip != null && !MotionRecorderTimeline.postRollRecorded(
                activeClip.lastVideoElapsedMs, finishDeadlineMs);
    }

    private void writePacketAcrossSegments(EncodedPacket packet) {
        if (activeClip == null || packet.elapsedUs < activeClip.startElapsedMs * 1_000L) return;
        long lastWrittenUs = packet.track == VIDEO_TRACK
                ? chainLastVideoElapsedUs : chainLastAudioElapsedUs;
        if (packet.elapsedUs <= lastWrittenUs) return;
        if (!stopRequested && packet.track == VIDEO_TRACK && MotionRecorderTimeline.startsSegmentAt(
                activeClip.startElapsedMs, packet.elapsedUs / 1_000L, packet.isKeyFrame())) {
            String endedVolumeId = activeClip.output.volumeId;
            closeActiveClip(true);
            long startMs = packet.elapsedUs / 1_000L;
            long durationMs = Math.max(0, Math.min(MotionRecorderTimeline.SEGMENT_MILLIS,
                    finishDeadlineMs - startMs));
            long requiredBytes = estimateSegmentBytes(Collections.emptyList(), durationMs,
                    config.audioEnabled && !audioFailed && audioFormat != null);
            if (!openSegment(startMs, Collections.emptyList(), requiredBytes, null)
                    && !outputRestartActive(SystemClock.elapsedRealtime()))
                scheduleOutputContinuation(endedVolumeId);
        }
        if (activeClip != null) {
            Clip clip = activeClip;
            if (!hasStorageForPacket(clip, packet.data.length)) {
                scheduleOutputContinuation(clip.output.volumeId);
                closeActiveClip();
            } else writePacket(clip, packet);
        }
    }

    private List<EncodedPacket> ringPacketsThroughDeadline(List<EncodedPacket> packets, long deadlineMs) {
        long deadlineUs = elapsedMicros(deadlineMs);
        long boundaryUs = MotionRecorderTimeline.firstVideoAtOrAfter(packets, VIDEO_TRACK, deadlineUs);
        long throughUs = boundaryUs == Long.MAX_VALUE ? deadlineUs : boundaryUs;
        ArrayList<EncodedPacket> result = new ArrayList<>();
        for (EncodedPacket packet : packets) if (packet.elapsedUs <= throughUs) result.add(packet);
        return result;
    }

    private void appendBufferedTailThroughDeadline() {
        if (activeClip == null || finishDeadlineMs == Long.MIN_VALUE) return;
        List<EncodedPacket> available = new ArrayList<>(ring);
        long deadlineUs = elapsedMicros(finishDeadlineMs);
        long boundaryUs = MotionRecorderTimeline.firstVideoAtOrAfter(available, VIDEO_TRACK, deadlineUs);
        appendBufferedTailThrough(boundaryUs == Long.MAX_VALUE ? deadlineUs : boundaryUs);
    }

    private void appendBufferedTailThrough(long throughUs) {
        if (activeClip == null) return;
        List<EncodedPacket> available = new ArrayList<>(ring);
        for (EncodedPacket packet : available) {
            if (activeClip == null) return;
            if (!MotionRecorderTimeline.shouldAppendSample(packet.track, packet.elapsedUs,
                    VIDEO_TRACK, AUDIO_TRACK, chainLastVideoElapsedUs, chainLastAudioElapsedUs, throughUs))
                continue;
            writePacketAcrossSegments(packet);
        }
    }

    private static long elapsedMicros(long elapsedMs) {
        if (elapsedMs == Long.MIN_VALUE) return Long.MIN_VALUE;
        return elapsedMs > Long.MAX_VALUE / 1_000L ? Long.MAX_VALUE : elapsedMs * 1_000L;
    }

    private List<EncodedPacket> ringPacketsFrom(long startMs) {
        ArrayList<EncodedPacket> result = new ArrayList<>();
        long startUs = startMs * 1_000L;
        for (EncodedPacket packet : ring) if (packet.elapsedUs >= startUs) result.add(packet);
        result.sort(PACKET_ORDER);
        return result;
    }

    private long estimateSegmentBytes(List<EncodedPacket> initialPackets, long futureMs, boolean includeAudio) {
        long bytes = 0;
        for (EncodedPacket packet : initialPackets) bytes = saturatedAdd(bytes, packet.data.length);
        long bitsPerSecond = config.bitrate() + (includeAudio ? 32_000L : 0L);
        long futureBytes = (bitsPerSecond * Math.max(0, futureMs) + 7_999L) / 8_000L;
        return saturatedAdd(bytes, futureBytes);
    }

    private boolean hasStorageForPacket(Clip clip, int packetBytes) {
        // A disposable three-second clip must never evict the user's archive.
        if (checkup) return true;
        long now = SystemClock.elapsedRealtime();
        long currentSize = Math.max(clip.segmentSizeBytes, clip.sampleBytes);
        long actualSize = storage.outputSizeBytes(clip.output);
        if (actualSize >= 0) currentSize = Math.max(currentSize, actualSize);
        long nextSize = saturatedAdd(currentSize, packetBytes);
        if (now - clip.lastStorageCheckMs >= 1_000L || nextSize > clip.storageWriteLimitBytes) {
            MotionMediaLibrary.StorageDecision decision = library.prepareOwnSpace(clip.output.volumeId,
                    config, packetBytes, clip.output.baseOwnBytes, currentSize);
            if (!decision.allowed) {
                notifyState(decision.reason == null ? "motion_storage_allowance_unavailable" : decision.reason);
                return false;
            }
            clip.storageWriteLimitBytes = saturatedAdd(currentSize, decision.allowanceBytes);
            clip.lastStorageCheckMs = now;
        }
        if (nextSize <= clip.storageWriteLimitBytes) return true;
        notifyState("motion_storage_packet_exceeds_allowance");
        return false;
    }

    private boolean openSegment(long startElapsedMs, List<EncodedPacket> initialPackets, long requiredBytes,
            String firstAttemptVolumeId) {
        if (stopRequested || videoFormat == null) return false;
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)
                .format(new Date(wallFromElapsed(startElapsedMs)));
        String name = "Motion_" + timestamp + "_" + UUID.randomUUID().toString().substring(0, 8) + ".mp4";
        MotionStorage.Output output = null;
        MediaMuxer muxer = null;
        MotionMediaLease lease = null;
        String segmentId = UUID.randomUUID().toString().replace("-", "");
        String uri = null;
        String preferred = library.selectedVolumeId(config.primarySd);
        long preparationStarted = SystemClock.elapsedRealtime();
        try {
            output = storage.createOutput(preferred, config.fallbackEnabled, name, checkup ? null : config,
                    checkup ? null : library, requiredBytes, firstAttemptVolumeId);
            logDiagnostic("output_prepared", "elapsed_ms="
                    + (SystemClock.elapsedRealtime() - preparationStarted));
            if (stopRequested) throw new IOException("recording stopped during output preparation");
            uri = output.uri.toString();
            logDiagnostic("mp4_create", "volume=" + output.volumeId + " name=" + output.displayName
                    + " media_id=" + (output.uri.getLastPathSegment() == null ? "unknown" : output.uri.getLastPathSegment()));
            lease = MotionMediaLease.acquire(library.leaseDirectory(), uri);
            MotionMediaIndex.Record record = new MotionMediaIndex.Record(uri,
                    output.recoveryUri == null ? null : output.recoveryUri.toString(),
                    output.volumeId, output.displayName, segmentId, wallFromElapsed(startElapsedMs),
                    startElapsedMs, config.width(), config.height(), MotionMediaIndex.RECORDING,
                    config.cameraOrder);
            index.put(record);
            if (output.descriptor != null) {
                muxer = new MediaMuxer(output.descriptor.getFileDescriptor(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            } else if (output.temporaryFile != null) {
                muxer = new MediaMuxer(output.temporaryFile.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            } else {
                throw new IOException("Motion storage returned no muxer target");
            }
            int videoTrack = muxer.addTrack(videoFormat);
            int audioTrack = -1;
            MediaFormat selectedAudioFormat = config.audioEnabled && !audioFailed ? audioFormat : null;
            if (selectedAudioFormat != null) audioTrack = muxer.addTrack(selectedAudioFormat);
            muxer.start();
            logDiagnostic("mp4_muxer_started", "volume=" + output.volumeId + " name=" + output.displayName);
            Clip clip = new Clip(output, muxer, lease, record, videoTrack, audioTrack,
                    selectedAudioFormat != null, startElapsedMs);
            activeClip = clip;
            recording = true;
            if (firstAttemptVolumeId != null) clearOutputRestart();
            clip.storageWriteLimitBytes = output.initialOwnAllowanceBytes;
            clip.segmentSizeBytes = Math.max(0, storage.outputSizeBytes(output));
            persistCurrentEpisode(clip, false);
            for (EncodedPacket packet : initialPackets) {
                if (packet.elapsedUs >= startElapsedMs * 1_000L) {
                    if (!hasStorageForPacket(clip, packet.data.length)) {
                        scheduleOutputContinuation(clip.output.volumeId);
                        closeActiveClip();
                        return false;
                    }
                    writePacket(clip, packet);
                    if (activeClip != clip) return false;
                }
            }
            return true;
        } catch (IOException | RuntimeException failure) {
            Log.e(TAG, "motion_segment_open_failed", failure);
            logDiagnostic("mp4_create_failed", "name=" + name
                    + " error=" + failure.getClass().getSimpleName());
            notifyState("motion_storage_unavailable:" + failure.getClass().getSimpleName());
            if (output != null) scheduleOutputContinuation(output.volumeId);
            else if (firstAttemptVolumeId != null) scheduleOutputContinuationTo(firstAttemptVolumeId);
            else scheduleOutputContinuationTo(storage.oppositeVolumeId(preferred));
            if (muxer != null) {
                try { muxer.stop(); } catch (RuntimeException ignored) { }
                try { muxer.release(); } catch (RuntimeException ignored) { }
            }
            closeDescriptor(output);
            if (output != null) {
                if (checkup) {
                    try { storage.deleteTemporaryOutput(output); }
                    catch (IOException | RuntimeException cleanup) { notifyState("checkup_delete_failed:" + cleanup); }
                } else storage.discard(output);
            }
            if (lease != null) try { lease.close(); } catch (IOException ignored) { }
            if (uri != null) {
                try { index.remove(uri); } catch (IOException ignored) { }
            }
            activeClip = null;
            recording = false;
            return false;
        }
    }

    private void writePacket(Clip clip, EncodedPacket packet) {
        if (videoFailed && packet.track == AUDIO_TRACK && packet.elapsedUs > lastVideoElapsedUs) return;
        int track = packet.track == VIDEO_TRACK ? clip.videoTrack : clip.audioTrack;
        if (track < 0 || (packet.track == AUDIO_TRACK && !clip.audioIncluded)) return;
        long presentation = packet.elapsedUs - clip.startElapsedMs * 1_000L;
        if (presentation < 0 || packet.data.length == 0) return;
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        info.set(0, packet.data.length, presentation, packet.flags);
        try {
            clip.muxer.writeSampleData(track, ByteBuffer.wrap(packet.data), info);
            clip.segmentSizeBytes = saturatedAdd(clip.segmentSizeBytes, packet.data.length);
            clip.sampleBytes = saturatedAdd(clip.sampleBytes, packet.data.length);
            if (packet.track == VIDEO_TRACK) {
                clip.videoSamples++;
                clip.lastVideoElapsedMs = packet.elapsedUs / 1_000L;
                clip.lastVideoElapsedUs = packet.elapsedUs;
                chainLastVideoElapsedUs = packet.elapsedUs;
                persistPendingEpisodes(clip, false);
            } else {
                clip.audioSamples++;
                chainLastAudioElapsedUs = packet.elapsedUs;
            }
        } catch (RuntimeException failure) {
            Log.e(TAG, "motion_muxer_write_failed", failure);
            notifyState("motion_muxer_write_failed");
            scheduleOutputContinuation(clip.output.volumeId);
            closeActiveClip();
        }
    }

    private void closeActiveClip() {
        closeActiveClip(false);
    }

    private void closeActiveClip(boolean segmentBoundary) {
        Clip clip = activeClip;
        if (clip == null) return;
        boolean postRollComplete = MotionRecorderTimeline.postRollRecorded(
                clip.lastVideoElapsedMs, finishDeadlineMs);
        boolean checkupDurationComplete = checkup && !videoFailed && postRollComplete;
        activeClip = null;
        recording = false;
        persistCurrentEpisode(clip, true);
        logDiagnostic("mp4_finalize_begin", "volume=" + clip.output.volumeId
                + " name=" + clip.output.displayName + " video_samples=" + clip.videoSamples);
        boolean stopped = false;
        boolean prepareThumbnail = false;
        try {
            clip.muxer.stop();
            stopped = true;
        } catch (RuntimeException failure) {
            Log.w(TAG, "motion_muxer_stop_failed", failure);
            logDiagnostic("mp4_muxer_stop_failed", "name=" + clip.output.displayName
                    + " error=" + failure.getClass().getSimpleName());
        } finally {
            try { clip.muxer.release(); } catch (RuntimeException ignored) { }
            closeDescriptor(clip.output);
        }
        try {
            if (stopped && clip.videoSamples > 0) {
                long size = clip.output.temporaryFile == null
                        ? sizeOf(clip.output.uri) : Math.max(0, clip.output.temporaryFile.length());
                storage.publish(clip.output);
                long durationMs = Math.max(1L, clip.lastVideoElapsedMs - clip.startElapsedMs + 34L);
                String status = !videoFailed && (segmentBoundary || postRollComplete)
                        ? MotionMediaIndex.COMPLETE : MotionMediaIndex.INTERRUPTED;
                boolean indexed = index.finishRecording(clip.record.uri, status, durationMs, size);
                prepareThumbnail = indexed && !checkup;
                logDiagnostic("mp4_finalize_complete", "volume=" + clip.output.volumeId
                        + " name=" + clip.output.displayName + " size_bytes=" + size
                        + " duration_ms=" + durationMs + " video_samples=" + clip.videoSamples
                        + " audio_samples=" + clip.audioSamples + " status=" + status);
                if (checkup) checkupSavedAndDeleted = checkupDurationComplete;
            } else if (clip.videoSamples == 0) {
                if (!checkup) storage.discard(clip.output);
                index.remove(clip.record.uri);
                logDiagnostic("mp4_finalize_discard", "volume=" + clip.output.volumeId
                        + " name=" + clip.output.displayName + " reason=no_video_samples");
            } else if (checkup) {
                throw new IOException("temporary recording muxer did not finish");
            }
        } catch (IOException | RuntimeException failure) {
            Log.e(TAG, "motion_segment_publish_failed", failure);
            logDiagnostic("mp4_finalize_failed", "volume=" + clip.output.volumeId
                    + " name=" + clip.output.displayName + " error=" + failure.getClass().getSimpleName());
            notifyState("motion_publish_failed:" + failure.getClass().getSimpleName());
            scheduleOutputContinuation(clip.output.volumeId);
        } finally {
            if (checkup) {
                try {
                    storage.deleteTemporaryOutput(clip.output);
                    index.remove(clip.record.uri);
                    logDiagnostic("checkup_clip_deleted", "name=" + clip.output.displayName);
                } catch (IOException | RuntimeException failure) {
                    checkupSavedAndDeleted = false;
                    notifyState("checkup_delete_failed:" + failure);
                }
                stopRequested = true;
                stopReason = "manual_check_finished";
            }
            try { clip.lease.close(); } catch (IOException ignored) { }
            if (prepareThumbnail) library.prepareThumbnail(clip.record.uri);
        }
        if (!segmentBoundary) clearEventWindow();
    }

    private void clearEventWindow() {
        eventWindowStartMs = Long.MIN_VALUE;
        mergeCloseDeadlineMs = Long.MIN_VALUE;
        chainLastVideoElapsedUs = Long.MIN_VALUE;
        chainLastAudioElapsedUs = Long.MIN_VALUE;
        bufferedTailUnavailable = false;
        mergeWaitAbandoned = false;
    }

    private void updateEpisode(Detection detection) {
        Episode episode = episodesByLevel.get(detection.level);
        if (episode != null && detection.elapsedMs >= episode.lastElapsedMs
                && detection.elapsedMs - episode.lastElapsedMs <= DETECTION_MERGE_GAP_MS) {
            episode.lastElapsedMs = detection.elapsedMs;
        } else {
            episode = new Episode(UUID.randomUUID().toString(), detection.level,
                    detection.elapsedMs, detection.elapsedMs);
            episodesByLevel.put(detection.level, episode);
        }
        detection.eventId = episode.id;
        detection.episodeStartMs = episode.startElapsedMs;
        detection.episodeEndMs = episode.lastElapsedMs;
        detection.latestElapsedMs = detection.elapsedMs;
    }

    private void persistEpisode(Clip clip, Detection detection) {
        if (clip == null || detection.eventId == null || detection.episodeEndMs < clip.startElapsedMs) return;
        persistEpisode(clip, detection.eventId, detection.level,
                Math.max(detection.episodeStartMs, clip.startElapsedMs), detection.episodeEndMs, false);
    }

    private void persistInitialEpisode(Clip clip, Detection detection) {
        if (clip == null || detection.eventId == null) return;
        long start = Math.max(detection.episodeStartMs, clip.startElapsedMs);
        long end = Math.max(start, detection.episodeEndMs);
        persistEpisode(clip, detection.eventId, detection.level, start, end, false);
    }

    private void persistCurrentEpisode(Clip clip, boolean force) {
        if (clip == null) return;
        for (Episode episode : episodesByLevel.values()) {
            if (episode.lastElapsedMs < clip.startElapsedMs) continue;
            persistEpisode(clip, episode.id, episode.level,
                    Math.max(episode.startElapsedMs, clip.startElapsedMs), episode.lastElapsedMs, force);
        }
        persistPendingEpisodes(clip, force);
    }

    private void persistEpisode(Clip clip, String eventId, int level, long startMs, long endMs, boolean force) {
        if (clip == null || eventId == null || endMs < clip.startElapsedMs) return;
        long boundedStart = Math.max(startMs, clip.startElapsedMs);
        if (endMs < boundedStart) return;
        PendingEpisode pending = clip.pendingEpisodes.get(eventId);
        if (pending == null) {
            pending = new PendingEpisode(level, boundedStart, endMs);
            clip.pendingEpisodes.put(eventId, pending);
        } else {
            pending.startElapsedMs = Math.min(pending.startElapsedMs, boundedStart);
            pending.endElapsedMs = Math.max(pending.endElapsedMs, endMs);
        }
        persistPendingEpisodes(clip, force);
    }

    private void persistPendingEpisodes(Clip clip, boolean force) {
        if (clip == null || clip.videoSamples == 0) return;
        Iterator<Map.Entry<String, PendingEpisode>> iterator = clip.pendingEpisodes.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, PendingEpisode> entry = iterator.next();
            String eventId = entry.getKey();
            PendingEpisode pending = entry.getValue();
            long boundedEnd = Math.min(pending.endElapsedMs, clip.lastVideoElapsedMs);
            if (boundedEnd < pending.startElapsedMs) continue;

            long previous = clip.persistedEpisodeEnds.getOrDefault(eventId, Long.MIN_VALUE);
            if (previous != Long.MIN_VALUE && pending.endElapsedMs <= previous) {
                iterator.remove();
                continue;
            }
            if (!force && previous != Long.MIN_VALUE
                    && boundedEnd - previous < MARKER_PERSIST_INTERVAL_MS) continue;
            if (!force && pending.lastAttemptElapsedMs != Long.MIN_VALUE
                    && clip.lastVideoElapsedMs - pending.lastAttemptElapsedMs < MARKER_PERSIST_INTERVAL_MS) continue;

            pending.lastAttemptElapsedMs = clip.lastVideoElapsedMs;
            try {
                index.upsertMarker(clip.record.uri, new MotionMediaIndex.Marker(eventId,
                        pending.startElapsedMs, wallFromElapsed(pending.startElapsedMs),
                        boundedEnd, wallFromElapsed(boundedEnd), pending.level));
                clip.persistedEpisodeEnds.put(eventId, boundedEnd);
                if (pending.endElapsedMs <= boundedEnd) iterator.remove();
            } catch (IOException failure) {
                notifyState("motion_marker_persist_failed");
            }
        }
    }

    private boolean persistMarkerInFinishedClip(Detection detection) {
        if (detection.eventId == null) return false;
        for (MotionMediaIndex.Record record : index.records()) {
            if (!MotionMediaIndex.COMPLETE.equals(record.status) && !MotionMediaIndex.INTERRUPTED.equals(record.status))
                continue;
            long end = record.startElapsedRealtimeMs + record.durationMs;
            if (detection.elapsedMs < record.startElapsedRealtimeMs || detection.elapsedMs > end) continue;
            long start = Math.max(detection.episodeStartMs, record.startElapsedRealtimeMs);
            long endInClip = Math.min(detection.episodeEndMs, end);
            if (endInClip < start) continue;
            for (MotionMediaIndex.Marker marker : record.markers) {
                if (marker.eventId.equals(detection.eventId)
                        && endInClip - marker.endElapsedRealtimeMs < MARKER_PERSIST_INTERVAL_MS) return true;
            }
            try {
                index.upsertMarker(record.uri, new MotionMediaIndex.Marker(detection.eventId, start,
                        wallFromElapsed(start), endInClip, wallFromElapsed(endInClip), detection.level));
                return true;
            } catch (IOException failure) {
                notifyState("motion_marker_persist_failed");
                return false;
            }
        }
        return false;
    }

    private void remember(EncodedPacket packet) {
        int position = Collections.binarySearch(ring, packet, PACKET_ORDER);
        if (position < 0) position = -position - 1;
        else while (position < ring.size() && PACKET_ORDER.compare(ring.get(position), packet) <= 0) position++;
        ring.add(position, packet);
        ringBytes += packet.data.length;
        latestRingElapsedUs = Math.max(latestRingElapsedUs, packet.elapsedUs);
    }

    private void trimRing() {
        if (ring.isEmpty()) return;
        long newestMs = latestRingElapsedUs / 1_000L;
        long requestedAge = Math.min(30, config.beforeSeconds) * 1_000L + 2_000L;
        long cutoffMs = newestMs - Math.min(MAX_RING_AGE_MS, requestedAge);
        long precedingKeyframe = Long.MIN_VALUE;
        long followingKeyframe = Long.MAX_VALUE;
        for (EncodedPacket packet : ring) {
            if (packet.track != VIDEO_TRACK || !packet.isKeyFrame()) continue;
            long time = packet.elapsedUs / 1_000L;
            if (time <= cutoffMs) precedingKeyframe = Math.max(precedingKeyframe, time);
            else followingKeyframe = Math.min(followingKeyframe, time);
        }
        long keepFrom = precedingKeyframe != Long.MIN_VALUE ? precedingKeyframe
                : followingKeyframe != Long.MAX_VALUE ? followingKeyframe : cutoffMs;
        removeRingBefore(keepFrom * 1_000L);
        while (ringBytes > MAX_RING_BYTES && ring.size() > 1) {
            long oldestKeyframe = Long.MAX_VALUE;
            for (EncodedPacket packet : ring) {
                if (packet.track == VIDEO_TRACK && packet.isKeyFrame() && packet.elapsedUs > ring.get(0).elapsedUs)
                    oldestKeyframe = Math.min(oldestKeyframe, packet.elapsedUs);
            }
            if (oldestKeyframe != Long.MAX_VALUE) removeRingBefore(oldestKeyframe);
            else {
                EncodedPacket removed = ring.remove(0);
                recordRingEviction(removed);
            }
        }
    }

    private void removeRingBefore(long elapsedUs) {
        Iterator<EncodedPacket> iterator = ring.iterator();
        while (iterator.hasNext()) {
            EncodedPacket packet = iterator.next();
            if (packet.elapsedUs < elapsedUs) {
                recordRingEviction(packet);
                iterator.remove();
            }
        }
    }

    private void recordRingEviction(EncodedPacket packet) {
        ringBytes -= packet.data.length;
        if (activeClip == null) return;
        long lastWrittenUs = packet.track == VIDEO_TRACK
                ? chainLastVideoElapsedUs : chainLastAudioElapsedUs;
        if (packet.elapsedUs > lastWrittenUs) bufferedTailUnavailable = true;
    }

    private void applyFinishState() {
        long requestedDeadline = finishState.apply(finishDeadlineMs);
        if (requestedDeadline > finishDeadlineMs) extendFinishDeadline(requestedDeadline);
        finishRequested = finishState.finishing();
    }

    static final class FinishState {
        private static final long NO_COMMAND = Long.MIN_VALUE;
        private static final long RESUME_COMMAND = Long.MIN_VALUE + 1;

        private final AtomicLong command = new AtomicLong(NO_COMMAND);
        private boolean finishing;

        void requestFinish(long deadlineMs) {
            if (deadlineMs < 0) return;
            while (true) {
                long current = command.get();
                long requested = current == RESUME_COMMAND
                        ? deadlineMs : Math.max(current, deadlineMs);
                if (command.compareAndSet(current, requested)) return;
            }
        }

        void requestResume() { command.set(RESUME_COMMAND); }

        long apply(long deadlineMs) {
            long requested = command.getAndSet(NO_COMMAND);
            if (requested == RESUME_COMMAND) finishing = false;
            else if (requested >= 0) {
                finishing = true;
                deadlineMs = Math.max(deadlineMs, requested);
            }
            return deadlineMs;
        }

        boolean finishing() { return finishing; }

        void reset() {
            command.set(NO_COMMAND);
            finishing = false;
        }
    }

    private void extendFinishDeadline(long deadlineMs) {
        finishDeadlineMs = Math.max(finishDeadlineMs, deadlineMs);
        if (outputRestartUntilElapsedMs != Long.MIN_VALUE)
            outputRestartUntilElapsedMs = Math.max(outputRestartUntilElapsedMs, finishDeadlineMs);
    }

    private void scheduleOutputContinuation(String failedVolumeId) {
        if (config == null || !config.fallbackEnabled || finishDeadlineMs == Long.MIN_VALUE) return;
        String inverseVolumeId = storage.oppositeVolumeId(failedVolumeId);
        scheduleOutputContinuationTo(inverseVolumeId);
    }

    private void scheduleOutputContinuationTo(String firstAttemptVolumeId) {
        if (checkup) return;
        if (config == null || !config.fallbackEnabled || firstAttemptVolumeId == null
                || finishDeadlineMs == Long.MIN_VALUE) return;
        if (finishDeadlineMs <= SystemClock.elapsedRealtime()) return;
        outputRestartVolumeId = firstAttemptVolumeId;
        outputRestartUntilElapsedMs = Math.max(outputRestartUntilElapsedMs, finishDeadlineMs);
    }

    private boolean outputRestartActive(long elapsedMs) {
        return outputRestartVolumeId != null && outputRestartUntilElapsedMs != Long.MIN_VALUE
                && elapsedMs < outputRestartUntilElapsedMs;
    }

    private void expireOutputRestart(long elapsedMs) {
        if (outputRestartUntilElapsedMs != Long.MIN_VALUE && elapsedMs >= outputRestartUntilElapsedMs)
            clearOutputRestart();
    }

    private void clearOutputRestart() {
        outputRestartVolumeId = null;
        outputRestartUntilElapsedMs = Long.MIN_VALUE;
    }

    private void signalVideoEndOfStream() {
        MediaCodec encoder = videoEncoder;
        if (encoder == null) return;
        try { encoder.signalEndOfInputStream(); }
        catch (RuntimeException failure) { Log.w(TAG, "video_eos_signal_failed", failure); }
    }

    private void stopAudioCapture() {
        audioRunning = false;
        AudioRecord recorder = audioRecord;
        if (recorder != null) {
            try { recorder.stop(); } catch (RuntimeException ignored) { }
        }
    }

    private long mapVideoPts(long ptsUs) {
        if (videoPtsOffsetUs == Long.MIN_VALUE) videoPtsOffsetUs = ptsOffset(ptsUs);
        return ptsUs + videoPtsOffsetUs;
    }

    private long mapAudioPts(long ptsUs) {
        if (audioPtsOffsetUs == Long.MIN_VALUE) audioPtsOffsetUs = ptsOffset(ptsUs);
        return ptsUs + audioPtsOffsetUs;
    }

    private static long ptsOffset(long ptsUs) {
        long nowUs = SystemClock.elapsedRealtimeNanos() / 1_000L;
        return ptsUs > 0 && Math.abs(ptsUs - nowUs) <= 5_000_000L ? 0 : nowUs - ptsUs;
    }

    private long wallFromElapsed(long elapsedMs) {
        MotionFrameTimestamp timestamp = frameTimestamp;
        return timestamp == null ? elapsedMs : timestamp.wallTimeMillis(elapsedMs);
    }

    private long sizeOf(android.net.Uri uri) {
        try (android.os.ParcelFileDescriptor descriptor = storage.openMediaFileDescriptor(uri, "r")) {
            return descriptor == null ? 0 : Math.max(0, descriptor.getStatSize());
        } catch (IOException | RuntimeException unavailable) { return 0; }
    }

    private void notifyState(String value) {
        if (checkup && !value.startsWith("input_release_required:") && !value.startsWith("stopped:")) {
            checkupFailure = value;
            stopRequested = true;
        }
        try { stateCallback.accept(value); }
        catch (RuntimeException failure) { Log.w(TAG, "state_callback_failed", failure); }
    }

    private void logDiagnostic(String event, String details) {
        String message = "session=" + sessionId + " pid=" + Process.myPid()
                + " elapsed_ms=" + SystemClock.elapsedRealtime() + " event=" + event
                + (details == null || details.isEmpty() ? "" : " " + details);
        Log.i(TAG, message);
        if (shellMediaContext != null) {
            System.out.println("MOTION " + message);
            System.out.flush();
        }
    }

    private static byte[] copyBytes(ByteBuffer source, int offset, int size) {
        ByteBuffer copy = source.duplicate();
        copy.position(offset);
        copy.limit(offset + size);
        byte[] result = new byte[size];
        copy.get(result);
        return result;
    }

    private static long saturatedAdd(long left, long right) {
        if (right > 0 && left > Long.MAX_VALUE - right) return Long.MAX_VALUE;
        return left + right;
    }

    private static void closeDescriptor(MotionStorage.Output output) {
        if (output != null && output.descriptor != null) {
            try { output.descriptor.close(); } catch (IOException ignored) { }
        }
    }

    private static final Comparator<EncodedPacket> PACKET_ORDER = Comparator
            .comparingLong((EncodedPacket packet) -> packet.elapsedUs)
            .thenComparingInt(packet -> packet.track);

    private static final class Detection {
        final int level;
        final long elapsedMs;
        String eventId;
        long episodeStartMs;
        long episodeEndMs;
        long latestElapsedMs;
        Detection(int level, long elapsedMs) { this.level = level; this.elapsedMs = elapsedMs; }
        void mergeLatest(Detection newer) {
            episodeEndMs = newer.episodeEndMs;
            latestElapsedMs = newer.latestElapsedMs;
        }
    }

    private static final class Episode {
        final String id;
        final int level;
        final long startElapsedMs;
        long lastElapsedMs;
        Episode(String id, int level, long startElapsedMs, long lastElapsedMs) {
            this.id = id; this.level = level; this.startElapsedMs = startElapsedMs;
            this.lastElapsedMs = lastElapsedMs;
        }
    }

    private static final class PendingEpisode {
        final int level;
        long startElapsedMs;
        long endElapsedMs;
        long lastAttemptElapsedMs = Long.MIN_VALUE;
        PendingEpisode(int level, long startElapsedMs, long endElapsedMs) {
            this.level = level;
            this.startElapsedMs = startElapsedMs;
            this.endElapsedMs = endElapsedMs;
        }
    }

    private static final class EncodedPacket implements MotionRecorderTimeline.Sample {
        final int track;
        final long elapsedUs;
        final int flags;
        final byte[] data;
        EncodedPacket(int track, long elapsedUs, int flags, byte[] data) {
            this.track = track; this.elapsedUs = elapsedUs; this.flags = flags; this.data = data;
        }
        @Override public int track() { return track; }
        @Override public long elapsedUs() { return elapsedUs; }
        boolean isKeyFrame() { return (flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0; }
    }

    private static final class TimelineItem {
        final EncodedPacket packet;
        final Detection detection;
        private TimelineItem(EncodedPacket packet, Detection detection) { this.packet = packet; this.detection = detection; }
        static TimelineItem packet(EncodedPacket value) { return new TimelineItem(value, null); }
        static TimelineItem detection(Detection value) { return new TimelineItem(null, value); }
        long elapsedUs() { return packet == null ? detection.elapsedMs * 1_000L : packet.elapsedUs; }
    }

    private static final class Clip {
        final MotionStorage.Output output;
        final MediaMuxer muxer;
        final MotionMediaLease lease;
        final MotionMediaIndex.Record record;
        final int videoTrack;
        final int audioTrack;
        final boolean audioIncluded;
        final long startElapsedMs;
        long lastVideoElapsedMs = Long.MIN_VALUE;
        long lastVideoElapsedUs = Long.MIN_VALUE;
        long videoSamples;
        long audioSamples;
        long sampleBytes;
        long segmentSizeBytes;
        long storageWriteLimitBytes = Long.MAX_VALUE;
        long lastStorageCheckMs;
        final HashMap<String, Long> persistedEpisodeEnds = new HashMap<>();
        final HashMap<String, PendingEpisode> pendingEpisodes = new HashMap<>();

        Clip(MotionStorage.Output output, MediaMuxer muxer, MotionMediaLease lease,
                MotionMediaIndex.Record record, int videoTrack, int audioTrack,
                boolean audioIncluded, long startElapsedMs) {
            this.output = output; this.muxer = muxer; this.lease = lease; this.record = record;
            this.videoTrack = videoTrack; this.audioTrack = audioTrack;
            this.audioIncluded = audioIncluded; this.startElapsedMs = startElapsedMs;
        }
    }
}
