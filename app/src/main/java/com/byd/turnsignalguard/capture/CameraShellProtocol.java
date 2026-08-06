package com.byd.turnsignalguard.capture;

import android.os.IBinder;
import android.os.Parcel;

final class CameraShellProtocol {
    static final String SERVICE_NAME = "byd_turn_signal_guard_camera";
    static final String PROCESS_NAME = "bydturnguard_camera";
    static final String HELPER_CLASS =
            "com.byd.turnsignalguard.capture.CameraShellMain";
    static final String DESCRIPTOR =
            "com.byd.turnsignalguard.capture.ICameraShell";
    static final String CALLBACK_DESCRIPTOR =
            "com.byd.turnsignalguard.capture.ICameraShellCallback";
    static final String LOCK_PATH = "/data/local/tmp/bydturnguard_camera.lock";
    static final String LOG_PATH = "/data/local/tmp/bydturnguard_camera.log";
    static final int VERSION = 18;

    static final int TX_PING = IBinder.FIRST_CALL_TRANSACTION;
    static final int TX_REGISTER_CALLBACK = IBinder.FIRST_CALL_TRANSACTION + 1;
    static final int TX_OPEN = IBinder.FIRST_CALL_TRANSACTION + 2;
    static final int TX_CLOSE = IBinder.FIRST_CALL_TRANSACTION + 3;
    static final int TX_SHUTDOWN = IBinder.FIRST_CALL_TRANSACTION + 4;
    static final int TX_OVERLAY_PREPARE = IBinder.FIRST_CALL_TRANSACTION + 5;
    static final int TX_OVERLAY_ACQUIRE_SURFACE = IBinder.FIRST_CALL_TRANSACTION + 6;
    static final int TX_OVERLAY_ARM_FRAME = IBinder.FIRST_CALL_TRANSACTION + 7;
    static final int TX_OVERLAY_SET_VISIBLE = IBinder.FIRST_CALL_TRANSACTION + 8;
    static final int TX_OVERLAY_CLOSE = IBinder.FIRST_CALL_TRANSACTION + 9;
    static final int TX_OVERLAY_SET_WARNING = IBinder.FIRST_CALL_TRANSACTION + 10;
    static final int TX_REVERSE_PREPARE = IBinder.FIRST_CALL_TRANSACTION + 11;
    static final int TX_REVERSE_ACQUIRE_SURFACES = IBinder.FIRST_CALL_TRANSACTION + 12;
    static final int TX_REVERSE_ARM_FRAMES = IBinder.FIRST_CALL_TRANSACTION + 13;
    static final int TX_REVERSE_SET_VISIBLE = IBinder.FIRST_CALL_TRANSACTION + 14;
    static final int TX_REVERSE_CLOSE = IBinder.FIRST_CALL_TRANSACTION + 15;
    static final int CB_EVENT = IBinder.FIRST_CALL_TRANSACTION;

    static final int WARNING_MODE_OFF = 0;
    static final int WARNING_MODE_CONSTANT = 1;
    static final int WARNING_MODE_PULSE = 2;
    static final int WARNING_EDGE_NONE = 0;
    static final int WARNING_EDGE_LEFT = 1;
    static final int WARNING_EDGE_RIGHT = 2;

    private CameraShellProtocol() {}

    static boolean isCallerAllowed(int actualUid, int appUid) {
        return actualUid == appUid;
    }

    static void validateWarning(
            int cameraId, int requestId, int surfaceGeneration, int edge, int mode) {
        if (!CameraProfile.of(cameraId).rear()) {
            throw new IllegalArgumentException("warning is rear-camera only");
        }
        if (requestId <= 0 || surfaceGeneration <= 0) {
            throw new IllegalArgumentException("invalid warning request identity");
        }
        if (edge == WARNING_EDGE_NONE && mode == WARNING_MODE_OFF) return;
        if ((edge != WARNING_EDGE_LEFT && edge != WARNING_EDGE_RIGHT)
                || (mode != WARNING_MODE_CONSTANT && mode != WARNING_MODE_PULSE)) {
            throw new IllegalArgumentException("invalid warning edge/mode");
        }
    }

