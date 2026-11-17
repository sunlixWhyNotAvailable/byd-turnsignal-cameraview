package com.byd.extend;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import androidx.core.content.ContextCompat;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;

/** Application-scoped UI observer and owner of manual exports; never owns camera runtimes. */
public final class LogShareRuntime {
    private static LogShareRuntime instance;
    public static synchronized LogShareRuntime get(Context context) {
        if (instance == null) instance = new LogShareRuntime(context.getApplicationContext());
        return instance;
    }
    private final Context context;
    private final File root;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService preparation = Executors.newSingleThreadExecutor(r -> new Thread(r, "log-share-prepare"));
    private final ExecutorService jobs = Executors.newCachedThreadPool(r -> new Thread(r, "log-share-job"));
    private final CopyOnWriteArrayList<Runnable> observers = new CopyOnWriteArrayList<>();
    private final Map<String, JSONObject> states = new LinkedHashMap<>();
    private final Map<String, JSONObject> ui = new HashMap<>();
    private final Set<String> fetching = new HashSet<>();
    private final Set<String> handoffs = new HashSet<>();
    private boolean loading = true;
    private volatile boolean dialogOpen;
    private volatile boolean saveOnly;
    private volatile boolean modalVisible;
    private java.lang.ref.WeakReference<AsyncServiceLog> activityLog = new java.lang.ref.WeakReference<>(null);

    void setActivityLog(AsyncServiceLog log) { activityLog = new java.lang.ref.WeakReference<>(log); }

    private LogShareRuntime(Context context) {
        this.context = context;
        root = new File(context.getFilesDir(), "log_share");
        jobs.execute(this::load);
    }

    public synchronized List<Job> snapshots() {
        List<Job> result = new ArrayList<>();
        for (JSONObject state : states.values()) result.add(new Job(state, ui.get(state.optString("id"))));
        return result;
    }
    public boolean isDialogOpen() { return dialogOpen; }
    public boolean isSaveOnly() { return saveOnly; }
    public synchronized boolean isLoaded() { return !loading; }
    public void setModalVisible(boolean value) { modalVisible = value; }
    public boolean isModalVisible() { return modalVisible || dialogOpen; }
    public synchronized boolean hasPreparation() {
        return states.values().stream().anyMatch(s -> LogShareFiles.preparing(s.optString("phase")));
    }
    public void open(boolean saveOnly) {
        this.saveOnly = saveOnly; dialogOpen = true;
        jobs.execute(() -> { refresh(true); observeActiveJobs(); }); changed();
    }
    public void closeDialog() { dialogOpen = false; changed(); }
    public void observe(Runnable listener) { observers.add(listener); }
    public void removeObserver(Runnable listener) { observers.remove(listener); }
    private void changed() { main.post(() -> observers.forEach(Runnable::run)); }

    public synchronized long cooldownSeconds() {
        long latest = 0;
        for (JSONObject state : states.values()) latest = Math.max(latest, state.optLong("upload_at"));
        return latest == 0 ? 0 : Math.max(0, (latest + 30_000 - SystemClock.elapsedRealtime() + 999) / 1000);
    }
    public synchronized boolean canPrepare() {
        return !loading && states.values().stream().noneMatch(s -> LogShareFiles.preparing(s.optString("phase"))
                || s.optString("phase").equals("oversized"));
    }
    public synchronized boolean canSend() { return canPrepare() && cooldownSeconds() == 0; }
    synchronized boolean active() { return states.values().stream().anyMatch(s -> LogShareFiles.active(s.optString("phase"))); }

