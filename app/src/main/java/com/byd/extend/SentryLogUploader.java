package com.byd.extend;

import io.sentry.*;
import io.sentry.hints.SubmissionResult;
import io.sentry.protocol.Message;
import io.sentry.protocol.SentryId;
import io.sentry.util.HintUtils;
import java.io.*;
import java.nio.file.Files;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.zip.ZipFile;

/** HUD manual-report transport; never installed as a global automatic Sentry client. */
final class SentryLogUploader {
    static final long MAX_ZIP_BYTES = 39_000_000L;
    static final long CONFIRMATION_MS = 120_000L;

    static final class Result {
        final String phase, eventId, detail;
        Result(String phase, String eventId, String detail) {
            this.phase = phase; this.eventId = eventId; this.detail = detail;
        }
    }

    static void validate(File archive) throws IOException {
        if (!archive.isFile()) throw new IOException("Archive is missing");
        if (archive.length() <= 0 || archive.length() >= MAX_ZIP_BYTES)
            throw new IOException("Archive must be between 1 and 38,999,999 bytes");
        try (ZipFile zip = new ZipFile(archive)) {
            if (!zip.entries().hasMoreElements()) throw new IOException("Archive is empty");
        }
    }

    static SentryOptions options() {
        SentryOptions options = new SentryOptions();
        options.setDsn(BuildConfig.SENTRY_DSN);
        options.setSampleRate(1.0);
        options.setTracesSampleRate(0.0);
        options.setProfilesSampleRate(0.0);
        options.setSendDefaultPii(false);
        options.setEnableUncaughtExceptionHandler(false);
        options.setEnableAutoSessionTracking(false);
        options.setEnableShutdownHook(false);
        options.setMaxAttachmentSize(MAX_ZIP_BYTES);
        options.setShutdownTimeoutMillis(2_000);
        options.setFlushTimeoutMillis(2_000);
        options.getIntegrations().clear();
        options.setBeforeSend((event, hint) -> "true".equals(event.getTag("bydextend_manual_upload")) ? event : null);
        return options;
    }

    static SentryEvent event(String id, String title, String comment, boolean systemLogs) {
        SentryEvent event = new SentryEvent();
        Message message = new Message(); message.setMessage(title); event.setMessage(message);
        event.setLevel(SentryLevel.INFO);
        event.setRelease(BuildConfig.APPLICATION_ID + "@" + BuildConfig.VERSION_NAME + "+" + BuildConfig.VERSION_CODE);
        event.setTag("bydextend_manual_upload", "true");
        event.setTag("upload_id", id);
        event.setTag("system_logs", systemLogs ? "requested" : "unavailable_without_adb");
        event.setExtra("user_comment", comment);
        event.setFingerprints(Collections.singletonList("manual-diagnostic-upload:" + id));
        return event;
    }

    static Result upload(File dir, File archive, String title, String comment,
            boolean systemLogs, Consumer<String> accepted) {
        String eventId = "";
        SentryClient client = null;
        try {
            validate(archive);
            SentryOptions options = options();
            options.setDebug(true);
            options.setDiagnosticLevel(SentryLevel.WARNING);
            options.setLogger(new ILogger() {
                public boolean isEnabled(SentryLevel level) { return level.ordinal() >= SentryLevel.WARNING.ordinal(); }
                public void log(SentryLevel level, String message, Object... args) {
                    if (isEnabled(level)) LogShareFiles.diagnostic(dir, "sentry_transport", String.format(java.util.Locale.ROOT, message, args));
                }
                public void log(SentryLevel level, String message, Throwable error) {
                    if (isEnabled(level)) {
                        LogShareFiles.diagnostic(dir, "sentry_transport", message);
                        if (error != null) LogShareFiles.diagnostic(dir, "sentry_transport", error);
                    }
                }
                public void log(SentryLevel level, Throwable error, String message, Object... args) {
                    log(level, message, args);
                    if (isEnabled(level) && error != null) LogShareFiles.diagnostic(dir, "sentry_transport", error);
                }
            });
            client = new SentryClient(options);
            Hint hint = new Hint();
            // The SDK sends asynchronously; bytes remain valid even after timeout cleanup.
            hint.addAttachment(new Attachment(Files.readAllBytes(archive.toPath()), archive.getName(), "application/zip"));
            Delivery delivery = new Delivery();
            HintUtils.setTypeCheckHint(hint, delivery);
            SentryId id = client.captureEvent(event(dir.getName(), title, comment, systemLogs), hint);
            if (SentryId.EMPTY_ID.equals(id)) return new Result("failed", "", "Sentry rejected the report; see transport diagnostic");
            eventId = id.toString();
            accepted.accept(eventId);
            if (!delivery.done.await(CONFIRMATION_MS, TimeUnit.MILLISECONDS))
                return new Result("unconfirmed", eventId, "Delivery not confirmed within 120000ms");
            return delivery.success ? new Result("success", eventId, "uploaded")
                    : new Result("failed", eventId, "Sentry submission failed; see transport diagnostic");
        } catch (Exception error) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            return new Result(eventId.isEmpty() ? "failed" : "unconfirmed", eventId,
                    LogShareFiles.diagnostic(dir, "upload", error));
        } finally {
            try { if (client != null) client.close(false); }
            catch (Exception cleanup) { LogShareFiles.diagnostic(dir, "transport_cleanup", cleanup); }
        }
    }

    private static final class Delivery implements SubmissionResult {
        final CountDownLatch done = new CountDownLatch(1);
        volatile boolean success;
        public void setResult(boolean result) { success = result; done.countDown(); }
        public boolean isSuccess() { return success; }
    }
    private SentryLogUploader() { }
}
