package com.byd.extend;

import android.content.Context;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** Settings access proven usable from the firmware-23 shell process. */
final class AvasShellSettings implements AutoCloseable {
    static final String DIRTY = "byd_extend_avas_dirty";
    static final String SAVED_NAV = "byd_extend_avas_saved_nav";
    static final String SAVED_MUTE = "byd_extend_avas_saved_mute";
    static final int CLEAN = 0;
    static final int EXTERIOR_DIRTY = 1;
    static final int NAVIGATION_DIRTY = 2;
    static final int NAVIGATION_PENDING = 3;
    static final int NAVIGATION_ACTIVE = 4;
    static final int NAVIGATION_REJECTED = 5;
    static final int EXTERIOR_CHANNEL0_DIRTY = 6;
    static final int EXTERIOR_CHANNEL0_UNACQUIRED = 7;
    static final int EXTERIOR_CHANNEL0_SHARED = 8;

    private final IBinder token = new Binder();
    private final Object provider;
    private final Object attribution;
    private final Method call;
    private final ProviderHandle providerHandle;

    AvasShellSettings(Context shellContext) throws Exception {
        Class<?> source = Class.forName("android.content.AttributionSource");
        attribution = invoke(Context.class.getMethod("getAttributionSource"), shellContext);
        call = Class.forName("android.content.IContentProvider").getMethod("call",
                source, String.class, String.class, String.class, Bundle.class);
        Object activityManager = invoke(Class.forName("android.app.ActivityManager")
                .getMethod("getService"), null);
        Class<?> activityManagerType = Class.forName("android.app.IActivityManager");
        Method remove = activityManagerType.getMethod(
                "removeContentProviderExternal", String.class, IBinder.class);
        Object holder = invoke(activityManagerType.getMethod(
                        "getContentProviderExternal", String.class, int.class, IBinder.class, String.class),
                activityManager, "settings", 0, token, "byd-extend-avas");
        if (holder == null) throw new IllegalStateException("SettingsProvider unavailable");
        ProviderHandle acquired = new ProviderHandle(activityManager, remove, token);
        try {
            provider = holder.getClass().getField("provider").get(holder);
            if (provider == null) throw new IllegalStateException("SettingsProvider unavailable");
            providerHandle = acquired;
        } catch (Exception failure) {
            try {
                acquired.close();
            } catch (Exception cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    int getInt(String key, int fallback) throws Exception {
        Bundle result = request("GET_global", key, null);
        String value = result == null ? null : result.getString("value");
        if (value == null) return fallback;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    void putInt(String key, int value) throws Exception {
        request("PUT_global", key, Integer.toString(value));
    }

    private Bundle request(String method, String key, String value) throws Exception {
        Bundle extras = new Bundle();
        extras.putInt("_user", 0);
        if (value != null) extras.putString("value", value);
        return (Bundle) invoke(call, provider, attribution, "settings", method, key, extras);
    }

    @Override
    public void close() throws Exception {
        providerHandle.close();
    }

    static final class ProviderHandle implements AutoCloseable {
        private final Object activityManager;
        private final Method remove;
        private final IBinder token;
        private boolean closed;

        ProviderHandle(Object activityManager, Method remove, IBinder token) {
            this.activityManager = activityManager;
            this.remove = remove;
            this.token = token;
        }

        @Override
        public synchronized void close() throws Exception {
            if (closed) return;
            closed = true;
            invoke(remove, activityManager, "settings", token);
        }
    }

    private static Object invoke(Method method, Object target, Object... arguments) throws Exception {
        try {
            return method.invoke(target, arguments);
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw failure;
        }
    }
}
