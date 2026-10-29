package com.byd.extend;

import android.content.Context;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import org.json.JSONObject;

/** The last full shell evidence is locally exportable even when ADB is unavailable. */
final class CellularRecoveryDiagnostics {
    static final String FILE_NAME = "cellular-recovery.jsonl";

    static void accept(Context context, String snapshot) {
        try {
            android.content.SharedPreferences prefs = context.getSharedPreferences("cellular_diagnostics", Context.MODE_PRIVATE);
            if (snapshot.equals(prefs.getString("snapshot", ""))) return;
            JSONObject record = new JSONObject(snapshot);
            write(context.getFilesDir(), record);
            JSONObject discovery = record.optJSONObject("discovery");
            String status = discovery == null ? "pending" : discovery.optString("status", "pending");
            if (discovery != null && (discovery.has("firmware")
                    && !android.os.Build.FINGERPRINT.equals(discovery.optString("firmware"))
                    || discovery.has("analyzer") && discovery.optInt("analyzer") != OemWakeAnalyzer.VERSION
                    || "failed".equals(status) && discovery.has("request")
                    && discovery.optLong("request") != context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                        .getLong(MotionConfig.CELLULAR_GENERATION_PREF, 0))) status = "pending";
            if (!prefs.edit().putString("snapshot", snapshot).putString("status", status).commit())
                throw new java.io.IOException("Cellular diagnostic preference commit failed");
        } catch (Exception error) {
            CameraHelperService.logViewingEvent(context, "cellular_diagnostic_persist", "cellular",
                    CellularRecovery.diagnosticDetail(error, "stage=app_diagnostic_persist"));
        }
    }

    static void write(File directory, JSONObject snapshot) throws Exception {
        JSONObject record = new JSONObject().put("kind", "cellular_diagnostics")
                .put("wall_ms", System.currentTimeMillis()).put("snapshot", snapshot);
        byte[] bytes = (record + "\n").getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 262_144) throw new java.io.IOException("Cellular diagnostic snapshot exceeds budget");
        File target = new File(directory, FILE_NAME), temp = new File(directory, FILE_NAME + ".tmp");
        try (FileOutputStream output = new FileOutputStream(temp)) {
            output.write(bytes); output.getFD().sync();
        }
        Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
