package com.byd.turnsignalguard.capture;

import android.content.Context;
import android.os.Build;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
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

/** Creates a private diagnostic ZIP. Callers own threading and the Android share flow. */
final class DiagnosticLogExporter {
    static final int MANIFEST_SCHEMA_VERSION = 1;
    private static final int BUFFER_SIZE = 64 * 1024;
    private static final String ARCHIVE_DIRECTORY = "shared_logs";
    private static final String MANIFEST_ENTRY = "manifest.json";

    private static final CollectorSpec[] SYSTEM_COLLECTORS = {
            new CollectorSpec(
                    "system/bydturnguard_helper.log",
                    "tail -c 1048576 /data/local/tmp/bydturnguard_helper.log 2>/dev/null"),
            new CollectorSpec(
                    "system/bydturnguard_camera.log",
                    "tail -c 1048576 /data/local/tmp/bydturnguard_camera.log 2>/dev/null"),
            new CollectorSpec(
                    "system/logcat-all.txt",
                    "logcat -b all -v threadtime -d 2>/dev/null", true),
            new CollectorSpec(
                    "system/logcat-crash-threadtime.txt",
                    "logcat -b crash -v threadtime -d 2>/dev/null | tail -c 2097152"),
            new CollectorSpec(
                    "system/dropbox-crash.txt",
                    "for tag in system_server_crash system_app_crash data_app_crash; do dumpsys dropbox --print \"$tag\" 2>/dev/null; done | tail -c 1048576"),
            new CollectorSpec(
                    "system/dropbox-native-crash.txt",
                    "for tag in system_server_native_crash system_app_native_crash data_app_native_crash; do dumpsys dropbox --print \"$tag\" 2>/dev/null; done | tail -c 1048576"),
            new CollectorSpec(
                    "system/dropbox-anr.txt",
                    "for tag in system_server_anr system_app_anr data_app_anr; do dumpsys dropbox --print \"$tag\" 2>/dev/null; done | tail -c 1048576"),
            new CollectorSpec(
                    "system/dropbox-tombstone.txt",
                    "for tag in system_server_tombstone system_app_tombstone data_app_tombstone; do dumpsys dropbox --print \"$tag\" 2>/dev/null; done | tail -c 1048576"),
            new CollectorSpec(
                    "system/dropbox-watchdog.txt",
                    "for tag in system_server_watchdog system_server_wtf; do dumpsys dropbox --print \"$tag\" 2>/dev/null; done | tail -c 1048576"),
            new CollectorSpec(
                    "system/dropbox.txt",
                    "dumpsys dropbox --print 2>/dev/null | tail -c 2097152"),
            new CollectorSpec(
                    "system/tombstones-latest.txt",
                    "for file in $(ls -1t /data/tombstones/tombstone_* 2>/dev/null | head -n 3); do printf '\\n--- %s ---\\n' \"$file\"; tail -c 524288 \"$file\" 2>/dev/null; done"),
            new CollectorSpec(
                    "system/firmware-platform-abi.txt",
                    "for prop in ro.build.version.release ro.build.version.incremental ro.build.version.security_patch ro.build.id ro.build.display.id ro.product.board ro.board.platform ro.hardware ro.boot.hardware ro.product.cpu.abi ro.product.cpu.abilist; do printf '%s=' \"$prop\"; getprop \"$prop\"; done"),
            new CollectorSpec(
                    "system/byd-avc-package.txt",
                    "dumpsys package com.byd.avc 2>/dev/null | grep -E 'versionName=|versionCode=|longVersionCode=|firstInstallTime=|lastUpdateTime=|enabled=' | head -n 20")
    };

    private DiagnosticLogExporter() {}

    /** Snapshot after the helper-service flush handshake and before background export starts. */
    static Snapshot snapshot(Context context) {
        File files = context.getFilesDir();
        File external = context.getExternalFilesDir(null);
        List<File> searchDirectories = new ArrayList<>();
        if (external != null) searchDirectories.add(new File(external, "captures"));
        searchDirectories.add(new File(files, "captures"));
        searchDirectories.add(files);
        return snapshot(searchDirectories.toArray(new File[0]));
    }

