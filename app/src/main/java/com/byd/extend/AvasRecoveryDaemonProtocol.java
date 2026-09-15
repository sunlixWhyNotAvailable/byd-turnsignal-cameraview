package com.byd.extend;

import android.os.IBinder;

final class AvasRecoveryDaemonProtocol {
    static final String SERVICE_NAME = "byd_extend_avas_keepalive";
    static final String PROCESS_NAME = "bydextend_avas_keepalive";
    static final String MAIN_CLASS = "com.byd.extend.AvasRecoveryShellMain";
    static final String DESCRIPTOR = "com.byd.extend.IAvasRecoveryDaemon";
    static final String LOCK_PATH = "/data/local/tmp/bydextend_avas_keepalive.lock";
    static final String LOG_PATH = "/data/local/tmp/bydextend_avas_keepalive.log";
    static final String PREVIOUS_LOG_PATH = "/data/local/tmp/bydextend_avas_keepalive.log.1";
    static final String BOOT_LOG_PATH = "/data/local/tmp/bydextend_avas_keepalive_boot.log";
    static final String ENABLED_SETTING = "byd_extend_avas_recovery_enabled";
    static final int VERSION = 3;

    static final int TX_PING = IBinder.FIRST_CALL_TRANSACTION;
    static final int TX_SET_ENABLED = IBinder.FIRST_CALL_TRANSACTION + 1;
    static final int TX_SHUTDOWN = IBinder.FIRST_CALL_TRANSACTION + 2;
    static final int TX_CLEAR_LOGCAT = IBinder.FIRST_CALL_TRANSACTION + 3;

    private AvasRecoveryDaemonProtocol() {}

    static boolean isCallerAllowed(int callerUid, int appUid) {
        return callerUid == appUid;
    }
}
