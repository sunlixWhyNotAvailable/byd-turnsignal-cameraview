package com.byd.extend;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Process;
import android.os.UserHandle;

import org.json.JSONException;
import org.json.JSONObject;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** Shell-side client for the app-owned, narrowly scoped camera mode provider. */
public final class CameraModeClient implements AutoCloseable {
    private final Context shellRuntimeContext;

    /** Stores only the shell runtime Context; provider acquisition is per operation. */
    public CameraModeClient(Context shellRuntimeContext) {
        if (shellRuntimeContext == null) throw new IllegalArgumentException("shell context required");
        this.shellRuntimeContext = shellRuntimeContext;
    }

    public JSONObject snapshot() throws Exception {
        try {
            return readSnapshot(request(CameraModeSettings.METHOD_SNAPSHOT, null, null));
        } catch (ModeException failure) {
            throw failure;
        } catch (Throwable failure) {
            throw new ModeException("modes_unavailable", null, failure);
        }
    }

    public JSONObject setMode(String mode, boolean enabled) throws Exception {
        if (!CameraModeSettings.MODE_TABLET.equals(mode)
                && !CameraModeSettings.MODE_RECORDING.equals(mode))
            throw new ModeException("invalid_mode", null,
                    new IllegalArgumentException("mode must be tablet or recording"));
        Bundle extras = new Bundle();
        extras.putBoolean("enabled", enabled);
        return write(CameraModeSettings.METHOD_SET, mode, extras);
    }

    public JSONObject setSetting(String setting, Object value) throws Exception {
        CameraModeSettings.validateSetting(setting, value);
        Bundle extras = new Bundle();
        if (value instanceof Boolean) extras.putBoolean("value", (Boolean) value);
        else extras.putInt("value", (Integer) value);
        return write(CameraModeSettings.METHOD_SET_SETTING, setting, extras);
    }

    private JSONObject write(String method, String name, Bundle extras) throws Exception {
        final Bundle response;
        try {
            response = request(method, name, extras);
        } catch (ModeException failure) {
            throw failure;
        } catch (Throwable failure) {
            throw new ModeException("modes_unavailable", null, failure);
        }
        String requestError = optionalString(response, "requestError");
        if (requestError != null && !requestError.isEmpty())
            throw new ModeException(requestError, optionalString(response, "errorRef"),
                    new IllegalStateException("app rejected camera mode update"));
        try {
            return readSnapshot(response);
        } catch (ModeException failure) {
            throw failure;
        } catch (Throwable failure) {
            throw new ModeException("modes_unavailable", optionalString(response, "errorRef"), failure);
        }
    }

    private Bundle request(String method, String mode, Bundle extras) throws ModeException {
        try {
            try (ExternalProvider provider = new ExternalProvider(shellRuntimeContext)) {
                return provider.call(method, mode, extras);
            }
        } catch (Throwable failure) {
            throw new ModeException("modes_unavailable", null, failure);
        }
    }

    private static JSONObject readSnapshot(Bundle response) throws JSONException, ModeException {
        if (response == null || !(response.get("available") instanceof Boolean)
                || !response.getBoolean("available"))
            throw new ModeException("modes_unavailable", optionalString(response, "errorRef"),
                    new IllegalStateException("app mode snapshot is unavailable"));
        Object tablet = response.get("tabletEnabled");
        Object recording = response.get("recordingEnabled");
        Object mirror = response.get(CameraModeSettings.SETTING_MIRROR_CABIN);
        Object sensitivity = response.get(CameraModeSettings.SETTING_RECORDING_SENSITIVITY);
        Object stateValue = response.get("applyState");
        if (!(tablet instanceof Boolean) || !(recording instanceof Boolean)
                || !(mirror instanceof Boolean) || !(sensitivity instanceof Integer)
                || (Integer) sensitivity < 0 || (Integer) sensitivity > 2 || !(stateValue instanceof String))
            throw new ModeException("modes_unavailable", optionalString(response, "errorRef"),
                    new IllegalStateException("app mode snapshot is incomplete"));
        String state = (String) stateValue;
        if (!"applied".equals(state) && !"pending".equals(state) && !"error".equals(state))
            throw new ModeException("modes_unavailable", optionalString(response, "errorRef"),
                    new IllegalStateException("app mode snapshot has an unknown apply state"));
        JSONObject result = new JSONObject()
                .put("available", true)
                .put("tabletEnabled", (Boolean) tablet)
                .put("recordingEnabled", (Boolean) recording)
                .put(CameraModeSettings.SETTING_MIRROR_CABIN, mirror)
                .put(CameraModeSettings.SETTING_RECORDING_SENSITIVITY, sensitivity)
                .put("applyState", state);
        String error = optionalString(response, "error");
        String errorRef = optionalString(response, "errorRef");
        if (error != null) result.put("error", error);
        if (errorRef != null) result.put("errorRef", errorRef);
        return result;
    }

