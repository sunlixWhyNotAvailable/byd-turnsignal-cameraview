package com.byd.extend;

import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Reached only after the common helper verifies the exact application UID. */
final class LogShareShellHost {
    static final int START = 1, STATUS = 2, CANCEL = 3, ARCHIVE = 4, RELEASE = 5;
    private final File root;
    private final ExecutorService staging = Executors.newCachedThreadPool(r -> new Thread(r, "log-share-staging"));
    private final Set<String> stagingIds = new HashSet<>();
    private final java.util.function.Consumer<Runnable> flush;

    LogShareShellHost(int appUid, java.util.function.Consumer<Runnable> flush) {
        root = new File("/data/local/tmp/bydextend-log-share-" + appUid);
        this.flush = flush;
    }

    synchronized void transact(Parcel data, Parcel reply) throws Exception {
        int action = data.readInt();
        String id = data.readString();
        File dir = LogShareFiles.directory(root, id);
        if (action == START) {
            String request = data.readString();
            ParcelFileDescriptor descriptor = ParcelFileDescriptor.CREATOR.createFromParcel(data);
            try {
                if (!new File(dir, "state.json").exists()) {
                    JSONObject state = new JSONObject(request);
                    if (!id.equals(state.getString("id"))) throw new IOException("Operation ID mismatch");
                    if (!Set.of("developer", "share", "save").contains(state.getString("destination")))
                        throw new IOException("Unknown log destination");
                    state.put("phase", "copying").put("system_logs", true);
                    LogShareFiles.write(dir, state);
                    stagingIds.add(id);
                    ParcelFileDescriptor owned = descriptor;
                    staging.execute(() -> stage(dir, owned));
                    descriptor = null;
                }
            } finally { if (descriptor != null) descriptor.close(); }
        } else if (action == CANCEL) {
            LogShareFiles.cancelPreparation(dir);
        } else if (action == ARCHIVE) {
            JSONObject state = status(dir);
            if (!Set.of("ready", "oversized").contains(state.optString("phase"))) throw new IOException("Archive is not ready");
            try (ParcelFileDescriptor fd = ParcelFileDescriptor.open(new File(dir, "archive.zip"), ParcelFileDescriptor.MODE_READ_ONLY)) {
                reply.writeNoException();
                fd.writeToParcel(reply, 0);
                return;
            }
        } else if (action == RELEASE) {
            JSONObject state = status(dir);
            if (LogShareFiles.active(state.optString("phase"))) throw new IOException("Cannot release active export");
            LogShareFiles.cleanupArchive(dir);
        } else if (action != STATUS) throw new IOException("Unknown log operation");
        reply.writeNoException(); reply.writeString(status(dir).toString());
    }

    private void stage(File dir, ParcelFileDescriptor descriptor) {
        try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(descriptor);
                OutputStream out = new FileOutputStream(new File(dir, "snapshot.zip"))) {
            LogShareFiles.copy(in, out);
        } catch (Exception error) { failed(dir, "snapshot_transfer", error); removeStaging(dir); return; }
        try {
            java.util.concurrent.CountDownLatch flushed = new java.util.concurrent.CountDownLatch(1);
            flush.accept(flushed::countDown);
            if (!flushed.await(5, java.util.concurrent.TimeUnit.SECONDS))
                LogShareFiles.diagnostic(dir, "helper_flush", "Existing helper journal flush not confirmed within 5000ms");
            if (new File(dir, "cancel").exists()) {
                JSONObject state = LogShareFiles.read(dir).put("phase", "cancelled");
                LogShareFiles.write(dir, state); LogShareFiles.cleanupArchive(dir); return;
            }
            String apk = System.getenv("CLASSPATH");
            if (apk == null || apk.isEmpty()) throw new IOException("Shell CLASSPATH unavailable");
            java.lang.Process child = new ProcessBuilder("/system/bin/app_process", "/system/bin",
                    "--nice-name=bydextend_log_share", LogShareWorker.class.getName(), dir.getAbsolutePath())
                    .redirectOutput(new File(dir, "worker.stdout"))
                    .redirectError(new File(dir, "worker.stderr")).start();
            // The finite child owns the job after this handoff, not an Activity or callback.
            child.waitFor();
            synchronized (this) {
                removeStaging(dir);
                JSONObject state = LogShareFiles.read(dir);
                if (LogShareFiles.active(state.optString("phase")))
                    throw new IOException("Log worker exited " + child.exitValue() + ": "
                            + new String(Files.readAllBytes(new File(dir, "worker.stderr").toPath()), StandardCharsets.UTF_8));
            }
        } catch (Exception error) { failed(dir, "shell_worker", error); }
        finally { removeStaging(dir); }
    }

    private synchronized void removeStaging(File dir) { stagingIds.remove(dir.getName()); }

    private JSONObject status(File dir) throws Exception {
        JSONObject state = LogShareFiles.read(dir);
        if (LogShareFiles.active(state.optString("phase")) && !stagingIds.contains(dir.getName())) {
            int pid = state.optInt("pid", -1);
            if (pid <= 0 || !bootId().equals(state.optString("boot"))
                    || !processStart(pid).equals(state.optString("pid_start"))) {
                String old = state.optString("phase");
                state.put("phase", old.equals("uploading") ? "unconfirmed" : "failed")
                        .put("detail", "Log worker no longer running; previous phase=" + old);
                LogShareFiles.write(dir, state);
                LogShareFiles.diagnostic(dir, "worker_lost", state.optString("detail"));
            }
        }
        return state;
    }

    private synchronized void failed(File dir, String operation, Exception error) {
        try {
            JSONObject state = LogShareFiles.read(dir);
            state.put("detail", LogShareFiles.diagnostic(dir, operation, error))
                    .put("phase", state.optString("phase").equals("uploading") ? "unconfirmed" : "failed");
            LogShareFiles.write(dir, state);
            LogShareFiles.cleanupArchive(dir);
        } catch (Exception secondary) { LogShareFiles.diagnostic(dir, operation + "_status", secondary); }
    }

    static String processStart(int pid) throws IOException {
        try {
            String text = new String(Files.readAllBytes(new File("/proc/" + pid + "/stat").toPath()), StandardCharsets.UTF_8);
            return text.substring(text.lastIndexOf(')') + 2).split(" ")[19];
        } catch (java.nio.file.NoSuchFileException missing) { return ""; }
    }
    static String bootId() throws IOException {
        return new String(Files.readAllBytes(new File("/proc/sys/kernel/random/boot_id").toPath()), StandardCharsets.UTF_8).trim();
    }
}
