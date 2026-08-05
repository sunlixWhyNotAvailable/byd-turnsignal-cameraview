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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

final class CameraDewarpRenderer {
    private static final String TAG = "CameraDewarpRenderer";
    private static final int START_TIMEOUT_MS = 1500;
    private static final long METRICS_INTERVAL_NS = TimeUnit.SECONDS.toNanos(5);
    private static final long MAX_VALID_FRAME_AGE_NS = TimeUnit.SECONDS.toNanos(60);
    private static final AtomicInteger RENDERS_IN_FLIGHT = new AtomicInteger();
    private static final AtomicInteger MAX_RENDERS_IN_FLIGHT = new AtomicInteger();
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
    private final Consumer<Stats> statsSink;
    private final Consumer<Event> eventSink;
    private final Runnable metricsTick = this::emitStatsTick;
    private final AtomicBoolean mappingUpdatePosted = new AtomicBoolean();
    private final AtomicBoolean startupFailureReported = new AtomicBoolean();
    private final AtomicBoolean runtimeFailureReported = new AtomicBoolean();
    private final AtomicBoolean released = new AtomicBoolean();
    private final AtomicBoolean cleanupStarted = new AtomicBoolean();
    private volatile CameraDewarpConfig config;
    private Handler handler;
    private Surface cameraSurface;
    private SurfaceTexture cameraTexture;
    private EGLDisplay eglDisplay = EGL14.EGL_NO_DISPLAY;
    private EGLContext eglContext = EGL14.EGL_NO_CONTEXT;
    private EGLSurface eglSurface = EGL14.EGL_NO_SURFACE;
    private int program;
    private int textureId;
    private int positionLocation;
    private int texCoordLocation;
    private int textureMatrixLocation;
    private int textureLocation;
    private FloatBuffer vertices;
    private ShortBuffer indices;
    private int indexCount;
    private CameraDewarpConfig appliedConfig;
    private final float[] textureMatrix = new float[16];
    private Throwable startupError;
    private long metricsStartedNs;
    private long lastCallbackNs;
    private long callbackCount;
    private long completedSwapCount;
    private long totalRenderNs;
    private long maxRenderNs;
    private long maxSwapNs;
    private long maxCallbackGapNs;
    private long lastFrameAgeNs = -1;
    private long maxFrameAgeNs = -1;
    private boolean metricsActive;

    static final class Event {
        final String kind;
        final int lens;
        final boolean enabled;
        final int fovDegrees;
        final int vertexCount;
        final double generationMs;
        final String error;

        Event(
                String kind, CameraDewarpConfig config, int vertexCount,
                long generationNs, Throwable error) {
            this.kind = kind;
            lens = config == null ? 0 : config.lens;
            enabled = config != null && config.enabled;
            fovDegrees = config == null ? 0 : config.fovDegrees;
            this.vertexCount = vertexCount;
            generationMs = generationNs < 0 ? -1.0 : generationNs / 1_000_000.0;
            this.error = error == null ? null
                    : error.getClass().getSimpleName() + ": " + error.getMessage();
        }
    }

    static final class Stats {
        final long intervalMs;
        final long callbacks;
        final long completedSwaps;
        final double averageRenderMs;
        final double maxRenderMs;
        final double maxSwapMs;
        final double maxCallbackGapMs;
        final double lastFrameAgeMs;
        final double maxFrameAgeMs;
        final int processMaxConcurrentRenders;
        final int bufferWidth;
        final int bufferHeight;

        Stats(long intervalNs, long callbacks, long completedSwaps,
                long totalRenderNs, long maxRenderNs, long maxSwapNs,
                long maxCallbackGapNs, long lastFrameAgeNs, long maxFrameAgeNs,
                int processMaxConcurrentRenders, int bufferWidth, int bufferHeight) {
            intervalMs = TimeUnit.NANOSECONDS.toMillis(intervalNs);
            this.callbacks = callbacks;
            this.completedSwaps = completedSwaps;
            averageRenderMs = completedSwaps == 0 ? 0.0
                    : nanosToMillis(totalRenderNs) / completedSwaps;
            this.maxRenderMs = nanosToMillis(maxRenderNs);
            this.maxSwapMs = nanosToMillis(maxSwapNs);
            this.maxCallbackGapMs = nanosToMillis(maxCallbackGapNs);
            this.lastFrameAgeMs = optionalNanosToMillis(lastFrameAgeNs);
            this.maxFrameAgeMs = optionalNanosToMillis(maxFrameAgeNs);
            this.processMaxConcurrentRenders = processMaxConcurrentRenders;
            this.bufferWidth = bufferWidth;
            this.bufferHeight = bufferHeight;
        }

