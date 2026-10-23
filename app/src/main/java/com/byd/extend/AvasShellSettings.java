package com.byd.extend;

import android.content.Context;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Process;

import org.json.JSONObject;

import java.io.PrintWriter;
import java.io.StringWriter;
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
    // Persisted 7/8 own only shared preparation, independent of the primary device.
    static final int EXTERIOR_UNACQUIRED = 7;
    static final int EXTERIOR_SHARED = 8;
    // Never reinterpret marker 6: interrupted CHANNEL0 sessions still require device 1000.
    static final int EXTERIOR_DEVICE3_DIRTY = 9;

    private final IBinder token = new Binder();
    private final Object provider;
    private final Object attribution;
    private final String callingPackage;
    private final String callSignature;
    private final boolean attributionCall;
    private final Method call;
    private final ProviderHandle providerHandle;

    AvasShellSettings(Context shellContext) throws Exception {
        String pkg = "unavailable";
        String signature = "unselected";
        String operation = "get_shell_package";
        ProviderHandle acquired = null;
        try {
            pkg = shellContext.getPackageName();
            operation = "resolve_provider_interface";
            Class<?> providerType = Class.forName("android.content.IContentProvider");

            operation = "resolve_provider_call_signature";
            Method selectedCall;
            Method attributionGetter = null;
            boolean useAttribution;
            try {
                Class<?> source = Class.forName("android.content.AttributionSource");
                attributionGetter = Context.class.getMethod("getAttributionSource");
                selectedCall = providerType.getMethod("call", source,
                        String.class, String.class, String.class, Bundle.class);
                signature = "call(android.content.AttributionSource, java.lang.String, "
                        + "java.lang.String, java.lang.String, android.os.Bundle)";
                useAttribution = true;
            } catch (ClassNotFoundException | NoSuchMethodException modernUnavailable) {
                operation = "resolve_legacy_provider_call_signature";
                try {
                    selectedCall = providerType.getMethod("call", String.class,
                            String.class, String.class, String.class, Bundle.class);
                } catch (Exception | Error legacyUnavailable) {
                    if (legacyUnavailable != modernUnavailable) {
                        try {
                            legacyUnavailable.addSuppressed(modernUnavailable);
                        } catch (Throwable ignored) { }
                    }
                    throw legacyUnavailable;
                }
                signature = "call(java.lang.String callingPkg, java.lang.String authority, "
                        + "java.lang.String method, java.lang.String arg, android.os.Bundle extras)";
                useAttribution = false;
            }

            callingPackage = pkg;
            callSignature = signature;
            attributionCall = useAttribution;
            call = selectedCall;
            operation = "select_provider_call";
            logDiagnostic("avas_shell_settings_signature", operation,
                    callSignature, callingPackage, null, null);

            operation = "getAttributionSource";
            Object selectedAttribution = useAttribution
                    ? invoke(attributionGetter, shellContext) : null;
            attribution = selectedAttribution;

            operation = "resolve_activity_manager";
            Object activityManager = invoke(Class.forName("android.app.ActivityManager")
                    .getMethod("getService"), null);
            Class<?> activityManagerType = Class.forName("android.app.IActivityManager");
            Method remove = activityManagerType.getMethod(
                    "removeContentProviderExternal", String.class, IBinder.class);

            operation = "getContentProviderExternal";
            Object holder = invoke(activityManagerType.getMethod(
                            "getContentProviderExternal", String.class, int.class, IBinder.class, String.class),
                    activityManager, "settings", 0, token, "byd-extend-avas");
            if (holder == null) throw new IllegalStateException("SettingsProvider unavailable");

            acquired = new ProviderHandle(activityManager, remove, token);
            operation = "read_external_provider";
            Object installedProvider = holder.getClass().getField("provider").get(holder);
            if (installedProvider == null) {
                throw new IllegalStateException("SettingsProvider unavailable");
            }
            provider = installedProvider;
            providerHandle = acquired;
        } catch (Exception | Error failure) {
            if (acquired != null) {
                try {
                    acquired.close();
                } catch (Throwable cleanupFailure) {
                    if (cleanupFailure != failure) {
                        try {
                            failure.addSuppressed(cleanupFailure);
                        } catch (Throwable ignored) { }
                    }
                    logDiagnostic("avas_shell_settings_error", "removeContentProviderExternal",
                            signature, pkg, null, cleanupFailure);
                }
            }
            logDiagnostic("avas_shell_settings_error", operation, signature,
                    pkg, null, failure);
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
        try {
            Bundle extras = new Bundle();
            extras.putInt("_user", 0);
            if (value != null) extras.putString("value", value);
            return (Bundle) invoke(call, provider,
                    attributionCall ? attribution : callingPackage,
                    "settings", method, key, extras);
        } catch (Exception | Error failure) {
            logFailure("IContentProvider.call/" + method, key, failure);
            throw failure;
        }
    }

    @Override
    public void close() throws Exception {
        try {
            providerHandle.close();
        } catch (Exception | Error failure) {
            logFailure("removeContentProviderExternal", null, failure);
            throw failure;
        }
    }

    private void logFailure(String operation, String key, Throwable failure) {
        logDiagnostic("avas_shell_settings_error", operation, callSignature,
                callingPackage, key, failure);
    }

    private static void logDiagnostic(String kind, String operation, String signature,
            String callingPackage, String key, Throwable failure) {
        try {
            JSONObject event = new JSONObject()
                    .put("kind", kind)
                    .put("source", "AvasShellSettings")
                    .put("operation", operation)
                    .put("signature", signature)
                    .put("uid", Process.myUid())
                    .put("package", callingPackage == null ? "unavailable" : callingPackage)
                    .put("user", 0)
                    .put("provider_user", 0);
            if (key != null) event.put("key", key);
            if (failure != null) {
                StringWriter stack = new StringWriter();
                failure.printStackTrace(new PrintWriter(stack));
                // Deduplication keys on error; include every cause, not just the wrapper.
                event.put("error", "signature=" + signature + "; " + stack)
                        .put("error_type", failure.getClass().getName())
                        .put("error_message", String.valueOf(failure.getMessage()));
            }
            DiagnosticLogPolicy.print(event.toString());
        } catch (Throwable ignored) {
            // Diagnostic output must never replace a provider failure.
        }
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
