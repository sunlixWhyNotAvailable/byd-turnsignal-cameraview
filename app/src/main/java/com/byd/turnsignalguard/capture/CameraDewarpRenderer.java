package com.byd.turnsignalguard.capture;

import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Log;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

final class CameraDewarpRenderer {
    private static final String TAG = "CameraDewarpRenderer";
    private static final String MATRIX_LOG_TAG = "BydCameraProbe";
    private static final int START_TIMEOUT_MS = 1500;
    private static final long METRICS_INTERVAL_NS = TimeUnit.SECONDS.toNanos(5);
    private static final long MAX_VALID_FRAME_AGE_NS = TimeUnit.SECONDS.toNanos(60);
    private static final AtomicInteger RENDERS_IN_FLIGHT = new AtomicInteger();
    private static final AtomicInteger MAX_RENDERS_IN_FLIGHT = new AtomicInteger();
    private static final AtomicInteger ACTIVE_RENDERERS = new AtomicInteger();
    private static final String VERTEX_SHADER =
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
    private static final String FRAGMENT_SHADER =
            "#extension GL_OES_EGL_image_external : require\n"
                    + "precision mediump float;\n"
                    + "uniform samplerExternalOES uTexture;\n"
                    + "varying vec2 vRawTexCoord;\n"
                    + "varying vec2 vTexCoord;\n"
                    + "void main(){\n"
                    + " if(vRawTexCoord.x<0.0||vRawTexCoord.x>1.0"
                    + "||vRawTexCoord.y<0.0||vRawTexCoord.y>1.0"
                    + "||vTexCoord.x<0.0||vTexCoord.x>1.0"
                    + "||vTexCoord.y<0.0||vTexCoord.y>1.0){"
                    + "gl_FragColor=vec4(0.0,0.0,0.0,1.0);return;}\n"
                    + " gl_FragColor=texture2D(uTexture,vTexCoord);\n"
                    + "}\n";

    private final HandlerThread thread = new HandlerThread("camera-dewarp");
    private final SurfaceTexture outputTexture;
    private final int width;
    private final int height;
    private final int inputGeneration;
    private final Consumer<Stats> statsSink;
    private final Consumer<Event> eventSink;
    private final Runnable metricsTick = this::emitStatsTick;
    private final AtomicBoolean mappingUpdatePosted = new AtomicBoolean();
    private final AtomicBoolean startupFailureReported = new AtomicBoolean();
    private final AtomicBoolean runtimeFailureReported = new AtomicBoolean();
    private final AtomicBoolean released = new AtomicBoolean();
    private final AtomicBoolean cleanupStarted = new AtomicBoolean();
    private volatile MappingRequest request;
    private Handler handler;
    private volatile Surface cameraSurface;
    private SurfaceTexture cameraTexture;
    private EGLDisplay eglDisplay = EGL14.EGL_NO_DISPLAY;
    private EGLContext eglContext = EGL14.EGL_NO_CONTEXT;
    private EGLSurface eglSurface = EGL14.EGL_NO_SURFACE;
    private EGLConfig eglConfig;
    private EGLSurface rawMirrorSurface = EGL14.EGL_NO_SURFACE;
    private EGLSurface correctedMirrorSurface = EGL14.EGL_NO_SURFACE;
    private int program;
    private int textureId;
    private int positionLocation;
    private int texCoordLocation;
    private int textureMatrixLocation;
    private int textureLocation;
    private FloatBuffer vertices;
    private ShortBuffer indices;
    private int indexCount;
    private FloatBuffer rawVertices;
    private ShortBuffer rawIndices;
    private int rawIndexCount;
    private MappingRequest appliedRequest;
    private MappingRequest pendingMeshRequest;
    private MappingRequest pendingRenderedRequest;
    private String pendingEventKind;
    private int pendingMeshVertexCount;
    private long pendingMeshGenerationNs = -1;
    private final float[] textureMatrix = new float[16];
    private boolean inputMatrixReported;
    private Throwable startupError;
    private long metricsStartedNs;
    private long lastCallbackNs;
    private long callbackCount;
    private long completedSwapCount;
    private long totalRenderNs;
    private long maxRenderNs;
    private long updateTotalNs;
    private long updateMaxNs;
    private long updateSamples;
    private long preSwapTotalNs;
    private long preSwapMaxNs;
    private long swapWaitTotalNs;
    private long swapWaitMaxNs;
    private long maxPrimarySwapNs;
    private long rawMirrorSwapCount;
    private long correctedMirrorSwapCount;
    private long mirrorPreSwapTotalNs;
    private long mirrorPreSwapMaxNs;
    private long mirrorSwapWaitTotalNs;
    private long mirrorSwapWaitMaxNs;
    private long maxCallbackGapNs;
    private long lastFrameAgeNs = -1;
    private long maxFrameAgeNs = -1;
    private boolean metricsActive;
    private boolean activeRendererCounted;
    private StatsContext statsContext;
    private StatsContext metricsWindowContext;
    private MappingRequest metricsWindowMapping;
    private final SwapTiming primaryTiming = new SwapTiming();
    private final SwapTiming mirrorTiming = new SwapTiming();

