package com.byd.extend;

import android.content.Context;
import android.media.Image;
import android.opengl.*;
import android.os.Handler;
import android.view.Surface;
import java.util.function.Consumer;

/** One OEM YUV source: original planes for detection, owned GPU snapshots for recording. */
final class MotionSharedSource {
    private EGLDisplay display = EGL14.EGL_NO_DISPLAY;
    private EGLContext root = EGL14.EGL_NO_CONTEXT;
    private CameraFramePump pump;
    private volatile MotionGpuComposer composer;
    private long windowStart, acquiredFrames, copiedFrames;

    MotionSharedSource(Consumer<Throwable> errors) throws Exception {
        try {
            display = CameraEglDisplay.acquire();
            int[] count = new int[1];
            EGLConfig[] configs = new EGLConfig[1];
            require(EGL14.eglChooseConfig(display, new int[]{
                    EGL14.EGL_RENDERABLE_TYPE, 0x40,
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT | EGL14.EGL_WINDOW_BIT,
                    EGL14.EGL_RED_SIZE,8,EGL14.EGL_GREEN_SIZE,8,EGL14.EGL_BLUE_SIZE,8,
                    0x3142,1,EGL14.EGL_NONE}, 0, configs, 0, 1, count, 0)
                    && count[0] > 0, "Motion EGL config");
            root = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
                    new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION,3,EGL14.EGL_NONE},0);
            require(root != EGL14.EGL_NO_CONTEXT, "Motion EGL context");
            pump = new CameraFramePump(display, configs[0], root, 3, 4123, new CameraFramePump.Listener() {
                @Override public void available() {
                    MotionGpuComposer target = composer;
                    int slot = pump.mailbox.acquire();
                    if (slot < 0) return;
                    if (target == null || !target.offerComposition(pump, slot)) pump.releaseRead(slot);
                }
                @Override public void failed(Throwable error) { errors.accept(error); }
                @Override public void acquired(CameraFramePump.Acquisition sample) { }
            });
            pump.start();
        } catch (Exception | Error error) {
            try { close(); } catch (Exception cleanup) { error.addSuppressed(cleanup); }
            throw error;
        }
    }

    Handler handler() { return pump.acquisitionHandler(); }
    void snapshot(Image image) {
        long now = android.os.SystemClock.elapsedRealtime();
        if (windowStart == 0) windowStart = now;
        acquiredFrames++;
        if (pump.snapshotImage(image)) copiedFrames++;
        if (now - windowStart >= 30_000) {
            String message = "motion_shared_source window_ms=" + (now - windowStart)
                    + " acquired_frames=" + acquiredFrames + " copied_frames=" + copiedFrames;
            android.util.Log.i("MotionSharedSource", message);
            System.out.println(message);
            windowStart = now;
            acquiredFrames = copiedFrames = 0;
        }
    }

    void startRecording(Context context, Surface encoder, int width, int height,
            int[] order, String session, MotionFrameTimestamp clock, Consumer<String> errors) throws Exception {
        if (composer != null) throw new IllegalStateException("Motion composer already owned");
        MotionGpuComposer next = new MotionGpuComposer(context, width, height, order,
                session, clock, root, 3, errors);
        composer = next; // Retain cleanup even if initialization times out.
        next.initialize(encoder);
        MotionCompositionLayout.Rect cell =
                MotionCompositionLayout.cameraRectForCell(0, width, height);
        pump.setOutputSize(cell.width() * 2, cell.height() * 2);
    }

    void stopRecording(Runnable closed) {
        pump.setOutputSize(0, 0);
        MotionGpuComposer old = composer;
        if (old == null) return;
        // Barrier: no new snapshot may be offered while the compositor releases its leases.
        DirectCameraSourceHub.SerializedCall<Void> barrier = new DirectCameraSourceHub.SerializedCall<>();
        if (!handler().post(() -> barrier.run(() -> null)))
            throw new IllegalStateException("Motion acquisition stopped before encoder detach");
        try { barrier.result(); }
        catch (Exception error) { throw new IllegalStateException(error); }
        old.close(closed);
        composer = null;
    }

    void close() throws Exception {
        if (composer != null) throw new IllegalStateException("Motion encoder still owns source snapshots");
        if (pump != null) { pump.close(); pump = null; }
        if (root != EGL14.EGL_NO_CONTEXT) {
            require(EGL14.eglDestroyContext(display, root), "Motion root destroy");
            root = EGL14.EGL_NO_CONTEXT;
        }
        if (display != EGL14.EGL_NO_DISPLAY) {
            CameraEglDisplay.release(display);
            display = EGL14.EGL_NO_DISPLAY;
        }
    }
    private static void require(boolean value, String message) {
        if (!value) throw new IllegalStateException(message);
    }
}
