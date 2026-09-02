package com.byd.extend;

import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Stable GPU fan-out boundary between full-size AVMCamera sources and logical consumers. */
final class DirectCameraSourceHub
        implements CameraHelperMain.HelperBinder.PersistentSurfaceFanout {
    interface Listener {
        void onConsumerFailure(Surface surface, int index, Throwable error);
        void onSourceFailure(int index, Throwable error);
        void onStats(int index, Stats stats);

        /** Optional diagnostic callback; older listeners do not need to implement it. */
        default void onTargetStall(Surface surface, int index, long swapWaitNs) {
        }
    }

    interface WorkerFinalizer {
        void dispatchClose() throws Exception;
        void awaitClose() throws Exception;
    }

    private static final int CALL_TIMEOUT_MS = 1500;
    private static final long STATS_INTERVAL_NS = TimeUnit.SECONDS.toNanos(5);
    private static final long STALL_REPORT_INTERVAL_NS = TimeUnit.SECONDS.toNanos(5);
    private static final int MAX_INDEX = 4;
    private static final String LOG_TAG = "BydCameraProbe";
    private static final String VERTEX_SHADER =
            "attribute vec2 aPosition;\n"
                    + "attribute vec2 aTexCoord;\n"
                    + "uniform mat4 uTextureMatrix;\n"
                    + "varying vec2 vTexCoord;\n"
                    + "void main(){\n"
                    + " gl_Position=vec4(aPosition,0.0,1.0);\n"
                    + " vTexCoord=(uTextureMatrix*vec4(aTexCoord,0.0,1.0)).xy;\n"
                    + "}\n";
    private static final String FRAGMENT_SHADER =
            "#extension GL_OES_EGL_image_external : require\n"
                    + "precision mediump float;\n"
                    + "uniform samplerExternalOES uTexture;\n"
                    + "varying vec2 vTexCoord;\n"
                    + "void main(){ gl_FragColor=texture2D(uTexture,vTexCoord); }\n";

    /** The coordinator owns the shared display/config and all identity routing. */
    private final HandlerThread coordinatorThread =
            new HandlerThread("direct-camera-source-coordinator");
    private final SourceWorker[] workers = new SourceWorker[MAX_INDEX + 1];
    private final IdentityHashMap<Surface, Target> targets = new IdentityHashMap<>();
    private final Listener listener;
    private Handler coordinatorHandler;
    private EGLDisplay display = EGL14.EGL_NO_DISPLAY;
    private EGLConfig config;
    private volatile boolean closed;

    private DirectCameraSourceHub(Listener listener) {
        this.listener = listener;
    }

    static DirectCameraSourceHub create(Listener listener) throws Exception {
        DirectCameraSourceHub result = new DirectCameraSourceHub(listener);
        result.coordinatorThread.start();
        result.coordinatorHandler = new Handler(result.coordinatorThread.getLooper());
        try {
            result.call(() -> {
                result.initializeDisplay();
                return null;
            });
            return result;
        } catch (Throwable error) {
            try {
                result.close();
            } catch (Throwable closeError) {
                error.addSuppressed(closeError);
            }
            throw error instanceof Exception ? (Exception) error : new Exception(error);
        }
    }

    @Override
    public Surface source(int index) throws Exception {
        requireIndex(index);
        return call(() -> worker(index).sourceSurface());
    }

    @Override
    public void attach(Surface[] surfaces, int[] indexes) throws Exception {
        if (surfaces == null || indexes == null || surfaces.length != indexes.length) {
            throw new IllegalArgumentException("surface/index batch length mismatch");
        }
        call(() -> {
            ArrayList<Target> attached = new ArrayList<>(surfaces.length);
            try {
                for (int i = 0; i < surfaces.length; i++) {
                    requireIndex(indexes[i]);
                    require(surfaces[i] != null, "downstream Surface is null");
                    synchronized (targets) {
                        if (targets.containsKey(surfaces[i])) {
                            throw new IllegalStateException("downstream Surface is already attached");
                        }
                    }
                    SourceWorker sourceWorker = worker(indexes[i]);
                    Target target = sourceWorker.attachTarget(surfaces[i], indexes[i]);
                    synchronized (targets) {
                        targets.put(surfaces[i], target);
                    }
                    attached.add(target);
                }
            } catch (Throwable error) {
                for (int i = attached.size() - 1; i >= 0; i--) {
                    Target target = attached.get(i);
                    try {
                        target.worker.detachTarget(target);
                        synchronized (targets) {
                            targets.remove(target.surface);
                        }
                    } catch (Throwable rollbackError) {
                        error.addSuppressed(rollbackError);
                    }
                }
                if (error instanceof Exception) throw (Exception) error;
                throw new Exception(error);
            }
            return null;
        });
    }

    @Override
    public void detach(Surface[] surfaces) throws Exception {
        if (surfaces == null) return;
        call(() -> {
            Throwable first = null;
            for (Surface surface : surfaces) {
                Target target;
                synchronized (targets) {
                    target = targets.get(surface);
                }
                if (target == null) continue;
                try {
                    target.worker.detachTarget(target);
                    synchronized (targets) {
                        targets.remove(surface);
                    }
                } catch (Throwable error) {
                    if (first == null) first = error;
                }
            }
            if (first instanceof Exception) throw (Exception) first;
            if (first != null) throw new Exception(first);
            return null;
        });
    }

    @Override
    public void setActive(Surface surface, boolean active) throws Exception {
        call(() -> {
            Target target;
            synchronized (targets) {
                target = targets.get(surface);
            }
            if (target == null) throw new IllegalStateException("downstream Surface is not attached");
            target.worker.setTargetActive(target, active);
            return null;
        });
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        Throwable first = null;
        try {
            callFinal(() -> {
                Throwable workerError = null;
                try {
                    closeWorkers(workers);
                } catch (Throwable error) {
                    workerError = error;
                }
                Arrays.fill(workers, null);
                try {
                    releaseDisplay();
                } catch (Throwable error) {
                    if (workerError == null) workerError = error;
                    else workerError.addSuppressed(error);
                }
                synchronized (targets) {
                    targets.clear();
                }
                if (workerError != null) {
                    if (workerError instanceof Exception) throw (Exception) workerError;
                    throw new Exception(workerError);
                }
                return null;
            });
        } catch (Throwable error) {
            first = error;
        } finally {
            coordinatorThread.quitSafely();
        }
        if (first != null) {
            throw new IllegalStateException("camera source cleanup failed", first);
        }
    }

    private void initializeDisplay() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        require(display != EGL14.EGL_NO_DISPLAY, "EGL display unavailable");
        require(EGL14.eglInitialize(display, null, 0, null, 0), "EGL init failed");
        EGLConfig[] configs = new EGLConfig[1];
        int[] count = new int[1];
        int[] attributes = {
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT | EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_NONE
        };
        require(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0)
                && count[0] > 0, "EGL config unavailable");
        config = configs[0];
    }

    private void releaseDisplay() {
        if (display == EGL14.EGL_NO_DISPLAY) return;
        EGL14.eglTerminate(display);
        display = EGL14.EGL_NO_DISPLAY;
        config = null;
    }

    private SourceWorker worker(int index) throws Exception {
        SourceWorker existing = workers[index];
        if (existing != null) return existing;
        SourceWorker created = new SourceWorker(index);
        try {
            created.start();
            workers[index] = created;
            return created;
        } catch (Throwable error) {
            try {
                created.closeFinal();
            } catch (Throwable closeError) {
                error.addSuppressed(closeError);
            }
            throw error instanceof Exception ? (Exception) error : new Exception(error);
        }
    }

    private void forgetTargetFromWorker(Target target) {
        synchronized (targets) {
            if (targets.get(target.surface) == target) targets.remove(target.surface);
        }
    }

    static boolean shouldRenderTarget(int sourceIndex, int targetIndex, boolean active) {
        return active && sourceIndex == targetIndex;
    }

    static String workerThreadName(int index) {
        requireIndex(index);
        return "direct-camera-source-" + index;
    }

    private final class SourceWorker implements WorkerFinalizer {
        final int index;
        final HandlerThread thread;
        final ArrayList<Target> targets = new ArrayList<>();
        final StatsWindow stats = new StatsWindow();
        final Object frameState = new Object();
        Handler handler;
        EGLContext context = EGL14.EGL_NO_CONTEXT;
        EGLSurface idleSurface = EGL14.EGL_NO_SURFACE;
        int program;
        int positionLocation;
        int texCoordLocation;
        int matrixLocation;
        int textureLocation;
        FloatBuffer positions;
        FloatBuffer texCoords;
        int textureName;
        SurfaceTexture texture;
        Surface surface;
        final float[] matrix = new float[16];
        final int[] targetWidths = new int[8];
        final int[] targetHeights = new int[8];
        boolean matrixReported;
        boolean sourceFailureReported;
        boolean renderQueued;
        int pendingFrameSignals;
        long firstPendingSignalNs = -1L;
        SerializedCall<Void> closeCall;

        SourceWorker(int index) {
            this.index = index;
            this.thread = new HandlerThread(workerThreadName(index));
        }

        void start() throws Exception {
            thread.start();
            handler = new Handler(thread.getLooper());
            call(() -> {
                initialize();
                return null;
            });
        }

        Surface sourceSurface() throws Exception {
            return call(() -> {
                if (surface == null) createSource();
                return surface;
            });
        }

        Target attachTarget(Surface value, int targetIndex) throws Exception {
            return call(() -> {
                require(value != null && value.isValid(), "downstream Surface is invalid");
                EGLSurface eglSurface = EGL14.eglCreateWindowSurface(display, config, value,
                        new int[]{EGL14.EGL_NONE}, 0);
                require(eglSurface != EGL14.EGL_NO_SURFACE, "downstream EGL surface failed");
                Target target = new Target(this, value, targetIndex, eglSurface);
                targets.add(target);
                return target;
            });
        }

        void detachTarget(Target target) throws Exception {
            call(() -> {
                if (!targets.remove(target)) return null;
                EGL14.eglDestroySurface(display, target.eglSurface);
                return null;
            });
        }

        void setTargetActive(Target target, boolean active) throws Exception {
            call(() -> {
                if (!targets.contains(target)) {
                    throw new IllegalStateException("downstream Surface is not attached");
                }
                target.active = active;
                if (active) target.timestampFrames = true;
                return null;
            });
        }

        void closeFinal() throws Exception {
            closeWorkers(new WorkerFinalizer[]{this});
        }

        @Override
        public void dispatchClose() throws Exception {
            if (closeCall != null) return;
            SerializedCall<Void> call = new SerializedCall<>();
            if (!handler.post(() -> {
                try {
                    call.run(() -> {
                        releaseResources();
                        return null;
                    });
                } finally {
                    thread.quitSafely();
                }
            })) {
                thread.quitSafely();
                throw new IllegalStateException(
                        "camera source worker stopped before cleanup");
            }
            closeCall = call;
        }

        @Override
        public void awaitClose() throws Exception {
            try {
                if (closeCall != null) {
                    callUninterruptibly(() -> awaitFinalizer(closeCall));
                }
            } finally {
                thread.quitSafely();
                callUninterruptibly(() -> {
                    thread.join();
                    return null;
                });
            }
        }

        private void initialize() {
            context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT,
                    new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
            require(context != EGL14.EGL_NO_CONTEXT, "EGL context failed");
            idleSurface = EGL14.eglCreatePbufferSurface(display, config,
                    new int[]{EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE}, 0);
            require(idleSurface != EGL14.EGL_NO_SURFACE, "EGL idle surface failed");
            makeCurrent(idleSurface);
            program = linkProgram(VERTEX_SHADER, FRAGMENT_SHADER);
            positionLocation = GLES20.glGetAttribLocation(program, "aPosition");
            texCoordLocation = GLES20.glGetAttribLocation(program, "aTexCoord");
            matrixLocation = GLES20.glGetUniformLocation(program, "uTextureMatrix");
            textureLocation = GLES20.glGetUniformLocation(program, "uTexture");
            positions = buffer(new float[]{-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f});
            texCoords = buffer(new float[]{0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f});
            createSource();
        }

        private void createSource() {
            int[] names = new int[1];
            GLES20.glGenTextures(1, names, 0);
            require(names[0] != 0, "camera source texture unavailable");
            textureName = names[0];
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureName);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                    GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                    GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                    GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                    GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            texture = new SurfaceTexture(textureName);
            texture.setDefaultBufferSize(1920, index == 0 ? 990 : 1300);
            surface = new Surface(texture);
            texture.setOnFrameAvailableListener(ignored -> signalFrame(), handler);
            Log.i(LOG_TAG, "{\"kind\":\"camera_source_worker_started\","
                    + "\"source_index\":" + index + ",\"worker\":\""
                    + workerThreadName(index) + "\"}");
        }

        private void signalFrame() {
            long nowNs = SystemClock.elapsedRealtimeNanos();
            boolean post;
            synchronized (frameState) {
                pendingFrameSignals++;
                if (firstPendingSignalNs < 0L) firstPendingSignalNs = nowNs;
                post = !renderQueued;
                renderQueued = true;
            }
            if (post) handler.post(this::render);
        }

        private void render() {
            long callbackStartedNs = SystemClock.elapsedRealtimeNanos();
            int frameSignals;
            long queueDelayNs;
            synchronized (frameState) {
                frameSignals = pendingFrameSignals;
                pendingFrameSignals = 0;
                queueDelayNs = firstPendingSignalNs < 0L
                        ? 0L : Math.max(0L, callbackStartedNs - firstPendingSignalNs);
                firstPendingSignalNs = -1L;
                renderQueued = false;
            }
            if (closed || sourceFailureReported || texture == null) return;
            long updatedNs;
            long updateNs;
            long producerTimestampNs;
            long acquiredFrameTimestampNanos;
            try {
                makeCurrent(idleSurface);
                acquiredFrameTimestampNanos = System.nanoTime();
                long updateStartedNs = SystemClock.elapsedRealtimeNanos();
                texture.updateTexImage();
                updatedNs = SystemClock.elapsedRealtimeNanos();
                updateNs = updatedNs - updateStartedNs;
                texture.getTransformMatrix(matrix);
                producerTimestampNs = texture.getTimestamp();
                if (!matrixReported) {
                    matrixReported = true;
                    Log.i(LOG_TAG, "{\"kind\":\"camera_texture_matrix\","
                            + "\"source\":\"direct_camera_source_hub\","
                            + "\"stage\":\"avm_source\",\"preview_index\":"
                            + index + ",\"surface_texture_id\":"
                            + System.identityHashCode(texture) + ",\"matrix\":"
                            + Arrays.toString(matrix) + ",\"worker\":\""
                            + workerThreadName(index) + "\"}");
                }
            } catch (Throwable error) {
                reportSourceFailure(error);
                return;
            }
            int targetCount = 0;
            int swapCount = 0;
            long preSwapTotalNs = 0L;
            long preSwapMaxNs = 0L;
            long swapWaitTotalNs = 0L;
            long swapWaitMaxNs = 0L;
            long drawMaxNs = 0L;
            long targetPixelsCurrent = 0L;
            int targetWidthMax = 0;
            int targetHeightMax = 0;
            int targetDimensionCount = 0;
            for (int i = targets.size() - 1; i >= 0; i--) {
                Target target = targets.get(i);
                if (!shouldRenderTarget(index, target.index, target.active)) continue;
                try {
                    DrawTiming timing = draw(target, acquiredFrameTimestampNanos);
                    targetCount++;
                    preSwapTotalNs += timing.preSwapNs;
                    preSwapMaxNs = Math.max(preSwapMaxNs, timing.preSwapNs);
                    swapWaitTotalNs += timing.swapWaitNs;
                    swapWaitMaxNs = Math.max(swapWaitMaxNs, timing.swapWaitNs);
                    drawMaxNs = Math.max(drawMaxNs, timing.preSwapNs + timing.swapWaitNs);
                    targetPixelsCurrent += (long) timing.width * timing.height;
                    targetWidthMax = Math.max(targetWidthMax, timing.width);
                    targetHeightMax = Math.max(targetHeightMax, timing.height);
                    if (targetDimensionCount < targetWidths.length) {
                        targetWidths[targetDimensionCount] = timing.width;
                        targetHeights[targetDimensionCount] = timing.height;
                        targetDimensionCount++;
                    }
                    swapCount++;
                } catch (Throwable error) {
                    EGL14.eglDestroySurface(display, target.eglSurface);
                    targets.remove(i);
                    forgetTargetFromWorker(target);
                    Surface failedSurface = target.surface;
                    int failedIndex = target.index;
                    postCoordinator(() -> listener.onConsumerFailure(
                            failedSurface, failedIndex, error));
                }
            }
            try {
                makeCurrent(idleSurface);
            } catch (Throwable error) {
                reportSourceFailure(error);
                return;
            }
            Stats report = stats.record(
                    callbackStartedNs,
                    producerTimestampNs,
                    updateNs,
                    preSwapTotalNs,
                    preSwapMaxNs,
                    swapWaitTotalNs,
                    swapWaitMaxNs,
                    drawMaxNs,
                    swapCount,
                    SystemClock.elapsedRealtimeNanos() - callbackStartedNs,
                    frameSignals,
                    Math.max(0, frameSignals - 1),
                    queueDelayNs,
                    targetCount,
                    targetPixelsCurrent,
                    targetWidthMax,
                    targetHeightMax,
                    targetWidths,
                    targetHeights,
                    targetDimensionCount,
                    1920,
                    index == 0 ? 990 : 1300,
                    workerThreadName(index));
            if (report != null) listener.onStats(index, report);
        }

        private void postCoordinator(Runnable action) {
            if (!coordinatorHandler.post(action)) {
                Log.w(LOG_TAG, "camera source coordinator stopped before failure callback");
            }
        }

        private void reportSourceFailure(Throwable error) {
            if (sourceFailureReported) return;
            sourceFailureReported = true;
            postCoordinator(() -> listener.onSourceFailure(index, error));
        }

        private DrawTiming draw(Target target, long acquiredFrameTimestampNanos) {
            long startedNs = SystemClock.elapsedRealtimeNanos();
            makeCurrent(target.eglSurface);
            int[] width = new int[1];
            int[] height = new int[1];
            require(EGL14.eglQuerySurface(display, target.eglSurface, EGL14.EGL_WIDTH, width, 0)
                    && EGL14.eglQuerySurface(display, target.eglSurface,
                    EGL14.EGL_HEIGHT, height, 0), "downstream size query failed");
            GLES20.glViewport(0, 0, width[0], height[0]);
            GLES20.glUseProgram(program);
            positions.position(0);
            texCoords.position(0);
            GLES20.glEnableVertexAttribArray(positionLocation);
            GLES20.glEnableVertexAttribArray(texCoordLocation);
            GLES20.glVertexAttribPointer(positionLocation, 2, GLES20.GL_FLOAT,
                    false, 0, positions);
            GLES20.glVertexAttribPointer(texCoordLocation, 2, GLES20.GL_FLOAT,
                    false, 0, texCoords);
            GLES20.glUniformMatrix4fv(matrixLocation, 1, false, matrix, 0);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureName);
            GLES20.glUniform1i(textureLocation, 0);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            require(GLES20.glGetError() == GLES20.GL_NO_ERROR, "GPU fanout draw failed");
            if (target.timestampFrames) {
                // Local monotonic time travels with the buffer, independent of the vendor clock.
                require(EGLExt.eglPresentationTimeANDROID(
                        display, target.eglSurface, acquiredFrameTimestampNanos),
                        "downstream frame timestamp failed");
            }
            long swapStartedNs = SystemClock.elapsedRealtimeNanos();
            require(EGL14.eglSwapBuffers(display, target.eglSurface),
                    "downstream buffer swap failed");
            long swapWaitNs = SystemClock.elapsedRealtimeNanos() - swapStartedNs;
            if (swapWaitNs >= TimeUnit.MILLISECONDS.toNanos(100)
                    && swapStartedNs - target.lastStallReportNs >= STALL_REPORT_INTERVAL_NS) {
                target.lastStallReportNs = swapStartedNs;
                try {
                    listener.onTargetStall(target.surface, target.index, swapWaitNs);
                } catch (Throwable error) {
                    Log.w(LOG_TAG, "target stall listener failed", error);
                }
            }
            DrawTiming timing = new DrawTiming();
            timing.preSwapNs = swapStartedNs - startedNs;
            timing.swapWaitNs = swapWaitNs;
            timing.width = width[0];
            timing.height = height[0];
            return timing;
        }

        private void releaseResources() {
            Throwable first = null;
            try {
                if (display != EGL14.EGL_NO_DISPLAY
                        && context != EGL14.EGL_NO_CONTEXT
                        && idleSurface != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglMakeCurrent(display, idleSurface, idleSurface, context);
                }
            } catch (Throwable error) {
                first = error;
            }
            for (Target target : new ArrayList<>(targets)) {
                try {
                    EGL14.eglDestroySurface(display, target.eglSurface);
                } catch (Throwable error) {
                    if (first == null) first = error;
                }
            }
            targets.clear();
            if (surface != null) {
                try {
                    surface.release();
                } catch (Throwable error) {
                    if (first == null) first = error;
                }
                surface = null;
            }
            if (texture != null) {
                try {
                    texture.setOnFrameAvailableListener(null);
                    texture.release();
                } catch (Throwable error) {
                    if (first == null) first = error;
                }
                texture = null;
            }
            if (textureName != 0) {
                try {
                    GLES20.glDeleteTextures(1, new int[]{textureName}, 0);
                } catch (Throwable error) {
                    if (first == null) first = error;
                }
                textureName = 0;
            }
            if (program != 0) {
                try {
                    GLES20.glDeleteProgram(program);
                } catch (Throwable error) {
                    if (first == null) first = error;
                }
                program = 0;
            }
            try {
                if (display != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE,
                            EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                    if (idleSurface != EGL14.EGL_NO_SURFACE) {
                        EGL14.eglDestroySurface(display, idleSurface);
                    }
                    if (context != EGL14.EGL_NO_CONTEXT) {
                        EGL14.eglDestroyContext(display, context);
                    }
                }
            } catch (Throwable error) {
                if (first == null) first = error;
            }
            idleSurface = EGL14.EGL_NO_SURFACE;
            context = EGL14.EGL_NO_CONTEXT;
            if (first != null) throw new IllegalStateException("source worker cleanup failed", first);
        }

        private void makeCurrent(EGLSurface value) {
            require(EGL14.eglMakeCurrent(display, value, value, context),
                    "EGL makeCurrent failed");
        }

        private <T> T call(Callable<T> callable) throws Exception {
            if (Looper.myLooper() == thread.getLooper()) return callable.call();
            SerializedCall<T> call = new SerializedCall<>();
            if (!handler.post(() -> call.run(callable))) {
                throw new IllegalStateException("camera source worker stopped");
            }
            if (!call.await(CALL_TIMEOUT_MS) && call.cancelIfQueued()) {
                throw new TimeoutException("camera source worker operation queue timed out");
            }
            return call.result();
        }

    }

    private <T> T call(Callable<T> callable) throws Exception {
        if (Looper.myLooper() == coordinatorThread.getLooper()) return callable.call();
        SerializedCall<T> call = new SerializedCall<>();
        if (!coordinatorHandler.post(() -> call.run(callable))) {
            throw new IllegalStateException("camera source coordinator stopped");
        }
        if (!call.await(CALL_TIMEOUT_MS) && call.cancelIfQueued()) {
            throw new TimeoutException("camera source operation queue timed out");
        }
        return call.result();
    }

    private <T> T callFinal(Callable<T> callable) throws Exception {
        if (Looper.myLooper() == coordinatorThread.getLooper()) return callable.call();
        SerializedCall<T> call = new SerializedCall<>();
        if (!coordinatorHandler.post(() -> call.run(callable))) {
            throw new IllegalStateException("camera source coordinator stopped before cleanup");
        }
        return awaitFinalizer(call);
    }

    static <T> T awaitFinalizer(SerializedCall<T> call) throws Exception {
        return call.result();
    }

    static <T> T callUninterruptibly(Callable<T> callable) throws Exception {
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    return callable.call();
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    static void closeWorkers(WorkerFinalizer[] finalizers) throws Exception {
        if (finalizers == null) return;
        Throwable first = null;
        for (WorkerFinalizer finalizer : finalizers) {
            if (finalizer == null) continue;
            try {
                finalizer.dispatchClose();
            } catch (Throwable error) {
                if (first == null) first = error;
                else first.addSuppressed(error);
            }
        }
        for (WorkerFinalizer finalizer : finalizers) {
            if (finalizer == null) continue;
            try {
                finalizer.awaitClose();
            } catch (Throwable error) {
                if (first == null) first = error;
                else first.addSuppressed(error);
            }
        }
        if (first instanceof Exception) throw (Exception) first;
        if (first != null) throw new Exception(first);
    }

    static final class SerializedCall<T> {
        private boolean started;
        private boolean cancelled;
        private boolean complete;
        private T value;
        private Throwable error;

        synchronized void run(Callable<T> callable) {
            if (cancelled) return;
            started = true;
            try {
                value = callable.call();
            } catch (Throwable failure) {
                error = failure;
            } finally {
                complete = true;
                notifyAll();
            }
        }

        synchronized boolean await(long timeoutMs) throws InterruptedException {
            if (!complete) wait(timeoutMs);
            return complete;
        }

        synchronized boolean cancelIfQueued() {
            if (complete || started) return false;
            cancelled = true;
            complete = true;
            notifyAll();
            return true;
        }

        synchronized T result() throws Exception {
            while (!complete) wait();
            if (cancelled) throw new TimeoutException("camera source operation cancelled");
            if (error instanceof Exception) throw (Exception) error;
            if (error != null) throw new Exception(error);
            return value;
        }
    }

    private static FloatBuffer buffer(float[] values) {
        FloatBuffer result = ByteBuffer.allocateDirect(values.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        result.put(values).position(0);
        return result;
    }

    private static int linkProgram(String vertexSource, String fragmentSource) {
        int vertex = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource);
        int fragment = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource);
        int result = GLES20.glCreateProgram();
        GLES20.glAttachShader(result, vertex);
        GLES20.glAttachShader(result, fragment);
        GLES20.glLinkProgram(result);
        int[] linked = new int[1];
        GLES20.glGetProgramiv(result, GLES20.GL_LINK_STATUS, linked, 0);
        GLES20.glDeleteShader(vertex);
        GLES20.glDeleteShader(fragment);
        require(linked[0] != 0, "GPU fanout program link failed");
        return result;
    }

    private static int compileShader(int type, String source) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        int[] compiled = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
        require(compiled[0] != 0, "GPU fanout shader compile failed");
        return shader;
    }

    private static void requireIndex(int index) {
        if (index < 0 || index > MAX_INDEX) {
            throw new IllegalArgumentException("Preview index must be 0..4");
        }
    }

    private static void require(boolean value, String message) {
        if (!value) throw new IllegalStateException(message);
    }

    static final class Stats {
        final long intervalNs;
        final int callbacks;
        final int frameSignals;
        final int renderedFrames;
        final int coalescedFrames;
        final long queueDelayTotalNs;
        final long queueDelayAvgNs;
        final long queueDelayMaxNs;
        final String workerName;
        final int callbackGaps;
        final long callbackGapTotalNs;
        final long callbackGapMaxNs;
        final long updateTotalNs;
        final long updateMaxNs;
        final int producerTimestampRepeated;
        final int producerTimestampInvalid;
        final int swaps;
        final long preSwapTotalNs;
        final long preSwapMaxNs;
        final long swapWaitTotalNs;
        final long swapWaitMaxNs;
        final long drawMaxNs;
        final long renderTotalNs;
        final long renderMaxNs;
        final int targetsCurrent;
        final int targetsMax;
        final long targetPixelsCurrent;
        final long targetPixelsMax;
        final int targetWidthMax;
        final int targetHeightMax;
        final String targetDimensions;
        final int sourceWidth;
        final int sourceHeight;

        Stats(
                long intervalNs,
                int callbacks,
                int frameSignals,
                int renderedFrames,
                int coalescedFrames,
                long queueDelayTotalNs,
                long queueDelayMaxNs,
                String workerName,
                int callbackGaps,
                long callbackGapTotalNs,
                long callbackGapMaxNs,
                long updateTotalNs,
                long updateMaxNs,
                int producerTimestampRepeated,
                int producerTimestampInvalid,
                int swaps,
                long preSwapTotalNs,
                long preSwapMaxNs,
                long swapWaitTotalNs,
                long swapWaitMaxNs,
                long drawMaxNs,
                long renderTotalNs,
                long renderMaxNs,
                int targetsCurrent,
                int targetsMax,
                long targetPixelsCurrent,
                long targetPixelsMax,
                int targetWidthMax,
                int targetHeightMax,
                String targetDimensions,
                int sourceWidth,
                int sourceHeight) {
            this.intervalNs = intervalNs;
            this.callbacks = callbacks;
            this.frameSignals = frameSignals;
            this.renderedFrames = renderedFrames;
            this.coalescedFrames = coalescedFrames;
            this.queueDelayTotalNs = queueDelayTotalNs;
            this.queueDelayAvgNs = renderedFrames <= 0
                    ? 0L : queueDelayTotalNs / renderedFrames;
            this.queueDelayMaxNs = queueDelayMaxNs;
            this.workerName = workerName;
            this.callbackGaps = callbackGaps;
            this.callbackGapTotalNs = callbackGapTotalNs;
            this.callbackGapMaxNs = callbackGapMaxNs;
            this.updateTotalNs = updateTotalNs;
            this.updateMaxNs = updateMaxNs;
            this.producerTimestampRepeated = producerTimestampRepeated;
            this.producerTimestampInvalid = producerTimestampInvalid;
            this.swaps = swaps;
            this.preSwapTotalNs = preSwapTotalNs;
            this.preSwapMaxNs = preSwapMaxNs;
            this.swapWaitTotalNs = swapWaitTotalNs;
            this.swapWaitMaxNs = swapWaitMaxNs;
            this.drawMaxNs = drawMaxNs;
            this.renderTotalNs = renderTotalNs;
            this.renderMaxNs = renderMaxNs;
            this.targetsCurrent = targetsCurrent;
            this.targetsMax = targetsMax;
            this.targetPixelsCurrent = targetPixelsCurrent;
            this.targetPixelsMax = targetPixelsMax;
            this.targetWidthMax = targetWidthMax;
            this.targetHeightMax = targetHeightMax;
            this.targetDimensions = targetDimensions;
            this.sourceWidth = sourceWidth;
            this.sourceHeight = sourceHeight;
        }
    }

    static final class StatsWindow {
        private long startedNs = -1L;
        private long previousCallbackNs = -1L;
        private int callbacks;
        private int frameSignals;
        private int renderedFrames;
        private int coalescedFrames;
        private long queueDelayTotalNs;
        private long queueDelayMaxNs;
        private int callbackGaps;
        private long callbackGapTotalNs;
        private long callbackGapMaxNs;
        private long updateTotalNs;
        private long updateMaxNs;
        private long previousProducerTimestamp = -1L;
        private int producerTimestampRepeated;
        private int producerTimestampInvalid;
        private int swaps;
        private long preSwapTotalNs;
        private long preSwapMaxNs;
        private long swapWaitTotalNs;
        private long swapWaitMaxNs;
        private long drawMaxNs;
        private long renderTotalNs;
        private long renderMaxNs;
        private int targetsMax;
        private long targetPixelsMax;
        private int targetWidthMax;
        private int targetHeightMax;
        private String targetDimensions = "";

        Stats record(
                long callbackNs,
                long producerTimestampNs,
                long updateNs,
                long framePreSwapTotalNs,
                long framePreSwapMaxNs,
                long frameSwapWaitTotalNs,
                long frameSwapWaitMaxNs,
                long frameDrawMaxNs,
                int frameSwaps,
                long renderNs,
                int targetsCurrent,
                long targetPixelsCurrent,
                int frameTargetWidthMax,
                int frameTargetHeightMax,
                int[] frameTargetWidths,
                int[] frameTargetHeights,
                int frameTargetCount,
                int sourceWidth,
                int sourceHeight) {
            return record(callbackNs, producerTimestampNs, updateNs,
                    framePreSwapTotalNs, framePreSwapMaxNs, frameSwapWaitTotalNs,
                    frameSwapWaitMaxNs, frameDrawMaxNs, frameSwaps, renderNs,
                    1, 0, 0L, targetsCurrent, targetPixelsCurrent,
                    frameTargetWidthMax, frameTargetHeightMax, frameTargetWidths,
                    frameTargetHeights, frameTargetCount, sourceWidth, sourceHeight, "");
        }

        Stats record(
                long callbackNs,
                long producerTimestampNs,
                long updateNs,
                long framePreSwapTotalNs,
                long framePreSwapMaxNs,
                long frameSwapWaitTotalNs,
                long frameSwapWaitMaxNs,
                long frameDrawMaxNs,
                int frameSwaps,
                long renderNs,
                int frameSignalCount,
                int frameCoalescedCount,
                long frameQueueDelayNs,
                int targetsCurrent,
                long targetPixelsCurrent,
                int frameTargetWidthMax,
                int frameTargetHeightMax,
                int[] frameTargetWidths,
                int[] frameTargetHeights,
                int frameTargetCount,
                int sourceWidth,
                int sourceHeight,
                String workerName) {
            if (startedNs < 0L) startedNs = callbackNs;
            if (previousCallbackNs >= 0L) {
                long gap = Math.max(0L, callbackNs - previousCallbackNs);
                callbackGaps++;
                callbackGapTotalNs += gap;
                callbackGapMaxNs = Math.max(callbackGapMaxNs, gap);
            }
            previousCallbackNs = callbackNs;
            callbacks++;
            frameSignals += Math.max(0, frameSignalCount);
            renderedFrames++;
            coalescedFrames += Math.max(0, frameCoalescedCount);
            queueDelayTotalNs += Math.max(0L, frameQueueDelayNs);
            queueDelayMaxNs = Math.max(queueDelayMaxNs, frameQueueDelayNs);
            recordProducerTimestamp(producerTimestampNs);
            updateTotalNs += Math.max(0L, updateNs);
            updateMaxNs = Math.max(updateMaxNs, updateNs);
            swaps += Math.max(0, frameSwaps);
            preSwapTotalNs += Math.max(0L, framePreSwapTotalNs);
            preSwapMaxNs = Math.max(preSwapMaxNs, framePreSwapMaxNs);
            swapWaitTotalNs += Math.max(0L, frameSwapWaitTotalNs);
            swapWaitMaxNs = Math.max(swapWaitMaxNs, frameSwapWaitMaxNs);
            drawMaxNs = Math.max(drawMaxNs, frameDrawMaxNs);
            renderTotalNs += Math.max(0L, renderNs);
            renderMaxNs = Math.max(renderMaxNs, renderNs);
            targetsMax = Math.max(targetsMax, targetsCurrent);
            targetPixelsMax = Math.max(targetPixelsMax, targetPixelsCurrent);
            targetWidthMax = Math.max(targetWidthMax, frameTargetWidthMax);
            targetHeightMax = Math.max(targetHeightMax, frameTargetHeightMax);
            if (callbackNs - startedNs < STATS_INTERVAL_NS) return null;
            targetDimensions = formatDimensions(
                    frameTargetWidths, frameTargetHeights, frameTargetCount);
            Stats result = new Stats(
                    callbackNs - startedNs,
                    callbacks, frameSignals, renderedFrames, coalescedFrames,
                    queueDelayTotalNs, queueDelayMaxNs, workerName,
                    callbackGaps, callbackGapTotalNs, callbackGapMaxNs,
                    updateTotalNs, updateMaxNs,
                    producerTimestampRepeated, producerTimestampInvalid,
                    swaps, preSwapTotalNs, preSwapMaxNs, swapWaitTotalNs,
                    swapWaitMaxNs, drawMaxNs, renderTotalNs, renderMaxNs,
                    targetsCurrent, targetsMax, targetPixelsCurrent, targetPixelsMax,
                    targetWidthMax, targetHeightMax, targetDimensions,
                    sourceWidth, sourceHeight);
            reset(callbackNs);
            return result;
        }

        private static String formatDimensions(int[] widths, int[] heights, int count) {
            if (widths == null || heights == null || count <= 0) return "";
            int safeCount = Math.min(count, Math.min(widths.length, heights.length));
            StringBuilder value = new StringBuilder(safeCount * 12);
            for (int i = 0; i < safeCount; i++) {
                if (i > 0) value.append(';');
                value.append(widths[i]).append('x').append(heights[i]);
            }
            return value.toString();
        }

        private void recordProducerTimestamp(long timestamp) {
            // Vendor units/clock are unverified: only equality and ordering are meaningful.
            if (timestamp <= 0L) {
                producerTimestampInvalid++;
                return;
            }
            if (previousProducerTimestamp > 0L) {
                if (timestamp == previousProducerTimestamp) {
                    producerTimestampRepeated++;
                } else if (timestamp < previousProducerTimestamp) {
                    producerTimestampInvalid++;
                    return;
                }
            }
            previousProducerTimestamp = timestamp;
        }

        private void reset(long callbackNs) {
            startedNs = callbackNs;
            previousCallbackNs = -1L;
            callbacks = 0;
            frameSignals = 0;
            renderedFrames = 0;
            coalescedFrames = 0;
            queueDelayTotalNs = 0L;
            queueDelayMaxNs = 0L;
            callbackGaps = 0;
            callbackGapTotalNs = 0L;
            callbackGapMaxNs = 0L;
            updateTotalNs = 0L;
            updateMaxNs = 0L;
            producerTimestampRepeated = 0;
            producerTimestampInvalid = 0;
            swaps = 0;
            preSwapTotalNs = 0L;
            preSwapMaxNs = 0L;
            swapWaitTotalNs = 0L;
            swapWaitMaxNs = 0L;
            drawMaxNs = 0L;
            renderTotalNs = 0L;
            renderMaxNs = 0L;
            targetsMax = 0;
            targetPixelsMax = 0L;
            targetWidthMax = 0;
            targetHeightMax = 0;
            targetDimensions = "";
        }
    }

    private static final class DrawTiming {
        long preSwapNs;
        long swapWaitNs;
        int width;
        int height;
    }

    private final class Target {
        final SourceWorker worker;
        final Surface surface;
        final int index;
        final EGLSurface eglSurface;
        long lastStallReportNs;
        boolean active = true;
        boolean timestampFrames;

        Target(SourceWorker worker, Surface surface, int index, EGLSurface eglSurface) {
            this.worker = worker;
            this.surface = surface;
            this.index = index;
            this.eglSurface = eglSurface;
        }
    }
}
