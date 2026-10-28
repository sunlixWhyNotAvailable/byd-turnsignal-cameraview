package com.byd.extend;

import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLES30;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import android.view.Surface;

import java.nio.FloatBuffer;
import java.util.Arrays;
import java.util.function.Consumer;
import java.util.function.IntPredicate;

/** Drains the OEM input without ever calling a downstream window's eglSwapBuffers. */
final class CameraFramePump {
    interface Listener {
        void available();
        void failed(Throwable error);
        void acquired(Acquisition sample);
        default void priority(Object... fields) {}
    }

    static final class Acquisition {
        long startedNs, producerTimestampNs, queueNs, preparationNs, updateNs;
        long copyNs, fenceNs, releaseNs, inputCycleNs;
        int signals;
        boolean allocated, copied, invalidTimestamp, repeatedTimestamp;
        boolean noPreparedBuffer, gpuBusy, mailboxBusy;

        void reset() {
            startedNs = producerTimestampNs = queueNs = preparationNs = updateNs = 0;
            copyNs = fenceNs = releaseNs = inputCycleNs = 0;
            signals = 0;
            allocated = copied = invalidTimestamp = repeatedTimestamp = false;
            noPreparedBuffer = gpuBusy = mailboxBusy = false;
        }
    }

    static final class Frame {
        int texture, framebuffer, width, height;
        long fence, acquiredNs, producerNs;
    }

    final CameraFrameMailbox mailbox = new CameraFrameMailbox();
    final Frame[] frames = {new Frame(), new Frame()};
    private final HandlerThread thread;
    private final EGLDisplay display;
    private final EGLConfig config;
    private final EGLContext sharedContext;
    private final Object outputSizeLock = new Object();
    private final int glVersion, sourceWidth, sourceHeight, index;
    private final Listener listener;
    private Handler handler;
    private EGLContext context = EGL14.EGL_NO_CONTEXT;
    private EGLSurface idle = EGL14.EGL_NO_SURFACE;
    private SurfaceTexture input;
    Surface surface;
    private int externalTexture, program, positionLocation, coordsLocation, matrixLocation, textureLocation;
    private FloatBuffer positions, coords;
    private final float[] matrix = new float[16];
    private final Runnable capture = this::capture;
    private final IntPredicate readyToWritePredicate = this::readyToWrite;
    private final IntPredicate preparedForOutputPredicate = this::preparedForOutput;
    private final Acquisition acquisition = new Acquisition();
    private int signals;
    private boolean queued, failed, matrixReported;
    private boolean trackingCaptureFence, captureFenceNotReady;
    private long firstCallbackNs, lastFrameTimestamp;
    private int nextPreparationSlot;
    private int reportedSourceWidth = -1, reportedSourceHeight = -1;
    private int reportedOutputWidth = Integer.MIN_VALUE, reportedOutputHeight = Integer.MIN_VALUE;
    private int reserveOutputWidth, reserveOutputHeight;
    private volatile long outputSize;
    private volatile boolean closed;

    CameraFramePump(EGLDisplay display, EGLConfig config, EGLContext sharedContext,
                    int glVersion, int index, Listener listener) {
        this(display, config, sharedContext, glVersion, index,
                index == 4123 ? 3840 : 1920, index == 4123 ? 2600 : index == 0 ? 990 : 1300,
                listener);
    }

    CameraFramePump(EGLDisplay display, EGLConfig config, EGLContext sharedContext,
                    int glVersion, int index, int sourceWidth, int sourceHeight, Listener listener) {
        this.display = display;
        this.config = config;
        this.sharedContext = sharedContext;
        this.glVersion = glVersion;
        this.index = index;
        if (sourceWidth < 2 || sourceHeight < 2)
            throw new IllegalArgumentException("camera source dimensions are invalid");
        this.sourceWidth = sourceWidth;
        this.sourceHeight = sourceHeight;
        this.listener = listener;
        thread = new HandlerThread("camera-acquire-" + index);
    }

    void start() throws Exception {
        thread.start();
        handler = new Handler(thread.getLooper());
        DirectCameraSourceHub.SerializedCall<Void> ready = new DirectCameraSourceHub.SerializedCall<>();
        if (!handler.post(() -> ready.run(() -> {
            applyExteriorThreadPriority(index, listener::priority);
            initialize();
            return null;
        }))) {
            throw new IllegalStateException("camera acquisition thread stopped");
        }
        ready.result();
    }

