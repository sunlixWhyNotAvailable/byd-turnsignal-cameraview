package com.byd.extend;

import android.content.Context;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Parcel;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.json.JSONObject;

/** Lives in bydextend_helper. App callback death is NOT camera runtime shutdown. */
final class CameraRuntimeHost {
    static final int ATTACH = 1, SETTINGS = 2, VISIBILITY = 3, PREVIEW = 4,
            TOGGLE_REVERSE = 5, ACK_SETTINGS = 6, STOP = 7, STATUS = 8, DETACH = 9,
            PANO = 10, POWER = 11, NAV_VOLUME = 12;
    static final String CALLBACK = "com.byd.extend.ICameraRuntimeClient";
    static final int EVENT = 1, PREFERENCES = 2;
    private final HandlerThread thread = new HandlerThread("camera-runtime");
    private final Handler handler;
    private final CameraRuntimeContext context;
    private final int appUid;
    private final Consumer<String> journal;
    private final java.util.function.BiConsumer<Integer, Integer> platformSignal;
    private final CameraHelperMain.HelperBinder helper;
    private BlindSpotOverlayController blind;
    private ParkingCameraController parking;
    private ReverseCameraController reverse;
    private RearviewMirrorController mirror;
    private final CameraRuntimeLifetime<IBinder> lifetime = new CameraRuntimeLifetime<>();
    private boolean visible;
    private boolean preview;
    private IBinder linkedClient;
    private IBinder.DeathRecipient clientDeath;
    private final Runnable discoveryRetry = this::discoverCamera;
    private IBinder cameraEndpointFor(IBinder owner) { return new Binder() {
        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
            if (Binder.getCallingUid() != appUid) throw new SecurityException("camera caller denied");
            if (lifetime.client() != owner || !owner.isBinderAlive() || lifetime.stopped())
                throw new IllegalStateException("camera client attachment expired");
            if (!CameraRuntimeClient.isCameraTransaction(code))
                throw new SecurityException("not a camera operation");
            long identity = Binder.clearCallingIdentity();
            try { return helper.onTransact(code, data, reply, flags); }
            catch (android.os.RemoteException error) { throw new IllegalStateException(error); }
            finally { Binder.restoreCallingIdentity(identity); }
        }
    }; }

    CameraRuntimeHost(Context system, int appUid, Consumer<String> journal,
            java.util.function.BiConsumer<Integer, Integer> platformSignal) throws Exception {
        this.appUid = appUid;
        this.journal = journal;
        this.platformSignal = platformSignal;
        context = new CameraRuntimeContext(system, appUid);
        CameraShellMain.initializeSystemFontsForShell();
        thread.start();
        handler = new Handler(thread.getLooper());
        helper = new CameraHelperMain.HelperBinder(context, handler, this::acceptCameraLine);
        context.settings.setChangeListener(delta -> handler.post(() -> send(PREFERENCES, null, Bundle.EMPTY)));
    }

    int transact(Parcel data, Parcel reply) throws Exception {
        int operation = data.readInt();
        IBinder owner = operation == ATTACH ? null : data.readStrongBinder();
        // Decode on the Binder thread; a Parcel must never outlive its transaction.
        Bundle settings = operation == ATTACH || operation == SETTINGS || operation == ACK_SETTINGS
                ? data.readBundle(getClass().getClassLoader()) : null;
        IBinder callback = operation == ATTACH ? data.readStrongBinder() : null;
        boolean flag = operation == ATTACH || operation == SETTINGS
                || operation == VISIBILITY || operation == PREVIEW ? data.readInt() != 0 : false;
        boolean initialVisible = operation == ATTACH && data.readInt() != 0;
        boolean initialPreview = operation == ATTACH && data.readInt() != 0;
        int platformValue = operation == PANO || operation == POWER ? data.readInt() : 0;
        String scope = operation == SETTINGS || operation == PANO ? data.readString() : "";
        Bundle pending = call(() -> {
            if (lifetime.stopped() && operation != STOP) throw new IllegalStateException("camera runtime stopped");
            if (operation != ATTACH && (owner == null || lifetime.client() != owner))
                throw new IllegalStateException("camera client attachment expired");
            switch (operation) {
                case ATTACH:
                    if (callback == null || settings == null) throw new IllegalArgumentException("missing attachment");
                    linkClient(callback);
                    context.overlayAllowed = flag;
                    Bundle changes = context.settings.reconcile(settings);
                    if (!lifetime.started()) {
                        visible = initialVisible; preview = initialPreview;
                        context.uiVisible = visible;
                    }
                    boolean retained;
                    try { retained = lifetime.attach(callback, this::initialize); }
                    catch (RuntimeException error) { stopOnHandler(); throw error; }
                    event("camera_runtime_attached", "pid", android.os.Process.myPid(), "retained", retained);
                    helper.reportRetainedCameraState();
                    return changes;
                case SETTINGS:
                    context.overlayAllowed = flag;
                    Bundle unsaved = context.settings.updateFromClient(settings);
                    if (!"sync".equals(scope)) applySettings(scope);
                    return unsaved;
                case ACK_SETTINGS: context.settings.acknowledge(settings); break;
                case STATUS: helper.reportRetainedCameraState(); break;
                case PANO:
                    if (platformValue < -1 || platformValue > 1 || scope == null || scope.length() > 64)
                        throw new IllegalArgumentException("invalid pano state");
                    blind.oemVisibility(platformValue >= 0, platformValue == 1);
                    reverse.oemVisibility(platformValue >= 0, platformValue == 1, scope);
                    mirror.oemVisibility(platformValue >= 0, platformValue == 1);
                    break;
                case POWER:
                    if (platformValue != 0 && platformValue != 1)
                        throw new IllegalArgumentException("invalid power event");
                    platformSignal.accept(operation, platformValue);
                    break;
                case NAV_VOLUME: platformSignal.accept(operation, 0); break;
                case DETACH: clientDied(owner); break;
                case VISIBILITY: setVisibility(flag); break;
                case PREVIEW:
                    preview = flag;
                    blind.setSuspended(flag); parking.setSuspended(flag);
                    break;
                case TOGGLE_REVERSE:
                    if (!visible) reverse.requestSteeringToggle(0);
                    break;
                case STOP: stopOnHandler(); break;
                default: throw new IllegalArgumentException("unknown camera runtime command");
            }
            return Bundle.EMPTY;
        });
        reply.writeNoException();
        if (operation == ATTACH) {
            reply.writeStrongBinder(cameraEndpointFor(callback));
            reply.writeBundle(pending);
        }
        if (operation == SETTINGS) reply.writeBundle(pending);
        return operation;
    }

    private void initialize() {
        BlindSpotOverlayController.migrateOverlayPreferences(context.settings);
        blind = new BlindSpotOverlayController(context, handler, this::event);
        parking = new ParkingCameraController(context, handler, this::event);
        reverse = new ReverseCameraController(context, handler, helper::emitControllerEvent,
                active -> { blind.setReversePriority(active); parking.setReversePriority(active); });
        mirror = new RearviewMirrorController(context, handler, this::event);
        discoverCamera();
        blind.setUiHidden(visible); parking.setUiHidden(visible);
        blind.setSuspended(preview); parking.setSuspended(preview);
        blind.attachHelper(helper); parking.attachHelper(helper); reverse.attachHelper(helper);
        mirror.attachHelper(helper);
        mirror.appVisibility(visible);
        mirror.setRuntimeAllowed(true);
    }

    private void discoverCamera() {
        handler.removeCallbacks(discoveryRetry);
        if (!lifetime.stopped() && !helper.discoverCamera()) handler.postDelayed(discoveryRetry, 3_000);
    }

    private void setVisibility(boolean next) {
        context.uiVisible = next;
        if (visible == next) return;
        visible = next;
        mirror.appVisibility(next); blind.setUiHidden(next); parking.setUiHidden(next);
        if (!next) { preview = false; blind.setSuspended(false); parking.setSuspended(false); }
    }

    private void clientDied(IBinder expected) {
        if (!lifetime.detach(expected)) return;
        unlinkClient();
        // Only app-owned preview surfaces are gone; all background consumers stay attached.
        helper.closeCameraForOwner(CameraHelperMain.CAMERA_OWNER_ACTIVITY, "ui_process_died");
        setVisibility(false);
        event("camera_runtime_ui_detached", "runtime_retained", true);
    }

    private void linkClient(IBinder callback) throws android.os.RemoteException {
        if (linkedClient == callback) return;
        IBinder.DeathRecipient death = () -> handler.post(() -> clientDied(callback));
        callback.linkToDeath(death, 0);
        unlinkClient();
        linkedClient = callback;
        clientDeath = death;
    }

    private void unlinkClient() {
        if (linkedClient != null) linkedClient.unlinkToDeath(clientDeath, 0);
        linkedClient = null;
        clientDeath = null;
    }

    private void applySettings(String scope) {
        if ("warning".equals(scope)) blind.applyWarningSettings();
        else if ("trigger".equals(scope)) blind.applyTriggerSettings();
        else if ("parking".equals(scope)) parking.settingsChanged();
        else if ("reverse".equals(scope)) reverse.settingsChanged();
        else if ("mirror".equals(scope)) mirror.settingsChanged();
        else if ("logging".equals(scope)) helper.configureLogging();
        else {
            helper.configureLogging();
            blind.applySettings(); blind.applyWarningSettings(); blind.applyTriggerSettings();
            parking.settingsChanged(); reverse.settingsChanged(); mirror.settingsChanged();
        }
    }

    void acceptTelemetry(String line) {
        handler.post(() -> { if (!lifetime.stopped() && lifetime.started()) dispatch(line); });
    }

    private void dispatch(String line) {
        blind.acceptEvent(line); parking.acceptEvent(line); reverse.acceptEvent(line);
        mirror.acceptEvent(line);
    }

    private void acceptCameraLine(String line) {
        handler.post(() -> {
            if (lifetime.stopped() || !lifetime.started()) return;
            dispatch(line);
            journal.accept(line);
            send(EVENT, line, null);
        });
    }

    private void event(String kind, Object... fields) {
        if (!DiagnosticLogPolicy.shouldProduce(kind)) return;
        try {
            JSONObject event = new JSONObject().put("kind", kind).put("source", "camera_runtime")
                    .put("t_ms", android.os.SystemClock.elapsedRealtime());
            for (int i = 0; i + 1 < fields.length; i += 2) event.put(String.valueOf(fields[i]), fields[i + 1]);
            String line = event.toString();
            journal.accept(line); send(EVENT, line, null);
        } catch (org.json.JSONException error) { throw new IllegalArgumentException(error); }
    }

    private void send(int code, String line, Bundle settings) {
        if (android.os.Looper.myLooper() != handler.getLooper()) {
            handler.post(() -> send(code, line, settings));
            return;
        }
        IBinder target = lifetime.client();
        if (target == null) return;
        Parcel data = Parcel.obtain();
        try {
            data.writeInterfaceToken(CALLBACK);
            if (code == EVENT) data.writeString(line); else data.writeBundle(settings);
            if (!target.transact(code, data, null, IBinder.FLAG_ONEWAY)) clientDied(target);
        } catch (android.os.RemoteException error) { clientDied(target); }
        finally { data.recycle(); }
    }

    private <T> T call(java.util.concurrent.Callable<T> task) throws Exception {
        if (android.os.Looper.myLooper() == handler.getLooper()) return task.call();
        FutureTask<T> queued = new FutureTask<>(task);
        if (!handler.post(queued)) throw new IllegalStateException("camera runtime closed");
        return queued.get(10, TimeUnit.SECONDS);
    }

    void shutdown() {
        try { call(() -> { stopOnHandler(); return null; }); }
        catch (Exception error) { journal.accept("{\"kind\":\"camera_runtime_shutdown_error\"}"); }
    }

    boolean isStopped() { return lifetime.stopped(); }

    private void stopOnHandler() {
        lifetime.shutdown(() -> {
            unlinkClient();
            handler.removeCallbacks(discoveryRetry);
            if (reverse != null) reverse.shutdown();
            if (mirror != null) mirror.shutdown();
            if (blind != null) blind.shutdown();
            if (parking != null) parking.shutdown();
            helper.shutdown(true);
            thread.quitSafely();
        });
    }
}
