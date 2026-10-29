package com.byd.extend;

import android.system.Os;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.LinkedHashMap;
import org.json.JSONObject;

/** Shell lifetime is independent of camera sessions and the APP observer. */
final class CellularRecovery implements AutoCloseable {
    interface Listener { void changed(String snapshot, String diagnostic); }
    private static volatile CellularRecovery owner;
    private final Listener listener;
    private final Path diagnostics = Paths.get(MotionDetectorProtocol.STATE_ROOT, "cellular_diagnostics.json");
    private final LinkedHashMap<String, String> observations = new LinkedHashMap<>();
    private JSONObject snapshot = new JSONObject();
    private volatile boolean enabled;
    private OemWakeDiscovery discovery;
    private CellularApnRecovery recovery;
    private long generation = -1;

    CellularRecovery(Listener listener) {
        this.listener = listener;
        owner = this;
        try {
            if (Files.exists(diagnostics, LinkOption.NOFOLLOW_LINKS)) {
                validateFile(diagnostics, 196_608);
                snapshot = new JSONObject(new String(Files.readAllBytes(diagnostics), StandardCharsets.UTF_8));
            }
        } catch (Exception error) { log(diagnosticDetail(error, "stage=cellular_diagnostic_read")); }
    }

    boolean enabled() { return enabled; }
    synchronized String snapshot() { return snapshot.toString(); }

    void configure(MotionConfig config) {
        boolean next = config != null && config.restoreCellular;
        long nextGeneration = config == null ? Math.max(0, generation) : config.cellularGeneration;
        if (next == enabled && generation == nextGeneration && (!next || recovery != null)) return;
        // Publish cancellation before terminating children; every wake checks this request again.
        Exception publishFailure = null;
        try {
            writeAtomic(Paths.get(WakeDiscoveryState.REQUEST_PATH),
                    (nextGeneration + ":" + (next ? "1" : "0") + ":0").getBytes(StandardCharsets.UTF_8));
        } catch (Exception error) { publishFailure = error; }
        synchronized (this) { enabled = next; generation = nextGeneration; }
        if (recovery != null) { recovery.close(); recovery = null; }
        if (discovery != null) { discovery.close(); discovery = null; }
        if (publishFailure != null) {
            log(diagnosticDetail(publishFailure, "stage=cellular_request_publish"));
            try {
                discoveryStatus(new JSONObject().put("status", "failed").put("reason", 13).put("source", "default")
                        .put("error", diagnosticDetail(publishFailure, "stage=cellular_request_publish")).toString());
            } catch (Exception error) { log(diagnosticDetail(error, "stage=cellular_status_publish")); }
            return;
        }
        if (next) {
            discovery = new OemWakeDiscovery();
            recovery = new CellularApnRecovery(discovery);
            discovery.start();
            recovery.start();
        }
        log("cellular_configuration enabled=" + next + " generation=" + generation + " no_sleep_requested=true");
    }

    void powerChanged(int raw) {
        CellularApnRecovery active = recovery;
        if (active != null) active.powerChanged(raw >= 0 && raw <= 4 ? raw : -1);
    }

    static void discoveryStatus(String json) {
        CellularRecovery active = owner;
        if (active == null) return;
        synchronized (active) {
            try { active.snapshot.put("discovery", new JSONObject(json)); active.persistAndNotify(""); }
            catch (Exception error) { log(diagnosticDetail(error, "stage=discovery_delivery")); }
        }
    }

    static void observation(String line) {
        CellularRecovery active = owner;
        if (active == null) { log(line); return; }
        String[] parts = line.split(" ", 3);
        String kind = parts.length > 1 ? parts[1] : "observation";
        synchronized (active) {
            String previous = active.observations.put(kind, line);
            if (!line.equals(previous) || kind.equals("wake_attempt") || kind.contains("error")) log(line);
            else if (DiagnosticLogPolicy.extended()) log("cellular_detail " + line);
        }
    }

    static void log(String detail) {
        CellularRecovery active = owner;
        if (active == null) { System.out.println(detail); return; }
        synchronized (active) {
            try {
                if (detail.contains("error") || detail.contains("Exception")) {
                    String key = detail.contains("child_") ? "cleanup_error" : "last_error";
                    active.snapshot.put(key, detail);
                }
                active.snapshot.put("last_event", detail);
                active.persistAndNotify(detail);
            } catch (Exception error) {
                active.listener.changed(active.snapshot.toString(), detail + "\nSecondary: "
                        + diagnosticDetail(error, "stage=cellular_diagnostic_persist"));
            }
        }
    }

    private void persistAndNotify(String diagnostic) throws Exception {
        snapshot.put("observations", new JSONObject(observations));
        String json = snapshot.toString();
        writeAtomic(diagnostics, json.getBytes(StandardCharsets.UTF_8));
        listener.changed(json, diagnostic);
    }

    static void validateFile(Path path, long limit) throws Exception {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > limit
                || Os.lstat(path.toString()).st_uid != 2000)
            throw new java.io.IOException("Invalid shell-owned cellular file: " + path);
    }

    static void writeAtomic(Path path, byte[] bytes) throws Exception {
        if (bytes.length > 196_608) throw new java.io.IOException("Cellular diagnostics exceed byte budget");
        Path root = path.getParent();
        Files.createDirectories(root);
        if (Files.isSymbolicLink(root) || Os.lstat(root.toString()).st_uid != 2000)
            throw new java.io.IOException("Invalid cellular state directory: " + root);
        Os.chmod(root.toString(), 0700);
        Path temp = Paths.get(path + ".tmp");
        if (Files.isSymbolicLink(temp)) throw new java.io.IOException("Symbolic link: " + temp);
        try (FileOutputStream out = new FileOutputStream(temp.toFile())) {
            Os.chmod(temp.toString(), 0600);
            out.write(bytes); out.getFD().sync();
        }
        Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    static String diagnosticDetail(Throwable error, String context) {
        StringWriter text = new StringWriter();
        error.printStackTrace(new PrintWriter(text));
        return context + "\n" + text;
    }

    @Override public void close() {
        try { configure(null); }
        catch (Exception error) { log(diagnosticDetail(error, "stage=cellular_stop")); }
    }
}