    static void validateWarning(int requestId, int surfaceGeneration, int edge, int mode) {
        validateWarning(CameraProfile.REAR_LEFT,
                requestId, surfaceGeneration, edge, mode);
    }

    static final class OverlaySpec {
        final int cameraId;
        final int requestId;
        final int target;
        final int width;
        final int height;
        final int x;
        final int y;
        final float cropLeft;
        final float cropTop;
        final float cropWidth;
        final float cropHeight;
        final int cropAspectMode;
        final int rotationDegrees;
        final int rotationMode;
        final int cornerRadiusDp;
        final CameraDewarpConfig dewarp;
        final DirectCameraCrop rawFallbackCrop;

        OverlaySpec(
                int requestId, int target, int width, int height, int x, int y,
                float cropLeft, float cropTop, float cropWidth, float cropHeight,
                int cropAspectMode) {
            this(CameraProfile.REAR_LEFT, requestId, target, width, height, x, y,
                    cropLeft, cropTop, cropWidth, cropHeight, cropAspectMode,
                    CameraRotation.DEFAULT_DEGREES, CameraRotation.MODE_FIT,
                    8, CameraDewarpConfig.disabled(CameraDewarpConfig.LENS_LEFT));
        }

        OverlaySpec(
                int cameraId, int requestId, int target, int width, int height, int x, int y,
                float cropLeft, float cropTop, float cropWidth, float cropHeight,
                int cropAspectMode, int cornerRadiusDp) {
            this(cameraId, requestId, target, width, height, x, y,
                    cropLeft, cropTop, cropWidth, cropHeight, cropAspectMode,
                    CameraRotation.DEFAULT_DEGREES, CameraRotation.MODE_FIT, cornerRadiusDp,
                    CameraDewarpConfig.disabled(
                            CameraDewarpConfig.lensFor(CameraProfile.of(cameraId))));
        }

        OverlaySpec(
                int cameraId, int requestId, int target, int width, int height, int x, int y,
                float cropLeft, float cropTop, float cropWidth, float cropHeight,
                int cropAspectMode, int rotationDegrees, int cornerRadiusDp) {
            this(cameraId, requestId, target, width, height, x, y,
                    cropLeft, cropTop, cropWidth, cropHeight, cropAspectMode,
                    rotationDegrees, CameraRotation.MODE_FIT, cornerRadiusDp,
                    CameraDewarpConfig.disabled(
                            CameraDewarpConfig.lensFor(CameraProfile.of(cameraId))));
        }

        OverlaySpec(
                int cameraId, int requestId, int target, int width, int height, int x, int y,
                float cropLeft, float cropTop, float cropWidth, float cropHeight,
                int cropAspectMode, int rotationDegrees, int cornerRadiusDp,
                CameraDewarpConfig dewarp) {
            this(cameraId, requestId, target, width, height, x, y,
                    cropLeft, cropTop, cropWidth, cropHeight, cropAspectMode,
                    rotationDegrees, CameraRotation.MODE_FIT, cornerRadiusDp, dewarp);
        }

        OverlaySpec(
                int cameraId, int requestId, int target, int width, int height, int x, int y,
                float cropLeft, float cropTop, float cropWidth, float cropHeight,
                int cropAspectMode, int rotationDegrees, int rotationMode,
                int cornerRadiusDp, CameraDewarpConfig dewarp) {
            this(cameraId, requestId, target, width, height, x, y,
                    cropLeft, cropTop, cropWidth, cropHeight, cropAspectMode,
                    rotationDegrees, rotationMode, cornerRadiusDp, dewarp,
                    DirectCameraCrop.of(cropLeft, cropTop, cropWidth, cropHeight,
                            cropAspectMode, rotationDegrees, rotationMode));
        }

