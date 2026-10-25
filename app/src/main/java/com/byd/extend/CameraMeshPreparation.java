package com.byd.extend;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;

/** Owns the process-local cache and settings-driven, asynchronous mesh warming. */
final class CameraMeshPreparation {
    private static final String TAG = "CameraMeshPreparation";
    private static final int SHELL_UID = 2000;
    private static final Object LOCK = new Object();
    private static volatile CameraMeshCache cache;

    private CameraMeshPreparation() {}

    static CameraMeshCache cache(Context context) {
        if (context == null) throw new IllegalArgumentException("camera context is required");
        CameraMeshCache current = cache;
        if (current != null) return current;
        synchronized (LOCK) {
            current = cache;
            if (current == null) {
                File directory = cacheDirectory(context);
                current = new CameraMeshCache(directory,
                        (operation, error) -> Log.e(TAG, operation + " path=" + directory, error));
                cache = current;
            }
            return current;
        }
    }

    static void warmSettings(Context context) {
        if (context == null) throw new IllegalArgumentException("camera context is required");
        try {
            SharedPreferences settings = context.getSharedPreferences(
                    "settings", Context.MODE_PRIVATE);
            List<CameraMeshCache.Request> requests = new ArrayList<>();
            collectBlindRequests(context, settings, requests);
            collectParkingRequests(context, settings, requests);
            collectMirrorRequests(context, settings, requests);
            ReverseCameraCompositionView.collectConfiguredMeshRequests(
                    context, settings, requests);

            CameraMeshCache active = cache(context);
            List<CompletableFuture<CameraMeshCache.Prepared>> pending =
                    active.replacePrewarm(requests);
            for (CompletableFuture<CameraMeshCache.Prepared> future : pending) {
                future.whenComplete((prepared, failure) -> {
                    if (failure != null && !isPrewarmSuperseded(failure)) {
                        Log.e(TAG, "camera mesh prewarm failed", failure);
                    }
                });
            }
        } catch (RuntimeException failure) {
            Log.e(TAG, "camera mesh settings prewarm failed", failure);
        }
    }

    static CameraMeshCache.Request request(CameraDewarpConfig config,
            float roiLeft, float roiTop,
            float roiWidth, float roiHeight, int width, int height) {
        if (config == null || width <= 0 || height <= 0) {
            throw new IllegalArgumentException("valid mesh configuration and size are required");
        }
        return config.enabled
                ? new CameraMeshCache.Request(config.withRoiCenter(
                        roiLeft + roiWidth / 2.0f, roiTop + roiHeight / 2.0f), width, height)
                : null;
    }

    private static boolean isPrewarmSuperseded(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof CancellationException) return true;
        }
        return false;
    }

    private static File cacheDirectory(Context context) {
        ApplicationInfo application = context.getApplicationInfo();
        if (application == null || !CameraHelperMain.PACKAGE_NAME.equals(application.packageName)
                || application.uid <= 0) {
            throw new SecurityException("verified camera application identity unavailable");
        }
        int processUid = android.os.Process.myUid();
        if (processUid == application.uid) {
            if (application.dataDir == null || application.dataDir.isEmpty()) {
                throw new IllegalStateException("camera application data directory unavailable");
            }
            return new File(new File(application.dataDir, "cache"), "camera-mesh");
        }
        if (processUid == SHELL_UID) {
            return new File("/data/local/tmp/bydextend-camera-mesh-" + application.uid);
        }
        throw new SecurityException("camera mesh cache requested by unsupported uid " + processUid);
    }

    private static void collectBlindRequests(Context context, SharedPreferences settings,
            List<CameraMeshCache.Request> requests) {
        for (CameraProfile profile : CameraProfile.values()) {
            int target = BlindSpotOverlayController.readTarget(settings, profile);
            DisplayMetrics metrics = displayMetrics(context, target);
            if (metrics == null) continue;
            float density = context.getResources().getDisplayMetrics().density;
            CameraShellProtocol.OverlaySpec spec = BlindSpotOverlayController.buildOverlaySpec(
                    settings, profile, 1, target, metrics.widthPixels, metrics.heightPixels,
                    target == CameraDisplayTarget.TABLET ? Math.round(16 * density) : 0,
                    target == CameraDisplayTarget.TABLET ? Math.round(36 * density) : 0,
                    target == CameraDisplayTarget.TABLET ? Math.round(88 * density) : 0);
            int[] size = BlindSpotCameraView.paneBoundedBufferSize(
                    spec.width, spec.height, spec.bufferQuality);
            add(requests, spec.dewarp, spec.rawFallbackCrop, size[0], size[1]);
        }
    }

    private static void collectParkingRequests(Context context, SharedPreferences settings,
            List<CameraMeshCache.Request> requests) {
        DisplayMetrics metrics = displayMetrics(context, CameraDisplayTarget.TABLET);
        if (metrics == null) return;
        for (ParkingCameraProfile profile : ParkingCameraProfile.values()) {
            CameraShellProtocol.OverlaySpec spec = ParkingCameraController.buildOverlaySpec(
                    profile, 1, CameraDisplayTarget.TABLET,
                    metrics.widthPixels, metrics.heightPixels, settings);
            int[] size = BlindSpotCameraView.paneBoundedBufferSize(
                    spec.width, spec.height, spec.bufferQuality);
            add(requests, spec.dewarp, spec.rawFallbackCrop, size[0], size[1]);
        }
    }

    private static void collectMirrorRequests(Context context, SharedPreferences settings,
            List<CameraMeshCache.Request> requests) {
        int target = RearviewMirrorSettings.target(settings);
        DisplayMetrics metrics = displayMetrics(context, target);
        if (metrics == null) return;
        boolean frontIntegrated = RearviewMirrorSettings.frontIntegrated(settings);
        addMirrorRequest(settings, target, metrics, false, requests);
        if (frontIntegrated) addMirrorRequest(settings, target, metrics, true, requests);
    }

    private static void addMirrorRequest(SharedPreferences settings,
            int target, DisplayMetrics metrics, boolean front,
            List<CameraMeshCache.Request> requests) {
        CameraShellProtocol.OverlaySpec spec = RearviewMirrorController.buildMeshPreparationSpec(
                settings, 1, front, target, metrics.widthPixels, metrics.heightPixels);
        int[] size = BlindSpotCameraView.paneBoundedBufferSize(
                spec.width, spec.height, spec.bufferQuality);
        add(requests, spec.dewarp, spec.rawFallbackCrop, size[0], size[1]);
    }

    private static void add(List<CameraMeshCache.Request> requests, CameraDewarpConfig config,
            DirectCameraCrop rawCrop, int width, int height) {
        CameraMeshCache.Request request = request(config, rawCrop.left, rawCrop.top,
                rawCrop.width, rawCrop.height, width, height);
        if (request != null) requests.add(request);
    }

    private static DisplayMetrics displayMetrics(Context context, int target) {
        try {
            Display display = CameraDisplayTarget.resolve(context, target);
            if (display == null) return null;
            DisplayMetrics metrics = new DisplayMetrics();
            display.getRealMetrics(metrics);
            return metrics.widthPixels > 0 && metrics.heightPixels > 0 ? metrics : null;
        } catch (RuntimeException failure) {
            Log.e(TAG, "camera mesh display lookup failed target="
                    + CameraDisplayTarget.name(target), failure);
            return null;
        }
    }
}
