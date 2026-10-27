package com.byd.extend;

import android.os.IBinder;
import android.os.Parcel;
import android.os.ResultReceiver;

/** Fixed Binder contract between the app, shell detector, and existing camera owner. */
public final class MotionDetectorProtocol {
    static final String SERVICE_NAME = "byd_extend_motion";
    static final String PROCESS_NAME = "bydextend_motion";
    static final String MAIN_CLASS = "com.byd.extend.MotionDetectorShellMain";
    static final String DESCRIPTOR = "com.byd.extend.IMotionDetector";
    static final String CALLBACK_DESCRIPTOR = "com.byd.extend.IMotionDetectorCallback";
    static final String LOCK_PATH = "/data/local/tmp/bydextend_motion.lock";
    static final String LOG_PATH = "/data/local/tmp/bydextend_motion.log";
    static final String STATE_ROOT = "/data/local/tmp/bydextend_motion";
    static final String CONFIG_PATH = STATE_ROOT + "/config.json";
    static final String ACTION_SHOW_WARNING = "com.byd.extend.action.SHOW_MOTION_WARNING";
    static final String EXTRA_SESSION = "motion_session_id";
    static final int VERSION = 18;
    static final int CONFIG_PENDING = 0;
    static final int CONFIG_APPLIED = 1;
    static final int CONFIG_MODE_ONLY = 2;
    static final int CONFIG_RETAINED = 3;
    public static final String EXTRA_WINDOW_REPORTER = "motion_window_reporter";
    public static final String WINDOW_COMMANDS = "window_commands";
    public static final String WINDOW_CANCELLED = "window_cancelled";
    public static final String WINDOW_DEADLINE = "window_deadline_elapsed_ms";
    public static final int WINDOW_READY = 1;
    public static final int WINDOW_DRAWN = 2;
    public static final int WINDOW_CLOSED = 3;
    public static final int WINDOW_CLOSE = 4;
    public static final int WINDOW_SET_DEADLINE = 5;
    public static final int WINDOW_PREPARE_SLEEP = 6;
    public static final int WINDOW_SLEEP_STARTED = 7;
    public static final int WINDOW_BLACK_FALLBACK = 8;
    public static final int WINDOW_PREPARED = 6;
    public static final int WINDOW_REQUEST_FINISH = 7;
    public static final int WINDOW_PAUSED = 8;
    public static final int WINDOW_USER_TAKEOVER = 9;
    public static final long WINDOW_START_TIMEOUT_MS = 30_000L;
    public static final long WINDOW_CLOSE_TIMEOUT_MS = 2_000L;

    static final int TX_PING = IBinder.FIRST_CALL_TRANSACTION;
    static final int TX_START = IBinder.FIRST_CALL_TRANSACTION + 1;
    static final int TX_SET_ELIGIBLE = IBinder.FIRST_CALL_TRANSACTION + 2;
    static final int TX_STOP = IBinder.FIRST_CALL_TRANSACTION + 3;
    static final int TX_STATUS = IBinder.FIRST_CALL_TRANSACTION + 4;
    static final int TX_WAKE_FOR_WARNING = IBinder.FIRST_CALL_TRANSACTION + 5;
    static final int TX_FINISH_WARNING = IBinder.FIRST_CALL_TRANSACTION + 6;
    static final int TX_USER_TAKEOVER = IBinder.FIRST_CALL_TRANSACTION + 7;
    static final int TX_WARNING_WINDOW_READY = IBinder.FIRST_CALL_TRANSACTION + 8;
    static final int TX_CONFIGURE = IBinder.FIRST_CALL_TRANSACTION + 9;
    static final int TX_ATTACH_UI = IBinder.FIRST_CALL_TRANSACTION + 10;
    static final int TX_DETACH_UI = IBinder.FIRST_CALL_TRANSACTION + 11;
    static final int TX_TELEMETRY = IBinder.FIRST_CALL_TRANSACTION + 12;
    static final int TX_IMPORT_MEDIA_INDEX = IBinder.FIRST_CALL_TRANSACTION + 13;
    static final int TX_REFRESH_MEDIA = IBinder.FIRST_CALL_TRANSACTION + 14;
    static final int TX_QUERY_MEDIA = IBinder.FIRST_CALL_TRANSACTION + 15;
    static final int TX_READ_MEDIA = IBinder.FIRST_CALL_TRANSACTION + 16;
    static final int TX_DELETE_MEDIA = IBinder.FIRST_CALL_TRANSACTION + 17;
    static final int TX_EXPLICIT_SHUTDOWN = IBinder.FIRST_CALL_TRANSACTION + 18;
    static final int TX_CHECK_START = IBinder.FIRST_CALL_TRANSACTION + 19;
    static final int TX_CHECK_CANCEL = IBinder.FIRST_CALL_TRANSACTION + 20;
    static final int TX_OPEN_THUMBNAIL = IBinder.FIRST_CALL_TRANSACTION + 21;
    static final int TX_READ_CHARGE = IBinder.FIRST_CALL_TRANSACTION + 22;
    static final int TX_VIEW_PREPARE = IBinder.FIRST_CALL_TRANSACTION + 23;
    static final int TX_VIEW_RELEASE = IBinder.FIRST_CALL_TRANSACTION + 24;
    static final int TX_REGISTER_VIEW_HELPER = IBinder.FIRST_CALL_TRANSACTION + 25;
    static final int TX_VIEW_OBSERVE_CHARGE = IBinder.FIRST_CALL_TRANSACTION + 26;
    static final int TX_VIEW_DETACH_CHARGE = IBinder.FIRST_CALL_TRANSACTION + 27;
    static final int TX_CAMERA_HOST_READY = IBinder.FIRST_CALL_TRANSACTION + 28;

