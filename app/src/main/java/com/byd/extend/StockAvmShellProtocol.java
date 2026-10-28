package com.byd.extend;

import android.os.IBinder;
import android.system.OsConstants;

/** Binder endpoint for the vendor Stock AVM renderer. */
final class StockAvmShellProtocol {
    static final String SERVICE_NAME = "byd_extend_avm";
    static final String PROCESS_NAME = "bydextend_avm";
    static final String HELPER_CLASS = "com.byd.extend.StockAvmShellMain";
    static final String DESCRIPTOR = "com.byd.extend.IStockAvmShell";
    static final String CALLBACK_DESCRIPTOR = "com.byd.extend.IStockAvmShellCallback";
    static final String CANCELLATION_DESCRIPTOR = "com.byd.extend.IStockAvmCancellation";
    static final String LOCK_PATH = "/data/local/tmp/bydextend_avm.lock";
    static final String LOG_PATH = "/data/local/tmp/bydextend_avm.log";
    static final int VERSION = 10;
    static final int OPEN_RESULT_OK = 0;
    static final int OPEN_RESULT_BYD_CONFIG_EACCES = 1;

    static final int TX_PING = IBinder.FIRST_CALL_TRANSACTION;
    static final int TX_REGISTER_CALLBACK = IBinder.FIRST_CALL_TRANSACTION + 1;
    static final int TX_OPEN = IBinder.FIRST_CALL_TRANSACTION + 2;
    static final int TX_CLOSE = IBinder.FIRST_CALL_TRANSACTION + 3;
    static final int TX_SHUTDOWN = IBinder.FIRST_CALL_TRANSACTION + 4;
    static final int TX_CONFIGURE_LOGGING = IBinder.FIRST_CALL_TRANSACTION + 5;
    static final int CB_EVENT = IBinder.FIRST_CALL_TRANSACTION;
    static final int TX_IS_CANCELLED = IBinder.FIRST_CALL_TRANSACTION;

    private StockAvmShellProtocol() {}

    static boolean isCallerAllowed(int actualUid, int appUid) {
        return actualUid == appUid || actualUid == 2000;
    }

    static String diagnostic(Throwable error) {
        java.io.StringWriter text = new java.io.StringWriter();
        error.printStackTrace(new java.io.PrintWriter(text));
        return text.toString();
    }

    static boolean isEacces(int errno) { return errno == OsConstants.EACCES; }

    static String errnoName(int errno) {
        if (errno == OsConstants.EACCES) return "EACCES";
        if (errno == OsConstants.ENOENT) return "ENOENT";
        return "errno_" + errno;
    }

    static String errorReference(int shellPid, int requestId, int attempt) {
        return "byd_cloud_stat:" + shellPid + ":" + requestId + ":" + attempt;
    }

    static final class BydConfigAccessDeniedException extends Exception {
        final int requestId;
        final int attempt;
        final int shellUid;
        final int shellPid;
        final int errno;
        final String stage;
        final String operation;
        final String path;
        final String errnoName;
        final String sourceErrorType;
        final String sourceErrorMessage;
        final String errorReference;

        BydConfigAccessDeniedException(int requestId, int attempt, int shellUid, int shellPid,
                int errno, String stage, String operation, String path, String errnoName,
                String sourceErrorType, String sourceErrorMessage, String errorReference) {
            super("BYD AVM config access denied;stage=" + stage + ";operation=" + operation
                    + ";path=" + path + ";errno=" + errno + ";errno_name=" + errnoName
                    + ";request_id=" + requestId + ";attempt=" + attempt
                    + ";shell_uid=" + shellUid + ";shell_pid=" + shellPid
                    + ";source_error=" + sourceErrorType + ": " + sourceErrorMessage
                    + ";error_ref=" + errorReference);
            this.requestId = requestId;
            this.attempt = attempt;
            this.shellUid = shellUid;
            this.shellPid = shellPid;
            this.errno = errno;
            this.stage = stage;
            this.operation = operation;
            this.path = path;
            this.errnoName = errnoName;
            this.sourceErrorType = sourceErrorType;
            this.sourceErrorMessage = sourceErrorMessage;
            this.errorReference = errorReference;
        }
    }
}
