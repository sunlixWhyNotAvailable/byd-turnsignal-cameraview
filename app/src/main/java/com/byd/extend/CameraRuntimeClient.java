package com.byd.extend;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Parcel;
import android.provider.Settings;
import java.util.function.Consumer;

/** Reconnectable UI facade. It never owns a video source, controller, or overlay Surface. */
final class CameraRuntimeClient {
    private final Context context;
    private final Handler handler;
    private final Consumer<String> events;
    private final SharedPreferences settings;
    private IBinder shell;
    private IBinder camera;
    private volatile boolean visible;
    private volatile boolean preview;
    private volatile boolean detached;
    private java.util.Map<String, Object> lastSettings = new java.util.HashMap<>();
    private final SharedPreferences.OnSharedPreferenceChangeListener preferenceListener =
            (prefs, key) -> scheduleSettingsSync();
    private final Runnable preferenceSync = this::syncPreferenceEdits;
    private final Binder callback = new Binder() {
        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
            if (Binder.getCallingUid() != 2000) throw new SecurityException("camera runtime sender denied");
            data.enforceInterface(CameraRuntimeHost.CALLBACK);
            if (code == CameraRuntimeHost.EVENT) {
                String line = data.readString();
                handler.post(() -> { if (!detached) events.accept(line); });
            } else if (code == CameraRuntimeHost.PREFERENCES) {
                data.readBundle(getClass().getClassLoader());
                handler.post(() -> settingsChanged("sync"));
            } else return false;
            return true;
        }
    };

    CameraRuntimeClient(Context context, Handler handler, Consumer<String> events) {
        this.context = context.getApplicationContext();
        this.handler = handler;
        this.events = events;
        settings = context.getSharedPreferences("settings", Context.MODE_PRIVATE);
        settings.registerOnSharedPreferenceChangeListener(preferenceListener);
    }

    synchronized boolean connect() {
        if (detached) return false;
        if (camera != null && camera.isBinderAlive()) return true;
        try {
            IBinder candidate = (IBinder) Class.forName("android.os.ServiceManager")
                    .getMethod("getService", String.class).invoke(null, TurnSignalShellProtocol.SERVICE_NAME);
            if (candidate == null) return false;
            Parcel data = Parcel.obtain(), reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(TurnSignalShellProtocol.DESCRIPTOR);
                data.writeInt(CameraRuntimeHost.ATTACH);
                java.util.Map<String, Object> snapshot = new java.util.HashMap<>(settings.getAll());
                data.writeBundle(CameraRuntimePreferences.encode(snapshot));
                data.writeStrongBinder(callback);
                data.writeInt(Settings.canDrawOverlays(context) ? 1 : 0);
                data.writeInt(visible ? 1 : 0);
                data.writeInt(preview ? 1 : 0);
                if (!candidate.transact(TurnSignalShellProtocol.TX_CAMERA_RUNTIME, data, reply, 0)) return false;
                reply.readException();
                IBinder endpoint = reply.readStrongBinder();
                if (endpoint == null) return false;
                Bundle pending = reply.readBundle(getClass().getClassLoader());
                shell = candidate; camera = endpoint;
                lastSettings = snapshot;
                applyRuntimeSettings(pending, snapshot);
                command(CameraRuntimeHost.VISIBILITY, visible, "", null);
                if (preview) command(CameraRuntimeHost.PREVIEW, true, "", null);
                return camera != null && camera.isBinderAlive();
            } finally { data.recycle(); reply.recycle(); }
        } catch (Exception error) {
            shell = null; camera = null;
            failure("camera_runtime_attach_failed", error);
            return false;
        }
    }

    private synchronized void applyRuntimeSettings(Bundle delta, java.util.Map<String, Object> snapshot) {
        if (delta == null || delta.isEmpty()) return;
        java.util.Map<String, ?> current = settings.getAll();
        Bundle accepted = new Bundle(delta);
        for (String key : delta.keySet()) {
            // Preserve a UI edit made while the Binder transaction was in flight.
            if (!java.util.Objects.equals(current.get(key), snapshot.get(key))) accepted.remove(key);
        }
        if (!accepted.isEmpty() && CameraRuntimePreferences.apply(settings, accepted)) {
            for (String key : accepted.keySet()) {
                Object value = CameraRuntimePreferences.normalize(accepted.get(key));
                if (value == null) lastSettings.remove(key); else lastSettings.put(key, value);
            }
            command(CameraRuntimeHost.ACK_SETTINGS, false, "", accepted);
            CameraProbeActivity.publishMirrorSettingsChanged();
        }
    }

    private void scheduleSettingsSync() {
        if (detached) return;
        handler.removeCallbacks(preferenceSync);
        handler.post(preferenceSync);
    }

    private synchronized void syncPreferenceEdits() {
        // Preference-only edits (language, restore-on-open, etc.) have no service action.
        // Push changes to an attached runtime; never start a helper just for a disk write.
        if (!detached && camera != null && !changes(lastSettings, settings.getAll()).isEmpty())
            settingsChanged("sync");
    }

    synchronized void settingsChanged(String scope) {
        if (!connect()) return;
        java.util.Map<String, Object> snapshot = new java.util.HashMap<>(settings.getAll());
        Bundle delta = CameraRuntimePreferences.encode(changes(lastSettings, snapshot));
        Bundle pending = command(CameraRuntimeHost.SETTINGS, Settings.canDrawOverlays(context), scope, delta);
        if (pending != null) {
            lastSettings = snapshot;
            applyRuntimeSettings(pending, snapshot);
        }
    }

    synchronized void visibility(boolean value) {
        visible = value;
        if (!value) preview = false;
        if (connect()) command(CameraRuntimeHost.VISIBILITY, value, "", null);
    }

    void setUiState(boolean visible, boolean preview) {
        this.visible = visible;
        this.preview = preview;
    }

    synchronized void preview(boolean value) {
        preview = value;
        if (connect()) command(CameraRuntimeHost.PREVIEW, value, "", null);
    }

    synchronized void toggleReverse() {
        if (!visible && camera != null) command(CameraRuntimeHost.TOGGLE_REVERSE, false, "", null);
    }

    synchronized void reportStatus() {
        if (connect()) command(CameraRuntimeHost.STATUS, false, "", null);
    }

    synchronized void shutdown() {
        // The existing global helper shutdown resolves unattached hosts as well.
        // Never initialize camera sources merely to shut the application down.
        if (shell != null) command(CameraRuntimeHost.STOP, false, "", null);
        shell = null; camera = null;
    }

    synchronized void detach() {
        detached = true;
        settings.unregisterOnSharedPreferenceChangeListener(preferenceListener);
        handler.removeCallbacks(preferenceSync);
        if (shell != null) command(CameraRuntimeHost.DETACH, false, "", null);
        shell = null; camera = null;
    }

    private Bundle command(int operation, boolean flag, String scope, Bundle bundle) {
        IBinder target = shell;
        if (target == null) return null;
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(TurnSignalShellProtocol.DESCRIPTOR);
            data.writeInt(operation);
            data.writeStrongBinder(callback);
            if (operation == CameraRuntimeHost.SETTINGS || operation == CameraRuntimeHost.ACK_SETTINGS)
                data.writeBundle(bundle);
            if (operation == CameraRuntimeHost.SETTINGS || operation == CameraRuntimeHost.VISIBILITY
                    || operation == CameraRuntimeHost.PREVIEW) data.writeInt(flag ? 1 : 0);
            if (operation == CameraRuntimeHost.SETTINGS) data.writeString(scope);
            if (!target.transact(TurnSignalShellProtocol.TX_CAMERA_RUNTIME, data, reply, 0))
                throw new IllegalStateException("camera runtime command unsupported");
            reply.readException();
            return operation == CameraRuntimeHost.SETTINGS
                    ? reply.readBundle(getClass().getClassLoader()) : Bundle.EMPTY;
        } catch (Exception error) {
            shell = null; camera = null;
            failure("camera_runtime_command_failed_" + operation, error);
            return null;
        } finally { data.recycle(); reply.recycle(); }
    }

    private void failure(String kind, Exception error) {
        try { events.accept(new org.json.JSONObject().put("kind", kind)
                .put("error", error.toString()).toString()); }
        catch (org.json.JSONException ignored) { /* fixed String fields */ }
    }

    static java.util.Map<String, Object> changes(java.util.Map<String, ?> before,
            java.util.Map<String, ?> after) {
        java.util.Map<String, Object> delta = new java.util.HashMap<>();
        java.util.Set<String> keys = new java.util.HashSet<>(before.keySet());
        keys.addAll(after.keySet());
        for (String key : keys) if (!java.util.Objects.equals(before.get(key), after.get(key)))
            delta.put(key, after.get(key));
        return delta;
    }

    synchronized boolean transactCamera(int code, Parcel data, Parcel reply, int flags)
            throws android.os.RemoteException {
        if (!connect()) {
            reply.writeException(new IllegalStateException("camera_runtime_unavailable"));
            return true;
        }
        return camera.transact(code, data, reply, flags);
    }

    static boolean isCameraTransaction(int code) {
        return code == CameraHelperMain.TX_OPEN || code == CameraHelperMain.TX_CLOSE
                || code == CameraHelperMain.TX_OPEN_STOCK_AVM || code == CameraHelperMain.TX_OPEN_DIRECT
                || code == CameraHelperMain.TX_OPEN_REVERSE_PREVIEW
                || code == CameraHelperMain.TX_OPEN_REVERSE_PREVIEW_TARGETED
                || code == CameraHelperMain.TX_UPDATE_VISUALS
                || code == CameraHelperMain.TX_UPDATE_REVERSE_VISIBILITY;
    }
}