    static final int TX_CONFIGURE_STORAGE = IBinder.FIRST_CALL_TRANSACTION + 29;
    static final int TX_SUSPEND_STORAGE = IBinder.FIRST_CALL_TRANSACTION + 30;

    static final int CB_PREPARED = IBinder.FIRST_CALL_TRANSACTION;
    static final int CB_STATUS = IBinder.FIRST_CALL_TRANSACTION + 1;
    static final int CB_DETECTION = IBinder.FIRST_CALL_TRANSACTION + 2;
    static final int CB_ERROR = IBinder.FIRST_CALL_TRANSACTION + 3;
    static final int CB_SCREEN = IBinder.FIRST_CALL_TRANSACTION + 4;
    static final int CB_CHARGE = IBinder.FIRST_CALL_TRANSACTION + 5;
    static final int CB_CONFIGURED = IBinder.FIRST_CALL_TRANSACTION + 6;
    static final int CB_CONFIGURE_ERROR = IBinder.FIRST_CALL_TRANSACTION + 7;
    static final String VIEW_CHARGE_CALLBACK_DESCRIPTOR = "com.byd.extend.ICameraViewingChargeCallback";
    static final int CB_VIEW_CHARGE = IBinder.FIRST_CALL_TRANSACTION;

    // CameraRuntimeHost operations on byd_extend_guard/TX_CAMERA_RUNTIME.
    static final int CAMERA_ATTACH = 13;
    static final int CAMERA_DETACH = 14;
    static final int CAMERA_AUTHORIZE = 15;
    static final int CAMERA_RECORD_ATTACH = 16;
    static final int CAMERA_RECORD_DETACH = 17;
    static final int CAMERA_OWN_WAKE = 18;
    static final int CAMERA_END_WAKE = 19;
    static final int CAMERA_BIND_WORKER = 20;
    static final int CAMERA_UNBIND_WORKER = 21;
    static final int SURFACE_INDEX = 4123;

    static final int INPUT_WIDTH = 3840;
    static final int INPUT_HEIGHT = 2600;
    static final int INPUT_BYTES = INPUT_WIDTH * INPUT_HEIGHT * 3 / 2;
    static final int PREVIEW_WIDTH = 1920;
    static final int PREVIEW_HEIGHT = 1080;
    static final long WARMUP_MS = 2_500L;
    public static final long WARNING_MS = 10_000L;
    static final String OEM_PACKAGE = "com.byd.sentrymode";

    private MotionDetectorProtocol() {}

    static void writeChargeState(Parcel parcel, CameraChargePolicy.State state) {
        Integer soc = state.socPercent();
        Float voltage = state.voltageVolts();
        parcel.writeInt(soc == null ? 0 : 1);
        if (soc != null) parcel.writeInt(soc);
        parcel.writeInt(voltage == null ? 0 : 1);
        if (voltage != null) parcel.writeFloat(voltage);
        parcel.writeInt(state.missingMetrics());
        parcel.writeInt(state.lowCauses());
        parcel.writeInt(state.isMonitoring() ? 1 : 0);
        parcel.writeInt(state.isLatchPersistenceHealthy() ? 1 : 0);
    }

    static CameraChargePolicy.State readChargeState(Parcel parcel) {
        int hasSoc = readFlag(parcel);
        Integer soc = hasSoc == 1 ? parcel.readInt() : null;
        int hasVoltage = readFlag(parcel);
        Float voltage = hasVoltage == 1 ? parcel.readFloat() : null;
        int missing = parcel.readInt();
        int causes = parcel.readInt();
        int monitoring = readFlag(parcel);
        int persistenceHealthy = readFlag(parcel);
        if ((missing & ~(CameraChargePolicy.MISSING_SOC | CameraChargePolicy.MISSING_VOLTAGE)) != 0
                || (causes & ~(CameraChargePolicy.CAUSE_SOC | CameraChargePolicy.CAUSE_VOLTAGE)) != 0
                || soc != null && (soc < 0 || soc > 100)
                || voltage != null && (!Float.isFinite(voltage) || voltage <= 0.0f || voltage > 20.0f)
                || (soc == null) != ((missing & CameraChargePolicy.MISSING_SOC) != 0)
                || (voltage == null) != ((missing & CameraChargePolicy.MISSING_VOLTAGE) != 0))
            throw new IllegalArgumentException("invalid camera charge state");
        return new CameraChargePolicy.State(soc, voltage, missing, causes,
                monitoring == 1, persistenceHealthy == 1);
    }

    private static int readFlag(Parcel parcel) {
        int value = parcel.readInt();
        if (value != 0 && value != 1) throw new IllegalArgumentException("flag must be 0 or 1");
        return value;
    }

    public static ResultReceiver remoteReceiver(ResultReceiver receiver) {
        // Bundles must name the framework class, not a process-local anonymous subclass.
        Parcel parcel = Parcel.obtain();
        try {
            receiver.writeToParcel(parcel, 0);
            parcel.setDataPosition(0);
            return ResultReceiver.CREATOR.createFromParcel(parcel);
        } finally {
            parcel.recycle();
        }
    }

    static boolean isMotionCameraLoss(String kind, String owner, int requestId,
            int activeRecordingRequestId) {
        return "camera_producer_closed".equals(kind)
                || "camera_error".equals(kind) && "motion_recording".equals(owner)
                && activeRecordingRequestId > 0 && requestId == activeRecordingRequestId;
    }

    static boolean validSession(String sessionId) {
        return sessionId != null && sessionId.matches("[0-9a-f]{32}");
    }
}
