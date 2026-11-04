package com.byd.extend;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

/**
 * Receives the OEM Pano application's visibility edge and lifecycle-triggered snapshots.
 *
 * <p>The stock process does not expose a stable public API for this state.  The tested broadcast
 * is therefore the live source and {@code sys.byd.pano_start} is read only during startup and
 * platform reconciliation until a live edge arrives. The cached live state is replayed thereafter; there is
 * intentionally no property polling.</p>
 */
final class OemCameraVisibilityRuntime {
    static final String ACTION_PANO = "byd.intent.action.pano";
    static final String EXTRA_PANO_STATE = "pano_state";
    static final String STARTUP_PROPERTY = "sys.byd.pano_start";

    interface Listener {
        void onVisibility(boolean known, boolean visible, String source);
    }

    static final class State {
        private volatile int panoState = -1;
        private boolean live;

        boolean accept(String raw, boolean broadcast) {
            if (!broadcast && live) return known();
            panoState = parseState(raw);
            if (broadcast && known()) live = true;
            return panoState >= 0;
        }

        boolean hasLiveState() { return live; }

        void invalidate() {
            panoState = -1;
            live = false;
        }

        boolean known() {
            return panoState >= 0;
        }

        boolean visible() {
            return panoState == 1;
        }

        int panoState() {
            return panoState;
        }
    }

    private final Context context;
    private final Handler handler;
    private final Listener listener;
    private final BiConsumer<String, Object[]> eventSink;
    private final AtomicBoolean reconcilePending = new AtomicBoolean();
    private final Object receiverLock = new Object();
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context receiverContext, Intent intent) {
            String action = intent == null ? "" : String.valueOf(intent.getAction());
            String value = readStateExtra(intent);
            runOnHandler(() -> acceptState(value, "broadcast", action));
        }
    };

    private volatile boolean started;
    private volatile boolean receiverRegistered;
    private volatile boolean stopped;
    private final State state = new State();

    OemCameraVisibilityRuntime(
            Context context, Handler handler, Listener listener,
            BiConsumer<String, Object[]> eventSink) {
        if (context == null || handler == null || listener == null || eventSink == null) {
            throw new IllegalArgumentException("OEM visibility runtime dependencies are required");
        }
        this.context = context;
        this.handler = handler;
        this.listener = listener;
        this.eventSink = eventSink;
    }

    void start() {
        scheduleReconcile(true);
    }

    /** Replays live state after attach/reconnect; reads the startup property only before live edges. */
    void reconcile() {
        scheduleReconcile(false);
    }

    /** Called only after the service has sealed its runtime queue. */
    void stopForTeardown() {
        stopped = true;
        stopOnHandler();
    }

    /** Replays the cached edge for a newly attached controller callback. */
    void reportStatus() {
        runOnHandler(() -> emitVisibility("status", ACTION_PANO));
    }

    private void scheduleReconcile(boolean startup) {
        if (stopped || !reconcilePending.compareAndSet(false, true)) return;
        if (!handler.post(() -> {
            try {
                reconcileOnHandler(startup);
            } finally {
                reconcilePending.set(false);
            }
        })) reconcilePending.set(false);
    }

    private void reconcileOnHandler(boolean startup) {
        if (stopped) return;
        started = true;
        String source = startup ? "startup_snapshot" : "reconcile";
        String sourceEvent = startup ? "startup" : "reconcile";
        if (!receiverRegistered) {
            String registrationError = null;
            synchronized (receiverLock) {
                if (stopped) return;
                try {
                    // A new receiver lifetime may bootstrap; an attached receiver keeps its edges.
                    state.invalidate();
                    context.registerReceiver(receiver, new IntentFilter(ACTION_PANO), null, handler);
                    receiverRegistered = true;
                } catch (Throwable error) {
                    receiverRegistered = false;
                    registrationError = summary(error);
                }
            }
            if (registrationError != null) {
                state.invalidate();
                emit("oem_camera_visibility_listener", "action", "registered", "ok", false,
                        "registered", false, "source_event", sourceEvent,
                        "error", registrationError);
                emitVisibility("registration_failed", ACTION_PANO, registrationError);
                return;
            }
            emit("oem_camera_visibility_listener", "action", "registered", "ok", true,
                    "registered", true, "source_event", sourceEvent);
        }
        if (stopped) return;
        if (state.hasLiveState()) {
            emitVisibility("live_replay", ACTION_PANO);
            return;
        }
        String snapshot;
        try {
            snapshot = readSystemProperty(STARTUP_PROPERTY);
        } catch (Throwable error) {
            state.invalidate();
            emitVisibility(source, ACTION_PANO, summary(error));
            return;
        }
        acceptState(snapshot, source, ACTION_PANO);
    }

    private void stopOnHandler() {
        if (!started && !receiverRegistered) return;
        started = false;
        String unregisterError = null;
        synchronized (receiverLock) {
            if (receiverRegistered) {
                receiverRegistered = false;
                try {
                    context.unregisterReceiver(receiver);
                } catch (Throwable error) {
                    unregisterError = summary(error);
                }
            }
        }
        if (unregisterError != null) {
            emit("oem_camera_visibility_listener", "action", "unregistered", "ok", false,
                    "registered", false, "source_event", "stop",
                    "error", unregisterError);
        }
        state.invalidate();
        emit("oem_camera_visibility_listener", "action", "stopped", "ok", true,
                "registered", false, "source_event", "stop");
    }

    private void acceptState(String raw, String source, String action) {
        if (!started || stopped) return;
        if (!state.accept(raw, "broadcast".equals(source))) {
            emitVisibility(source, action, "invalid_pano_state");
            return;
        }
        emitVisibility(source, action);
    }

    private void emitVisibility(String source, String action) {
        emitVisibility(source, action, null);
    }

    private void emitVisibility(String source, String action, String error) {
        boolean known = state.known();
        boolean visible = known && state.visible();
        if (error != null) {
            emit("oem_camera_visibility", "valid", known, "known", known,
                    "visible", visible, "pano_state", state.panoState(),
                    "source_event", source, "action", action,
                    "registered", receiverRegistered, "error", error);
        } else {
            emit("oem_camera_visibility", "valid", known, "known", known,
                    "visible", visible,
                    "pano_state", state.panoState(), "source_event", source,
                    "action", action, "registered", receiverRegistered);
        }
        listener.onVisibility(known, visible, source);
    }

    private static String readStateExtra(Intent intent) {
        if (intent == null) return null;
        try {
            android.os.Bundle extras = intent.getExtras();
            if (extras == null || !extras.containsKey(EXTRA_PANO_STATE)) return null;
            Object value = extras.get(EXTRA_PANO_STATE);
            return value == null ? null : String.valueOf(value);
        } catch (Throwable ignored) {
            // A vendor bundle with an unexpected type is handled as an invalid edge.
        }
        return null;
    }

    static int parseState(String value) {
        if (value == null) return -1;
        String normalized = value.trim();
        if ("1".equals(normalized)) return 1;
        if ("0".equals(normalized)) return 0;
        return -1;
    }

    static String readSystemProperty(String key) throws Exception {
        Class<?> type = Class.forName("android.os.SystemProperties");
        return String.valueOf(type.getMethod("get", String.class).invoke(null, key));
    }

    private void runOnHandler(Runnable action) {
        if (Looper.myLooper() == handler.getLooper()) action.run();
        else handler.post(action);
    }

    private void emit(String kind, Object... fields) {
        eventSink.accept(kind, fields);
    }

    private static String summary(Throwable error) {
        return com.byd.extend.remote.CameraViewingDiagnostics.describe(error);
    }
}
