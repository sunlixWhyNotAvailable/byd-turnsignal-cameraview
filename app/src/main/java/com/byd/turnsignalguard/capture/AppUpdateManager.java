package com.byd.turnsignalguard.capture;

import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

final class AppUpdateManager {
    private static final String PREFS_NAME = "app_updates";
    private static final String KEY_LAST_CHECK_MS = "last_check_ms";
    private static final String RELEASE_API_HOST = "api.github.com";
    private static final String RELEASE_API_PATH =
            "/repos/sunlixWhyNotAvailable/byd-turnsignal-cameraview/releases/latest";
    private static final String APK_DOWNLOAD_HOST = "github.com";
    private static final String APK_PATH_MARKER =
            "/sunlixWhyNotAvailable/byd-turnsignal-cameraview/releases/download/";
    private static final String APK_NAME_PREFIX = "byd-turnsignal-camera-v";
    private static final String APK_MIME = "application/vnd.android.package-archive";
    private static final long CHECK_THROTTLE_MS = 10 * 60 * 1000L;
    private static final long DOWNLOAD_TIMEOUT_MS = 10 * 60 * 1000L;
    private static volatile UpdateInfo cachedAvailable;

    interface ProgressListener {
        void onProgress(int percent);
    }

    static final class UpdateInfo {
        final String version;
        final String downloadUrl;
        final String releaseNotes;

        UpdateInfo(String version, String downloadUrl, String releaseNotes) {
            this.version = version;
            this.downloadUrl = downloadUrl;
            this.releaseNotes = releaseNotes;
        }
    }

    UpdateInfo checkForUpdate(Context context, boolean force) throws Exception {
        UpdateInfo cached = cachedAvailable;
        if (!force && cached != null) return cached;
        if (force) cachedAvailable = null;

        SharedPreferences preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        long lastCheck = preferences.getLong(KEY_LAST_CHECK_MS, 0L);
        if (!force && lastCheck > 0L && now >= lastCheck
                && now - lastCheck < CHECK_THROTTLE_MS) {
            return null;
        }
        preferences.edit().putLong(KEY_LAST_CHECK_MS, now).apply();

        JSONObject release = new JSONObject(fetchLatestRelease());
        if (release.optBoolean("draft", false) || release.optBoolean("prerelease", false)) {
            throw new IllegalStateException("GitHub latest release is not stable");
        }
        String version = normalizeVersion(release.optString("tag_name", ""));
        if (!isNewerVersion(version, BuildConfig.VERSION_NAME)) return null;

        UpdateInfo available = new UpdateInfo(
                version,
                findApkAssetUrl(release.optJSONArray("assets")),
                release.optString("body", ""));
        cachedAvailable = available;
        return available;
    }

