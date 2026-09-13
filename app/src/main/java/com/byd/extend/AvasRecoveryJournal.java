package com.byd.extend;

import android.content.Context;
import android.os.Process;
import android.os.SystemClock;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Small process-safe-enough startup journal available before the foreground service log exists. */
final class AvasRecoveryJournal {
    static final String FILE_NAME = "avas-recovery-journal.jsonl";
    static final String PREVIOUS_FILE_NAME = "avas-recovery-journal.1.jsonl";
    static final long MAX_BYTES = 256L * 1024L;

    private AvasRecoveryJournal() {}

    static void event(Context context, String kind, Object... fields) {
        if (context == null || kind == null) return;
        synchronized (AvasRecoveryJournal.class) {
            try {
                File directory = recoveryDirectory(context);
                if (directory == null) return;
                File current = new File(directory, FILE_NAME);
                rotateIfNeeded(current, new File(directory, PREVIOUS_FILE_NAME));
                JSONObject event = new JSONObject()
                        .put("kind", kind)
                        .put("source", "avas_recovery")
                        .put("pid", Process.myPid())
                        .put("wall_time", new SimpleDateFormat(
                                "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).format(new Date()))
                        .put("wall_time_ms", System.currentTimeMillis())
                        .put("elapsed_ms", SystemClock.elapsedRealtime())
                        .put("uptime_ms", SystemClock.uptimeMillis());
                String boot = bootId();
                if (!boot.isEmpty()) event.put("boot_id", boot);
                if (fields != null) {
                    for (int index = 0; index + 1 < fields.length; index += 2) {
                        event.put(String.valueOf(fields[index]), fields[index + 1]);
                    }
                }
                byte[] line = (event.toString() + "\n").getBytes(StandardCharsets.UTF_8);
                try (FileOutputStream output = new FileOutputStream(current, true)) {
                    output.write(line);
                    output.getFD().sync();
                }
            } catch (Throwable ignored) {
                // Recovery must never depend on diagnostics.
            }
        }
    }

    static void rotateIfNeeded(File current, File previous) throws Exception {
        if (!current.isFile() || current.length() < MAX_BYTES) return;
        Files.move(current.toPath(), previous.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    private static File recoveryDirectory(Context context) {
        File external = context.getExternalFilesDir(null);
        if (external != null) {
            File captures = new File(external, "captures");
            if (captures.isDirectory() || captures.mkdirs()) return captures;
        }
        File captures = new File(context.getFilesDir(), "captures");
        return captures.isDirectory() || captures.mkdirs() ? captures : null;
    }

    private static String bootId() {
        try {
            return new String(Files.readAllBytes(
                    new File("/proc/sys/kernel/random/boot_id").toPath()),
                    StandardCharsets.UTF_8).trim();
        } catch (Throwable ignored) {
            return "";
        }
    }
}