    static void applyExteriorThreadPriority(int index, Consumer<Object[]> report) {
        if (index < 1 || index > 4) return;
        final int requested = -10;
        int tid = Process.myTid();
        Integer before = null, actual = null;
        RuntimeException failure = null;
        String operation = "getThreadPriority_before";
        try {
            before = Process.getThreadPriority(tid);
            operation = "setThreadPriority";
            // Apply on this worker after its Looper starts: a denial must not kill the thread.
            Process.setThreadPriority(requested);
        } catch (RuntimeException error) {
            failure = error;
        }
        try {
            actual = Process.getThreadPriority(tid);
        } catch (RuntimeException error) {
            if (failure == null) {
                operation = "getThreadPriority_after";
                failure = error;
            } else {
                failure.addSuppressed(error);
            }
        }
        report.accept(new Object[]{
                "source_index", index, "worker", Thread.currentThread().getName(),
                "pid", Process.myPid(), "uid", Process.myUid(), "tid", tid,
                "requested_nice", requested, "previous_nice", before, "actual_nice", actual,
                "applied", failure == null && actual != null && actual == requested,
                "failed_operation", failure == null ? "" : operation,
                "error", com.byd.extend.remote.CameraViewingDiagnostics.describe(failure)});
    }

    void setOutputSize(int width, int height) {
        synchronized (outputSizeLock) {
            outputSize = ((long) Math.min(sourceWidth, width) << 32)
                    | (Math.min(sourceHeight, height) & 0xffffffffL);
        }
    }

    void finishDiagnostics(Runnable action) {
        if (!closed) handler.post(action);
    }

