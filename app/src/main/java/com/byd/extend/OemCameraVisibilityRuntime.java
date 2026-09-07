package com.byd.extend;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;

import java.util.function.BiConsumer;

/**
 * Receives the OEM Pano application's visibility edge and one startup snapshot.
 *
 * <p>The stock process does not expose a stable public API for this state.  The tested broadcast
 * is therefore the live source and {@code sys.byd.pano_start} is read only during startup.  The
 * cached state is replayed for status requests; there is intentionally no property polling.</p>
 */
final class OemCameraVisibilityRuntime {
    static final String ACTION_PANO = "byd.intent.action.pano";
    static final String EXTRA_PANO_STATE = "pano_state";
    static final String STARTUP_PROPERTY = "sys.byd.pano_start";

    private final Context context;
    private final Handler handler;
    private final BiConsumer<String, Object[]> eventSink;
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context receiverContext, Intent intent) {
            String action = intent == null ? "" : String.valueOf(intent.getAction());
            String value = readStateExtra(intent);
            handler.post(() -> acceptState(value, "broadcast", action));
        }
    };

    private boolean started;
    private boolean receiverRegistered;
    private boolean known;
    private boolean visible;
    private int panoState = -1;

    OemCameraVisibilityRuntime(
            Context context, Handler handler, BiConsumer<String, Object[]> eventSink) {
        if (context == null || handler == null || eventSink == null) {
            throw new IllegalArgumentException("OEM visibility runtime dependencies are required");
        }
        this.context = context;
        this.handler = handler;
        this.eventSink = eventSink;
    }

    void start() {
        runOnHandler(this::startOnHandler);
    }

    void stop() {
        runOnHandler(this::stopOnHandler);
    }

    /** Replays the cached edge for a newly attached controller callback. */
    void reportStatus() {
        runOnHandler(() -> emitVisibility("status", ACTION_PANO));
    }

    private void startOnHandler() {
        if (started) return;
        started = true;
        IntentFilter filter = new IntentFilter(ACTION_PANO);
        try {
            context.registerReceiver(receiver, filter);
            receiverRegistered = true;
            emit("oem_camera_visibility_listener", "action", "registered", "ok", true,
                    "registered", true, "source_event", "startup");
        } catch (Throwable error) {
            receiverRegistered = false;
            emit("oem_camera_visibility_listener", "action", "registered", "ok", false,
                    "registered", false, "source_event", "startup",
                    "error", summary(error));
        }
        // Exactly one property snapshot per shell start/reconnect.  Later status requests replay
        // the cached value instead of querying SystemProperties again.
        String snapshot;
        try {
            snapshot = readSystemProperty(STARTUP_PROPERTY);
        } catch (Throwable error) {
            invalidateState();
            emit("oem_camera_visibility", "valid", false, "known", false, "visible", false,
                    "pano_state", -1, "source_event", "startup_snapshot",
                    "action", ACTION_PANO, "error", summary(error));
            return;
        }
        acceptState(snapshot, "startup_snapshot", ACTION_PANO);
    }

    private void stopOnHandler() {
        if (!started) return;
        started = false;
        if (receiverRegistered) {
            receiverRegistered = false;
            try {
                context.unregisterReceiver(receiver);
            } catch (Throwable error) {
                emit("oem_camera_visibility_listener", "action", "unregistered", "ok", false,
                        "registered", false, "source_event", "stop",
                        "error", summary(error));
            }
        }
        emit("oem_camera_visibility_listener", "action", "stopped", "ok", true,
                "registered", false, "source_event", "stop");
    }

    private void acceptState(String raw, String source, String action) {
        if (!started && !"status".equals(source)) return;
        int parsed = parseState(raw);
        if (parsed < 0) {
            invalidateState();
            emit("oem_camera_visibility", "valid", false, "known", false, "visible", false,
                    "pano_state", -1, "source_event", source,
                    "action", action, "registered", receiverRegistered,
                    "error", "invalid_pano_state");
            return;
        }
        known = true;
        panoState = parsed;
        visible = parsed == 1;
        emitVisibility(source, action);
    }

    private void invalidateState() {
        known = false;
        visible = false;
        panoState = -1;
    }

    private void emitVisibility(String source, String action) {
        emit("oem_camera_visibility", "valid", known, "known", known,
                "visible", known && visible,
                "pano_state", known ? panoState : -1, "source_event", source,
                "action", action, "registered", receiverRegistered);
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
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
        String message = cause.getMessage();
        return cause.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }
}
