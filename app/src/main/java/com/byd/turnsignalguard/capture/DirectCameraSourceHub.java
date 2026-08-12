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
    }

    private static final int CALL_TIMEOUT_MS = 1500;
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
        try {
            makeCurrent(idleSurface);
            source.texture.updateTexImage();
            source.texture.getTransformMatrix(source.matrix);
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
        for (int i = targets.size() - 1; i >= 0; i--) {
            Target target = targets.get(i);
            if (target.index != source.index) continue;
            try {
                draw(source, target);
            } catch (Throwable error) {
                EGL14.eglDestroySurface(display, target.eglSurface);
                targets.remove(i);
                listener.onConsumerFailure(target.surface, target.index, error);
            }
        }
        makeCurrent(idleSurface);
    }

    private void draw(Source source, Target target) {
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
        require(EGL14.eglSwapBuffers(display, target.eglSurface),
                "downstream buffer swap failed");
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
        boolean matrixReported;

        Source(int index, int textureName, SurfaceTexture texture, Surface surface) {
            this.index = index;
            this.textureName = textureName;
            this.texture = texture;
            this.surface = surface;
        }
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
