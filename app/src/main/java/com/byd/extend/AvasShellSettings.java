package com.byd.extend;

import android.content.Context;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** Settings access proven usable from the firmware-23 shell process. */
final class AvasShellSettings {
    static final String DIRTY = "byd_extend_avas_dirty";
    static final String SAVED_NAV = "byd_extend_avas_saved_nav";
    static final String SAVED_MUTE = "byd_extend_avas_saved_mute";
    static final int CLEAN = 0;
    static final int EXTERIOR_DIRTY = 1;
    static final int NAVIGATION_DIRTY = 2;

    private final IBinder token = new Binder();
    private final Object provider;
    private final Object attribution;
    private final Method call;

    AvasShellSettings(Context shellContext) throws Exception {
        Class<?> source = Class.forName("android.content.AttributionSource");
        attribution = invoke(Context.class.getMethod("getAttributionSource"), shellContext);
        call = Class.forName("android.content.IContentProvider").getMethod("call",
                source, String.class, String.class, String.class, Bundle.class);
        Object activityManager = invoke(Class.forName("android.app.ActivityManager")
                .getMethod("getService"), null);
        Object holder = invoke(Class.forName("android.app.IActivityManager").getMethod(
                        "getContentProviderExternal", String.class, int.class, IBinder.class, String.class),
                activityManager, "settings", 0, token, "byd-extend-avas");
        if (holder == null) throw new IllegalStateException("SettingsProvider unavailable");
        provider = holder.getClass().getField("provider").get(holder);
        if (provider == null) throw new IllegalStateException("SettingsProvider unavailable");
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
