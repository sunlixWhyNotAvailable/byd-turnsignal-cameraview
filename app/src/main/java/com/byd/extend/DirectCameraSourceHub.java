package com.byd.extend;

import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
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
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Stable GPU fan-out boundary between full-size AVMCamera sources and logical consumers. */
final class DirectCameraSourceHub
        implements CameraHelperMain.HelperBinder.PersistentSurfaceFanout {
    interface Listener {
        void onConsumerFailure(
                Surface surface, int index, int requestId, int producerEpoch, Throwable error);
        void onSourceFailure(int index, Throwable error);
        void onStats(int index, Stats stats);

        default void onFirstFrame(int index, long windowId, long elapsedNs) {
        }

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
    static final long FPS_STATS_WINDOW_NS = TimeUnit.SECONDS.toNanos(15);
    private static final long STALL_REPORT_INTERVAL_NS = TimeUnit.SECONDS.toNanos(5);
    private static final long SWAP_FAILURE_LOG_INTERVAL_NS = TimeUnit.SECONDS.toNanos(5);
    private static final int MAX_INDEX = 4;
    private static final String LOG_TAG = "BydCameraProbe";
    static final String VERTEX_SHADER =
            "attribute vec2 aPosition;\n"
                    + "attribute vec2 aTexCoord;\n"
                    + "uniform mat4 uTextureMatrix;\n"
                    + "varying vec2 vRawTexCoord;\n"
                    + "varying vec2 vTexCoord;\n"
                    + "void main(){\n"
                    + " gl_Position=vec4(aPosition,0.0,1.0);\n"
                    + " vRawTexCoord=aTexCoord;\n"
                    + " vTexCoord=(uTextureMatrix*vec4(aTexCoord,0.0,1.0)).xy;\n"
                    + "}\n";
    private static final String OUTPUT_VERTEX_SHADER =
            "uniform mat4 uOutputMatrix;\n" + VERTEX_SHADER.replace(
                    "gl_Position=vec4(aPosition,0.0,1.0);",
                    "gl_Position=uOutputMatrix*vec4(aPosition,0.0,1.0);");
    static final String FRAGMENT_SHADER =
            "#extension GL_OES_EGL_image_external : require\n"
                    + "precision mediump float;\n"
                    + "uniform samplerExternalOES uTexture;\n"
                    + "varying vec2 vRawTexCoord;\n"
                    + "varying vec2 vTexCoord;\n"
                    + "void main(){\n"
                    + " if(vRawTexCoord.x<0.0||vRawTexCoord.x>1.0||vRawTexCoord.y<0.0||vRawTexCoord.y>1.0"
                    + "||vTexCoord.x<0.0||vTexCoord.x>1.0||vTexCoord.y<0.0||vTexCoord.y>1.0)"
                    + "{gl_FragColor=vec4(0.0,0.0,0.0,1.0);return;}\n"
                    + " gl_FragColor=texture2D(uTexture,vTexCoord); }\n";
    private static final String SNAPSHOT_FRAGMENT_SHADER =
            "precision mediump float;\n"
                    + "uniform sampler2D uTexture;\n"
                    + "uniform bool uCheckBounds;\n"
                    + "varying vec2 vTexCoord;\n"
                    + "void main(){\n"
                    + " if(uCheckBounds && (vTexCoord.x<0.0||vTexCoord.x>1.0"
                    + "||vTexCoord.y<0.0||vTexCoord.y>1.0))"
                    + "{gl_FragColor=vec4(0.0,0.0,0.0,1.0);return;}\n"
                    + " gl_FragColor=texture2D(uTexture,vTexCoord); }\n";

    /** The coordinator owns the shared display/config and all identity routing. */
    private final HandlerThread coordinatorThread =
            new HandlerThread("direct-camera-source-coordinator");
    private final SourceWorker[] workers = new SourceWorker[MAX_INDEX + 1];
    private final IdentityHashMap<Surface, Target> targets = new IdentityHashMap<>();
    private final Listener listener;
    private long lastSwapFailureLogNs = Long.MIN_VALUE;
    private Handler coordinatorHandler;
    private EGLDisplay display = EGL14.EGL_NO_DISPLAY;
    private EGLConfig config;
    private EGLContext sharedContext = EGL14.EGL_NO_CONTEXT;
    private int sharedGlVersion = 3;
    private final android.content.Context renderingContext;
    private final CameraMeshCache meshCache;
    private volatile boolean closed;
    private volatile FpsMeasurementWindow fpsWindow = new FpsMeasurementWindow(0, 0, 0);

    private DirectCameraSourceHub(android.content.Context renderingContext, Listener listener) {
        this.renderingContext = renderingContext;
        this.meshCache = CameraMeshPreparation.cache(renderingContext);
        this.listener = listener;
    }

    static DirectCameraSourceHub create(android.content.Context context, Listener listener) throws Exception {
        DirectCameraSourceHub result = new DirectCameraSourceHub(context, listener);
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

    synchronized long collectStatsForWindow(int requestedFps) {
        long nowNs = SystemClock.elapsedRealtimeNanos();
        long nextWindowId = fpsWindow.id + 1L;
        if (nextWindowId <= 0L) nextWindowId = 1L;
        fpsWindow = new FpsMeasurementWindow(nextWindowId, requestedFps, nowNs);
        return nextWindowId;
    }

    boolean statsCollectionActive() {
        FpsMeasurementWindow window = fpsWindow;
        return statsCollectionActive(DiagnosticLogPolicy.extended(),
                SystemClock.elapsedRealtimeNanos(),
                window.startedNs, window.untilNs);
    }

    static boolean statsCollectionActive(
            boolean extended, long nowNs, long windowStartedNs, long windowUntilNs) {
        return extended || windowStartedNs > 0L && nowNs >= windowStartedNs
                && nowNs < windowUntilNs;
    }

    @Override
    public void attach(Surface[] surfaces, int[] indexes,
            int requestId, int producerEpoch) throws Exception {
        attach(surfaces, indexes, requestId, producerEpoch, null);
    }

    @Override
    public void attach(Surface[] surfaces, int[] indexes,
            int requestId, int producerEpoch, boolean[] initialActive) throws Exception {
        if (surfaces == null || indexes == null || surfaces.length != indexes.length
                || initialActive != null && initialActive.length != surfaces.length) {
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
                    MotionRecordingOutput recording = MotionRecordingOutput.find(surfaces[i]);
                    if (recording != null)
                        recording.prepare(renderingContext, sharedContext, sharedGlVersion);
                    Target target = sourceWorker.attachTarget(
                            surfaces[i], indexes[i], requestId, producerEpoch,
                            initialActive == null || initialActive[i], initialActive != null);
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
            } finally {
                for (Surface surface : surfaces) {
                    MotionRecordingOutput recording = MotionRecordingOutput.find(surface);
                    if (recording != null) recording.closeUnused();
                }
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
    public void releaseSource(int index) throws Exception {
        requireIndex(index);
        call(() -> {
            SourceWorker existing = workers[index];
            if (existing == null) return null;
            synchronized (targets) {
                for (Target target : targets.values()) {
                    require(target.worker != existing,
                            "camera source still has an attached downstream Surface");
                }
            }
            existing.closeFinal();
            if (workers[index] == existing) workers[index] = null;
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
        sharedContext = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT,
                new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, sharedGlVersion, EGL14.EGL_NONE}, 0);
        if (sharedContext == EGL14.EGL_NO_CONTEXT) {
            EGL14.eglGetError();
            sharedGlVersion = 2;
            sharedContext = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT,
                    new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, sharedGlVersion, EGL14.EGL_NONE}, 0);
        }
        require(sharedContext != EGL14.EGL_NO_CONTEXT, "shared camera context failed");
    }

    private void releaseDisplay() {
        if (display == EGL14.EGL_NO_DISPLAY) return;
        if (sharedContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, sharedContext);
        sharedContext = EGL14.EGL_NO_CONTEXT;
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

    private boolean isCurrentWorker(SourceWorker worker) {
        return !closed && !worker.released && workers[worker.index] == worker;
    }

    static boolean shouldRenderTarget(int sourceIndex, int targetIndex, boolean active) {
        return active && sourceIndex == targetIndex;
    }

    static boolean isFpsStatsWindowActive(
            long nowNs, long windowId, long activeWindowId,
            long windowStartedNs, long windowUntilNs) {
        return windowId > 0L && windowId == activeWindowId
                && windowStartedNs > 0L && nowNs >= windowStartedNs
                && nowNs < windowUntilNs;
    }

    static boolean isFusedTarget(CameraGpuOutput.Binding mapping) {
        return mapping != null && !mapping.lifecycleOnly;
    }

    private static boolean isFusedTarget(Target target) {
        return isFusedTarget(target.mapping);
    }

    static boolean isLifecycleOnlyTarget(CameraGpuOutput.Binding mapping) {
        return mapping != null && mapping.lifecycleOnly;
    }

    private static boolean isLifecycleOnlyTarget(Target target) {
        return isLifecycleOnlyTarget(target.mapping);
    }

    static boolean shouldReportSwapFailure(long nowNs, long lastReportNs) {
        return lastReportNs == Long.MIN_VALUE
                || nowNs - lastReportNs >= SWAP_FAILURE_LOG_INTERVAL_NS;
    }

    private synchronized boolean acquireSwapFailureLog(long nowNs) {
        if (!shouldReportSwapFailure(nowNs, lastSwapFailureLogNs)) return false;
        lastSwapFailureLogNs = nowNs;
        return true;
    }

    private void reportSwapFailure(Target target, int eglError) {
        long nowNs = SystemClock.elapsedRealtimeNanos();
        if (!acquireSwapFailureLog(nowNs)) return;
        long mappingToken = target.mapping == null || target.mapping.request == null
                ? -1L : target.mapping.request.token;
        Log.w(LOG_TAG, "{\"kind\":\"camera_gpu_swap_failure\",\"source_index\":"
                + target.index + ",\"output_surface_id\":"
                + System.identityHashCode(target.surface)
                + ",\"request_id\":" + target.requestId
                + ",\"producer_epoch\":" + target.producerEpoch
                + ",\"mapping_token\":" + mappingToken
                + ",\"retiring\":" + target.retiring
                + ",\"egl_error\":" + eglError + "}");
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
        final StatsWindow acquisitionStats = new StatsWindow();
        final Object frameState = new Object();
        Handler handler;
        EGLContext context = EGL14.EGL_NO_CONTEXT;
        EGLSurface idleSurface = EGL14.EGL_NO_SURFACE;
        int program;
        int positionLocation;
        int texCoordLocation;
        int matrixLocation;
        int outputMatrixLocation;
        int textureLocation;
        int checkBoundsLocation;
        FloatBuffer positions;
        FloatBuffer texCoords;
        int textureName;
        Surface surface;
        CameraFramePump pump;
        int glVersion = 3;
        final float[] matrix = new float[16];
        final int[] targetWidths = new int[8];
        final int[] targetHeights = new int[8];
        final int[] queriedSize = new int[1];
        final AtomicBoolean sourceFailureReported = new AtomicBoolean();
        final Runnable renderTask = this::render;
        boolean renderQueued;
        int pendingFrameSignals;
        long firstPendingSignalNs = -1L;
        long statsWindowGeneration;
        long statsWindowFirstFrameNs;
        boolean statsWindowFinished;
        long acquisitionWindowGeneration;
        long acquisitionWindowFirstFrameNs;
        long lastAcquiredProducerTimestamp;
        boolean acquisitionWindowFinished;
        long droppedAtReport;
        long outputAgeMaxNs;
        SerializedCall<Void> closeCall;
        volatile boolean released;

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
                require(surface != null, "camera source unavailable");
                return surface;
            });
        }

        Target attachTarget(
                Surface value, int targetIndex, int requestId, int producerEpoch,
                boolean initialActive, boolean activitySpecified)
                throws Exception {
            return call(() -> {
                require(value != null && value.isValid(), "downstream Surface is invalid");
                MotionRecordingOutput recording = MotionRecordingOutput.find(value);
                // Lease first: the owner must not abandon its TextureView during EGL attachment.
                CameraGpuOutput.Binding mapping = CameraGpuOutput.bind(value, () -> postCoordinator(() -> {
                    try {
                        DirectCameraSourceHub.this.detach(new Surface[]{value});
                    } catch (Exception error) {
                        handler.post(() -> reportSourceFailure(error));
                    }
                }));
                if (mapping != null && !mapping.lifecycleOnly) {
                    meshCache.request(mapping.frameConfiguration().mapping.config,
                            mapping.width, mapping.height);
                }
                EGLSurface eglSurface;
                try {
                    eglSurface = recording == null ? EGL14.eglCreateWindowSurface(display, config, value,
                            new int[]{EGL14.EGL_NONE}, 0) : EGL14.EGL_NO_SURFACE;
                    require(recording != null || eglSurface != EGL14.EGL_NO_SURFACE,
                            "downstream EGL surface failed");
                } catch (Throwable failure) {
                    if (mapping != null) mapping.close();
                    throw failure;
                }
                Target target = new Target(
                        this, value, targetIndex, eglSurface, requestId, producerEpoch);
                target.mapping = mapping;
                target.recording = recording;
                if (recording != null) recording.retain();
                target.active = initialActive;
                // Preserve the timestamps previously enabled by the post-attach activation call.
                target.timestampFrames = activitySpecified && initialActive
                        || mapping != null && mapping.lifecycleOnly;
                try {
                    updateTargetSize(target);
                    targets.add(target);
                    refreshCaptureSize();
                    resetOutputStats();
                } catch (Throwable error) {
                    targets.remove(target);
                    try { destroyTarget(target); }
                    catch (Throwable cleanup) { error.addSuppressed(cleanup); }
                    throw error;
                }
                Log.i(LOG_TAG, "{\"kind\":\"camera_gpu_output_attached\",\"source_index\":"
                        + targetIndex + ",\"request_id\":" + requestId
                        + ",\"producer_epoch\":" + producerEpoch
                        + ",\"fused\":" + isFusedTarget(target)
                        + ",\"lifecycle_only\":" + isLifecycleOnlyTarget(target) + "}");
                return target;
            });
        }

        void detachTarget(Target target) throws Exception {
            call(() -> {
                if (!targets.contains(target)) return null;
                target.retiring = true;
                destroyTarget(target);
                targets.remove(target);
                refreshCaptureSize();
                resetOutputStats();
                return null;
            });
        }

        private void destroyTarget(Target target) {
            target.retiring = true;
            if (target.recording != null) {
                target.recording.detach(target.index);
                target.recording = null;
                return;
            }
            makeCurrent(idleSurface);
            // Detach is the teardown barrier; do not drain GPU work on the frame path.
            GLES20.glFinish();
            require(EGL14.eglDestroySurface(display, target.eglSurface), "downstream EGL release failed");
            if (target.alternateEglSurface != EGL14.EGL_NO_SURFACE)
                require(EGL14.eglDestroySurface(display, target.alternateEglSurface),
                        "alternate downstream EGL release failed");
            if (target.mapping != null) target.mapping.close();
        }

        void setTargetActive(Target target, boolean active) throws Exception {
            call(() -> {
                if (!targets.contains(target)) {
                    throw new IllegalStateException("downstream Surface is not attached");
                }
                if (target.active != active) resetOutputStats();
                target.active = active;
                if (active) target.timestampFrames = true;
                refreshCaptureSize();
                return null;
            });
        }

        void closeFinal() throws Exception {
            closeWorkers(new WorkerFinalizer[]{this});
        }

        @Override
        public void dispatchClose() throws Exception {
            if (closeCall != null) return;
            released = true;
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
            glVersion = sharedGlVersion;
            context = EGL14.eglCreateContext(display, config, sharedContext,
                    new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, glVersion, EGL14.EGL_NONE}, 0);
            require(context != EGL14.EGL_NO_CONTEXT, "EGL context failed");
            idleSurface = EGL14.eglCreatePbufferSurface(display, config,
                    new int[]{EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE}, 0);
            require(idleSurface != EGL14.EGL_NO_SURFACE, "EGL idle surface failed");
            makeCurrent(idleSurface);
            program = CameraDewarpRenderer.linkProgram(
                    OUTPUT_VERTEX_SHADER, SNAPSHOT_FRAGMENT_SHADER);
            positionLocation = GLES20.glGetAttribLocation(program, "aPosition");
            texCoordLocation = GLES20.glGetAttribLocation(program, "aTexCoord");
            matrixLocation = GLES20.glGetUniformLocation(program, "uTextureMatrix");
            outputMatrixLocation = GLES20.glGetUniformLocation(program, "uOutputMatrix");
            textureLocation = GLES20.glGetUniformLocation(program, "uTexture");
            checkBoundsLocation = GLES20.glGetUniformLocation(program, "uCheckBounds");
            positions = buffer(new float[]{-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f});
            texCoords = buffer(new float[]{0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f});
            android.opengl.Matrix.setIdentityM(matrix, 0);
            createSource();
        }

        private void createSource() {
            pump = new CameraFramePump(display, config, context, glVersion, index,
                    new CameraFramePump.Listener() {
                        @Override public void available() { signalFrame(); }
                        @Override public void failed(Throwable error) {
                            reportSourceFailure(error);
                        }
                        @Override public void acquired(long started, long timestamp, long update,
                                                       long copy, int signals) {
                            recordAcquisition(started, timestamp, update, copy, signals);
                        }
                    });
            try { pump.start(); }
            catch (Exception error) { throw new IllegalStateException("camera acquisition failed", error); }
            surface = pump.surface;
            Log.i(LOG_TAG, "{\"kind\":\"camera_source_worker_started\","
                    + "\"source_index\":" + index + ",\"worker\":\""
                    + workerThreadName(index) + "\",\"gl_version\":" + glVersion + "}");
        }

        private void refreshCaptureSize() {
            int width = 0, height = 0;
            for (Target target : targets) {
                if (!target.active) continue;
                width = Math.max(width, target.width);
                height = Math.max(height, target.height);
            }
            pump.setOutputSize(width, height);
        }

        private void resetOutputStats() {
            stats.restart();
            droppedAtReport = pump.mailbox.dropped();
            outputAgeMaxNs = 0;
        }

        private void updateTargetSize(Target target) {
            if (target.recording != null) {
                MotionCompositionLayout.Rect cell = MotionCompositionLayout.cameraRectForCell(
                        0, target.recording.width, target.recording.height);
                target.width = cell.width(); target.height = cell.height();
                return;
            }
            if (target.mapping != null) {
                // Managed buffers keep their quality size across view resize; a new input rebinds it.
                target.width = target.mapping.width;
                target.height = target.mapping.height;
                return;
            }
            require(EGL14.eglQuerySurface(display, target.eglSurface, EGL14.EGL_WIDTH, queriedSize, 0),
                    "downstream width query failed");
            target.width = queriedSize[0];
            require(EGL14.eglQuerySurface(display, target.eglSurface, EGL14.EGL_HEIGHT, queriedSize, 0),
                    "downstream height query failed");
            target.height = queriedSize[0];
        }

        // Only the acquisition thread touches this window; downstream swap stalls cannot lower it.
        private void recordAcquisition(long started, long timestamp, long update, long copy, int signals) {
            FpsMeasurementWindow measurement = fpsWindow;
            long generation = measurement.id;
            boolean newBuffer = timestamp > lastAcquiredProducerTimestamp && timestamp > 0;
            if (measurement.start(started, newBuffer ? timestamp : 0)) {
                postCoordinator(() -> {
                    listener.onFirstFrame(index, generation, started);
                    coordinatorHandler.postDelayed(() -> {
                        if (closed || fpsWindow != measurement) return;
                        for (SourceWorker worker : workers) {
                            if (worker != null) worker.finishFpsWindow(measurement);
                        }
                    }, TimeUnit.NANOSECONDS.toMillis(FPS_STATS_WINDOW_NS));
                });
            }
            if (acquisitionWindowGeneration != generation
                    || acquisitionWindowFirstFrameNs != measurement.startedNs) {
                acquisitionStats.restart();
                acquisitionStats.previousProducerTimestamp = lastAcquiredProducerTimestamp;
                acquisitionWindowGeneration = generation;
                acquisitionWindowFirstFrameNs = measurement.startedNs;
                acquisitionWindowFinished = false;
            }
            if (newBuffer) lastAcquiredProducerTimestamp = timestamp;
            boolean window = isFpsStatsWindowActive(started, generation, fpsWindow.id,
                    measurement.startedNs, measurement.untilNs);
            if (!acquisitionWindowFinished && measurement.untilNs > 0
                    && started >= measurement.untilNs) {
                acquisitionWindowFinished = true;
                Stats tail = acquisitionStats.finish(measurement.untilNs, 0, 0,
                        null, null, 0, 1920, index == 0 ? 990 : 1300, "camera-acquire-" + index);
                publishStats(tail, true, generation);
            }
            if (!DiagnosticLogPolicy.extended() && !window) {
                acquisitionStats.restart();
                return;
            }
            Stats report = acquisitionStats.record(started, timestamp, update,
                    0, 0, 0, 0, 0, 0, update + copy, signals, Math.max(0, signals - 1),
                    0, 0, 0, 0, 0, null, null, 0, 1920, index == 0 ? 990 : 1300,
                    "camera-acquire-" + index);
            publishStats(report, window, generation);
        }

        private void finishFpsWindow(FpsMeasurementWindow measurement) {
            // Flush on the owning threads even if the OEM stopped delivering frames.
            pump.finishDiagnostics(() -> {
                if (released || fpsWindow != measurement || acquisitionWindowFinished) return;
                acquisitionWindowFinished = true;
                publishStats(acquisitionStats.finish(measurement.untilNs, 0, 0,
                        null, null, 0, 1920, index == 0 ? 990 : 1300,
                        "camera-acquire-" + index), true, measurement.id);
            });
            handler.post(() -> {
                if (released || fpsWindow != measurement || statsWindowFinished) return;
                statsWindowFinished = true;
                publishOutputStats(stats.finish(measurement.untilNs, targets.size(), 0,
                        targetWidths, targetHeights, 0, 1920, index == 0 ? 990 : 1300,
                        workerThreadName(index)), true, measurement.id);
            });
        }

        private void signalFrame() {
            if (released || closed) return;
            long nowNs = SystemClock.elapsedRealtimeNanos();
            boolean post;
            synchronized (frameState) {
                pendingFrameSignals++;
                if (firstPendingSignalNs < 0L) firstPendingSignalNs = nowNs;
                post = !renderQueued;
                renderQueued = true;
            }
            if (post) handler.post(renderTask);
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
            if (closed || released || sourceFailureReported.get() || pump == null) return;
            int slot = pump.mailbox.acquire();
            if (slot < 0) return;
            boolean transferred = false;
            try {
                renderSnapshot(slot, callbackStartedNs, frameSignals, queueDelayNs);
                if (!sourceFailureReported.get()) for (Target target : targets) {
                    if (target.recording == null || !target.active || target.retiring) continue;
                    makeCurrent(idleSurface);
                    pump.transferRead(slot);
                    transferred = target.recording.offer(index, pump, slot);
                    if (!transferred) pump.awaitRead(slot);
                    break;
                }
            } catch (Throwable error) {
                reportSourceFailure(error);
            } finally {
                try { if (!transferred) pump.releaseRead(slot); }
                catch (Throwable error) { reportSourceFailure(error); }
            }
        }

        private void renderSnapshot(int slot, long callbackStartedNs, int frameSignals, long queueDelayNs) {
            FpsMeasurementWindow measurement = fpsWindow;
            long windowGeneration = measurement.id;
            if (windowGeneration != statsWindowGeneration
                    || statsWindowFirstFrameNs != measurement.startedNs) {
                resetOutputStats();
                statsWindowGeneration = windowGeneration;
                statsWindowFirstFrameNs = measurement.startedNs;
                statsWindowFinished = false;
            }
            CameraFramePump.Frame frame = pump.frames[slot];
            long updateNs = 0;
            long producerTimestampNs = frame.producerNs;
            long acquiredFrameTimestampNanos = frame.acquiredNs;
            try {
                makeCurrent(idleSurface);
                pump.awaitRead(slot);
                textureName = frame.texture;
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
                if (target.recording != null) continue;
                if (!shouldRenderTarget(index, target.index, target.active)) continue;
                try {
                    DrawTiming timing = draw(target, acquiredFrameTimestampNanos);
                    if (timing == null) continue;
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
                    outputAgeMaxNs = Math.max(outputAgeMaxNs,
                            Math.max(0, System.nanoTime() - acquiredFrameTimestampNanos));
                } catch (Throwable error) {
                    try { destroyTarget(target); }
                    catch (Throwable cleanup) { error.addSuppressed(cleanup); }
                    targets.remove(i);
                    resetOutputStats();
                    forgetTargetFromWorker(target);
                    Surface failedSurface = target.surface;
                    int failedIndex = target.index;
                    int failedRequestId = target.requestId;
                    int failedProducerEpoch = target.producerEpoch;
                    postCoordinator(() -> listener.onConsumerFailure(
                            failedSurface, failedIndex, failedRequestId,
                            failedProducerEpoch, error));
                }
            }
            try {
                makeCurrent(idleSurface);
                refreshCaptureSize();
            } catch (Throwable error) {
                reportSourceFailure(error);
                return;
            }
            boolean fpsWindowActive = isFpsStatsWindowActive(
                    callbackStartedNs, windowGeneration, fpsWindow.id,
                    measurement.startedNs, measurement.untilNs);
            if (!statsWindowFinished && measurement.untilNs > 0
                    && callbackStartedNs >= measurement.untilNs) {
                statsWindowFinished = true;
                // Flush only already collected frames; the current frame is outside the window.
                Stats tail = stats.finish(measurement.untilNs, targetCount,
                        targetPixelsCurrent, targetWidths, targetHeights, targetDimensionCount,
                        1920, index == 0 ? 990 : 1300, workerThreadName(index));
                publishOutputStats(tail, true, windowGeneration);
            }
            if (targetCount == 0 || (!DiagnosticLogPolicy.extended() && !fpsWindowActive)) {
                resetOutputStats();
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
            publishOutputStats(report, fpsWindowActive, windowGeneration);
        }

        private void publishOutputStats(Stats report, boolean window, long generation) {
            if (report == null) return;
            report.outputStage = true;
            long dropped = pump.mailbox.dropped();
            report.droppedSnapshots = dropped - droppedAtReport;
            droppedAtReport = dropped;
            report.outputAgeMaxNs = outputAgeMaxNs;
            outputAgeMaxNs = 0;
            publishStats(report, window, generation);
        }

        private void publishStats(Stats report, boolean fpsWindowActive, long windowGeneration) {
            if (report != null) {
                FpsMeasurementWindow measurement = fpsWindow;
                report.fpsWindow = fpsWindowActive;
                report.fpsWindowId = fpsWindowActive ? windowGeneration : 0L;
                report.requestedFps = fpsWindowActive ? measurement.requestedFps : 0;
                report.fpsRequestedNs = fpsWindowActive ? measurement.requestedNs : 0;
                report.fpsFirstFrameNs = fpsWindowActive ? measurement.startedNs : 0;
                Stats completed = report;
                postCoordinator(() -> listener.onStats(index, completed));
            }
        }

        private void postCoordinator(Runnable action) {
            if (!coordinatorHandler.post(() -> {
                if (isCurrentWorker(this)) action.run();
            })) {
                Log.w(LOG_TAG, "camera source coordinator stopped before failure callback");
            }
        }

        private void reportSourceFailure(Throwable error) {
            if (released || !sourceFailureReported.compareAndSet(false, true)) return;
            postCoordinator(() -> listener.onSourceFailure(index, error));
        }

        private DrawTiming draw(Target target, long acquiredFrameTimestampNanos) throws Exception {
            long startedNs = SystemClock.elapsedRealtimeNanos();
            CameraGpuOutput.FrameConfiguration frame = target.mapping == null
                    ? null : target.mapping.frameConfiguration();
            CameraDewarpRenderer.MappingRequest rendered = target.mapping == null
                    || target.mapping.lifecycleOnly ? null
                    : target.mesh.prepare(frame.mapping, target.mapping.width, target.mapping.height);
            // Keep the last presented buffer until the exact new mapping is ready.
            if (target.mapping != null && !target.mapping.lifecycleOnly && rendered == null) return null;
            EGLSurface output = target.eglSurface;
            if (frame != null && frame.slot == 1) {
                if (target.alternateEglSurface == EGL14.EGL_NO_SURFACE) {
                    target.alternateEglSurface = EGL14.eglCreateWindowSurface(display, config,
                            frame.alternate, new int[]{EGL14.EGL_NONE}, 0);
                    require(target.alternateEglSurface != EGL14.EGL_NO_SURFACE,
                            "alternate downstream EGL surface failed");
                }
                output = target.alternateEglSurface;
            }
            makeCurrent(output);
            if (target.mapping == null) updateTargetSize(target);
            GLES20.glViewport(0, 0, target.width, target.height);
            GLES20.glUseProgram(program);
            positions.position(0);
            texCoords.position(0);
            GLES20.glEnableVertexAttribArray(positionLocation);
            GLES20.glEnableVertexAttribArray(texCoordLocation);
            boolean corrected = rendered != null && rendered.config.enabled && !target.mesh.rawFallback;
            GLES20.glUniform1i(checkBoundsLocation, corrected ? 1 : 0);
            boolean directGeometry = target.mapping != null && !target.mapping.lifecycleOnly;
            GLES20.glUniformMatrix4fv(outputMatrixLocation, 1, false,
                    directGeometry ? frame.geometry : CameraGpuOutput.IDENTITY, 0);
            // Rotation/Fit can leave uncovered pixels even when fisheye correction is off.
            if (corrected || directGeometry) {
                GLES20.glClearColor(0, 0, 0, 1);
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            }
            if (rendered == null) {
                GLES20.glVertexAttribPointer(positionLocation, 2, GLES20.GL_FLOAT, false, 0, positions);
                GLES20.glVertexAttribPointer(texCoordLocation, 2, GLES20.GL_FLOAT, false, 0, texCoords);
            } else {
                target.mesh.vertices.position(0);
                GLES20.glVertexAttribPointer(positionLocation, 2, GLES20.GL_FLOAT, false, 16, target.mesh.vertices);
                target.mesh.vertices.position(2);
                GLES20.glVertexAttribPointer(texCoordLocation, 2, GLES20.GL_FLOAT, false, 16, target.mesh.vertices);
            }
            GLES20.glUniformMatrix4fv(matrixLocation, 1, false, matrix, 0);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureName);
            GLES20.glUniform1i(textureLocation, 0);
            if (rendered == null) GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            else {
                target.mesh.indices.position(0);
                GLES20.glDrawElements(GLES20.GL_TRIANGLES, target.mesh.indices.capacity(),
                        GLES20.GL_UNSIGNED_SHORT, target.mesh.indices);
            }
            require(GLES20.glGetError() == GLES20.GL_NO_ERROR, "GPU fanout draw failed");
            if (target.timestampFrames || rendered != null) {
                // Local monotonic time travels with the buffer, independent of the vendor clock.
                require(EGLExt.eglPresentationTimeANDROID(
                        display, output, acquiredFrameTimestampNanos),
                        "downstream frame timestamp failed");
            }
            long swapStartedNs = SystemClock.elapsedRealtimeNanos();
            if (!EGL14.eglSwapBuffers(display, output)) {
                int eglError = EGL14.eglGetError();
                reportSwapFailure(target, eglError);
                throw new IllegalStateException("downstream buffer swap failed: source="
                        + target.index + " output=" + System.identityHashCode(target.surface)
                        + " request=" + target.requestId + " epoch=" + target.producerEpoch
                        + " retiring=" + target.retiring + " egl=" + eglError);
            }
            long swapWaitNs = SystemClock.elapsedRealtimeNanos() - swapStartedNs;
            if (rendered != null && target.acknowledgedToken != rendered.token) {
                target.mapping.applied(rendered.token, target.mesh.rawFallback,
                        target.mesh.vertices.capacity() / 4, target.mesh.generationNs);
                target.acknowledgedToken = rendered.token;
            }
            if (target.mapping != null) target.mapping.frame(acquiredFrameTimestampNanos, frame);
            if (swapWaitNs >= TimeUnit.MILLISECONDS.toNanos(100)
                    && swapStartedNs - target.lastStallReportNs >= STALL_REPORT_INTERVAL_NS) {
                target.lastStallReportNs = swapStartedNs;
                try {
                    postCoordinator(() -> listener.onTargetStall(
                            target.surface, target.index, swapWaitNs));
                } catch (Throwable error) {
                    Log.w(LOG_TAG, "target stall listener failed", error);
                }
            }
            DrawTiming timing = target.timing;
            timing.preSwapNs = swapStartedNs - startedNs;
            timing.swapWaitNs = swapWaitNs;
            timing.width = target.width;
            timing.height = target.height;
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
                    destroyTarget(target);
                } catch (Throwable error) {
                    if (first == null) first = error;
                }
            }
            targets.clear();
            try { if (pump != null) pump.close(); }
            catch (Throwable error) { if (first == null) first = error; }
            pump = null;
            surface = null;
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

    static FloatBuffer buffer(float[] values) {
        FloatBuffer result = ByteBuffer.allocateDirect(values.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        result.put(values).position(0);
        return result;
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
        boolean fpsWindow;
        long fpsWindowId;
        int requestedFps;
        long fpsRequestedNs;
        long fpsFirstFrameNs;
        boolean outputStage;
        long droppedSnapshots;
        long outputAgeMaxNs;

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

    static final class FpsMeasurementWindow {
        final long id, requestedNs;
        final int requestedFps;
        volatile long startedNs, untilNs;

        FpsMeasurementWindow(long id, int requestedFps, long requestedNs) {
            this.id = id;
            this.requestedFps = requestedFps;
            this.requestedNs = requestedNs;
        }

        boolean start(long elapsedNs, long producerTimestampNs) {
            if (id <= 0 || startedNs != 0 || producerTimestampNs <= 0 || elapsedNs < requestedNs)
                return false;
            synchronized (this) {
                if (startedNs != 0) return false;
                untilNs = elapsedNs + FPS_STATS_WINDOW_NS;
                startedNs = elapsedNs;
                return true;
            }
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

        void restart() {
            reset(-1L);
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
            return finish(callbackNs, targetsCurrent, targetPixelsCurrent,
                    frameTargetWidths, frameTargetHeights, frameTargetCount,
                    sourceWidth, sourceHeight, workerName);
        }

        Stats finish(long endNs, int targetsCurrent, long targetPixelsCurrent,
                int[] frameTargetWidths, int[] frameTargetHeights, int frameTargetCount,
                int sourceWidth, int sourceHeight, String workerName) {
            if (callbacks == 0 || startedNs < 0L || endNs <= startedNs) return null;
            targetDimensions = formatDimensions(
                    frameTargetWidths, frameTargetHeights, frameTargetCount);
            Stats result = new Stats(
                    endNs - startedNs,
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
            long lastProducerTimestamp = previousProducerTimestamp;
            long lastCallbackNs = previousCallbackNs;
            reset(endNs);
            previousProducerTimestamp = lastProducerTimestamp;
            previousCallbackNs = lastCallbackNs;
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
            previousProducerTimestamp = -1L;
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
        EGLSurface alternateEglSurface = EGL14.EGL_NO_SURFACE;
        final int requestId;
        final int producerEpoch;
        long lastStallReportNs;
        int width;
        int height;
        boolean active = true;
        boolean timestampFrames;
        boolean retiring;
        CameraGpuOutput.Binding mapping;
        MotionRecordingOutput recording;
        final MappingMesh mesh;
        final DrawTiming timing = new DrawTiming();
        long acknowledgedToken = -1;

        Target(SourceWorker worker, Surface surface, int index, EGLSurface eglSurface,
                int requestId, int producerEpoch) {
            this.worker = worker;
            this.mesh = new MappingMesh(meshCache, worker::signalFrame);
            this.surface = surface;
            this.index = index;
            this.eglSurface = eglSurface;
            this.requestId = requestId;
            this.producerEpoch = producerEpoch;
        }
    }

    static final class MappingMesh {
        private final CameraMeshCache cache;
        private final Runnable ready;
        private CameraDewarpConfig pendingConfig;
        private int pendingWidth, pendingHeight, appliedWidth, appliedHeight;
        private CompletableFuture<CameraMeshCache.Prepared> pending;
        CameraDewarpRenderer.MappingRequest applied;
        FloatBuffer vertices;
        ShortBuffer indices;
        boolean rawFallback;
        long generationNs;

        MappingMesh(CameraMeshCache cache, Runnable ready) {
            this.cache = cache;
            this.ready = ready;
        }

        CameraDewarpRenderer.MappingRequest prepare(
                CameraDewarpRenderer.MappingRequest next, int width, int height) {
            if (next == applied && width == appliedWidth && height == appliedHeight) return applied;
            if (applied != null && applied.config.sameMapping(next.config)
                    && width == appliedWidth && height == appliedHeight) {
                generationNs = 0;
                applied = next;
                return applied;
            }
            if (pending == null || !pendingConfig.sameMapping(next.config)
                    || width != pendingWidth || height != pendingHeight) {
                pendingConfig = next.config;
                pendingWidth = width;
                pendingHeight = height;
                pending = cache.request(next.config, width, height);
                pending.whenComplete((value, error) -> ready.run());
            }
            if (!pending.isDone()) return null;
            CameraMeshCache.Prepared mesh = pending.join();
            vertices = mesh.vertices();
            indices = mesh.indices();
            rawFallback = mesh.rawFallback();
            generationNs = mesh.generationNs();
            appliedWidth = width;
            appliedHeight = height;
            applied = next;
            return applied;
        }

    }
}
