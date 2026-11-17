package com.byd.extend;

import android.os.IBinder;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import org.json.JSONObject;
import java.io.*;

final class LogShareShellClient {
    static IBinder available() throws Exception {
        IBinder binder = (IBinder) Class.forName("android.os.ServiceManager")
                .getMethod("getService", String.class).invoke(null, TurnSignalShellProtocol.SERVICE_NAME);
        return TurnSignalController.ping(binder).healthy() ? binder : null;
    }

    static JSONObject call(IBinder binder, int action, String id, JSONObject request, File snapshot, File archive) throws Exception {
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(TurnSignalShellProtocol.DESCRIPTOR);
            data.writeInt(action); data.writeString(id);
            if (action == LogShareShellHost.START) {
                data.writeString(request.toString());
                try (ParcelFileDescriptor fd = ParcelFileDescriptor.open(snapshot, ParcelFileDescriptor.MODE_READ_ONLY)) {
                    fd.writeToParcel(data, 0);
                    transact(binder, data, reply);
                }
            } else transact(binder, data, reply);
            if (action == LogShareShellHost.ARCHIVE) {
                try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.CREATOR.createFromParcel(reply));
                        OutputStream out = new FileOutputStream(archive)) { LogShareFiles.copy(in, out); }
                return new JSONObject();
            }
            return new JSONObject(reply.readString());
        } finally { data.recycle(); reply.recycle(); }
    }

    private static void transact(IBinder binder, Parcel data, Parcel reply) throws Exception {
        if (binder == null || !binder.transact(TurnSignalShellProtocol.TX_LOG_SHARE, data, reply, 0))
            throw new IOException("Log share helper transaction rejected");
        reply.readException();
    }
    private LogShareShellClient() { }
}
