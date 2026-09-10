package com.byd.extend;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Private AVAS source library and atomically prepared PCM cache. */
public final class AvasAudioLibrary {
    public static final String PREF_CONFIG = "avas_config_v1";
    private static final int COPY_BUFFER_BYTES = 32 * 1024;

    private final Context context;
    private final SharedPreferences preferences;
    private final File sourceRoot;
    private final File preparedRoot;

    public AvasAudioLibrary(Context context, SharedPreferences preferences) {
        if (context == null || preferences == null) throw new IllegalArgumentException("null argument");
        this.context = context.getApplicationContext();
        this.preferences = preferences;
        sourceRoot = new File(this.context.getFilesDir(), "avas");
        preparedRoot = new File(this.context.getFilesDir(), "avas-prepared");
    }

    public AvasConfig loadConfig() {
        String value = preferences.getString(PREF_CONFIG, "");
        return value == null || value.isEmpty() ? AvasConfig.empty() : AvasConfig.parse(value);
    }

    public void saveConfig(AvasConfig config) {
        if (config == null) throw new IllegalArgumentException("config is null");
        if (!preferences.edit().putString(PREF_CONFIG, config.toJson()).commit()) {
            throw new IllegalStateException("AVAS configuration commit failed");
        }
    }

    public File preparedFile(String assetId) {
        requireAssetId(assetId);
        return new File(preparedRoot, assetId + ".wav");
    }

    public ParcelFileDescriptor openPrepared(String assetId) throws IOException {
        File file = preparedFile(assetId);
        if (!file.isFile()) throw new IOException("prepared AVAS asset is unavailable");
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    public ImportResult importFiles(String profileId, List<Uri> uris) {
        if (!AvasConfig.PROFILE_IDS.contains(profileId) || uris == null) {
            throw new IllegalArgumentException("invalid import request");
        }
        File profileRoot = new File(sourceRoot, profileId);
        if ((!profileRoot.isDirectory() && !profileRoot.mkdirs())
                || (!preparedRoot.isDirectory() && !preparedRoot.mkdirs())) {
            return new ImportResult(Collections.emptyList(), uris.size());
        }
        List<AvasConfig.Asset> added = new ArrayList<>();
        Set<String> names = existingDisplayNames(profileId);
        for (Uri uri : uris) {
            File sourceTemp = null;
            File preparedTemp = null;
            File sourceFinal = null;
            File preparedFinal = null;
            try {
                if (uri == null) throw new IOException("missing source URI");
                String displayName = uniqueName(displayName(uri), names);
                AvasConfig.Asset asset = uniqueAsset(displayName, profileRoot);
                sourceTemp = new File(profileRoot, "." + asset.id + ".source.tmp");
                preparedTemp = new File(preparedRoot, "." + asset.id + ".wav.tmp");
                copy(uri, sourceTemp);
                AvasPcmDecoder.decodeToPcm16Wav(sourceTemp, preparedTemp);
                sourceFinal = new File(profileRoot, asset.id + ".source");
                preparedFinal = preparedFile(asset.id);
                moveReady(sourceTemp, sourceFinal);
                if (!preparedTemp.renameTo(preparedFinal)) {
                    sourceFinal.delete();
                    throw new IOException("cannot publish prepared AVAS file");
                }
                names.add(displayName);
                added.add(asset);
            } catch (Exception error) {
                delete(sourceTemp);
                delete(preparedTemp);
                if (preparedFinal != null && !addedContains(added, preparedFinal)) delete(preparedFinal);
                if (error instanceof InterruptedIOException
                        || Thread.currentThread().isInterrupted()) break;
            }
        }
        return new ImportResult(added, uris.size() - added.size());
    }

    private void copy(Uri uri, File output) throws IOException {
        try (InputStream input = context.getContentResolver().openInputStream(uri);
                FileOutputStream target = new FileOutputStream(output)) {
            if (input == null) throw new IOException("cannot open audio source");
            byte[] buffer = new byte[COPY_BUFFER_BYTES];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedIOException("AVAS import cancelled");
                }
                if (count > 0) target.write(buffer, 0, count);
            }
            target.getFD().sync();
        }
    }

    private String displayName(Uri uri) {
        try (Cursor cursor = context.getContentResolver().query(
                uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                String value = cursor.getString(0);
                if (validDisplayName(value)) return value;
            }
        } catch (RuntimeException ignored) {}
        String value = uri.getLastPathSegment();
        return validDisplayName(value) ? value : "Audio";
    }

    private static boolean validDisplayName(String value) {
        if (value == null || value.trim().isEmpty()) return false;
        for (int index = 0; index < value.length(); index++) {
            if (Character.isISOControl(value.charAt(index))) return false;
        }
        return true;
    }

    static String uniqueName(String requested, Set<String> names) {
        String trimmed = requested.trim();
        if (trimmed.length() > 256) trimmed = trimmed.substring(0, 256);
        if (!names.contains(trimmed)) return trimmed;
        int dot = trimmed.lastIndexOf('.');
        String stem = dot > 0 ? trimmed.substring(0, dot) : trimmed;
        String extension = dot > 0 ? trimmed.substring(dot) : "";
        for (int suffix = 2; ; suffix++) {
            String marker = " (" + suffix + ")";
            String boundedExtension = extension.substring(0,
                    Math.min(extension.length(), 256 - marker.length() - 1));
            int maxStem = 256 - boundedExtension.length() - marker.length();
            String candidate = stem.substring(0, Math.min(stem.length(), maxStem))
                    + marker + boundedExtension;
            if (!names.contains(candidate)) return candidate;
        }
    }

    private Set<String> existingDisplayNames(String profileId) {
        Set<String> names = new HashSet<>();
        try {
            for (AvasConfig.Asset asset : loadConfig().profile(profileId).assets) {
                names.add(asset.name);
            }
        } catch (IllegalArgumentException ignored) {
            // A bad stored configuration remains the UI caller's error to report; imports are isolated.
        }
        return names;
    }

    private AvasConfig.Asset uniqueAsset(String displayName, File profileRoot) {
        while (true) {
            AvasConfig.Asset asset = AvasConfig.Asset.create(displayName);
            if (!new File(profileRoot, asset.id + ".source").exists()
                    && !preparedFile(asset.id).exists()) return asset;
        }
    }

    private static void moveReady(File source, File destination) throws IOException {
        if (!source.renameTo(destination)) throw new IOException("cannot publish AVAS source");
    }

    private static boolean addedContains(List<AvasConfig.Asset> added, File file) {
        String name = file.getName();
        for (AvasConfig.Asset asset : added) if ((asset.id + ".wav").equals(name)) return true;
        return false;
    }

    private static void delete(File file) {
        if (file != null && file.exists()) file.delete();
    }

    private static void requireAssetId(String assetId) {
        if (assetId == null || !assetId.matches("[0-9a-f]{32}")) {
            throw new IllegalArgumentException("invalid asset id");
        }
    }

    public static final class ImportResult {
        public final List<AvasConfig.Asset> added;
        public final int failureCount;

        public ImportResult(List<AvasConfig.Asset> added, int failureCount) {
            if (added == null || failureCount < 0) throw new IllegalArgumentException("invalid result");
            this.added = Collections.unmodifiableList(new ArrayList<>(added));
            this.failureCount = failureCount;
        }
    }
}
