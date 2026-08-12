package com.byd.turnsignalguard.capture;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Best-effort, separate compatibility evidence bundle. No vehicle writes or camera opens. */
final class CompatibilityBundleExporter {
    static final long MAX_FILE_BYTES = 256L * 1024L * 1024L;
    static final long MAX_TOTAL_BYTES = 512L * 1024L * 1024L;
    static final long MAX_TEXT_BYTES = 16L * 1024L * 1024L;
    static final int MANIFEST_SCHEMA_VERSION = 1;
    private static final int BUFFER_SIZE = 64 * 1024;
    private static final String ARCHIVE_DIRECTORY = "shared_logs";
    private static final String MANIFEST_ENTRY = "manifest.json";
    private static final String PACKAGE_NAME = "com.byd.avc";
    private static final String[] ALLOWED_ROOTS = {
            "/system/framework/", "/system_ext/framework/", "/vendor/framework/",
            "/odm/framework/", "/product/framework/", "/system/app/", "/system/priv-app/",
            "/system_ext/app/", "/system_ext/priv-app/", "/vendor/app/", "/vendor/priv-app/",
            "/odm/app/", "/odm/priv-app/", "/product/app/", "/product/priv-app/",
            "/vendor/etc/", "/odm/etc/", "/system/etc/", "/system_ext/etc/",
            "/data/app/", "/data/vendor/camera/", "/data/misc/camera/"
    };
    private static final String[] PROPERTY_COMMANDS = {
            "getprop ro.build.version.release; getprop ro.build.version.sdk; "
                    + "getprop ro.build.version.security_patch",
            "getprop ro.product.board; getprop ro.board.platform; getprop ro.hardware; "
                    + "getprop ro.soc.model; getprop ro.boot.hardware",
            "getprop ro.product.cpu.abi; getprop ro.product.cpu.abilist; "
                    + "getprop ro.product.cpu.abilist32; getprop ro.product.cpu.abilist64",
            "getprop ro.build.fingerprint; getprop ro.product.manufacturer; "
                    + "getprop ro.product.model; getprop ro.product.name"
    };
    private static final String CAMERA_COMMAND =
            "getprop vehicle.config.cam_sort; getprop vehicle.config.camInfo.avm; "
                    + "getprop vendor.vehicle.config.camInfo.avm; "
                    + "getprop persist.vendor.camera.autostudy.avm; "
                    + "getprop persist.vendor.camera.pano; getprop vendor.camera.config";
    private static final String CLASSPATH_COMMAND =
            "printf 'BOOTCLASSPATH=%s\\nSYSTEMSERVERCLASSPATH=%s\\n' "
                    + "\"$BOOTCLASSPATH\" \"$SYSTEMSERVERCLASSPATH\"";
    private static final String PACKAGE_COMMAND = "pm path " + PACKAGE_NAME + " 2>/dev/null";
    private static final String JAR_FIND_COMMAND =
            "find /system/framework /system_ext/framework /vendor/framework /odm/framework "
            + "/product/framework -maxdepth 1 -type f \\("
                    + "-iname '*byd*.jar' -o -iname '*dilink*.jar' -o -iname '*avm*.jar' "
                    + "-o -iname '*pano*.jar' -o -name 'framework.jar' -o -name 'services.jar'"
            + "\\) -print 2>/dev/null";
    private static final String CONFIG_FIND_COMMAND =
            "find /vendor/etc /odm/etc /system/etc /system_ext/etc /data/vendor/camera "
                    + "/data/misc/camera -maxdepth 4 -type f -print 2>/dev/null";

    private CompatibilityBundleExporter() {}

    static File export(Context context, SharedPreferences preferences) throws IOException {
        Identity identity = new Identity(
                context.getPackageName(), BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE,
                Build.MANUFACTURER, Build.MODEL, Build.PRODUCT, Build.DEVICE,
                Build.VERSION.RELEASE, Build.VERSION.SDK_INT);
        return export(context.getCacheDir(), preferences, identity,
                (command, limit) -> fromAdbResult(LocalAdbClient.executeAuthorizedText(
                        context, command, limit, (kind, fields) -> {})),
                (command, output, limit) -> {
                    CountingOutputStream counted = new CountingOutputStream(output);
                    LocalAdbClient.Result result = LocalAdbClient.executeAuthorizedStreaming(
                            context, command, counted, limit, (kind, fields) -> {});
                    return fromAdbResult(result, counted.count);
                },
                System.currentTimeMillis());
    }

