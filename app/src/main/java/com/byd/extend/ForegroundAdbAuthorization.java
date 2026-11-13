package com.byd.extend;

import android.content.Context;
import android.os.SystemClock;
import java.io.File;
import org.json.JSONObject;

/** The foreground RSA owner is application-scoped, not a camera or Activity callback. */
final class ForegroundAdbAuthorization {
    private static final java.util.concurrent.atomic.AtomicLong completedRequest =
            new java.util.concurrent.atomic.AtomicLong();
    private ForegroundAdbAuthorization() {}

    static {
        LocalAdbClient.AUTHORIZATION.addForegroundListener(() -> {
            AdbAuthorizationSession.RequestSnapshot snapshot =
                    LocalAdbClient.AUTHORIZATION.foregroundSnapshot();
            if (snapshot != null && !snapshot.isActive()
                    && completedRequest.getAndAccumulate(snapshot.requestId, Math::max)
                            < snapshot.requestId) {
                CameraHelperService.foregroundAdbFinished(snapshot.requestId,
                        snapshot.result != null && snapshot.result.ok);
            }
        });
    }

    static AdbAuthorizationSession.Submission request(
            Context context, LocalAdbClient.PromptMode mode, String reason) {
        Context app = context.getApplicationContext();
        return LocalAdbClient.AUTHORIZATION.requestForeground(mode,
                LocalAdbClient::cancelPendingAuthorization,
                (requestId, requestedMode, cancellationToken) -> {
                    AsyncServiceLog log = new AsyncServiceLog(
                            () -> logFile(app, requestId), 0L);
                    java.util.function.BiConsumer<String, Object[]> events =
                            (kind, fields) -> record(log, requestId, kind, fields);
                    try {
                        events.accept("adb_foreground_started", new Object[]{
                                "mode", requestedMode.name(), "reason", reason});
                        if (GuardRecovery.isUserShutdownActive(app)) {
                            return LocalAdbClient.Result.superseded();
                        }
                        LocalAdbClient.Result result = LocalAdbClient.authorize(
                                app, requestedMode, cancellationToken, events);
                        events.accept("adb_foreground_result", new Object[]{
                                "ok", result.ok, "error", result.error,
                                "public_key_sent", result.publicKeySent,
                                "public_key_write_started", result.publicKeyWriteStarted,
                                "fingerprint", result.fingerprint});
                        return result;
                    } finally {
                        log.close();
                    }
                });
    }

    private static File logFile(Context app, long requestId) {
        File base = app.getExternalFilesDir(null);
        if (base == null) base = app.getFilesDir();
        File captures = new File(base, "captures");
        if (!captures.isDirectory() && !captures.mkdirs()) captures = app.getFilesDir();
        return new File(captures, "helper-service-adb-authorization-"
                + LogTime.fileStamp(System.currentTimeMillis()) + "-" + requestId + ".jsonl");
    }

    private static void record(AsyncServiceLog log, long requestId, String kind, Object[] fields) {
        try {
            JSONObject event = new JSONObject().put("kind", kind)
                    .put("source", "foreground_adb").put("request_id", requestId)
                    .put("t_ms", SystemClock.elapsedRealtime())
                    .put("wall_time_ms", System.currentTimeMillis());
            for (int i = 0; fields != null && i + 1 < fields.length; i += 2) {
                event.put(String.valueOf(fields[i]), fields[i + 1]);
            }
            log.appendRaw(event.toString());
        } catch (Exception ignored) { /* Diagnostics must not affect authorization. */ }
    }
}
