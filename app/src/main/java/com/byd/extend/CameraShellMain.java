package com.byd.extend;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.view.Surface;

import org.json.JSONObject;

import java.io.RandomAccessFile;
import java.lang.reflect.Method;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.FutureTask;

public final class CameraShellMain {
    private static final long LOG_FLUSH_DELAY_MS = 250;

    private CameraShellMain() {}

    static Map<String, Object> eventFieldMap(Object... fields) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (int i = 0; i + 1 < fields.length; i += 2) {
            values.put(String.valueOf(fields[i]), fields[i + 1]);
        }
        return values;
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException(
                "usage: CameraShellMain <appUid> <versionCode>");
        int appUid = Integer.parseInt(args[0]);
        int versionCode = Integer.parseInt(args[1]);
        OwnerLock owner = OwnerLock.acquire();
        if (owner == null) return;
        prepareMainLooperForShell();
        Context context = systemContext();
        initializeSystemFontsForShell();
        Handler handler = new Handler(Looper.getMainLooper());
        ShellBinder binder = new ShellBinder(context, handler, appUid, versionCode);
        binder.attachInterface(null, CameraShellProtocol.DESCRIPTOR);
        try {
            Class<?> serviceManager = Class.forName("android.os.ServiceManager");
            Method addService = serviceManager.getMethod("addService", String.class, IBinder.class);
            addService.invoke(null, CameraShellProtocol.SERVICE_NAME, binder);
            System.out.println("READY pid=" + Process.myPid()
                    + " protocol=" + CameraShellProtocol.VERSION
                    + " build=" + versionCode);
            DiagnosticLogPolicy.flushOutput();
            Looper.loop();
        } finally {
            try {
                binder.closeAll("process_exit");
            } finally {
                DiagnosticLogPolicy.flushOutput();
                owner.close();
            }
        }
    }

    static final class ShellBinder extends Binder {
        private final Handler handler;
        private final int appUid;
        private final int versionCode;
        private final ShellCameraOverlay[] overlays =
                new ShellCameraOverlay[CameraOverlayProfile.COUNT];
        private final ShellReverseCameraOverlay[] reverseOverlays =
                new ShellReverseCameraOverlay[2];
        private final Runnable processTerminator;
        private final DisplayManager displayManager;
        private final DisplayManager.DisplayListener displayListener =
                new DisplayManager.DisplayListener() {
                    @Override public void onDisplayAdded(int displayId) {
                        clusterDisplayChanged(displayId, false);
                    }
                    @Override public void onDisplayChanged(int displayId) {
                        clusterDisplayChanged(displayId, false);
                    }
                    @Override public void onDisplayRemoved(int displayId) {
                        clusterDisplayChanged(displayId, true);
                    }
                };
        private IBinder callback;
        private boolean stdoutFlushScheduled;
        private boolean processTerminationRequested;
        private boolean reversePrepareInProgress;
        private int preparingReverseRequestId;
        private boolean preparingReverseClusterExpected;
        private int deferredReverseSurfaceReadyRequestId;

        ShellBinder(Context context, Handler handler, int appUid, int versionCode) {
            this(context, handler, appUid, versionCode, CameraShellMain::terminateProcess);
        }

        ShellBinder(
                Context context, Handler handler, int appUid, int versionCode,
                Runnable processTerminator) {
            if (processTerminator == null) {
                throw new IllegalArgumentException("process terminator is null");
            }
            this.handler = handler;
            this.appUid = appUid;
            this.versionCode = versionCode;
            this.processTerminator = processTerminator;
            for (CameraOverlayProfile profile : CameraOverlayProfile.values()) {
                overlays[profile.id] = new ShellCameraOverlay(context, profile.id, this::emit);
            }
            reverseOverlays[CameraDisplayTarget.TABLET] = new ShellReverseCameraOverlay(
                    context, CameraDisplayTarget.TABLET, this::emit);
            reverseOverlays[CameraDisplayTarget.CLUSTER] = new ShellReverseCameraOverlay(
                    context, CameraDisplayTarget.CLUSTER, this::emit);
            displayManager = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
            if (displayManager != null) displayManager.registerDisplayListener(displayListener, handler);
        }

        private void clusterDisplayChanged(int displayId, boolean removed) {
            for (ShellCameraOverlay overlay : overlays) {
                overlay.onClusterDisplayChanged(displayId, removed);
            }
            reverseOverlays[CameraDisplayTarget.CLUSTER]
                    .onClusterDisplayChanged(displayId, removed);
        }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            if (!CameraShellProtocol.isCallerAllowed(Binder.getCallingUid(), appUid)) {
                if (reply != null) reply.writeException(new SecurityException("caller uid denied"));
                return true;
            }
            try {
                data.enforceInterface(CameraShellProtocol.DESCRIPTOR);
                if (code == CameraShellProtocol.TX_CONFIGURE_LOGGING) {
                    int enabled = data.readInt();
                    if (enabled != 0 && enabled != 1) throw new IllegalArgumentException("invalid logging flag");
                    DiagnosticLogPolicy.configure(enabled == 1);
                    reply.writeNoException();
                    return true;
                }
                if (code == CameraShellProtocol.TX_PING) {
                    reply.writeNoException();
                    reply.writeInt(CameraShellProtocol.VERSION);
                    reply.writeInt(versionCode);
                    reply.writeInt(Process.myPid());
                    reply.writeInt(CameraShellProtocol.CAP_REVERSE_TOGGLE_MODE);
                    return true;
                }
                if (code == CameraShellProtocol.TX_REGISTER_CALLBACK) {
                    registerCallback(data.readStrongBinder());
                    reply.writeNoException();
                    return true;
                }
                if (code == CameraShellProtocol.TX_OPEN) {
                    throw new IllegalStateException("stock AVM is isolated in bydextend_avm");
                }
                if (code == CameraShellProtocol.TX_CLOSE) {
                    throw new IllegalStateException("stock AVM is isolated in bydextend_avm");
                }
                if (code == CameraShellProtocol.TX_SHUTDOWN) {
                    try {
                        runOnMain(() -> {
                            closeAll("controller_shutdown");
                            emit("camera_shell_shutdown", "reason", "controller_request");
                            return null;
                        });
                        reply.writeNoException();
                    } finally {
                        queueProcessTermination();
                    }
                    return true;
                }
                if (code == CameraShellProtocol.TX_OVERLAY_PREPARE) {
                    CameraShellProtocol.OverlaySpec spec =
                            CameraShellProtocol.OverlaySpec.readFromParcel(data);
                    if (isReverseOverlayOpen()
                            && !overlayAllowedWhileReverseActive(spec.cameraId)) {
                        throw new IllegalStateException("reverse overlay has camera priority");
                    }
                    int prepareResult;
                    try {
                        runOnMain(() -> {
                            overlay(spec.cameraId).prepare(spec);
                            return null;
                        });
                        prepareResult = CameraShellProtocol.PREPARE_OK;
                    } catch (CameraShellProtocol.PrepareRestartRequired restart) {
                        prepareResult = CameraShellProtocol.PREPARE_RESTART_REQUIRED;
                        emit("camera_shell_prepare_restart_required",
                                "camera_id", spec.cameraId,
                                "request_id", spec.requestId,
                                "reason", restart.reason);
                    }
                    reply.writeNoException();
                    reply.writeInt(prepareResult);
                    return true;
                }
                if (code == CameraShellProtocol.TX_OVERLAY_ACQUIRE_SURFACE) {
                    int cameraId = data.readInt();
                    int requestId = data.readInt();
                    ShellCameraOverlay.SurfaceSnapshot snapshot = runOnMain(
                            () -> overlay(cameraId).acquireSurface(requestId));
                    reply.writeNoException();
                    reply.writeInt(cameraId);
                    reply.writeInt(snapshot.requestId);
                    reply.writeInt(snapshot.surfaceGeneration);
                    snapshot.surface.writeToParcel(reply, 0);
                    return true;
                }
                if (code == CameraShellProtocol.TX_OVERLAY_ARM_FRAME) {
                    OverlayFrameArm arm = OverlayFrameArm.fromWireFields(
                            data.readInt(), data.readInt(), data.readInt(), data.readInt());
                    runOnMain(() -> {
                        overlay(arm.cameraId).armFirstFrame(arm);
                        return null;
                    });
                    reply.writeNoException();
                    return true;
                }
                if (code == CameraShellProtocol.TX_OVERLAY_SET_VISIBLE) {
                    int cameraId = data.readInt();
                    int requestId = data.readInt();
                    int surfaceGeneration = data.readInt();
                    boolean visible = data.readInt() != 0;
                    runOnMain(() -> {
                        overlay(cameraId).setVisible(requestId, surfaceGeneration, visible);
                        return null;
                    });
                    reply.writeNoException();
                    return true;
                }
                if (code == CameraShellProtocol.TX_OVERLAY_CLOSE) {
                    int cameraId = data.readInt();
                    String reason = data.readString();
                    runOnMain(() -> {
                        overlay(cameraId).close(reason);
                        return null;
                    });
                    reply.writeNoException();
                    return true;
                }
                if (code == CameraShellProtocol.TX_OVERLAY_SET_WARNING) {
                    int cameraId = data.readInt();
                    int requestId = data.readInt();
                    int surfaceGeneration = data.readInt();
                    int edge = data.readInt();
                    int mode = data.readInt();
                    CameraShellProtocol.validateWarning(
                            cameraId, requestId, surfaceGeneration, edge, mode);
                    runOnMain(() -> {
                        overlay(cameraId).setWarning(requestId, surfaceGeneration, edge, mode);
                        return null;
                    });
                    reply.writeNoException();
                    return true;
                }
                if (code == CameraShellProtocol.TX_REVERSE_PREPARE) {
                    CameraShellProtocol.ReverseOverlaySpec spec =
                            CameraShellProtocol.ReverseOverlaySpec.readFromParcel(data);
                    int prepareResult;
                    try {
                        runOnMain(() -> {
                            deferredReverseSurfaceReadyRequestId = 0;
                            reversePrepareInProgress = true;
                            preparingReverseRequestId = spec.requestId;
                            preparingReverseClusterExpected =
                                    spec.layout.containsTarget(CameraDisplayTarget.CLUSTER);
                            try {
                                closeOverlays("reverse_priority", CameraOverlayProfile.BLIND_COUNT);
                                reverseOverlay(CameraDisplayTarget.TABLET).prepare(spec);
                                ShellReverseCameraOverlay cluster =
                                        reverseOverlay(CameraDisplayTarget.CLUSTER);
                                if (spec.layout.containsTarget(CameraDisplayTarget.CLUSTER)) {
                                    try {
                                        cluster.prepare(spec);
                                    } catch (Throwable clusterError) {
                                        cluster.close("cluster_prepare_unavailable");
                                        if (clusterError instanceof CameraShellProtocol.PrepareRestartRequired) {
                                            throw (CameraShellProtocol.PrepareRestartRequired) clusterError;
                                        }
                                        emit("reverse_overlay_display_unavailable",
                                                "request_id", spec.requestId,
                                                "display_target", CameraDisplayTarget.CLUSTER,
                                                "error", summary(clusterError));
                                    }
                                } else {
                                    cluster.close("composition_empty");
                                }
                                return null;
                            } finally {
                                reversePrepareInProgress = false;
                                preparingReverseRequestId = 0;
                                preparingReverseClusterExpected = false;
                            }
                        });
                        prepareResult = CameraShellProtocol.PREPARE_OK;
                        releaseDeferredReverseTabletReady(spec.requestId);
                    } catch (CameraShellProtocol.PrepareRestartRequired restart) {
                        prepareResult = CameraShellProtocol.PREPARE_RESTART_REQUIRED;
                        Object[] fields = new Object[4 + restart.diagnosticFields.length];
                        fields[0] = "request_id";
                        fields[1] = spec.requestId;
                        fields[2] = "reason";
                        fields[3] = restart.reason;
                        System.arraycopy(
                                restart.diagnosticFields, 0, fields, 4,
                                restart.diagnosticFields.length);
                        emit("camera_shell_reverse_prepare_restart_required", fields);
                    }
                    reply.writeNoException();
                    reply.writeInt(prepareResult);
                    return true;
                }
                if (code == CameraShellProtocol.TX_REVERSE_ACQUIRE_SURFACES) {
                    int requestId = data.readInt();
                    boolean forceTabletFallback = data.readInt() != 0;
                    ReverseSurfaceBundle snapshot = runOnMain(
                            () -> acquireReverseSurfaceBundle(requestId, forceTabletFallback));
                    reply.writeNoException();
                    reply.writeInt(snapshot.requestId);
                    reply.writeInt(snapshot.preparedDisplayTargets.length);
                    int offset = 0;
                    for (int displayTarget : snapshot.preparedDisplayTargets) {
                        int count = 0;
                        while (offset + count < snapshot.displayTargets.length
                                && snapshot.displayTargets[offset + count] == displayTarget) {
                            count++;
                        }
                        reply.writeInt(displayTarget);
                        reply.writeInt(count);
                        for (int index = offset; index < offset + count; index++) {
                            reply.writeInt(snapshot.sourceIndexes[index]);
                            reply.writeInt(snapshot.generations[index]);
                        }
                        for (int index = offset; index < offset + count; index++) {
                            snapshot.surfaces[index].writeToParcel(reply, 0);
                        }
                        offset += count;
                    }
                    return true;
                }
                if (code == CameraShellProtocol.TX_REVERSE_ARM_FRAMES) {
                    int requestId = data.readInt();
                    int displayTarget = readReverseDisplayTarget(data);
                    int[] generations = readReverseGenerations(data);
                    runOnMain(() -> {
                        reverseOverlay(displayTarget).armFrames(requestId, generations);
                        return null;
                    });
                    reply.writeNoException();
                    return true;
                }
                if (code == CameraShellProtocol.TX_REVERSE_SET_VISIBLE) {
                    int requestId = data.readInt();
                    int displayTarget = readReverseDisplayTarget(data);
                    int[] generations = readReverseGenerations(data);
                    boolean visible = data.readInt() != 0;
                    runOnMain(() -> {
                        reverseOverlay(displayTarget).setVisible(
                                requestId, generations, visible);
                        return null;
                    });
                    reply.writeNoException();
                    return true;
                }
                if (code == CameraShellProtocol.TX_REVERSE_UPDATE_VISIBILITY) {
                    int requestId = data.readInt();
                    int displayTarget = readReverseDisplayTarget(data);
                    int[] generations = readReverseGenerations(data);
                    int visibilityMask = data.readInt();
                    boolean widgetVisible = data.readInt() != 0;
                    runOnMain(() -> {
                        reverseOverlay(displayTarget).updateVisibility(
                                requestId, generations, visibilityMask, widgetVisible);
                        return null;
                    });
                    reply.writeNoException();
                    return true;
                }
                if (code == CameraShellProtocol.TX_REVERSE_TOGGLE_MODE) {
                    int requestId = data.readInt();
                    if (requestId <= 0) {
                        throw new IllegalArgumentException("invalid reverse request id");
                    }
                    runOnMain(() -> {
                        for (ShellReverseCameraOverlay output : reverseOverlays) {
                            if (output.isOpen()) output.toggleSideMode(requestId);
                        }
                        return null;
                    });
                    reply.writeNoException();
                    return true;
                }
                if (code == CameraShellProtocol.TX_REVERSE_SET_MODE) {
                    int requestId = data.readInt();
                    int mode = data.readInt();
                    if (requestId <= 0 || (mode != ReverseSideSelectorView.MODE_REAR
                            && mode != ReverseSideSelectorView.MODE_FRONT)) {
                        throw new IllegalArgumentException("invalid reverse direction request");
                    }
                    runOnMain(() -> {
                        for (ShellReverseCameraOverlay output : reverseOverlays) {
                            if (output.isOpen()) output.setSideMode(requestId, mode);
                        }
                        return null;
                    });
                    reply.writeNoException();
                    return true;
                }
                if (code == CameraShellProtocol.TX_REVERSE_STEERING) {
                    int requestId = data.readInt();
                    if (requestId <= 0) throw new IllegalArgumentException("invalid reverse request id");
                    ReverseSteeringSample sample = new ReverseSteeringSample(
                            data.readFloat(), data.readFloat(), data.readFloat(), data.readLong());
                    runOnMain(() -> {
                        for (ShellReverseCameraOverlay output : reverseOverlays) {
                            output.updateSteering(requestId, sample);
                        }
                        return null;
                    });
                    reply.writeNoException();
                    return true;
                }
                if (code == CameraShellProtocol.TX_REVERSE_CLOSE) {
                    String reason = data.readString();
                    runOnMain(() -> {
                        closeReverseOverlays(reason);
                        return null;
                    });
                    reply.writeNoException();
                    return true;
                }
                if (code == CameraShellProtocol.TX_UPDATE_VISUALS) {
                    int cornerRadiusDp = data.readInt();
                    int transparencyPercent = data.readInt();
                    CameraShellProtocol.validateVisualStyle(
                            cornerRadiusDp, transparencyPercent);
                    runOnMain(() -> {
                        Throwable[] failure = new Throwable[1];
                        for (ShellCameraOverlay overlay : overlays) {
                            try {
                                overlay.updateVisuals(cornerRadiusDp, transparencyPercent);
                            } catch (Throwable error) {
                                if (failure[0] == null) failure[0] = error;
                            }
                        }
                        for (ShellReverseCameraOverlay output : reverseOverlays) {
                            try {
                                output.updateVisuals(cornerRadiusDp, transparencyPercent);
                            } catch (Throwable error) {
                                if (failure[0] == null) failure[0] = error;
                            }
                        }
                        if (failure[0] != null) throw new IllegalStateException(
                                summary(failure[0]), failure[0]);
                        return null;
                    });
                    reply.writeNoException();
                    return true;
                }
                return false;
            } catch (Throwable error) {
                if (reply != null) reply.writeException(new IllegalStateException(summary(error)));
                emit("camera_shell_transaction_error", "code", code, "error", summary(error));
                return true;
            }
        }

        private void closeAll(String reason) {
            if (displayManager != null) displayManager.unregisterDisplayListener(displayListener);
            Throwable failure = null;
            try {
                closeOverlays(reason);
            } catch (Throwable error) {
                failure = error;
            }
            for (ShellReverseCameraOverlay output : reverseOverlays) {
                try {
                    output.close(reason);
                } catch (Throwable error) {
                    if (failure == null) failure = error;
                }
            }
            if (failure != null) throw new IllegalStateException(summary(failure), failure);
        }

        private static int[] readReverseGenerations(Parcel data) {
            int count = data.readInt();
            if (count < 0 || count > 4) {
                throw new IllegalArgumentException(
                        "zero to four reverse generations required");
            }
            int[] values = new int[count];
            for (int i = 0; i < count; i++) {
                values[i] = data.readInt();
                if (values[i] <= 0) {
                    throw new IllegalArgumentException("invalid reverse Surface generation");
                }
            }
            return values;
        }

        private static int readReverseDisplayTarget(Parcel data) {
            int target = data.readInt();
            if (!CameraDisplayTarget.isValid(target)) {
                throw new IllegalArgumentException("invalid Reverse display target");
            }
            return target;
        }

        private boolean isReverseOverlayOpen() {
            for (ShellReverseCameraOverlay output : reverseOverlays) {
                if (output.isOpen()) return true;
            }
            return false;
        }

        private ShellReverseCameraOverlay reverseOverlay(int displayTarget) {
            if (!CameraDisplayTarget.isValid(displayTarget)) {
                throw new IllegalArgumentException("invalid Reverse display target");
            }
            return reverseOverlays[displayTarget];
        }

        private void closeReverseOverlays(String reason) {
            Throwable failure = null;
            for (ShellReverseCameraOverlay output : reverseOverlays) {
                try {
                    output.close(reason);
                } catch (Throwable error) {
                    if (failure == null) failure = error;
                }
            }
            if (failure != null) throw new IllegalStateException(summary(failure), failure);
        }

        private ReverseSurfaceBundle acquireReverseSurfaceBundle(
                int requestId, boolean forceTabletFallback) {
            ShellReverseCameraOverlay.SurfaceSnapshot tablet =
                    reverseOverlay(CameraDisplayTarget.TABLET).acquireSurfaces(requestId);
            ShellReverseCameraOverlay.SurfaceSnapshot cluster = null;
            ShellReverseCameraOverlay clusterOutput = reverseOverlay(CameraDisplayTarget.CLUSTER);
            if (clusterOutput.isOpen()) {
                try {
                    if (clusterOutput.surfacesReady()) {
                        cluster = clusterOutput.acquireSurfaces(requestId);
                    } else if (forceTabletFallback) {
                        clusterOutput.close("cluster_surface_timeout");
                        emit("reverse_overlay_display_unavailable",
                                "request_id", requestId,
                                "display_target", CameraDisplayTarget.CLUSTER,
                                "reason", "surface_timeout");
                    } else {
                        emit("reverse_overlay_display_unavailable",
                                "request_id", requestId,
                                "display_target", CameraDisplayTarget.CLUSTER,
                                "reason", "surfaces_not_ready");
                    }
                } catch (Throwable clusterError) {
                    clusterOutput.close("cluster_surface_unavailable");
                    emit("reverse_overlay_display_unavailable",
                            "request_id", requestId,
                            "display_target", CameraDisplayTarget.CLUSTER,
                            "error", summary(clusterError));
                }
            }
            int clusterCount = cluster == null ? 0 : cluster.surfaces.length;
            int count = tablet.surfaces.length + clusterCount;
            int[] preparedDisplayTargets = cluster == null
                    ? new int[]{CameraDisplayTarget.TABLET}
                    : new int[]{CameraDisplayTarget.TABLET, CameraDisplayTarget.CLUSTER};
            int[] targets = new int[count];
            int[] sourceIndexes = new int[count];
            int[] generations = new int[count];
            Surface[] surfaces = new Surface[count];
            appendReverseSurfaces(tablet, targets, sourceIndexes, generations, surfaces, 0);
            if (cluster != null) {
                appendReverseSurfaces(
                        cluster, targets, sourceIndexes, generations, surfaces,
                        tablet.surfaces.length);
            }
            return new ReverseSurfaceBundle(requestId, preparedDisplayTargets, targets, sourceIndexes,
                    generations, surfaces);
        }

        private static void appendReverseSurfaces(
                ShellReverseCameraOverlay.SurfaceSnapshot snapshot,
                int[] targets, int[] sourceIndexes, int[] generations,
                Surface[] surfaces, int offset) {
            for (int index = 0; index < snapshot.surfaces.length; index++) {
                targets[offset + index] = snapshot.displayTarget;
                sourceIndexes[offset + index] = snapshot.sourceIndexes[index];
                generations[offset + index] = snapshot.generations[index];
                surfaces[offset + index] = snapshot.surfaces[index];
            }
        }

        private void synchronizeReverseSideMode(String kind, Object[] fields) {
            if (!"reverse_overlay_selector".equals(kind)) return;
            Map<String, Object> values = eventFieldMap(fields);
            if (values.containsKey("automatic")) return;
            Object targetValue = values.get("display_target");
            Object requestValue = values.get("request_id");
            Object modeValue = values.get("mode");
            if (!(targetValue instanceof Number) || !(requestValue instanceof Number)
                    || !(modeValue instanceof String)) return;
            int origin = ((Number) targetValue).intValue();
            int requestId = ((Number) requestValue).intValue();
            String mode = (String) modeValue;
            int nextMode = "front".equals(mode) ? ReverseSideSelectorView.MODE_FRONT
                    : "rear".equals(mode) ? ReverseSideSelectorView.MODE_REAR : -1;
            if (!CameraDisplayTarget.isValid(origin) || nextMode < 0) return;
            handler.post(() -> {
                int other = origin == CameraDisplayTarget.TABLET
                        ? CameraDisplayTarget.CLUSTER : CameraDisplayTarget.TABLET;
                ShellReverseCameraOverlay output = reverseOverlay(other);
                if (output.isOpenForRequest(requestId)) output.setSideMode(requestId, nextMode);
            });
        }

        private boolean deferReverseSurfaceReady(String kind, Object[] fields) {
            if (!"reverse_overlay_surface".equals(kind)) return false;
            // Acquire after both opened display roots have produced their Surface inputs.
            Map<String, Object> values = eventFieldMap(fields);
            if (!(values.get("request_id") instanceof Number)
                    || !(values.get("display_target") instanceof Number)
                    || !"ready".equals(values.get("state"))) return false;
            int requestId = ((Number) values.get("request_id")).intValue();
            int displayTarget = ((Number) values.get("display_target")).intValue();
            if (!CameraDisplayTarget.isValid(displayTarget)) return true;
            ShellReverseCameraOverlay cluster = reverseOverlay(CameraDisplayTarget.CLUSTER);
            if (displayTarget == CameraDisplayTarget.CLUSTER && !cluster.isOpenForRequest(requestId)) {
                return true;
            }
            ShellReverseCameraOverlay tablet = reverseOverlay(CameraDisplayTarget.TABLET);
            boolean preparing = reversePrepareInProgress
                    && preparingReverseRequestId == requestId;
            boolean clusterExpected = cluster.isOpenForRequest(requestId)
                    || preparing && preparingReverseClusterExpected;
            boolean tabletReady = tablet.isOpenForRequest(requestId) && tablet.surfacesReady();
            boolean clusterReady = !clusterExpected
                    || cluster.isOpenForRequest(requestId) && cluster.surfacesReady();
            if (shouldDeferReverseSurfaceReady(
                    preparing, clusterExpected, tabletReady, clusterReady)) {
                deferredReverseSurfaceReadyRequestId = requestId;
                return true;
            }
            if (deferredReverseSurfaceReadyRequestId == requestId) {
                deferredReverseSurfaceReadyRequestId = 0;
            }
            return false;
        }

        static boolean shouldDeferReverseSurfaceReady(
                boolean preparing, boolean clusterExpected,
                boolean tabletReady, boolean clusterReady) {
            return preparing || clusterExpected && (!tabletReady || !clusterReady);
        }

        private boolean isClusterUnavailableForDeferredReverse(String kind, Object[] fields) {
            if (!"reverse_overlay_display_unavailable".equals(kind)) return false;
            Map<String, Object> values = eventFieldMap(fields);
            return values.get("request_id") instanceof Number
                    && values.get("display_target") instanceof Number
                    && ((Number) values.get("request_id")).intValue()
                            == deferredReverseSurfaceReadyRequestId
                    && ((Number) values.get("display_target")).intValue()
                            == CameraDisplayTarget.CLUSTER;
        }

        private void releaseDeferredReverseTabletReady(int requestId) {
            handler.post(() -> {
                if (deferredReverseSurfaceReadyRequestId != requestId) return;
                ShellReverseCameraOverlay cluster = reverseOverlay(CameraDisplayTarget.CLUSTER);
                ShellReverseCameraOverlay tablet = reverseOverlay(CameraDisplayTarget.TABLET);
                boolean preparing = reversePrepareInProgress
                        && preparingReverseRequestId == requestId;
                boolean clusterExpected = cluster.isOpenForRequest(requestId);
                boolean tabletReady = tablet.isOpenForRequest(requestId) && tablet.surfacesReady();
                boolean clusterReady = !clusterExpected || cluster.surfacesReady();
                if (shouldDeferReverseSurfaceReady(
                        preparing, clusterExpected, tabletReady, clusterReady)) return;
                deferredReverseSurfaceReadyRequestId = 0;
                tablet.notifySurfacesReadyForAcquire(requestId);
            });
        }

        private ShellCameraOverlay overlay(int cameraId) {
            return overlays[CameraOverlayProfile.of(cameraId).id];
        }

        private void closeOverlays(String reason) {
            closeOverlays(reason, overlays.length);
        }

        private void closeOverlays(String reason, int count) {
            Throwable failure = null;
            for (int i = 0; i < count; i++) {
                try {
                    overlays[i].close(reason);
                } catch (Throwable error) {
                    if (failure == null) failure = error;
                }
            }
            if (failure != null) throw new IllegalStateException(summary(failure), failure);
        }

        static boolean overlayAllowedWhileReverseActive(int cameraId) {
            CameraOverlayProfile.of(cameraId);
            return CameraOverlayProfile.isParking(cameraId)
                    || CameraOverlayProfile.isMirror(cameraId);
        }

        private <T> T runOnMain(java.util.concurrent.Callable<T> callable) throws Exception {
            if (Looper.myLooper() == handler.getLooper()) return callable.call();
            FutureTask<T> task = new FutureTask<>(callable);
            if (!handler.post(task)) {
                throw new IllegalStateException("camera main handler rejected task");
            }
            try {
                return task.get(5, TimeUnit.SECONDS);
            } catch (java.util.concurrent.ExecutionException error) {
                Throwable cause = error.getCause();
                if (cause instanceof Exception) throw (Exception) cause;
                if (cause instanceof Error) throw (Error) cause;
                throw error;
            }
        }

        private synchronized void registerCallback(IBinder value) throws RemoteException {
            if (value == null) throw new IllegalArgumentException("callback is null");
            callback = value;
            value.linkToDeath(() -> handler.post(() -> callbackDied(value)), 0);
            emit("camera_shell_callback_registered", "shell_uid", Process.myUid());
        }

        private synchronized void callbackDied(IBinder value) {
            if (callback != value) return;
            callback = null;
            try {
                closeAll("controller_died");
            } finally {
                terminateProcessOnce();
            }
        }

        private void queueProcessTermination() {
            boolean posted = false;
            try {
                posted = handler.post(this::terminateProcessOnce);
            } finally {
                if (!posted) terminateProcessOnce();
            }
        }

        private void terminateProcessOnce() {
            synchronized (this) {
                if (processTerminationRequested) return;
                processTerminationRequested = true;
            }
            processTerminator.run();
        }

        private void emit(String kind, Object... fields) {
            synchronizeReverseSideMode(kind, fields);
            if (deferReverseSurfaceReady(kind, fields)) return;
            boolean releaseDeferredTablet = isClusterUnavailableForDeferredReverse(kind, fields);
            if (releaseDeferredTablet) {
                Object requestValue = eventFieldMap(fields).get("request_id");
                if (requestValue instanceof Number) {
                    releaseDeferredReverseTabletReady(((Number) requestValue).intValue());
                }
            }
            if (!DiagnosticLogPolicy.shouldProduce(kind)) return;
            String line;
            try {
                JSONObject json = new JSONObject();
                json.put("kind", kind);
                json.put("source", "camera_shell_helper");
                json.put("wall_time", new SimpleDateFormat(
                        "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).format(new Date()));
                json.put("t_ms", SystemClock.elapsedRealtime());
                if ("camera_overlay_first_frame".equals(kind)) {
                    for (Map.Entry<String, Object> field : eventFieldMap(fields).entrySet()) {
                        json.put(field.getKey(), field.getValue());
                    }
                } else {
                    for (int i = 0; i + 1 < fields.length; i += 2) {
                        json.put(String.valueOf(fields[i]), fields[i + 1]);
                    }
                }
                line = json.toString();
            } catch (Throwable error) {
                line = "{\"kind\":\"camera_shell_json_error\"}";
            }
            if (DiagnosticLogPolicy.shouldPersist(kind)) {
                DiagnosticLogPolicy.print(line);
                scheduleStdoutFlush();
            }
            IBinder target;
            synchronized (this) {
                target = callback;
            }
            if (target == null) return;
            Parcel parcel = Parcel.obtain();
            try {
                parcel.writeInterfaceToken(CameraShellProtocol.CALLBACK_DESCRIPTOR);
                parcel.writeString(line);
                target.transact(CameraShellProtocol.CB_EVENT,
                        parcel, null, IBinder.FLAG_ONEWAY);
            } catch (Throwable error) {
                handler.post(() -> callbackDied(target));
            } finally {
                parcel.recycle();
            }
        }

        private static final class ReverseSurfaceBundle {
            final int requestId;
            final int[] preparedDisplayTargets;
            final int[] displayTargets;
            final int[] sourceIndexes;
            final int[] generations;
            final Surface[] surfaces;

            ReverseSurfaceBundle(
                    int requestId, int[] preparedDisplayTargets,
                    int[] displayTargets, int[] sourceIndexes,
                    int[] generations, Surface[] surfaces) {
                this.requestId = requestId;
                this.preparedDisplayTargets = preparedDisplayTargets;
                this.displayTargets = displayTargets;
                this.sourceIndexes = sourceIndexes;
                this.generations = generations;
                this.surfaces = surfaces;
            }
        }

        private void scheduleStdoutFlush() {
            synchronized (this) {
                if (stdoutFlushScheduled) return;
                stdoutFlushScheduled = true;
            }
            if (!handler.postDelayed(() -> {
                synchronized (ShellBinder.this) {
                    stdoutFlushScheduled = false;
                }
                DiagnosticLogPolicy.flushOutput();
            }, LOG_FLUSH_DELAY_MS)) {
                synchronized (this) {
                    stdoutFlushScheduled = false;
                }
                DiagnosticLogPolicy.flushOutput();
            }
        }
    }

    private static Context systemContext() throws Exception {
        Class<?> type = Class.forName("android.app.ActivityThread");
        Object thread = type.getMethod("currentActivityThread").invoke(null);
        if (thread == null) thread = type.getMethod("systemMain").invoke(null);
        Context context = (Context) type.getMethod("getSystemContext").invoke(thread);
        if (context == null) throw new IllegalStateException("system context unavailable");
        return context;
    }

    static Looper prepareMainLooperForShell() {
        if (Looper.getMainLooper() == null) Looper.prepareMainLooper();
        return Looper.getMainLooper();
    }

    static void initializeSystemFontsForShell() throws Exception {
        Class<?> typeface = Class.forName("android.graphics.Typeface");
        Object current = typeface.getMethod("getSystemFontMap").invoke(null);
        if (current instanceof Map && !((Map<?, ?>) current).isEmpty()) return;
        typeface.getMethod("loadPreinstalledSystemFontMap").invoke(null);
    }

    private static void terminateProcess() {
        DiagnosticLogPolicy.flushOutput();
        Process.killProcess(Process.myPid());
    }

    private static String summary(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null
                && current instanceof java.lang.reflect.InvocationTargetException) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return current.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    private static final class OwnerLock {
        private final RandomAccessFile file;
        private final FileChannel channel;
        private final FileLock lock;

        private OwnerLock(RandomAccessFile file, FileChannel channel, FileLock lock) {
            this.file = file;
            this.channel = channel;
            this.lock = lock;
        }

        static OwnerLock acquire() throws Exception {
            RandomAccessFile file = new RandomAccessFile(CameraShellProtocol.LOCK_PATH, "rw");
            FileChannel channel = file.getChannel();
            FileLock lock = channel.tryLock();
            if (lock == null) {
                channel.close();
                file.close();
                return null;
            }
            return new OwnerLock(file, channel, lock);
        }

        void close() {
            try { lock.release(); } catch (Throwable ignored) {}
            try { channel.close(); } catch (Throwable ignored) {}
            try { file.close(); } catch (Throwable ignored) {}
        }
    }
}