    static final class Event {
        final String kind;
        final CameraDewarpConfig mapping;
        final long requestToken;
        final int lens;
        final boolean enabled;
        final int fovDegrees;
        final int projection;
        final int vertexCount;
        final double generationMs;
        final String error;

        Event(
                String kind, MappingRequest request, int vertexCount,
                long generationNs, Throwable error) {
            this.kind = kind;
            CameraDewarpConfig config = request == null ? null : request.config;
            mapping = config;
            requestToken = request == null ? 0 : request.token;
            lens = config == null ? 0 : config.lens;
            enabled = config != null && config.enabled;
            fovDegrees = config == null ? 0 : config.fovDegrees;
            projection = config == null
                    ? CameraDewarpConfig.DEFAULT_PROJECTION : config.projection;
            this.vertexCount = vertexCount;
            generationMs = generationNs < 0 ? -1.0 : generationNs / 1_000_000.0;
            this.error = error == null ? null
                    : error.getClass().getSimpleName() + ": " + error.getMessage();
        }
    }

    static final class MappingRequest {
        final CameraDewarpConfig config;
        final long token;

        MappingRequest(CameraDewarpConfig config, long token) {
            if (config == null) throw new IllegalArgumentException("dewarp config is required");
            this.config = config;
            this.token = token;
        }
    }

    private static final class StatsContext {
        final int requestId;
        final int generation;
        final int viewWidth;
        final int viewHeight;

        StatsContext(int requestId, int generation, int viewWidth, int viewHeight) {
            this.requestId = Math.max(0, requestId);
            this.generation = Math.max(0, generation);
            this.viewWidth = Math.max(0, viewWidth);
            this.viewHeight = Math.max(0, viewHeight);
        }

        boolean same(int nextRequestId, int nextGeneration, int width, int height) {
            return requestId == Math.max(0, nextRequestId)
                    && generation == Math.max(0, nextGeneration)
                    && viewWidth == Math.max(0, width)
                    && viewHeight == Math.max(0, height);
        }
    }

    static boolean sameStatsWindow(
            MappingRequest startedMapping, MappingRequest currentMapping,
            int startedRequestId, int currentRequestId,
            int startedGeneration, int currentGeneration,
            int startedViewWidth, int currentViewWidth,
            int startedViewHeight, int currentViewHeight) {
        return startedMapping == currentMapping
                && startedRequestId == currentRequestId
                && startedGeneration == currentGeneration
                && startedViewWidth == currentViewWidth
                && startedViewHeight == currentViewHeight;
    }

    static final class Stats {
        final long intervalMs;
        final long callbacks;
        final long updateSamples;
        final long completedSwaps;
        final double callbackFps;
        final double completedSwapFps;
        final double averageRenderMs;
        final double maxRenderMs;
        final double maxSwapMs;
        final double averageUpdateTexImageMs;
        final double maxUpdateTexImageMs;
        final double averagePreSwapMs;
        final double maxPreSwapMs;
        final double averageSwapWaitMs;
        final double maxSwapWaitMs;
        final long rawMirrorSwaps;
        final long correctedMirrorSwaps;
        final double averageMirrorPreSwapMs;
        final double maxMirrorPreSwapMs;
        final double averageMirrorSwapWaitMs;
        final double maxMirrorSwapWaitMs;
        final double maxCallbackGapMs;
        final double lastFrameAgeMs;
        final double maxFrameAgeMs;
        final int processMaxConcurrentRenders;
        final int processActiveRenderers;
        final int rendererId;
        final int requestId;
        final int inputGeneration;
        final int contextGeneration;
        final long mappingRequestToken;
        final int bufferWidth;
        final int bufferHeight;
        final int viewWidth;
        final int viewHeight;
        final CameraDewarpConfig mapping;

