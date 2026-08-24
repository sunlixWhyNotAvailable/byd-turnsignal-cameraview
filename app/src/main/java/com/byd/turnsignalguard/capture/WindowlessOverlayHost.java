package com.byd.turnsignalguard.capture;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.PixelFormat;
import android.os.IBinder;
import android.view.Display;
import android.view.SurfaceControl;
import android.view.SurfaceControlViewHost;
import android.view.View;
import android.view.WindowManager;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** The shell's trusted, non-touchable display surface. */
@SuppressLint({
        "BlockedPrivateApi", "SoonBlockedPrivateApi", "DiscouragedPrivateApi",
        "PrivateApi", "NewApi"
})
final class WindowlessOverlayHost {
    static final int REVERSE_LAYER = Integer.MAX_VALUE - 32;
    private static final int CAMERA_LAYER_BASE = Integer.MAX_VALUE - 16;

    private final Context context;
    private final int layer;
    private final Display display;
    private SurfaceControlViewHost host;
    private SurfaceControlViewHost.SurfacePackage surfacePackage;
    private SurfaceControl root;
    private int width;
    private int height;
    private String trustedApi;

    WindowlessOverlayHost(Context context, Display display, int layer) {
        if (context == null || display == null) {
            throw new IllegalArgumentException("context and display are required");
        }
        this.context = context;
        this.display = display;
        this.layer = layer;
    }

    static int cameraLayer(int overlayId) {
        if (overlayId < 0 || overlayId > 11) {
            throw new IllegalArgumentException("invalid camera overlay id: " + overlayId);
        }
        return CAMERA_LAYER_BASE + overlayId;
    }

    static float alphaForTransparency(int transparencyPercent) {
        if (transparencyPercent < 0 || transparencyPercent > 100) {
            throw new IllegalArgumentException("transparency must be 0..100");
        }
        return (100 - transparencyPercent) / 100.0f;
    }

    void attach(View view, int nextWidth, int nextHeight, int x, int y, String title)
            throws Exception {
        if (view == null || nextWidth <= 0 || nextHeight <= 0) {
            throw new IllegalArgumentException("valid view and size are required");
        }
        if (host != null) throw new IllegalStateException("overlay host already attached");
        exemptHiddenApis();
        SurfaceControlViewHost nextHost = new SurfaceControlViewHost(
                context, display, (IBinder) null);
        SurfaceControlViewHost.SurfacePackage nextPackage = null;
        SurfaceControl nextRoot = null;
        try {
            WindowManager.LayoutParams layout = new WindowManager.LayoutParams(
                    nextWidth,
                    nextHeight,
                    WindowManager.LayoutParams.TYPE_APPLICATION,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                    PixelFormat.RGBA_8888);
            layout.alpha = 1.0f;
            layout.windowAnimations = 0;
            layout.setTitle(title == null ? "BYD trusted camera" : title);
            trustedApi = setTrustedOverlay(layout);
            setView(nextHost, view, layout);
            nextPackage = nextHost.getSurfacePackage();
            if (nextPackage == null) {
                throw new IllegalStateException("surface package unavailable");
            }
            nextRoot = getSurfaceControl(nextPackage);
            if (nextRoot == null || !nextRoot.isValid()) {
                throw new IllegalStateException("root surface unavailable");
            }
            try (SurfaceControl.Transaction transaction = new SurfaceControl.Transaction()) {
                setLayerStack(transaction, nextRoot, displayLayerStack(display));
                transaction.setLayer(nextRoot, layer);
                setPosition(transaction, nextRoot, x, y);
                transaction.setAlpha(nextRoot, 0.0f);
                transaction.setVisibility(nextRoot, true);
                transaction.apply();
            }
            host = nextHost;
            surfacePackage = nextPackage;
            root = nextRoot;
            width = nextWidth;
            height = nextHeight;
        } catch (Throwable error) {
            try {
                nextHost.release();
            } catch (Throwable ignored) {}
            if (nextRoot != null) {
                try (SurfaceControl.Transaction transaction = new SurfaceControl.Transaction()) {
                    remove(transaction, nextRoot);
                    transaction.apply();
                } catch (Throwable ignored) {}
            }
            if (nextPackage != null) {
                try {
                    nextPackage.release();
                } catch (Throwable ignored) {}
            }
            throw asException(error);
        }
    }

    void setPosition(int x, int y) throws Exception {
        requireAttached();
        try (SurfaceControl.Transaction transaction = new SurfaceControl.Transaction()) {
            setPosition(transaction, root, x, y);
            transaction.apply();
        }
    }

    void setVisible(boolean visible, float visibleAlpha) throws Exception {
        requireAttached();
        if (!Float.isFinite(visibleAlpha) || visibleAlpha < 0.0f || visibleAlpha > 1.0f) {
            throw new IllegalArgumentException("visible alpha must be 0..1");
        }
        try (SurfaceControl.Transaction transaction = new SurfaceControl.Transaction()) {
            transaction.setVisibility(root, true);
            transaction.setAlpha(root, visible ? visibleAlpha : 0.0f);
            transaction.apply();
        }
    }

