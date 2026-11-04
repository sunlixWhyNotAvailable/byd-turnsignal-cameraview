package com.byd.extend;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.ResolveInfo;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import com.byd.extend.remote.CameraViewingDiagnostics;
import java.util.function.BiConsumer;
import org.json.JSONObject;

/** AMS binding belongs to the app; gear decisions and pending intent belong to the helper. */
final class NativePanoramaController {
    private final Context context;
    private final Handler handler;
    private final SharedPreferences settings;
    private final BiConsumer<String, Object[]> events;
    private final BiConsumer<Long, Long> acknowledge;
    private final IntentState desired = new IntentState();
    private Binding binding;
    private volatile boolean closed;
    private boolean powerAllowed = true, panoramaVisible;
    private long sentEpoch = -1, sentRevision = -1;

    NativePanoramaController(Context context, Handler handler, SharedPreferences settings,
            BiConsumer<String, Object[]> events, BiConsumer<Long, Long> acknowledge) {
        this.context = context.getApplicationContext();
        this.handler = handler;
        this.settings = settings;
        this.events = events;
        this.acknowledge = acknowledge;
    }

    void accept(JSONObject event) {
        onHandler(() -> {
            if (closed) return;
            try {
                if (!desired.accept(event.getLong("source_epoch"), event.getLong("revision"),
                        event.getLong("session"), event.getBoolean("active"), event.getInt("target"),
                        event.optBoolean("manual", false))) return;
                reconcile();
            } catch (Exception failure) { failed("decode_intent", failure); }
        });
    }

    void power(boolean allowed) { onHandler(() -> { powerAllowed = allowed; reconcile(); }); }
    void visibility(boolean known, boolean visible) {
        onHandler(() -> { panoramaVisible = known && visible; reconcile(); });
    }
    void settingsChanged() { onHandler(() -> { if (!allowed()) release("settings_changed"); }); }
    synchronized void close() {
        // Teardown seals/clears this queue; posting cleanup would lose the unbind.
        closed = true;
        release("shutdown");
    }

    private void onHandler(Runnable action) {
        Runnable serialized = () -> { synchronized (this) { action.run(); } };
        if (Looper.myLooper() == handler.getLooper()) serialized.run(); else handler.post(serialized);
    }

    private boolean allowed() {
        return !closed && powerAllowed && panoramaVisible && desired.active
                && settings.getBoolean(ReverseCameraController.PREF_ENABLED, false)
                && (desired.manual
                    ? ReverseCameraController.loadWidgetVisible(settings)
                        && ReverseCameraController.hasAnyFrontIntegration(settings)
                    : ReverseGearSwitching.nativeView(ReverseGearSwitching.read(settings)));
    }

    private void reconcile() {
        if (!allowed()) { release("inactive"); return; }
        if (binding == null) {
            Binding candidate = new Binding();
            binding = candidate;
            try {
                Intent intent = new Intent("com.byd.action.AVCSERVICE").setPackage("com.byd.avc");
                ResolveInfo service = context.getPackageManager().resolveService(intent, 0);
                if (service == null || service.serviceInfo == null)
                    throw new IllegalStateException("AVCSERVICE resolveService returned no service");
                intent.setComponent(new ComponentName(service.serviceInfo.packageName, service.serviceInfo.name));
                // Never create/wake the OEM service: this controller only follows a visible panorama.
                candidate.registered = context.bindService(intent, candidate, 0);
                if (!candidate.registered) throw new IllegalStateException("AVCSERVICE bindService returned false");
                emit("binding", "component", intent.getComponent().flattenToShortString(),
                        "uid", android.os.Process.myUid(), "pid", android.os.Process.myPid(),
                        "android_user", android.os.Process.myUid() / 100000,
                        "package", context.getPackageName());
            } catch (Exception failure) { failed("bind", failure); release("bind_failed"); }
        }
        sendPending();
    }

    private void sendPending() {
        if (!allowed() || binding == null || binding.remote == null || desired.target < 0) return;
        if (sentEpoch == desired.epoch && sentRevision == desired.revision) {
            acknowledge.accept(sentEpoch, sentRevision);
            return;
        }
        try {
            int command = desired.target == ReverseGearSessionPolicy.MODE_FRONT ? 0 : 1;
            binding.remote.send(Message.obtain(null, command));
            sentEpoch = desired.epoch;
            sentRevision = desired.revision;
            emit("sent", "what", command, "visible_frame_confirmed", false);
            acknowledge.accept(sentEpoch, sentRevision);
        } catch (Exception failure) { failed("send", failure); release("send_failed"); }
    }

    private void release(String reason) {
        Binding previous = binding;
        binding = null;
        if (previous == null) return;
        previous.remote = null;
        if (previous.registered) {
            try { context.unbindService(previous); }
            catch (RuntimeException failure) { failed("unbind", failure); }
        }
        emit("unbound", "reason", reason);
    }

    private void failed(String operation, Throwable failure) {
        emit("failed", "operation", operation, "error", CameraViewingDiagnostics.describe(failure));
    }

    private void emit(String state, Object... fields) {
        Object[] record = new Object[fields.length + 10];
        Object[] identity = {"state", state, "source_epoch", desired.epoch,
                "revision", desired.revision, "session", desired.session, "target", desired.target};
        System.arraycopy(identity, 0, record, 0, identity.length);
        System.arraycopy(fields, 0, record, identity.length, fields.length);
        events.accept("native_panorama_command", record);
    }

    private final class Binding implements ServiceConnection {
        boolean registered;
        Messenger remote;
        @Override public void onServiceConnected(ComponentName name, IBinder service) {
            onHandler(() -> {
                if (binding != this || !allowed()) return;
                remote = new Messenger(service);
                emit("connected", "component", name.flattenToShortString());
                sendPending();
            });
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            onHandler(() -> { if (binding == this) {
                remote = null;
                emit("disconnected", "component", name.flattenToShortString());
            } });
        }
        @Override public void onBindingDied(ComponentName name) {
            onHandler(() -> { if (binding == this) release("binding_died"); });
        }
        @Override public void onNullBinding(ComponentName name) {
            onHandler(() -> { if (binding == this) {
                failed("connect", new IllegalStateException("AVCSERVICE returned a null binding: " + name));
                release("null_binding");
            } });
        }
    }

    static final class IntentState {
        long epoch = -1, revision = -1, session;
        boolean active, manual;
        int target = -1;

        boolean accept(long epoch, long revision, long session, boolean active, int target, boolean manual) {
            if (epoch < 0 || revision < 0 || session < 0 || target < -1 || target > 1
                    || !active && (target != -1 || manual))
                throw new IllegalArgumentException("Invalid native panorama intent");
            if (epoch < this.epoch || epoch == this.epoch && revision < this.revision) return false;
            if (epoch == this.epoch && revision == this.revision
                    && (session != this.session || active != this.active || manual != this.manual
                        || target != this.target && target != -1))
                throw new IllegalArgumentException("Native intent changed without a revision");
            this.epoch = epoch; this.revision = revision; this.session = session;
            this.active = active; this.target = target; this.manual = manual;
            return true;
        }
    }
}
