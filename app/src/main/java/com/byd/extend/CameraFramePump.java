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
import android.os.SystemClock;
import android.util.Log;
import android.view.Surface;

import java.nio.FloatBuffer;
import java.util.Arrays;
import java.util.function.IntPredicate;

/** Drains the OEM input without ever calling a downstream window's eglSwapBuffers. */
final class CameraFramePump {
    interface Listener {
        void available();
        void failed(Throwable error);
        void acquired(long startedNs, long timestampNs, long updateNs, long copyNs, int signals);
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
    private int signals;
    private boolean queued, failed, matrixReported;
    private long lastFrameTimestamp;
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
        if (!handler.post(() -> ready.run(() -> { initialize(); return null; }))) {
            throw new IllegalStateException("camera acquisition thread stopped");
        }
        ready.result();
    }

    void setOutputSize(int width, int height) {
        outputSize = ((long) Math.min(sourceWidth, width) << 32)
                | (Math.min(sourceHeight, height) & 0xffffffffL);
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
            signals++;
            if (!queued) {
                queued = true;
                handler.post(capture);
            }
        }, handler);
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
        queued = false;
        int count = signals;
        signals = 0;
        if (closed || failed) return;
        long started = SystemClock.elapsedRealtimeNanos();
        try {
            input.updateTexImage();
            long acquired = System.nanoTime();
            long updated = SystemClock.elapsedRealtimeNanos();
            long timestamp;
            int slot = -1;
            try {
                timestamp = input.getTimestamp();
                // A late callback can find only the placeholder left by releaseTexImage().
                if (timestamp <= 0 || timestamp == lastFrameTimestamp) {
                    listener.acquired(started, timestamp, updated - started, 0, count);
                    return;
                }
                lastFrameTimestamp = timestamp;
                input.getTransformMatrix(matrix);
                if (!matrixReported) {
                    matrixReported = true;
                    Log.i("BydCameraProbe", "{\"kind\":\"camera_texture_matrix\","
                            + "\"source\":\"direct_camera_source_hub\",\"stage\":\"avm_source\","
                            + "\"preview_index\":" + index + ",\"surface_texture_id\":"
                            + System.identityHashCode(input) + ",\"matrix\":" + Arrays.toString(matrix)
                            + ",\"worker\":\"" + thread.getName() + "\"}");
                }
                long size = outputSize;
                int width = (int) (size >>> 32), height = (int) size;
                if (width > 0 && height > 0) {
                    slot = mailbox.reserve(readyToWritePredicate);
                    if (slot >= 0) {
                        Frame frame = frames[slot];
                        allocate(frame, width, height);
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
                        require(GLES20.glGetError() == GLES20.GL_NO_ERROR, "camera snapshot draw failed");
                        frame.acquiredNs = acquired;
                        frame.producerNs = timestamp;
                        frame.fence = finishUse();
                        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
                    }
                }
            } finally {
                // GLConsumer supplies the release fence; do not retain OEM input until the next frame.
                input.releaseTexImage();
            }
            if (slot >= 0) {
                mailbox.publish(slot);
                listener.available();
            }
            listener.acquired(started, timestamp, updated - started,
                    SystemClock.elapsedRealtimeNanos() - updated, count);
        } catch (Throwable error) {
            failed = true;
            listener.failed(error);
        }
    }

    private boolean readyToWrite(int slot) {
        Frame frame = frames[slot];
        if (frame.fence == 0) return true;
        int status = GLES30.glClientWaitSync(frame.fence, 0, 0);
        require(status != GLES30.GL_WAIT_FAILED, "camera snapshot fence failed");
        if (status == GLES30.GL_TIMEOUT_EXPIRED) return false;
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
            require(fence != 0, "camera snapshot fence unavailable");
            GLES20.glFlush();
            return fence;
        }
        // ES2 has no core cross-context fences; finish this context, never a downstream swap.
        GLES20.glFinish();
        return 0;
    }

    private void allocate(Frame frame, int width, int height) {
        if (frame.width == width && frame.height == height) return;
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
        require(GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
                == GLES20.GL_FRAMEBUFFER_COMPLETE, "camera snapshot framebuffer incomplete");
        frame.width = width;
        frame.height = height;
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