        Stats(long intervalNs, long callbacks, long updateSamples, long completedSwaps,
                long totalRenderNs, long maxRenderNs,
                long updateTotalNs, long updateMaxNs,
                long preSwapTotalNs, long preSwapMaxNs,
                long swapWaitTotalNs, long swapWaitMaxNs,
                long maxPrimarySwapNs,
                long rawMirrorSwaps, long correctedMirrorSwaps,
                long mirrorPreSwapTotalNs, long mirrorPreSwapMaxNs,
                long mirrorSwapWaitTotalNs, long mirrorSwapWaitMaxNs,
                long maxCallbackGapNs, long lastFrameAgeNs, long maxFrameAgeNs,
                int processMaxConcurrentRenders, int processActiveRenderers, int rendererId,
                int requestId, int inputGeneration, int contextGeneration,
                int bufferWidth, int bufferHeight, int viewWidth, int viewHeight,
                MappingRequest mappingRequest) {
            intervalMs = TimeUnit.NANOSECONDS.toMillis(intervalNs);
            this.callbacks = callbacks;
            this.updateSamples = updateSamples;
            this.completedSwaps = completedSwaps;
            callbackFps = ratePerSecond(callbacks, intervalNs);
            completedSwapFps = ratePerSecond(completedSwaps, intervalNs);
            averageRenderMs = completedSwaps == 0 ? 0.0
                    : nanosToMillis(totalRenderNs) / completedSwaps;
            this.maxRenderMs = nanosToMillis(maxRenderNs);
            maxSwapMs = nanosToMillis(maxPrimarySwapNs);
            averageUpdateTexImageMs = averageMillis(updateTotalNs, updateSamples);
            maxUpdateTexImageMs = nanosToMillis(updateMaxNs);
            averagePreSwapMs = averageMillis(preSwapTotalNs, completedSwaps);
            maxPreSwapMs = nanosToMillis(preSwapMaxNs);
            averageSwapWaitMs = averageMillis(swapWaitTotalNs, completedSwaps);
            maxSwapWaitMs = nanosToMillis(swapWaitMaxNs);
            this.rawMirrorSwaps = rawMirrorSwaps;
            this.correctedMirrorSwaps = correctedMirrorSwaps;
            long mirrorSwaps = rawMirrorSwaps + correctedMirrorSwaps;
            averageMirrorPreSwapMs = averageMillis(mirrorPreSwapTotalNs, mirrorSwaps);
            maxMirrorPreSwapMs = nanosToMillis(mirrorPreSwapMaxNs);
            averageMirrorSwapWaitMs = averageMillis(mirrorSwapWaitTotalNs, mirrorSwaps);
            maxMirrorSwapWaitMs = nanosToMillis(mirrorSwapWaitMaxNs);
            this.maxCallbackGapMs = nanosToMillis(maxCallbackGapNs);
            this.lastFrameAgeMs = optionalNanosToMillis(lastFrameAgeNs);
            this.maxFrameAgeMs = optionalNanosToMillis(maxFrameAgeNs);
            this.processMaxConcurrentRenders = processMaxConcurrentRenders;
            this.processActiveRenderers = processActiveRenderers;
            this.rendererId = rendererId;
            this.requestId = requestId;
            this.inputGeneration = inputGeneration;
            this.contextGeneration = contextGeneration;
            mappingRequestToken = mappingRequest == null ? 0L : mappingRequest.token;
            this.bufferWidth = bufferWidth;
            this.bufferHeight = bufferHeight;
            this.viewWidth = viewWidth;
            this.viewHeight = viewHeight;
            mapping = mappingRequest == null ? null : mappingRequest.config;
        }

        private static double averageMillis(long totalNs, long count) {
            return count <= 0L ? 0.0d : nanosToMillis(totalNs) / count;
        }

        private static double ratePerSecond(long count, long intervalNs) {
            return intervalNs <= 0L ? 0.0d
                    : count * 1_000_000_000.0d / intervalNs;
        }

        private static double nanosToMillis(long value) {
            return value / 1_000_000.0;
        }

        private static double optionalNanosToMillis(long value) {
            return value < 0 ? -1.0 : nanosToMillis(value);
        }
    }

    private CameraDewarpRenderer(
            SurfaceTexture outputTexture, int width, int height,
            CameraDewarpConfig config, long requestToken, int inputGeneration,
            int statsRequestId, int statsGeneration, int viewWidth, int viewHeight,
            Consumer<Stats> statsSink, Consumer<Event> eventSink) {
        this.outputTexture = outputTexture;
        this.width = width;
        this.height = height;
        this.inputGeneration = inputGeneration;
        statsContext = new StatsContext(
                statsRequestId, statsGeneration, viewWidth, viewHeight);
        request = new MappingRequest(config, requestToken);
        this.statsSink = statsSink;
        this.eventSink = eventSink;
    }

    static CameraDewarpRenderer start(
            SurfaceTexture outputTexture, int width, int height,
            CameraDewarpConfig config, long requestToken, int inputGeneration,
            int statsRequestId, int statsGeneration, int viewWidth, int viewHeight,
            Consumer<Stats> statsSink, Consumer<Event> eventSink) {
        CameraDewarpRenderer renderer = new CameraDewarpRenderer(
                outputTexture, width, height, config, requestToken, inputGeneration,
                statsRequestId, statsGeneration, viewWidth, viewHeight, statsSink, eventSink);
        return renderer.startInternal() ? renderer : null;
    }

    Surface cameraSurface() {
        return cameraSurface;
    }

    void setRawMirror(SurfaceTexture texture) {
        setMirror(texture, true);
    }

    void setCorrectedMirror(SurfaceTexture texture) {
        setMirror(texture, false);
    }

    private void setMirror(SurfaceTexture texture, boolean raw) {
        Handler activeHandler = handler;
        if (released.get() || activeHandler == null) return;
        activeHandler.post(() -> {
            try {
                replaceMirror(texture, raw);
            } catch (Throwable error) {
                emitEvent(new Event("dewarp_mirror_failed",
                        appliedRequest == null ? request : appliedRequest,
                        0, -1, error));
            }
        });
    }

    void update(CameraDewarpConfig value, long requestToken) {
        if (value == null) throw new IllegalArgumentException("dewarp config is required");
        if (released.get()) return;
        MappingRequest current = request;
        if (requestToken <= current.token) return;
        request = new MappingRequest(value, requestToken);
        Handler activeHandler = handler;
        if (activeHandler != null && mappingUpdatePosted.compareAndSet(false, true)) {
            activeHandler.post(this::applyLatestMapping);
        }
    }

