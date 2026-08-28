package com.byd.extend;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.PixelFormat;
import android.os.IBinder;
import android.os.SystemClock;
import android.view.Display;
import android.view.SurfaceControl;
import android.view.SurfaceControlViewHost;
import android.view.View;
import android.view.WindowManager;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;

/** The shell's trusted, non-touchable display surface. */
@SuppressLint({
        "BlockedPrivateApi", "SoonBlockedPrivateApi", "DiscouragedPrivateApi",
        "PrivateApi", "NewApi"
})
final class WindowlessOverlayHost {
    static final int REVERSE_LAYER = Integer.MAX_VALUE - 32;
    static final int REVERSE_CONTROL_LAYER = Integer.MAX_VALUE - 4;
    private static final int CAMERA_LAYER_BASE = Integer.MAX_VALUE - 16;
    private static final AtomicLong NEXT_HOST_ID = new AtomicLong(1L);

    private final Context context;
    private final int layer;
    private final Display display;
    private final long hostId;
    private final String overlayType;
    private final int cameraId;
    private final BiConsumer<String, Object[]> eventSink;
    private SurfaceControlViewHost host;
    private SurfaceControlViewHost.SurfacePackage surfacePackage;
    private SurfaceControl root;
    private View attachedView;
    private int width;
    private int height;
    private String trustedApi;
    private int diagnosticRequestId;
    private String diagnosticSurfaceGeneration = "";

    WindowlessOverlayHost(
            Context context, Display display, int layer,
            String overlayType, int cameraId, BiConsumer<String, Object[]> eventSink) {
        if (context == null || display == null) {
            throw new IllegalArgumentException("context and display are required");
        }
        if (overlayType == null || overlayType.isEmpty()) {
            throw new IllegalArgumentException("overlay type is required");
        }
        this.context = context;
        this.display = display;
        this.layer = layer;
        this.hostId = NEXT_HOST_ID.getAndIncrement();
        this.overlayType = overlayType;
        this.cameraId = cameraId;
        this.eventSink = eventSink;
    }

    void setDiagnosticState(int requestId, String surfaceGeneration) {
        diagnosticRequestId = requestId;
        diagnosticSurfaceGeneration = surfaceGeneration == null ? "" : surfaceGeneration;
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
        attach(view, nextWidth, nextHeight, x, y, title, false, false);
    }

