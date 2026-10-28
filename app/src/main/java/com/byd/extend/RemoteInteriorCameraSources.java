package com.byd.extend;

import android.content.Context;
import android.hardware.Camera;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;

import com.byd.extend.remote.CameraViewingDiagnostics;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;

/** Shell-process-owned clients for the two interior camera channels. */
final class RemoteInteriorCameraSources implements AutoCloseable {
    private static final String TAG = "RemoteInteriorCamera";
    private static final long CAMERA_CALL_TIMEOUT_MS = 8_000L;
    private final Slot[] slots = {new Slot(), new Slot()};
    private final BiConsumer<Integer, String> failureListener;
    private final String sourceSession;

    static final class Specs {
        final int attemptedMask;
        final int availableMask;
        final int[] widths;
        final int[] heights;
        final String[] failureReferences;

        Specs(int attemptedMask, int availableMask, int[] widths, int[] heights, String[] references) {
            this.attemptedMask = attemptedMask;
            this.availableMask = availableMask;
            this.widths = widths;
            this.heights = heights;
            this.failureReferences = references;
        }
    }

    RemoteInteriorCameraSources(String sourceSession, BiConsumer<Integer, String> failureListener) {
        this.sourceSession = sourceSession;
        this.failureListener = failureListener;
    }

    Specs prepare(int requestedMask) {
        RemoteCameraView.validateMask(requestedMask);
        int openMask = 0;
        for (int index = 0; index < slots.length; index++) {
            int bit = 1 << index;
            if ((requestedMask & bit) == 0) continue;
            synchronized (slots[index]) {
                if (!slots[index].attempted) {
                    slots[index].attempted = true;
                    openMask |= bit;
                }
            }
        }
        int available = 0;
        int attempted = 0;
        int[] widths = new int[2], heights = new int[2];
        String[] references = new String[2];
        if (openMask != RemoteCameraView.NONE) {
            Context shell = null;
            try {
                shell = ShellCameraAttribution.initialize();
                if (shell == null) throw new IllegalStateException("shell camera attribution returned null");
            } catch (Throwable failure) {
                for (int index = 0; index < slots.length; index++) {
                    if ((openMask & (1 << index)) == 0) continue;
                    references[index] = recordFailure(index, "identity", failure);
                    synchronized (slots[index]) {
                        slots[index].failureReference = references[index];
                    }
                }
            }
            for (int index = 0; shell != null && index < slots.length; index++) {
                if ((openMask & (1 << index)) == 0) continue;
                final int channel = index;
                Slot slot = slots[index];
                InteriorCameraClient client = null;
                try {
                    int id = resolveId(index == 0 ? "ims" : "dms");
                    client = index == 0 ? new ImsClient(id, failure -> fail(channel, failure))
                            : new DmsClient(id, failure -> fail(channel, failure));
                    synchronized (slot) { slot.client = client; }
                    client.open(shell);
                    synchronized (slot) {
                        if (slot.client == client) {
                            slot.width = client.width;
                            slot.height = client.height;
                        }
                    }
                } catch (Throwable failure) {
                    String reference = UUID.randomUUID().toString().replace("-", "");
                    synchronized (slot) {
                        if (slot.failureReference == null) slot.failureReference = reference;
                    }
                    if (client != null) {
                        try {
                            client.close();
                            synchronized (slot) {
                                if (slot.client == client) slot.client = null;
                                if (slot.client == null) {
                                    slot.surface = null;
                                    slot.started = false;
                                    slot.inputGeneration = 0;
                                }
                            }
                        } catch (Throwable cleanup) {
                            if (cleanup != failure) failure.addSuppressed(cleanup);
                            CameraViewingDiagnostics.failure(
                                    "interior_open_cleanup_" + channelName(index) + "_" + reference,
                                    "shared", cleanup);
                        }
                    }
                    recordFailure(index, "open", reference, failure);
                }
            }
        }
        for (int index = 0; index < slots.length; index++) synchronized (slots[index]) {
            Slot slot = slots[index];
            if (slot.attempted) attempted |= 1 << index;
            if (slot.client != null && slot.failureReference == null) {
                available |= 1 << index;
                widths[index] = slot.width;
                heights[index] = slot.height;
            } else references[index] = slot.failureReference;
        }
        return new Specs(attempted, available, widths, heights, references);
    }

