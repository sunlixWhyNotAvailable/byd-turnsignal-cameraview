package com.byd.extend;

import android.os.IBinder;
import android.os.Parcel;
import com.byd.extend.artwork.ArtworkDirectPolicy;
import com.byd.extend.artwork.ArtworkFiles;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;

/** Uses the broker's real shell identity, without cellsservice_host or provider writes. */
final class MusicArtworkDirect {
    static final class Failure extends IOException {
        Failure(String operation, Throwable cause) { super(operation, cause); }
    }
    final String vm, singleOs, hostRoot;
    private final int user;
    private final String prefix;
    private final IBinder binder;

    MusicArtworkDirect(int user, String prefix) throws Failure {
        this.user = user; this.prefix = prefix;
        try {
            if (android.os.Process.myUid() != 2000) throw new SecurityException("Direct artwork requires shell UID2000");
            Class<?> properties = Class.forName("android.os.SystemProperties");
            vm = (String) properties.getMethod("get", String.class).invoke(null, "ro.boot.vm.name");
            singleOs = (String) properties.getMethod("get", String.class).invoke(null, "ro.build.system.fission_single_os");
            hostRoot = ArtworkDirectPolicy.hostMediaRoot(vm, singleOs);
            binder = (IBinder) Class.forName("android.os.ServiceManager").getMethod("checkService", String.class)
                    .invoke(null, "auto_container");
            if (binder == null) throw new IOException("auto_container lookup returned null; absence/access denial not distinguished");
            if (!"android.os.IAutoContainer".equals(binder.getInterfaceDescriptor()))
                throw new IOException("Unexpected auto_container descriptor");
        } catch (Exception failure) { throw failure("direct_artwork_initialize", failure); }
    }

    void send(String local) throws Failure {
        Parcel request = Parcel.obtain(), reply = Parcel.obtain();
        try {
            if (android.os.Process.myUid() != 2000 || android.os.Binder.getCallingUid() != 2000)
                throw new SecurityException("Direct artwork send must execute as shell without an incoming Binder caller");
            String path = "default";
            if (local != null) {
                path = ArtworkDirectPolicy.hostPath(hostRoot, user, prefix, local);
                ArtworkFiles.regular(new File(local));
                if (!new File(local).canRead()) throw new IOException("Own artwork PNG is not readable");
            }
            request.writeInterfaceToken("android.os.IAutoContainer");
            request.writeInt(2000); request.writeInt(0); request.writeString(path);
            if (!binder.transact(2, request, reply, 0)) throw new IOException("auto_container transaction2 rejected");
            reply.readException();
            int result = reply.readInt();
            if (result != 1) throw new IOException("auto_container sendInfo type2000 result=" + result);
        } catch (Exception failure) { throw failure("direct_artwork_send", failure); }
        finally { request.recycle(); reply.recycle(); }
    }

    private static Failure failure(String operation, Exception error) {
        Throwable cause = error instanceof InvocationTargetException && error.getCause() != null ? error.getCause() : error;
        return new Failure(operation, cause);
    }
}
