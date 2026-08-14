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
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;

/** Stable full-frame GPU boundary between AVMCamera and logical preview consumers. */
final class DirectCameraSourceHub
        implements CameraHelperMain.HelperBinder.PersistentSurfaceFanout {
    interface Listener {
        void onConsumerFailure(Surface surface, int index, Throwable error);
        void onSourceFailure(int index, Throwable error);
        void onStats(int index, Stats stats);
    }

    private static final int CALL_TIMEOUT_MS = 1500;
    private static final long STATS_INTERVAL_NS = TimeUnit.SECONDS.toNanos(5);
    private static final long MAX_VALID_FRAME_AGE_NS = TimeUnit.SECONDS.toNanos(60);
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

    private final HandlerThread thread = new HandlerThread("direct-camera-source");
    private final Source[] sources = new Source[MAX_INDEX + 1];
    private final ArrayList<Target> targets = new ArrayList<>();
    private final Listener listener;
    private Handler handler;
    private EGLDisplay display = EGL14.EGL_NO_DISPLAY;
    private EGLContext context = EGL14.EGL_NO_CONTEXT;
    private EGLSurface idleSurface = EGL14.EGL_NO_SURFACE;
    private EGLConfig config;
    private int program;
    private int positionLocation;
    private int texCoordLocation;
    private int matrixLocation;
    private int textureLocation;
    private FloatBuffer positions;
    private FloatBuffer texCoords;
    private boolean sourceFailureReported;
    private boolean closed;

    private DirectCameraSourceHub(Listener listener) {
        this.listener = listener;
    }

    static DirectCameraSourceHub create(Listener listener) throws Exception {
        DirectCameraSourceHub result = new DirectCameraSourceHub(listener);
        result.thread.start();
        result.handler = new Handler(result.thread.getLooper());
        try {
            result.call(() -> {
                result.initializeGl();
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
        return call(() -> createSource(index).surface);
    }

    @Override
    public void attach(Surface[] surfaces, int[] indexes) throws Exception {
        call(() -> {
            int attached = 0;
            try {
                for (int i = 0; i < surfaces.length; i++) {
                    attachTarget(surfaces[i], indexes[i]);
                    attached++;
                }
            } catch (Throwable error) {
                for (int i = attached - 1; i >= 0; i--) detachTarget(surfaces[i]);
                throw error;
            }
            return null;
        });
    }

    @Override
    public void detach(Surface[] surfaces) throws Exception {
        call(() -> {
            for (Surface surface : surfaces) detachTarget(surface);
            return null;
        });
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        try {
            callFinal(() -> {
                releaseGl();
                return null;
            });
        } catch (Throwable error) {
            throw new IllegalStateException("camera source cleanup failed", error);
        } finally {
            thread.quitSafely();
        }
    }

    private void initializeGl() {
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
    }

    private Source createSource(int index) {
        Source existing = sources[index];
        if (existing != null) return existing;
        makeCurrent(idleSurface);
        int[] names = new int[1];
        GLES20.glGenTextures(1, names, 0);
        require(names[0] != 0, "camera source texture unavailable");
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, names[0]);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        SurfaceTexture texture = new SurfaceTexture(names[0]);
        texture.setDefaultBufferSize(1920, index == 0 ? 990 : 1300);
        Source source = new Source(index, names[0], texture, new Surface(texture));
        sources[index] = source;
        texture.setOnFrameAvailableListener(ignored -> render(source), handler);
        return source;
    }

    private void attachTarget(Surface surface, int index) {
        requireIndex(index);
        require(surface != null && surface.isValid(), "downstream Surface is invalid");
        EGLSurface eglSurface = EGL14.eglCreateWindowSurface(display, config, surface,
                new int[]{EGL14.EGL_NONE}, 0);
        require(eglSurface != EGL14.EGL_NO_SURFACE, "downstream EGL surface failed");
        targets.add(new Target(surface, index, eglSurface));
    }

    private void detachTarget(Surface surface) {
        for (int i = targets.size() - 1; i >= 0; i--) {
            Target target = targets.get(i);
            if (target.surface != surface) continue;
            EGL14.eglDestroySurface(display, target.eglSurface);
            targets.remove(i);
        }
    }

    private void render(Source source) {
        if (closed || sourceFailureReported) return;
        long callbackStartedNs = SystemClock.elapsedRealtimeNanos();
        long updatedNs;
        long updateNs;
        long producerTimestampNs;
        try {
            makeCurrent(idleSurface);
            long updateStartedNs = SystemClock.elapsedRealtimeNanos();
            source.texture.updateTexImage();
            updatedNs = SystemClock.elapsedRealtimeNanos();
            updateNs = updatedNs - updateStartedNs;
            source.texture.getTransformMatrix(source.matrix);
            producerTimestampNs = source.texture.getTimestamp();
            if (!source.matrixReported) {
                source.matrixReported = true;
                Log.i(LOG_TAG, "{\"kind\":\"camera_texture_matrix\","
                        + "\"source\":\"direct_camera_source_hub\","
                        + "\"stage\":\"avm_source\","
                        + "\"preview_index\":" + source.index + ","
                        + "\"surface_texture_id\":"
                        + System.identityHashCode(source.texture) + ","
                        + "\"matrix\":" + Arrays.toString(source.matrix) + "}");
            }
        } catch (Throwable error) {
            sourceFailureReported = true;
            listener.onSourceFailure(source.index, error);
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
            if (target.index != source.index) continue;
            try {
                draw(source, target, source.drawTiming);
                targetCount++;
                preSwapTotalNs += source.drawTiming.preSwapNs;
                preSwapMaxNs = Math.max(preSwapMaxNs, source.drawTiming.preSwapNs);
                swapWaitTotalNs += source.drawTiming.swapWaitNs;
                swapWaitMaxNs = Math.max(swapWaitMaxNs, source.drawTiming.swapWaitNs);
                drawMaxNs = Math.max(drawMaxNs,
                        source.drawTiming.preSwapNs + source.drawTiming.swapWaitNs);
                targetPixelsCurrent += (long) source.drawTiming.width
                        * source.drawTiming.height;
                targetWidthMax = Math.max(targetWidthMax, source.drawTiming.width);
                targetHeightMax = Math.max(targetHeightMax, source.drawTiming.height);
                if (targetDimensionCount < source.targetWidths.length) {
                    source.targetWidths[targetDimensionCount] = source.drawTiming.width;
                    source.targetHeights[targetDimensionCount] = source.drawTiming.height;
                    targetDimensionCount++;
                }
                swapCount++;
            } catch (Throwable error) {
                EGL14.eglDestroySurface(display, target.eglSurface);
                targets.remove(i);
                listener.onConsumerFailure(target.surface, target.index, error);
            }
        }
        makeCurrent(idleSurface);
        Stats stats = source.stats.record(
                callbackStartedNs,
                producerTimestampNs,
                updatedNs,
                updateNs,
                preSwapTotalNs,
                preSwapMaxNs,
                swapWaitTotalNs,
                swapWaitMaxNs,
                drawMaxNs,
                swapCount,
                SystemClock.elapsedRealtimeNanos() - callbackStartedNs,
                targetCount,
                targetPixelsCurrent,
                targetWidthMax,
                targetHeightMax,
                source.targetWidths,
                source.targetHeights,
                targetDimensionCount,
                1920,
                source.index == 0 ? 990 : 1300);
        if (stats != null) listener.onStats(source.index, stats);
    }

    private void draw(Source source, Target target, DrawTiming timing) {
        long startedNs = SystemClock.elapsedRealtimeNanos();
        makeCurrent(target.eglSurface);
        int[] width = new int[1];
        int[] height = new int[1];
        require(EGL14.eglQuerySurface(display, target.eglSurface, EGL14.EGL_WIDTH, width, 0)
                && EGL14.eglQuerySurface(display, target.eglSurface, EGL14.EGL_HEIGHT, height, 0),
                "downstream size query failed");
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
        GLES20.glUniformMatrix4fv(matrixLocation, 1, false, source.matrix, 0);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, source.textureName);
        GLES20.glUniform1i(textureLocation, 0);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        require(GLES20.glGetError() == GLES20.GL_NO_ERROR, "GPU fanout draw failed");
        long swapStartedNs = SystemClock.elapsedRealtimeNanos();
        require(EGL14.eglSwapBuffers(display, target.eglSurface),
                "downstream buffer swap failed");
        timing.preSwapNs = swapStartedNs - startedNs;
        timing.swapWaitNs = SystemClock.elapsedRealtimeNanos() - swapStartedNs;
        timing.width = width[0];
        timing.height = height[0];
    }

    private void releaseGl() {
        if (display == EGL14.EGL_NO_DISPLAY) return;
        makeCurrent(idleSurface);
        for (Target target : targets) {
            EGL14.eglDestroySurface(display, target.eglSurface);
        }
        targets.clear();
        for (int i = 0; i < sources.length; i++) {
            Source source = sources[i];
            if (source == null) continue;
            source.texture.setOnFrameAvailableListener(null);
            source.surface.release();
            source.texture.release();
            GLES20.glDeleteTextures(1, new int[]{source.textureName}, 0);
            sources[i] = null;
        }
        if (program != 0) GLES20.glDeleteProgram(program);
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
        if (idleSurface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglDestroySurface(display, idleSurface);
        }
        if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context);
        EGL14.eglTerminate(display);
        idleSurface = EGL14.EGL_NO_SURFACE;
        context = EGL14.EGL_NO_CONTEXT;
        display = EGL14.EGL_NO_DISPLAY;
    }

    private void makeCurrent(EGLSurface surface) {
        require(EGL14.eglMakeCurrent(display, surface, surface, context),
                "EGL makeCurrent failed");
    }

    private <T> T call(Callable<T> callable) throws Exception {
        if (Looper.myLooper() == thread.getLooper()) return callable.call();
        SerializedCall<T> call = new SerializedCall<>();
        if (!handler.post(() -> call.run(callable))) {
            throw new IllegalStateException("camera source thread stopped");
        }
        if (!call.await(CALL_TIMEOUT_MS) && call.cancelIfQueued()) {
            throw new TimeoutException("camera source operation queue timed out");
        }
        return call.result();
    }

    private <T> T callFinal(Callable<T> callable) throws Exception {
        if (Looper.myLooper() == thread.getLooper()) return callable.call();
        SerializedCall<T> call = new SerializedCall<>();
        if (!handler.post(() -> call.run(callable))) {
            throw new IllegalStateException("camera source thread stopped before cleanup");
        }
        return awaitFinalizer(call);
    }

    static <T> T awaitFinalizer(SerializedCall<T> call) throws Exception {
        return call.result();
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

    private static final class Source {
        final int index;
        final int textureName;
        final SurfaceTexture texture;
        final Surface surface;
        final float[] matrix = new float[16];
        final StatsWindow stats = new StatsWindow();
        final DrawTiming drawTiming = new DrawTiming();
        final int[] targetWidths = new int[8];
        final int[] targetHeights = new int[8];
        boolean matrixReported;

        Source(int index, int textureName, SurfaceTexture texture, Surface surface) {
            this.index = index;
            this.textureName = textureName;
            this.texture = texture;
            this.surface = surface;
        }
    }

    static final class Stats {
        final long intervalNs;
        final int callbacks;
        final int callbackGaps;
        final long callbackGapTotalNs;
        final long callbackGapMaxNs;
        final long updateTotalNs;
        final long updateMaxNs;
        final int producerTimestampDeltas;
        final long producerTimestampDeltaTotalNs;
        final long producerTimestampDeltaMinNs;
        final long producerTimestampDeltaMaxNs;
        final int producerTimestampRepeated;
        final int producerTimestampInvalid;
        final int frameAgeSamples;
        final int frameAgeNonPositive;
        final int frameAgeFuture;
        final int frameAgeStale;
        final long frameAgeTotalNs;
        final long frameAgeMaxNs;
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
                int callbackGaps,
                long callbackGapTotalNs,
                long callbackGapMaxNs,
                long updateTotalNs,
                long updateMaxNs,
                int producerTimestampDeltas,
                long producerTimestampDeltaTotalNs,
                long producerTimestampDeltaMinNs,
                long producerTimestampDeltaMaxNs,
                int producerTimestampRepeated,
                int producerTimestampInvalid,
                int frameAgeSamples,
                int frameAgeNonPositive,
                int frameAgeFuture,
                int frameAgeStale,
                long frameAgeTotalNs,
                long frameAgeMaxNs,
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
            this.callbackGaps = callbackGaps;
            this.callbackGapTotalNs = callbackGapTotalNs;
            this.callbackGapMaxNs = callbackGapMaxNs;
            this.updateTotalNs = updateTotalNs;
            this.updateMaxNs = updateMaxNs;
            this.producerTimestampDeltas = producerTimestampDeltas;
            this.producerTimestampDeltaTotalNs = producerTimestampDeltaTotalNs;
            this.producerTimestampDeltaMinNs = producerTimestampDeltaMinNs;
            this.producerTimestampDeltaMaxNs = producerTimestampDeltaMaxNs;
            this.producerTimestampRepeated = producerTimestampRepeated;
            this.producerTimestampInvalid = producerTimestampInvalid;
            this.frameAgeSamples = frameAgeSamples;
            this.frameAgeNonPositive = frameAgeNonPositive;
            this.frameAgeFuture = frameAgeFuture;
            this.frameAgeStale = frameAgeStale;
            this.frameAgeTotalNs = frameAgeTotalNs;
            this.frameAgeMaxNs = frameAgeMaxNs;
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
        private int callbackGaps;
        private long callbackGapTotalNs;
        private long callbackGapMaxNs;
        private long updateTotalNs;
        private long updateMaxNs;
        private long previousProducerTimestampNs = -1L;
        private int producerTimestampDeltas;
        private long producerTimestampDeltaTotalNs;
        private long producerTimestampDeltaMinNs = Long.MAX_VALUE;
        private long producerTimestampDeltaMaxNs;
        private int producerTimestampRepeated;
        private int producerTimestampInvalid;
        private int frameAgeSamples;
        private int frameAgeNonPositive;
        private int frameAgeFuture;
        private int frameAgeStale;
        private long frameAgeTotalNs;
        private long frameAgeMaxNs;
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
                long updatedNs,
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
            if (startedNs < 0L) startedNs = callbackNs;
            if (previousCallbackNs >= 0L) {
                long gap = Math.max(0L, callbackNs - previousCallbackNs);
                callbackGaps++;
                callbackGapTotalNs += gap;
                callbackGapMaxNs = Math.max(callbackGapMaxNs, gap);
            }
            previousCallbackNs = callbackNs;
            callbacks++;
            recordProducerTimestamp(producerTimestampNs, updatedNs);
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
                    callbacks, callbackGaps, callbackGapTotalNs, callbackGapMaxNs,
                    updateTotalNs, updateMaxNs,
                    producerTimestampDeltas, producerTimestampDeltaTotalNs,
                    producerTimestampDeltaMinNs == Long.MAX_VALUE
                            ? 0L : producerTimestampDeltaMinNs,
                    producerTimestampDeltaMaxNs, producerTimestampRepeated,
                    producerTimestampInvalid, frameAgeSamples, frameAgeNonPositive,
                    frameAgeFuture,
                    frameAgeStale, frameAgeTotalNs,
                    frameAgeMaxNs, swaps, preSwapTotalNs, preSwapMaxNs,
                    swapWaitTotalNs, swapWaitMaxNs, drawMaxNs,
                    renderTotalNs, renderMaxNs, targetsCurrent, targetsMax,
                    targetPixelsCurrent, targetPixelsMax, targetWidthMax, targetHeightMax,
                    targetDimensions,
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

        private void recordProducerTimestamp(long timestampNs, long nowNs) {
            if (timestampNs <= 0L) {
                producerTimestampInvalid++;
                frameAgeNonPositive++;
                return;
            }
            if (previousProducerTimestampNs > 0L) {
                long deltaNs = timestampNs - previousProducerTimestampNs;
                if (deltaNs == 0L) {
                    producerTimestampRepeated++;
                } else if (deltaNs > 0L) {
                    producerTimestampDeltas++;
                    producerTimestampDeltaTotalNs += deltaNs;
                    producerTimestampDeltaMinNs = Math.min(
                            producerTimestampDeltaMinNs, deltaNs);
                    producerTimestampDeltaMaxNs = Math.max(
                            producerTimestampDeltaMaxNs, deltaNs);
                } else {
                    producerTimestampInvalid++;
                    return;
                }
            }
            previousProducerTimestampNs = timestampNs;
            if (nowNs >= timestampNs) {
                long ageNs = nowNs - timestampNs;
                if (ageNs <= MAX_VALID_FRAME_AGE_NS) {
                    frameAgeSamples++;
                    frameAgeTotalNs += ageNs;
                    frameAgeMaxNs = Math.max(frameAgeMaxNs, ageNs);
                } else {
                    frameAgeStale++;
                }
            } else {
                frameAgeFuture++;
            }
        }

        private void reset(long callbackNs) {
            startedNs = callbackNs;
            callbacks = 0;
            callbackGaps = 0;
            callbackGapTotalNs = 0L;
            callbackGapMaxNs = 0L;
            updateTotalNs = 0L;
            updateMaxNs = 0L;
            producerTimestampDeltas = 0;
            producerTimestampDeltaTotalNs = 0L;
            producerTimestampDeltaMinNs = Long.MAX_VALUE;
            producerTimestampDeltaMaxNs = 0L;
            producerTimestampRepeated = 0;
            producerTimestampInvalid = 0;
            frameAgeSamples = 0;
            frameAgeNonPositive = 0;
            frameAgeFuture = 0;
            frameAgeStale = 0;
            frameAgeTotalNs = 0L;
            frameAgeMaxNs = 0L;
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

    private static final class Target {
        final Surface surface;
        final int index;
        final EGLSurface eglSurface;

        Target(Surface surface, int index, EGLSurface eglSurface) {
            this.surface = surface;
            this.index = index;
            this.eglSurface = eglSurface;
        }
    }
}