    void retain(int allowedMask) throws Exception {
        RemoteCameraView.validateMask(allowedMask);
        Throwable first = null;
        for (int index = 0; index < slots.length; index++) {
            if ((allowedMask & (1 << index)) != 0) continue;
            try { closeSlot(index); }
            catch (Throwable failure) { first = append(first, failure); continue; }
            Slot slot = slots[index];
            synchronized (slot) {
                slot.attempted = false; slot.failureReference = null;
                slot.width = slot.height = 0;
            }
        }
        rethrow(first, "interior camera release failed");
    }

    void attachInputs(Surface[] surfaces, int startMask, long[] inputGenerations) {
        if (surfaces == null || surfaces.length != 2)
            throw new IllegalArgumentException("two live interior input surfaces required");
        if (inputGenerations == null || inputGenerations.length != 2)
            throw new IllegalArgumentException("two live interior input generations required");
        RemoteCameraView.validateMask(startMask);
        for (int index = 0; index < slots.length; index++) {
            Surface surface = surfaces[index];
            if ((startMask & (1 << index)) == 0) {
                if (surface != null) try { surface.release(); }
                catch (Throwable failure) {
                    CameraViewingDiagnostics.failure("interior_unused_input_release_" + channelName(index),
                            "shared", failure);
                }
                continue;
            }
            Slot slot = slots[index];
            InteriorCameraClient client;
            long generation = inputGenerations[index];
            boolean alreadyAttached;
            synchronized (slot) {
                client = slot.failureReference == null ? slot.client : null;
                alreadyAttached = client != null && slot.started && slot.inputGeneration == generation;
            }
            if (client == null) {
                if (surface != null) try { surface.release(); }
                catch (Throwable failure) {
                    CameraViewingDiagnostics.failure("interior_unowned_input_release_" + channelName(index),
                            "shared", failure);
                }
                continue;
            }
            if (surface == null || !surface.isValid() || generation <= 0) {
                IllegalStateException failure = new IllegalStateException(
                        "interior GPU input Surface unavailable for generation=" + generation);
                if (surface != null) try { surface.release(); }
                catch (Throwable cleanup) { if (cleanup != failure) failure.addSuppressed(cleanup); }
                fail(index, failure);
                continue;
            }
            if (alreadyAttached) {
                try { surface.release(); }
                catch (Throwable failure) {
                    CameraViewingDiagnostics.failure("interior_duplicate_input_release_" + channelName(index),
                            "shared", failure);
                }
                CameraViewingDiagnostics.detail("interior_surface_retained", "shared",
                        "camera=" + channelName(index) + " generation=" + generation);
                continue;
            }
            try {
                CameraViewingDiagnostics.detail("interior_surface_received", "shared",
                        "camera=" + channelName(index) + " valid=true generation=" + generation);
                client.start(surface);
                synchronized (slot) {
                    if (slot.client == client) {
                        slot.started = true; slot.surface = surface; slot.inputGeneration = generation;
                    }
                }
            } catch (Throwable failure) {
                if (!client.ownsSurface(surface)) {
                    try { surface.release(); } catch (Throwable cleanup) { failure.addSuppressed(cleanup); }
                }
                fail(index, failure);
            }
        }
        // A channel whose own close previously failed remains unavailable and retains its handle.
        // Retry that cleanup after starting the independent available channel(s).
        for (int index = 0; index < slots.length; index++) {
            if ((startMask & (1 << index)) != 0) continue;
            Slot slot = slots[index];
            boolean retryClose;
            String reference;
            synchronized (slot) {
                retryClose = slot.client != null && slot.failureReference != null;
                reference = slot.failureReference;
            }
            if (!retryClose) continue;
            try { closeSlot(index); }
            catch (Throwable cleanup) {
                CameraViewingDiagnostics.failure("interior_unavailable_cleanup_"
                        + channelName(index) + "_" + reference, "shared", cleanup);
            }
        }
    }