    void updateStatsContext(int requestId, int generation, int width, int height) {
        Handler activeHandler = handler;
        if (released.get() || activeHandler == null) return;
        activeHandler.post(() -> {
            if (!statsContext.same(requestId, generation, width, height)) {
                statsContext = new StatsContext(requestId, generation, width, height);
            }
        });
    }

    void release() {
        released.set(true);
        if (!cleanupStarted.compareAndSet(false, true)) return;
        Handler activeHandler = handler;
        if (activeHandler == null) return;
        CountDownLatch released = new CountDownLatch(1);
        activeHandler.post(() -> {
            try {
                releaseGl();
            } finally {
                released.countDown();
            }
        });
        try {
            released.await(START_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        thread.quitSafely();
        handler = null;
    }

    private boolean startInternal() {
        CountDownLatch ready = new CountDownLatch(1);
        thread.start();
        handler = new Handler(thread.getLooper());
        handler.post(() -> {
            try {
                initializeGl();
            } catch (Throwable error) {
                startupError = error;
                reportStartupFailure(error);
            } finally {
                ready.countDown();
            }
        });
        try {
            if (!ready.await(START_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                released.set(true);
                startupError = new IllegalStateException("dewarp renderer startup timeout");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            released.set(true);
            startupError = interrupted;
        }
        if (startupError == null && cameraSurface != null && cameraSurface.isValid()) return true;
        reportStartupFailure(startupError == null
                ? new IllegalStateException("dewarp renderer unavailable") : startupError);
        release();
        return false;
    }

    private void initializeGl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        require(eglDisplay != EGL14.EGL_NO_DISPLAY, "EGL display unavailable");
        require(EGL14.eglInitialize(eglDisplay, null, 0, null, 0), "EGL init failed");
        int[] attributes = {
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_NONE
        };
        EGLConfig[] configs = new EGLConfig[1];
        int[] count = new int[1];
        require(EGL14.eglChooseConfig(
                eglDisplay, attributes, 0, configs, 0, 1, count, 0) && count[0] > 0,
                "EGL config unavailable");
        eglConfig = configs[0];
        eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT,
                new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
        require(eglContext != EGL14.EGL_NO_CONTEXT, "EGL context failed");
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, outputTexture,
                new int[]{EGL14.EGL_NONE}, 0);
        require(eglSurface != EGL14.EGL_NO_SURFACE, "EGL window surface failed");
        require(EGL14.eglMakeCurrent(
                eglDisplay, eglSurface, eglSurface, eglContext), "EGL makeCurrent failed");

        program = linkProgram(VERTEX_SHADER, FRAGMENT_SHADER);
        positionLocation = GLES20.glGetAttribLocation(program, "aPosition");
        texCoordLocation = GLES20.glGetAttribLocation(program, "aTexCoord");
        textureMatrixLocation = GLES20.glGetUniformLocation(program, "uTextureMatrix");
        textureLocation = GLES20.glGetUniformLocation(program, "uTexture");
        int[] textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);
        textureId = textures[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        CameraFisheyeMapping.Mesh rawMesh = CameraFisheyeMapping.buildMesh(
                CameraDewarpConfig.disabled(CameraDewarpConfig.LENS_LEFT), width, height);
        rawVertices = floatBuffer(rawMesh.vertices);
        rawIndices = shortBuffer(rawMesh.indices);
        rawIndexCount = rawMesh.indices.length;
        createCameraInput();
        MappingRequest initial = request;
        try {
            installMapping(initial, initial.config, "dewarp_mesh_applied");
        } catch (CameraFisheyeMapping.CalibratedDomainException unsupported) {
            installRawFallback(initial);
        }
        metricsStartedNs = SystemClock.elapsedRealtimeNanos();
        metricsWindowMapping = appliedRequest;
        metricsWindowContext = statsContext;
        metricsActive = true;
        ACTIVE_RENDERERS.incrementAndGet();
        activeRendererCounted = true;
        handler.postDelayed(metricsTick, TimeUnit.NANOSECONDS.toMillis(METRICS_INTERVAL_NS));
    }

    private void createCameraInput() {
        SurfaceTexture input = new SurfaceTexture(textureId);
        input.setDefaultBufferSize(width, height);
        input.setOnFrameAvailableListener(texture -> {
            if (texture != cameraTexture) return;
            long callbackNs = SystemClock.elapsedRealtimeNanos();
            recordCallback(callbackNs);
            try {
                renderFrame();
            } catch (Throwable error) {
                Log.e(TAG, "Dewarp frame failed", error);
                texture.setOnFrameAvailableListener(null);
                metricsActive = false;
                handler.removeCallbacks(metricsTick);
                if (activeRendererCounted) {
                    ACTIVE_RENDERERS.decrementAndGet();
                    activeRendererCounted = false;
                }
                if (runtimeFailureReported.compareAndSet(false, true)) {
                    emitEvent(new Event("dewarp_frame_failed",
                            appliedRequest == null ? request : appliedRequest,
                            vertices == null ? 0 : vertices.capacity() / 4, -1, error));
                }
            }
        }, handler);
        cameraTexture = input;
        cameraSurface = new Surface(input);
    }

    private void releaseCameraInput() {
        SurfaceTexture input = cameraTexture;
        cameraTexture = null;
        if (input != null) input.setOnFrameAvailableListener(null);
        Surface surface = cameraSurface;
        cameraSurface = null;
        if (surface != null) surface.release();
        if (input != null) input.release();
    }

    private void replaceMirror(SurfaceTexture texture, boolean raw) {
        require(EGL14.eglMakeCurrent(
                eglDisplay, eglSurface, eglSurface, eglContext),
                "EGL primary makeCurrent failed");
        EGLSurface previous = raw ? rawMirrorSurface : correctedMirrorSurface;
        if (previous != EGL14.EGL_NO_SURFACE) {
            EGL14.eglDestroySurface(eglDisplay, previous);
        }
        EGLSurface next = EGL14.EGL_NO_SURFACE;
        if (texture != null) {
            texture.setDefaultBufferSize(width, height);
            next = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, texture,
                    new int[]{EGL14.EGL_NONE}, 0);
            if (next == EGL14.EGL_NO_SURFACE) {
                emitEvent(new Event("dewarp_mirror_failed",
                        appliedRequest == null ? request : appliedRequest,
                        0, -1, new IllegalStateException(
                                (raw ? "raw" : "corrected") + " mirror attach failed")));
            }
        }
        if (raw) rawMirrorSurface = next;
        else correctedMirrorSurface = next;
    }

    private void renderFrame() {
        if (cameraTexture == null || eglSurface == EGL14.EGL_NO_SURFACE) return;
        int concurrent = RENDERS_IN_FLIGHT.incrementAndGet();
        MAX_RENDERS_IN_FLIGHT.accumulateAndGet(concurrent, Math::max);
        long renderStartedNs = SystemClock.elapsedRealtimeNanos();
        boolean swapped = false;
        MappingRequest renderedRequest = appliedRequest;
        try {
            long updateStartedNs = SystemClock.elapsedRealtimeNanos();
            cameraTexture.updateTexImage();
            long updateNs = SystemClock.elapsedRealtimeNanos() - updateStartedNs;
            updateSamples++;
            updateTotalNs += updateNs;
            updateMaxNs = Math.max(updateMaxNs, updateNs);
            cameraTexture.getTransformMatrix(textureMatrix);
            if (!inputMatrixReported) {
                inputMatrixReported = true;
                Log.i(MATRIX_LOG_TAG, "{\"kind\":\"camera_texture_matrix\","
                        + "\"source\":\"camera_dewarp_renderer\","
                        + "\"stage\":\"dewarp_input\","
                        + "\"renderer_id\":" + System.identityHashCode(this) + ","
                        + "\"width\":" + width + ","
                        + "\"height\":" + height + ","
                        + "\"matrix\":" + Arrays.toString(textureMatrix) + "}");
            }
            recordFrameAge(SystemClock.elapsedRealtimeNanos(), cameraTexture.getTimestamp());
            drawAndSwap(eglSurface, vertices, indices, indexCount, primaryTiming);
            swapped = true;
            emitAppliedMeshAfterSwap(renderedRequest);
            renderMirror(true, mirrorTiming);
            renderMirror(false, mirrorTiming);
            require(EGL14.eglMakeCurrent(
                    eglDisplay, eglSurface, eglSurface, eglContext),
                    "EGL primary restore failed");
        } finally {
            long completedNs = SystemClock.elapsedRealtimeNanos();
            RENDERS_IN_FLIGHT.decrementAndGet();
            if (swapped) recordCompletedRender(completedNs - renderStartedNs, primaryTiming);
        }
    }

    private void drawAndSwap(
            EGLSurface surface, FloatBuffer drawVertices,
            ShortBuffer drawIndices, int drawIndexCount, SwapTiming timing) {
        long startedNs = SystemClock.elapsedRealtimeNanos();
        require(EGL14.eglMakeCurrent(
                eglDisplay, surface, surface, eglContext), "EGL makeCurrent failed");
        GLES20.glViewport(0, 0, width, height);
        GLES20.glClearColor(0, 0, 0, 1);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        GLES20.glUseProgram(program);
        drawVertices.position(0);
        GLES20.glVertexAttribPointer(
                positionLocation, 2, GLES20.GL_FLOAT, false, 16, drawVertices);
        drawVertices.position(2);
        GLES20.glVertexAttribPointer(
                texCoordLocation, 2, GLES20.GL_FLOAT, false, 16, drawVertices);
        GLES20.glEnableVertexAttribArray(positionLocation);
        GLES20.glEnableVertexAttribArray(texCoordLocation);
        GLES20.glUniformMatrix4fv(textureMatrixLocation, 1, false, textureMatrix, 0);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
        GLES20.glUniform1i(textureLocation, 0);
        drawIndices.position(0);
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, drawIndexCount,
                GLES20.GL_UNSIGNED_SHORT, drawIndices);
        long swapStartedNs = SystemClock.elapsedRealtimeNanos();
        require(EGL14.eglSwapBuffers(eglDisplay, surface), "EGL swap failed");
        timing.preSwapNs = swapStartedNs - startedNs;
        timing.swapWaitNs = SystemClock.elapsedRealtimeNanos() - swapStartedNs;
    }

