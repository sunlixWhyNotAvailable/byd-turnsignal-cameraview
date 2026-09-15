package com.byd.extend;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.function.BiConsumer;
import java.util.function.Function;

/** Diagnostic capture owned by the existing shell recovery process, not the audio worker. */
final class ContinuousLogcatRecorder implements AutoCloseable {
    static final String PREF_ENABLED = "diagnostic_logcat_recording_enabled";
    static final String DIRECTORY = "/data/local/tmp/bydextend_diagnostic_logcat";
    static final String LOG_PATH = DIRECTORY + "/logcat.txt";
    static final String STATUS_PATH = DIRECTORY + "/status.txt";
    static final String SIZE_COMMAND = "stat -c %s '" + LOG_PATH + "' 2>/dev/null";

    static String snapshotCommand(long bytes) {
        if (bytes < 0) throw new IllegalArgumentException("Negative snapshot size");
        return "head -c " + bytes + " '" + LOG_PATH + "' 2>/dev/null";
    }
    static final String SNAPSHOT_COMMAND = "log_file='" + LOG_PATH
            + "'; log_size=$(stat -c %s \"$log_file\" 2>/dev/null)"
            + " && head -c \"$log_size\" \"$log_file\" 2>/dev/null";
    static final long MIN_FREE_BYTES = 512L * 1024L * 1024L;
    private static final int BUFFER_BYTES = 64 * 1024;
    private final File directory;
    private final String bootId;
    private final String identity;
    private final BiConsumer<String, Object[]> events;
    private final Function<List<String>, java.lang.Process> launch;
    private volatile boolean running;
    private volatile java.lang.Process process;
    private Thread reader;
    private String lastStatus = "";

    ContinuousLogcatRecorder(String identity, BiConsumer<String, Object[]> events) {
        this(new File(DIRECTORY), bootId(), identity, events);
    }

    ContinuousLogcatRecorder(File directory, String bootId, String identity,
            BiConsumer<String, Object[]> events) {
        this(directory, bootId, identity, events, command -> {
            try { return new ProcessBuilder(command).redirectErrorStream(true).start(); }
            catch (IOException failure) { throw new java.io.UncheckedIOException(failure); }
        });
    }

    ContinuousLogcatRecorder(File directory, String bootId, String identity,
            BiConsumer<String, Object[]> events, Function<List<String>, java.lang.Process> launch) {
        this.directory = directory;
        this.bootId = bootId;
        this.identity = identity;
        this.events = events;
        this.launch = launch;
    }

    synchronized void start() {
        if (running) return;
        if (reader != null && reader.isAlive()) throw new IllegalStateException("Recorder still stopping");
        running = true;
        reader = new Thread(this::record, "diagnostic-logcat");
        reader.setDaemon(true);
        reader.start();
    }

    private void record() {
        while (running) {
            try {
                ensureDirectory();
                requireSpace(directory.getUsableSpace());
                // Replay retained buffers after recovery, then follow. Nothing is cleared in logd.
                // Session markers distinguish replayed records from a second real vehicle event.
                try (FileOutputStream output = new FileOutputStream(new File(directory, "logcat.txt"), true)) {
                    output.write(("\n# recorder_session wall_ms=" + System.currentTimeMillis()
                            + " monotonic_ns=" + System.nanoTime() + " boot_id=" + bootId
                            + " " + identity + "\n").getBytes(StandardCharsets.UTF_8));
                    output.getFD().sync();
                    process = launch.apply(command(clearSince()));
                    status("recording; replay_retained_buffers=true");
                    try (InputStream input = process.getInputStream()) {
                        byte[] buffer = new byte[BUFFER_BYTES];
                        long checked = 0L;
                        long synced = System.nanoTime();
                        int count;
                        while (running && (count = input.read(buffer)) != -1) {
                            long now = System.nanoTime();
                            if (now - checked >= 1_000_000_000L) {
                                requireSpace(directory.getUsableSpace());
                                checked = now;
                            }
                            output.write(buffer, 0, count);
                            if (now - synced >= 5_000_000_000L) {
                                output.getFD().sync();
                                synced = now;
                            }
                        }
                        output.getFD().sync();
                    }
                    if (running) status("logcat_exited; restarting");
                }
            } catch (Exception failure) {
                if (running) status("paused: " + failure);
            } finally {
                java.lang.Process child = process;
                if (child != null) child.destroy();
                process = null;
            }
            if (running) {
                try { Thread.sleep(1000L); }
                catch (InterruptedException stop) { Thread.currentThread().interrupt(); break; }
            }
        }
        status("stopped");
    }

