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
    static final int VERSION = 7;

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

    static void validateWarning(int requestId, int surfaceGeneration, int edge, int mode) {
        if (requestId <= 0 || surfaceGeneration <= 0) {
            throw new IllegalArgumentException("invalid warning request identity");
        }
        if (edge == WARNING_EDGE_NONE && mode == WARNING_MODE_OFF) return;
        if ((edge != WARNING_EDGE_LEFT && edge != WARNING_EDGE_RIGHT)
                || (mode != WARNING_MODE_CONSTANT && mode != WARNING_MODE_PULSE)) {
            throw new IllegalArgumentException("invalid warning edge/mode");
        }
    }

    static final class OverlaySpec {
        final int requestId;
        final int width;
        final int height;
        final int x;
        final int y;
        final float cropLeft;
        final float cropTop;
        final float cropWidth;
        final float cropHeight;
        final int cropAspectMode;

        OverlaySpec(
                int requestId, int width, int height, int x, int y,
                float cropLeft, float cropTop, float cropWidth, float cropHeight,
                int cropAspectMode) {
            this.requestId = requestId;
            this.width = width;
            this.height = height;
            this.x = x;
            this.y = y;
            this.cropLeft = cropLeft;
            this.cropTop = cropTop;
            this.cropWidth = cropWidth;
            this.cropHeight = cropHeight;
            this.cropAspectMode = cropAspectMode;
        }

        void writeToParcel(Parcel parcel) {
            parcel.writeInt(requestId);
            parcel.writeInt(width);
            parcel.writeInt(height);
            parcel.writeInt(x);
            parcel.writeInt(y);
            parcel.writeFloat(cropLeft);
            parcel.writeFloat(cropTop);
            parcel.writeFloat(cropWidth);
            parcel.writeFloat(cropHeight);
            parcel.writeInt(cropAspectMode);
        }

        static OverlaySpec readFromParcel(Parcel parcel) {
            return new OverlaySpec(
                    parcel.readInt(), parcel.readInt(), parcel.readInt(),
                    parcel.readInt(), parcel.readInt(), parcel.readFloat(),
                    parcel.readFloat(), parcel.readFloat(), parcel.readFloat(),
                    parcel.readInt());
        }

        void validate(int displayWidth, int displayHeight) {
            if (requestId <= 0) throw new IllegalArgumentException("invalid request id");
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
        }

        DirectCameraCrop crop() {
            return DirectCameraCrop.of(
                    cropLeft, cropTop, cropWidth, cropHeight, cropAspectMode);
        }

        private static boolean finite(float value) {
            return !Float.isNaN(value) && !Float.isInfinite(value);
        }
    }
}
