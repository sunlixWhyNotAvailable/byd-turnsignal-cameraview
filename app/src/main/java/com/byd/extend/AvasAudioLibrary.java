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
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Private AVAS source library and atomically prepared PCM cache. */
public final class AvasAudioLibrary {
    public static final String PREF_CONFIG = "avas_config_v1";
    private static final int COPY_BUFFER_BYTES = 32 * 1024;
    private static final Object CONFIG_LOCK = new Object();

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

    AvasAudioLibrary(SharedPreferences preferences, File sourceRoot, File preparedRoot) {
        if (preferences == null || sourceRoot == null || preparedRoot == null) {
            throw new IllegalArgumentException("null argument");
        }
        this.context = null;
        this.preferences = preferences;
        this.sourceRoot = sourceRoot;
        this.preparedRoot = preparedRoot;
    }

    public AvasConfig loadConfig() {
        synchronized (CONFIG_LOCK) {
            return loadConfigLocked();
        }
    }

    public void saveConfig(AvasConfig config) {
        if (config == null) throw new IllegalArgumentException("config is null");
        synchronized (CONFIG_LOCK) {
            saveConfigLocked(config);
        }
    }

    /** Establish selection before controls can publish settings; WAV preparation stays off-main. */
    public void initializeBuiltinConfig() {
        synchronized (CONFIG_LOCK) {
            boolean fresh = !preferences.contains(PREF_CONFIG);
            AvasConfig config = loadConfigLocked();
            AvasConfig merged = config;
            for (String profileId : AvasConfig.PROFILE_IDS) {
                AvasConfig.Profile profile = merged.profile(profileId);
                String builtinId = AvasBuiltinSounds.assetId(profileId);
                boolean present = false;
                for (int index = 0; index < profile.assets.size(); index++) {
                    AvasConfig.Asset asset = profile.assets.get(index);
                    if (builtinId.equals(asset.id)) {
                        present = true;
                        if (!"test".equals(asset.name)) {
                            List<AvasConfig.Asset> assets = new ArrayList<>(profile.assets);
                            assets.set(index, AvasBuiltinSounds.asset(profileId));
                            merged = merged.withProfile(profile.withAssets(
                                    assets, profile.selectedAssetId));
                        }
                        break;
                    }
                }
                if (!present) {
                    List<AvasConfig.Asset> assets = new ArrayList<>(profile.assets);
                    assets.add(0, AvasBuiltinSounds.asset(profileId));
                    String selected = fresh ? builtinId : profile.selectedAssetId;
                    merged = merged.withProfile(profile.withAssets(assets, selected));
                }
            }
            if (fresh || !merged.toJson().equals(config.toJson())) saveConfigLocked(merged);
        }
    }

    public void ensureBuiltins() {
        synchronized (CONFIG_LOCK) {
            initializeBuiltinConfig();
            for (String profileId : AvasConfig.PROFILE_IDS) ensureBuiltinFiles(profileId);
        }
    }

    public boolean removeAsset(String profileId, String assetId) {
        if (!AvasConfig.PROFILE_IDS.contains(profileId)) {
            throw new IllegalArgumentException("unknown AVAS profile");
        }
        requireAssetId(assetId);
        synchronized (CONFIG_LOCK) {
            AvasConfig config = loadConfigLocked();
            AvasConfig.Profile profile = config.profile(profileId);
            int removedIndex = -1;
            for (int index = 0; index < profile.assets.size(); index++) {
                if (assetId.equals(profile.assets.get(index).id)) {
                    removedIndex = index;
                    break;
                }
            }
            if (removedIndex < 0 || AvasBuiltinSounds.isBuiltinAsset(assetId)) return false;
            List<AvasConfig.Asset> assets = new ArrayList<>(profile.assets);
            assets.remove(removedIndex);
            String selected = profile.selectedAssetId;
            if (assetId.equals(selected)) selected = AvasBuiltinSounds.assetId(profileId);
            AvasConfig updated = config.withProfile(profile.withAssets(assets, selected));
            saveConfigLocked(updated);

            // Configuration is authoritative. Failed best-effort cleanup leaves only unreachable,
            // app-private orphan files and never removes an original provider document.
            delete(new File(new File(sourceRoot, profileId), assetId + ".source"));
            delete(preparedFile(assetId));
            return true;
        }
    }

