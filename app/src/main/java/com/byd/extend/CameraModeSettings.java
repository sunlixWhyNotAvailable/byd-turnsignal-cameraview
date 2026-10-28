package com.byd.extend;

import android.Manifest;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Process;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.UUID;

/** App-owned, DUMP-protected persistence endpoint for shared camera preferences. */
public final class CameraModeSettings extends ContentProvider {
    public static final String AUTHORITY = "com.byd.extend.camera_modes";
    public static final String METHOD_SNAPSHOT = "snapshot";
    public static final String METHOD_SET = "set";
    public static final String METHOD_SET_SETTING = "setSetting";
    public static final String SETTING_MIRROR_CABIN = "mirrorCabin";
    public static final String SETTING_RECORDING_SENSITIVITY = "recordingSensitivity";
    public static final String MODE_TABLET = "tablet";
    public static final String MODE_RECORDING = "recording";

    private static final String TAG = "CameraModeSettings";
    private static final String KEY_ENABLED = "enabled";

    @Override public boolean onCreate() { return true; }

    @Override public Bundle call(String method, String arg, Bundle extras) {
        verifyCaller();
        long callerIdentity = Binder.clearCallingIdentity();
        try {
            if (METHOD_SNAPSHOT.equals(method)) {
                if (arg != null || extras != null && !extras.isEmpty())
                    throw new IllegalArgumentException("snapshot accepts no arguments");
                return snapshot(getContext());
            }
            boolean setting = METHOD_SET_SETTING.equals(method);
            if (!METHOD_SET.equals(method) && !setting)
                throw new IllegalArgumentException("unsupported camera mode operation");
            String valueKey = setting ? "value" : KEY_ENABLED;
            if (extras == null || extras.size() != 1 || !extras.containsKey(valueKey))
                throw new IllegalArgumentException("set requires exactly one " + valueKey + " value");
            Object value = extras.get(valueKey);
            if (setting) validateSetting(arg, value);
            else if (!(value instanceof Boolean))
                throw new IllegalArgumentException("set requires one boolean enabled value");
            try {
                if (setting) {
                    persistSettingPreference(getContext(), arg, value, "html");
                    if (SETTING_RECORDING_SENSITIVITY.equals(arg))
                        MotionRuntime.settingsChanged(getContext());
                } else MotionRuntime.setModeEnabled(getContext(), arg, (Boolean) value);
                return snapshot(getContext());
            } catch (CameraModeClient.ModeException failure) {
                String reference = UUID.randomUUID().toString();
                Bundle result;
                try {
                    result = snapshot(getContext());
                } catch (Throwable snapshotFailure) {
                    failure.addSuppressed(snapshotFailure);
                    result = new Bundle();
                    result.putBoolean("available", false);
                    result.putString("applyState", "error");
                }
                Log.e(TAG, "camera_setting_set_failed operation=" + method + " setting=" + arg
                        + " code=" + failure.code()
                        + " reference=" + reference, failure);
                com.byd.extend.remote.CameraViewingDiagnostics.initializeApp(getContext());
                com.byd.extend.remote.CameraViewingDiagnostics.failure(
                        "camera_setting_set_failed_" + reference, "shared", failure);
                result.putString("requestError", failure.code());
                result.putString("errorRef", reference);
                return result;
            }
        } finally {
            Binder.restoreCallingIdentity(callerIdentity);
        }
    }

    /** Strict value types are shared by HTTP intake, Binder and local persistence. */
    public static void validateSetting(String setting, Object value) {
        if (SETTING_MIRROR_CABIN.equals(setting) && value instanceof Boolean) return;
        if (SETTING_RECORDING_SENSITIVITY.equals(setting) && value instanceof Integer
                && (Integer) value >= 0 && (Integer) value <= 2) return;
        throw new IllegalArgumentException("invalid camera setting/value: " + setting);
    }

    public static void setRecordingSensitivity(Context context, int value)
            throws CameraModeClient.ModeException {
        persistSettingPreference(context, SETTING_RECORDING_SENSITIVITY, value);
        MotionRuntime.settingsChanged(context);
    }

    public static boolean persistSettingPreference(Context context, String setting, Object value)
            throws CameraModeClient.ModeException {
        return persistSettingPreference(context, setting, value, "android");
    }

    private static boolean persistSettingPreference(Context context, String setting, Object value,
            String source) throws CameraModeClient.ModeException {
        if (context == null) throw modeFailure("invalid_request",
                new IllegalArgumentException("context required"));
        try {
            validateSetting(setting, value);
            boolean mirror = SETTING_MIRROR_CABIN.equals(setting);
            SharedPreferences preferences = context.getSharedPreferences(
                    mirror ? "settings" : MotionConfig.SCREEN_PREFS, Context.MODE_PRIVATE);
            String key = mirror ? RemoteCameraView.MIRROR_CABIN_PREFERENCE_KEY
                    : MotionConfig.KEY_RECORDING_SENSITIVITY;
            Object previous = mirror ? preferences.getBoolean(key, false)
                    : preferences.getInt(key, MotionConfig.DEFAULT_RECORDING_SENSITIVITY);
            if (previous.equals(value)) return false;
            SharedPreferences.Editor editor = preferences.edit();
            if (mirror) editor.putBoolean(key, (Boolean) value);
            else editor.putInt(key, (Integer) value);
            if (!editor.commit()) throw new IllegalStateException("SharedPreferences commit returned false");
            com.byd.extend.remote.CameraViewingDiagnostics.initializeApp(context);
            com.byd.extend.remote.CameraViewingDiagnostics.detail("camera_setting_saved", "shared",
                    "setting=" + setting + " old=" + previous + " new=" + value + " source=" + source);
            return true;
        } catch (IllegalArgumentException failure) {
            throw modeFailure("invalid_setting", failure);
        } catch (RuntimeException failure) {
            throw modeFailure("persistence_failed", failure);
        }
    }