    public synchronized void begin(String destination, String comment, String fallbackTitle) {
        if (!canPrepare() || (destination.equals("developer") && !canSend())) return;
        String id = LogShareFiles.newId();
        try {
            File dir = LogShareFiles.directory(root, id);
            JSONObject state = new JSONObject().put("id", id).put("destination", destination)
                    .put("comment", comment).put("title", LogShareFiles.title(BuildConfig.VERSION_NAME, comment, fallbackTitle))
                    .put("created_at", System.currentTimeMillis()).put("started_at", SystemClock.elapsedRealtime())
                    .put("phase", "waiting").put("app_boot", appBootCount());
            LogShareFiles.write(dir, state);
            states.put(id, state); ui.put(id, new JSONObject());
            ContextCompat.startForegroundService(context, new Intent(context, LogShareService.class));
            dialogOpen = false;
            jobs.execute(this::cleanupReleased);
            preparation.execute(() -> prepare(id));
        } catch (Exception error) { failure(id, "start", error); }
        changed();
    }

    private void prepare(String id) {
        try {
            File dir = LogShareFiles.directory(root, id);
            CountDownLatch flushed = new CountDownLatch(2);
            CameraHelperService.flushExistingLogs(flushed::countDown);
            AsyncServiceLog log = activityLog.get();
            if (log == null) flushed.countDown(); else log.flush(flushed::countDown);
            if (!flushed.await(5, TimeUnit.SECONDS)) LogShareFiles.diagnostic(dir, "flush", "Existing service journal flush not confirmed within 5000ms");
            JSONObject state = LogShareFiles.read(dir);
            state.put("phase", "copying"); writeState(dir, state);
            CompatibilityBundleExporter.ExportControl control = new CompatibilityBundleExporter.ExportControl(progress -> {
                try {
                    state.put("bytes", progress.bytes).put("total", progress.totalBytes).put("path", progress.currentPath);
                    writeState(dir, state);
                } catch (Exception e) { LogShareFiles.diagnostic(dir, "snapshot_progress", e); }
            }, () -> new File(dir, "cancel").exists());
            IBinder helper = null;
            try { helper = LogShareShellClient.available(); }
            catch (Exception unavailable) { LogShareFiles.diagnostic(dir, "helper_lookup", unavailable); }
            state.put("system_logs", helper != null);
            File appArchive = DiagnosticLogExporter.export(dir, DiagnosticLogExporter.snapshot(context),
                    DiagnosticLogExporter.deviceIdentity(), null, null, state.getLong("created_at"), control);
            File snapshot = new File(dir, "snapshot.zip");
            if (!appArchive.renameTo(snapshot)) throw new IOException("Cannot prepare snapshot descriptor");
            CompatibilityBundleExporter.checkCancelled(control);
            writeState(dir, state);
            if (helper != null) {
                // Persist the handoff before IPC: a lost reply must never start a duplicate upload.
                synchronized (this) { handoffs.add(id); }
                writeUi(id, "remote", true);
                CompatibilityBundleExporter.checkCancelled(control);
                JSONObject remote = LogShareShellClient.call(helper, LogShareShellHost.START, id, state, snapshot, null);
                writeRemoteState(dir, remote);
                if (new File(dir, "cancel").exists())
                    LogShareShellClient.call(helper, LogShareShellHost.CANCEL, id, null, null, null);
                if (!snapshot.delete()) LogShareFiles.diagnostic(dir, "snapshot_cleanup", "Snapshot descriptor transferred; local file retained");
            } else {
                jobs.execute(() -> {
                    try { new LogShareWorker(dir).run(false); }
                    catch (Exception error) { failure(id, "app_worker", error); }
                    finally { refresh(false); }
                });
            }
        } catch (Exception error) { failure(id, "prepare", error); }
        finally { synchronized (this) { handoffs.remove(id); } }
    }