    public void close() throws Exception {
        Throwable first = null;
        for (int index = 0; index < slots.length; index++) {
            boolean closed = false;
            try { closeSlot(index); closed = true; }
            catch (Throwable failure) { first = append(first, failure); }
            Slot slot = slots[index];
            if (closed) synchronized (slot) {
                slot.attempted = false; slot.failureReference = null;
                slot.width = slot.height = 0;
            }
        }
        rethrow(first, "interior camera close failed");
    }

    private void closeSlot(int index) throws Exception {
        Slot slot = slots[index];
        InteriorCameraClient client;
        Surface surface;
        synchronized (slot) { client = slot.client; surface = slot.surface; }
        if (client != null) client.close();
        else if (surface != null) surface.release();
        synchronized (slot) {
            if (client != null && slot.client == client) {
                slot.client = null; slot.surface = null; slot.started = false; slot.inputGeneration = 0;
            } else if (client == null && slot.client == null && slot.surface == surface) {
                slot.surface = null; slot.started = false; slot.inputGeneration = 0;
            }
        }
    }

    private void fail(int index, Throwable failure) {
        Slot slot = slots[index];
        String operation;
        String reference = UUID.randomUUID().toString().replace("-", "");
        InteriorCameraClient client;
        Surface surface;
        synchronized (slot) {
            if (slot.failureReference != null) return;
            operation = slot.started ? "stream" : "preview";
            slot.failureReference = reference;
            client = slot.client; surface = slot.surface;
        }
        try {
            if (client != null) client.close();
            else if (surface != null) surface.release();
            synchronized (slot) {
                if (client != null && slot.client == client) {
                    slot.client = null; slot.surface = null; slot.started = false; slot.inputGeneration = 0;
                } else if (client == null && slot.client == null && slot.surface == surface) {
                    slot.surface = null; slot.started = false; slot.inputGeneration = 0;
                }
            }
        } catch (Throwable cleanup) {
            if (cleanup != failure) failure.addSuppressed(cleanup);
        }
        recordFailure(index, operation, reference, failure);
        if (failureListener != null) try { failureListener.accept(index + 4, reference); }
        catch (Throwable callbackFailure) {
            CameraViewingDiagnostics.failure("interior_failure_callback_" + reference, "shared", callbackFailure);
        }
    }

    private String recordFailure(int index, String operation, Throwable failure) {
        String reference = UUID.randomUUID().toString().replace("-", "");
        recordFailure(index, operation, reference, failure);
        return reference;
    }

    private void recordFailure(int index, String operation, String reference, Throwable failure) {
        String channel = channelName(index);
        CameraViewingDiagnostics.detail("interior_" + operation + "_" + channel + "_" + reference,
                "shared", "source=" + sourceSession + " channel=" + channel + " ref=" + reference
                        + "\n" + CameraViewingDiagnostics.describe(failure));
        Log.w(TAG, "camera=" + channel + " operation=" + operation + " ref=" + reference, failure);
    }

    private static String channelName(int index) { return index == 0 ? "cabin" : "driver"; }

