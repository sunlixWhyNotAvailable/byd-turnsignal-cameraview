package com.byd.extend;

import android.os.FileObserver;
import android.os.SystemClock;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;

/** Independent from APN2: one analysis per firmware/request, never one per recovery attempt. */
final class OemWakeDiscovery implements AutoCloseable {
    private final Object lock = new Object();
    private final OwnedShellProcess child = new OwnedShellProcess();
    private final Thread worker = new Thread(this::loop, "byd-wake-discovery");
    private volatile boolean stopped;
    private volatile WakeDiscoveryState.Selection selected = WakeDiscoveryState.DEFAULT;
    private volatile String observedRequest = "";
    private String lastPublished = "", lastError = "";
    private JSONObject failedInMemory, currentRecord;
    private final FileObserver observer = new FileObserver(MotionDetectorProtocol.STATE_ROOT, FileObserver.MOVED_TO | FileObserver.DELETE) {
        @Override public void onEvent(int event, String path) {
            if (!"wake_request".equals(path)) return;
            synchronized (lock) {
                try {
                    if (!request().equals(observedRequest)) child.cancel();
                } catch (Exception e) { child.cancel(); logError(e, "request_change"); }
                lock.notifyAll();
            }
        }
    };

    void start() { observer.startWatching(); worker.setDaemon(true); worker.start(); }
    WakeDiscoveryState.Selection selection() { return selected; }