    /** Stop/join before deletion: neither a live file descriptor nor queued bytes survive Clear. */
    synchronized int clear() throws IOException {
        boolean restart = running;
        close();
        try { return clearStored(); }
        finally { if (restart) start(); }
    }

    /** Caller must own the recovery-daemon lock when this instance is not running. */
    int clearStored() throws IOException {
        ensureDirectory();
        String since = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
        // A later recorder restart must not refill a cleared file with the old logd ring.
        Files.write(new File(directory, "clear-since.txt").toPath(),
                (bootId + "\n" + since).getBytes(StandardCharsets.UTF_8));
        int removed = Files.deleteIfExists(new File(directory, "logcat.txt").toPath()) ? 1 : 0;
        status("cleared; since=" + since);
        return removed;
    }

    String clearSince() throws IOException {
        File cutoff = new File(directory, "clear-since.txt");
        if (!cutoff.isFile()) return "";
        String[] value = new String(Files.readAllBytes(cutoff.toPath()), StandardCharsets.UTF_8).split("\n", 2);
        return value.length == 2 && bootId.equals(value[0])
                && value[1].matches("[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{3}")
                ? value[1] : "";
    }

    static List<String> command(String since) {
        List<String> command = new ArrayList<>();
        java.util.Collections.addAll(command, "/system/bin/logcat", "-b", "all", "-v", "threadtime");
        if (!since.isEmpty()) java.util.Collections.addAll(command, "-T", since);
        return command;
    }

    static void requireSpace(long usable) throws IOException {
        if (usable < MIN_FREE_BYTES) throw new IOException("low_space; free_bytes=" + usable
                + "; reserve_bytes=" + MIN_FREE_BYTES + "; existing_logs_retained");
    }

    private void ensureDirectory() throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create log directory");
        // Full Logcat contains other apps' data. Only shell owns these on-device files.
        directory.setReadable(false, false);
        directory.setWritable(false, false);
        directory.setExecutable(false, false);
        directory.setReadable(true, true);
        directory.setWritable(true, true);
        directory.setExecutable(true, true);
    }

    private void status(String state) {
        if (state.equals(lastStatus)) return;
        lastStatus = state;
        String value = "state=" + state + "\nwall_ms=" + System.currentTimeMillis()
                + "\nboot_id=" + bootId + "\n" + identity + "\n";
        try { Files.write(new File(directory, "status.txt").toPath(), value.getBytes(StandardCharsets.UTF_8)); }
        catch (IOException ignored) { /* Recovery journal remains an independent status channel. */ }
        if (events != null) events.accept("diagnostic_logcat_state", new Object[]{"state", state,
                "path", new File(directory, "logcat.txt").getPath()});
    }

    @Override public synchronized void close() throws IOException {
        running = false;
        java.lang.Process child = process;
        if (child != null) child.destroy();
        if (reader == null) return;
        reader.interrupt();
        try { reader.join(3000L); }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while stopping Logcat", interrupted);
        }
        if (reader.isAlive()) throw new IOException("Logcat reader still active; retained files not cleared");
    }

    private static String bootId() {
        try { return new String(Files.readAllBytes(new File("/proc/sys/kernel/random/boot_id").toPath()),
                StandardCharsets.UTF_8).trim(); }
        catch (IOException unavailable) { return "unknown"; }
    }
}