    private void renderMirror(boolean raw, SwapTiming timing) {
        EGLSurface surface = raw ? rawMirrorSurface : correctedMirrorSurface;
        if (surface == EGL14.EGL_NO_SURFACE) return;
        try {
            drawAndSwap(surface,
                    raw ? rawVertices : vertices,
                    raw ? rawIndices : indices,
                    raw ? rawIndexCount : indexCount, timing);
            if (raw) rawMirrorSwapCount++;
            else correctedMirrorSwapCount++;
            mirrorPreSwapTotalNs += timing.preSwapNs;
            mirrorPreSwapMaxNs = Math.max(mirrorPreSwapMaxNs, timing.preSwapNs);
            mirrorSwapWaitTotalNs += timing.swapWaitNs;
            mirrorSwapWaitMaxNs = Math.max(mirrorSwapWaitMaxNs, timing.swapWaitNs);
        } catch (Throwable error) {
            EGL14.eglDestroySurface(eglDisplay, surface);
            if (raw) rawMirrorSurface = EGL14.EGL_NO_SURFACE;
            else correctedMirrorSurface = EGL14.EGL_NO_SURFACE;
            emitEvent(new Event("dewarp_mirror_failed",
                    appliedRequest == null ? request : appliedRequest,
                    0, -1, new IllegalStateException(
                            (raw ? "raw" : "corrected") + " mirror failed", error)));
        }
    }

