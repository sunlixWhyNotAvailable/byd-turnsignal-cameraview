package com.byd.extend;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.os.Build;
import com.byd.extend.remote.CameraViewingDiagnostics;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.UUID;

/** Installed-package evidence only: never loads OEM code or starts Sentry. */
final class SentryExportEvidence {
    static final String PACKAGE = "com.byd.sentrymode";
    static final String STREAM_PREFIX = "#sentry-evidence\n";

    static String metadata(Context context) throws Exception {
        PackageInfo info = context.getPackageManager().getPackageInfo(PACKAGE, 0);
        ApplicationInfo app = info.applicationInfo;
        if (app == null) throw new IOException("Sentry PackageManager returned no applicationInfo");
        return new JSONObject().put("package", PACKAGE).put("version_name", info.versionName)
                .put("version_code", Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode)
                .put("last_update_ms", info.lastUpdateTime)
                .put("uid", app.uid).put("source_dir", app.sourceDir)
                .put("split_source_dirs", new JSONArray(app.splitSourceDirs == null ? new String[0] : app.splitSourceDirs))
                .put("native_library_dir", app.nativeLibraryDir)
                .put("device_abis", new JSONArray(Build.SUPPORTED_ABIS)).toString();
    }

    static boolean allowedResolved(String path) {
        if (path == null || path.contains("..") || path.indexOf('\n') >= 0 || path.indexOf('\r') >= 0
                || path.indexOf('\0') >= 0) return false;
        if (CompatibilityBundleEvidence.isHuaweiApk(path)) return true;
        int slash = path.lastIndexOf('/');
        if (slash > 0 && CompatibilityBundleEvidence.isHuaweiNativeLibrary(path, path.substring(0, slash))) return true;
        for (String root : new String[]{"/system/lib/", "/system/lib64/", "/vendor/lib/", "/vendor/lib64/",
                "/odm/lib/", "/odm/lib64/", "/system_ext/lib/", "/system_ext/lib64/", "/product/lib/", "/product/lib64/"})
            if (path.startsWith(root) && path.endsWith(".so")) return true;
        return false;
    }

    static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    static String hex(byte[] bytes) {
        StringBuilder value = new StringBuilder(bytes.length * 2);
        for (byte item : bytes) value.append(String.format(java.util.Locale.ROOT, "%02x", item & 255));
        return value.toString();
    }

    static CompatibilityBundleExporter.StreamResult stream(Context context, String command,
            OutputStream output, long limit, CompatibilityBundleExporter.ExportControl control) {
        String errors = "/data/local/tmp/bydextend_sentry_export_" + UUID.randomUUID() + ".err";
        LocalAdbClient.Result result = LocalAdbClient.executeAuthorizedStreaming(context,
                "(" + command + ") 2>" + quote(errors), output, limit,
                LocalAdbClient.EXPORT_READ_TIMEOUT_MS, control, (kind, fields) -> {});
        // Legacy ADB merges stderr with binary stdout. Retain it separately, then remove our own file.
        LocalAdbClient.Result diagnostic = LocalAdbClient.executeAuthorizedText(context,
                "if [ -e " + quote(errors) + " ]; then cat " + quote(errors)
                        + "; rc=$?; rm -f " + quote(errors) + "; exit $rc; fi",
                64 * 1024, 5_000, null, (kind, fields) -> {});
        String detail = (result.error == null ? "" : result.error)
                + (result.cause == null ? "" : "\n" + CameraViewingDiagnostics.describe(result.cause))
                + (diagnostic.output.isEmpty() ? "" : "\n" + diagnostic.output);
        if (!diagnostic.ok) {
            detail += "\nstderr collection/cleanup: " + diagnostic.error;
            if (diagnostic.cause != null) detail += "\n" + CameraViewingDiagnostics.describe(diagnostic.cause);
        }
        return result.ok && diagnostic.ok && diagnostic.output.isEmpty()
                ? CompatibilityBundleExporter.StreamResult.success(result.exitCode, 0)
                : CompatibilityBundleExporter.StreamResult.failure(detail, result.exitCode, 0);
    }

    static String quote(String text) { return "'" + text.replace("'", "'\\''") + "'"; }
}
