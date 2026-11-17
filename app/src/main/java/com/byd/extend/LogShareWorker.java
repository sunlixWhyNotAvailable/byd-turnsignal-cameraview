package com.byd.extend;

import android.os.SystemClock;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.*;

/** Finite shell-UID job. No camera/vehicle initialization and no app-owned Context. */
public final class LogShareWorker {
    private final File dir;
    private final JSONObject state;
    private long lastProgress;

    LogShareWorker(File dir) throws Exception { this.dir = dir; state = LogShareFiles.read(dir); }

    public static void main(String[] args) {
        if (android.os.Process.myUid() != 2000 || args.length != 1) System.exit(2);
        int result = 0;
        try {
            File dir = new File(args[0]);
            new LogShareWorker(dir).run(true);
        } catch (Throwable error) {
            error.printStackTrace(); result = 1;
        }
        System.exit(result);
    }

    void run(boolean shell) throws Exception {
        try {
            if (shell) {
                state.put("pid", android.os.Process.myPid());
                state.put("pid_start", LogShareShellHost.processStart(android.os.Process.myPid()));
                state.put("boot", LogShareShellHost.bootId());
            }
            state.put("system_logs", shell);
            save("archiving");
            CompatibilityBundleExporter.ExportControl control = new CompatibilityBundleExporter.ExportControl(progress -> {
                long now = SystemClock.elapsedRealtime();
                if (now - lastProgress < 1000) return;
                lastProgress = now;
                try {
                    state.put("path", progress.currentPath).put("bytes", progress.bytes).put("total", progress.totalBytes);
                    save("archiving");
                } catch (Exception e) { LogShareFiles.diagnostic(dir, "progress", e); }
            }, () -> new File(dir, "cancel").exists());
            File snapshot = new File(dir, "snapshot.zip");
            File archive;
            if (shell) {
                archive = DiagnosticLogExporter.export(dir, new DiagnosticLogExporter.Snapshot(snapshot),
                        DiagnosticLogExporter.deviceIdentity(), this::textCommand, this::streamCommand,
                        state.getLong("created_at"), control);
                File target = new File(dir, "archive.zip");
                if (!archive.renameTo(target)) throw new IOException("Cannot publish prepared log archive");
                archive = target;
            } else {
                archive = new File(dir, "archive.zip");
                if (!snapshot.renameTo(archive)) throw new IOException("Cannot publish app log archive");
            }
            CompatibilityBundleExporter.checkCancelled(control);
            state.put("bytes", archive.length()).put("total", archive.length()).put("path", "");
            if (!"developer".equals(state.getString("destination"))) { save("ready"); return; }
            if (archive.length() >= SentryLogUploader.MAX_ZIP_BYTES) { save("oversized"); return; }
            SentryLogUploader.validate(archive);
            synchronized (LogShareFiles.class) {
                try (FileOutputStream lockFile = new FileOutputStream(new File(dir, "transition.lock"), true);
                        java.nio.channels.FileLock ignored = lockFile.getChannel().lock()) {
                    CompatibilityBundleExporter.checkCancelled(control);
                    state.put("upload_at", SystemClock.elapsedRealtime());
                    save("uploading");
                }
            }
            SentryLogUploader.Result result = SentryLogUploader.upload(dir, archive,
                    state.getString("title"), state.optString("comment"), shell, eventId -> {
                        try { state.put("event_id", eventId); save("uploading"); }
                        catch (Exception e) { LogShareFiles.diagnostic(dir, "persist_event_id", e); }
                    });
            state.put("event_id", result.eventId).put("detail", result.detail);
            save(result.phase);
            LogShareFiles.cleanupArchive(dir);
        } catch (Throwable error) {
            String phase = error instanceof CompatibilityBundleExporter.CancellationException ? "cancelled"
                    : state.optString("phase").equals("uploading") ? "unconfirmed" : "failed";
            state.put("detail", LogShareFiles.diagnostic(dir, "worker_" + state.optString("phase"), error));
            save(phase);
            LogShareFiles.cleanupArchive(dir);
        }
    }

    private void save(String phase) throws Exception {
        boolean transition = !phase.equals(state.optString("phase"));
        state.put("phase", phase).put("updated_at", System.currentTimeMillis());
        if (!LogShareFiles.active(phase)) state.put("ended_at", SystemClock.elapsedRealtime());
        LogShareFiles.write(dir, state);
        if (transition) LogShareFiles.event(dir, "phase", "phase=" + phase
                + " uid=" + android.os.Process.myUid() + " pid=" + android.os.Process.myPid()
                + " system_logs=" + state.optBoolean("system_logs") + " bytes=" + state.optLong("bytes"));
    }

