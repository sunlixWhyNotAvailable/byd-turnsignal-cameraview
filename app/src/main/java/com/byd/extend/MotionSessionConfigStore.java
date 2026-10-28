package com.byd.extend;

import android.system.Os;
import android.system.OsConstants;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import org.json.JSONException;
import org.json.JSONObject;

/** Atomic shell-private persistence for the autonomous Motion worker configuration. */
final class MotionSessionConfigStore {
    static final class Snapshot {
        final String sessionId;
        final MotionConfig config;

        Snapshot(String sessionId, MotionConfig config) {
            this.sessionId = sessionId;
            this.config = config;
        }
    }

    private final File root;
    private final File file;
    private final int ownerUid;
    private final boolean shellStateRoot;

    MotionSessionConfigStore(int ownerUid) {
        this(new File(MotionDetectorProtocol.STATE_ROOT), ownerUid);
    }

    MotionSessionConfigStore(File root, int ownerUid) {
        if (root == null || ownerUid < 0) throw new IllegalArgumentException("invalid Motion state store");
        this.root = root;
        this.file = new File(root, "config.json");
        this.ownerUid = ownerUid;
        this.shellStateRoot = MotionDetectorProtocol.STATE_ROOT.equals(root.getAbsolutePath());
    }

    synchronized Snapshot read() throws IOException {
        if (!file.isFile()) return null;
        byte[] bytes = readBounded(file, 16_384);
        try {
            JSONObject envelope = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            if (envelope.optInt("version", -1) != 1
                    || envelope.optInt("owner_uid", -1) != ownerUid)
                throw new IOException("Motion config ownership/version mismatch");
            String id = envelope.optString("session_id", "");
            if (!MotionDetectorProtocol.validSession(id))
                throw new IOException("Motion config session ID invalid");
            MotionConfig config = MotionConfig.parse(envelope.getString("config")).cameraConfig();
            if (!config.anyEnabled()) return null;
            return new Snapshot(id, config);
        } catch (JSONException invalid) {
            throw new IOException("Motion config is malformed", invalid);
        }
    }

    synchronized Snapshot write(String configJson) throws IOException {
        final MotionConfig config;
        try { config = MotionConfig.parse(configJson).cameraConfig(); }
        catch (JSONException invalid) { throw new IOException("invalid Motion config", invalid); }
        if (!config.anyEnabled()) {
            delete();
            return null;
        }
        Snapshot previous = read();
        if (previous != null && previous.config.toJson().equals(config.toJson())) return previous;
        boolean inPlace = previous != null && config.onlyInPlaceMotionChangesDifferFrom(previous.config);
        String id = inPlace ? previous.sessionId : UUID.randomUUID().toString().replace("-", "");
        try {
            JSONObject envelope = new JSONObject().put("version", 1).put("owner_uid", ownerUid)
                    .put("session_id", id).put("config", config.toJson());
            writeAtomic(envelope.toString().getBytes(StandardCharsets.UTF_8));
            return new Snapshot(id, config);
        } catch (JSONException impossible) {
            throw new IOException("could not serialize Motion config", impossible);
        }
    }

    synchronized void delete() throws IOException {
        if (file.exists() && !file.delete()) throw new IOException("could not delete Motion config");
        if (shellStateRoot && root.exists()) chmod(root, 0700);
    }

    private void writeAtomic(byte[] bytes) throws IOException {
        if (!root.isDirectory() && !root.mkdirs() && !root.isDirectory())
            throw new IOException("could not create shell-private Motion state directory");
        if (shellStateRoot) chmod(root, 0700);
        File temp = new File(root, "config.json.tmp");
        try (FileOutputStream output = new FileOutputStream(temp)) {
            output.write(bytes);
            output.flush();
            output.getFD().sync();
        }
        if (shellStateRoot) chmod(temp, 0600);
        try {
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException failure) {
            temp.delete();
            throw new IOException("could not atomically publish Motion config", failure);
        }
        if (shellStateRoot) chmod(file, 0600);
        syncDirectory(root);
    }

    private static byte[] readBounded(File file, int limit) throws IOException {
        try (FileInputStream input = new FileInputStream(file);
                ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[2048];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (bytes.size() + count > limit) throw new IOException("Motion config exceeds size limit");
                bytes.write(buffer, 0, count);
            }
            return bytes.toByteArray();
        }
    }

    private static void chmod(File file, int mode) throws IOException {
        try { Os.chmod(file.getAbsolutePath(), mode); }
        catch (Exception error) { throw new IOException("could not secure Motion state path", error); }
    }

    private static void syncDirectory(File directory) throws IOException {
        try (FileInputStream input = new FileInputStream(directory)) { input.getFD().sync(); }
        catch (IOException ignored) {
            // Some Android filesystems do not expose directory fsync through FileInputStream.
        }
    }
}