    private void applyLatestMapping() {
        mappingUpdatePosted.set(false);
        if (released.get()) return;
        MappingRequest latest = request;
        if (canReuseAppliedMapping(appliedRequest, latest)) {
            armPendingAck("dewarp_mesh_applied", latest, appliedRequest,
                    vertices.capacity() / 4, 0);
        } else {
            try {
                installMapping(latest, latest.config, "dewarp_mesh_applied");
            } catch (CameraFisheyeMapping.CalibratedDomainException unsupported) {
                try {
                    installRawFallback(latest);
                } catch (Throwable fallbackError) {
                    Log.e(TAG, "Dewarp raw fallback mesh failed", fallbackError);
                    emitEvent(new Event(
                            "dewarp_pipeline_failed", latest,
                            vertices == null ? 0 : vertices.capacity() / 4,
                            -1, fallbackError));
                }
            } catch (Throwable error) {
                Log.e(TAG, "Dewarp mesh update failed", error);
                String kind = mappingFailureKind(
                        appliedRequest == null ? null : appliedRequest.config,
                        latest.config);
                emitEvent(new Event(
                        kind, latest,
                        vertices == null ? 0 : vertices.capacity() / 4, -1, error));
            }
        }
        if (!released.get() && request != latest
                && mappingUpdatePosted.compareAndSet(false, true)) {
            handler.post(this::applyLatestMapping);
        }
    }

    private void installRawFallback(MappingRequest value) {
        CameraDewarpConfig identity = CameraDewarpConfig.disabled(value.config.lens);
        if (appliedRequest != null && appliedRequest.config.sameMapping(identity)) {
            armPendingAck("dewarp_fallback_raw", value, appliedRequest,
                    vertices.capacity() / 4, 0);
            return;
        }
        installMapping(value, identity, "dewarp_fallback_raw");
    }