    /** Publishes completed imports against the latest configuration without resurrecting removals. */
    public AvasConfig mergeImportedAssets(String profileId, ImportResult result) {
        if (!AvasConfig.PROFILE_IDS.contains(profileId) || result == null) {
            throw new IllegalArgumentException("invalid import merge");
        }
        synchronized (CONFIG_LOCK) {
            AvasConfig latest = loadConfigLocked();
            if (result.added.isEmpty()) return latest;
            AvasConfig.Profile profile = latest.profile(profileId);
            List<AvasConfig.Asset> assets = new ArrayList<>(profile.assets);
            Set<String> ids = new HashSet<>();
            for (AvasConfig.Asset asset : assets) ids.add(asset.id);
            AvasConfig.Asset firstAdded = null;
            for (AvasConfig.Asset asset : result.added) {
                File source = new File(new File(sourceRoot, profileId), asset.id + ".source");
                if (source.isFile() && preparedFile(asset.id).isFile() && ids.add(asset.id)) {
                    assets.add(asset);
                    if (firstAdded == null) firstAdded = asset;
                }
            }
            if (firstAdded == null) return latest;
            String selected = profile.selectedAssetId;
            if (selected.isEmpty()) selected = firstAdded.id;
            AvasConfig merged = latest.withProfile(profile.withAssets(assets, selected));
            saveConfigLocked(merged);
            return merged;
        }
    }

    /** Applies optional profile fields to the latest configuration under the publication lock. */
    public AvasConfig updateProfileSettings(String profileId, Boolean enabled, Boolean random,
            Integer volume, String selectedAssetId) {
        if (!AvasConfig.PROFILE_IDS.contains(profileId)) {
            throw new IllegalArgumentException("unknown AVAS profile");
        }
        synchronized (CONFIG_LOCK) {
            AvasConfig latest = loadConfigLocked();
            AvasConfig.Profile profile = latest.profile(profileId);
            AvasConfig.Profile updatedProfile = profile.withSettings(
                    enabled == null ? profile.enabled : enabled,
                    random == null ? profile.random : random,
                    volume == null ? profile.volume : volume,
                    selectedAssetId == null ? profile.selectedAssetId : selectedAssetId);
            AvasConfig updated = latest.withProfile(updatedProfile);
            if (!updated.toJson().equals(latest.toJson())) saveConfigLocked(updated);
            return updated;
        }
    }

    public Long durationMillis(String assetId) {
        File file = preparedFile(assetId);
        if (!file.isFile()) return null;
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            if (input.length() < 12 || input.readInt() != 0x52494646) return null; // RIFF
            readLittleEndianInt(input);
            if (input.readInt() != 0x57415645) return null; // WAVE
            long byteRate = 0;
            long dataBytes = -1;
            while (input.getFilePointer() + 8 <= input.length()) {
                int chunk = input.readInt();
                long size = Integer.toUnsignedLong(readLittleEndianInt(input));
                long end = input.getFilePointer() + size;
                if (end < input.getFilePointer() || end > input.length()) return null;
                if (chunk == 0x666d7420 && size >= 12) { // fmt[space]
                    input.skipBytes(8);
                    byteRate = Integer.toUnsignedLong(readLittleEndianInt(input));
                } else if (chunk == 0x64617461) { // data
                    dataBytes = size;
                }
                input.seek(end + (size & 1));
            }
            return byteRate > 0 && dataBytes >= 0 ? dataBytes * 1000L / byteRate : null;
        } catch (IOException | ArithmeticException ignored) {
            return null;
        }
    }

    private AvasConfig loadConfigLocked() {
        String value = preferences.getString(PREF_CONFIG, "");
        return value == null || value.isEmpty() ? AvasConfig.empty() : AvasConfig.parse(value);
    }

    private void saveConfigLocked(AvasConfig config) {
        if (!preferences.edit().putString(PREF_CONFIG, config.toJson()).commit()) {
            throw new IllegalStateException("AVAS configuration commit failed");
        }
    }

    private void ensureBuiltinFiles(String profileId) {
        File profileRoot = new File(sourceRoot, profileId);
        if ((!profileRoot.isDirectory() && !profileRoot.mkdirs())
                || (!preparedRoot.isDirectory() && !preparedRoot.mkdirs())) {
            throw new IllegalStateException("cannot create AVAS library directories");
        }
        String assetId = AvasBuiltinSounds.assetId(profileId);
        try {
            ensureBuiltinFile(profileId, new File(profileRoot, assetId + ".source"));
            ensureBuiltinFile(profileId, preparedFile(assetId));
        } catch (IOException error) {
            throw new IllegalStateException("cannot seed built-in AVAS sound", error);
        }
    }

    private static void ensureBuiltinFile(String profileId, File target) throws IOException {
        if (target.isFile()) return;
        File temp = new File(target.getParentFile(), "." + target.getName() + ".tmp");
        delete(temp);
        try {
            AvasBuiltinSounds.writeWav(profileId, temp);
            if (!temp.renameTo(target)) throw new IOException("cannot publish built-in AVAS sound");
        } finally {
            delete(temp);
        }
    }

    private static int readLittleEndianInt(RandomAccessFile input) throws IOException {
        return Integer.reverseBytes(input.readInt());
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
        if (context == null) throw new IOException("content resolver is unavailable");
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
        try {
            if (file != null && file.exists()) file.delete();
        } catch (SecurityException ignored) {}
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
            for (AvasConfig.Asset asset : added) {
                if (asset == null) throw new IllegalArgumentException("invalid result");
            }
            this.added = Collections.unmodifiableList(new ArrayList<>(added));
            this.failureCount = failureCount;
        }
    }
}
