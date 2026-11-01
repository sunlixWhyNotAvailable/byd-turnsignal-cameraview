package com.byd.extend;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import java.lang.reflect.Method;

/** The two fixed OEM IDC requests, owned only by an active OFF-state Motion session. */
final class MotionCameraPreparation {
    interface Listener {
        void onSample(String session, int power, int online, int stream, String error);
        void onRelease(String session, String error);
        void onStopped();
    }

    private static final long IDC_PERIOD_MS = 500;
    private static final long STREAM_PERIOD_MS = 1_000;
    private static final byte[] IDC_WAKE = {76, 0, 0, 0, 0, 0, 0, 0};
    private final Context context;
    private final Listener listener;
    private final Handler handler;
    private final Runnable tick = this::tick;
    private final Runnable reconcile = this::reconcile;
    private volatile String requestedSession = "";
    private volatile String readySession = "";
    private volatile boolean stopped = true;
    private String activeSession = "";
    private Object manager;
    private Method getInt, setInt, setBuffer;
    private int streamRequest, idcWake, onlineFid, streamFid;
    private boolean releaseNeeded;
    private long nextReleaseAttemptMs;
    private final StreamCadence streamCadence = new StreamCadence();

    MotionCameraPreparation(Context context, Listener listener) {
        this.context = context;
        this.listener = listener;
        // Native inference and camera detach can block Motion's control thread for seconds.
        HandlerThread thread = new HandlerThread("motion-idc-keepalive");
        thread.start();
        handler = new Handler(thread.getLooper());
    }

    synchronized void start(String session) {
        if (session.equals(requestedSession)) return;
        stopped = false;
        requestedSession = session;
        readySession = "";
        handler.post(reconcile);
    }

    synchronized void stop() {
        // A pending release already owns its retry. Repeated stop must not accelerate it.
        if (requestedSession.isEmpty()) return;
        requestedSession = "";
        readySession = "";
        handler.post(reconcile);
    }

    boolean ready(String session) { return session.equals(requestedSession) && session.equals(readySession); }
    boolean stopped() { return stopped; }

    static boolean sourceReady(int power, int online, int stream) {
        return power == 0 && online == 1 && stream == 1;
    }

    static boolean remoteReady(int power, boolean prepared, long frameAtMs, long nowMs) {
        return power == 1 || power == 2
                || power == 0 && (prepared || CameraFrameFreshness.fresh(frameAtMs, nowMs));
    }

    static final class StreamCadence {
        private long nextAttemptMs;

        boolean reserveAttempt(long nowMs) {
            if (nowMs < nextAttemptMs) return false;
            nextAttemptMs = nowMs + STREAM_PERIOD_MS;
            return true;
        }

        void reset() { nextAttemptMs = 0; }
    }

    private void reconcile() {
        handler.removeCallbacks(reconcile);
        handler.removeCallbacks(tick);
        String requested = requestedSession;
        if (!activeSession.equals(requested)) {
            if (!release()) {
                handler.postDelayed(reconcile, STREAM_PERIOD_MS);
                return;
            }
            activeSession = requested;
            streamCadence.reset();
        }
        if (!activeSession.isEmpty()) tick();
        else {
            synchronized (this) {
                if (!requestedSession.isEmpty()) { handler.post(reconcile); return; }
                stopped = true;
            }
            listener.onStopped();
        }
    }

    private void tick() {
        String session = activeSession;
        if (session.isEmpty() || !session.equals(requestedSession)) { reconcile(); return; }
        long started = SystemClock.elapsedRealtime();
        int power = -1, online = -1, stream = -1;
        String error = "";
        try {
            initialize();
            power = read(AvasTelemetryController.POWER_DEVICE, AvasTelemetryController.POWER_FID);
            if (power == 0 && session.equals(requestedSession)) {
                if (streamCadence.reserveAttempt(started)) {
                    releaseNeeded = true; // An uncertain Binder result still requires stream0.
                    requireSuccess("stream1", setInt.invoke(manager, 1049, streamRequest, 1));
                }
                if (session.equals(requestedSession)) {
                    requireSuccess("idc", setBuffer.invoke(manager, 1049, idcWake, IDC_WAKE));
                    online = read(1001, onlineFid);
                    stream = read(1038, streamFid);
                }
            } else {
                release();
            }
        } catch (Exception failure) {
            error = describe(failure);
            release();
        }
        readySession = sourceReady(power, online, stream) && error.isEmpty()
                && session.equals(requestedSession) ? session : "";
        listener.onSample(session, power, online, stream, error);
        if (session.equals(requestedSession)) {
            handler.postDelayed(tick, Math.max(1, IDC_PERIOD_MS - (SystemClock.elapsedRealtime() - started)));
        } else reconcile();
    }

    private void initialize() throws Exception {
        if (manager != null) return;
        Object next = context.getSystemService("auto");
        if (next == null) throw new IllegalStateException("BYDAutoManager unavailable");
        getInt = next.getClass().getMethod("getInt", int.class, int.class);
        setInt = next.getClass().getMethod("setInt", int.class, int.class, int.class);
        setBuffer = next.getClass().getMethod("setBuffer", int.class, int.class, byte[].class);
        streamRequest = fid("Special", "SPECIAL_SENTRY_MODE_VIDEO_STREAM_SOURCE_SET");
        idcWake = fid("Special", "SPECIAL_SENTRY_MODE_WAKEUP_INTELLIGENT_DRIVING_CONTROLER_SET");
        onlineFid = fid("Bodywork", "BODYWORK_ONLINE_HAS_0x02F000");
        streamFid = fid("Adas", "ADAS_AVM_VIDEO_STREAM_STATUS");
        manager = next;
    }

    private static int fid(String group, String name) throws Exception {
        return Class.forName("android.hardware.bydauto.BYDAutoFeatureIds$" + group)
                .getField(name).getInt(null);
    }

    private int read(int device, int fid) throws Exception {
        return ((Number) getInt.invoke(manager, device, fid)).intValue();
    }

    private boolean release() {
        readySession = "";
        if (!releaseNeeded) return true;
        long now = SystemClock.elapsedRealtime();
        if (now < nextReleaseAttemptMs) return false;
        nextReleaseAttemptMs = now + STREAM_PERIOD_MS;
        String error = "";
        try {
            requireSuccess("stream0", setInt.invoke(manager, 1049, streamRequest, 0));
            releaseNeeded = false;
            nextReleaseAttemptMs = 0;
        } catch (Exception failure) { error = describe(failure); }
        listener.onRelease(activeSession, error);
        return !releaseNeeded;
    }

    private static void requireSuccess(String command, Object result) {
        if (!(result instanceof Number) || ((Number) result).intValue() != 0)
            throw new IllegalStateException(command + " rejected: " + result);
    }

    private static String describe(Throwable error) {
        return StockAvmShellProtocol.diagnostic(error);
    }
}