    void release() throws Exception {
        SurfaceControlViewHost activeHost = host;
        SurfaceControl activeRoot = root;
        SurfaceControlViewHost.SurfacePackage activePackage = surfacePackage;
        host = null;
        root = null;
        surfacePackage = null;
        width = 0;
        height = 0;
        trustedApi = null;
        Throwable failure = null;
        if (activeHost != null) {
            try {
                activeHost.release();
            } catch (Throwable error) {
                failure = rootCause(error);
            }
        }
        if (activeRoot != null) {
            try (SurfaceControl.Transaction transaction = new SurfaceControl.Transaction()) {
                remove(transaction, activeRoot);
                transaction.apply();
            } catch (Throwable error) {
                if (failure == null) failure = rootCause(error);
            }
        }
        if (activePackage != null) {
            try {
                activePackage.release();
            } catch (Throwable error) {
                if (failure == null) failure = rootCause(error);
            }
        }
        if (failure != null) throw asException(failure);
    }

    int width() {
        return width;
    }

    int height() {
        return height;
    }

    String displayName() {
        return display.getName();
    }

    int layer() {
        return layer;
    }

    String trustedApi() {
        return trustedApi;
    }

    private void requireAttached() {
        if (root == null) throw new IllegalStateException("overlay host unavailable");
    }

    private static String setTrustedOverlay(WindowManager.LayoutParams layout) throws Exception {
        try {
            Method method = WindowManager.LayoutParams.class
                    .getDeclaredMethod("setTrustedOverlay");
            method.setAccessible(true);
            method.invoke(layout);
            return "method";
        } catch (Throwable methodError) {
            Field field = WindowManager.LayoutParams.class.getField("privateFlags");
            field.setInt(layout, field.getInt(layout) | 0x20000000);
            return "field";
        }
    }

    private static void setView(
            SurfaceControlViewHost host, View view, WindowManager.LayoutParams layout)
            throws Exception {
        Method method = SurfaceControlViewHost.class.getDeclaredMethod(
                "setView", View.class, WindowManager.LayoutParams.class);
        method.setAccessible(true);
        method.invoke(host, view, layout);
    }

    private static SurfaceControl getSurfaceControl(
            SurfaceControlViewHost.SurfacePackage surfacePackage) throws Exception {
        Method method = SurfaceControlViewHost.SurfacePackage.class.getDeclaredMethod(
                "getSurfaceControl");
        method.setAccessible(true);
        return (SurfaceControl) method.invoke(surfacePackage);
    }

    private static int displayLayerStack(Display display) {
        try {
            Method method = Display.class.getDeclaredMethod("getLayerStack");
            method.setAccessible(true);
            return (Integer) method.invoke(display);
        } catch (Throwable ignored) {
            return display.getDisplayId();
        }
    }

    private static void setLayerStack(
            SurfaceControl.Transaction transaction, SurfaceControl surface, int layerStack)
            throws Exception {
        Method method = SurfaceControl.Transaction.class.getDeclaredMethod(
                "setLayerStack", SurfaceControl.class, int.class);
        method.setAccessible(true);
        method.invoke(transaction, surface, layerStack);
    }

    private static void setPosition(
            SurfaceControl.Transaction transaction, SurfaceControl surface, float x, float y)
            throws Exception {
        Method method = SurfaceControl.Transaction.class.getDeclaredMethod(
                "setPosition", SurfaceControl.class, float.class, float.class);
        method.setAccessible(true);
        method.invoke(transaction, surface, x, y);
    }

    private static void remove(SurfaceControl.Transaction transaction, SurfaceControl surface)
            throws Exception {
        Method method = SurfaceControl.Transaction.class.getDeclaredMethod(
                "remove", SurfaceControl.class);
        method.setAccessible(true);
        method.invoke(transaction, surface);
    }

    private static void exemptHiddenApis() throws Exception {
        Class<?> type = Class.forName("dalvik.system.VMRuntime");
        Method getRuntime = type.getDeclaredMethod("getRuntime");
        Method setExemptions = type.getDeclaredMethod(
                "setHiddenApiExemptions", String[].class);
        Object runtime = getRuntime.invoke(null);
        setExemptions.invoke(runtime,
                (Object) new String[]{
                        "Landroid/app/", "Landroid/hardware/", "Landroid/view/",
                        "Landroid/os/SystemProperties;"});
    }

    private static Exception asException(Throwable error) {
        if (error instanceof Exception) return (Exception) error;
        return new RuntimeException(error);
    }

    private static Throwable rootCause(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current != current.getCause()) {
            current = current.getCause();
        }
        return current;
    }
}
