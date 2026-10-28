package com.byd.extend;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.IBinder;
import android.os.Parcel;

/** Fixed Binder contract for the independent shell-UID camera-viewing runtime. */
public final class CameraViewingShellProtocol {
    public static final String SERVICE_NAME = "byd_extend_viewing";
    public static final String PROCESS_NAME = "bydextend_viewing";
    public static final String MAIN_CLASS = "com.byd.extend.CameraViewingShellMain";
    public static final String DESCRIPTOR = "com.byd.extend.ICameraViewingShell";
    public static final String CALLBACK_DESCRIPTOR = "com.byd.extend.ICameraViewingShellCallback";
    public static final String OWNER_PACKAGE = "com.byd.extend";
    public static final String LOCK_PATH = "/data/local/tmp/bydextend_viewing.lock";
    public static final String LOG_PATH = "/data/local/tmp/bydextend_viewing.log";
    public static final String STATE_ROOT = "/data/local/tmp/bydextend_viewing";
    public static final int VERSION = 5;
    public static final int MAX_REQUEST_BYTES = 64 * 1024;
    public static final int MAX_REPLY_BYTES = 256 * 1024;

    static final int TX_PING = IBinder.FIRST_CALL_TRANSACTION;
    static final int TX_REQUEST = IBinder.FIRST_CALL_TRANSACTION + 1;
    static final int TX_SNAPSHOT = IBinder.FIRST_CALL_TRANSACTION + 2;
    static final int TX_OBSERVE = IBinder.FIRST_CALL_TRANSACTION + 3;
    static final int TX_DETACH = IBinder.FIRST_CALL_TRANSACTION + 4;
    static final int CB_SNAPSHOT = IBinder.FIRST_CALL_TRANSACTION;

    private CameraViewingShellProtocol() {}

    public static boolean isOperationAllowed(String operation) {
        return "provision".equals(operation) || "configure".equals(operation)
                || "authorize".equals(operation) || "authorization_opened".equals(operation)
                || "reconnect".equals(operation) || "revoke".equals(operation) || "cancel".equals(operation)
                || "shutdown".equals(operation) || "reconfigured".equals(operation);
    }

    static String currentApkIdentity(Context context) throws Exception {
        if (context == null) throw new IllegalArgumentException("context required");
        ApplicationInfo application = context.getPackageManager()
                .getApplicationInfo(OWNER_PACKAGE, 0);
        long updated = context.getPackageManager().getPackageInfo(OWNER_PACKAGE, 0).lastUpdateTime;
        return application.sourceDir + ":" + updated;
    }

    static Identity readIdentity(IBinder binder) {
        if (binder == null || !binder.isBinderAlive()) return null;
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            if (!binder.transact(TX_PING, data, reply, 0)) return null;
            reply.readException();
            int protocol = reply.readInt();
            int build = reply.readInt();
            int uid = reply.readInt();
            int pid = reply.readInt();
            String apk = reply.readString();
            if (reply.dataAvail() != 0 || uid != 2000 || pid <= 0 || apk == null || apk.isEmpty())
                return null;
            return new Identity(protocol, build, uid, pid, apk);
        } catch (Throwable unavailable) {
            return null;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    static final class Identity {
        final int protocol, build, uid, pid;
        final String apk;

        Identity(int protocol, int build, int uid, int pid, String apk) {
            this.protocol = protocol;
            this.build = build;
            this.uid = uid;
            this.pid = pid;
            this.apk = apk;
        }

        boolean matches(int expectedPid, String expectedApk) {
            return protocol == VERSION && build == BuildConfig.VERSION_CODE && uid == 2000
                    && pid == expectedPid && apk.equals(expectedApk);
        }
    }
}
