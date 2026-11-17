package com.byd.extend;

import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;

/** Each manual operation owns its files until its consumer releases them. */
final class LogShareFiles {
    static final long RETENTION_MS = 10 * 60_000L;
    static String newId() { return UUID.randomUUID().toString(); }

    static File directory(File root, String id) throws IOException {
        if (id == null || !id.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))
            throw new IOException("Invalid log operation ID");
        File dir = new File(root, id);
        if (!dir.getCanonicalFile().getParentFile().equals(root.getCanonicalFile()))
            throw new IOException("Log operation outside workspace");
        if (!dir.isDirectory()) {
            if (!dir.mkdirs()) throw new IOException("Cannot create log operation directory");
            dir.setReadable(false, false); dir.setWritable(false, false); dir.setExecutable(false, false);
            dir.setReadable(true, true); dir.setWritable(true, true); dir.setExecutable(true, true);
        }
        return dir;
    }

    static JSONObject read(File dir) throws IOException {
        try { return new JSONObject(new String(Files.readAllBytes(new File(dir, "state.json").toPath()), StandardCharsets.UTF_8)); }
        catch (org.json.JSONException e) { throw new IOException("Invalid log operation state", e); }
    }

    static void write(File dir, JSONObject state) throws IOException {
        File part = new File(dir, "state.json.part");
        try (FileOutputStream out = new FileOutputStream(part)) {
            out.write(state.toString().getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        }
        if (!part.renameTo(new File(dir, "state.json"))) throw new IOException("Cannot commit log operation state");
    }

    static boolean preparing(String phase) {
        return phase.equals("waiting") || phase.equals("copying") || phase.equals("archiving");
    }
    static boolean active(String phase) { return preparing(phase) || phase.equals("uploading"); }
    static boolean needsArchive(String phase) {
        return active(phase) || phase.equals("oversized") || phase.equals("ready");
    }

    static synchronized void cancelPreparation(File dir) throws IOException {
        try (FileOutputStream lockFile = new FileOutputStream(new File(dir, "transition.lock"), true);
                java.nio.channels.FileLock ignored = lockFile.getChannel().lock()) {
            if (preparing(read(dir).optString("phase"))) {
                try (FileOutputStream marker = new FileOutputStream(new File(dir, "cancel"))) { marker.getFD().sync(); }
            }
        }
    }

    static String title(String version, String comment, String fallback) {
        String summary = comment == null ? "" : comment.trim().replaceAll("\\s+", " ");
        if (summary.isEmpty()) summary = fallback;
        int end = summary.offsetByCodePoints(0, Math.min(100, summary.codePointCount(0, summary.length())));
        return "BYD Extend " + version + " — " + summary.substring(0, end);
    }

    static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        int n;
        while ((n = in.read(buffer)) >= 0) out.write(buffer, 0, n);
    }

    static String diagnostic(File dir, String operation, Throwable error) {
        StringWriter trace = new StringWriter();
        error.printStackTrace(new PrintWriter(trace));
        return diagnostic(dir, operation, trace.toString());
    }

    static synchronized String diagnostic(File dir, String operation, String detail) {
        return record(dir, operation, detail, true);
    }

    static synchronized void event(File dir, String operation, String detail) {
        record(dir, operation, detail, false);
    }

    private static String record(File dir, String operation, String detail, boolean error) {
        String safe = detail.replace(BuildConfig.SENTRY_DSN, "[sentry-dsn]")
                .replaceAll("(?i)([?&](?:password|token|key|auth)=[^\\s&]*)", "[redacted]");
        try {
            JSONObject entry = new JSONObject().put("time", System.currentTimeMillis())
                    .put("operation_id", dir.getName()).put("operation", operation).put("detail", safe);
            try (FileOutputStream out = new FileOutputStream(new File(dir, "diagnostic.jsonl"), true)) {
                out.write((entry + "\n").getBytes(StandardCharsets.UTF_8));
                out.getFD().sync();
            }
        } catch (Exception secondary) {
            android.util.Log.e("BydExtendLogShare", "Cannot persist diagnostic for " + operation, secondary);
        }
        String message = "id=" + dir.getName() + " operation=" + operation + " " + safe;
        if (error) android.util.Log.e("BydExtendLogShare", message);
        else android.util.Log.i("BydExtendLogShare", message);
        return safe;
    }

    static void cleanupArchive(File dir) {
        for (String name : new String[] { "snapshot.zip", "archive.zip" }) {
            File file = new File(dir, name);
            if (file.exists() && !file.delete()) diagnostic(dir, "cleanup", "Cannot remove " + name);
        }
        File shared = new File(dir, "shared_logs");
        File[] files = shared.listFiles();
        if (files != null) for (File file : files) {
            if (file.isFile() && !file.delete()) diagnostic(dir, "cleanup", "Cannot remove " + file.getName());
        }
    }

    private LogShareFiles() { }
}
