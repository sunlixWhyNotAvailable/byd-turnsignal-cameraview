package com.byd.turnsignalguard.capture;

import android.os.IBinder;

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
    static final int VERSION = 5;

    static final int TX_PING = IBinder.FIRST_CALL_TRANSACTION;
    static final int TX_REGISTER_CALLBACK = IBinder.FIRST_CALL_TRANSACTION + 1;
    static final int TX_OPEN = IBinder.FIRST_CALL_TRANSACTION + 2;
    static final int TX_CLOSE = IBinder.FIRST_CALL_TRANSACTION + 3;
    static final int TX_SHUTDOWN = IBinder.FIRST_CALL_TRANSACTION + 4;
    static final int CB_EVENT = IBinder.FIRST_CALL_TRANSACTION;

    private CameraShellProtocol() {}

    static boolean isCallerAllowed(int actualUid, int appUid) {
        return actualUid == appUid;
    }
}
