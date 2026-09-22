package com.byd.extend;

import android.os.IBinder;

final class TurnSignalShellProtocol {
    static final String SERVICE_NAME = "byd_extend_guard";
    static final String PROCESS_NAME = "bydextend_helper";
    static final String HELPER_CLASS =
            "com.byd.extend.TurnSignalShellMain";
    static final String DESCRIPTOR =
            "com.byd.extend.ITurnSignalShell";
    static final String CALLBACK_DESCRIPTOR =
            "com.byd.extend.ITurnSignalShellCallback";
    static final String LOCK_PATH = "/data/local/tmp/bydextend_helper.lock";
    static final String LOG_PATH = "/data/local/tmp/bydextend_helper.log";
    static final int VERSION = 11;

    static final int TX_PING = IBinder.FIRST_CALL_TRANSACTION;
    static final int TX_REGISTER_CALLBACK = IBinder.FIRST_CALL_TRANSACTION + 1;
    static final int TX_CONFIGURE_GUARD = IBinder.FIRST_CALL_TRANSACTION + 2;
    static final int TX_SET_MANUAL_STATE = IBinder.FIRST_CALL_TRANSACTION + 3;
    static final int TX_REPORT_STATUS = IBinder.FIRST_CALL_TRANSACTION + 4;
    static final int TX_ATTACH_CONTROLLER = IBinder.FIRST_CALL_TRANSACTION + 5;
    static final int TX_SHUTDOWN = IBinder.FIRST_CALL_TRANSACTION + 6;
    static final int TX_CONFIGURE_MUSIC = IBinder.FIRST_CALL_TRANSACTION + 7;
    static final int TX_CONFIGURE_PARKING_RADAR = IBinder.FIRST_CALL_TRANSACTION + 8;
    static final int TX_CONFIGURE_AVAS = IBinder.FIRST_CALL_TRANSACTION + 9;
    static final int TX_INSTALL_AVAS_ASSET = IBinder.FIRST_CALL_TRANSACTION + 10;
    static final int TX_START_AVAS_MANUAL = IBinder.FIRST_CALL_TRANSACTION + 11;
    static final int TX_STOP_AVAS_MANUAL = IBinder.FIRST_CALL_TRANSACTION + 12;
    static final int TX_REPORT_AVAS_STATUS = IBinder.FIRST_CALL_TRANSACTION + 13;
    static final int TX_SHUTDOWN_KEEPING_AVAS = IBinder.FIRST_CALL_TRANSACTION + 14;
    static final int TX_START_AVAS_AUDITION = IBinder.FIRST_CALL_TRANSACTION + 15;
    static final int TX_STOP_AVAS_AUDITION = IBinder.FIRST_CALL_TRANSACTION + 16;
    static final int TX_CONFIGURE_LOGGING = IBinder.FIRST_CALL_TRANSACTION + 17;
    static final int CB_EVENT = IBinder.FIRST_CALL_TRANSACTION;

    private TurnSignalShellProtocol() {}

    static boolean isCallerAllowed(int actualUid, int appUid) {
        return actualUid == appUid;
    }

    static boolean isPayloadAllowed(int payload) {
        return payload >= 0 && payload <= 3;
    }

    static boolean isAvasProfileAllowed(String profileId) {
        return AvasConfig.PROFILE_IDS.contains(profileId);
    }

    static boolean isAvasAssetAllowed(String assetId) {
        return assetId != null && assetId.matches("[0-9a-f]{32}");
    }

    static boolean isAvasSessionAllowed(String sessionId) {
        return sessionId != null && sessionId.matches("[0-9a-f]{32}");
    }
}