    void refresh(boolean includeUnconfirmed) {
        for (Job job : snapshots()) {
            try {
                synchronized (this) { if (handoffs.contains(job.id)) continue; }
                File dir = LogShareFiles.directory(root, job.id);
                boolean localAction;
                synchronized (this) { localAction = ui.getOrDefault(job.id, new JSONObject()).optBoolean("local_action"); }
                if (job.remote && !localAction && (job.active || (includeUnconfirmed && job.phase.equals("unconfirmed")))) {
                    IBinder helper = LogShareShellClient.available();
                    if (helper == null) throw new IOException("Log share helper unavailable; remote outcome unknown");
                    JSONObject remote = LogShareShellClient.call(helper, LogShareShellHost.STATUS, job.id, null, null, null);
                    writeRemoteState(dir, remote);
                } else if ((!job.remote || localAction) && job.active) {
                    JSONObject state = LogShareFiles.read(dir);
                    synchronized (this) { states.put(job.id, state); }
                }
                Job current = find(job.id);
                if (current != null && (current.phase.equals("ready") || current.phase.equals("oversized"))
                        && !archive(job.id).isFile()) fetchArchive(current);
            } catch (Exception error) { failure(job.id, "status", error); }
        }
        changed();
    }

    private void fetchArchive(Job job) throws Exception {
        synchronized (this) { if (!fetching.add(job.id)) return; }
        try {
            File dir = LogShareFiles.directory(root, job.id);
            File target = archive(job.id);
            File part = new File(target.getPath() + ".part");
            if (job.remote) {
                LogShareShellClient.call(LogShareShellClient.available(), LogShareShellHost.ARCHIVE,
                        job.id, null, null, part);
            } else try (InputStream in = new FileInputStream(new File(dir, "archive.zip"));
                        OutputStream out = new FileOutputStream(part)) { LogShareFiles.copy(in, out); }
            if (!part.renameTo(target)) throw new IOException("Cannot publish shared archive");
        } finally { synchronized (this) { fetching.remove(job.id); } }
    }

    public synchronized Job find(String id) {
        JSONObject state = states.get(id);
        return state == null ? null : new Job(state, ui.get(id));
    }
    public File archive(String id) throws IOException {
        Job job = find(id);
        if (job == null) throw new IOException("Log operation missing");
        return new File(LogShareFiles.directory(root, id), DiagnosticLogExporter.archiveName(job.createdAt));
    }
    public boolean archiveReady(String id) {
        try { return archive(id).isFile(); } catch (IOException ignored) { return false; }
    }

    public void cancel(String id) {
        jobs.execute(() -> {
            Job job = find(id);
            if (job == null || !job.preparing) return;
            try {
                boolean localAction;
                boolean handingOff;
                synchronized (this) {
                    localAction = ui.getOrDefault(id, new JSONObject()).optBoolean("local_action");
                    handingOff = handoffs.contains(id);
                }
                if (job.remote && !localAction && !handingOff) LogShareShellClient.call(LogShareShellClient.available(), LogShareShellHost.CANCEL, id, null, null, null);
                else LogShareFiles.cancelPreparation(LogShareFiles.directory(root, id));
            } catch (Exception error) { failure(id, "cancel", error); }
        });
    }

    public void hide(String id) { setUi(id, "hidden", true); }
    public void handled(String id) { setUi(id, "local_action", true); setUi(id, "handled", true); }
    public void resumeUi(String pendingDocumentId) {
        for (Job job : snapshots()) {
            if (!job.id.equals(pendingDocumentId) && (job.phase.equals("ready") || job.phase.equals("oversized")))
                setUi(job.id, "handled", false);
        }
    }
    public void anotherApp(String id) { setUi(id, "share_requested", true); changed(); }
    public void finish(String id, String phase, Throwable error) {
        setUi(id, "local_action", true);
        jobs.execute(() -> {
            try {
                File dir = LogShareFiles.directory(root, id);
                JSONObject state = LogShareFiles.read(dir);
                if (error != null) state.put("detail", LogShareFiles.diagnostic(dir, phase, error));
                state.put("phase", phase).put("ended_at", SystemClock.elapsedRealtime());
                if (phase.equals("shared")) state.put("shared_at", System.currentTimeMillis());
                writeState(dir, state);
                if (!phase.equals("shared")) archive(id).delete();
                release(id);
            } catch (Exception e) { failure(id, "finish", e); }
        });
    }