    private static int resolveId(String tag) throws Exception {
        Throwable managerFailure = null;
        try {
            int id = (Integer) Class.forName("android.hardware.BmmCameraInfo")
                    .getMethod("getCameraId", String.class).invoke(null, tag);
            if (id >= 0) {
                CameraViewingDiagnostics.detail("interior_camera_mapping", "shared",
                        "tag=" + tag + " id=" + id + " source=BmmCameraInfo");
                return id;
            }
        } catch (Throwable failure) { managerFailure = failure; }
        String sort;
        try {
            sort = (String) Class.forName("android.os.SystemProperties")
                    .getMethod("get", String.class, String.class)
                    .invoke(null, "vehicle.config.cam_sort", "");
        } catch (Throwable fallbackFailure) {
            if (managerFailure != null) fallbackFailure.addSuppressed(managerFailure);
            if (fallbackFailure instanceof Exception) throw (Exception) fallbackFailure;
            if (fallbackFailure instanceof Error) throw (Error) fallbackFailure;
            throw new IllegalStateException("OEM camera mapping fallback failed", fallbackFailure);
        }
        for (String item : sort.split(";")) {
            String[] pair = item.split(":");
            if (pair.length != 2 || !tag.equals(pair[0].trim())) continue;
            try {
                int id = Integer.parseInt(pair[1].trim());
                if (id < 0) break;
                CameraViewingDiagnostics.detail("interior_camera_mapping", "shared",
                        "tag=" + tag + " id=" + id + " source=vehicle.config.cam_sort");
                return id;
            } catch (NumberFormatException failure) {
                if (managerFailure != null) failure.addSuppressed(managerFailure);
                throw new IllegalStateException("invalid OEM camera mapping for " + tag, failure);
            }
        }
        IllegalStateException failure = new IllegalStateException("OEM camera id not found for " + tag);
        if (managerFailure != null) failure.addSuppressed(managerFailure);
        throw failure;
    }

    void failedByComposer(int logicalChannel, String reference) {
        int index = logicalChannel - 4;
        if (index < 0 || index > 1) return;
        Slot slot = slots[index];
        InteriorCameraClient client;
        Surface surface;
        synchronized (slot) {
            if (slot.failureReference != null) return;
            slot.failureReference = reference;
            client = slot.client; surface = slot.surface;
        }
        try {
            if (client != null) client.close();
            else if (surface != null) surface.release();
            synchronized (slot) {
                if (client != null && slot.client == client) {
                    slot.client = null; slot.surface = null; slot.started = false; slot.inputGeneration = 0;
                } else if (client == null && slot.client == null && slot.surface == surface) {
                    slot.surface = null; slot.started = false; slot.inputGeneration = 0;
                }
            }
        } catch (Throwable cleanup) {
            CameraViewingDiagnostics.failure("interior_gpu_cleanup_" + reference, "shared", cleanup);
        }
        CameraViewingDiagnostics.detail("interior_gpu_source_closed", "shared",
                "channel=" + (index == 0 ? "cabin" : "driver") + " ref=" + reference);
    }

    private static Throwable append(Throwable primary, Throwable secondary) {
        if (primary == null) return secondary;
        primary.addSuppressed(secondary);
        return primary;
    }

    private static void rethrow(Throwable failure, String operation) throws Exception {
        if (failure == null) return;
        if (failure instanceof Exception) throw (Exception) failure;
        if (failure instanceof Error) throw (Error) failure;
        throw new IllegalStateException(operation, failure);
    }

    private static final class Slot {
        volatile InteriorCameraClient client;
        volatile int width, height;
        volatile boolean attempted, started;
        volatile String failureReference;
        volatile Surface surface;
        volatile long inputGeneration;
    }

    private abstract static class InteriorCameraClient {
        final HandlerThread thread;
        final Handler handler;
        final int id;
        volatile int width, height;
        volatile boolean started;
        private final java.util.ArrayList<Surface> ownedSurfaces = new java.util.ArrayList<>();
        Surface surface;

        InteriorCameraClient(String name, int id) {
            this.id = id;
            thread = new HandlerThread("viewing-" + name);
            thread.start();
            Looper looper = thread.getLooper();
            if (looper == null) {
                thread.quitSafely();
                throw new IllegalStateException("camera Looper unavailable: " + name);
            }
            handler = new Handler(looper);
        }