    void attach(
            View view, int nextWidth, int nextHeight, int x, int y, String title,
            boolean touchable, boolean initiallyHidden) throws Exception {
        if (view == null || nextWidth <= 0 || nextHeight <= 0) {
            throw new IllegalArgumentException("valid view and size are required");
        }
        if (host != null) throw new IllegalStateException("overlay host already attached");
        long attachStart = SystemClock.elapsedRealtimeNanos();
        emitLifecycle("attach_start", attachStart, view, null, null, null);
        SurfaceControlViewHost nextHost = null;
        SurfaceControlViewHost.SurfacePackage nextPackage = null;
        SurfaceControl nextRoot = null;
        try {
            exemptHiddenApis();
            nextHost = new SurfaceControlViewHost(context, display, (IBinder) null);
            WindowManager.LayoutParams layout = new WindowManager.LayoutParams(
                    nextWidth,
                    nextHeight,
                    WindowManager.LayoutParams.TYPE_APPLICATION,
                    windowFlags(touchable),
                    PixelFormat.RGBA_8888);
            layout.alpha = 1.0f;
            layout.windowAnimations = 0;
            layout.setTitle(title == null ? "BYD trusted camera" : title);
            trustedApi = setTrustedOverlay(layout);
            setView(nextHost, view, layout);
            emitLifecycle("view_set", attachStart, view, null, null, nextHost);
            nextPackage = nextHost.getSurfacePackage();
            if (nextPackage == null) {
                throw new IllegalStateException("surface package unavailable");
            }
            emitLifecycle("package_acquired", attachStart, view, null, nextPackage, nextHost);
            nextRoot = getSurfaceControl(nextPackage);
            if (nextRoot == null || !nextRoot.isValid()) {
                throw new IllegalStateException("root surface unavailable");
            }
            try (SurfaceControl.Transaction transaction = new SurfaceControl.Transaction()) {
                setLayerStack(transaction, nextRoot, displayLayerStack(display));
                transaction.setLayer(nextRoot, layer);
                setPosition(transaction, nextRoot, x, y);
                transaction.setAlpha(nextRoot, 0.0f);
                transaction.setVisibility(nextRoot, !initiallyHidden);
                transaction.apply();
            }
            host = nextHost;
            surfacePackage = nextPackage;
            root = nextRoot;
            attachedView = view;
            width = nextWidth;
            height = nextHeight;
            emitLifecycle("root_attached", attachStart, view, nextRoot, nextPackage, nextHost);
        } catch (Throwable error) {
            emitLifecycle("attach_error", attachStart, view, nextRoot, nextPackage, nextHost, error);
            if (nextHost != null) {
                try {
                    nextHost.release();
                } catch (Throwable ignored) {}
            }
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
        long visibilityStart = SystemClock.elapsedRealtimeNanos();
        try (SurfaceControl.Transaction transaction = new SurfaceControl.Transaction()) {
            transaction.setVisibility(root, true);
            transaction.setAlpha(root, visible ? visibleAlpha : 0.0f);
            transaction.apply();
        }
        emitLifecycle("visibility", visibilityStart, attachedView, root, surfacePackage, host,
                null, "visible", visible);
    }

    void setStrictVisible(boolean visible, float visibleAlpha) throws Exception {
        requireAttached();
        if (!Float.isFinite(visibleAlpha) || visibleAlpha < 0.0f || visibleAlpha > 1.0f) {
            throw new IllegalArgumentException("visible alpha must be 0..1");
        }
        long visibilityStart = SystemClock.elapsedRealtimeNanos();
        try (SurfaceControl.Transaction transaction = new SurfaceControl.Transaction()) {
            transaction.setVisibility(root, visible);
            transaction.setAlpha(root, visible ? visibleAlpha : 0.0f);
            transaction.apply();
        }
        emitLifecycle("strict_visibility", visibilityStart, attachedView, root,
                surfacePackage, host, null, "visible", visible);
    }

    static int windowFlags(boolean touchable) {
        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
        return touchable ? flags : flags | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
    }

    void release() throws Exception {
        SurfaceControlViewHost activeHost = host;
        SurfaceControl activeRoot = root;
        SurfaceControlViewHost.SurfacePackage activePackage = surfacePackage;
        View activeView = attachedView;
        long releaseStart = SystemClock.elapsedRealtimeNanos();
        emitLifecycle("release_start", releaseStart, activeView, activeRoot,
                activePackage, activeHost);
        host = null;
        root = null;
        surfacePackage = null;
        attachedView = null;
        width = 0;
        height = 0;
        trustedApi = null;
        Throwable failure = null;
        if (activeHost != null) {
            try {
                activeHost.release();
                emitLifecycle("host_released", releaseStart, activeView, activeRoot,
                        activePackage, activeHost);
            } catch (Throwable error) {
                failure = rootCause(error);
                emitLifecycle("release_error", releaseStart, activeView, activeRoot,
                        activePackage, activeHost, error, "host_release");
            }
        }
        if (activeRoot != null) {
            try (SurfaceControl.Transaction transaction = new SurfaceControl.Transaction()) {
                remove(transaction, activeRoot);
                transaction.apply();
                emitLifecycle("root_removed", releaseStart, activeView, activeRoot,
                        activePackage, activeHost);
            } catch (Throwable error) {
                if (failure == null) failure = rootCause(error);
                emitLifecycle("release_error", releaseStart, activeView, activeRoot,
                        activePackage, activeHost, error, "root_remove");
            }
        }
        if (activePackage != null) {
            try {
                activePackage.release();
                emitLifecycle("package_released", releaseStart, activeView, activeRoot,
                        activePackage, activeHost);
            } catch (Throwable error) {
                if (failure == null) failure = rootCause(error);
                emitLifecycle("release_error", releaseStart, activeView, activeRoot,
                        activePackage, activeHost, error, "package_release");
            }
        }
        if (failure != null) {
            emitLifecycle("release_error", releaseStart, activeView, activeRoot,
                    activePackage, activeHost, failure, "release_complete");
            throw asException(failure);
        }
        emitLifecycle("release_complete", releaseStart, activeView, activeRoot,
                activePackage, activeHost);
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

    private void emitLifecycle(
            String stage, long startNanos, View view, SurfaceControl surface,
            SurfaceControlViewHost.SurfacePackage packageValue,
            SurfaceControlViewHost hostValue, Throwable error, Object... extraFields) {
        if (eventSink == null) return;
        long durationMs = startNanos <= 0L ? 0L
                : Math.max(0L, (SystemClock.elapsedRealtimeNanos() - startNanos) / 1_000_000L);
        Object[] fields = new Object[]{
                "stage", stage,
                "host_id", hostId,
                "overlay_type", overlayType,
                "camera_id", cameraId,
                "request_id", diagnosticRequestId,
                "surface_generation", diagnosticSurfaceGeneration,
                "root_id", identity(surface),
                "surface_id", identity(surface),
                "surface_package_id", identity(packageValue),
                "root_valid", surface != null && surface.isValid(),
                "view_attached", view != null && view.isAttachedToWindow(),
                "display_id", display.getDisplayId(),
                "display_name", display.getName(),
                "layer", layer,
                "thread", Thread.currentThread().getName(),
                "stage_duration_ms", durationMs,
                "error", error == null ? "" : summary(error)
        };
        if (extraFields.length == 0) {
            try {
                eventSink.accept("windowless_host_lifecycle", fields);
            } catch (Throwable ignored) {}
            return;
        }
        Object[] expanded = new Object[fields.length + extraFields.length];
        System.arraycopy(fields, 0, expanded, 0, fields.length);
        System.arraycopy(extraFields, 0, expanded, fields.length, extraFields.length);
        try {
            eventSink.accept("windowless_host_lifecycle", expanded);
        } catch (Throwable ignored) {}
    }

    private void emitLifecycle(
            String stage, long startNanos, View view, SurfaceControl surface,
            SurfaceControlViewHost.SurfacePackage packageValue,
            SurfaceControlViewHost hostValue) {
        emitLifecycle(stage, startNanos, view, surface, packageValue, hostValue, null);
    }

    private static int identity(Object value) {
        return value == null ? 0 : System.identityHashCode(value);
    }

    private static String summary(Throwable error) {
        String message = error.getMessage();
        return error.getClass().getSimpleName() + (message == null ? "" : ": " + message);
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