    public void save(String id, Uri destination) {
        if (destination == null) { finish(id, "cancelled", null); return; }
        try {
            File dir = LogShareFiles.directory(root, id);
            setUi(id, "local_action", true);
            JSONObject state = LogShareFiles.read(dir).put("phase", "copying");
            writeState(dir, state);
            ContextCompat.startForegroundService(context, new Intent(context, LogShareService.class));
        }
        catch (Exception error) { failure(id, "save_service", error); return; }
        jobs.execute(() -> {
            try {
                File dir = LogShareFiles.directory(root, id);
                try (OutputStream out = context.getContentResolver().openOutputStream(destination, "w")) {
                    ArchiveDocumentWriter.copy(archive(id), out,
                            new CompatibilityBundleExporter.ExportControl(null, () -> new File(dir, "cancel").exists()));
                }
                finish(id, "saved", null);
            } catch (Exception error) {
                try { android.provider.DocumentsContract.deleteDocument(context.getContentResolver(), destination); }
                catch (Exception secondary) { error.addSuppressed(secondary); }
                finish(id, error instanceof CompatibilityBundleExporter.CancellationException ? "cancelled" : "failed", error);
            }
        });
    }

    private void release(String id) throws Exception {
        Job job = find(id);
        if (job != null && job.remote) {
            try { LogShareShellClient.call(LogShareShellClient.available(), LogShareShellHost.RELEASE, id, null, null, null); }
            catch (Exception error) { LogShareFiles.diagnostic(LogShareFiles.directory(root, id), "remote_release", error); }
        }
        LogShareFiles.cleanupArchive(LogShareFiles.directory(root, id));
    }

    private synchronized void setUi(String id, String key, boolean value) {
        try {
            writeUi(id, key, value);
        } catch (Exception error) { android.util.Log.e("BydExtendLogShare", "Cannot persist UI operation " + id, error); }
        changed();
    }
    private synchronized void writeUi(String id, String key, boolean value) throws Exception {
        JSONObject data = new JSONObject(ui.getOrDefault(id, new JSONObject()).toString()); data.put(key, value);
        File dir = LogShareFiles.directory(root, id);
        File part = new File(dir, "ui.json.part");
        try (FileOutputStream out = new FileOutputStream(part)) { out.write(data.toString().getBytes(StandardCharsets.UTF_8)); out.getFD().sync(); }
        if (!part.renameTo(new File(dir, "ui.json"))) throw new IOException("Cannot commit log UI state");
        ui.put(id, data);
    }
    private synchronized void writeState(File dir, JSONObject state) throws IOException {
        LogShareFiles.write(dir, state);
        try { states.put(dir.getName(), new JSONObject(state.toString())); }
        catch (org.json.JSONException error) { throw new IOException("Cannot publish operation state", error); }
        changed();
    }

    private synchronized void writeRemoteState(File dir, JSONObject state) throws IOException {
        if (!ui.getOrDefault(dir.getName(), new JSONObject()).optBoolean("local_action")) writeState(dir, state);
    }

    private void failure(String id, String operation, Throwable error) {
        try {
            File dir = LogShareFiles.directory(root, id);
            synchronized (this) {
                if (operation.equals("status") && ui.getOrDefault(id, new JSONObject()).optBoolean("local_action")) {
                    LogShareFiles.diagnostic(dir, "late_status", error); return;
                }
            }
            JSONObject state = new File(dir, "state.json").isFile() ? LogShareFiles.read(dir) : new JSONObject().put("id", id);
            Job job = find(id);
            state.put("phase", error instanceof CompatibilityBundleExporter.CancellationException ? "cancelled"
                    : job != null && (job.remote || job.phase.equals("uploading")) ? "unconfirmed" : "failed");
            state.put("detail", LogShareFiles.diagnostic(dir, operation, error));
            writeState(dir, state);
        } catch (Exception secondary) { error.addSuppressed(secondary); android.util.Log.e("BydExtendLogShare", operation, error); }
        changed();
    }