    static File export(
            File cacheDirectory,
            SharedPreferences preferences,
            Identity identity,
            TextCommandRunner textRunner,
            StreamCommandRunner streamRunner,
            long createdAtMillis) throws IOException {
        File outputDirectory = new File(cacheDirectory, ARCHIVE_DIRECTORY);
        if (!outputDirectory.isDirectory() && !outputDirectory.mkdirs()) {
            throw new IOException("Unable to create compatibility archive directory");
        }
        File finished = nextAvailableFile(outputDirectory, archiveName(createdAtMillis));
        File partial = new File(finished.getPath() + ".part");
        if (partial.exists() && !partial.delete()) {
            throw new IOException("Unable to replace incomplete compatibility archive");
        }
        File tempDirectory = new File(outputDirectory, ".compatibility-tmp-"
                + Long.toHexString(createdAtMillis));
        if (!tempDirectory.isDirectory() && !tempDirectory.mkdirs()) {
            throw new IOException("Unable to create compatibility temporary directory");
        }

        byte[] prefsBytes = sanitizedPreferences(preferences)
                .getBytes(StandardCharsets.UTF_8);
        long reservedPrefsBytes = fitsBudget(
                prefsBytes.length, 0L, MAX_FILE_BYTES, MAX_TOTAL_BYTES)
                ? prefsBytes.length : 0L;
        long textBudget = Math.max(0L, MAX_TOTAL_BYTES - reservedPrefsBytes);
        List<TextCapture> text = new ArrayList<>();
        long textTotal = 0L;
        for (TextSpec spec : textSpecs()) {
            CommandResult result;
            long limit = Math.min(MAX_TEXT_BYTES, Math.max(0L, textBudget - textTotal));
            try {
                result = limit <= 0L
                        ? CommandResult.failure("total_limit", -1)
                        : textRunner.run(spec.command, limit);
                if (result == null) result = CommandResult.failure("runner_returned_null", -1);
            } catch (Throwable error) {
                result = CommandResult.failure(summary(error), -1);
            }
            byte[] value = result.output.getBytes(StandardCharsets.UTF_8);
            if (value.length > limit) {
                value = Arrays.copyOf(value, (int) limit);
                result = CommandResult.failure(new String(value, StandardCharsets.UTF_8),
                        "too_large", result.exitCode);
            }
            textTotal += value.length;
            text.add(new TextCapture(spec, result, value));
        }
        List<RemoteSpec> remote = new ArrayList<>();
        Set<String> paths = new HashSet<>();
        addRemote(remote, paths, "/system/framework/framework.jar", "framework");
        addRemote(remote, paths, "/system/framework/services.jar", "framework");
        addPaths(remote, paths, text.get(6).result.output, "com.byd.avc", false);
        addPaths(remote, paths, text.get(7).result.output, "allowlisted_jar", true);
        addPaths(remote, paths, text.get(8).result.output, "avm_config", false);

        List<SourceRecord> records = new ArrayList<>();
        long total = 0L;
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(partial))) {
            for (TextCapture capture : text) {
                SourceRecord record = new SourceRecord(
                        capture.spec.type, capture.spec.entry, capture.spec.command, -1);
                byte[] value = capture.value;
                record.sizeBytes = value.length;
                record.sha256 = sha256(value);
                if (value.length > 0 && total + value.length <= MAX_TOTAL_BYTES) {
                    zip.putNextEntry(new ZipEntry(capture.spec.entry));
                    zip.write(value);
                    zip.closeEntry();
                    record.status = capture.result.ok ? "included"
                            : "too_large".equals(capture.result.error) ? "too_large" : "partial";
                    if (!capture.result.ok) record.error = capture.result.error;
                    total += value.length;
                } else if (value.length > 0) {
                    record.status = "too_large";
                    record.error = "total_limit";
                } else if (isPermission(capture.result.error)) {
                    record.status = "permission_denied";
                    record.error = capture.result.error;
                } else if (capture.result.ok) {
                    record.status = "missing";
                } else {
                    record.status = capture.result.exitCode == 44 ? "missing" : "error";
                    record.error = capture.result.error;
                }
                records.add(record);
            }
            String prefsEntry = "app/sanitized-camera-preferences.json";
            SourceRecord prefsRecord = new SourceRecord("sanitized_preferences", prefsEntry,
                    "local SharedPreferences", prefsBytes.length);
            prefsRecord.sizeBytes = prefsBytes.length;
            prefsRecord.sha256 = sha256(prefsBytes);
            if (fitsBudget(prefsBytes.length, total, MAX_FILE_BYTES, MAX_TOTAL_BYTES)) {
                zip.putNextEntry(new ZipEntry(prefsEntry));
                zip.write(prefsBytes);
                zip.closeEntry();
                prefsRecord.status = "included";
                total += prefsBytes.length;
            } else {
                prefsRecord.status = "too_large";
                prefsRecord.error = prefsBytes.length > MAX_FILE_BYTES
                        ? "size_limit" : "total_limit";
            }
            records.add(prefsRecord);

            for (RemoteSpec spec : remote) {
                SourceRecord record = new SourceRecord(spec.type, entryFor(spec.path),
                        spec.path, -1);
                if (total >= MAX_TOTAL_BYTES) {
                    record.status = "too_large";
                    record.error = "total_limit";
                    records.add(record);
                    continue;
                }
                File temp = new File(tempDirectory, Integer.toHexString(records.size()) + ".bin");
                StreamResult result = null;
                try (FileOutputStream output = new FileOutputStream(temp)) {
                    long remaining = MAX_TOTAL_BYTES - total;
                    result = streamRunner.run(catCommand(spec.path), output,
                            Math.min(MAX_FILE_BYTES, remaining));
                } catch (Throwable error) {
                    result = StreamResult.failure(summary(error), -1, 0L);
                }
                long size = temp.isFile() ? temp.length() : 0L;
                record.sizeBytes = size;
                if (result != null && result.ok && temp.isFile()) {
                    if (size > MAX_FILE_BYTES || size > MAX_TOTAL_BYTES - total) {
                        record.status = "too_large";
                        record.error = "size_limit";
                        record.sha256 = hashFile(temp);
                    } else {
                        zip.putNextEntry(new ZipEntry(record.entry));
                        record.sha256 = copyAndHash(temp, zip);
                        zip.closeEntry();
                        record.sizeBytes = size;
                        record.status = "included";
                        total += size;
                    }
                } else if (result != null && "too_large".equals(result.error)) {
                    record.status = "too_large";
                    record.error = result.error;
                    if (temp.isFile()) record.sha256 = hashFile(temp);
                } else if (result != null && size > 0L) {
                    if (size > MAX_FILE_BYTES || size > MAX_TOTAL_BYTES - total) {
                        record.status = "too_large";
                        record.error = "size_limit";
                        record.sha256 = hashFile(temp);
                    } else {
                        zip.putNextEntry(new ZipEntry(record.entry));
                        record.sha256 = copyAndHash(temp, zip);
                        zip.closeEntry();
                        record.status = "partial";
                        record.error = result.error;
                        total += size;
                    }
                } else if (result != null && result.exitCode == 44) {
                    record.status = "missing";
                    record.error = result.error;
                } else if (result != null && isPermission(result.error)) {
                    record.status = "permission_denied";
                    record.error = result.error;
                } else {
                    record.status = "error";
                    record.error = result == null ? "stream_returned_null" : result.error;
                }
                records.add(record);
                if (temp.exists()) temp.delete();
            }
            byte[] manifest = manifest(identity, createdAtMillis, records)
                    .getBytes(StandardCharsets.UTF_8);
            zip.putNextEntry(new ZipEntry(MANIFEST_ENTRY));
            zip.write(manifest);
            zip.closeEntry();
        } catch (Throwable error) {
            if (partial.exists()) partial.delete();
            deleteTree(tempDirectory);
            if (error instanceof IOException) throw (IOException) error;
            throw new IOException("Unable to create compatibility archive", error);
        }
        deleteTree(tempDirectory);
        if (!partial.renameTo(finished)) {
            partial.delete();
            throw new IOException("Unable to finish compatibility archive atomically");
        }
        deleteOlderCompatibilityArchives(outputDirectory, finished);
        return finished;
    }

    static String archiveName(long createdAtMillis) {
        SimpleDateFormat format = new SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return "byd-turnsignal-compatibility-" + format.format(new Date(createdAtMillis)) + ".zip";
    }

    static String catCommandForTest(String path) {
        return catCommand(path);
    }

    static boolean isAllowedRemotePath(String path) {
        if (path == null || path.isEmpty() || path.indexOf('\0') >= 0
                || path.contains("..") || !path.startsWith("/")) return false;
        for (String root : ALLOWED_ROOTS) {
            if (path.startsWith(root) && path.length() > root.length()) return true;
        }
        return false;
    }

    static boolean fitsBudget(long size, long total, long maxFile, long maxTotal) {
        return size >= 0L && total >= 0L && size <= maxFile && total <= maxTotal - size;
    }

    static boolean isAllowedJarPath(String path) {
        if (!isAllowedRemotePath(path) || !path.endsWith(".jar")) return false;
        String name = basename(path).toLowerCase(Locale.US);
        return name.equals("framework.jar") || name.equals("services.jar")
                || name.contains("byd") || name.contains("dilink")
                || name.contains("avm") || name.contains("pano");
    }

    static String sanitizedPreferences(SharedPreferences preferences) {
        if (preferences == null) return "{}";
        Map<String, ?> all = preferences.getAll();
        List<String> keys = new ArrayList<>(all.keySet());
        Collections.sort(keys);
        StringBuilder json = new StringBuilder("{");
        boolean first = true;
        for (String key : keys) {
            String lower = key == null ? "" : key.toLowerCase(Locale.US);
            if (!isSafePreferenceKey(lower)) continue;
            Object value = all.get(key);
            if (!(value instanceof String || value instanceof Number || value instanceof Boolean)) {
                continue;
            }
            if (!first) json.append(',');
            first = false;
            json.append(quote(key)).append(':');
            if (value instanceof String) json.append(quote(String.valueOf(value)));
            else json.append(String.valueOf(value));
        }
        return json.append('}').toString();
    }

    private static boolean isSafePreferenceKey(String key) {
        if (key.isEmpty() || key.contains("adb") || key.contains("rsa")
                || key.contains("serial") || key.contains("android_id")
                || key.contains("device_id") || key.contains("vin")
                || key.contains("token") || key.contains("fingerprint")
                || key.contains("personal")) return false;
        return key.startsWith("camera_") || key.startsWith("reverse_")
                || key.startsWith("calibration_") || key.startsWith("dewarp_")
                || key.startsWith("blindspot_") || key.startsWith("guard_");
    }

    private static List<TextSpec> textSpecs() {
        List<TextSpec> specs = new ArrayList<>();
        for (int i = 0; i < PROPERTY_COMMANDS.length; i++) {
            specs.add(new TextSpec("device/properties-" + i + ".txt", "build", PROPERTY_COMMANDS[i]));
        }
        specs.add(new TextSpec("camera/discovery-config.txt", "camera_discovery", CAMERA_COMMAND));
        specs.add(new TextSpec("runtime/classpaths.txt", "classpath", CLASSPATH_COMMAND));
        specs.add(new TextSpec("com.byd.avc/paths.txt", "package_metadata", PACKAGE_COMMAND));
        specs.add(new TextSpec("framework/allowlisted-jars.txt", "jar_metadata", JAR_FIND_COMMAND));
        specs.add(new TextSpec("camera/allowlisted-config-files.txt", "config_metadata", CONFIG_FIND_COMMAND));
        return specs;
    }

    private static void addPaths(
            List<RemoteSpec> remote, Set<String> paths, String output, String type, boolean jars) {
        if (output == null) return;
        for (String line : output.split("\\r?\\n")) {
            String path = line.trim();
            if (path.startsWith("package:")) path = path.substring("package:".length());
            boolean allowed = jars ? isAllowedJarPath(path)
                    : type.equals("avm_config")
                    ? isAllowedConfigPath(path) : isAllowedRemotePath(path);
            if (allowed) {
                addRemote(remote, paths, path, type);
            }
        }
    }

    private static void addRemote(List<RemoteSpec> remote, Set<String> paths,
            String path, String type) {
        if (!isAllowedRemotePath(path) || !paths.add(path)) return;
        remote.add(new RemoteSpec(path, type));
    }

    private static String catCommand(String path) {
        String quoted = "'" + path.replace("'", "'\\''") + "'";
        return "(if [ ! -e " + quoted + " ]; then exit 44; "
                + "elif [ ! -r " + quoted + " ]; then exit 13; "
                + "else cat " + quoted + " 2>/dev/null; fi)";
    }

    private static boolean isAllowedConfigPath(String path) {
        if (!isAllowedRemotePath(path)) return false;
        String name = basename(path).toLowerCase(Locale.US);
        boolean cameraDataRoot = path.startsWith("/data/vendor/camera/")
                || path.startsWith("/data/misc/camera/");
        if (!cameraDataRoot && !(name.contains("avm") || name.contains("camera")
                || name.contains("cam") || name.contains("pano"))) return false;
        return name.endsWith(".xml") || name.endsWith(".json") || name.endsWith(".conf")
                || name.endsWith(".ini") || name.endsWith(".cfg") || name.endsWith(".bin")
                || name.endsWith(".dat") || name.endsWith(".db") || name.endsWith(".cache")
                || name.endsWith(".txt");
    }

    static CommandResult fromAdbResult(LocalAdbClient.Result result) {
        if (result.ok) return CommandResult.success(result.output);
        String error = result.authorizationRequired ? "authorization_required" : result.error;
        return CommandResult.failure(result.output, error, result.exitCode);
    }

    private static StreamResult fromAdbResult(LocalAdbClient.Result result, long bytes) {
        if (result.ok) return StreamResult.success(result.exitCode, bytes);
        String error = result.authorizationRequired ? "authorization_required" : result.error;
        return StreamResult.failure(error, result.exitCode, bytes);
    }

    private static String copyAndHash(File source, OutputStream output) throws IOException {
        MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); }
        catch (Exception error) { throw new IOException("SHA-256 unavailable", error); }
        byte[] buffer = new byte[BUFFER_SIZE];
        try (FileInputStream input = new FileInputStream(source)) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) continue;
                output.write(buffer, 0, read);
                digest.update(buffer, 0, read);
            }
        }
        return digestHex(digest.digest());
    }

    private static String sha256(byte[] value) {
        try { return digestHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (Exception error) { return "unavailable"; }
    }

    private static String hashFile(File source) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[BUFFER_SIZE];
            try (FileInputStream input = new FileInputStream(source)) {
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read > 0) digest.update(buffer, 0, read);
                }
            }
            return digestHex(digest.digest());
        } catch (Throwable error) {
            return "unavailable";
        }
    }

    private static String digestHex(byte[] value) {
        StringBuilder result = new StringBuilder(value.length * 2);
        for (byte b : value) result.append(String.format(Locale.US, "%02x", b & 0xff));
        return result.toString();
    }

    private static String manifest(Identity identity, long createdAtMillis,
            List<SourceRecord> records) {
        StringBuilder json = new StringBuilder(2048);
        json.append('{').append("\"schema_version\":").append(MANIFEST_SCHEMA_VERSION)
                .append(",\"created_at_ms\":").append(createdAtMillis)
                .append(",\"app\":{\"package\":").append(quote(identity.packageName))
                .append(",\"version_name\":").append(quote(identity.versionName))
                .append(",\"version_code\":").append(identity.versionCode).append('}')
                .append(",\"device\":{\"manufacturer\":").append(quote(identity.manufacturer))
                .append(",\"model\":").append(quote(identity.model))
                .append(",\"product\":").append(quote(identity.product))
                .append(",\"device\":").append(quote(identity.device))
                .append(",\"android_release\":").append(quote(identity.androidRelease))
                .append(",\"sdk\":").append(identity.sdk).append("},\"sources\":[");
        for (int i = 0; i < records.size(); i++) {
            if (i > 0) json.append(',');
            SourceRecord record = records.get(i);
            json.append('{').append("\"type\":").append(quote(record.type))
                    .append(",\"entry\":").append(quote(record.entry))
                    .append(",\"source\":").append(quote(record.source))
                    .append(",\"status\":").append(quote(record.status))
                    .append(",\"size_bytes\":").append(record.sizeBytes)
                    .append(",\"sha256\":").append(quote(record.sha256))
                    .append(",\"error\":").append(quote(record.error)).append('}');
        }
        return json.append("]}").toString();
    }

    private static String entryFor(String path) {
        return "remote" + path;
    }

    private static String basename(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    private static boolean isPermission(String error) {
        if (error == null) return false;
        String lower = error.toLowerCase(Locale.US);
        return lower.contains("authorization") || lower.contains("permission")
                || lower.contains("denied") || lower.equals("shell_exit_13");
    }

    private static File nextAvailableFile(File directory, String preferredName) {
        File preferred = new File(directory, preferredName);
        if (!preferred.exists() && !new File(preferred.getPath() + ".part").exists()) return preferred;
        int extension = preferredName.lastIndexOf('.');
        String stem = extension < 0 ? preferredName : preferredName.substring(0, extension);
        String suffix = extension < 0 ? "" : preferredName.substring(extension);
        int index = 2;
        File candidate;
        do { candidate = new File(directory, stem + "-" + index++ + suffix); }
        while (candidate.exists() || new File(candidate.getPath() + ".part").exists());
        return candidate;
    }

    private static void deleteOlderCompatibilityArchives(File directory, File current) {
        File[] files = directory.listFiles(file -> file.isFile()
                && file.getName().startsWith("byd-turnsignal-compatibility-")
                && file.getName().endsWith(".zip") && !file.equals(current));
        if (files == null) return;
        for (File file : files) file.delete();
    }

    private static void deleteTree(File directory) {
        if (directory == null) return;
        File[] files = directory.listFiles();
        if (files != null) for (File file : files) {
            if (file.isDirectory()) deleteTree(file);
            else file.delete();
        }
        directory.delete();
    }

    private static String quote(String value) {
        if (value == null) return "null";
        StringBuilder escaped = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"') escaped.append("\\\"");
            else if (c == '\\') escaped.append("\\\\");
            else if (c == '\n') escaped.append("\\n");
            else if (c == '\r') escaped.append("\\r");
            else if (c == '\t') escaped.append("\\t");
            else escaped.append(c);
        }
        return escaped.append('"').toString();
    }

    private static String summary(Throwable error) {
        String message = error.getMessage();
        return error.getClass().getSimpleName()
                + (message == null || message.isEmpty() ? "" : ": " + message);
    }

    private static final class CountingOutputStream extends OutputStream {
        private final OutputStream delegate;
        long count;

        CountingOutputStream(OutputStream delegate) { this.delegate = delegate; }

        @Override public void write(int value) throws IOException {
            delegate.write(value);
            count++;
        }

        @Override public void write(byte[] value, int offset, int length) throws IOException {
            delegate.write(value, offset, length);
            count += length;
        }
    }

    static final class Identity {
        final String packageName, versionName, manufacturer, model, product, device, androidRelease;
        final int versionCode, sdk;
        Identity(String packageName, String versionName, int versionCode, String manufacturer,
                String model, String product, String device, String androidRelease, int sdk) {
            this.packageName = packageName; this.versionName = versionName; this.versionCode = versionCode;
            this.manufacturer = manufacturer; this.model = model; this.product = product;
            this.device = device; this.androidRelease = androidRelease; this.sdk = sdk;
        }
    }

    interface TextCommandRunner {
        CommandResult run(String command, long maxBytes);
    }
    interface StreamCommandRunner { StreamResult run(String command, OutputStream output, long maxBytes); }

    static final class CommandResult {
        final boolean ok; final String output; final String error; final int exitCode;
        private CommandResult(boolean ok, String output, String error, int exitCode) {
            this.ok = ok; this.output = output == null ? "" : output; this.error = error; this.exitCode = exitCode;
        }
        static CommandResult success(String output) { return new CommandResult(true, output, null, 0); }
        static CommandResult failure(String error, int exitCode) {
            return new CommandResult(false, "", error, exitCode);
        }
        static CommandResult failure(String output, String error, int exitCode) {
            return new CommandResult(false, output, error, exitCode);
        }
    }

    static final class StreamResult {
        final boolean ok; final String error; final int exitCode; final long bytesWritten;
        private StreamResult(boolean ok, String error, int exitCode, long bytesWritten) {
            this.ok = ok; this.error = error; this.exitCode = exitCode; this.bytesWritten = bytesWritten;
        }
        static StreamResult success(int exitCode, long bytes) { return new StreamResult(true, null, exitCode, bytes); }
        static StreamResult failure(String error, int exitCode, long bytes) {
            return new StreamResult(false, error, exitCode, bytes);
        }
    }

    private static final class TextSpec {
        final String entry, type, command;
        TextSpec(String entry, String type, String command) { this.entry = entry; this.type = type; this.command = command; }
    }
    private static final class TextCapture {
        final TextSpec spec;
        final CommandResult result;
        final byte[] value;
        TextCapture(TextSpec spec, CommandResult result, byte[] value) {
            this.spec = spec;
            this.result = result;
            this.value = value;
        }
    }
    private static final class RemoteSpec { final String path, type; RemoteSpec(String p, String t) { path = p; type = t; } }
    private static final class SourceRecord {
        final String type, entry, source; final long snapshotBytes; String status = "missing";
        String error, sha256; long sizeBytes;
        SourceRecord(String t, String e, String s, long b) {
            type = t; entry = e; source = s; snapshotBytes = b; sha256 = sha256(new byte[0]);
        }
    }
}
