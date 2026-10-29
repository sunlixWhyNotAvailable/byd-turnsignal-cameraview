package com.byd.extend;

import android.content.Context;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.provider.Settings;
import org.json.JSONObject;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Reconnectable UI facade. It never owns a video source, controller, or overlay Surface. */
final class CameraRuntimeClient {
    private final Context context;
    private final Handler handler;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Consumer<String> events;
    private final SharedPreferences settings;
    private IBinder shell;
    private IBinder camera;
    private volatile boolean visible;
    private volatile boolean preview;
    private volatile boolean detached;
    // Android registrations/bindService need a real AMS-owned process. Video stays in the helper.
    private ClusterFullscreenController cluster;
    private String lastVehiclePowerState;
    private java.util.function.BiConsumer<Boolean, Boolean> vehiclePowerListener;

    synchronized void setVehiclePowerListener(java.util.function.BiConsumer<Boolean, Boolean> listener) {
        vehiclePowerListener = listener;
        if (listener != null) listener.accept(lastVehiclePowerKnown, lastVehiclePowerOff);
    }
    private long lastVehiclePowerSourceEpoch = -1;
    private long lastVehiclePowerRevision = -1;
    private boolean lastVehiclePowerKnown;
    private boolean lastVehiclePowerOff;
    private int lastVehiclePowerFailureCount;
    private volatile OemCameraVisibilityRuntime pano;
    private boolean platformRegistered;
    private final BroadcastReceiver platformReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context ignored, Intent intent) {
            String action = intent == null ? "" : intent.getAction();
            if ("android.media.VOLUME_CHANGED_ACTION".equals(action)
                    || "android.media.STREAM_MUTE_CHANGED_ACTION".equals(action)) {
                platformCommand(CameraRuntimeHost.NAV_VOLUME, 0, "");
            } else {
                int event = powerEvent(action);
                if (shouldReconcilePano(event)) reconcilePano();
                platformCommand(CameraRuntimeHost.POWER, event, "");
            }
        }
    };
    static int powerEvent(String action) {
        if (Intent.ACTION_SCREEN_OFF.equals(action)) return CameraRuntimeHost.POWER_SCREEN_OFF;
        if (Intent.ACTION_SCREEN_ON.equals(action)) return CameraRuntimeHost.POWER_SCREEN_ON;
        if (Intent.ACTION_USER_PRESENT.equals(action)) return CameraRuntimeHost.POWER_USER_PRESENT;
        return "android.intent.action.QUICKBOOT_POWERON".equals(action)
                ? CameraRuntimeHost.POWER_QUICKBOOT : 0;
    }
    static boolean shouldReconcilePano(int event) {
        return event == CameraRuntimeHost.POWER_SCREEN_ON
                || event == CameraRuntimeHost.POWER_USER_PRESENT
                || event == CameraRuntimeHost.POWER_QUICKBOOT;
    }
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
            } else if (code == CameraRuntimeHost.AVM_CONFIG) {
                writeAvmConfig(reply);
            } else return false;
            return true;
        }
    };

    private void writeAvmConfig(Parcel reply) {
        FutureTask<StockAvmPreview.Config> task = new FutureTask<>(() -> {
            if (detached) throw new IllegalStateException("camera runtime detached");
            return StockAvmPreview.readConfig(context, (stage, detail) -> {
                try { deliverEvent(new org.json.JSONObject().put("kind", "stock_avm_config_stage")
                        .put("stage", stage).put("detail", detail).toString()); }
                catch (org.json.JSONException ignored) { /* fixed String fields */ }
            });
        });
        try {
            if (!mainHandler.post(task)) throw new IllegalStateException("app main handler unavailable");
            StockAvmPreview.Config config = task.get(10, TimeUnit.SECONDS);
            if (detached) throw new IllegalStateException("camera runtime detached");
            reply.writeNoException();
            reply.writeInt(1);
            config.writeToParcel(reply);
        } catch (Exception error) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            Throwable cause = error instanceof java.util.concurrent.ExecutionException
                    ? error.getCause() : error;
            reply.writeNoException();
            reply.writeInt(0);
            reply.writeString(cause instanceof StockAvmPreview.StageException
                    ? ((StockAvmPreview.StageException) cause).stage : "camera_config_read");
            reply.writeString(cause.toString());
        } finally {
            mainHandler.removeCallbacks(task);
            task.cancel(false);
        }
    }

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
            // A retained host may belong to the previous APK. The existing launcher replaces it.
            if (!TurnSignalController.ping(candidate).healthy()) return false;
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
                handler.post(this::platformAttached);
                MotionDetectorClient.notifyCameraHostReady(context);
                // A new host needs the viewing capability registered again, without a polling loop.
                com.byd.extend.remote.CameraViewingController.get(context).workerReconfigured();
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
        if (!detached && cluster != null) cluster.settingsChanged();
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
        if (pano != null) pano.reportStatus();
    }

    private synchronized void platformAttached() {
        if (detached || shell == null) return;
        if (cluster == null)
            cluster = new ClusterFullscreenController(context, settings, handler, this::platformEvent);
        if (lastVehiclePowerState != null) cluster.acceptEvent(lastVehiclePowerState);
        if (!platformRegistered) {
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_SCREEN_ON);
            filter.addAction(Intent.ACTION_SCREEN_OFF);
            filter.addAction(Intent.ACTION_USER_PRESENT);
            filter.addAction("android.intent.action.QUICKBOOT_POWERON");
            filter.addAction("android.media.VOLUME_CHANGED_ACTION");
            filter.addAction("android.media.STREAM_MUTE_CHANGED_ACTION");
            try {
                context.registerReceiver(platformReceiver, filter, null, handler);
                platformRegistered = true;
                platformEvent("app_platform_listener", "registered", true);
            } catch (RuntimeException error) {
                platformEvent("app_platform_listener", "registered", false, "error", error.toString());
            }
        }
        // Reconcile current state after a process gap; do not recreate live cameras or tracks.
        platformCommand(CameraRuntimeHost.POWER, 0, "");
        platformCommand(CameraRuntimeHost.NAV_VOLUME, 0, "");
        if (pano == null) {
            pano = new OemCameraVisibilityRuntime(context, handler,
                    (known, active, source) -> platformCommand(CameraRuntimeHost.PANO,
                            known ? (active ? 1 : 0) : -1, source), this::platformEvent);
            pano.start();
        } else pano.reconcile();
    }

    private void reconcilePano() {
        if (detached) return;
        OemCameraVisibilityRuntime runtime = pano;
        if (runtime != null) runtime.reconcile();
    }

    synchronized void acceptPlatformEvent(String line) {
        if (detached || line == null) return;
        try {
            String kind = new JSONObject(line).optString("kind");
            if ("camera_vehicle_power_state".equals(kind)) {
                JSONObject event = new JSONObject(line);
                Object epochValue = event.opt("source_epoch");
                Object revisionValue = event.opt("revision");
                Object knownValue = event.opt("known");
                Object offValue = event.opt("off");
                Object failuresValue = event.opt("consecutive_failed_reads");
                if (!(epochValue instanceof Number) || !(revisionValue instanceof Number)
                        || !(knownValue instanceof Boolean) || !(offValue instanceof Boolean)
                        || !(failuresValue instanceof Number)) {
                    platformEvent("camera_runtime_platform_event_error", "stage",
                            "invalid_camera_power_state", "reason", "required field missing");
                    return;
                }
                long sourceEpoch = ((Number) epochValue).longValue();
                long revision = ((Number) revisionValue).longValue();
                boolean known = (Boolean) knownValue;
                boolean off = (Boolean) offValue;
                int failures = ((Number) failuresValue).intValue();
                if (sourceEpoch < 0 || revision < 0 || failures < 0 || failures > 5
                        || off && !known || known && failures == 5) {
                    platformEvent("camera_runtime_platform_event_error", "stage",
                            "invalid_camera_power_state", "reason", "field out of range");
                    return;
                }
                if (sourceEpoch < lastVehiclePowerSourceEpoch
                        || sourceEpoch == lastVehiclePowerSourceEpoch
                        && revision < lastVehiclePowerRevision) return;
                if (sourceEpoch == lastVehiclePowerSourceEpoch
                        && revision == lastVehiclePowerRevision
                        && (known != lastVehiclePowerKnown || off != lastVehiclePowerOff
                        || failures != lastVehiclePowerFailureCount)) {
                    platformEvent("camera_runtime_platform_event_error", "stage",
                            "invalid_camera_power_state", "reason",
                            "decision changed without a new revision");
                    return;
                }
                lastVehiclePowerState = line;
                lastVehiclePowerSourceEpoch = sourceEpoch;
                lastVehiclePowerRevision = revision;
                lastVehiclePowerKnown = known;
                lastVehiclePowerOff = off;
                lastVehiclePowerFailureCount = failures;
                if (vehiclePowerListener != null) vehiclePowerListener.accept(known, off);
                if (cluster != null) cluster.acceptEvent(line);
            } else if ("shell_power_state".equals(kind) && cluster != null) {
                cluster.acceptEvent(line);
            }
        } catch (org.json.JSONException | IllegalArgumentException failure) {
            platformEvent("camera_runtime_platform_event_error", "stage", "parse_helper_event",
                    "error", failure.toString());
        }
    }

    private synchronized void platformCommand(int operation, int value, String source) {
        if (!detached && shell != null) command(operation, false, source, null, value);
    }

    private void platformEvent(String kind, Object... fields) {
        try {
            org.json.JSONObject event = new org.json.JSONObject().put("kind", kind)
                    .put("source", "app_platform").put("t_ms", android.os.SystemClock.elapsedRealtime());
            for (int i = 0; i + 1 < fields.length; i += 2)
                event.put(String.valueOf(fields[i]), fields[i + 1]);
            deliverEvent(event.toString());
        } catch (org.json.JSONException error) { throw new IllegalArgumentException(error); }
    }

    synchronized void shutdown() {
        // The existing global helper shutdown resolves unattached hosts as well.
        // Never initialize camera sources merely to shut the application down.
        if (shell != null) command(CameraRuntimeHost.STOP, false, "", null);
        shell = null; camera = null;
    }

    synchronized void detach() {
        detached = true;
        vehiclePowerListener = null;
        settings.unregisterOnSharedPreferenceChangeListener(preferenceListener);
        handler.removeCallbacks(preferenceSync);
        if (pano != null) { pano.stopForTeardown(); pano = null; }
        if (cluster != null) { cluster.shutdown(); cluster = null; }
        if (platformRegistered) {
            context.unregisterReceiver(platformReceiver);
            platformRegistered = false;
        }
        if (shell != null) command(CameraRuntimeHost.DETACH, false, "", null);
        shell = null; camera = null;
    }

    private Bundle command(int operation, boolean flag, String scope, Bundle bundle) {
        return command(operation, flag, scope, bundle, 0);
    }

    private Bundle command(int operation, boolean flag, String scope, Bundle bundle, int platformValue) {
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
            if (operation == CameraRuntimeHost.PANO || operation == CameraRuntimeHost.POWER)
                data.writeInt(platformValue);
            if (operation == CameraRuntimeHost.SETTINGS || operation == CameraRuntimeHost.PANO)
                data.writeString(scope);
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
        try { deliverEvent(new org.json.JSONObject().put("kind", kind)
                .put("error", error.toString()).toString()); }
        catch (org.json.JSONException ignored) { /* fixed String fields */ }
    }

    private void deliverEvent(String line) {
        // HelperBinder's camera entry holds its own lock before ours. Never call it back under ours.
        handler.post(() -> { if (!detached) events.accept(line); });
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
                || code == CameraHelperMain.TX_OPEN_MOTION_PREVIEW
                || code == CameraHelperMain.TX_CLOSE_MOTION_PREVIEW
                || code == CameraHelperMain.TX_UPDATE_VISUALS
                || code == CameraHelperMain.TX_UPDATE_REVERSE_VISIBILITY;
    }
}