    File downloadAndVerify(Context context, UpdateInfo info, ProgressListener listener)
            throws Exception {
        requireTrustedApkDownloadUrl(info.downloadUrl);
        File downloadDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (downloadDir == null) throw new IOException("Update download directory is unavailable");
        if (!downloadDir.exists() && !downloadDir.mkdirs()) {
            throw new IOException("Cannot create update download directory");
        }

        String fileName = APK_NAME_PREFIX + info.version + ".apk";
        deleteOldApks(downloadDir, fileName);
        File downloaded = new File(downloadDir, fileName);
        if (downloaded.exists() && !downloaded.delete()) {
            throw new IOException("Cannot replace downloaded update");
        }

        DownloadManager manager = (DownloadManager) context.getSystemService(
                Context.DOWNLOAD_SERVICE);
        if (manager == null) throw new IllegalStateException("DownloadManager is unavailable");
        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(info.downloadUrl))
                .setTitle("BYD Turn Signal Camera " + info.version)
                .setDescription("Downloading update")
                .setMimeType(APK_MIME)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                .setDestinationInExternalFilesDir(
                        context, Environment.DIRECTORY_DOWNLOADS, fileName);
        long downloadId = manager.enqueue(request);
        try {
            pollDownload(manager, downloadId, listener);
            File staged = stageDownloadedApk(context, downloaded, fileName);
            validateDownloadedApk(context, staged);
            return staged;
        } catch (Exception error) {
            manager.remove(downloadId);
            throw error;
        }
    }

    void install(Context context, UpdateInfo info, File file) throws Exception {
        requireTrustedApkDownloadUrl(info.downloadUrl);
        validateDownloadedApk(context, file);
        Uri uri = FileProvider.getUriForFile(
                context, context.getPackageName() + ".fileprovider", file);
        Intent intent = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, APK_MIME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        context.startActivity(intent);
    }

    static UpdateInfo cachedAvailable() {
        return cachedAvailable;
    }

    static void clearCachedAvailable() {
        cachedAvailable = null;
    }

    static boolean isNewerVersion(String remote, String local) {
        int[] remoteParts = parseVersion(remote);
        int[] localParts = parseVersion(local);
        for (int index = 0; index < remoteParts.length; index++) {
            if (remoteParts[index] != localParts[index]) {
                return remoteParts[index] > localParts[index];
            }
        }
        return false;
    }

    static String requireTrustedReleaseApiUrl(String value) {
        URI uri = parseUri(value, "GitHub release API URL");
        if (!"https".equalsIgnoreCase(uri.getScheme())
                || !RELEASE_API_HOST.equalsIgnoreCase(uri.getHost())
                || !RELEASE_API_PATH.equals(uri.getPath())
                || uri.getPort() != -1
                || uri.getUserInfo() != null) {
            throw new IllegalArgumentException("GitHub release API URL is not allowed");
        }
        return value;
    }

    static String requireTrustedApkDownloadUrl(String value) {
        URI uri = parseUri(value, "GitHub APK URL");
        String path = uri.getPath() == null ? "" : uri.getPath();
        if (!"https".equalsIgnoreCase(uri.getScheme())
                || !APK_DOWNLOAD_HOST.equalsIgnoreCase(uri.getHost())
                || !path.startsWith(APK_PATH_MARKER)
                || !path.toLowerCase(Locale.US).endsWith(".apk")
                || uri.getPort() != -1
                || uri.getUserInfo() != null) {
            throw new IllegalArgumentException("GitHub APK URL is not allowed");
        }
        return value;
    }

    private String fetchLatestRelease() throws IOException {
        String trustedUrl = requireTrustedReleaseApiUrl(BuildConfig.UPDATE_RELEASE_API_URL);
        HttpURLConnection connection = (HttpURLConnection) new URL(trustedUrl).openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(15_000);
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        connection.setRequestProperty("User-Agent", BuildConfig.UPDATE_USER_AGENT);
        try {
            int code = connection.getResponseCode();
            if (code < 200 || code > 299) {
                throw new IOException("GitHub API HTTP " + code);
            }
            try (java.io.InputStream input = connection.getInputStream()) {
                return new String(readAllBytes(input), StandardCharsets.UTF_8);
            }
        } finally {
            connection.disconnect();
        }
    }

    private static byte[] readAllBytes(java.io.InputStream input) throws IOException {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) >= 0) output.write(buffer, 0, read);
        return output.toByteArray();
    }

    private static String findApkAssetUrl(JSONArray assets) {
        if (assets == null) throw new IllegalStateException("GitHub release has no assets");
        for (int index = 0; index < assets.length(); index++) {
            JSONObject asset = assets.optJSONObject(index);
            if (asset == null) continue;
            String name = asset.optString("name", "");
            String url = asset.optString("browser_download_url", "");
            if (name.startsWith(APK_NAME_PREFIX) && name.endsWith(".apk") && !url.isEmpty()) {
                return requireTrustedApkDownloadUrl(url);
            }
        }
        throw new IllegalStateException("GitHub release has no canonical APK asset");
    }

    private static String normalizeVersion(String value) {
        String version = value == null ? "" : value.trim();
        if (version.startsWith("v") || version.startsWith("V")) version = version.substring(1);
        parseVersion(version);
        return version;
    }

    private static int[] parseVersion(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.startsWith("v") || normalized.startsWith("V")) {
            normalized = normalized.substring(1);
        }
        String[] parts = normalized.split("\\.", -1);
        if (parts.length != 3) throw new IllegalArgumentException("Unsupported version: " + value);
        int[] result = new int[3];
        try {
            for (int index = 0; index < parts.length; index++) {
                if (parts[index].isEmpty()) throw new NumberFormatException();
                result[index] = Integer.parseInt(parts[index]);
                if (result[index] < 0) throw new NumberFormatException();
            }
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("Unsupported version: " + value, error);
        }
        return result;
    }

    private static URI parseUri(String value, String label) {
        try {
            return URI.create(value == null ? "" : value);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException(label + " is invalid", error);
        }
    }

    private static void pollDownload(
            DownloadManager manager, long downloadId, ProgressListener listener) throws Exception {
        long startedAt = System.currentTimeMillis();
        int lastProgress = -1;
        while (System.currentTimeMillis() - startedAt <= DOWNLOAD_TIMEOUT_MS) {
            try (Cursor cursor = manager.query(
                    new DownloadManager.Query().setFilterById(downloadId))) {
                if (cursor == null || !cursor.moveToFirst()) {
                    throw new IOException("Update download row is missing");
                }
                int status = cursor.getInt(cursor.getColumnIndexOrThrow(
                        DownloadManager.COLUMN_STATUS));
                if (status == DownloadManager.STATUS_SUCCESSFUL) {
                    if (listener != null) listener.onProgress(100);
                    return;
                }
                if (status == DownloadManager.STATUS_FAILED) {
                    int reason = cursor.getInt(cursor.getColumnIndexOrThrow(
                            DownloadManager.COLUMN_REASON));
                    throw new IOException("Update download failed: " + reason);
                }
                long total = cursor.getLong(cursor.getColumnIndexOrThrow(
                        DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                long received = cursor.getLong(cursor.getColumnIndexOrThrow(
                        DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                int progress = total <= 0L ? 0 : (int) Math.min(99L, received * 100L / total);
                if (progress != lastProgress && listener != null) listener.onProgress(progress);
                lastProgress = progress;
            }
            try {
                Thread.sleep(500L);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IOException("Update download interrupted", error);
            }
        }
        throw new IOException("Update download timed out");
    }

    private static File stageDownloadedApk(Context context, File source, String fileName)
            throws IOException {
        if (!source.isFile()) throw new IOException("Downloaded APK is missing");
        File directory = new File(context.getFilesDir(), "updates");
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IOException("Cannot create verified update directory");
        }
        deleteOldApks(directory, fileName);
        File target = new File(directory, fileName);
        try (FileInputStream input = new FileInputStream(source);
             FileOutputStream output = new FileOutputStream(target, false)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) output.write(buffer, 0, read);
        }
        source.delete();
        return target;
    }

    private static void deleteOldApks(File directory, String keepName) {
        File[] files = directory.listFiles((dir, name) ->
                name.startsWith(APK_NAME_PREFIX) && name.endsWith(".apk")
                        && !name.equals(keepName));
        if (files == null) return;
        for (File file : files) file.delete();
    }

    @SuppressWarnings("deprecation")
    private static void validateDownloadedApk(Context context, File file) throws Exception {
        if (!file.isFile()) throw new IOException("Downloaded APK is missing");
        PackageManager packageManager = context.getPackageManager();
        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                ? PackageManager.GET_SIGNING_CERTIFICATES
                : PackageManager.GET_SIGNATURES;
        PackageInfo archive = packageManager.getPackageArchiveInfo(file.getAbsolutePath(), flags);
        if (archive == null) throw new IOException("Downloaded file is not an APK");
        if (!context.getPackageName().equals(archive.packageName)) {
            throw new SecurityException("Downloaded APK package mismatch: " + archive.packageName);
        }
        long archiveVersion = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                ? archive.getLongVersionCode() : archive.versionCode;
        if (archiveVersion <= BuildConfig.VERSION_CODE) {
            throw new SecurityException("Downloaded APK is not newer");
        }
        PackageInfo installed = packageManager.getPackageInfo(context.getPackageName(), flags);
        Set<String> archiveSigners = signerSet(archive);
        Set<String> installedSigners = signerSet(installed);
        if (archiveSigners.isEmpty() || !archiveSigners.equals(installedSigners)) {
            throw new SecurityException("Downloaded APK signing certificate mismatch");
        }
    }

    @SuppressWarnings("deprecation")
    private static Set<String> signerSet(PackageInfo info) {
        Signature[] signatures;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            signatures = info.signingInfo == null
                    ? new Signature[0] : info.signingInfo.getApkContentsSigners();
        } else {
            signatures = info.signatures == null ? new Signature[0] : info.signatures;
        }
        Set<String> result = new HashSet<>();
        for (Signature signature : signatures) {
            result.add(Arrays.toString(signature.toByteArray()));
        }
        return result;
    }
}
