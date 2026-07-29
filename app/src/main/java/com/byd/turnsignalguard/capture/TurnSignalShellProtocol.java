package com.byd.turnsignalguard.capture;

import android.os.IBinder;

final class TurnSignalShellProtocol {
    static final String SERVICE_NAME = "byd_turn_signal_guard";
    static final String PROCESS_NAME = "bydturnguard_helper";
    static final String HELPER_CLASS =
            "com.byd.turnsignalguard.capture.TurnSignalShellMain";
    static final String DESCRIPTOR =
            "com.byd.turnsignalguard.capture.ITurnSignalShell";
    static final String CALLBACK_DESCRIPTOR =
            "com.byd.turnsignalguard.capture.ITurnSignalShellCallback";
    static final String LOCK_PATH = "/data/local/tmp/bydturnguard_helper.lock";
    static final String LOG_PATH = "/data/local/tmp/bydturnguard_helper.log";
    static final int VERSION = 4;

    static final int TX_PING = IBinder.FIRST_CALL_TRANSACTION;
    static final int TX_REGISTER_CALLBACK = IBinder.FIRST_CALL_TRANSACTION + 1;
    static final int TX_CONFIGURE_GUARD = IBinder.FIRST_CALL_TRANSACTION + 2;
    static final int TX_SET_MANUAL_STATE = IBinder.FIRST_CALL_TRANSACTION + 3;
    static final int TX_REPORT_STATUS = IBinder.FIRST_CALL_TRANSACTION + 4;
    static final int TX_ATTACH_CONTROLLER = IBinder.FIRST_CALL_TRANSACTION + 5;
    static final int TX_SHUTDOWN = IBinder.FIRST_CALL_TRANSACTION + 6;
    static final int CB_EVENT = IBinder.FIRST_CALL_TRANSACTION;

    private TurnSignalShellProtocol() {}

    static boolean isCallerAllowed(int actualUid, int appUid) {
        return actualUid == appUid;
    }

    static boolean isPayloadAllowed(int payload) {
        return payload >= 0 && payload <= 3;
    }
}
