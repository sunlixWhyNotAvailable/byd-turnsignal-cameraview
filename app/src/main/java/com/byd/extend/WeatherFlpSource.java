package com.byd.extend;

import android.content.Context;
import android.location.Location;
import dalvik.system.PathClassLoader;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Optional app-UID receiver; never binds with a shell/helper identity. */
final class WeatherFlpSource {
    interface Listener {
        void locations(Location[] locations);
        void started();
        void failed(String operation, Throwable error);
        void cleanupFailed(String operation, Throwable error);
    }

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "weather-flp");
        thread.setDaemon(true);
        return thread;
    });
    private final Listener listener;
    private volatile boolean stopped;
    private Object manager, service, handle;
    private Class<?> managerType, serviceType, handleType;

    WeatherFlpSource(Listener listener) { this.listener = listener; }

    void start(Context application) {
        WORKER.execute(() -> {
            String operation = "load_sdk";
            try {
                if (stopped) return;
                ClassLoader loader = new PathClassLoader("/system_ext/framework/com.qti.location.sdk.jar",
                        application.getClassLoader());
                managerType = loader.loadClass("com.qti.location.sdk.IZatManager");
                serviceType = loader.loadClass("com.qti.location.sdk.IZatFlpService");
                handleType = loader.loadClass("com.qti.location.sdk.IZatFlpService$IZatFlpSessionHandle");
                Class<?> requestType = loader.loadClass("com.qti.location.sdk.IZatFlpService$IzatFlpRequest");
                Class<?> callbackType = loader.loadClass("com.qti.location.sdk.IZatFlpService$IFlpLocationCallback");
                operation = "get_manager";
                manager = managerType.getMethod("getInstance", Context.class).invoke(null, application);
                if (stopped) return;
                operation = "connect_service";
                service = managerType.getMethod("connectFlpService").invoke(manager);
                if (service == null) throw new IllegalStateException("connectFlpService returned null");
                if (stopped) return;
                Object request = requestType.getMethod("getForegroundFlprequest").invoke(null);
                requestType.getMethod("setTimeInterval", long.class).invoke(request, 1000L);
                Object callback = Proxy.newProxyInstance(loader, new Class<?>[]{callbackType}, (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
                        if (method.getName().equals("equals")) return proxy == args[0];
                        return "WeatherFlpCallback";
                    }
                    if (!stopped && method.getName().equals("onLocationAvailable")
                            && args != null && args.length > 0 && args[0] instanceof Location[]) {
                        listener.locations((Location[]) args[0]);
                    }
                    return null;
                });
                operation = "start_session";
                handle = serviceType.getMethod("startFlpSession", callbackType, requestType)
                        .invoke(service, callback, request);
                if (handle == null) throw new IllegalStateException("startFlpSession returned null");
                if (!stopped) listener.started();
            } catch (Exception | LinkageError error) {
                stopped = true;
                listener.failed(operation, unwrap(error));
            } finally {
                if (stopped) cleanup();
            }
        });
    }

    void stop() {
        stopped = true;
        // Serialized with start: a handle returned after OFF is still released by its owner.
        WORKER.execute(this::cleanup);
    }

    private void cleanup() {
        try {
            if (handle != null) serviceType.getMethod("stopFlpSession", handleType).invoke(service, handle);
        } catch (Exception | LinkageError error) {
            listener.cleanupFailed("stop_session", unwrap(error));
        } finally {
            handle = null;
            try {
                if (service != null) managerType.getMethod("disconnectFlpService", serviceType).invoke(manager, service);
            } catch (Exception | LinkageError error) {
                listener.cleanupFailed("disconnect_service", unwrap(error));
            } finally {
                service = null;
            }
        }
    }

    static Throwable unwrap(Throwable error) {
        return error instanceof InvocationTargetException && error.getCause() != null ? error.getCause() : error;
    }
}