        OverlaySpec(
                int cameraId, int requestId, int target, int width, int height, int x, int y,
                float cropLeft, float cropTop, float cropWidth, float cropHeight,
                int cropAspectMode, int rotationDegrees, int rotationMode,
                int cornerRadiusDp, CameraDewarpConfig dewarp,
                DirectCameraCrop rawFallbackCrop) {
            this.cameraId = cameraId;
            this.requestId = requestId;
            this.target = target;
            this.width = width;
            this.height = height;
            this.x = x;
            this.y = y;
            this.cropLeft = cropLeft;
            this.cropTop = cropTop;
            this.cropWidth = cropWidth;
            this.cropHeight = cropHeight;
            this.cropAspectMode = cropAspectMode;
            this.rotationDegrees = rotationDegrees;
            this.rotationMode = rotationMode;
            this.cornerRadiusDp = cornerRadiusDp;
            this.dewarp = dewarp == null
                    ? CameraDewarpConfig.disabled(
                            CameraDewarpConfig.lensFor(CameraProfile.of(cameraId)))
                    : dewarp;
            if (rawFallbackCrop == null) {
                throw new IllegalArgumentException("raw fallback crop is required");
            }
            this.rawFallbackCrop = rawFallbackCrop;
        }

        void writeToParcel(Parcel parcel) {
            parcel.writeInt(cameraId);
            parcel.writeInt(requestId);
            parcel.writeInt(target);
            parcel.writeInt(width);
            parcel.writeInt(height);
            parcel.writeInt(x);
            parcel.writeInt(y);
            parcel.writeFloat(cropLeft);
            parcel.writeFloat(cropTop);
            parcel.writeFloat(cropWidth);
            parcel.writeFloat(cropHeight);
            parcel.writeInt(cropAspectMode);
            parcel.writeInt(rotationDegrees);
            parcel.writeInt(rotationMode);
            parcel.writeInt(cornerRadiusDp);
            writeDewarp(parcel, dewarp);
            writeCrop(parcel, rawFallbackCrop);
        }

        static OverlaySpec readFromParcel(Parcel parcel) {
            int cameraId = parcel.readInt();
            int requestId = parcel.readInt();
            int target = parcel.readInt();
            int width = parcel.readInt();
            int height = parcel.readInt();
            int x = parcel.readInt();
            int y = parcel.readInt();
            float cropLeft = parcel.readFloat();
            float cropTop = parcel.readFloat();
            float cropWidth = parcel.readFloat();
            float cropHeight = parcel.readFloat();
            int cropAspectMode = parcel.readInt();
            int rotationDegrees = parcel.readInt();
            int rotationMode = parcel.readInt();
            int cornerRadiusDp = parcel.readInt();
            CameraDewarpConfig dewarp = readDewarp(parcel);
            return new OverlaySpec(
                    cameraId, requestId, target, width, height, x, y,
                    cropLeft, cropTop, cropWidth, cropHeight, cropAspectMode,
                    rotationDegrees, rotationMode, cornerRadiusDp, dewarp,
                    readCrop(parcel));
        }

        void validate(int displayWidth, int displayHeight) {
            CameraProfile profile = CameraProfile.of(cameraId);
            if (requestId <= 0) throw new IllegalArgumentException("invalid request id");
            if (!CameraDisplayTarget.isValid(target)) {
                throw new IllegalArgumentException("invalid display target");
            }
            if (width <= 0 || height <= 0 || x < 0 || y < 0
                    || width > displayWidth || height > displayHeight
                    || x > displayWidth - width || y > displayHeight - height) {
                throw new IllegalArgumentException("overlay geometry outside display");
            }
            if (!finite(cropLeft) || !finite(cropTop)
                    || !finite(cropWidth) || !finite(cropHeight)
                    || cropLeft < 0.0f || cropTop < 0.0f
                    || cropWidth <= 0.0f || cropHeight <= 0.0f
                    || cropLeft + cropWidth > 1.0001f
                    || cropTop + cropHeight > 1.0001f) {
                throw new IllegalArgumentException("invalid normalized crop");
            }
            if (cropAspectMode < DirectCameraCrop.ASPECT_FOUR_THREE
                    || cropAspectMode > DirectCameraCrop.ASPECT_FREE) {
                throw new IllegalArgumentException("invalid crop aspect mode");
            }
            if (!CameraRotation.isValid(rotationDegrees)) {
                throw new IllegalArgumentException("invalid camera rotation");
            }
            if (!CameraRotation.isValidMode(rotationMode)) {
                throw new IllegalArgumentException("invalid camera rotation mode");
            }
            if (cornerRadiusDp < 0 || cornerRadiusDp > 48) {
                throw new IllegalArgumentException("invalid corner radius");
            }
            if (dewarp.lens != CameraDewarpConfig.lensFor(profile)) {
                throw new IllegalArgumentException("dewarp lens does not match camera");
            }
            validateCrop(rawFallbackCrop);
        }

