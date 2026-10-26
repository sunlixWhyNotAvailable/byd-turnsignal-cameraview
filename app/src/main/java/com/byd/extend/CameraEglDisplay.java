package com.byd.extend;

import android.opengl.EGL14;
import android.opengl.EGLDisplay;

/** Process-local ownership: eglInitialize itself does not reference-count display users. */
final class CameraEglDisplay {
    private static EGLDisplay display = EGL14.EGL_NO_DISPLAY;
    private static int owners;

    private CameraEglDisplay() { }

    static synchronized EGLDisplay acquire() {
        if (owners == 0) {
            EGLDisplay candidate = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            require(candidate != EGL14.EGL_NO_DISPLAY, "eglGetDisplay");
            require(EGL14.eglInitialize(candidate, null, 0, null, 0), "eglInitialize");
            display = candidate;
        }
        owners++;
        return display;
    }

    static synchronized void release(EGLDisplay owned) {
        if (owners == 0 || !display.equals(owned))
            throw new IllegalStateException("Releasing an unowned camera EGL display");
        if (owners == 1) {
            require(EGL14.eglTerminate(display), "eglTerminate");
            display = EGL14.EGL_NO_DISPLAY;
        }
        owners--;
    }

    private static void require(boolean success, String operation) {
        if (!success) throw new IllegalStateException(operation
                + " failed: EGL error=0x" + Integer.toHexString(EGL14.eglGetError()));
    }
}
