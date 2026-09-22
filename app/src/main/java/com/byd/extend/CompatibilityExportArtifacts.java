package com.byd.extend;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Absolute, non-renewing lifecycle for compatibility exports in app cache only. */
final class CompatibilityExportArtifacts {
    static final long RETENTION_MS = 15L * 60L * 1000L;
    static final long RETRY_MS = 60_000L;
    private static final int JOB_ID = 0x425943;
    private static final String TAG = "BYDExtendCompat";
    private static final Pattern OWNED = Pattern.compile(
            "^((?:byd-extend|byd-turnsignal)-compatibility-[0-9]{8}-[0-9]{6}"
                    + "(?:-[0-9]{3})?(?:-(?:[2-9]|[1-9][0-9]+))?\\.zip)"
                    + "(?:\\.part|\\.expiry\\.json(?:\\.part)?)?$");
    private static final Pattern OWNED_STAGING = Pattern.compile(
            "^\\.compatibility-tmp-[0-9a-f]+-[0-9a-f]+$");
    private static final ScheduledExecutorService WORKER =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "compatibility-export-cleanup");
                thread.setDaemon(true);
                return thread;
            });
    private static final Set<String> ACTIVE = ConcurrentHashMap.newKeySet();
    private static ScheduledFuture<?> scheduled;

    private CompatibilityExportArtifacts() { }

    static void protect(File file) { ACTIVE.add(file.getAbsolutePath()); }
    static void unprotect(File file) { ACTIVE.remove(file.getAbsolutePath()); }

    static void completed(File archive, long completedAtMs) throws IOException {
        persist(archive, completedAtMs, Math.addExact(completedAtMs, RETENTION_MS));
    }

    static void discardMetadata(File archive) {
        metadata(archive).delete();
        new File(metadata(archive).getPath() + ".part").delete();
    }

    private static void persist(File archive, long createdAtMs, long expiresAtMs)
            throws IOException {
        File metadata = metadata(archive);
        File temporary = new File(metadata.getPath() + ".part");
        if (Files.isSymbolicLink(metadata.toPath()) || Files.isSymbolicLink(temporary.toPath())) {
            throw new IOException("Compatibility expiry metadata is a symbolic link");
        }
        byte[] value;
        try {
            value = new JSONObject().put("createdAtMs", createdAtMs)
                    .put("expiresAtMs", expiresAtMs).toString().getBytes(StandardCharsets.UTF_8);
        } catch (org.json.JSONException error) {
            throw new IOException("Unable to encode compatibility expiry metadata", error);
        }
        try (FileOutputStream output = new FileOutputStream(temporary)) {
            output.write(value);
            output.getFD().sync();
        }
        try {
            Files.move(temporary.toPath(), metadata.toPath(), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException error) {
            temporary.delete();
            throw error;
        }
    }

    static void checkBeforeExport(Context context) throws IOException {
        try { WORKER.submit(() -> run(context.getApplicationContext())).get(); }
        catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("Compatibility cleanup interrupted", error);
        } catch (ExecutionException error) {
            throw new IOException("Compatibility cleanup failed", error.getCause());
        }
    }

    static void checkAsync(Context context) {
        Context app = context.getApplicationContext();
        WORKER.execute(() -> run(app));
    }

    static void checkAsync(Context context, Runnable finished) {
        Context app = context.getApplicationContext();
        WORKER.execute(() -> { try { run(app); } finally { finished.run(); } });
    }

    static void checkDirectoryBeforeExport(File directory, long now) {
        sweep(directory, now, ACTIVE, ignored -> { });
    }

    private static synchronized void run(Context context) {
        long now = System.currentTimeMillis();
        File directory = new File(context.getCacheDir(), "shared_logs");
        long next = sweep(directory, now, ACTIVE,
                event -> Log.i(TAG, "compatibility_export " + event));
        if (scheduled != null) scheduled.cancel(false);
        JobScheduler jobs = context.getSystemService(JobScheduler.class);
        if (next == Long.MAX_VALUE) {
            if (jobs != null) jobs.cancel(JOB_ID);
            return;
        }
        long delay = Math.max(1L, next - now);
        scheduled = WORKER.schedule(() -> run(context), delay, TimeUnit.MILLISECONDS);
        if (jobs != null) {
            int result = jobs.schedule(new JobInfo.Builder(JOB_ID,
                    new ComponentName(context, CompatibilityExportCleanupJob.class))
                    .setPersisted(true).setMinimumLatency(delay).setOverrideDeadline(delay).build());
            if (result != JobScheduler.RESULT_SUCCESS) Log.w(TAG, "Cleanup job scheduling failed");
        }
    }

    static long sweep(File directory, long now, Set<String> active, Consumer<String> log) {
        File[] children = directory.listFiles();
        if (children == null) return Long.MAX_VALUE;
        Map<String, List<File>> groups = new HashMap<>();
        long next = Long.MAX_VALUE;
        for (File file : children) {
            if (OWNED_STAGING.matcher(file.getName()).matches()) {
                if (!active.contains(file.getAbsolutePath()) && !Files.isSymbolicLink(file.toPath())) {
                    if (deleteTree(file)) log.accept("cleanup staging=" + file.getName());
                    else next = Math.min(next, now + RETRY_MS);
                }
                continue;
            }
            Matcher match = OWNED.matcher(file.getName());
            if (!match.matches() || !file.isFile() || Files.isSymbolicLink(file.toPath())) continue;
            groups.computeIfAbsent(match.group(1), ignored -> new ArrayList<>()).add(file);
        }
        for (Map.Entry<String, List<File>> group : groups.entrySet()) {
            File archive = new File(directory, group.getKey());
            if (active.contains(archive.getAbsolutePath())) continue;
            File metadata = metadata(archive);
            List<File> files = group.getValue();
            boolean complete = files.stream().anyMatch(file -> file.equals(archive));
            long expires = complete ? readExpiry(metadata) : 0L;
            if (complete && expires == 0L) {
                long modified = archive.lastModified();
                long created = modified > 0L && modified <= now ? modified : now;
                expires = created + RETENTION_MS;
                try { persist(archive, created, expires); }
                catch (IOException error) {
                    log.accept("cleanup_failed metadata=" + archive.getName());
                    next = Math.min(next, now + RETRY_MS);
                    if (expires > now) continue;
                }
            }
            if (complete && now < expires) {
                next = Math.min(next, expires);
                continue;
            }
            boolean failed = false;
            for (File file : files) {
                if (!file.delete() && file.exists()) failed = true;
            }
            if (!failed && metadata.exists() && !metadata.delete()) failed = true;
            if (failed) {
                log.accept("cleanup_failed archive=" + archive.getName());
                next = Math.min(next, now + RETRY_MS);
            } else {
                log.accept("cleanup archive=" + archive.getName() + " expired=" + complete);
            }
        }
        return next;
    }

    private static long readExpiry(File metadata) {
        try {
            if (!metadata.isFile() || metadata.length() > 1024L
                    || Files.isSymbolicLink(metadata.toPath())) return 0L;
            JSONObject value = new JSONObject(new String(Files.readAllBytes(metadata.toPath()),
                    StandardCharsets.UTF_8));
            long created = value.getLong("createdAtMs");
            long expires = value.getLong("expiresAtMs");
            return created > 0L && expires - created == RETENTION_MS ? expires : 0L;
        } catch (Exception ignored) { return 0L; }
    }

    private static File metadata(File archive) {
        return new File(archive.getPath() + ".expiry.json");
    }

    private static boolean deleteTree(File root) {
        try {
            java.nio.file.Path normalized = root.toPath().toAbsolutePath().normalize();
            try (java.util.stream.Stream<java.nio.file.Path> tree = Files.walk(normalized)) {
                for (java.nio.file.Path path : (Iterable<java.nio.file.Path>) tree
                        .sorted(Comparator.reverseOrder())::iterator) {
                    if (!path.toAbsolutePath().normalize().startsWith(normalized)) return false;
                    Files.deleteIfExists(path);
                }
            }
            return true;
        } catch (IOException error) { return false; }
    }
}
