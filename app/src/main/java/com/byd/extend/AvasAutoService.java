package com.byd.extend;

import android.os.IBinder;

import java.util.concurrent.Callable;

/** Shared lookup only; each caller retains its own Binder cache and synchronization. */
final class AvasAutoService {
    private AvasAutoService() {}

    static IBinder resolve(IBinder cached) throws Exception {
        return resolve(cached, () -> (IBinder) Class.forName("android.os.ServiceManager")
                .getMethod("getService", String.class).invoke(null, "autoservice"));
    }

    static IBinder resolve(IBinder cached, Callable<IBinder> lookup) throws Exception {
        if (cached != null && cached.isBinderAlive()) return cached;
        IBinder service = lookup.call();
        if (service == null) throw new IllegalStateException("autoservice unavailable");
        return service;
    }
}