        DirectCameraCrop crop() {
            return DirectCameraCrop.of(
                    cropLeft, cropTop, cropWidth, cropHeight,
                    cropAspectMode, rotationDegrees, rotationMode);
        }

        private static boolean finite(float value) {
            return !Float.isNaN(value) && !Float.isInfinite(value);
        }

        private static void validateCrop(DirectCameraCrop crop) {
            if (!finite(crop.left) || !finite(crop.top)
                    || !finite(crop.width) || !finite(crop.height)
                    || crop.left < 0.0f || crop.top < 0.0f
                    || crop.width <= 0.0f || crop.height <= 0.0f
                    || crop.right() > 1.0001f || crop.bottom() > 1.0001f
                    || crop.aspectMode < DirectCameraCrop.ASPECT_FOUR_THREE
                    || crop.aspectMode > DirectCameraCrop.ASPECT_FREE
                    || !CameraRotation.isValid(crop.rotationDegrees)
                    || !CameraRotation.isValidMode(crop.rotationMode)) {
                throw new IllegalArgumentException("invalid raw fallback crop");
            }
        }
    }

    static final class ReverseOverlaySpec {
        final int requestId;
        final ReverseCameraLayout layout;
        final ReverseCameraLayout rawFallbackLayout;
        final int cornerRadiusDp;
        final CameraDewarpConfig rearDewarp;
        final CameraDewarpConfig leftDewarp;
        final CameraDewarpConfig rightDewarp;

        ReverseOverlaySpec(int requestId, ReverseCameraLayout layout) {
            this(requestId, layout, 8,
                    CameraDewarpConfig.disabled(CameraDewarpConfig.LENS_REAR),
                    CameraDewarpConfig.disabled(CameraDewarpConfig.LENS_LEFT),
                    CameraDewarpConfig.disabled(CameraDewarpConfig.LENS_RIGHT));
        }

        ReverseOverlaySpec(int requestId, ReverseCameraLayout layout, int cornerRadiusDp) {
            this(requestId, layout, cornerRadiusDp,
                    CameraDewarpConfig.disabled(CameraDewarpConfig.LENS_REAR),
                    CameraDewarpConfig.disabled(CameraDewarpConfig.LENS_LEFT),
                    CameraDewarpConfig.disabled(CameraDewarpConfig.LENS_RIGHT));
        }

        ReverseOverlaySpec(
                int requestId, ReverseCameraLayout layout, int cornerRadiusDp,
                CameraDewarpConfig rearDewarp,
                CameraDewarpConfig leftDewarp,
                CameraDewarpConfig rightDewarp) {
            this(requestId, layout, layout, cornerRadiusDp,
                    rearDewarp, leftDewarp, rightDewarp);
        }

