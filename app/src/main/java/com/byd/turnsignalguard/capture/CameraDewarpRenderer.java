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
import android.util.Log;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

final class CameraDewarpRenderer {
    private static final String TAG = "CameraDewarpRenderer";
    private static final int START_TIMEOUT_MS = 1500;
    private static final float[] VERTICES = {
            -1, -1, 0, 0,
             1, -1, 1, 0,
            -1,  1, 0, 1,
             1,  1, 1, 1
    };
    private static final String VERTEX_SHADER =
            "attribute vec2 aPosition;\n"
                    + "attribute vec2 aTexCoord;\n"
                    + "varying vec2 vTexCoord;\n"
                    + "void main(){ gl_Position=vec4(aPosition,0.0,1.0);"
                    + "vTexCoord=aTexCoord; }\n";
    private static final String FRAGMENT_SHADER =
            "#extension GL_OES_EGL_image_external : require\n"
                    + "precision mediump float;\n"
                    + "uniform samplerExternalOES uTexture;\n"
                    + "uniform mat4 uTextureMatrix;\n"
                    + "uniform float uStrength;\n"
                    + "uniform vec2 uCenter;\n"
                    + "uniform float uZoom;\n"
                    + "varying vec2 vTexCoord;\n"
                    + "void main(){\n"
                    + " vec2 base=(uTextureMatrix*vec4(vTexCoord,0.0,1.0)).xy;\n"
                    + " vec2 q=(base-uCenter)/uZoom;\n"
                    + " float r2=4.0*dot(q,q);\n"
                    + " vec2 uv=uCenter+q*(1.0+uStrength*r2);\n"
                    + " if(uv.x<0.0||uv.x>1.0||uv.y<0.0||uv.y>1.0){"
                    + "gl_FragColor=vec4(0.0,0.0,0.0,1.0);return;}\n"
                    + " gl_FragColor=texture2D(uTexture,uv);\n"
                    + "}\n";

    private final HandlerThread thread = new HandlerThread("camera-dewarp");
    private final SurfaceTexture outputTexture;
    private final int width;
    private final int height;
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
    private int strengthLocation;
    private int centerLocation;
    private int zoomLocation;
    private int textureLocation;
    private FloatBuffer vertices;
    private final float[] textureMatrix = new float[16];
    private Throwable startupError;

    private CameraDewarpRenderer(
            SurfaceTexture outputTexture, int width, int height, CameraDewarpConfig config) {
        this.outputTexture = outputTexture;
        this.width = width;
        this.height = height;
        this.config = config;
    }

    static CameraDewarpRenderer start(
            SurfaceTexture outputTexture, int width, int height, CameraDewarpConfig config) {
        CameraDewarpRenderer renderer = new CameraDewarpRenderer(
                outputTexture, width, height, config);
        return renderer.startInternal() ? renderer : null;
    }

    Surface cameraSurface() {
        return cameraSurface;
    }

    void update(CameraDewarpConfig value) {
        config = value;
    }

    void release() {
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
                releaseGl();
            } finally {
                ready.countDown();
            }
        });
        try {
            if (!ready.await(START_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                startupError = new IllegalStateException("dewarp renderer startup timeout");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            startupError = interrupted;
        }
        if (startupError == null && cameraSurface != null && cameraSurface.isValid()) return true;
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
        strengthLocation = GLES20.glGetUniformLocation(program, "uStrength");
        centerLocation = GLES20.glGetUniformLocation(program, "uCenter");
        zoomLocation = GLES20.glGetUniformLocation(program, "uZoom");
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
            try {
                renderFrame();
            } catch (Throwable error) {
                Log.e(TAG, "Dewarp frame failed", error);
                texture.setOnFrameAvailableListener(null);
            }
        }, handler);
        cameraSurface = new Surface(cameraTexture);
        vertices = ByteBuffer.allocateDirect(VERTICES.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        vertices.put(VERTICES).position(0);
    }

    private void renderFrame() {
        if (cameraTexture == null || eglSurface == EGL14.EGL_NO_SURFACE) return;
        cameraTexture.updateTexImage();
        cameraTexture.getTransformMatrix(textureMatrix);
        CameraDewarpConfig value = config;

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
        GLES20.glUniform1f(strengthLocation,
                value.enabled ? value.strength / 100.0f : 0.0f);
        GLES20.glUniform2f(centerLocation,
                value.centerX / 100.0f, value.centerY / 100.0f);
        GLES20.glUniform1f(zoomLocation,
                value.enabled ? value.zoom / 100.0f : 1.0f);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
        GLES20.glUniform1i(textureLocation, 0);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        require(EGL14.eglSwapBuffers(eglDisplay, eglSurface), "EGL swap failed");
    }

    private void releaseGl() {
        if (cameraTexture != null) cameraTexture.setOnFrameAvailableListener(null);
        if (cameraSurface != null) cameraSurface.release();
        cameraSurface = null;
        if (cameraTexture != null) cameraTexture.release();
        cameraTexture = null;
        if (textureId != 0) GLES20.glDeleteTextures(1, new int[]{textureId}, 0);
        textureId = 0;
        if (program != 0) GLES20.glDeleteProgram(program);
        program = 0;
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