    static Snapshot snapshot(File... searchDirectories) {
        Map<String, Source> sources = new LinkedHashMap<>();
        for (File directory : searchDirectories) {
            if (directory == null || !directory.isDirectory()) continue;
            File[] files = directory.listFiles(file -> file.isFile() && isRetainedJsonl(file));
            if (files == null) continue;
            Arrays.sort(files, Comparator.comparing(File::getName));
            for (File file : files) {
                try {
                    File canonical = file.getCanonicalFile();
                    sources.putIfAbsent(canonical.getPath(), new Source(canonical, canonical.length()));
                } catch (IOException ignored) {
                    File absolute = file.getAbsoluteFile();
                    sources.putIfAbsent(absolute.getPath(), new Source(absolute, absolute.length()));
                }
            }
        }
        return new Snapshot(new ArrayList<>(sources.values()));
    }

    /** Runs on a worker after {@link #snapshot(Context)} has captured source bounds. */
    static File export(Context context, Snapshot snapshot) throws IOException {
        Identity identity = new Identity(
                context.getPackageName(),
                BuildConfig.VERSION_NAME,
                BuildConfig.VERSION_CODE,
                Build.MANUFACTURER,
                Build.MODEL,
                Build.PRODUCT,
                Build.DEVICE,
                Build.VERSION.RELEASE,
                Build.VERSION.INCREMENTAL,
                Build.BOARD,
                Build.HARDWARE,
                joinAbis(Build.SUPPORTED_ABIS),
                Build.VERSION.SDK_INT);
        return export(
                context.getCacheDir(),
                snapshot,
                identity,
                command -> fromAdbResult(LocalAdbClient.executeAuthorized(
                        context, command, (kind, fields) -> {})),
                (command, output) -> fromAdbResult(
                        LocalAdbClient.executeAuthorizedStreaming(
                                context, command, output, Long.MAX_VALUE,
                                (kind, fields) -> {})),
                System.currentTimeMillis());
    }

    static File export(
            File cacheDirectory,
            Snapshot snapshot,
            Identity identity,
            CommandRunner commandRunner,
            long createdAtMillis) throws IOException {
        return export(cacheDirectory, snapshot, identity, commandRunner,
                (command, output) -> {
                    CommandResult result = commandRunner.run(command);
                    if (result != null && !result.output.isEmpty()) {
                        output.write(result.output.getBytes(StandardCharsets.UTF_8));
                    }
                    return result;
                }, createdAtMillis);
    }

    static File export(
            File cacheDirectory,
            Snapshot snapshot,
            Identity identity,
            CommandRunner commandRunner,
            StreamCommandRunner streamCommandRunner,
            long createdAtMillis) throws IOException {
        File outputDirectory = new File(cacheDirectory, ARCHIVE_DIRECTORY);
        if (!outputDirectory.isDirectory() && !outputDirectory.mkdirs()) {
            throw new IOException("Unable to create diagnostic archive directory");
        }
        File finished = nextAvailableFile(outputDirectory, archiveName(createdAtMillis));
        File partial = new File(finished.getPath() + ".part");
        if (partial.exists() && !partial.delete()) {
            throw new IOException("Unable to replace incomplete diagnostic archive");
        }

        List<SourceRecord> records = new ArrayList<>();
        try {
            writeArchive(partial, snapshot, identity, commandRunner,
                    streamCommandRunner, createdAtMillis, records);
            if (!partial.renameTo(finished)) {
                throw new IOException("Unable to finish diagnostic archive atomically");
            }
            deleteOlderArchives(outputDirectory, finished);
            return finished;
        } catch (Throwable error) {
            if (partial.exists()) partial.delete();
            if (error instanceof IOException) throw (IOException) error;
            throw new IOException("Unable to create diagnostic archive", error);
        }
    }

    static String[] fixedCommands() {
        String[] commands = new String[SYSTEM_COLLECTORS.length];
        for (int i = 0; i < SYSTEM_COLLECTORS.length; i++) {
            commands[i] = SYSTEM_COLLECTORS[i].command;
        }
        return commands;
    }

