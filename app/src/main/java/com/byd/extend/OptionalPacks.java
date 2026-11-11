package com.byd.extend;

import android.app.DownloadManager;
import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;
import com.byd.extend.remote.CameraViewingDiagnostics;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** App-owned downloads. DownloadManager owns network waits and survives app process death. */
public final class OptionalPacks {
    public static final class State {
        public final String phase, error;
        public final float progress;
        State(String phase, float progress, String error) {
            this.phase = phase; this.progress = progress; this.error = error;
        }
        public boolean ready() { return "ready".equals(phase); }
        public boolean busy() { return "downloading".equals(phase) || "installing".equals(phase); }
    }
    private static OptionalPacks instance;
    public static synchronized OptionalPacks get(Context context) {
        if (instance == null) instance = new OptionalPacks(context.getApplicationContext());
        return instance;
    }
    private final Context context;
    private final SharedPreferences preferences, settings, templates;
    private final DownloadManager downloads;
    private final File root;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> new Thread(r, "optional-packs"));
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Set<String> cancelled = ConcurrentHashMap.newKeySet();
    private final Set<Runnable> listeners = new CopyOnWriteArraySet<>();
    private final Set<Consumer<String>> installedListeners = new CopyOnWriteArraySet<>();
    private final AtomicBoolean refreshQueued = new AtomicBoolean();
    private final Map<String, State> states = new ConcurrentHashMap<>();
    private boolean scanned;
    private final SharedPreferences.OnSharedPreferenceChangeListener changed = (prefs, key) -> {
        if (key == null || AvasAudioLibrary.PREF_CONFIG.equals(key) || "templates".equals(key)) reconcile();
    };

    private OptionalPacks(Context context) {
        this.context = context;
        root = new File(context.getFilesDir(), "optional-packs");
        preferences = context.getSharedPreferences("optional_pack_downloads", Context.MODE_PRIVATE);
        settings = context.getSharedPreferences("settings", Context.MODE_PRIVATE);
        templates = context.getSharedPreferences("motion_screen", Context.MODE_PRIVATE);
        downloads = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        for (OptionalPackCatalog.Pack pack : OptionalPackCatalog.PACKS) {
            if (preferences.getBoolean(pack.key() + ".cancelled", false)) cancelled.add(pack.key());
            states.put(pack.key(), new State(pack.bundled() ? "ready" : "missing", 0, ""));
        }
        settings.registerOnSharedPreferenceChangeListener(changed);
        templates.registerOnSharedPreferenceChangeListener(changed);
        reconcile();
    }
    public File root() { return root; }
    public State state(String key) { OptionalPackCatalog.get(key); return states.get(key); }
    public void addListener(Runnable listener) { listeners.add(listener); }
    public void removeListener(Runnable listener) { listeners.remove(listener); }
    void addInstalledListener(Consumer<String> listener) { installedListeners.add(listener); }
    void removeInstalledListener(Consumer<String> listener) { installedListeners.remove(listener); }
    public void download(String key) {
        OptionalPackCatalog.Pack pack = OptionalPackCatalog.get(key);
        worker.execute(() -> start(pack, true));
    }
    public void cancel(String key) {
        OptionalPackCatalog.get(key);
        cancelled.add(key); // Interrupt verification/unpacking even while the worker is busy.
        preferences.edit().putBoolean(key + ".cancelled", true).apply();
        publish(key, new State("cancelled", 0, ""));
        worker.execute(() -> {
            try { clearJob(key); }
            catch (Exception failure) { fail(key, "cancel", failure); }
        });
    }
    public void refresh() {
        if (!refreshQueued.compareAndSet(false, true)) return;
        worker.execute(() -> {
            try { refreshJobs(); }
            finally { refreshQueued.set(false); }
        });
    }
    public void reconcile() {
        worker.execute(() -> {
            refreshJobs();
            try {
                for (String key : OptionalPackSelection.required(
                        settings.getString(AvasAudioLibrary.PREF_CONFIG, null),
                        templates.getString("templates", null))) start(OptionalPackCatalog.get(key), false);
            } catch (Exception failure) { logFailure("migration", "selection", failure); }
        });
    }
    void completed(Runnable done) {
        worker.execute(() -> { try { refreshJobs(); } finally { done.run(); } });
    }
    private void start(OptionalPackCatalog.Pack pack, boolean manual) {
        String key = pack.key();
        try {
            if (pack.bundled() || OptionalPackFiles.ready(root, pack)) {
                publish(key, new State("ready", 1, "")); return;
            }
            if (preferences.getLong(key + ".job", -1) >= 0) return;
            if (!manual && (cancelled.contains(key) || preferences.getBoolean(key + ".attempted", false))) return;
            if (downloads == null) throw new IOException("DownloadManager unavailable");
            cancelled.remove(key);
            preferences.edit().putBoolean(key + ".cancelled", false).putBoolean(key + ".attempted", true).apply();
            // Unique destination also avoids stale partial files after interrupted system cleanup.
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(pack.url()))
                    .setTitle("BYD Extend — " + pack.id)
                    .setMimeType("application/zip")
                    .setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI | DownloadManager.Request.NETWORK_MOBILE)
                    .setAllowedOverMetered(true).setAllowedOverRoaming(true)
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                    .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS,
                            key + "-" + UUID.randomUUID() + ".zip");
            long id = downloads.enqueue(request);
            if (!preferences.edit().putLong(key + ".job", id).commit()) {
                IOException failure = new IOException("Cannot persist pack download ID");
                try { downloads.remove(id); }
                catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
            publish(key, new State("downloading", 0, ""));
            log("download_started", key, "job=" + id);
        } catch (Exception failure) { fail(key, "enqueue", failure); }
    }
    private void refreshJobs() {
        boolean initial = !scanned;
        scanned = true;
        for (OptionalPackCatalog.Pack pack : OptionalPackCatalog.PACKS) {
            String key = pack.key();
            if (pack.bundled()) continue;
            try {
                if (initial) OptionalPackFiles.cleanup(root, pack);
                if (states.get(key).ready() || initial && OptionalPackFiles.ready(root, pack)) {
                    publish(key, new State("ready", 1, ""));
                    clearCompletedJob(key);
                    continue;
                }
                if (cancelled.contains(key)) { clearJob(key); continue; }
                long id = preferences.getLong(key + ".job", -1);
                if (id < 0) continue;
                if (downloads == null) throw new IOException("DownloadManager unavailable; job=" + id);
                try (Cursor cursor = downloads.query(new DownloadManager.Query().setFilterById(id))) {
                    if (cursor == null || !cursor.moveToFirst()) throw new IOException("Download entry missing; job=" + id);
                    int status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                    long bytes = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                    int reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON));
                    if (status == DownloadManager.STATUS_FAILED)
                        throw new IOException("DownloadManager failed; job=" + id + "; reason=" + reason);
                    if (status != DownloadManager.STATUS_SUCCESSFUL) {
                        publish(key, new State("downloading", Math.max(0, Math.min(1, bytes / (float) pack.bytes)), ""));
                        continue;
                    }
                }
                publish(key, new State("installing", 1, ""));
                try (ParcelFileDescriptor file = downloads.openDownloadedFile(id);
                     InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(file)) {
                    OptionalPackFiles.install(root, pack, input, () -> cancelled.contains(key));
                }
                publish(key, new State("ready", 1, ""));
                log("installed", key, "sha256=" + pack.sha256);
                clearCompletedJob(key);
            } catch (Exception failure) {
                try { clearJob(key); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
                if (failure instanceof InterruptedIOException && cancelled.contains(key))
                    publish(key, new State("cancelled", 0, ""));
                else fail(key, "complete", failure);
            }
        }
    }
    private void clearJob(String key) {
        long id = preferences.getLong(key + ".job", -1);
        if (id >= 0) {
            if (downloads == null) throw new IllegalStateException("DownloadManager unavailable for cleanup; job=" + id);
            downloads.remove(id);
            preferences.edit().remove(key + ".job").apply();
        }
    }
    private void clearCompletedJob(String key) {
        try { clearJob(key); }
        catch (Exception cleanup) { logFailure("completed_cleanup", key, cleanup); }
    }
    private void fail(String key, String operation, Exception failure) {
        logFailure(operation, key, failure);
        publish(key, new State(cancelled.contains(key) ? "cancelled" : "failed", 0, failure.toString()));
    }
    private void publish(String key, State next) {
        // A late system progress read must not overwrite the user's concurrent Stop.
        if (next.busy() && cancelled.contains(key)) return;
        State previous = states.put(key, next);
        if (previous != null && previous.phase.equals(next.phase) && previous.progress == next.progress
                && previous.error.equals(next.error)) return;
        main.post(() -> {
            for (Runnable listener : listeners) listener.run();
            if (next.ready() && (previous == null || !previous.ready()))
                for (Consumer<String> listener : installedListeners) listener.accept(key);
        });
    }
    private void log(String operation, String key, String detail) {
        CameraHelperService.logViewingEvent(context, "optional_pack_" + operation, key, detail);
    }
    private void logFailure(String operation, String key, Throwable failure) {
        log(operation + "_error", key, CameraViewingDiagnostics.describe(failure));
    }
}