        final void open(Context shell) throws Exception { call(() -> { openOnThread(shell); return null; }); }
        final void start(Surface next) throws Exception { call(() -> { startOnThread(next); return null; }); }
        final void close() throws Exception {
            call(() -> { closeOnThread(); return null; });
            thread.quitSafely();
        }

        abstract void openOnThread(Context shell) throws Exception;
        abstract void startOnThread(Surface next) throws Exception;
        abstract void suspendOnThread() throws Exception;
        abstract void closeOnThread() throws Exception;

        private <T> T call(Callable<T> action) throws Exception {
            FutureTask<T> task = new FutureTask<>(action);
            if (Looper.myLooper() == thread.getLooper()) task.run();
            else if (!handler.post(task)) throw new IllegalStateException("camera thread stopped");
            try { return task.get(CAMERA_CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS); }
            catch (TimeoutException pending) {
                throw new IllegalStateException("camera call remains pending after "
                        + CAMERA_CALL_TIMEOUT_MS + "ms", pending);
            } catch (ExecutionException execution) {
                Throwable cause = execution.getCause() == null ? execution : execution.getCause();
                if (cause instanceof Exception) throw (Exception) cause;
                if (cause instanceof Error) throw (Error) cause;
                throw new IllegalStateException("camera call failed", cause);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw interrupted;
            }
        }

        final void replaceSurface(Surface next) {
            Surface previous = surface;
            surface = next;
            retainSurface(next);
            if (previous != null && previous != next) releaseTrackedSurface(previous);
        }

        final void retainSurface(Surface value) {
            if (value == null) return;
            synchronized (ownedSurfaces) {
                for (Surface current : ownedSurfaces) if (current == value) return;
                ownedSurfaces.add(value);
            }
        }

        final boolean ownsSurface(Surface value) {
            synchronized (ownedSurfaces) {
                for (Surface current : ownedSurfaces) if (current == value) return true;
            }
            return false;
        }

        final Throwable releaseTrackedSurfaces(Throwable primary) {
            java.util.ArrayList<Surface> copy;
            synchronized (ownedSurfaces) { copy = new java.util.ArrayList<>(ownedSurfaces); }
            for (Surface owned : copy) {
                try { releaseTrackedSurface(owned); }
                catch (Throwable failure) { primary = append(primary, failure); }
            }
            return primary;
        }

        private void releaseTrackedSurface(Surface value) {
            try { value.release(); }
            catch (Throwable failure) {
                throw new IllegalStateException("interior Surface release failed", failure);
            }
            synchronized (ownedSurfaces) {
                for (int index = ownedSurfaces.size() - 1; index >= 0; index--)
                    if (ownedSurfaces.get(index) == value) ownedSurfaces.remove(index);
            }
            if (surface == value) surface = null;
        }
    }

    private static final class ImsClient extends InteriorCameraClient {
        private Camera camera;
        private Method setPreviewSurface;
        private final java.util.function.Consumer<Throwable> failed;

        ImsClient(int id, java.util.function.Consumer<Throwable> failed) {
            super("cabin", id); this.failed = failed;
        }

        @Override void openOnThread(Context shell) throws Exception {
            CameraViewingDiagnostics.detail("interior_camera_permission", "shared",
                    "camera=cabin uid=" + android.os.Process.myUid() + " package=" + shell.getOpPackageName()
                            + " granted=" + (shell.checkSelfPermission("android.permission.CAMERA")
                            == android.content.pm.PackageManager.PERMISSION_GRANTED));
            camera = Camera.open(id);
            if (camera == null) throw new IllegalStateException("Camera.open returned null for IMS id=" + id);
            Camera.Size preview = camera.getParameters().getPreviewSize();
            if (preview == null || preview.width < 2 || preview.height < 2)
                throw new IllegalStateException("Camera1 returned invalid IMS preview dimensions");
            width = preview.width; height = preview.height;
            try {
                setPreviewSurface = Camera.class.getMethod("setPreviewSurface", Surface.class);
                CameraViewingDiagnostics.detail("interior_camera_surface_api", "shared",
                        "camera=cabin method=Camera.setPreviewSurface(Surface) available=true");
            } catch (NoSuchMethodException missing) {
                CameraViewingDiagnostics.failure("interior_camera_surface_api_cabin", "shared", missing);
                throw missing;
            }
            camera.setErrorCallback((code, ignored) -> failed.accept(
                    new IllegalStateException("Camera1 IMS native error code=" + code + " id=" + id)));
            CameraViewingDiagnostics.detail("interior_camera_parameters", "shared",
                    "camera=cabin id=" + id + " api=Camera1 size=" + width + "x" + height);
        }