    private void initialize() {
        context = EGL14.eglCreateContext(display, config, sharedContext,
                new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, glVersion, EGL14.EGL_NONE}, 0);
        require(context != EGL14.EGL_NO_CONTEXT, "camera acquisition context failed");
        idle = EGL14.eglCreatePbufferSurface(display, config,
                new int[]{EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE}, 0);
        require(idle != EGL14.EGL_NO_SURFACE && EGL14.eglMakeCurrent(display, idle, idle, context),
                "camera acquisition surface failed");
        program = CameraDewarpRenderer.linkProgram(
                DirectCameraSourceHub.VERTEX_SHADER, DirectCameraSourceHub.FRAGMENT_SHADER);
        positionLocation = GLES20.glGetAttribLocation(program, "aPosition");
        coordsLocation = GLES20.glGetAttribLocation(program, "aTexCoord");
        matrixLocation = GLES20.glGetUniformLocation(program, "uTextureMatrix");
        textureLocation = GLES20.glGetUniformLocation(program, "uTexture");
        positions = DirectCameraSourceHub.buffer(new float[]{-1, -1, 1, -1, -1, 1, 1, 1});
        coords = DirectCameraSourceHub.buffer(new float[]{0, 0, 1, 0, 0, 1, 1, 1});
        int[] names = new int[1];
        GLES20.glGenTextures(1, names, 0);
        externalTexture = names[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, externalTexture);
        configureTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES);
        if (index == 4123) return; // Motion supplies its original CPU-readable Image.
        input = new SurfaceTexture(externalTexture);
        input.setDefaultBufferSize(sourceWidth, sourceHeight);
        surface = new Surface(input);
        input.setOnFrameAvailableListener(ignored -> {
            if (closed || failed) return;
            long callbackNs = SystemClock.elapsedRealtimeNanos();
            signals++;
            if (!queued) {
                queued = true;
                firstCallbackNs = callbackNs;
                handler.post(capture);
            }
        }, handler);
        long size = outputSize;
        reportSourceFormat((int) (size >>> 32), (int) size);
    }

    Handler acquisitionHandler() { return handler; }

    /** Runs on acquisitionHandler; completes all source reads before the caller closes its Image. */
    boolean snapshotImage(android.media.Image image) {
        if (closed || failed) return false;
        long size = outputSize;
        int width = (int) (size >>> 32), height = (int) size;
        if (width <= 0 || height <= 0) return false;
        int slot = mailbox.reserve(readyToWritePredicate);
        if (slot < 0) return false;
        long imported = 0;
        try (android.hardware.HardwareBuffer buffer = image.getHardwareBuffer()) {
            if (buffer == null) throw new IllegalStateException("Motion image has no hardware buffer");
            imported = MotionHardwareBuffer.bind(buffer, externalTexture);
            Frame frame = frames[slot];
            allocate(frame, width, height);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, frame.framebuffer);
            GLES20.glViewport(0, 0, width, height);
            GLES20.glUseProgram(program);
            GLES20.glEnableVertexAttribArray(positionLocation);
            GLES20.glEnableVertexAttribArray(coordsLocation);
            GLES20.glVertexAttribPointer(positionLocation, 2, GLES20.GL_FLOAT, false, 0, positions);
            GLES20.glVertexAttribPointer(coordsLocation, 2, GLES20.GL_FLOAT, false, 0, coords);
            android.opengl.Matrix.setIdentityM(matrix, 0);
            matrix[5] = -1; matrix[13] = 1; // Image rows are top-down; owned GL textures are bottom-up.
            GLES20.glUniformMatrix4fv(matrixLocation, 1, false, matrix, 0);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, externalTexture);
            GLES20.glUniform1i(textureLocation, 0);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            require(GLES20.glGetError() == GLES20.GL_NO_ERROR, "Motion shared snapshot failed");
            // API32 Java Image has no release-fence setter. Do not return it before GPU reads finish.
            GLES20.glFinish();
            frame.producerNs = image.getTimestamp();
            frame.acquiredNs = System.nanoTime();
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
            mailbox.publish(slot);
            listener.available();
            return true;
        } catch (RuntimeException error) {
            failed = true;
            throw error;
        } finally {
            if (imported != 0) MotionHardwareBuffer.release(imported);
        }
    }

    private void capture() {
        long started = SystemClock.elapsedRealtimeNanos();
        Acquisition sample = acquisition;
        sample.reset();
        sample.startedNs = started;
        queued = false;
        int count = signals;
        signals = 0;
        long callbackNs = firstCallbackNs;
        firstCallbackNs = 0;
        if (closed || failed) return;

        sample.queueNs = callbackNs == 0 ? 0 : Math.max(0, started - callbackNs);
        sample.signals = count;

        Throwable failure = null;
        int preparedSlot = -1;
        long prepareStart = SystemClock.elapsedRealtimeNanos();
        try {
            try {
                long size = outputSize;
                int width = (int) (size >>> 32), height = (int) size;
                if (width > 0 && height > 0) {
                    for (int offset = 0; offset < CameraFrameMailbox.SLOT_COUNT; offset++) {
                        int candidate = (nextPreparationSlot + offset) % CameraFrameMailbox.SLOT_COUNT;
                        if (mailbox.reserveFree(candidate) < 0) continue;
                        preparedSlot = candidate;
                        if (!readyToWrite(candidate)) {
                            mailbox.cancel(candidate);
                            preparedSlot = -1;
                            continue;
                        }
                        sample.allocated = allocate(frames[candidate], width, height);
                        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
                        nextPreparationSlot = (candidate + 1) % CameraFrameMailbox.SLOT_COUNT;
                        break;
                    }
                }
            } catch (Throwable error) {
                failure = appendFailure(failure, "prepare camera snapshot", error);
            }
            if (preparedSlot >= 0) {
                try {
                    mailbox.cancel(preparedSlot);
                } catch (Throwable error) {
                    failure = appendFailure(failure, "release camera preparation reservation", error);
                }
                preparedSlot = -1;
            }
        } finally {
            sample.preparationNs = SystemClock.elapsedRealtimeNanos() - prepareStart;
        }

        long updateStart = SystemClock.elapsedRealtimeNanos();
        boolean updated = false;
        boolean publish = false;
        boolean published = false;
        boolean reportMatrix = false;
        int slot = -1;
        int copyWidth = 0, copyHeight = 0;
        try {
            try {
                input.updateTexImage();
                updated = true;
            } catch (Throwable error) {
                failure = appendFailure(failure, "update camera SurfaceTexture", error);
            }
            sample.updateNs = SystemClock.elapsedRealtimeNanos() - updateStart;

            if (updated && failure == null) {
                long acquired = System.nanoTime();
                long timestamp = 0;
                try {
                    timestamp = input.getTimestamp();
                    sample.producerTimestampNs = timestamp;
                } catch (Throwable error) {
                    failure = appendFailure(failure, "read camera SurfaceTexture timestamp", error);
                }
                if (failure == null && timestamp <= 0) {
                    sample.invalidTimestamp = true;
                } else if (failure == null && timestamp == lastFrameTimestamp) {
                    sample.repeatedTimestamp = true;
                } else if (failure == null) {
                    lastFrameTimestamp = timestamp;
                    try {
                        input.getTransformMatrix(matrix);
                        reportMatrix = true;
                    } catch (Throwable error) {
                        failure = appendFailure(failure, "read camera SurfaceTexture transform", error);
                    }

                    if (failure == null) {
                        long size = outputSize;
                        int width = (int) (size >>> 32), height = (int) size;
                        if (width > 0 && height > 0) {
                            reserveOutputWidth = copyWidth = width;
                            reserveOutputHeight = copyHeight = height;
                            captureFenceNotReady = false;
                            trackingCaptureFence = true;
                            try {
                                slot = mailbox.reserve(preparedForOutputPredicate);
                            } catch (Throwable error) {
                                failure = appendFailure(failure, "reserve prepared camera snapshot", error);
                            } finally {
                                trackingCaptureFence = false;
                            }

                            if (failure == null && slot < 0) {
                                boolean compatible = false;
                                for (Frame frame : frames) {
                                    if (frame.width == width && frame.height == height) {
                                        compatible = true;
                                        break;
                                    }
                                }
                                if (!compatible) sample.noPreparedBuffer = true;
                                else if (captureFenceNotReady) sample.gpuBusy = true;
                                else sample.mailboxBusy = true;
                            } else if (failure == null) {
                                Frame frame = frames[slot];
                                long copyStart = SystemClock.elapsedRealtimeNanos();
                                try {
                                    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, frame.framebuffer);
                                    GLES20.glViewport(0, 0, width, height);
                                    GLES20.glUseProgram(program);
                                    GLES20.glEnableVertexAttribArray(positionLocation);
                                    GLES20.glEnableVertexAttribArray(coordsLocation);
                                    GLES20.glVertexAttribPointer(positionLocation, 2, GLES20.GL_FLOAT, false, 0, positions);
                                    GLES20.glVertexAttribPointer(coordsLocation, 2, GLES20.GL_FLOAT, false, 0, coords);
                                    GLES20.glUniformMatrix4fv(matrixLocation, 1, false, matrix, 0);
                                    GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
                                    GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, externalTexture);
                                    GLES20.glUniform1i(textureLocation, 0);
                                    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
                                    int drawError = GLES20.glGetError();
                                    if (drawError != GLES20.GL_NO_ERROR) {
                                        throw new IllegalStateException("camera snapshot draw failed: GL error 0x"
                                                + Integer.toHexString(drawError));
                                    }
                                    sample.copyNs = SystemClock.elapsedRealtimeNanos() - copyStart;
                                    frame.acquiredNs = acquired;
                                    frame.producerNs = timestamp;
                                } catch (Throwable error) {
                                    failure = appendFailure(failure, "copy camera SurfaceTexture frame", error);
                                }
                                if (failure == null) {
                                    long fenceStart = SystemClock.elapsedRealtimeNanos();
                                    try {
                                        frame.fence = finishUse();
                                        sample.fenceNs = SystemClock.elapsedRealtimeNanos() - fenceStart;
                                        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
                                        sample.copied = true;
                                        publish = true;
                                    } catch (Throwable error) {
                                        failure = appendFailure(failure,
                                                "finish camera snapshot GPU use", error);
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } finally {
            long releaseStart = SystemClock.elapsedRealtimeNanos();
            try {
                // An update may acquire input before throwing; GLConsumer supplies its release fence.
                input.releaseTexImage();
            } catch (Throwable error) {
                failure = appendFailure(failure, "release camera SurfaceTexture image", error);
            } finally {
                sample.releaseNs = SystemClock.elapsedRealtimeNanos() - releaseStart;
                sample.inputCycleNs = SystemClock.elapsedRealtimeNanos() - updateStart;
            }
        }

        if (publish && failure == null) {
            synchronized (outputSizeLock) {
                long size = outputSize;
                int width = (int) (size >>> 32), height = (int) size;
                if (width == copyWidth && height == copyHeight) {
                    try {
                        mailbox.publish(slot);
                        slot = -1;
                        published = true;
                    } catch (Throwable error) {
                        failure = appendFailure(failure, "publish camera snapshot", error);
                    }
                } else {
                    sample.noPreparedBuffer = true;
                }
            }
        }
        if (slot >= 0) {
            try {
                mailbox.cancel(slot);
            } catch (Throwable error) {
                failure = appendFailure(failure, "cancel unused camera snapshot", error);
            }
        }
        if (failure != null) {
            reportFailure(failure);
            return;
        }

        Throwable diagnosticFailure = null;
        long size = outputSize;
        try {
            reportSourceFormat((int) (size >>> 32), (int) size);
        } catch (Throwable error) {
            diagnosticFailure = appendFailure(diagnosticFailure, "report camera source format", error);
        }
        if (reportMatrix && !matrixReported && surface != null) {
            try {
                Log.i("BydCameraProbe", "{\"kind\":\"camera_texture_matrix\","
                        + "\"source\":\"direct_camera_source_hub\",\"stage\":\"avm_source\","
                        + "\"source_index\":" + index + ",\"pid\":" + Process.myPid()
                        + ",\"tid\":" + Process.myTid() + ",\"surface_identity\":"
                        + System.identityHashCode(surface) + ",\"preview_index\":" + index
                        + ",\"surface_texture_id\":" + System.identityHashCode(input)
                        + ",\"source_width\":" + sourceWidth
                        + ",\"source_height\":" + sourceHeight + ",\"output_width\":"
                        + (int) (size >>> 32) + ",\"output_height\":" + (int) size
                        + ",\"matrix\":" + Arrays.toString(matrix)
                        + ",\"worker\":\"" + thread.getName() + "\"}");
                matrixReported = true;
            } catch (Throwable error) {
                diagnosticFailure = appendFailure(diagnosticFailure,
                        "report camera texture matrix", error);
            }
        }
        if (published) {
            try {
                listener.available();
            } catch (Throwable error) {
                diagnosticFailure = appendFailure(diagnosticFailure,
                        "notify camera frame availability", error);
            }
        }
        try {
            listener.acquired(sample);
        } catch (Throwable error) {
            diagnosticFailure = appendFailure(diagnosticFailure,
                    "report camera acquisition metrics", error);
        }
        if (diagnosticFailure != null) reportFailure(diagnosticFailure);
    }

    private boolean preparedForOutput(int slot) {
        Frame frame = frames[slot];
        return frame.width == reserveOutputWidth && frame.height == reserveOutputHeight
                && readyToWrite(slot);
    }

    private boolean readyToWrite(int slot) {
        Frame frame = frames[slot];
        if (frame.fence == 0) return true;
        int status = GLES30.glClientWaitSync(frame.fence, 0, 0);
        if (status == GLES30.GL_WAIT_FAILED) {
            throw new IllegalStateException("camera snapshot fence wait failed: GL status 0x"
                    + Integer.toHexString(status));
        }
        if (status == GLES30.GL_TIMEOUT_EXPIRED) {
            if (trackingCaptureFence) captureFenceNotReady = true;
            return false;
        }
        GLES30.glDeleteSync(frame.fence);
        frame.fence = 0;
        return true;
    }

    void awaitRead(int slot) {
        Frame frame = frames[slot];
        if (frame.fence != 0) {
            GLES30.glWaitSync(frame.fence, 0, GLES30.GL_TIMEOUT_IGNORED);
            GLES30.glDeleteSync(frame.fence);
            frame.fence = 0;
        }
    }

    void releaseRead(int slot) {
        frames[slot].fence = finishUse();
        mailbox.release(slot);
    }

    void transferRead(int slot) {
        frames[slot].fence = finishUse();
    }

    private long finishUse() {
        if (glVersion >= 3) {
            long fence = GLES30.glFenceSync(GLES30.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
            if (fence == 0) {
                int error = GLES20.glGetError();
                throw new IllegalStateException("camera snapshot fence unavailable: GL error 0x"
                        + Integer.toHexString(error));
            }
            GLES20.glFlush();
            return fence;
        }
        // ES2 has no core cross-context fences; finish this context, never a downstream swap.
        GLES20.glFinish();
        return 0;
    }

    private boolean allocate(Frame frame, int width, int height) {
        if (frame.width == width && frame.height == height) return false;
        if (frame.texture == 0) {
            int[] names = new int[1];
            GLES20.glGenTextures(1, names, 0);
            frame.texture = names[0];
            GLES20.glGenFramebuffers(1, names, 0);
            frame.framebuffer = names[0];
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, frame.texture);
        configureTexture(GLES20.GL_TEXTURE_2D);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, frame.framebuffer);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, frame.texture, 0);
        int framebufferStatus = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER);
        if (framebufferStatus != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            throw new IllegalStateException("camera snapshot framebuffer incomplete: GL status 0x"
                    + Integer.toHexString(framebufferStatus));
        }
        frame.width = width;
        frame.height = height;
        return true;
    }

    private void reportSourceFormat(int outputWidth, int outputHeight) {
        if (surface == null || (reportedSourceWidth == sourceWidth
                && reportedSourceHeight == sourceHeight
                && reportedOutputWidth == outputWidth && reportedOutputHeight == outputHeight)) return;
        Log.i("BydCameraProbe", "{\"kind\":\"camera_source_format\","
                + "\"source\":\"direct_camera_source_hub\",\"stage\":\"avm_source\","
                + "\"source_index\":" + index + ",\"pid\":" + Process.myPid()
                + ",\"tid\":" + Process.myTid() + ",\"surface_identity\":"
                + System.identityHashCode(surface) + ",\"surface_texture_id\":"
                + System.identityHashCode(input) + ",\"source_width\":" + sourceWidth
                + ",\"source_height\":" + sourceHeight + ",\"output_width\":" + outputWidth
                + ",\"output_height\":" + outputHeight + ",\"worker\":\""
                + thread.getName() + "\"}");
        reportedSourceWidth = sourceWidth;
        reportedSourceHeight = sourceHeight;
        reportedOutputWidth = outputWidth;
        reportedOutputHeight = outputHeight;
    }

    private static Throwable appendFailure(Throwable primary, String operation, Throwable error) {
        Throwable contextual = new IllegalStateException(operation + " failed", error);
        if (primary == null) return contextual;
        primary.addSuppressed(contextual);
        return primary;
    }

    private void reportFailure(Throwable error) {
        failed = true;
        try {
            listener.failed(error);
        } catch (Throwable listenerError) {
            error.addSuppressed(new IllegalStateException("report camera acquisition failure failed",
                    listenerError));
            Log.e("BydCameraProbe", "camera acquisition failure handler failed", error);
        }
    }

    private static void configureTexture(int target) {
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
    }

    /** Called after the output worker has finished reading and retired its window surfaces. */
    void close() throws Exception {
        closed = true;
        mailbox.close();
        if (handler == null) return;
        DirectCameraSourceHub.SerializedCall<Void> done = new DirectCameraSourceHub.SerializedCall<>();
        require(handler.post(() -> done.run(() -> { releaseGl(); return null; })),
                "camera acquisition stopped before cleanup");
        try {
            DirectCameraSourceHub.callUninterruptibly(done::result);
        } finally {
            thread.quitSafely();
            DirectCameraSourceHub.callUninterruptibly(() -> { thread.join(); return null; });
        }
    }

    private void releaseGl() {
        try {
            if (context != EGL14.EGL_NO_CONTEXT && idle != EGL14.EGL_NO_SURFACE) {
                require(EGL14.eglMakeCurrent(display, idle, idle, context),
                        "camera acquisition cleanup context failed");
                for (Frame frame : frames) {
                    if (frame.fence != 0) {
                        GLES30.glWaitSync(frame.fence, 0, GLES30.GL_TIMEOUT_IGNORED);
                        GLES30.glDeleteSync(frame.fence);
                    }
                }
                GLES20.glFinish();
                for (Frame frame : frames) {
                    GLES20.glDeleteTextures(1, new int[]{frame.texture}, 0);
                    GLES20.glDeleteFramebuffers(1, new int[]{frame.framebuffer}, 0);
                }
                GLES20.glDeleteTextures(1, new int[]{externalTexture}, 0);
                GLES20.glDeleteProgram(program);
            }
        } finally {
            try {
                if (surface != null) surface.release();
            } finally {
                try {
                    if (input != null) { input.setOnFrameAvailableListener(null); input.release(); }
                } finally {
                    EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE,
                            EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                    if (idle != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, idle);
                    if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context);
                }
            }
        }
    }

    private static void require(boolean value, String message) {
        if (!value) throw new IllegalStateException(message);
    }
}