    private void installMapping(
            MappingRequest value, CameraDewarpConfig renderedConfig,
            String eventKind) {
        long startedNs = SystemClock.elapsedRealtimeNanos();
        CameraFisheyeMapping.Mesh mesh = CameraFisheyeMapping.buildMesh(
                renderedConfig, width, height);
        FloatBuffer nextVertices = ByteBuffer.allocateDirect(mesh.vertices.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        nextVertices.put(mesh.vertices).position(0);
        ShortBuffer nextIndices = ByteBuffer.allocateDirect(mesh.indices.length * 2)
                .order(ByteOrder.nativeOrder()).asShortBuffer();
        nextIndices.put(mesh.indices).position(0);
        vertices = nextVertices;
        indices = nextIndices;
        indexCount = mesh.indices.length;
        appliedRequest = renderedConfig == value.config
                ? value : new MappingRequest(renderedConfig, value.token);
        armPendingAck(eventKind, value, appliedRequest, mesh.vertexCount(),
                SystemClock.elapsedRealtimeNanos() - startedNs);
    }

    private static FloatBuffer floatBuffer(float[] values) {
        FloatBuffer buffer = ByteBuffer.allocateDirect(values.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        buffer.put(values).position(0);
        return buffer;
    }

    private static ShortBuffer shortBuffer(short[] values) {
        ShortBuffer buffer = ByteBuffer.allocateDirect(values.length * 2)
                .order(ByteOrder.nativeOrder()).asShortBuffer();
        buffer.put(values).position(0);
        return buffer;
    }

    private void armPendingAck(
            String eventKind, MappingRequest value, MappingRequest rendered,
            int vertexCount, long generationNs) {
        pendingEventKind = eventKind;
        pendingMeshRequest = value;
        pendingRenderedRequest = rendered;
        pendingMeshVertexCount = vertexCount;
        pendingMeshGenerationNs = generationNs;
    }

    private void emitAppliedMeshAfterSwap(MappingRequest renderedRequest) {
        MappingRequest pending = pendingMeshRequest;
        if (!shouldEmitAppliedMesh(
                true, pending, pendingRenderedRequest, renderedRequest, request)) return;
        String eventKind = pendingEventKind;
        int vertexCount = pendingMeshVertexCount;
        long generationNs = pendingMeshGenerationNs;
        pendingEventKind = null;
        pendingMeshRequest = null;
        pendingRenderedRequest = null;
        pendingMeshVertexCount = 0;
        pendingMeshGenerationNs = -1;
        emitEvent(new Event(
                eventKind, pending, vertexCount, generationNs, null));
    }

    static boolean shouldEmitAppliedMesh(
            boolean swapSucceeded,
            MappingRequest pending,
            MappingRequest expectedRendered,
            MappingRequest rendered,
            MappingRequest requested) {
        return swapSucceeded && pending != null && expectedRendered != null
                && pending == requested
                && expectedRendered == rendered;
    }

    static boolean canReuseAppliedMapping(
            MappingRequest applied, MappingRequest requested) {
        return applied != null && requested != null
                && applied.config.sameMapping(requested.config);
    }

    private void recordCallback(long nowNs) {
        if (metricsStartedNs == 0) metricsStartedNs = nowNs;
        callbackCount++;
        if (lastCallbackNs != 0) {
            maxCallbackGapNs = Math.max(maxCallbackGapNs, nowNs - lastCallbackNs);
        }
        lastCallbackNs = nowNs;
    }

    private void recordFrameAge(long nowNs, long frameTimestampNs) {
        if (frameTimestampNs <= 0 || nowNs < frameTimestampNs) {
            lastFrameAgeNs = -1;
            return;
        }
        long ageNs = nowNs - frameTimestampNs;
        if (ageNs > MAX_VALID_FRAME_AGE_NS) {
            lastFrameAgeNs = -1;
            return;
        }
        lastFrameAgeNs = ageNs;
        maxFrameAgeNs = Math.max(maxFrameAgeNs, ageNs);
    }

    private void recordCompletedRender(long renderNs, SwapTiming timing) {
        completedSwapCount++;
        totalRenderNs += renderNs;
        maxRenderNs = Math.max(maxRenderNs, renderNs);
        preSwapTotalNs += timing.preSwapNs;
        preSwapMaxNs = Math.max(preSwapMaxNs, timing.preSwapNs);
        swapWaitTotalNs += timing.swapWaitNs;
        swapWaitMaxNs = Math.max(swapWaitMaxNs, timing.swapWaitNs);
        maxPrimarySwapNs = Math.max(
                maxPrimarySwapNs, timing.preSwapNs + timing.swapWaitNs);
    }

    private void emitStatsTick() {
        if (!metricsActive) return;
        emitStats(SystemClock.elapsedRealtimeNanos());
        if (metricsActive) {
            handler.postDelayed(metricsTick,
                    TimeUnit.NANOSECONDS.toMillis(METRICS_INTERVAL_NS));
        }
    }

    private void emitStats(long nowNs) {
        if (statsSink == null || metricsStartedNs == 0) return;
        long intervalNs = nowNs - metricsStartedNs;
        MappingRequest completedWindowMapping = metricsWindowMapping;
        StatsContext completedWindowContext = metricsWindowContext;
        boolean stableWindow = sameStatsWindow(
                completedWindowMapping, appliedRequest,
                completedWindowContext.requestId, statsContext.requestId,
                completedWindowContext.generation, statsContext.generation,
                completedWindowContext.viewWidth, statsContext.viewWidth,
                completedWindowContext.viewHeight, statsContext.viewHeight);
        Stats stats = new Stats(intervalNs, callbackCount, updateSamples, completedSwapCount,
                totalRenderNs, maxRenderNs, updateTotalNs, updateMaxNs,
                preSwapTotalNs, preSwapMaxNs, swapWaitTotalNs, swapWaitMaxNs,
                maxPrimarySwapNs,
                rawMirrorSwapCount, correctedMirrorSwapCount,
                mirrorPreSwapTotalNs, mirrorPreSwapMaxNs,
                mirrorSwapWaitTotalNs, mirrorSwapWaitMaxNs,
                maxCallbackGapNs, lastFrameAgeNs, maxFrameAgeNs,
                MAX_RENDERS_IN_FLIGHT.get(), ACTIVE_RENDERERS.get(),
                System.identityHashCode(this), completedWindowContext.requestId,
                inputGeneration, completedWindowContext.generation,
                width, height, completedWindowContext.viewWidth,
                completedWindowContext.viewHeight, completedWindowMapping);
        metricsStartedNs = nowNs;
        metricsWindowMapping = appliedRequest;
        metricsWindowContext = statsContext;
        callbackCount = 0;
        completedSwapCount = 0;
        totalRenderNs = 0;
        maxRenderNs = 0;
        updateTotalNs = 0;
        updateMaxNs = 0;
        updateSamples = 0;
        preSwapTotalNs = 0;
        preSwapMaxNs = 0;
        swapWaitTotalNs = 0;
        swapWaitMaxNs = 0;
        maxPrimarySwapNs = 0;
        rawMirrorSwapCount = 0;
        correctedMirrorSwapCount = 0;
        mirrorPreSwapTotalNs = 0;
        mirrorPreSwapMaxNs = 0;
        mirrorSwapWaitTotalNs = 0;
        mirrorSwapWaitMaxNs = 0;
        maxCallbackGapNs = 0;
        maxFrameAgeNs = -1;
        try {
            if (stableWindow) statsSink.accept(stats);
        } catch (Throwable error) {
            Log.w(TAG, "Dewarp stats sink failed", error);
        }
    }

    private void releaseGl() {
        metricsActive = false;
        if (activeRendererCounted) {
            ACTIVE_RENDERERS.decrementAndGet();
            activeRendererCounted = false;
        }
        if (handler != null) handler.removeCallbacks(metricsTick);
        releaseCameraInput();
        if (textureId != 0) GLES20.glDeleteTextures(1, new int[]{textureId}, 0);
        textureId = 0;
        if (program != 0) GLES20.glDeleteProgram(program);
        program = 0;
        vertices = null;
        indices = null;
        indexCount = 0;
        appliedRequest = null;
        pendingEventKind = null;
        pendingMeshRequest = null;
        pendingRenderedRequest = null;
        pendingMeshVertexCount = 0;
        pendingMeshGenerationNs = -1;
        metricsWindowMapping = null;
        metricsWindowContext = null;
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            if (rawMirrorSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglDisplay, rawMirrorSurface);
            }
            if (correctedMirrorSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglDisplay, correctedMirrorSurface);
            }
            if (eglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglDisplay, eglSurface);
            }
            if (eglContext != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(eglDisplay, eglContext);
            }
            EGL14.eglTerminate(eglDisplay);
        }
        rawMirrorSurface = EGL14.EGL_NO_SURFACE;
        correctedMirrorSurface = EGL14.EGL_NO_SURFACE;
        eglSurface = EGL14.EGL_NO_SURFACE;
        eglContext = EGL14.EGL_NO_CONTEXT;
        eglDisplay = EGL14.EGL_NO_DISPLAY;
        eglConfig = null;
        rawVertices = null;
        rawIndices = null;
        rawIndexCount = 0;
    }

    private void emitEvent(Event event) {
        emitEvent(event, false);
    }

    private void emitEvent(Event event, boolean allowReleased) {
        if ((!allowReleased && released.get()) || eventSink == null) return;
        try {
            eventSink.accept(event);
        } catch (Throwable error) {
            Log.w(TAG, "Dewarp event sink failed", error);
        }
    }

    private void reportStartupFailure(Throwable error) {
        if (!startupFailureReported.compareAndSet(false, true)) return;
        emitEvent(new Event("dewarp_fallback_raw", request, 0, -1, error), true);
    }

    static String mappingFailureKind(
            CameraDewarpConfig applied, CameraDewarpConfig requested) {
        if (requested == null) throw new IllegalArgumentException("requested config required");
        if (applied == null || (!applied.enabled && requested.enabled)) {
            return "dewarp_fallback_raw";
        }
        return "dewarp_pipeline_failed";
    }

    static boolean isFatalEventKind(String kind) {
        return "dewarp_frame_failed".equals(kind)
                || "dewarp_pipeline_failed".equals(kind);
    }

    static long nextRequestToken(
            long currentToken, CameraDewarpConfig current,
            CameraDewarpConfig requested) {
        if (current == null || requested == null) {
            throw new IllegalArgumentException("dewarp configs are required");
        }
        return current.sameMapping(requested) ? currentToken : currentToken + 1;
    }

    static boolean shouldHandleEvent(String kind, long eventToken, long currentToken) {
        if ("dewarp_frame_failed".equals(kind)) return true;
        if ("dewarp_mesh_applied".equals(kind)
                || "dewarp_fallback_raw".equals(kind)
                || "dewarp_pipeline_failed".equals(kind)) {
            return eventToken == currentToken;
        }
        return true;
    }

    private static int linkProgram(String vertexSource, String fragmentSource) {
        int vertex = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource);
        int fragment = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource);
        int linked = GLES20.glCreateProgram();
        GLES20.glAttachShader(linked, vertex);
        GLES20.glAttachShader(linked, fragment);
        GLES20.glLinkProgram(linked);
        int[] status = new int[1];
        GLES20.glGetProgramiv(linked, GLES20.GL_LINK_STATUS, status, 0);
        GLES20.glDeleteShader(vertex);
        GLES20.glDeleteShader(fragment);
        if (status[0] == 0) {
            String error = GLES20.glGetProgramInfoLog(linked);
            GLES20.glDeleteProgram(linked);
            throw new IllegalStateException("GL program link failed: " + error);
        }
        return linked;
    }

    private static int compileShader(int type, String source) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        int[] status = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0);
        if (status[0] == 0) {
            String error = GLES20.glGetShaderInfoLog(shader);
            GLES20.glDeleteShader(shader);
            throw new IllegalStateException("GL shader compile failed: " + error);
        }
        return shader;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private static final class SwapTiming {
        long preSwapNs;
        long swapWaitNs;
    }
}