    private static String optionalString(Bundle bundle, String key) {
        if (bundle == null) return null;
        Object value = bundle.get(key);
        return value instanceof String ? (String) value : null;
    }

    @Override public void close() { }

    /** Stable transport code plus the original exception chain and optional app diagnostic ref. */
    public static final class ModeException extends Exception {
        private final String code;
        private final String errorRef;

        public ModeException(String code, String errorRef, Throwable cause) {
            super("camera mode operation failed: " + code
                    + (errorRef == null ? "" : " (diagnostic ref " + errorRef + ")"), cause);
            if (code == null || code.isEmpty()) throw new IllegalArgumentException("error code required");
            this.code = code;
            this.errorRef = errorRef;
        }

        public String code() { return code; }
        public String errorRef() { return errorRef; }
    }

    /** One temporary external-provider reference. It is never cached or replayed after failure. */
    private static final class ExternalProvider implements AutoCloseable {
        private final Object provider;
        private final Object attribution;
        private final Object activityManager;
        private final Method removeProvider;
        private final IBinder token;
        private final Method call;
        private boolean closed;

        ExternalProvider(Context shellContext) throws Exception {
            if (Process.myUid() != 2000)
                throw new SecurityException("camera mode client must run as the shell helper");
            Class<?> attributionType = Class.forName("android.content.AttributionSource");
            attribution = invoke(Context.class.getMethod("getAttributionSource"), shellContext);
            if (attribution == null) throw new SecurityException("shell AttributionSource unavailable");
            int attributedUid = (Integer) invoke(attributionType.getMethod("getUid"), attribution);
            if (attributedUid != Process.myUid())
                throw new SecurityException("camera mode AttributionSource UID does not match shell process");

            Class<?> providerType = Class.forName("android.content.IContentProvider");
            call = providerType.getMethod("call", attributionType, String.class, String.class,
                    String.class, Bundle.class);
            token = new Binder();
            Class<?> managerType = Class.forName("android.app.IActivityManager");
            activityManager = invoke(Class.forName("android.app.ActivityManager")
                    .getMethod("getService"), null);
            Method acquire = managerType.getMethod("getContentProviderExternal",
                    String.class, int.class, IBinder.class, String.class);
            removeProvider = managerType.getMethod("removeContentProviderExternal",
                    String.class, IBinder.class);

            ApplicationInfo owner = shellContext.getPackageManager().getApplicationInfo(
                    CameraViewingShellProtocol.OWNER_PACKAGE, 0);
            int ownerUser = (Integer) invoke(UserHandle.class.getMethod("getUserId", int.class),
                    null, owner.uid);
            Object holder = invoke(acquire, activityManager, CameraModeSettings.AUTHORITY,
                    ownerUser, token, "byd-extend-camera-modes");
            if (holder == null) throw new IllegalStateException("camera mode provider unavailable");
            try {
                Field providerField = holder.getClass().getField("provider");
                provider = providerField.get(holder);
                if (provider == null) throw new IllegalStateException("camera mode provider unavailable");
            } catch (Throwable failure) {
                try { invoke(removeProvider, activityManager, CameraModeSettings.AUTHORITY, token); }
                catch (Throwable cleanupFailure) { failure.addSuppressed(cleanupFailure); }
                if (failure instanceof Exception) throw (Exception) failure;
                if (failure instanceof Error) throw (Error) failure;
                throw new IllegalStateException("camera mode provider lookup failed", failure);
            }
        }

        Bundle call(String method, String mode, Bundle extras) throws Exception {
            if (closed) throw new IllegalStateException("camera mode provider handle is closed");
            return (Bundle) invoke(call, provider, attribution,
                    CameraModeSettings.AUTHORITY, method, mode, extras);
        }

        @Override public void close() throws Exception {
            if (closed) return;
            closed = true;
            invoke(removeProvider, activityManager, CameraModeSettings.AUTHORITY, token);
        }
    }

    private static Object invoke(Method method, Object target, Object... args) throws Exception {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw failure;
        }
    }
}