        private static double nanosToMillis(long value) {
            return value / 1_000_000.0;
        }

        private static double optionalNanosToMillis(long value) {
            return value < 0 ? -1.0 : nanosToMillis(value);
        }
    }

    private CameraDewarpRenderer(
            SurfaceTexture outputTexture, int width, int height, CameraDewarpConfig config,
            Consumer<Stats> statsSink, Consumer<Event> eventSink) {
        this.outputTexture = outputTexture;
        this.width = width;
        this.height = height;
        this.config = config;
        this.statsSink = statsSink;
        this.eventSink = eventSink;
    }

    static CameraDewarpRenderer start(
            SurfaceTexture outputTexture, int width, int height, CameraDewarpConfig config,
            Consumer<Stats> statsSink, Consumer<Event> eventSink) {
        CameraDewarpRenderer renderer = new CameraDewarpRenderer(
                outputTexture, width, height, config, statsSink, eventSink);
        return renderer.startInternal() ? renderer : null;
    }

    Surface cameraSurface() {
        return cameraSurface;
    }

    void update(CameraDewarpConfig value) {
        if (value == null) throw new IllegalArgumentException("dewarp config is required");
        if (released.get()) return;
        config = value;
        Handler activeHandler = handler;
        if (activeHandler != null && mappingUpdatePosted.compareAndSet(false, true)) {
            activeHandler.post(this::applyLatestMapping);
        }
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
        eglContext = EGL14.eglCreateContext(eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT,
                new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
        require(eglContext != EGL14.EGL_NO_CONTEXT, "EGL context failed");
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, configs[0], outputTexture,
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
        cameraTexture = new SurfaceTexture(textureId);
        cameraTexture.setDefaultBufferSize(width, height);
        cameraTexture.setOnFrameAvailableListener(texture -> {
            long callbackNs = SystemClock.elapsedRealtimeNanos();
            recordCallback(callbackNs);
            try {
                renderFrame();
            } catch (Throwable error) {
                Log.e(TAG, "Dewarp frame failed", error);
                texture.setOnFrameAvailableListener(null);
                metricsActive = false;
                handler.removeCallbacks(metricsTick);
                if (runtimeFailureReported.compareAndSet(false, true)) {
                    emitEvent(new Event("dewarp_frame_failed",
                            appliedConfig == null ? config : appliedConfig,
                            vertices == null ? 0 : vertices.capacity() / 4, -1, error));
                }
            }
        }, handler);
        cameraSurface = new Surface(cameraTexture);
        installMapping(config);
        metricsStartedNs = SystemClock.elapsedRealtimeNanos();
        metricsActive = true;
        handler.postDelayed(metricsTick, TimeUnit.NANOSECONDS.toMillis(METRICS_INTERVAL_NS));
    }

    private void renderFrame() {
        if (cameraTexture == null || eglSurface == EGL14.EGL_NO_SURFACE) return;
        int concurrent = RENDERS_IN_FLIGHT.incrementAndGet();
        MAX_RENDERS_IN_FLIGHT.accumulateAndGet(concurrent, Math::max);
        long renderStartedNs = SystemClock.elapsedRealtimeNanos();
        boolean swapped = false;
        long swapNs = 0;
        try {
            cameraTexture.updateTexImage();
            cameraTexture.getTransformMatrix(textureMatrix);
            recordFrameAge(SystemClock.elapsedRealtimeNanos(), cameraTexture.getTimestamp());
            GLES20.glViewport(0, 0, width, height);
            GLES20.glClearColor(0, 0, 0, 1);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            GLES20.glUseProgram(program);
            vertices.position(0);
            GLES20.glVertexAttribPointer(
                    positionLocation, 2, GLES20.GL_FLOAT, false, 16, vertices);
            vertices.position(2);
            GLES20.glVertexAttribPointer(
                    texCoordLocation, 2, GLES20.GL_FLOAT, false, 16, vertices);
            GLES20.glEnableVertexAttribArray(positionLocation);
            GLES20.glEnableVertexAttribArray(texCoordLocation);
            GLES20.glUniformMatrix4fv(
                    textureMatrixLocation, 1, false, textureMatrix, 0);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
            GLES20.glUniform1i(textureLocation, 0);
            indices.position(0);
            GLES20.glDrawElements(
                    GLES20.GL_TRIANGLES, indexCount,
                    GLES20.GL_UNSIGNED_SHORT, indices);
            long swapStartedNs = SystemClock.elapsedRealtimeNanos();
            require(EGL14.eglSwapBuffers(eglDisplay, eglSurface), "EGL swap failed");
            swapNs = SystemClock.elapsedRealtimeNanos() - swapStartedNs;
            swapped = true;
        } finally {
            long completedNs = SystemClock.elapsedRealtimeNanos();
            RENDERS_IN_FLIGHT.decrementAndGet();
            if (swapped) recordCompletedRender(completedNs - renderStartedNs, swapNs);
        }
    }

    private void applyLatestMapping() {
        mappingUpdatePosted.set(false);
        if (released.get()) return;
        CameraDewarpConfig latest = config;
        if (appliedConfig != null && appliedConfig.sameMapping(latest)) return;
        try {
            installMapping(latest);
        } catch (Throwable error) {
            Log.e(TAG, "Dewarp mesh update failed", error);
            String kind = mappingFailureKind(appliedConfig, latest);
            emitEvent(new Event(
                    kind, latest,
                    vertices == null ? 0 : vertices.capacity() / 4, -1, error));
        }
        if (!released.get() && config != latest
                && mappingUpdatePosted.compareAndSet(false, true)) {
            handler.post(this::applyLatestMapping);
        }
    }

    private void installMapping(CameraDewarpConfig value) {
        long startedNs = SystemClock.elapsedRealtimeNanos();
        CameraFisheyeMapping.Mesh mesh = CameraFisheyeMapping.buildMesh(value, width, height);
        FloatBuffer nextVertices = ByteBuffer.allocateDirect(mesh.vertices.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        nextVertices.put(mesh.vertices).position(0);
        ShortBuffer nextIndices = ByteBuffer.allocateDirect(mesh.indices.length * 2)
                .order(ByteOrder.nativeOrder()).asShortBuffer();
        nextIndices.put(mesh.indices).position(0);
        vertices = nextVertices;
        indices = nextIndices;
        indexCount = mesh.indices.length;
        appliedConfig = value;
        long generationNs = SystemClock.elapsedRealtimeNanos() - startedNs;
        emitEvent(new Event(
                "dewarp_mesh_applied", value, mesh.vertexCount(), generationNs, null));
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

    private void recordCompletedRender(long renderNs, long swapNs) {
        completedSwapCount++;
        totalRenderNs += renderNs;
        maxRenderNs = Math.max(maxRenderNs, renderNs);
        maxSwapNs = Math.max(maxSwapNs, swapNs);
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
        Stats stats = new Stats(intervalNs, callbackCount, completedSwapCount,
                totalRenderNs, maxRenderNs, maxSwapNs, maxCallbackGapNs,
                lastFrameAgeNs, maxFrameAgeNs, MAX_RENDERS_IN_FLIGHT.get(), width, height);
        metricsStartedNs = nowNs;
        callbackCount = 0;
        completedSwapCount = 0;
        totalRenderNs = 0;
        maxRenderNs = 0;
        maxSwapNs = 0;
        maxCallbackGapNs = 0;
        maxFrameAgeNs = -1;
        try {
            statsSink.accept(stats);
        } catch (Throwable error) {
            Log.w(TAG, "Dewarp stats sink failed", error);
        }
    }

    private void releaseGl() {
        metricsActive = false;
        if (handler != null) handler.removeCallbacks(metricsTick);
        if (cameraTexture != null) cameraTexture.setOnFrameAvailableListener(null);
        if (cameraSurface != null) cameraSurface.release();
        cameraSurface = null;
        if (cameraTexture != null) cameraTexture.release();
        cameraTexture = null;
        if (textureId != 0) GLES20.glDeleteTextures(1, new int[]{textureId}, 0);
        textureId = 0;
        if (program != 0) GLES20.glDeleteProgram(program);
        program = 0;
        vertices = null;
        indices = null;
        indexCount = 0;
        appliedConfig = null;
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            if (eglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglDisplay, eglSurface);
            }
            if (eglContext != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(eglDisplay, eglContext);
            }
            EGL14.eglTerminate(eglDisplay);
        }
        eglSurface = EGL14.EGL_NO_SURFACE;
        eglContext = EGL14.EGL_NO_CONTEXT;
        eglDisplay = EGL14.EGL_NO_DISPLAY;
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
        emitEvent(new Event("dewarp_fallback_raw", config, 0, -1, error), true);
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
}