        ReverseOverlaySpec(
                int requestId, ReverseCameraLayout layout,
                ReverseCameraLayout rawFallbackLayout, int cornerRadiusDp,
                CameraDewarpConfig rearDewarp,
                CameraDewarpConfig leftDewarp,
                CameraDewarpConfig rightDewarp) {
            if (layout == null) throw new IllegalArgumentException("reverse layout required");
            if (rawFallbackLayout == null) {
                throw new IllegalArgumentException("reverse raw fallback layout required");
            }
            this.requestId = requestId;
            this.layout = layout;
            this.rawFallbackLayout = rawFallbackLayout;
            this.cornerRadiusDp = cornerRadiusDp;
            this.rearDewarp = rearDewarp == null
                    ? CameraDewarpConfig.disabled(CameraDewarpConfig.LENS_REAR) : rearDewarp;
            this.leftDewarp = leftDewarp == null
                    ? CameraDewarpConfig.disabled(CameraDewarpConfig.LENS_LEFT) : leftDewarp;
            this.rightDewarp = rightDewarp == null
                    ? CameraDewarpConfig.disabled(CameraDewarpConfig.LENS_RIGHT) : rightDewarp;
        }

        void writeToParcel(Parcel parcel) {
            parcel.writeInt(requestId);
            writeRect(parcel, layout.background);
            parcel.writeInt(cornerRadiusDp);
            writeDewarp(parcel, rearDewarp);
            writeDewarp(parcel, leftDewarp);
            writeDewarp(parcel, rightDewarp);
            for (ReverseCameraLayout.Pane pane : rawFallbackLayout.panes()) {
                writeRect(parcel, pane.sourceCrop);
            }
            for (ReverseCameraLayout.Pane pane : layout.panes()) {
                parcel.writeInt(pane.cameraIndex);
                writeRect(parcel, pane.destination);
                writeRect(parcel, pane.sourceCrop);
                parcel.writeInt(pane.rotationDegrees);
                parcel.writeInt(pane.displayMode);
                parcel.writeInt(pane.zOrder);
            }
        }

        static ReverseOverlaySpec readFromParcel(Parcel parcel) {
            int requestId = parcel.readInt();
            ReverseCameraLayout layout = ReverseCameraLayout.defaults();
            float[] background = readRect(parcel);
            validateRect(background, ReverseCameraLayout.MIN_DESTINATION_SIZE);
            layout = ReverseCameraLayout.withBackground(layout,
                    ReverseCameraLayout.destination(background[0], background[1],
                            background[2], background[3]));
            int cornerRadiusDp = parcel.readInt();
            CameraDewarpConfig rearDewarp = readDewarp(parcel);
            CameraDewarpConfig leftDewarp = readDewarp(parcel);
            CameraDewarpConfig rightDewarp = readDewarp(parcel);
            ReverseCameraLayout.Rect[] rawCrops = new ReverseCameraLayout.Rect[3];
            for (int i = 0; i < rawCrops.length; i++) {
                float[] crop = readRect(parcel);
                validateRect(crop, 0.0f);
                rawCrops[i] = ReverseCameraLayout.sourceCrop(
                        crop[0], crop[1], crop[2], crop[3]);
            }
            int[] indexes = new int[3];
            int[] zOrders = new int[3];
            for (int i = 0; i < indexes.length; i++) {
                int cameraIndex = parcel.readInt();
                int expectedIndex = i + 1;
                if (cameraIndex != expectedIndex) {
                    throw new IllegalArgumentException("invalid reverse camera mapping");
                }
                float[] destination = readRect(parcel);
                float[] crop = readRect(parcel);
                int rotationDegrees = parcel.readInt();
                int displayMode = parcel.readInt();
                int zOrder = parcel.readInt();
                validateRect(destination, ReverseCameraLayout.MIN_DESTINATION_SIZE);
                validateRect(crop, 0.0f);
                if (zOrder < 0 || zOrder > 2) {
                    throw new IllegalArgumentException("invalid reverse z-order");
                }
                if (!CameraRotation.isValid(rotationDegrees)) {
                    throw new IllegalArgumentException("invalid reverse rotation");
                }
                if (!ReverseCameraLayout.isValidDisplayMode(displayMode)) {
                    throw new IllegalArgumentException("invalid reverse display mode");
                }
                layout = ReverseCameraLayout.withPane(layout, cameraIndex,
                        ReverseCameraLayout.destination(destination[0], destination[1],
                                destination[2], destination[3]),
                        ReverseCameraLayout.sourceCrop(crop[0], crop[1],
                                crop[2], crop[3]), rotationDegrees);
                layout = ReverseCameraLayout.withDisplayMode(
                        layout, cameraIndex, displayMode);
                indexes[i] = cameraIndex;
                zOrders[i] = zOrder;
            }
            if (zOrders[0] == zOrders[1] || zOrders[0] == zOrders[2]
                    || zOrders[1] == zOrders[2]) {
                throw new IllegalArgumentException("duplicate reverse z-order");
            }
            for (int z = 0; z < 3; z++) {
                for (int i = 0; i < indexes.length; i++) {
                    if (zOrders[i] == z) {
                        layout = ReverseCameraLayout.bringToFront(layout, indexes[i]);
                        break;
                    }
                }
            }
            ReverseCameraLayout rawFallbackLayout = layout;
            for (int i = 0; i < rawCrops.length; i++) {
                ReverseCameraLayout.Pane pane = layout.pane(i + 1);
                rawFallbackLayout = ReverseCameraLayout.withPane(
                        rawFallbackLayout, pane.cameraIndex, pane.destination,
                        rawCrops[i], pane.rotationDegrees);
            }
            return new ReverseOverlaySpec(requestId, layout, rawFallbackLayout, cornerRadiusDp,
                    rearDewarp, leftDewarp, rightDewarp);
        }

