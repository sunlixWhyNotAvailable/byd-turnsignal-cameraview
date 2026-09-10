package com.byd.extend;

import android.os.IBinder;

/** Binder endpoint for the vendor Stock AVM renderer. */
final class StockAvmShellProtocol {
    static final String SERVICE_NAME = "byd_extend_avm";
    static final String PROCESS_NAME = "bydextend_avm";
    static final String HELPER_CLASS = "com.byd.extend.StockAvmShellMain";
    static final String DESCRIPTOR = "com.byd.extend.IStockAvmShell";
    static final String CALLBACK_DESCRIPTOR = "com.byd.extend.IStockAvmShellCallback";
    static final String LOCK_PATH = "/data/local/tmp/bydextend_avm.lock";
    static final String LOG_PATH = "/data/local/tmp/bydextend_avm.log";
    static final int VERSION = 2;

    static final int TX_PING = IBinder.FIRST_CALL_TRANSACTION;
    static final int TX_REGISTER_CALLBACK = IBinder.FIRST_CALL_TRANSACTION + 1;
    static final int TX_OPEN = IBinder.FIRST_CALL_TRANSACTION + 2;
    static final int TX_CLOSE = IBinder.FIRST_CALL_TRANSACTION + 3;
    static final int TX_SHUTDOWN = IBinder.FIRST_CALL_TRANSACTION + 4;
    static final int CB_EVENT = IBinder.FIRST_CALL_TRANSACTION;

    private StockAvmShellProtocol() {}

    static boolean isCallerAllowed(int actualUid, int appUid) {
        return actualUid == appUid;
    }
}