    /** Shared local/remote write path. The editor touches only the selected mode key. */
    static boolean persistModePreference(Context context, String mode, boolean enabled)
            throws CameraModeClient.ModeException {
        if (context == null) throw modeFailure("invalid_request",
                new IllegalArgumentException("context required"));
        String preferencesName;
        if (MODE_TABLET.equals(mode)) preferencesName = MotionConfig.SCREEN_PREFS;
        else if (MODE_RECORDING.equals(mode)) preferencesName = MotionConfig.RECORDING_PREFS;
        else throw modeFailure("invalid_mode", new IllegalArgumentException("unsupported mode"));

        Context application = context.getApplicationContext();
        Context target = application == null ? context : application;
        if (MODE_RECORDING.equals(mode) && enabled
                && !com.byd.extend.ui.motion.MotionRecordingModelKt
                        .motionRecordingStoragePermissionGranted(target))
            throw modeFailure("permission_required",
                    new SecurityException("Motion recording storage permission is not granted"));

        SharedPreferences preferences = target.getSharedPreferences(preferencesName, Context.MODE_PRIVATE);
        if (preferences.getBoolean(KEY_ENABLED, false) == enabled) return false;
        if (!preferences.edit().putBoolean(KEY_ENABLED, enabled).commit())
            throw modeFailure("persistence_failed",
                    new IllegalStateException("SharedPreferences commit returned false"));
        return true;
    }

    private static Bundle snapshot(Context context) {
        MotionConfig config = MotionConfig.read(context);
        Bundle result = new Bundle();
        result.putBoolean("available", true);
        result.putBoolean("tabletEnabled", config.warningEnabled);
        result.putBoolean("recordingEnabled", config.recordingEnabled);
        result.putBoolean(SETTING_MIRROR_CABIN, context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getBoolean(RemoteCameraView.MIRROR_CABIN_PREFERENCE_KEY, false));
        result.putInt(SETTING_RECORDING_SENSITIVITY, config.recordingSensitivity);
        try {
            JSONObject status = MotionRuntime.applyStatus(config.toJson());
            result.putString("applyState", status.getString("applyState"));
            if (status.has("error")) result.putString("error", status.getString("error"));
            if (status.has("errorRef")) result.putString("errorRef", status.getString("errorRef"));
        } catch (JSONException failure) {
            throw new IllegalStateException("invalid Motion apply status", failure);
        }
        return result;
    }

    private void verifyCaller() {
        Context context = getContext();
        if (context == null) throw new IllegalStateException("camera mode provider is not attached");
        context.enforceCallingPermission(Manifest.permission.DUMP,
                "Camera mode access requires DUMP permission");
        int callingUid = Binder.getCallingUid();
        int callingPid = Binder.getCallingPid();
        try {
            if (callingUid != 2000 || callingPid <= 0)
                throw new SecurityException("camera mode caller is not the shell viewing helper");
            ApplicationInfo app = context.getPackageManager().getApplicationInfo(
                    CameraViewingShellProtocol.OWNER_PACKAGE, 0);
            if (app.uid != Process.myUid())
                throw new SecurityException("Extend provider process UID does not match its package");
            String apkIdentity = CameraViewingShellProtocol.currentApkIdentity(context);
            IBinder current = (IBinder) Class.forName("android.os.ServiceManager")
                    .getMethod("getService", String.class)
                    .invoke(null, CameraViewingShellProtocol.SERVICE_NAME);
            CameraViewingShellProtocol.Identity identity = readCurrentHelperIdentity(current);
            if (!identity.matches(identity.pid, apkIdentity)
                    || identity.pid != callingPid)
                throw new SecurityException("camera mode caller does not match the current APK-verified viewing helper");
        } catch (Exception failure) {
            Log.w(TAG, "camera_mode_caller_denied uid=" + callingUid + " pid=" + callingPid, failure);
            if (failure instanceof SecurityException) throw (SecurityException) failure;
            throw new SecurityException("camera mode caller identity verification failed", failure);
        }
    }

    private static CameraViewingShellProtocol.Identity readCurrentHelperIdentity(IBinder helper)
            throws Exception {
        if (helper == null || !helper.isBinderAlive())
            throw new SecurityException("current viewing helper Binder is unavailable");
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(CameraViewingShellProtocol.DESCRIPTOR);
            if (!helper.transact(CameraViewingShellProtocol.TX_PING, data, reply, 0))
                throw new IllegalStateException("current viewing helper rejected identity ping");
            reply.readException();
            int protocol = reply.readInt();
            int build = reply.readInt();
            int uid = reply.readInt();
            int pid = reply.readInt();
            String helperApk = reply.readString();
            if (reply.dataAvail() != 0 || uid != 2000 || pid <= 0
                    || helperApk == null || helperApk.isEmpty())
                throw new SecurityException("current viewing helper returned an invalid identity");
            return new CameraViewingShellProtocol.Identity(protocol, build, uid, pid, helperApk);
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private static CameraModeClient.ModeException modeFailure(String code, Throwable cause) {
        return new CameraModeClient.ModeException(code, null, cause);
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection,
            String[] selectionArgs, String sortOrder) {
        throw new UnsupportedOperationException("camera mode provider supports call only");
    }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("camera mode provider supports call only");
    }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("camera mode provider supports call only");
    }
    @Override public int update(Uri uri, ContentValues values, String selection,
            String[] selectionArgs) {
        throw new UnsupportedOperationException("camera mode provider supports call only");
    }
}