        void validate(int displayWidth, int displayHeight) {
            if (requestId <= 0) throw new IllegalArgumentException("invalid reverse request id");
            if (displayWidth <= 0 || displayHeight <= 0) {
                throw new IllegalArgumentException("invalid reverse display bounds");
            }
            validateModelRect(layout.background, ReverseCameraLayout.MIN_DESTINATION_SIZE);
            if (cornerRadiusDp < 0 || cornerRadiusDp > 48) {
                throw new IllegalArgumentException("invalid reverse corner radius");
            }
            if (rearDewarp.lens != CameraDewarpConfig.LENS_REAR
                    || leftDewarp.lens != CameraDewarpConfig.LENS_LEFT
                    || rightDewarp.lens != CameraDewarpConfig.LENS_RIGHT) {
                throw new IllegalArgumentException("invalid reverse dewarp lens mapping");
            }
            boolean[] zSeen = new boolean[3];
            int expectedIndex = 1;
            for (ReverseCameraLayout.Pane pane : layout.panes()) {
                if (pane.cameraIndex != expectedIndex++) {
                    throw new IllegalArgumentException("invalid reverse camera mapping");
                }
                validateModelRect(pane.destination, ReverseCameraLayout.MIN_DESTINATION_SIZE);
                validateModelRect(pane.sourceCrop, 0.0f);
                if (!CameraRotation.isValid(pane.rotationDegrees)) {
                    throw new IllegalArgumentException("invalid reverse rotation");
                }
                if (!ReverseCameraLayout.isValidDisplayMode(pane.displayMode)) {
                    throw new IllegalArgumentException("invalid reverse display mode");
                }
                if (pane.zOrder < 0 || pane.zOrder >= zSeen.length || zSeen[pane.zOrder]) {
                    throw new IllegalArgumentException("invalid reverse z-order");
                }
                zSeen[pane.zOrder] = true;
            }
            int rawIndex = 1;
            for (ReverseCameraLayout.Pane pane : rawFallbackLayout.panes()) {
                if (pane.cameraIndex != rawIndex++) {
                    throw new IllegalArgumentException("invalid reverse raw camera mapping");
                }
                validateModelRect(pane.sourceCrop, 0.0f);
            }
        }

        private static void writeRect(Parcel parcel, ReverseCameraLayout.Rect rect) {
            parcel.writeFloat(rect.left);
            parcel.writeFloat(rect.top);
            parcel.writeFloat(rect.width);
            parcel.writeFloat(rect.height);
        }