        @Override void startOnThread(Surface next) throws Exception {
            if (camera == null || next == null || !next.isValid())
                throw new IllegalStateException("Camera1 IMS preview Surface unavailable");
            if (started) camera.stopPreview();
            setPreviewSurface.invoke(camera, next);
            retainSurface(next);
            replaceSurface(next);
            CameraViewingDiagnostics.detail("interior_surface_attached", "shared",
                    "camera=cabin id=" + id + " api=Camera.setPreviewSurface");
            camera.startPreview();
            started = true;
            CameraViewingDiagnostics.detail("interior_preview_started", "shared", "camera=cabin id=" + id);
        }

        @Override void suspendOnThread() {
            if (camera != null && started) camera.stopPreview();
            started = false;
        }

        @Override void closeOnThread() throws Exception {
            Throwable first = null;
            if (camera != null) {
                try { if (started) { camera.stopPreview(); started = false; } }
                catch (Throwable failure) { first = failure; }
                try { camera.setErrorCallback(null); }
                catch (Throwable failure) { first = append(first, failure); }
                try { camera.release(); camera = null; started = false; }
                catch (Throwable failure) { first = append(first, failure); }
            }
            if (camera == null) first = releaseTrackedSurfaces(first);
            rethrow(first, "Camera1 IMS cleanup failed");
        }
    }

    private static final class DmsClient extends InteriorCameraClient {
        private Object camera;
        private Class<?> cameraType;
        private Object eventCallback;
        private Surface attachedSurface;
        private boolean attached;
        private final java.util.function.Consumer<Throwable> failed;

        DmsClient(int id, java.util.function.Consumer<Throwable> failed) {
            super("driver", id); this.failed = failed;
        }

        @Override void openOnThread(Context shell) throws Exception {
            CameraViewingDiagnostics.detail("interior_camera_permission", "shared",
                    "camera=driver uid=" + android.os.Process.myUid() + " package=" + shell.getOpPackageName()
                            + " granted=" + (shell.checkSelfPermission("android.permission.CAMERA")
                            == android.content.pm.PackageManager.PERMISSION_GRANTED));
            cameraType = Class.forName("android.hardware.NormalCamera");
            camera = cameraType.getMethod("open", int.class).invoke(null, id);
            if (camera == null) throw new IllegalStateException("NormalCamera.open returned null for DMS id=" + id);
            width = (Integer) cameraType.getMethod("getPreviewWidth").invoke(camera);
            height = (Integer) cameraType.getMethod("getPreviewHeight").invoke(camera);
            if (width < 2 || height < 2)
                throw new IllegalStateException("NormalCamera returned invalid DMS preview dimensions");
            Class<?> callback = Class.forName("android.hardware.NormalCamera$IEventCallback");
            eventCallback = Proxy.newProxyInstance(callback.getClassLoader(), new Class<?>[]{callback},
                    new DmsEvents(failed, id));
            cameraType.getMethod("setEventCallback", callback).invoke(camera, eventCallback);
            CameraViewingDiagnostics.detail("interior_camera_parameters", "shared",
                    "camera=driver id=" + id + " api=NormalCamera size=" + width + "x" + height);
        }

