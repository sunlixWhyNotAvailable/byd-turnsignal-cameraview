package com.byd.extend;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import org.json.JSONException;
import org.json.JSONObject;

/** Storage-only policy; never grants camera demand or changes the user's quota. */
final class MotionOemRetentionState {
    final boolean enabled, primarySd, suspended;
    final int limitGb;

    MotionOemRetentionState(boolean enabled, boolean primarySd, int limitGb, boolean suspended) {
        if (limitGb < 0) throw new IllegalArgumentException("negative OEM quota");
        this.enabled = enabled;
        this.primarySd = primarySd;
        this.limitGb = limitGb;
        this.suspended = suspended;
    }

    static MotionOemRetentionState from(MotionConfig config) {
        return new MotionOemRetentionState(config.oemCleanup, config.primarySd, config.oemLimitGb, false);
    }

    boolean active() { return enabled && !suspended; }
    boolean sameAs(MotionOemRetentionState other) {
        return other != null && enabled == other.enabled && primarySd == other.primarySd
                && limitGb == other.limitGb && suspended == other.suspended;
    }
    long limitBytes(long capacityBytes) {
        return limitGb == 0 ? Math.max(0, capacityBytes) : limitGb * MotionStoragePolicy.GB;
    }

    void write(File file, int ownerUid) throws IOException {
        if (!file.getParentFile().isDirectory()) throw new IOException("OEM retention directory is missing");
        File temporary = new File(file.getPath() + ".tmp");
        Throwable primary = null;
        try {
            JSONObject json = new JSONObject().put("version", 1).put("ownerUid", ownerUid)
                    .put("enabled", enabled).put("sd", primarySd).put("limitGb", limitGb)
                    .put("suspended", suspended);
            try (FileOutputStream output = new FileOutputStream(temporary)) {
                output.write(json.toString().getBytes(StandardCharsets.UTF_8));
                output.getFD().sync();
            }
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (JSONException invalid) {
            IOException wrapped = new IOException("Could not serialize OEM retention", invalid);
            primary = wrapped;
            throw wrapped;
        } catch (IOException | RuntimeException failure) {
            primary = failure;
            throw failure;
        } finally {
            try { Files.deleteIfExists(temporary.toPath()); }
            catch (IOException cleanupFailure) {
                if (primary != null) primary.addSuppressed(cleanupFailure);
                else throw cleanupFailure;
            }
        }
    }

    static MotionOemRetentionState read(File file, int ownerUid) throws IOException {
        if (!file.exists()) return new MotionOemRetentionState(false, false, 0, false);
        if (file.length() > 4096) throw new IOException("OEM retention state exceeds limit");
        try {
            JSONObject json = new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
            if (json.getInt("version") != 1 || json.getInt("ownerUid") != ownerUid)
                throw new IOException("OEM retention state ownership/version mismatch");
            return new MotionOemRetentionState(json.getBoolean("enabled"), json.getBoolean("sd"),
                    json.getInt("limitGb"), json.getBoolean("suspended"));
        } catch (JSONException | IllegalArgumentException invalid) {
            throw new IOException("Invalid OEM retention state", invalid);
        }
    }
}