    private DiagnosticLogExporter.CommandResult textCommand(String command) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        DiagnosticLogExporter.CommandResult result = streamCommand(command, output);
        String text = new String(output.toByteArray(), StandardCharsets.UTF_8);
        return result.ok ? DiagnosticLogExporter.CommandResult.success(text)
                : DiagnosticLogExporter.CommandResult.failure(text, result.error, result.exitCode);
    }

    private DiagnosticLogExporter.CommandResult streamCommand(String requested, OutputStream output) {
        // Commands come only from DiagnosticLogExporter's fixed collector list, never IPC input.
        String command = "extend_dropbox_binary_export".equals(requested)
                ? DropBoxTombstoneCollector.command(System.getenv("CLASSPATH")) : requested;
        command = command.replace("2>/dev/null", "");
        java.lang.Process process = null;
        File stderr = new File(dir, "collector.stderr");
        File stdout = new File(dir, "collector.stdout");
        Exception failure = null;
        int exit = -1;
        try {
            process = new ProcessBuilder("/system/bin/setsid", "/system/bin/sh", "-c", "echo $$; " + command)
                    .redirectOutput(stdout).redirectError(stderr).start();
            process.getOutputStream().close();
            if (!process.waitFor(5, TimeUnit.SECONDS)) throw new IOException("Collector deadline exceeded: " + requested);
            exit = process.exitValue();
            if (exit != 0) failure = new IOException("Collector exited " + exit);
        } catch (Exception error) {
            failure = error;
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
        } finally {
            if (process != null) {
                try (InputStream in = new FileInputStream(stdout)) {
                    // Only this setsid collector group is ours, including pipeline descendants.
                    int group = readCollectorPid(in);
                    try { android.system.Os.kill(-group, android.system.OsConstants.SIGKILL); }
                    catch (android.system.ErrnoException error) {
                        if (error.errno != android.system.OsConstants.ESRCH) throw error;
                    }
                } catch (Exception cleanup) {
                    if (failure == null) failure = cleanup; else failure.addSuppressed(cleanup);
                }
                if (process.isAlive()) process.destroyForcibly();
            }
        }
        // Only this job thread writes the ZIP; no timed-out reader can corrupt its next entry.
        if (process != null) try (InputStream in = new FileInputStream(stdout)) {
            readCollectorPid(in);
            byte[] buffer = new byte[64 * 1024]; int n;
            long bytes = 0;
            while ((n = in.read(buffer)) != -1) {
                if (new File(dir, "cancel").exists()) throw new CompatibilityBundleExporter.CancellationException();
                bytes += n;
                if (output instanceof ByteArrayOutputStream && bytes > 16L * 1024 * 1024)
                    throw new IOException("Text collector exceeded 16 MiB");
                output.write(buffer, 0, n);
            }
        } catch (Exception copy) {
            if (failure == null) failure = copy; else failure.addSuppressed(copy);
        }
        if (stderr.isFile()) try {
            String detail = new String(Files.readAllBytes(stderr.toPath()), StandardCharsets.UTF_8);
            if (!detail.isBlank()) {
                IOException reported = new IOException("Collector stderr (exit=" + exit + "): " + detail);
                if (failure == null) failure = reported; else failure.addSuppressed(reported);
            }
        } catch (IOException read) {
            if (failure == null) failure = read; else failure.addSuppressed(read);
        }
        for (File file : new File[] {stdout, stderr}) {
            if (file.exists() && !file.delete()) LogShareFiles.diagnostic(dir, "collector_cleanup", "Cannot remove " + file.getName());
        }
        return failure == null ? DiagnosticLogExporter.CommandResult.success("")
                : DiagnosticLogExporter.CommandResult.failure("", LogShareFiles.diagnostic(dir,
                        "collector: " + requested, failure), exit);
    }

    static int readCollectorPid(InputStream in) throws IOException {
        StringBuilder header = new StringBuilder();
        int value;
        while ((value = in.read()) != '\n') {
            if (value < '0' || value > '9' || header.length() >= 10)
                throw new IOException("Collector PID header invalid");
            header.append((char) value);
        }
        try {
            int pid = Integer.parseInt(header.toString());
            if (pid <= 1) throw new NumberFormatException("PID must exceed 1");
            return pid;
        } catch (NumberFormatException error) { throw new IOException("Collector PID invalid", error); }
    }
}