    private void load() {
        File[] dirs = root.listFiles(File::isDirectory);
        if (dirs != null) for (File dir : dirs) {
            try {
                JSONObject state = LogShareFiles.read(dir);
                File flags = new File(dir, "ui.json");
                JSONObject extra = flags.isFile() ? new JSONObject(new String(Files.readAllBytes(flags.toPath()), StandardCharsets.UTF_8)) : new JSONObject();
                if ((!appBootCount().isEmpty() && !appBootCount().equals(state.optString("app_boot")))
                        || SystemClock.elapsedRealtime() < state.optLong("started_at")) state.remove("upload_at");
                if (LogShareFiles.active(state.optString("phase"))
                        && (!extra.optBoolean("remote") || extra.optBoolean("local_action"))) {
                    state.put("phase", state.optString("phase").equals("uploading") ? "unconfirmed" : "failed")
                            .put("detail", "Application worker interrupted; no automatic resend");
                    LogShareFiles.write(dir, state);
                }
                synchronized (this) { states.put(dir.getName(), state); ui.put(dir.getName(), extra); }
                if (!LogShareFiles.needsArchive(state.optString("phase"))
                        && System.currentTimeMillis() - state.optLong("shared_at", state.optLong("created_at")) >= LogShareFiles.RETENTION_MS) {
                    File archive = archive(dir.getName()); if (archive.isFile()) archive.delete();
                    LogShareFiles.cleanupArchive(dir);
                }
            } catch (Exception error) { LogShareFiles.diagnostic(dir, "restore", error); }
        }
        synchronized (this) { loading = false; }
        refresh(true);
        observeActiveJobs();
    }

    private void observeActiveJobs() {
        if (active()) {
            try { ContextCompat.startForegroundService(context, new Intent(context, LogShareService.class)); }
            catch (Exception error) { android.util.Log.e("BydExtendLogShare", "Could not resume status service", error); }
        }
    }

    private void cleanupReleased() {
        for (Job job : snapshots()) {
            if (LogShareFiles.needsArchive(job.phase)) continue;
            try {
                File dir = LogShareFiles.directory(root, job.id);
                JSONObject state = LogShareFiles.read(dir);
                if (System.currentTimeMillis() - state.optLong("shared_at", job.createdAt) < LogShareFiles.RETENTION_MS) continue;
                File archive = archive(job.id);
                if (archive.isFile() && !archive.delete()) throw new IOException("Cannot remove expired shared archive");
                LogShareFiles.cleanupArchive(dir);
            } catch (Exception error) { android.util.Log.e("BydExtendLogShare", "Archive retirement " + job.id, error); }
        }
    }

    public static final class Job {
        public final String id, title, comment, phase, detail, eventId, destination, path;
        public final long createdAt, startedAt, endedAt, bytes, total;
        public final boolean active, preparing, hidden, handled, remote, systemLogs, shareRequested;
        Job(JSONObject state, JSONObject ui) {
            if (ui == null) ui = new JSONObject();
            id = state.optString("id"); title = state.optString("title"); comment = state.optString("comment");
            phase = state.optString("phase"); detail = state.optString("detail"); eventId = state.optString("event_id");
            destination = state.optString("destination"); path = state.optString("path");
            createdAt = state.optLong("created_at"); startedAt = state.optLong("started_at"); endedAt = state.optLong("ended_at");
            bytes = state.optLong("bytes"); total = state.optLong("total", -1);
            active = LogShareFiles.active(phase); preparing = LogShareFiles.preparing(phase);
            hidden = ui.optBoolean("hidden"); handled = ui.optBoolean("handled"); remote = ui.optBoolean("remote");
            systemLogs = state.optBoolean("system_logs"); shareRequested = ui.optBoolean("share_requested");
        }
    }

    private String appBootCount() {
        try {
            int count = android.provider.Settings.Global.getInt(context.getContentResolver(), "boot_count", -1);
            return count < 0 ? "" : Integer.toString(count);
        } catch (RuntimeException error) {
            android.util.Log.e("BydExtendLogShare", "Boot counter unavailable; using elapsed clock reset", error);
            return "";
        }
    }
}
