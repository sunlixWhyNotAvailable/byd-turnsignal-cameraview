package com.byd.extend;

import android.content.Context;
import android.os.DropBoxManager;
import android.os.Looper;
import android.os.Process;
import com.byd.extend.remote.CameraViewingDiagnostics;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.json.JSONArray;
import org.json.JSONObject;

/** One-shot, read-only ADB export. DropBox's text dump lists binary entries without their data. */
public final class DropBoxTombstoneCollector {
    private static final String TAG = "SYSTEM_TOMBSTONE_PROTO";

    public static void main(String[] args) {
        OutputStream binary = new FileOutputStream(FileDescriptor.out);
        System.setOut(System.err); // Framework initialization must not corrupt the binary archive.
        try (ZipOutputStream zip = new ZipOutputStream(binary)) {
            if (Process.myUid() != 2000) throw new SecurityException("DropBox export requires ADB shell UID");
            JSONObject manifest = new JSONObject().put("tag", TAG).put("uid", Process.myUid())
                    .put("pid", Process.myPid()).put("android_user", Process.myUid() / 100000);
            JSONArray entries = new JSONArray();
            manifest.put("entries", entries);
            try {
                if (Looper.myLooper() == null) Looper.prepare();
                Class<?> activityThread = Class.forName("android.app.ActivityThread");
                Object thread = activityThread.getMethod("systemMain").invoke(null);
                Context system = (Context) activityThread.getMethod("getSystemContext").invoke(thread);
                Context shell = TurnSignalShellMain.shellContext(system);
                manifest.put("attribution", shell.getPackageName());
                if (!"com.android.shell".equals(shell.getPackageName()))
                    throw new SecurityException("DropBox shell Context attribution mismatch");
                DropBoxManager manager = (DropBoxManager) shell.getSystemService(Context.DROPBOX_SERVICE);
                if (manager == null) throw new IllegalStateException("DropBox service unavailable");
                long cursor = 0;
                boolean lostPayload = false;
                while (true) {
                    try (DropBoxManager.Entry entry = manager.getNextEntry(TAG, cursor)) {
                        if (entry == null) break;
                        long timestamp = entry.getTimeMillis();
                        if (timestamp <= cursor) throw new IOException("DropBox cursor did not advance: " + timestamp);
                        JSONObject record = new JSONObject().put("tag", TAG).put("time_ms", timestamp)
                                .put("status", "partial").put("bytes", JSONObject.NULL);
                        entries.put(record);
                        try (InputStream input = entry.getInputStream()) {
                            if (input == null) {
                                lostPayload = true;
                                record.put("status", "payload_lost").put("bytes", 0);
                            } else {
                                String name = TAG + "_" + timestamp + ".pb";
                                record.put("entry", name);
                                record.put("bytes", writePayload(zip, name, input)).put("status", "included");
                            }
                        }
                        cursor = timestamp;
                    }
                }
                manifest.put("method", "DropBoxManager.getNextEntry").put("enumeration_complete", true)
                        .put("status", lostPayload ? "partial" : "complete");
            } catch (Throwable primary) {
                manifest.put("primary_failure", CameraViewingDiagnostics.describe(primary))
                        .put("status", "partial");
                try {
                    File stderr = File.createTempFile("extend-dropbox-", ".err", new File("/data/local/tmp"));
                    java.lang.Process dump = null;
                    try {
                        dump = new ProcessBuilder("dumpsys", "dropbox", "--proto", TAG)
                                .redirectError(stderr).start();
                        try (InputStream input = dump.getInputStream()) {
                            manifest.put("fallback_bytes", writePayload(zip, "dropbox-limited.pb", input));
                        }
                        manifest.put("fallback_exit", dump.waitFor())
                                .put("fallback_method", "dumpsys --proto")
                                .put("fallback_limit_per_entry_bytes", 262144)
                                .put("fallback_complete_payloads_guaranteed", false);
                        try (InputStream input = new FileInputStream(stderr)) {
                            writePayload(zip, "fallback-stderr.txt", input);
                        }
                    } finally {
                        if (dump != null) dump.destroy();
                        if (!stderr.delete()) manifest.put("cleanup_failure", "could not remove fallback stderr");
                    }
                } catch (Throwable secondary) {
                    manifest.put("fallback_failure", CameraViewingDiagnostics.describe(secondary));
                }
            }
            zip.putNextEntry(new ZipEntry("manifest.json"));
            zip.write(manifest.toString(2).getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        } catch (Throwable failure) {
            failure.printStackTrace(System.err);
            System.exit(1);
        }
        System.exit(0);
    }

    static long writePayload(ZipOutputStream zip, String name, InputStream input) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        long bytes = 0;
        byte[] buffer = new byte[64 * 1024];
        Throwable primary = null;
        try {
            int count;
            while ((count = input.read(buffer)) != -1) {
                zip.write(buffer, 0, count);
                bytes += count;
            }
        } catch (IOException | RuntimeException | Error failure) {
            primary = failure;
            throw failure;
        } finally {
            try { zip.closeEntry(); }
            catch (IOException cleanup) {
                if (primary == null) throw cleanup;
                primary.addSuppressed(cleanup);
            }
        }
        return bytes;
    }

    static String command(String apkPath) {
        return "CLASSPATH='" + apkPath.replace("'", "'\\''") + "' app_process /system/bin "
                + DropBoxTombstoneCollector.class.getName()
                + " 2>/data/local/tmp/bydextend-dropbox-export.err";
    }
}