        @Override void startOnThread(Surface next) throws Exception {
            if (camera == null || next == null || !next.isValid())
                throw new IllegalStateException("NormalCamera DMS preview Surface unavailable");
            if (started) suspendOnThread();
            requireTrue(cameraType.getMethod("addPreviewSurface", Surface.class).invoke(camera, next),
                    "NormalCamera.addPreviewSurface");
            attached = true;
            attachedSurface = next;
            retainSurface(next);
            CameraViewingDiagnostics.detail("interior_surface_attached", "shared",
                    "camera=driver id=" + id + " api=NormalCamera.addPreviewSurface");
            requireTrue(cameraType.getMethod("startPreview").invoke(camera), "NormalCamera.startPreview");
            replaceSurface(next);
            started = true;
            CameraViewingDiagnostics.detail("interior_preview_started", "shared", "camera=driver id=" + id);
        }

        @Override void suspendOnThread() throws Exception {
            Throwable first = null;
            if (started) {
                try {
                    requireTrue(cameraType.getMethod("stopPreview").invoke(camera), "NormalCamera.stopPreview");
                    started = false;
                } catch (Throwable failure) { first = failure; }
            }
            if (camera != null && attachedSurface != null && attached) {
                try {
                    requireTrue(cameraType.getMethod("rmPreviewSurface", Surface.class).invoke(camera, attachedSurface),
                            "NormalCamera.rmPreviewSurface");
                    attached = false;
                    attachedSurface = null;
                } catch (Throwable failure) { first = append(first, failure); }
            }
            rethrow(first, "NormalCamera DMS suspend failed");
        }

        @Override void closeOnThread() throws Exception {
            Throwable first = null;
            try { if (started || attached) suspendOnThread(); }
            catch (Throwable failure) { first = failure; }
            boolean cameraClosed = false;
            if (camera != null) {
                try { cameraType.getMethod("close").invoke(camera); cameraClosed = true; }
                catch (Throwable failure) { first = append(first, failure); }
            }
            if (cameraClosed) {
                camera = null;
                started = false;
                attached = false;
                attachedSurface = null;
            }
            if (camera == null) first = releaseTrackedSurfaces(first);
            rethrow(first, "NormalCamera DMS cleanup failed");
        }
    }

    private static final class DmsEvents implements InvocationHandler {
        private final java.util.function.Consumer<Throwable> failed;
        private final int id;
        DmsEvents(java.util.function.Consumer<Throwable> failed, int id) { this.failed = failed; this.id = id; }

        @Override public Object invoke(Object proxy, Method method, Object[] arguments) {
            if (method.getDeclaringClass() == Object.class) {
                if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                if ("equals".equals(method.getName())) return proxy == arguments[0];
                return "RemoteInteriorDmsEvents";
            }
            boolean nativeFailure = isNativeFailureEvent(method.getName(), arguments);
            if (nativeFailure || !"onEvent".equals(method.getName())) {
                CameraViewingDiagnostics.detail("interior_camera_event", "shared",
                        "camera=driver id=" + id + " callback=" + method.getName()
                                + " arguments=" + Arrays.toString(arguments));
            } else {
                CameraViewingDiagnostics.verbose("interior_camera_event", "shared",
                        () -> "camera=driver id=" + id + " callback=" + method.getName()
                                + " arguments=" + Arrays.toString(arguments));
            }
            if (nativeFailure)
                failed.accept(new IllegalStateException("NormalCamera DMS native event type=1008 id=" + id));
            return null;
        }
    }

    static boolean isNativeFailureEvent(String callback, Object[] arguments) {
        return "onEvent".equals(callback) && arguments != null && arguments.length > 1
                && arguments[1] instanceof Number && ((Number) arguments[1]).intValue() == 1008;
    }

    private static void requireTrue(Object result, String operation) {
        if (!(result instanceof Boolean) || !((Boolean) result))
            throw new IllegalStateException(operation + " returned " + result);
    }
}
