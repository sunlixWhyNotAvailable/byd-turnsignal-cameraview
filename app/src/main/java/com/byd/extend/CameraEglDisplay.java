package com.byd.extend;

import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;

/** Process-local ownership: eglInitialize itself does not reference-count display users. */
final class CameraEglDisplay {
    private static EGLDisplay display = EGL14.EGL_NO_DISPLAY;
    private static int owners;
    private static SharedGroup sharedGroup;

    static final class SharedGroup {
        final EGLDisplay display;
        final EGLConfig config;
        final int glVersion;
        EGLContext rootContext;
        int owners;

        SharedGroup(EGLDisplay display, EGLConfig config, EGLContext rootContext, int glVersion) {
            this.display = display;
            this.config = config;
            this.rootContext = rootContext;
            this.glVersion = glVersion;
        }
    }

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

    /** One config and root context shared by the direct-camera hub and live compositor. */
    static synchronized SharedGroup acquireSharedGroup() {
        if (sharedGroup == null) {
            EGLDisplay ownedDisplay = acquire();
            EGLContext root = EGL14.EGL_NO_CONTEXT;
            try {
                EGLConfig[] configs = new EGLConfig[1];
                int[] count = new int[1];
                int[] attributes = {
                        EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                        EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT | EGL14.EGL_PBUFFER_BIT,
                        EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
                        EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_NONE
                };
                require(EGL14.eglChooseConfig(ownedDisplay, attributes, 0, configs, 0,
                        1, count, 0) && count[0] > 0, "shared camera EGL config unavailable");
                root = EGL14.eglCreateContext(ownedDisplay, configs[0],
                        EGL14.EGL_NO_CONTEXT, new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION,
                                3, EGL14.EGL_NONE}, 0);
                int version = 3;
                if (root == EGL14.EGL_NO_CONTEXT) {
                    EGL14.eglGetError();
                    version = 2;
                    root = EGL14.eglCreateContext(ownedDisplay, configs[0],
                            EGL14.EGL_NO_CONTEXT, new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION,
                                    version, EGL14.EGL_NONE}, 0);
                }
                require(root != EGL14.EGL_NO_CONTEXT, "shared camera EGL context unavailable");
                sharedGroup = new SharedGroup(ownedDisplay, configs[0], root, version);
            } catch (RuntimeException | Error failure) {
                if (root != EGL14.EGL_NO_CONTEXT) {
                    try { require(EGL14.eglDestroyContext(ownedDisplay, root),
                            "eglDestroyContext(failed shared camera root)"); }
                    catch (Throwable cleanup) { if (cleanup != failure) failure.addSuppressed(cleanup); }
                }
                try { release(ownedDisplay); }
                catch (Throwable cleanup) { if (cleanup != failure) failure.addSuppressed(cleanup); }
                throw failure;
            }
        }
        if (sharedGroup.rootContext == EGL14.EGL_NO_CONTEXT)
            throw new IllegalStateException("shared camera EGL group release is incomplete");
        sharedGroup.owners++;
        return sharedGroup;
    }

    static synchronized void releaseSharedGroup(SharedGroup owned) {
        if (owned == null || owned != sharedGroup || owned.owners == 0)
            throw new IllegalStateException("Releasing an unowned camera EGL group");
        if (owned.owners > 1) {
            owned.owners--;
            return;
        }
        if (owned.rootContext != EGL14.EGL_NO_CONTEXT) {
            require(EGL14.eglDestroyContext(owned.display, owned.rootContext),
                    "eglDestroyContext(shared camera root)");
            owned.rootContext = EGL14.EGL_NO_CONTEXT;
        }
        release(owned.display);
        owned.owners = 0;
        sharedGroup = null;
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
