package com.byd.extend;

import android.Manifest;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.MediaExtractor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.os.StatFs;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.MediaStore;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FilenameFilter;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public final class MotionStorage {
    public static final String RELATIVE_PATH = Environment.DIRECTORY_MOVIES + "/BYD Extend/Motion/";
    private static final String TAG = "MotionStorage";
    private static final Pattern CDR_NORMAL_NAME = Pattern.compile(
            "^(?:V5IN1H-|V5IN1-)?\\d{4}-\\d{2}-\\d{2}-\\d{2}-\\d{2}-\\d{2}\\.mp4$");
    private final Context context;
    private final MotionMediaProvider mediaProvider;
    private final MotionMediaProvider.OemRecorder oemRecorderProvider;
    private final MotionShellMediaContext shellMediaContext;
    private final String ownerPackage;
    private final String pendingOwnerPackage;
    private final StorageManager manager;
    private final Map<String, String> reportedVolumeMappings = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile MotionOemRetentionState oemRetention;
    private volatile boolean closed;

    public static final class Volume {
        public final String id;
        public final String label;
        public final boolean primary;
        public final boolean removable;
        public final boolean mounted;
        public final boolean readOnly;
        public final long totalBytes;
        public final long freeBytes;
        final String mediaStoreName;
        final File appFilesDirectory;
        final File publicRoot;

        Volume(String id, String label, boolean primary, boolean removable, boolean mounted,
                boolean readOnly, long totalBytes, long freeBytes, String mediaStoreName,
                File appFilesDirectory, File publicRoot) {
            this.id = id;
            this.label = label;
            this.primary = primary;
            this.removable = removable;
            this.mounted = mounted;
            this.readOnly = readOnly;
            this.totalBytes = totalBytes;
            this.freeBytes = freeBytes;
            this.mediaStoreName = mediaStoreName;
            this.appFilesDirectory = appFilesDirectory;
            this.publicRoot = publicRoot;
        }
    }

    public static final class Output {
        public final Uri uri;
        public final String volumeId;
        public final String displayName;
        public final ParcelFileDescriptor descriptor;
        public final File temporaryFile;
        public final Uri recoveryUri;
        public final long baseOwnBytes;
        public final long initialOwnAllowanceBytes;
        private final File destinationFile;
        private final boolean pendingMediaStore;

        Output(Uri uri, String volumeId, String displayName, ParcelFileDescriptor descriptor,
                File temporaryFile, File destinationFile, boolean pendingMediaStore,
                long baseOwnBytes, long initialOwnAllowanceBytes) {
            this.uri = uri;
            this.volumeId = volumeId;
            this.displayName = displayName;
            this.descriptor = descriptor;
            this.temporaryFile = temporaryFile;
            this.recoveryUri = temporaryFile == null ? null : Uri.fromFile(temporaryFile);
            this.destinationFile = destinationFile;
            this.pendingMediaStore = pendingMediaStore;
            this.baseOwnBytes = baseOwnBytes;
            this.initialOwnAllowanceBytes = initialOwnAllowanceBytes;
        }
    }

    public MotionStorage(Context context) {
        this.context = context.getApplicationContext();
        this.mediaProvider = new MotionMediaProvider.Resolver(this.context);
        this.oemRecorderProvider = new MotionMediaProvider.OemRecorder.Resolver(this.context);
        this.shellMediaContext = null;
        this.ownerPackage = this.context.getPackageName();
        this.pendingOwnerPackage = this.ownerPackage;
        this.manager = this.context.getSystemService(StorageManager.class);
    }

    MotionStorage(MotionShellMediaContext shellMediaContext) {
        this.shellMediaContext = shellMediaContext;
        this.context = shellMediaContext.systemContext();
        this.mediaProvider = shellMediaContext.mediaProvider();
        this.oemRecorderProvider = shellMediaContext.oemRecorderProvider();
        this.ownerPackage = MotionShellMediaContext.OWNER_PACKAGE;
        this.pendingOwnerPackage = this.context.getPackageName();
        this.manager = this.context.getSystemService(StorageManager.class);
    }

    void close() {
        closed = true;
        mediaProvider.close();
        oemRecorderProvider.close();
    }

    public List<Volume> volumes() {
        ArrayList<Volume> result = new ArrayList<>();
        try {
            List<StorageVolume> storageVolumes = manager.getStorageVolumes();
            File[] appFiles = context.getExternalFilesDirs(null);
            Set<String> mediaVolumes = Build.VERSION.SDK_INT >= 29
                    ? mediaProvider.volumeNames() : Collections.emptySet();
            for (StorageVolume volume : storageVolumes) {
                boolean primary = volume.isPrimary();
                boolean removable = volume.isRemovable();
                String state = volume.getState();
                boolean mounted = Environment.MEDIA_MOUNTED.equals(state)
                        || Environment.MEDIA_MOUNTED_READ_ONLY.equals(state);
                boolean readOnly = Environment.MEDIA_MOUNTED_READ_ONLY.equals(state);
                File appDir = findAppFilesDirectory(appFiles, volume);
                File root = volumeRoot(volume, appDir);
                String candidate = Build.VERSION.SDK_INT >= 30 ? volume.getMediaStoreVolumeName()
                        : Build.VERSION.SDK_INT == 29
                        ? MotionVolumeMapping.legacyName(primary, volume.getUuid()) : null;
                String mediaName = MotionVolumeMapping.indexedName(candidate, mediaVolumes);
                long total = -1;
                long free = -1;
                File statPath = Build.VERSION.SDK_INT >= 30 ? volume.getDirectory() : appDir;
                if (mounted && statPath != null) {
                    try {
                        StatFs stat = new StatFs(statPath.getPath());
                        total = stat.getTotalBytes();
                        free = stat.getAvailableBytes();
                    } catch (RuntimeException e) {
                        Log.w(TAG, "stat_failed volume=" + (primary ? "primary" : volume.getUuid()), e);
                    }
                }
                String id = primary ? "primary" : volume.getUuid();
                if (id == null) continue;
                if (Build.VERSION.SDK_INT >= 29) reportVolumeMapping(id, volume.getUuid(),
                        state, candidate, mediaName);
                result.add(new Volume(id, primary ? "Tablet" : (removable ? "SD" : volume.getDescription(context)),
                        primary, removable, mounted, readOnly, total, free, mediaName, appDir, root));
            }
        } catch (RuntimeException e) {
            Log.e(TAG, "volume_query_failed", e);
        }
        result.sort(Comparator.comparing((Volume volume) -> !volume.primary).thenComparing(volume -> volume.id));
        return Collections.unmodifiableList(result);
    }

    private synchronized void reportVolumeMapping(String id, String uuid, String state,
            String candidate, String mediaName) {
        String detail = "uuid=" + uuid + " state=" + state + " candidate=" + candidate
                + " media_store=" + mediaName + " reason="
                + (mediaName != null ? "indexed" : candidate == null ? "no_name" : "not_indexed");
        if (!detail.equals(reportedVolumeMappings.put(id, detail)))
            Log.i(TAG, "volume_mapping id=" + id + " " + detail);
    }

    public boolean oemMediaPermissionGranted() {
        if (Build.VERSION.SDK_INT < 23) return true;
        return shellMediaContext == null
                ? context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
                : shellMediaContext.hasShellPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                        && shellMediaContext.hasAppMotionReadPermission();
    }

    boolean hasMotionStoragePermission() {
        return oemMediaPermissionGranted()
                && (Build.VERSION.SDK_INT >= 29 || hasWritePermission());
    }

    private boolean oemCleanupPermissionsGranted() {
        if (Build.VERSION.SDK_INT < 23) return true;
        if (shellMediaContext == null) {
            return context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED
                    && context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return shellMediaContext.hasAppOemCleanupPermissions()
                && shellMediaContext.hasShellPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                && shellMediaContext.hasShellPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE);
    }

    long queryOwnedBytes(Volume volume) {
        if (!volume.mounted) return -1;
        if (Build.VERSION.SDK_INT < 29) {
            if (!oemMediaPermissionGranted()) return -1;
            return scanMotionDirectory(volume, false);
        }
        if (shellMediaContext != null && !oemMediaPermissionGranted()) return -1;
        if (volume.mediaStoreName == null) return -1;
        Uri collection = MediaStore.Video.Media.getContentUri(volume.mediaStoreName);
        String[] columns = {MediaStore.MediaColumns.SIZE};
        try (Cursor cursor = mediaProvider.query(collection, columns, ownedMediaSelection(),
                ownedMediaArgs(), null)) {
            long total = 0;
            if (cursor == null) return -1;
            int size = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE);
            while (cursor.moveToNext()) total += Math.max(0, cursor.getLong(size));
            return total;
        } catch (SecurityException e) {
            Log.w(TAG, "own_bytes_permission_denied volume=" + volume.id, e);
            return -1;
        } catch (RuntimeException e) {
            Log.w(TAG, "own_bytes_unavailable volume=" + volume.id, e);
            return -1;
        }
    }

    long queryRecorderBytes(Volume volume) {
        if (!oemMediaPermissionGranted() || !volume.mounted) return -1;
        // The OEM places .nomedia below Recorder, so MediaStore cannot account for these files.
        return scanRecorderDirectory(volume);
    }

    List<OwnedMedia> ownedMedia(Volume volume) throws IOException {
        if (!volume.mounted) return Collections.emptyList();
        if (Build.VERSION.SDK_INT >= 29) {
            if (shellMediaContext != null && !oemMediaPermissionGranted())
                throw new IOException("Motion storage read permission is not granted");
            if (volume.mediaStoreName == null) throw new IOException("Motion MediaStore volume is not indexed");
            Uri collection = MediaStore.Video.Media.getContentUri(volume.mediaStoreName);
            String[] columns = {MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.SIZE, MediaStore.Video.Media.DURATION,
                    MediaStore.Video.Media.DATE_TAKEN, MediaStore.MediaColumns.DATE_ADDED,
                    MediaStore.Video.Media.WIDTH, MediaStore.Video.Media.HEIGHT,
                    MediaStore.MediaColumns.IS_PENDING};
            ArrayList<OwnedMedia> rows = new ArrayList<>();
            try (Cursor cursor = mediaProvider.query(collection, columns, ownedMediaSelection(),
                    ownedMediaArgs(),
                    MediaStore.MediaColumns.DATE_ADDED + " DESC")) {
                if (cursor == null) throw new IOException("Motion MediaStore query returned null");
                int id = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID);
                int name = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME);
                int size = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE);
                int duration = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION);
                int taken = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_TAKEN);
                int added = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED);
                int width = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.WIDTH);
                int height = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.HEIGHT);
                int pending = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.IS_PENDING);
                while (cursor.moveToNext()) {
                    Uri uri = Uri.withAppendedPath(collection, Long.toString(cursor.getLong(id)));
                    long recordedAt = cursor.getLong(taken);
                    if (recordedAt <= 0) recordedAt = cursor.getLong(added) * 1000L;
                    rows.add(new OwnedMedia(uri, cursor.getString(name), volume.id,
                            Math.max(0, recordedAt), Math.max(0, cursor.getLong(duration)),
                            Math.max(0, cursor.getLong(size)), cursor.getInt(width), cursor.getInt(height),
                            cursor.getInt(pending) != 0));
                }
            } catch (SecurityException e) {
                throw new IOException("Motion storage permission is not granted", e);
            } catch (RuntimeException e) {
                throw new IOException("Motion MediaStore query failed", e);
            }
            return rows;
        }
        if (!oemMediaPermissionGranted()) throw new IOException("Motion storage read permission is not granted");
        File directory = queryMotionDirectory(volume.publicRoot, Environment.DIRECTORY_MOVIES);
        if (directory == null) return Collections.emptyList();
        String[] names = listChildren(directory,
                (parent, name) -> name.toLowerCase(java.util.Locale.ROOT).endsWith(".mp4"));
        ArrayList<OwnedMedia> rows = new ArrayList<>();
        for (String name : names) {
            File file = new File(directory, name);
            if (!file.isFile()) continue;
            rows.add(new OwnedMedia(Uri.fromFile(file), file.getName(), volume.id,
                    file.lastModified(), 0, file.length(), 0, 0, false));
        }
        return rows;
    }

    boolean isOwnedMedia(Volume volume, Uri uri) {
        if (volume == null || uri == null || !volume.mounted
                || (shellMediaContext != null && !oemMediaPermissionGranted())) return false;
        if (Build.VERSION.SDK_INT >= 29) {
            if (!isVolumeMediaUri(volume, uri)) return false;
            String[] columns = {MediaStore.MediaColumns.OWNER_PACKAGE_NAME,
                    MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.IS_PENDING};
            try (Cursor cursor = mediaProvider.query(uri, columns, null, null, null)) {
                return cursor != null && cursor.moveToFirst()
                        && ownerPackage.equals(cursor.getString(0))
                        && RELATIVE_PATH.equals(cursor.getString(1))
                        && cursor.getInt(2) == 0;
            } catch (RuntimeException e) {
                return false;
            }
        }
        File expected = motionDirectory(volume);
        if (expected == null || !"file".equals(uri.getScheme())) return false;
        try {
            File file = new File(uri.getPath()).getCanonicalFile();
            return expected.getCanonicalFile().equals(file.getParentFile())
                    && file.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".mp4");
        } catch (IOException e) {
            return false;
        }
    }

    /** Distinguishes a missing/unowned item from a provider that could not be checked. */
    boolean isKnownOwnedMedia(Volume volume, Uri uri) throws IOException {
        return inspectMediaStoreOwnership(volume, uri) == MediaStoreOwnership.OWNED;
    }

    enum MediaStoreOwnership { ABSENT, OWNED, OTHER }

    private String ownedMediaSelection() {
        return "(" + MediaStore.MediaColumns.OWNER_PACKAGE_NAME + "=? OR ("
                + MediaStore.MediaColumns.OWNER_PACKAGE_NAME + "=? AND "
                + MediaStore.MediaColumns.IS_PENDING + "=1)) AND "
                + MediaStore.MediaColumns.RELATIVE_PATH + "=?";
    }

    private String[] ownedMediaArgs() {
        return new String[]{ownerPackage, pendingOwnerPackage, RELATIVE_PATH};
    }

    private boolean matchesMediaOwner(String owner, int pending) {
        return ownerPackage.equals(owner) || (pending == 1 && pendingOwnerPackage.equals(owner));
    }

    MediaStoreOwnership inspectMediaStoreOwnership(Volume volume, Uri uri) throws IOException {
        if (volume == null || uri == null) return MediaStoreOwnership.OTHER;
        if (!volume.mounted) throw new IOException("Motion volume is not mounted");
        if (!oemMediaPermissionGranted()) throw new IOException("Motion storage read permission is not granted");
        if (Build.VERSION.SDK_INT < 29) {
            if (!"file".equals(uri.getScheme()) || uri.getPath() == null)
                return MediaStoreOwnership.OTHER;
            if (!isOwnedMedia(volume, uri)) return MediaStoreOwnership.OTHER;
            File file = new File(uri.getPath());
            File directory = queryMotionDirectory(volume.publicRoot, Environment.DIRECTORY_MOVIES);
            if (directory == null) return MediaStoreOwnership.ABSENT;
            String[] matches = listChildren(directory, (parent, name) -> name.equals(file.getName()));
            return matches.length == 0 ? MediaStoreOwnership.ABSENT : MediaStoreOwnership.OWNED;
        }
        if (!isVolumeMediaUri(volume, uri)) return MediaStoreOwnership.OTHER;
        String[] columns = {MediaStore.MediaColumns._ID, MediaStore.MediaColumns.OWNER_PACKAGE_NAME,
                MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.IS_PENDING};
        try (Cursor cursor = mediaProvider.query(uri, columns, null, null, null)) {
            if (cursor == null) throw new IOException("MediaStore ownership query returned null");
            if (!cursor.moveToFirst()) return MediaStoreOwnership.ABSENT;
            return matchesMediaOwner(cursor.getString(1), cursor.getInt(3))
                    && RELATIVE_PATH.equals(cursor.getString(2))
                    ? MediaStoreOwnership.OWNED : MediaStoreOwnership.OTHER;
        } catch (RuntimeException failure) {
            throw new IOException("Cannot validate Motion MediaStore record", failure);
        }
    }

    /** Strict final-record validation without enumerating the full Motion directory or store. */
    MediaStoreOwnership inspectPlayableOwnership(Volume volume, Uri uri) throws IOException {
        if (volume == null || uri == null) return MediaStoreOwnership.OTHER;
        if (!volume.mounted) throw new IOException("Motion volume is not mounted");
        if (!oemMediaPermissionGranted()) throw new IOException("Motion storage read permission is not granted");
        if (Build.VERSION.SDK_INT < 29) {
            if (!"file".equals(uri.getScheme()) || uri.getPath() == null)
                return MediaStoreOwnership.OTHER;
            if (!isOwnedMedia(volume, uri)) return MediaStoreOwnership.OTHER;
            return new File(uri.getPath()).isFile()
                    ? MediaStoreOwnership.OWNED : MediaStoreOwnership.ABSENT;
        }
        if (!isVolumeMediaUri(volume, uri)) return MediaStoreOwnership.OTHER;
        String[] columns = {MediaStore.MediaColumns.OWNER_PACKAGE_NAME,
                MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.IS_PENDING};
        try (Cursor cursor = mediaProvider.query(uri, columns, null, null, null)) {
            if (cursor == null) throw new IOException("Motion archive ownership query returned null");
            if (!cursor.moveToFirst()) return MediaStoreOwnership.ABSENT;
            return ownerPackage.equals(cursor.getString(0))
                    && RELATIVE_PATH.equals(cursor.getString(1))
                    && cursor.getInt(2) == 0
                    ? MediaStoreOwnership.OWNED : MediaStoreOwnership.OTHER;
        } catch (RuntimeException failure) {
            throw new IOException("Cannot validate finalized Motion media ownership", failure);
        }
    }

    boolean canConfirmAbsent(Volume volume, Uri uri) throws IOException {
        if (volume == null || !volume.mounted || volume.readOnly || uri == null
                || !oemMediaPermissionGranted()) return false;
        if (Build.VERSION.SDK_INT >= 29) return isVolumeMediaUri(volume, uri);
        if (!isOwnedMedia(volume, uri)) return false;
        queryMotionDirectory(volume.publicRoot, Environment.DIRECTORY_MOVIES);
        return true;
    }

    ParcelFileDescriptor openMediaFileDescriptor(Uri uri, String mode) throws IOException {
        if (uri == null) throw new IOException("Motion media URI is missing");
        if ("content".equals(uri.getScheme())) return mediaProvider.openFileDescriptor(uri, mode);
        if ("file".equals(uri.getScheme())) {
            int flags = "r".equals(mode) ? ParcelFileDescriptor.MODE_READ_ONLY
                    : ParcelFileDescriptor.MODE_READ_WRITE;
            return ParcelFileDescriptor.open(new File(uri.getPath()), flags);
        }
        throw new IOException("Unsupported Motion media URI scheme");
    }

    long mediaStoreId(Volume volume, Uri uri) {
        if (!isVolumeMediaUri(volume, uri)) return -1;
        try { return Long.parseLong(uri.getLastPathSegment()); }
        catch (NumberFormatException invalid) { return -1; }
    }

    boolean recoverInterrupted(Volume volume, Uri uri, String stagingUri) throws IOException {
        if (volume == null || uri == null || !volume.mounted) return false;
        if (Build.VERSION.SDK_INT >= 29) {
            if (!isVolumeMediaUri(volume, uri)) return false;
            String[] columns = {MediaStore.MediaColumns.OWNER_PACKAGE_NAME,
                    MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.IS_PENDING};
            try (Cursor cursor = mediaProvider.query(uri, columns, null, null, null)) {
                if (cursor == null || !cursor.moveToFirst()
                        || !matchesMediaOwner(cursor.getString(0), cursor.getInt(2))
                        || !RELATIVE_PATH.equals(cursor.getString(1))) return false;
                if (cursor.getInt(2) == 0) return true;
            } catch (RuntimeException e) {
                throw new IOException("Cannot inspect interrupted Motion media", e);
            }
            publishPending(uri);
            return true;
        }
        if (isOwnedMedia(volume, uri)) return true;
        if (!"file".equals(uri.getScheme()) || stagingUri == null) return false;
        File destination = new File(uri.getPath());
        File staging = new File(Uri.parse(stagingUri).getPath());
        if (!isOwnedDestination(volume, destination) || !isOwnedStagingFile(volume, staging)) return false;
        File parent = destination.getParentFile();
        if (parent == null || (!parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory())) return false;
        if (destination.exists() || !staging.renameTo(destination)) return false;
        android.media.MediaScannerConnection.scanFile(context,
                new String[]{destination.getAbsolutePath()}, new String[]{"video/mp4"}, null);
        return true;
    }

    boolean discardUnplayableInterrupted(Volume volume, Uri uri, String stagingUri) throws IOException {
        if (volume == null || uri == null || !volume.mounted) return false;
        if (Build.VERSION.SDK_INT >= 29) {
            if (!isVolumeMediaUri(volume, uri)) return false;
            String[] columns = {MediaStore.MediaColumns.OWNER_PACKAGE_NAME,
                    MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.IS_PENDING};
            try (Cursor cursor = mediaProvider.query(uri, columns, null, null, null)) {
                if (cursor == null) throw new IOException("Cannot inspect stale Motion media");
                if (!cursor.moveToFirst()) return true;
                if (!matchesMediaOwner(cursor.getString(0), cursor.getInt(2))
                        || !RELATIVE_PATH.equals(cursor.getString(1))
                        || cursor.getInt(2) != 1) return false;
            } catch (RuntimeException e) {
                throw new IOException("Cannot inspect stale Motion media", e);
            }
            int removed = mediaProvider.delete(uri, ownedMediaSelection() + " AND "
                    + MediaStore.MediaColumns.IS_PENDING + "=1", ownedMediaArgs());
            return removed == 1;
        }
        if (!"file".equals(uri.getScheme())) return false;
        File destination = new File(uri.getPath());
        if (!isOwnedDestination(volume, destination)) return false;
        File staging = stagingUri == null ? null : new File(Uri.parse(stagingUri).getPath());
        if (staging != null && isOwnedStagingFile(volume, staging) && staging.exists()) {
            return staging.delete();
        }
        return !destination.exists();
    }

    int deleteOwned(Volume volume, Uri uri) {
        if (!isOwnedMedia(volume, uri)) return 0;
        if (Build.VERSION.SDK_INT >= 29) {
            return mediaProvider.delete(uri, MediaStore.MediaColumns.OWNER_PACKAGE_NAME + "=? AND "
                    + MediaStore.MediaColumns.RELATIVE_PATH + "=? AND "
                    + MediaStore.MediaColumns.IS_PENDING + "=0",
                    new String[]{ownerPackage, RELATIVE_PATH});
        }
        if (Build.VERSION.SDK_INT >= 23 && !hasWritePermission())
            return 0;
        if (!oemMediaPermissionGranted()) return 0;
        try {
            return new File(uri.getPath()).delete() ? 1 : 0;
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /**
     * BYD's CDR provider indexes scanned normal clips, and its scanner excludes the exact active
     * recording filename. The provider delete API only deletes the DB row, so this method rechecks
     * that exact normal-table row and canonical Recorder/Normal file before any physical deletion.
     * It intentionally never considers Event, Picture, LOCK, or SentryMode.
     * Contract evidence is in the local BydCDR decompile under
     * `magic sentry/stock_components_20260715_104135/decompiled/BydCDR/sources`: CDRProvider.java
     * lines 72, 80-98, and 192 define the normal-table URI, row-only delete, and row schema;
     * CDRScanner.java lines 369-393 validate normal filenames and omit the exact active path before
     * adding scan results. Its insertVideoData() path stores the resulting file_path/name rows.
     */
    CleanupResult cleanOemNormalToLimit(String volumeId, long maximumArchiveBytes,
            java.util.function.BooleanSupplier cancelled, java.util.function.Predicate<File> deleteFile) {
        Volume volume = findVolume(volumeId);
        if (volume == null || !volume.mounted || volume.readOnly || volume.publicRoot == null)
            return CleanupResult.unavailable("volume_not_writable");
        if (!oemCleanupPermissionsGranted())
            return CleanupResult.unavailable("oem_storage_permission_denied");
        long currentBytes = queryRecorderBytes(volume);
        if (currentBytes < 0) return CleanupResult.unavailable("recorder_tree_unreadable");
        long initialBytes = currentBytes;
        CleanupSkips skips = new CleanupSkips();
        if (currentBytes <= maximumArchiveBytes) return CleanupResult.success(initialBytes, 0, currentBytes, skips);

        final ArrayList<CdrNormalRow> rows = new ArrayList<>();
        try (Cursor cursor = oemRecorderProvider.queryNormal(null, null)) {
            if (cursor == null) return CleanupResult.unavailable("cdr_normal_query_returned_null", currentBytes, skips);
            int pathColumn = cursor.getColumnIndex("file_path");
            int nameColumn = cursor.getColumnIndex("name");
            if (pathColumn < 0 || nameColumn < 0)
                return CleanupResult.unavailable("cdr_normal_schema_unrecognized", currentBytes, skips);
            while (cursor.moveToNext()) {
                String path = cursor.getString(pathColumn);
                if (closed || cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
                    return CleanupResult.unavailable("cleanup_cancelled", currentBytes, skips);
                String indexedName = cursor.getString(nameColumn);
                CdrNormalRow row = cdrNormalRow(volume, path);
                if (row == null || !row.file.getName().equals(indexedName)) skips.candidateRejected++;
                else rows.add(row);
            }
        } catch (SecurityException e) {
            Log.w(TAG, "cdr_normal_cleanup_permission_denied volume=" + volumeId, e);
            return CleanupResult.unavailable("cdr_provider_permission_denied", currentBytes, skips);
        } catch (RuntimeException e) {
            Log.w(TAG, "cdr_normal_cleanup_provider_unavailable volume=" + volumeId, e);
            return CleanupResult.unavailable("cdr_provider_unavailable", currentBytes, skips);
        }
        rows.sort(Comparator.comparingLong(row -> row.timestampMs));
        long deleted = 0;
        for (CdrNormalRow row : rows) {
            if (closed || cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
                return CleanupResult.failure(initialBytes, deleted, currentBytes, "cleanup_cancelled", skips);
            if (currentBytes <= maximumArchiveBytes) break;
            File file = row.file;
            if (!file.isFile()) { skips.missingFiles++; continue; }
            if (file.length() <= 0) { skips.emptyFiles++; continue; }
            if (!validVideoFile(file)) { skips.invalidVideos++; continue; }
            // Requery the same exact table/path immediately before deleting; do not trust a stale scan.
            CdrRowState beforeDelete = cdrNormalRowState(row.path, file.getName());
            if (beforeDelete != CdrRowState.PRESENT) {
                if (beforeDelete == CdrRowState.ABSENT) skips.rowAbsent++;
                else skips.rowUnknown++;
                continue;
            }
            long bytes = file.length();
            if (closed || cancelled.getAsBoolean())
                return CleanupResult.failure(initialBytes, deleted, currentBytes, "cleanup_cancelled", skips);
            if (!oemCleanupPermissionsGranted())
                return CleanupResult.failure(initialBytes, deleted, currentBytes, "oem_storage_permission_denied", skips);
            if (!deleteFile.test(file)) {
                skips.fileDeleteFailures++;
                continue;
            }
            // Physical deletion happened; account it even if the provider's row cleanup fails.
            deleted += bytes;
            currentBytes = Math.max(0, currentBytes - bytes);
            try {
                oemRecorderProvider.deleteNormal("file_path=?", new String[]{row.path});
            } catch (RuntimeException e) {
                skips.rowDeleteFailures++;
                Log.e(TAG, "cdr_normal_row_cleanup_failed after deleting " + row.path, e);
                return CleanupResult.failure(initialBytes, deleted, currentBytes, "cdr_row_cleanup_failed", skips);
            }
            CdrRowState afterDelete = cdrNormalRowState(row.path, file.getName());
            if (afterDelete != CdrRowState.ABSENT) {
                if (afterDelete == CdrRowState.PRESENT) skips.rowRemains++;
                else skips.rowVerifyUnknown++;
                Log.e(TAG, "cdr_normal_row_remains_after_file_delete path=" + row.path);
                return CleanupResult.failure(initialBytes, deleted, currentBytes, "cdr_row_cleanup_failed", skips);
            }
        }
        if (currentBytes > maximumArchiveBytes) {
            return CleanupResult.failure(initialBytes, deleted, currentBytes,
                    "no_deletable_completed_normal_files", skips);
        }
        return CleanupResult.success(initialBytes, deleted, currentBytes, skips);
    }

    static final class CleanupResult {
        final boolean available;
        final boolean complete;
        final long bytesBefore;
        final long deletedBytes;
        final long remainingBytes;
        final String reason;
        final CleanupSkips skips;

        private CleanupResult(boolean available, boolean complete, long bytesBefore, long deletedBytes,
                long remainingBytes, String reason, CleanupSkips skips) {
            this.available = available; this.complete = complete; this.deletedBytes = deletedBytes;
            this.bytesBefore = bytesBefore; this.remainingBytes = remainingBytes; this.reason = reason;
            this.skips = skips;
        }
        static CleanupResult unavailable(String reason) {
            return unavailable(reason, -1, new CleanupSkips());
        }
        static CleanupResult unavailable(String reason, long currentBytes, CleanupSkips skips) {
            return new CleanupResult(false, false, currentBytes, 0, currentBytes, reason, skips);
        }
        static CleanupResult failure(long before, long deleted, long remaining, String reason, CleanupSkips skips) {
            return new CleanupResult(true, false, before, deleted, remaining, reason, skips);
        }
        static CleanupResult success(long before, long deleted, long remaining, CleanupSkips skips) {
            return new CleanupResult(true, true, before, deleted, remaining, null, skips);
        }
        String skippedRecords() { return skips.toString(); }
    }

    private static final class CleanupSkips {
        int candidateRejected;
        int missingFiles;
        int emptyFiles;
        int invalidVideos;
        int rowAbsent;
        int rowUnknown;
        int fileDeleteFailures;
        int rowDeleteFailures;
        int rowRemains;
        int rowVerifyUnknown;

        @Override public String toString() {
            return "candidate_rejected=" + candidateRejected + ",missing_files=" + missingFiles
                    + ",empty_files=" + emptyFiles + ",invalid_videos=" + invalidVideos
                    + ",row_absent=" + rowAbsent + ",row_unknown=" + rowUnknown
                    + ",file_delete_failures=" + fileDeleteFailures
                    + ",row_delete_failures=" + rowDeleteFailures + ",row_remains=" + rowRemains
                    + ",row_verify_unknown=" + rowVerifyUnknown;
        }
    }

    static final class CdrNormalRow {
        final String path;
        final File file;
        final long timestampMs;
        CdrNormalRow(String path, File file, long timestampMs) {
            this.path = path; this.file = file; this.timestampMs = timestampMs;
        }
    }

    private CdrNormalRow cdrNormalRow(Volume volume, String path) {
        if (volume.publicRoot == null) return null;
        return cdrNormalRow(new File(volume.publicRoot, "Recorder/Normal"), path);
    }

    static CdrNormalRow cdrNormalRow(File expectedDirectory, String path) {
        if (path == null) return null;
        try {
            File expected = expectedDirectory.getCanonicalFile();
            File file = new File(path).getCanonicalFile();
            if (!expected.equals(file.getParentFile())) return null;
            String name = file.getName();
            if (!CDR_NORMAL_NAME.matcher(name).matches()) return null;
            String timestamp = name;
            if (timestamp.startsWith("V5IN1H-")) timestamp = timestamp.substring("V5IN1H-".length());
            else if (timestamp.startsWith("V5IN1-")) timestamp = timestamp.substring("V5IN1-".length());
            timestamp = timestamp.substring(0, timestamp.length() - ".mp4".length());
            SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.US);
            format.setLenient(false);
            ParsePosition position = new ParsePosition(0);
            Date date = format.parse(timestamp, position);
            if (date == null || position.getIndex() != timestamp.length()) return null;
            return new CdrNormalRow(path, file, date.getTime());
        } catch (IOException | RuntimeException invalid) {
            return null;
        }
    }

    private enum CdrRowState { PRESENT, ABSENT, UNKNOWN }

    private CdrRowState cdrNormalRowState(String path, String expectedName) {
        try (Cursor cursor = oemRecorderProvider.queryNormal("file_path=?", new String[]{path})) {
            if (cursor == null) return CdrRowState.UNKNOWN;
            boolean foundRow = false;
            while (cursor.moveToNext()) {
                foundRow = true;
                if (path.equals(cursor.getString(0)) && expectedName.equals(cursor.getString(1)))
                    return CdrRowState.PRESENT;
            }
            return foundRow ? CdrRowState.UNKNOWN : CdrRowState.ABSENT;
        } catch (RuntimeException unavailable) {
            Log.w(TAG, "cdr_normal_row_verification_failed path=" + path, unavailable);
            return CdrRowState.UNKNOWN;
        }
    }

    private boolean validVideoFile(File file) {
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(file.getAbsolutePath());
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                String mime = extractor.getTrackFormat(i).getString(android.media.MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) return true;
            }
            return false;
        } catch (IOException | RuntimeException invalid) {
            Log.w(TAG, "cdr_normal_video_validation_failed path=" + file, invalid);
            return false;
        } finally {
            try { extractor.release(); }
            catch (RuntimeException cleanupFailure) {
                Log.w(TAG, "cdr_normal_extractor_release_failed path=" + file, cleanupFailure);
            }
        }
    }

    Uri uriForOutput(String mediaStoreName, long id) {
        return Uri.withAppendedPath(MediaStore.Video.Media.getContentUri(mediaStoreName), Long.toString(id));
    }

    Volume findVolume(String id) {
        return findVolume(volumes(), id);
    }

    String oppositeVolumeId(String id) {
        Volume failed = findVolume(id);
        if (failed == null) return null;
        for (Volume volume : volumes()) {
            if (volume.mounted && !volume.readOnly && volume.primary != failed.primary) return volume.id;
        }
        return null;
    }

    private static Volume findVolume(List<Volume> volumes, String id) {
        if (id == null) return null;
        for (Volume volume : volumes) if (volume.id.equals(id)) return volume;
        return null;
    }

    static final class OwnedMedia {
        final Uri uri;
        final String name;
        final String volumeId;
        final long recordedAtMs;
        final long durationMs;
        final long sizeBytes;
        final int width;
        final int height;
        final boolean pending;

        OwnedMedia(Uri uri, String name, String volumeId, long recordedAtMs, long durationMs,
                long sizeBytes, int width, int height, boolean pending) {
            this.uri = uri; this.name = name; this.volumeId = volumeId;
            this.recordedAtMs = recordedAtMs; this.durationMs = durationMs; this.sizeBytes = sizeBytes;
            this.width = width; this.height = height; this.pending = pending;
        }
    }

    Output createOutput(String preferredVolumeId, boolean fallbackEnabled, String displayName) throws IOException {
        return createOutput(preferredVolumeId, fallbackEnabled, displayName, null);
    }

    Output createOutput(String preferredVolumeId, boolean fallbackEnabled, String displayName,
            MotionConfig config) throws IOException {
        return createOutput(preferredVolumeId, fallbackEnabled, displayName, config, null, 0);
    }

    Output createOutput(String preferredVolumeId, boolean fallbackEnabled, String displayName,
            MotionConfig config, MotionMediaLibrary library, long requiredOwnGrowthBytes) throws IOException {
        return createOutput(preferredVolumeId, fallbackEnabled, displayName, config, library,
                requiredOwnGrowthBytes, null);
    }

    Output createOutput(String preferredVolumeId, boolean fallbackEnabled, String displayName,
            MotionConfig config, MotionMediaLibrary library, long requiredOwnGrowthBytes,
            String firstAttemptVolumeId) throws IOException {
        if (!hasMotionStoragePermission())
            throw new IOException("motion_storage_permission_denied");
        List<Volume> available = volumes();
        Volume preferred = findVolume(available, preferredVolumeId);
        ArrayList<Volume> attempts = new ArrayList<>();
        Volume firstAttempt = findVolume(available, firstAttemptVolumeId);
        if (fallbackEnabled && firstAttempt != null && firstAttempt != preferred
                && firstAttempt.mounted && !firstAttempt.readOnly) {
            attempts.add(firstAttempt);
        }
        if (preferred != null && preferred.mounted && !preferred.readOnly) attempts.add(preferred);
        if (fallbackEnabled) {
            for (Volume volume : available) {
                boolean opposite = preferred != null ? volume.primary != preferred.primary
                        : ("primary".equals(preferredVolumeId) ? volume.removable : volume.primary);
                if (volume != preferred && volume != firstAttempt && opposite && volume.mounted && !volume.readOnly) {
                    attempts.add(volume);
                }
            }
        }
        if (attempts.isEmpty()) throw new IOException("Selected Motion volume is unavailable or read-only");
        IOException failure = null;
        for (Volume volume : attempts) {
            try {
                if (volume.totalBytes <= 0 || volume.freeBytes < 0)
                    throw new IOException("motion_free_space_unavailable");
                if (queryOwnedBytes(volume) < 0)
                    throw new IOException("motion_archive_accounting_unavailable");
                long baseOwnBytes = -1;
                long ownAllowance = Long.MAX_VALUE;
                if (library != null) {
                    MotionMediaLibrary.StorageDecision own = library.prepareOwnSpace(
                            volume.id, config, requiredOwnGrowthBytes);
                    if (!own.allowed) throw new IOException(own.reason);
                    baseOwnBytes = own.ownBytes;
                    ownAllowance = own.allowanceBytes;
                }
                return createOutput(volume, displayName, baseOwnBytes, ownAllowance);
            } catch (IOException e) {
                Log.w(TAG, "segment_volume_failed volume=" + volume.id + " reason=" + e.getMessage());
                failure = e;
            }
        }
        throw failure == null ? new IOException("No writable Motion volume") : failure;
    }

    void setOemRetention(MotionOemRetentionState state) { oemRetention = state; }

    Long oemLimitBytes(MotionConfig config, Volume volume) {
        if (volume == null || config == null) return null;
        MotionOemRetentionState current = oemRetention;
        if (current == null) current = MotionOemRetentionState.from(config);
        if (!current.active()) return null;
        // A fallback volume is not implicitly authorized for deletion or OEM quota reservation.
        Volume selected = null;
        for (Volume candidate : volumes()) {
            if (current.primarySd ? candidate.removable && candidate.mounted : candidate.primary) {
                selected = candidate;
                break;
            }
        }
        return selected != null && selected.id.equals(volume.id) ? current.limitBytes(volume.totalBytes) : null;
    }

    void publish(Output output) throws IOException {
        if (output.pendingMediaStore) {
            publishPending(output.uri);
            return;
        }
        if (output.temporaryFile == null || output.destinationFile == null || !output.temporaryFile.isFile()) {
            throw new IOException("Legacy Motion video is missing");
        }
        File parent = output.destinationFile.getParentFile();
        if (parent == null || (!parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory())) {
            throw new IOException("Cannot create Motion media directory");
        }
        try (RandomAccessFile staged = new RandomAccessFile(output.temporaryFile, "rw")) {
            staged.getFD().sync();
        }
        if (output.destinationFile.exists() || !output.temporaryFile.renameTo(output.destinationFile)) {
            throw new IOException("Cannot atomically publish finished Motion video");
        }
        android.media.MediaScannerConnection.scanFile(context,
                new String[]{output.destinationFile.getAbsolutePath()}, new String[]{"video/mp4"}, null);
    }

    long outputSizeBytes(Output output) {
        if (output == null) return -1;
        if (output.temporaryFile != null) return output.temporaryFile.isFile() ? output.temporaryFile.length() : 0;
        if (output.descriptor != null) {
            try { return output.descriptor.getStatSize(); }
            catch (RuntimeException ignored) { }
        }
        return -1;
    }

    void deleteTemporaryOutput(Output output) throws IOException {
        if (output.pendingMediaStore) {
            int deleted = mediaProvider.delete(output.uri, ownedMediaSelection(), ownedMediaArgs());
            if (deleted != 1) throw new IOException("Cannot delete temporary Motion clip: " + output.uri);
        } else {
            for (File file : new File[]{output.temporaryFile, output.destinationFile}) {
                if (file != null && file.exists() && !file.delete())
                    throw new IOException("Cannot delete temporary Motion clip: " + file.getName());
            }
        }
    }

    void discard(Output output) {
        if (output == null) return;
        if (output.pendingMediaStore) {
            try {
                mediaProvider.delete(output.uri, ownedMediaSelection() + " AND "
                        + MediaStore.MediaColumns.IS_PENDING + "=1", ownedMediaArgs());
            }
            catch (RuntimeException e) { Log.w(TAG, "pending_delete_failed uri=" + output.uri, e); }
        } else {
            if (output.temporaryFile != null && output.temporaryFile.exists() && !output.temporaryFile.delete()) {
                Log.w(TAG, "temporary_delete_failed " + output.temporaryFile.getName());
            }
        }
    }

    Uri collectionUri(Volume volume) {
        if (volume.mediaStoreName == null) return MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
        return MediaStore.Video.Media.getContentUri(volume.mediaStoreName);
    }

    private void publishPending(Uri uri) throws IOException {
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.IS_PENDING, 0);
        // Keep the writer as owner while pending; transfer only with the completed publication.
        if (shellMediaContext != null) values.put(MediaStore.MediaColumns.OWNER_PACKAGE_NAME, ownerPackage);
        if (mediaProvider.update(uri, values, ownedMediaSelection() + " AND "
                + MediaStore.MediaColumns.IS_PENDING + "=1", ownedMediaArgs()) != 1) {
            throw new IOException("Cannot publish finished Motion video");
        }
    }

    private boolean isVolumeMediaUri(Volume volume, Uri uri) {
        if (volume == null || volume.mediaStoreName == null || uri == null
                || !"content".equals(uri.getScheme()) || !"media".equals(uri.getAuthority())) return false;
        List<String> expected = MediaStore.Video.Media.getContentUri(volume.mediaStoreName).getPathSegments();
        List<String> actual = uri.getPathSegments();
        if (actual.size() != expected.size() + 1) return false;
        for (int i = 0; i < expected.size(); i++) {
            if (!expected.get(i).equals(actual.get(i))) return false;
        }
        try { return Long.parseLong(actual.get(actual.size() - 1)) > 0; }
        catch (NumberFormatException invalidId) { return false; }
    }

    boolean isMediaStoreUriForVolume(Volume volume, Uri uri) { return isVolumeMediaUri(volume, uri); }

    private Output createOutput(Volume volume, String displayName, long baseOwnBytes,
            long initialOwnAllowanceBytes) throws IOException {
        if (!volume.mounted || volume.readOnly) throw new IOException("Volume is not writable");
        if (Build.VERSION.SDK_INT >= 29) {
            if (volume.mediaStoreName == null) throw new IOException("MediaStore volume is not available");
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, displayName);
            values.put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4");
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_PATH);
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);
            if (shellMediaContext != null)
                values.put(MediaStore.MediaColumns.OWNER_PACKAGE_NAME, pendingOwnerPackage);
            Uri uri = mediaProvider.insert(collectionUri(volume), values);
            if (uri == null) throw new IOException("MediaStore insert returned no item");
            ParcelFileDescriptor descriptor = null;
            try {
                descriptor = mediaProvider.openFileDescriptor(uri, "rw");
                if (descriptor == null) throw new IOException("MediaStore item cannot be opened");
                return new Output(uri, volume.id, displayName, descriptor, null, null, true,
                        baseOwnBytes, initialOwnAllowanceBytes);
            } catch (IOException | RuntimeException failure) {
                if (descriptor != null) {
                    try { descriptor.close(); }
                    catch (IOException cleanup) { failure.addSuppressed(cleanup); }
                }
                try {
                    if (mediaProvider.delete(uri, ownedMediaSelection() + " AND "
                            + MediaStore.MediaColumns.IS_PENDING + "=1", ownedMediaArgs()) != 1)
                        throw new IOException("Cannot delete unopened Motion clip: " + uri);
                } catch (IOException | RuntimeException cleanup) {
                    failure.addSuppressed(cleanup);
                }
                throw failure;
            }
        }
        if (!hasWritePermission()) {
            throw new IOException("Legacy external-storage permission is not granted");
        }
        if (volume.appFilesDirectory == null || volume.publicRoot == null) {
            throw new IOException("Legacy volume path is unavailable");
        }
        File destination = new File(new File(volume.publicRoot, Environment.DIRECTORY_MOVIES + "/BYD Extend/Motion"), displayName);
        File temporaryDirectory = new File(destination.getParentFile(), ".pending");
        if (!temporaryDirectory.isDirectory() && !temporaryDirectory.mkdirs() && !temporaryDirectory.isDirectory()) {
            throw new IOException("Cannot create hidden Motion staging directory");
        }
        File temporary = File.createTempFile(".mo", ".tmp", temporaryDirectory);
        return new Output(Uri.fromFile(destination), volume.id, displayName, null, temporary, destination, false,
                baseOwnBytes, initialOwnAllowanceBytes);
    }

    private boolean hasWritePermission() {
        if (shellMediaContext == null)
            return context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED;
        return shellMediaContext.hasShellPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                && shellMediaContext.hasAppMotionWritePermission();
    }

    private File findAppFilesDirectory(File[] directories, StorageVolume volume) {
        if (directories == null) return null;
        for (File directory : directories) {
            if (directory == null) continue;
            try {
                StorageVolume actual = manager.getStorageVolume(directory);
                if (sameVolume(actual, volume)) return directory;
            } catch (RuntimeException ignored) { }
        }
        return null;
    }

    private File volumeRoot(StorageVolume volume, File appFilesDirectory) {
        if (Build.VERSION.SDK_INT >= 30) return volume.getDirectory();
        File root = appFilesDirectory;
        for (int i = 0; i < 4 && root != null; i++) root = root.getParentFile();
        if (root == null) return null;
        try { return sameVolume(manager.getStorageVolume(root), volume) ? root : null; }
        catch (RuntimeException ignored) { return null; }
    }

    private long scanRecorderDirectory(Volume volume) {
        File recorder = volume.publicRoot == null ? null : new File(volume.publicRoot, "Recorder");
        return scanTree(recorder, volume.publicRoot, true);
    }

    private long scanMotionDirectory(Volume volume, boolean includePending) {
        File directory = motionDirectory(volume);
        if (directory == null || !directory.isDirectory()) return 0;
        File[] files = directory.listFiles();
        if (files == null) return -1;
        long total = 0;
        for (File file : files) {
            if (file.isDirectory()) {
                if (includePending && ".pending".equals(file.getName())) {
                    long nested = scanTree(file, volume.publicRoot, false);
                    if (nested < 0) return -1;
                    total += nested;
                }
            } else if (file.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".mp4")) {
                total += Math.max(0, file.length());
            }
        }
        return total;
    }

    private File motionDirectory(Volume volume) {
        return volume.publicRoot == null ? null
                : new File(volume.publicRoot, Environment.DIRECTORY_MOVIES + "/BYD Extend/Motion");
    }

    static File queryMotionDirectory(File publicRoot, String moviesDirectoryName) throws IOException {
        if (moviesDirectoryName == null || moviesDirectoryName.isEmpty())
            throw new IOException("Motion media directory is unavailable");
        File directory = publicRoot;
        for (String child : new String[]{moviesDirectoryName, "BYD Extend", "Motion"}) {
            String[] matches = listChildren(directory, (parent, name) -> name.equals(child));
            if (matches.length == 0) return null;
            directory = new File(directory, child);
        }
        if (!directory.isDirectory()) throw new IOException("Cannot query Motion media directory");
        return directory;
    }

    private static String[] listChildren(File directory, FilenameFilter filter) throws IOException {
        if (directory == null || !directory.isDirectory())
            throw new IOException("Cannot query Motion media directory");
        String[] children = directory.list(filter);
        if (children == null) throw new IOException("Cannot query Motion media directory");
        return children;
    }

    private boolean isOwnedDestination(Volume volume, File file) {
        File directory = motionDirectory(volume);
        if (directory == null) return false;
        try {
            File canonicalDirectory = directory.getCanonicalFile();
            File canonicalFile = file.getCanonicalFile();
            return canonicalDirectory.equals(canonicalFile.getParentFile())
                    && canonicalFile.getName().toLowerCase(Locale.ROOT).endsWith(".mp4");
        } catch (IOException e) { return false; }
    }

    private boolean isOwnedStagingFile(Volume volume, File file) {
        File directory = motionDirectory(volume);
        if (directory == null) return false;
        try {
            File stagingDirectory = new File(directory, ".pending").getCanonicalFile();
            File canonicalFile = file.getCanonicalFile();
            return stagingDirectory.equals(canonicalFile.getParentFile())
                    && canonicalFile.getName().startsWith(".mo")
                    && canonicalFile.getName().endsWith(".tmp");
        } catch (IOException e) { return false; }
    }

    private long scanTree(File directory, File allowedRoot, boolean includeHidden) {
        if (directory == null) return -1;
        if (!directory.exists()) return 0;
        if (!directory.canRead()) return -1;
        final File canonicalRoot;
        try {
            if (allowedRoot == null) return -1;
            canonicalRoot = allowedRoot.getCanonicalFile();
        } catch (IOException e) { return -1; }
        File canonical;
        try { canonical = directory.getCanonicalFile(); }
        catch (IOException e) { return -1; }
        if (!canonical.equals(canonicalRoot) && !canonical.toPath().startsWith(canonicalRoot.toPath())) return -1;
        File[] children = directory.listFiles();
        if (children == null) return -1;
        long total = 0;
        for (File child : children) {
            if (closed || Thread.currentThread().isInterrupted()) return -1;
            if (!includeHidden && child.getName().startsWith(".")) continue;
            if (child.isDirectory()) {
                long nested = scanTree(child, allowedRoot, includeHidden);
                if (nested < 0) return -1;
                total += nested;
            } else if (child.isFile()) {
                total += Math.max(0, child.length());
            }
        }
        return total;
    }

    private static boolean sameVolume(StorageVolume left, StorageVolume right) {
        if (left == null || right == null) return false;
        if (left.isPrimary() || right.isPrimary()) return left.isPrimary() && right.isPrimary();
        return left.getUuid() != null && left.getUuid().equals(right.getUuid());
    }
}