        private static float[] readRect(Parcel parcel) {
            return new float[]{parcel.readFloat(), parcel.readFloat(),
                    parcel.readFloat(), parcel.readFloat()};
        }

        private static void validateModelRect(
                ReverseCameraLayout.Rect rect, float minimumSize) {
            validateRect(new float[]{rect.left, rect.top, rect.width, rect.height}, minimumSize);
        }

        private static void validateRect(float[] rect, float minimumSize) {
            for (float value : rect) {
                if (Float.isNaN(value) || Float.isInfinite(value)) {
                    throw new IllegalArgumentException("reverse geometry must be finite");
                }
            }
            if (rect[0] < 0 || rect[1] < 0
                    || rect[2] <= minimumSize - 0.0001f
                    || rect[3] <= minimumSize - 0.0001f
                    || rect[0] + rect[2] > 1.0001f
                    || rect[1] + rect[3] > 1.0001f) {
                throw new IllegalArgumentException("reverse geometry outside canvas");
            }
        }
    }

    private static void writeDewarp(Parcel parcel, CameraDewarpConfig value) {
        for (int field : encodeDewarp(value)) parcel.writeInt(field);
    }

    private static CameraDewarpConfig readDewarp(Parcel parcel) {
        return decodeDewarp(new int[]{
                parcel.readInt(), parcel.readInt(), parcel.readInt(), parcel.readInt()});
    }

    static int[] encodeDewarp(CameraDewarpConfig value) {
        if (value == null) throw new IllegalArgumentException("dewarp config required");
        return new int[]{
                value.lens, value.enabled ? 1 : 0, value.fovDegrees, value.projection};
    }

    static CameraDewarpConfig decodeDewarp(int[] wire) {
        if (wire == null || wire.length != 4) {
            throw new IllegalArgumentException("invalid dewarp wire record");
        }
        return decodeDewarp(wire[0], wire[1], wire[2], wire[3]);
    }

    static CameraDewarpConfig decodeDewarp(
            int lens, int enabled, int fovDegrees, int projection) {
        if (!CameraDewarpConfig.isValidLens(lens)
                || (enabled != 0 && enabled != 1)
                || fovDegrees < CameraDewarpConfig.MIN_FOV_DEGREES
                || fovDegrees > CameraDewarpConfig.MAX_FOV_DEGREES
                || !CameraDewarpConfig.isValidProjection(projection)) {
            throw new IllegalArgumentException("invalid dewarp config");
        }
        return CameraDewarpConfig.of(lens, enabled == 1, fovDegrees, projection);
    }

    private static void writeCrop(Parcel parcel, DirectCameraCrop crop) {
        parcel.writeFloat(crop.left);
        parcel.writeFloat(crop.top);
        parcel.writeFloat(crop.width);
        parcel.writeFloat(crop.height);
        parcel.writeInt(crop.aspectMode);
        parcel.writeInt(crop.rotationDegrees);
        parcel.writeInt(crop.rotationMode);
    }

    private static DirectCameraCrop readCrop(Parcel parcel) {
        float left = parcel.readFloat();
        float top = parcel.readFloat();
        float width = parcel.readFloat();
        float height = parcel.readFloat();
        int aspectMode = parcel.readInt();
        int rotationDegrees = parcel.readInt();
        int rotationMode = parcel.readInt();
        if (!Float.isFinite(left) || !Float.isFinite(top)
                || !Float.isFinite(width) || !Float.isFinite(height)
                || left < 0.0f || top < 0.0f || width <= 0.0f || height <= 0.0f
                || left + width > 1.0001f || top + height > 1.0001f
                || aspectMode < DirectCameraCrop.ASPECT_FOUR_THREE
                || aspectMode > DirectCameraCrop.ASPECT_FREE
                || !CameraRotation.isValid(rotationDegrees)
                || !CameraRotation.isValidMode(rotationMode)) {
            throw new IllegalArgumentException("invalid raw fallback crop");
        }
        return DirectCameraCrop.of(left, top, width, height,
                aspectMode, rotationDegrees, rotationMode);
    }
}