    static String archiveName(long createdAtMillis) {
        SimpleDateFormat format = new SimpleDateFormat(
                "yyyyMMdd-HHmmss-SSS", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return "byd-turnsignal-diagnostics-" + format.format(new Date(createdAtMillis)) + ".zip";
    }

    private static void writeArchive(
            File partial,
            Snapshot snapshot,
            Identity identity,
            CommandRunner commandRunner,
            StreamCommandRunner streamCommandRunner,
            long createdAtMillis,
            List<SourceRecord> records) throws IOException {
        Set<String> usedNames = new HashSet<>();
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(partial))) {
            for (Source source : snapshot.sources) {
                String entry = uniqueLogEntry(source.file.getName(), usedNames);
                SourceRecord record = new SourceRecord(
                        "app_jsonl", entry, source.file.getName(), source.length);
                records.add(record);
                try {
                    long currentLength = source.file.length();
                    long readableBound = Math.min(source.length, currentLength);
                    long completeBound = completeJsonlLength(source.file, readableBound);
                    record.droppedBytes = Math.max(0, source.length - completeBound);
                    zip.putNextEntry(new ZipEntry(entry));
                    record.archivedBytes = copyBounded(source.file, completeBound, zip);
                    zip.closeEntry();
                    if (record.archivedBytes != source.length) {
                        record.status = "partial";
                        if (currentLength < source.length) record.error = "source_shrank";
                    } else {
                        record.status = "included";
                    }
                } catch (Throwable error) {
                    safelyCloseEntry(zip);
                    record.status = "error";
                    record.error = summary(error);
                }
            }

            for (CollectorSpec collector : SYSTEM_COLLECTORS) {
                SourceRecord record = new SourceRecord(
                        "system", collector.entry, collector.entry, -1);
                records.add(record);
                CommandResult result;
                if (collector.streaming) {
                    CountingOutputStream output = new CountingOutputStream(zip);
                    zip.putNextEntry(new ZipEntry(collector.entry));
                    try {
                        result = streamCommandRunner.run(collector.command, output);
                        if (result == null) throw new IOException("collector_returned_null");
                    } catch (Throwable error) {
                        safelyCloseEntry(zip);
                        record.archivedBytes = output.count;
                        record.status = output.count > 0 ? "partial" : "error";
                        record.error = summary(error);
                        continue;
                    }
                    zip.closeEntry();
                    record.archivedBytes = output.count;
                    applyCollectorResult(record, result, output.count > 0);
                    continue;
                }
                try {
                    result = commandRunner.run(collector.command);
                    if (result == null) throw new IOException("collector_returned_null");
                } catch (Throwable error) {
                    record.status = "error";
                    record.error = summary(error);
                    continue;
                }
                byte[] output = result.output.getBytes(StandardCharsets.UTF_8);
                if (output.length > 0) {
                    zip.putNextEntry(new ZipEntry(collector.entry));
                    zip.write(output);
                    zip.closeEntry();
                    record.archivedBytes = output.length;
                }
                applyCollectorResult(record, result, output.length > 0);
            }

            byte[] manifest = manifest(identity, createdAtMillis, records)
                    .getBytes(StandardCharsets.UTF_8);
            zip.putNextEntry(new ZipEntry(MANIFEST_ENTRY));
            zip.write(manifest);
            zip.closeEntry();
        }
    }

    private static long completeJsonlLength(File file, long bound) throws IOException {
        if (bound <= 0) return 0;
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            long readable = Math.min(bound, input.length());
            if (readable <= 0) return 0;
            input.seek(readable - 1);
            if (input.read() == '\n') return readable;

            byte[] buffer = new byte[BUFFER_SIZE];
            long cursor = readable;
            while (cursor > 0) {
                int count = (int) Math.min(buffer.length, cursor);
                cursor -= count;
                input.seek(cursor);
                input.readFully(buffer, 0, count);
                for (int i = count - 1; i >= 0; i--) {
                    if (buffer[i] == '\n') return cursor + i + 1;
                }
            }
            return 0;
        }
    }

    private static long copyBounded(File source, long bound, ZipOutputStream output)
            throws IOException {
        long remaining = bound;
        long copied = 0;
        byte[] buffer = new byte[BUFFER_SIZE];
        try (FileInputStream input = new FileInputStream(source)) {
            while (remaining > 0) {
                int read = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (read < 0) break;
                output.write(buffer, 0, read);
                copied += read;
                remaining -= read;
            }
        }
        return copied;
    }

    private static String manifest(
            Identity identity, long createdAtMillis, List<SourceRecord> records) {
        StringBuilder json = new StringBuilder(1024);
        json.append('{')
                .append("\"schema_version\":").append(MANIFEST_SCHEMA_VERSION)
                .append(",\"created_at_ms\":").append(createdAtMillis)
                .append(",\"app\":{")
                .append("\"package\":").append(quote(identity.packageName))
                .append(",\"version_name\":").append(quote(identity.versionName))
                .append(",\"version_code\":").append(identity.versionCode)
                .append("},\"device\":{")
                .append("\"manufacturer\":").append(quote(identity.manufacturer))
                .append(",\"model\":").append(quote(identity.model))
                .append(",\"product\":").append(quote(identity.product))
                .append(",\"device\":").append(quote(identity.device))
                .append(",\"android_release\":").append(quote(identity.androidRelease))
                .append(",\"firmware\":").append(quote(identity.firmware))
                .append(",\"board\":").append(quote(identity.board))
                .append(",\"platform\":").append(quote(identity.platform))
                .append(",\"abi\":").append(quote(identity.abi))
                .append(",\"sdk\":").append(identity.sdk)
                .append("},\"sources\":[");
        for (int i = 0; i < records.size(); i++) {
            if (i > 0) json.append(',');
            SourceRecord record = records.get(i);
            json.append('{')
                    .append("\"type\":").append(quote(record.type))
                    .append(",\"name\":").append(quote(record.name))
                    .append(",\"entry\":").append(quote(record.entry))
                    .append(",\"status\":").append(quote(record.status))
                    .append(",\"snapshot_bytes\":").append(record.snapshotBytes)
                    .append(",\"archived_bytes\":").append(record.archivedBytes)
                    .append(",\"dropped_tail_bytes\":").append(record.droppedBytes)
                    .append(",\"exit_code\":").append(record.exitCode)
                    .append(",\"error\":").append(quote(record.error))
                    .append('}');
        }
        return json.append("]}").toString();
    }

    private static String quote(String value) {
        if (value == null) return "null";
        StringBuilder escaped = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            switch (character) {
                case '"': escaped.append("\\\""); break;
                case '\\': escaped.append("\\\\"); break;
                case '\b': escaped.append("\\b"); break;
                case '\f': escaped.append("\\f"); break;
                case '\n': escaped.append("\\n"); break;
                case '\r': escaped.append("\\r"); break;
                case '\t': escaped.append("\\t"); break;
                default:
                    if (character < 0x20) {
                        escaped.append(String.format(Locale.US, "\\u%04x", (int) character));
                    } else {
                        escaped.append(character);
                    }
            }
        }
        return escaped.append('"').toString();
    }

    private static boolean isPermissionDenied(String error) {
        if (error == null) return false;
        String normalized = error.toLowerCase(Locale.US);
        return normalized.contains("authorization_required")
                || normalized.contains("authorization_rejected")
                || normalized.contains("permission_denied")
                || normalized.contains("permission denied")
                || normalized.contains("shell_exit_13");
    }

    private static void applyCollectorResult(
            SourceRecord record, CommandResult result, boolean hasOutput) {
        record.error = result.error;
        record.exitCode = result.exitCode;
        record.status = isPermissionDenied(result.error)
                ? "permission_denied"
                : hasOutput ? result.ok ? "included" : "partial"
                : result.ok ? "missing" : "error";
    }

    private static String joinAbis(String[] abis) {
        if (abis == null || abis.length == 0) return "";
        StringBuilder joined = new StringBuilder();
        for (String abi : abis) {
            if (abi == null || abi.isEmpty()) continue;
            if (joined.length() > 0) joined.append(',');
            joined.append(abi);
        }
        return joined.toString();
    }

    private static CommandResult fromAdbResult(LocalAdbClient.Result result) {
        if (result.ok) return CommandResult.success(result.output);
        String error = result.authorizationRequired ? "authorization_required" : result.error;
        return CommandResult.failure(result.output, error, result.exitCode);
    }

    private static boolean isRetainedJsonl(File file) {
        String name = file.getName();
        return name.endsWith(".jsonl")
                && (name.startsWith("guard-camera-") || name.startsWith("helper-service-"));
    }

    private static String uniqueLogEntry(String fileName, Set<String> usedNames) {
        String candidate = "logs/" + fileName;
        if (usedNames.add(candidate)) return candidate;
        int suffix = 2;
        do {
            candidate = "logs/" + suffix++ + "-" + fileName;
        } while (!usedNames.add(candidate));
        return candidate;
    }

    private static File nextAvailableFile(File directory, String preferredName) {
        File preferred = new File(directory, preferredName);
        if (!preferred.exists() && !new File(preferred.getPath() + ".part").exists()) {
            return preferred;
        }
        int extension = preferredName.lastIndexOf('.');
        String stem = extension < 0 ? preferredName : preferredName.substring(0, extension);
        String suffix = extension < 0 ? "" : preferredName.substring(extension);
        int index = 2;
        File candidate;
        do {
            candidate = new File(directory, stem + "-" + index++ + suffix);
        } while (candidate.exists() || new File(candidate.getPath() + ".part").exists());
        return candidate;
    }

    private static void deleteOlderArchives(File directory, File current) {
        File[] files = directory.listFiles(file -> file.isFile()
                && file.getName().startsWith("byd-turnsignal-diagnostics-")
                && file.getName().endsWith(".zip") && !file.equals(current));
        if (files == null) return;
        for (File file : files) file.delete();
    }

    private static void safelyCloseEntry(ZipOutputStream zip) {
        try {
            zip.closeEntry();
        } catch (Throwable ignored) {
        }
    }

    private static String summary(Throwable error) {
        String message = error.getMessage();
        return error.getClass().getSimpleName()
                + (message == null || message.isEmpty() ? "" : ": " + message);
    }

    static final class Snapshot {
        final List<Source> sources;

        Snapshot(List<Source> sources) {
            this.sources = Collections.unmodifiableList(new ArrayList<>(sources));
        }
    }

    static final class Source {
        final File file;
        final long length;

        Source(File file, long length) {
            this.file = file;
            this.length = Math.max(0, length);
        }
    }

    static final class Identity {
        final String packageName;
        final String versionName;
        final int versionCode;
        final String manufacturer;
        final String model;
        final String product;
        final String device;
        final String androidRelease;
        final String firmware;
        final String board;
        final String platform;
        final String abi;
        final int sdk;

        Identity(
                String packageName,
                String versionName,
                int versionCode,
                String manufacturer,
                String model,
                String product,
                String device,
                String androidRelease,
                int sdk) {
            this(packageName, versionName, versionCode, manufacturer, model, product, device,
                    androidRelease, "", "", "", "", sdk);
        }

        Identity(
                String packageName,
                String versionName,
                int versionCode,
                String manufacturer,
                String model,
                String product,
                String device,
                String androidRelease,
                String firmware,
                String board,
                String platform,
                String abi,
                int sdk) {
            this.packageName = packageName;
            this.versionName = versionName;
            this.versionCode = versionCode;
            this.manufacturer = manufacturer;
            this.model = model;
            this.product = product;
            this.device = device;
            this.androidRelease = androidRelease;
            this.firmware = firmware;
            this.board = board;
            this.platform = platform;
            this.abi = abi;
            this.sdk = sdk;
        }
    }

    interface CommandRunner {
        CommandResult run(String command);
    }

    interface StreamCommandRunner {
        CommandResult run(String command, OutputStream output) throws IOException;
    }

    static final class CommandResult {
        final boolean ok;
        final String output;
        final String error;
        final int exitCode;

        private CommandResult(boolean ok, String output, String error, int exitCode) {
            this.ok = ok;
            this.output = output == null ? "" : output;
            this.error = error == null || error.isEmpty() ? null : error;
            this.exitCode = exitCode;
        }

        static CommandResult success(String output) {
            return new CommandResult(true, output, null, 0);
        }

        static CommandResult failure(String output, String error, int exitCode) {
            return new CommandResult(false, output, error, exitCode);
        }
    }

    private static final class CollectorSpec {
        final String entry;
        final String command;
        final boolean streaming;

        CollectorSpec(String entry, String command) {
            this(entry, command, false);
        }

        CollectorSpec(String entry, String command, boolean streaming) {
            this.entry = entry;
            this.command = command;
            this.streaming = streaming;
        }
    }

    private static final class CountingOutputStream extends OutputStream {
        private final OutputStream output;
        long count;

        CountingOutputStream(OutputStream output) {
            this.output = output;
        }

        @Override public void write(int value) throws IOException {
            output.write(value);
            count++;
        }

        @Override public void write(byte[] bytes, int offset, int length) throws IOException {
            output.write(bytes, offset, length);
            count += length;
        }
    }

    private static final class SourceRecord {
        final String type;
        final String entry;
        final String name;
        final long snapshotBytes;
        String status = "missing";
        String error;
        long archivedBytes;
        long droppedBytes;
        int exitCode = -1;

        SourceRecord(String type, String entry, String name, long snapshotBytes) {
            this.type = type;
            this.entry = entry;
            this.name = name;
            this.snapshotBytes = snapshotBytes;
        }
    }
}
