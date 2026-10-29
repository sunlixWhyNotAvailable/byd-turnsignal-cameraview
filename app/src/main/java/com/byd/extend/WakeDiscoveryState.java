package com.byd.extend;

import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Shell-owned atomic evidence; Evidence travels through authenticated Motion IPC into ordinary APP diagnostics. */
final class WakeDiscoveryState {
    static final int DEFAULT_REASON = 13;
    static final String PATH = MotionDetectorProtocol.STATE_ROOT + "/wake_discovery.json";
    static final String REQUEST_PATH = MotionDetectorProtocol.STATE_ROOT + "/wake_request";

    static final class Selection {
        final int reason; final String source;
        Selection(int reason, String source) { this.reason = reason; this.source = source; }
    }
    static final Selection DEFAULT = new Selection(DEFAULT_REASON, "default");

    static List<File> sources() throws IOException {
        LinkedHashSet<String> paths = new LinkedHashSet<>();
        paths.add("/system/framework/services.jar");
        paths.add("/system/framework/framework.jar");
        String classpath = System.getenv("SYSTEMSERVERCLASSPATH");
        if (classpath != null) paths.addAll(Arrays.asList(classpath.split(":")));
        List<File> out = new ArrayList<>();
        for (String path : paths) {
            File file = new File(path).getCanonicalFile();
            if (!(file.getPath().startsWith("/system/") || file.getPath().startsWith("/system_ext/")
                    || file.getPath().startsWith("/apex/") || file.getPath().startsWith("/product/")))
                throw new IOException("Unexpected system JAR path: " + file);
            if (!out.contains(file)) out.add(file);
        }
        if (out.size() > 64) throw new IOException("System JAR count exceeds budget");
        return out;
    }

    static String identity(List<File> sources) throws Exception {
        StringBuilder text = new StringBuilder(android.os.Build.FINGERPRINT)
                .append(':').append(android.os.Build.VERSION.INCREMENTAL).append(':').append(OemWakeAnalyzer.VERSION);
        for (File file : sources) text.append('\n').append(file).append(':').append(file.length()).append(':').append(file.lastModified());
        return hex(MessageDigest.getInstance("SHA-256").digest(text.toString().getBytes(StandardCharsets.UTF_8)));
    }

    static JSONObject hashes(List<File> sources) throws Exception {
        JSONObject out = new JSONObject();
        byte[] buffer = new byte[32768];
        for (File file : sources) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = new FileInputStream(file)) {
                int n; while ((n = in.read(buffer)) >= 0) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Source hashing cancelled");
                    digest.update(buffer, 0, n);
                }
            }
            out.put(file.getPath(), hex(digest.digest()));
        }
        return out;
    }

    static String hex(byte[] bytes) {
        StringBuilder text = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) text.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return text.toString();
    }

    static JSONObject read() throws Exception {
        Path path = Paths.get(PATH);
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null;
        CellularRecovery.validateFile(path, 65_536);
        return read(path);
    }

    static JSONObject read(Path path) throws Exception {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null;
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > 65_536)
            throw new IOException("Invalid wake discovery state file");
        JSONObject value = new JSONObject(new String(Files.readAllBytes(path), StandardCharsets.UTF_8));
        if (value.getInt("analyzer") != OemWakeAnalyzer.VERSION) return null;
        String status = value.getString("status");
        if (!Arrays.asList("pending", "found", "failed").contains(status)
                || value.getInt("reason") < 0 || value.getLong("request") < 0
                || !value.getString("identity").matches("[0-9a-f]{64}")) throw new IOException("Invalid discovery record fields");
        if (status.equals("found") && value.getString("evidence").isEmpty()) throw new IOException("Discovery evidence missing");
        return value;
    }

    static boolean reusable(JSONObject saved, String identity, long generation) throws Exception {
        return saved != null && saved.getString("identity").equals(identity)
                && (saved.getString("status").equals("found")
                    || saved.getString("status").equals("failed") && saved.getLong("request") == generation);
    }

    static JSONObject record(String identity, long request, String status, int reason) throws Exception {
        return new JSONObject().put("analyzer", OemWakeAnalyzer.VERSION).put("identity", identity)
                .put("firmware", android.os.Build.FINGERPRINT).put("request", request)
                .put("status", status).put("reason", reason).put("source", status.equals("found") ? "detected" : "default")
                .put("wall_ms", System.currentTimeMillis()).put("elapsed_ms", android.os.SystemClock.elapsedRealtime());
    }

    static void write(JSONObject value) throws Exception { write(Paths.get(PATH), value, true); }

    static void write(Path path, JSONObject value, boolean shellOwned) throws Exception {
        byte[] bytes = value.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 65_536) throw new IOException("Discovery evidence exceeds file budget");
        Path temp = Paths.get(path + ".tmp");
        if (Files.isSymbolicLink(temp)) throw new IOException("Discovery temp is a symbolic link");
        try (FileOutputStream out = new FileOutputStream(temp.toFile())) {
            if (shellOwned) android.system.Os.chmod(temp.toString(), 0600);
            out.write(bytes); out.getFD().sync();
        }
        Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
}