    private void loop() {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
        while (!stopped) {
            try { reconcile(); }
            catch (Exception error) {
                if (stopped) return;
                selected = WakeDiscoveryState.DEFAULT;
                logError(error, "discovery_reconcile");
                currentRecord = null;
                try { publish("failed"); } catch (Exception e) { logError(e, "status_publish"); }
            }
            synchronized (lock) {
                if (stopped) return;
                try {
                    // Metadata only; no DEX parsing/hashing on this timer.
                    if (request().equals(observedRequest)) lock.wait(30_000L);
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
                catch (Exception e) { logError(e, "request_read"); try { lock.wait(30_000L); } catch (InterruptedException stop) { return; } }
            }
        }
    }

    private void reconcile() throws Exception {
        long token;
        String request;
        synchronized (lock) {
            if (stopped) return;
            request = request(); observedRequest = request; token = child.token();
        }
        if (request.isEmpty()) {
            selected = WakeDiscoveryState.DEFAULT;
            publish("pending");
            return;
        }
        String[] parts = request.split(":");
        long generation = Long.parseLong(parts[0]);
        if (!parts[1].equals("1") || !parts[2].equals("0")) return;
        List<File> sources = WakeDiscoveryState.sources();
        String identity = WakeDiscoveryState.identity(sources);
        JSONObject saved = null;
        try { saved = WakeDiscoveryState.read(); }
        catch (Exception e) { logError(e, "cache_read"); }
        if (!WakeDiscoveryState.reusable(saved, identity, generation)
                && WakeDiscoveryState.reusable(failedInMemory, identity, generation)) saved = failedInMemory;
        if (WakeDiscoveryState.reusable(saved, identity, generation)) {
            selected = saved.getString("status").equals("found")
                    ? new WakeDiscoveryState.Selection(saved.getInt("reason"), "detected") : WakeDiscoveryState.DEFAULT;
            currentRecord = saved;
            publish(saved.getString("status"));
            return;
        }
        selected = WakeDiscoveryState.DEFAULT;
        JSONObject record = WakeDiscoveryState.record(identity, generation, "pending", 13);
        currentRecord = record;
        WakeDiscoveryState.write(record);
        publish("pending");
        CellularRecovery.log("wake_discovery_start request=" + generation + " identity=" + identity + " uid=" + android.os.Process.myUid());
        JSONObject completed;
        JSONObject sourceHashes = null;
        try {
            OwnedShellProcess.Result result = child.run(token, OemWakeDiscoveryMain.class.getName(),
                    "bydextend_wake_discovery", SystemClock.elapsedRealtime() + 30_000L, identity);
            for (String line : result.output.split("\\r?\\n")) if (line.startsWith("WAKE_SOURCES "))
                sourceHashes = new JSONObject(line.substring(13));
            if (result.exitCode != 0) throw new IOException("Analyzer exit=" + result.exitCode + " output=" + result.output);
            String payload = null;
            for (String line : result.output.split("\\r?\\n")) if (line.startsWith("WAKE_RESULT ")) {
                if (payload != null) throw new IOException("Multiple analyzer results");
                payload = line.substring(12);
            }
            if (payload == null) throw new IOException("Analyzer result absent: " + result.output);
            JSONObject found = new JSONObject(payload);
            if (found.getInt("reason") < 0 || found.getString("evidence").isEmpty()) throw new IOException("Invalid analyzer result");
            completed = WakeDiscoveryState.record(identity, generation, "found", found.getInt("reason"))
                    .put("evidence", found.getString("evidence")).put("hashes", found.getJSONObject("hashes"));
        } catch (Exception e) {
            logError(e, "analyzer");
            completed = WakeDiscoveryState.record(identity, generation, "failed", 13)
                    .put("error", CellularRecovery.diagnosticDetail(e, "stage=wake_analyzer"));
            if (sourceHashes != null) completed.put("hashes", sourceHashes);
        }
        synchronized (lock) {
            if (stopped || !request.equals(request()) || token != child.token()) return;
            if (!identity.equals(WakeDiscoveryState.identity(sources))) { observedRequest = ""; return; }
            completed.put("started_wall_ms", record.getLong("wall_ms"));
            if (completed.getString("status").equals("failed")) failedInMemory = completed;
            currentRecord = completed;
            try { WakeDiscoveryState.write(completed); }
            catch (Exception error) {
                failedInMemory = WakeDiscoveryState.record(identity, generation, "failed", 13)
                        .put("error", CellularRecovery.diagnosticDetail(error, "stage=discovery_persist"))
                        .put("analysis", completed);
                throw error;
            }
            selected = completed.getString("status").equals("found")
                    ? new WakeDiscoveryState.Selection(completed.getInt("reason"), "detected") : WakeDiscoveryState.DEFAULT;
        }
        publish(completed.getString("status"));
        CellularRecovery.log("wake_discovery_finished request=" + generation + " status=" + completed.getString("status")
                + " reason=" + selected.reason + " source=" + selected.source);
    }

    static String request() throws Exception {
        Path path = Paths.get(WakeDiscoveryState.REQUEST_PATH);
        // First-run initialization may not have published a request yet.
        if (Files.notExists(path, LinkOption.NOFOLLOW_LINKS)) return "";
        CellularRecovery.validateFile(path, 128);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > 128)
            throw new IOException("Wake discovery request missing/invalid");
        String text = new String(Files.readAllBytes(path), StandardCharsets.UTF_8).trim();
        if (!text.matches("[0-9]{1,18}:[01]:[01]")) throw new IOException("Invalid wake request: " + text);
        return text;
    }

    private void publish(String status) {
        try { publishStatus(status); }
        catch (Exception error) { if (!stopped) logError(error, "status_publish"); }
    }

    private void publishStatus(String status) throws Exception {
        if (stopped) return;
        JSONObject evidence = currentRecord != null && status.equals(currentRecord.optString("status"))
                ? currentRecord : new JSONObject();
        JSONObject message = new JSONObject(evidence.toString()).put("status", status)
                .put("reason", selected.reason).put("source", selected.source);
        if (status.equals("failed") && !message.has("error")) message.put("error", lastError);
        String value = message.toString();
        if (value.equals(lastPublished)) return;
        CellularRecovery.discoveryStatus(value);
        lastPublished = value;
    }

    private void logError(Exception error, String stage) {
        String detail = CellularRecovery.diagnosticDetail(error, "stage=" + stage);
        if (!detail.equals(lastError)) { CellularRecovery.log("wake_discovery_error " + detail); lastError = detail; }
    }

    @Override public void close() {
        observer.stopWatching();
        synchronized (lock) { stopped = true; child.cancel(); lock.notifyAll(); }
        worker.interrupt();
    }
}
