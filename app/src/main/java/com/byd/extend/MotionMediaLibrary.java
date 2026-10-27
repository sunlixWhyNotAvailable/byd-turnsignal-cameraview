package com.byd.extend;

import android.content.Context;
import android.database.Cursor;
import android.media.MediaExtractor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Persistent archive index and real MediaStore-backed archive/storage queries. */
public final class MotionMediaLibrary {
    private final Context context;
    private final MotionStorage storage;
    private final MotionMediaIndex index;
    private final File leaseDirectory;
    private final Access access;
    private final MotionThumbnailStore thumbnailStore;
    private final MotionShellMediaContext ownedShellMediaContext;

    /** Narrow app-side facade for the worker-owned index; implementations must not expose paths. */
    public interface Access {
        int refresh();
        String query(Query query, MotionConfig config, String pageToken);
        String read(String volumeId, long mediaStoreId);
        boolean delete(String volumeId, long mediaStoreId);
        boolean isWorkerAvailable();
        ParcelFileDescriptor openThumbnail(String volumeId, long mediaStoreId, String expectedKey);
        boolean importLegacyIndex(ParcelFileDescriptor source) throws IOException;
    }

    public MotionMediaLibrary(Context context) {
        this.context = context.getApplicationContext();
        this.storage = new MotionStorage(this.context);
        this.leaseDirectory = new File(this.context.getFilesDir(), "motion/leases");
        this.access = null;
        this.thumbnailStore = null;
        this.ownedShellMediaContext = null;
        try {
            this.index = MotionMediaIndex.shared(new File(this.context.getFilesDir(), "motion/media-index.json"));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot open Motion media index", e);
        }
    }

    MotionMediaLibrary(MotionShellMediaContext shellContext) {
        this(shellContext, false);
    }

    private MotionMediaLibrary(MotionShellMediaContext shellContext, boolean ownsShellContext) {
        this.context = shellContext.systemContext();
        this.storage = new MotionStorage(shellContext);
        this.leaseDirectory = new File(shellContext.privateDirectory(), "leases");
        this.access = null;
        this.ownedShellMediaContext = ownsShellContext ? shellContext : null;
        MotionMediaIndex openedIndex;
        try {
            openedIndex = MotionMediaIndex.shared(new File(shellContext.privateDirectory(), "media-index.json"));
        } catch (IOException e) {
            storage.close();
            if (ownsShellContext) shellContext.close();
            throw new IllegalStateException("Cannot open shell Motion media index", e);
        }
        this.index = openedIndex;
        this.thumbnailStore = new MotionThumbnailStore(storage, index, leaseDirectory,
                shellContext.privateDirectory());
    }

    /** Creates the existing UI-facing API over the authenticated worker Binder client. */
    public MotionMediaLibrary(Context context, Access access) {
        if (access == null) throw new IllegalArgumentException("Motion media access is required");
        this.context = context.getApplicationContext();
        this.storage = null;
        this.index = null;
        this.leaseDirectory = null;
        this.thumbnailStore = null;
        this.access = access;
        this.ownedShellMediaContext = null;
    }

    /** Opens a read-only shell-UID view of the Motion helper's shared index and thumbnail cache. */
    public static MotionMediaLibrary openShellArchive(Context shellRuntimeContext, int ownerUid)
            throws Exception {
        MotionShellMediaContext shellContext = new MotionShellMediaContext(shellRuntimeContext,
                ownerUid, MotionShellMediaContext.OWNER_PACKAGE, MotionShellMediaContext.WORKER_ROOT);
        try {
            return new MotionMediaLibrary(shellContext, true);
        } catch (RuntimeException failure) {
            shellContext.close();
            throw failure;
        }
    }

    public boolean importLegacyIndex(ParcelFileDescriptor source) throws IOException {
        if (access == null) throw new IllegalStateException("Legacy index import is worker-only");
        return access.importLegacyIndex(source);
    }

    /** Read-only lifecycle guard; unlike media operations this never starts or replaces a worker. */
    public boolean isWorkerAvailable() {
        return access == null || access.isWorkerAvailable();
    }

    /** Opens only a worker-prepared private cover via an owner-validated read-only descriptor. */
    public ParcelFileDescriptor openThumbnail(Entry entry, String expectedKey) {
        if (access == null || entry == null || entry.mediaStoreId <= 0
                || !MotionThumbnailKey.isValid(expectedKey)) return null;
        return access.openThumbnail(entry.volumeId, entry.mediaStoreId, expectedKey);
    }

    /** Returns only finalized, currently owned index rows for the remote archive. */
    public List<ArchiveItem> queryArchiveIndex() throws IOException {
        requireShellArchive();
        index.reloadIfChanged();
        Map<String, MotionStorage.Volume> volumes = new HashMap<>();
        for (MotionStorage.Volume volume : storage.volumes()) volumes.put(volume.id, volume);
        ArrayList<ArchiveItem> result = new ArrayList<>();
        for (MotionMediaIndex.Record record : index.records()) {
            if (!isFinalizedArchiveRecord(record)) continue;
            MotionStorage.Volume volume = volumes.get(record.volumeId);
            if (volume == null || !volume.mounted) continue;
            Uri uri = Uri.parse(record.uri);
            if (storage.inspectPlayableOwnership(volume, uri) != MotionStorage.MediaStoreOwnership.OWNED)
                continue;
            long mediaStoreId = storage.mediaStoreId(volume, uri);
            Entry entry = archiveEntry(record, uri, mediaStoreId);
            result.add(new ArchiveItem(archiveId(record), entry, volume.removable ? "sd" : "tablet"));
        }
        Comparator<ArchiveItem> order = Comparator.comparingLong(item -> item.entry.recordedAtMillis);
        result.sort(order.reversed().thenComparing(item -> item.id));
        return Collections.unmodifiableList(result);
    }

    /** Opens only an existing worker-cached cover for a currently indexed, owned record. */
    public ParcelFileDescriptor openArchiveThumbnail(String id) throws IOException {
        requireShellArchive();
        MotionMediaIndex.Record record = resolveArchiveRecord(id);
        if (record == null) return null;
        MotionStorage.Volume volume = storage.findVolume(record.volumeId);
        if (volume == null || !volume.mounted) return null;
        Uri uri = Uri.parse(record.uri);
        if (storage.inspectPlayableOwnership(volume, uri) != MotionStorage.MediaStoreOwnership.OWNED)
            return null;
        long mediaStoreId = storage.mediaStoreId(volume, uri);
        if (mediaStoreId <= 0) return null;
        return thumbnailStore.open(volume.id, mediaStoreId,
                MotionThumbnailKey.forRecord(record, mediaStoreId), record);
    }

    /** Acquires the cross-process cleanup lease for one validated recording. */
    public ArchivePlayback openArchiveVideo(String id) throws IOException {
        requireShellArchive();
        MotionMediaIndex.Record initial = resolveArchiveRecord(id);
        if (initial == null) throw new java.io.FileNotFoundException("Motion archive item is unavailable");
        MotionStorage.Volume volume = storage.findVolume(initial.volumeId);
        if (volume == null || !volume.mounted)
            throw new java.io.FileNotFoundException("Motion archive item is unavailable");
        if (storage.inspectPlayableOwnership(volume, Uri.parse(initial.uri))
                != MotionStorage.MediaStoreOwnership.OWNED)
            throw new java.io.FileNotFoundException("Motion archive item is unavailable");

        MotionMediaLease lease = MotionMediaLease.acquire(leaseDirectory, initial.uri);
        try {
            index.reloadIfChanged();
            MotionMediaIndex.Record current = index.find(initial.uri);
            volume = current == null ? null : storage.findVolume(current.volumeId);
            if (current == null || !archiveId(current).equals(id) || !isFinalizedArchiveRecord(current)
                    || volume == null || !volume.mounted
                    || storage.inspectPlayableOwnership(volume, Uri.parse(current.uri))
                    != MotionStorage.MediaStoreOwnership.OWNED)
                throw new java.io.FileNotFoundException("Motion archive item is unavailable");
            return new ArchivePlayback(this, current, lease);
        } catch (IOException | RuntimeException failure) {
            try { lease.close(); }
            catch (IOException cleanupFailure) { failure.addSuppressed(cleanupFailure); }
            throw failure;
        }
    }

    private ParcelFileDescriptor openArchiveVideoDescriptor(String volumeId, String uriValue,
            String segmentId) throws IOException {
        index.reloadIfChanged();
        MotionMediaIndex.Record current = index.find(uriValue);
        if (current == null || !volumeId.equals(current.volumeId) || !segmentId.equals(current.segmentId)
                || !isFinalizedArchiveRecord(current))
            throw new java.io.FileNotFoundException("Motion archive item is unavailable");
        MotionStorage.Volume volume = storage.findVolume(volumeId);
        Uri uri = Uri.parse(uriValue);
        if (volume == null || !volume.mounted
                || storage.inspectPlayableOwnership(volume, uri)
                != MotionStorage.MediaStoreOwnership.OWNED)
            throw new java.io.FileNotFoundException("Motion archive item is unavailable");
        ParcelFileDescriptor descriptor = storage.openMediaFileDescriptor(uri, "r");
        if (descriptor == null) throw new IOException("Motion archive video could not be opened");
        return descriptor;
    }

    private MotionMediaIndex.Record resolveArchiveRecord(String id) throws IOException {
        if (id == null || !id.matches("[A-Za-z0-9_-]{43}")) return null;
        index.reloadIfChanged();
        for (MotionMediaIndex.Record record : index.records()) {
            if (id.equals(archiveId(record)) && isFinalizedArchiveRecord(record)) return record;
        }
        return null;
    }

    private void requireShellArchive() {
        if (access != null || thumbnailStore == null || storage == null || index == null)
            throw new IllegalStateException("Shell-owned Motion archive access is unavailable");
    }

    private static boolean isFinalizedArchiveRecord(MotionMediaIndex.Record record) {
        return record != null && (MotionMediaIndex.COMPLETE.equals(record.status)
                || MotionMediaIndex.INTERRUPTED.equals(record.status));
    }

    private static Entry archiveEntry(MotionMediaIndex.Record record, Uri uri, long mediaStoreId) {
        ArrayList<Marker> markers = new ArrayList<>();
        for (MotionMediaIndex.Marker marker : record.markers) {
            markers.add(new Marker(marker.eventId, marker.wallTimeMs, marker.elapsedRealtimeMs,
                    marker.endWallTimeMs, marker.endElapsedRealtimeMs,
                    marker.elapsedRealtimeMs - record.startElapsedRealtimeMs, marker.level));
        }
        return new Entry(uri, record.name, record.volumeId, mediaStoreId, record.segmentId,
                record.recordedAtMs, record.durationMs, record.sizeBytes, status(record.status),
                markers, true, record.protectedFile, record.cameraOrder);
    }

    private static String archiveId(MotionMediaIndex.Record record) {
        String identity = record.volumeId + '\u0000' + record.uri + '\u0000' + record.segmentId;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(identity.getBytes(StandardCharsets.UTF_8));
            return Base64.encodeToString(digest, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static void closeFailedArchiveDescriptor(ParcelFileDescriptor descriptor, Throwable failure) {
        try { descriptor.close(); }
        catch (IOException cleanupFailure) { failure.addSuppressed(cleanupFailure); }
    }

    void prepareThumbnail(String uri) {
        if (thumbnailStore != null) thumbnailStore.prepare(uri);
    }

    void scanAvailableThumbnails() {
        if (thumbnailStore != null) thumbnailStore.scanAvailable();
    }

    ParcelFileDescriptor openPreparedThumbnail(String volumeId, long mediaStoreId, String expectedKey) {
        if (thumbnailStore == null || volumeId == null || volumeId.isEmpty() || mediaStoreId <= 0
                || !MotionThumbnailKey.isValid(expectedKey)) return null;
        MotionMediaIndex.Record current = index.find(volumeId, mediaStoreId);
        if (current == null || !(MotionMediaIndex.COMPLETE.equals(current.status)
                || MotionMediaIndex.INTERRUPTED.equals(current.status))
                || !expectedKey.equals(MotionThumbnailKey.forRecord(current, mediaStoreId))) return null;
        MotionStorage.Volume volume = storage.findVolume(volumeId);
        if (volume != null && volume.mounted
                && !storage.isOwnedMedia(volume, Uri.parse(current.uri))) return null;
        return thumbnailStore.open(volumeId, mediaStoreId, expectedKey, current);
    }

    public enum Sort { NEWEST_FIRST, OLDEST_FIRST }
    public enum Status { RECORDING, COMPLETE, INTERRUPTED, UNKNOWN, UNAVAILABLE }

    public static final class Query {
        public final Long startInclusiveMs;
        public final Long endExclusiveMs;
        public final String volumeId;
        public final Sort sort;

        public Query(Long startInclusiveMs, Long endExclusiveMs, String volumeId, Sort sort) {
            this.startInclusiveMs = startInclusiveMs;
            this.endExclusiveMs = endExclusiveMs;
            this.volumeId = volumeId;
            this.sort = sort == null ? Sort.NEWEST_FIRST : sort;
        }

        public static Query recent() { return new Query(null, null, null, Sort.NEWEST_FIRST); }
    }

    public static final class Marker {
        public final String eventId;
        public final long recordedAtMillis;
        public final long elapsedRealtimeMs;
        public final long endRecordedAtMillis;
        public final long endElapsedRealtimeMs;
        public final long positionMs;
        public final long durationMs;
        public final int level;

        private Marker(String eventId, long recordedAtMillis, long elapsedRealtimeMs,
                long endRecordedAtMillis, long endElapsedRealtimeMs, long positionMs, int level) {
            this.eventId = eventId;
            this.recordedAtMillis = recordedAtMillis;
            this.elapsedRealtimeMs = elapsedRealtimeMs;
            this.endRecordedAtMillis = endRecordedAtMillis;
            this.endElapsedRealtimeMs = endElapsedRealtimeMs;
            this.positionMs = Math.max(0, positionMs);
            this.durationMs = Math.max(0, endElapsedRealtimeMs - elapsedRealtimeMs);
            this.level = level;
        }
    }

    public static final class Entry {
        public final Uri contentUri;
        public final String title;
        public final String volumeId;
        public final long mediaStoreId;
        public final String segmentId;
        public final long recordedAtMillis;
        public final long durationMs;
        public final long sizeBytes;
        public final Status status;
        public final List<Marker> markers;
        public final boolean available;
        public final boolean protectedFile;
        public final int[] cameraOrder;

        private Entry(Uri contentUri, String title, String volumeId, long mediaStoreId, String segmentId,
                long recordedAtMillis, long durationMs, long sizeBytes, Status status,
                List<Marker> markers, boolean available, boolean protectedFile, int[] cameraOrder) {
            this.contentUri = contentUri;
            this.title = title == null ? "" : title;
            this.volumeId = volumeId;
            this.mediaStoreId = mediaStoreId;
            this.segmentId = segmentId;
            this.recordedAtMillis = recordedAtMillis;
            this.durationMs = Math.max(0, durationMs);
            this.sizeBytes = Math.max(0, sizeBytes);
            this.status = status;
            this.markers = Collections.unmodifiableList(markers);
            this.available = available;
            this.protectedFile = protectedFile;
            this.cameraOrder = MotionMediaIndex.copyCameraOrder(cameraOrder);
        }
    }

    /** Metadata row for the helper-owned remote archive; the URI stays inside the helper. */
    public static final class ArchiveItem {
        public final String id;
        public final Entry entry;
        public final String storage;

        private ArchiveItem(String id, Entry entry, String storage) {
            this.id = id;
            this.entry = entry;
            this.storage = storage;
        }
    }

    /** Seekable descriptor for one active range response, closed by the response or playback. */
    public static final class ArchiveDescriptor implements java.io.Closeable {
        public final ParcelFileDescriptor descriptor;
        public final long contentLength;
        private final ArchivePlayback playback;
        private boolean closed;

        private ArchiveDescriptor(ArchivePlayback playback, ParcelFileDescriptor descriptor,
                long contentLength) {
            this.playback = playback;
            this.descriptor = descriptor;
            this.contentLength = contentLength;
        }

        @Override public void close() throws IOException {
            synchronized (this) {
                if (closed) return;
                closed = true;
                playback.release(this);
                descriptor.close();
            }
        }

        private void closeFromPlayback() throws IOException {
            synchronized (this) {
                if (closed) return;
                closed = true;
                descriptor.close();
            }
        }
    }

    /** Cross-process Motion cleanup lease held for the whole selected playback, across Range requests. */
    public static final class ArchivePlayback implements java.io.Closeable {
        private final MotionMediaLibrary library;
        private final String volumeId;
        private final String uri;
        private final String segmentId;
        private final long indexedSize;
        private final MotionMediaLease lease;
        private final Set<ArchiveDescriptor> descriptors = new HashSet<>();
        private boolean closed;

        private ArchivePlayback(MotionMediaLibrary library, MotionMediaIndex.Record record,
                MotionMediaLease lease) {
            this.library = library;
            this.volumeId = record.volumeId;
            this.uri = record.uri;
            this.segmentId = record.segmentId;
            this.indexedSize = record.sizeBytes;
            this.lease = lease;
        }

        public ArchiveDescriptor openDescriptor() throws IOException {
            synchronized (this) {
                if (closed) throw new IOException("Motion archive playback is closed");
            }
            ParcelFileDescriptor descriptor = library.openArchiveVideoDescriptor(volumeId, uri, segmentId);
            try {
                long statSize = descriptor.getStatSize();
                if (statSize == 0) throw new IOException("Motion archive video is empty");
                long contentLength = statSize > 0 ? statSize : indexedSize;
                if (contentLength <= 0) throw new IOException("Motion archive video length is unavailable");
                ArchiveDescriptor range = new ArchiveDescriptor(this, descriptor, contentLength);
                synchronized (this) {
                    if (closed) throw new IOException("Motion archive playback is closed");
                    descriptors.add(range);
                }
                return range;
            } catch (IOException failure) {
                closeFailedArchiveDescriptor(descriptor, failure);
                throw failure;
            } catch (RuntimeException failure) {
                closeFailedArchiveDescriptor(descriptor, failure);
                throw failure;
            } catch (Error failure) {
                closeFailedArchiveDescriptor(descriptor, failure);
                throw failure;
            }
        }

        private void release(ArchiveDescriptor descriptor) {
            synchronized (this) { descriptors.remove(descriptor); }
        }

        @Override public void close() throws IOException {
            ArrayList<ArchiveDescriptor> active;
            synchronized (this) {
                if (closed) return;
                closed = true;
                active = new ArrayList<>(descriptors);
                descriptors.clear();
            }
            IOException failure = null;
            for (ArchiveDescriptor descriptor : active) {
                try { descriptor.closeFromPlayback(); }
                catch (IOException cleanupFailure) {
                    if (failure == null) failure = cleanupFailure;
                    else failure.addSuppressed(cleanupFailure);
                }
            }
            try { lease.close(); }
            catch (IOException cleanupFailure) {
                if (failure == null) failure = cleanupFailure;
                else failure.addSuppressed(cleanupFailure);
            }
            if (failure != null) throw failure;
        }
    }

    public static final class Day {
        public final LocalDate date;
        public final int entryCount;
        public final long durationMs;
        public final long sizeBytes;
        public final int yellowEvents;
        public final int redEvents;

        private Day(LocalDate date, int entryCount, long durationMs, long sizeBytes,
                int yellowEvents, int redEvents) {
            this.date = date;
            this.entryCount = entryCount;
            this.durationMs = durationMs;
            this.sizeBytes = sizeBytes;
            this.yellowEvents = yellowEvents;
            this.redEvents = redEvents;
        }
    }

    public static final class VolumeStats {
        public final String id;
        public final String label;
        public final boolean primary;
        public final boolean removable;
        public final boolean mounted;
        public final boolean readOnly;
        public final long capacityBytes;
        public final long freeBytes;
        public final long otherBytes;
        public final long ownBytes;
        public final long oemRecorderBytes;
        public final long ownAllowanceBytes;
        public final long oemAllowanceBytes;
        public final long unallocatedBytes;

        private VolumeStats(String id, String label, boolean primary, boolean removable,
                boolean mounted, boolean readOnly, long capacityBytes, long freeBytes,
                long otherBytes, long ownBytes, long oemRecorderBytes, long ownAllowanceBytes,
                long oemAllowanceBytes, long unallocatedBytes) {
            this.id = id; this.label = label; this.primary = primary; this.removable = removable;
            this.mounted = mounted; this.readOnly = readOnly; this.capacityBytes = capacityBytes;
            this.freeBytes = freeBytes; this.otherBytes = otherBytes; this.ownBytes = ownBytes;
            this.oemRecorderBytes = oemRecorderBytes; this.ownAllowanceBytes = ownAllowanceBytes;
            this.oemAllowanceBytes = oemAllowanceBytes; this.unallocatedBytes = unallocatedBytes;
        }
    }

    public static final class Stats {
        public final List<VolumeStats> volumes;
        public final VolumeStats primary;
        public final VolumeStats secondary;

        private Stats(List<VolumeStats> volumes, VolumeStats primary, VolumeStats secondary) {
            this.volumes = Collections.unmodifiableList(volumes);
            this.primary = primary;
            this.secondary = secondary;
        }
    }

    public static final class Snapshot {
        public final List<Entry> entries;
        public final Stats stats;

        private Snapshot(List<Entry> entries, Stats stats) {
            this.entries = Collections.unmodifiableList(entries);
            this.stats = stats;
        }
    }

    public static final class DeleteResult {
        public final int deletedCount;
        public final int failedCount;
        public final int skippedCount;

        private DeleteResult(int deletedCount, int failedCount, int skippedCount) {
            this.deletedCount = deletedCount; this.failedCount = failedCount; this.skippedCount = skippedCount;
        }
    }

    static final class StorageDecision {
        final boolean allowed;
        final long ownBytes;
        final long allowanceBytes;
        final String reason;

        private StorageDecision(boolean allowed, long ownBytes, long allowanceBytes, String reason) {
            this.allowed = allowed;
            this.ownBytes = ownBytes;
            this.allowanceBytes = allowanceBytes;
            this.reason = reason;
        }
    }

    /** Reconciles app-owned public media and finishes valid interrupted segments after a crash. */
    public synchronized int refresh() {
        if (access != null) return access.refresh();
        int changed = 0;
        List<MotionStorage.Volume> volumes = storage.volumes();
        Map<String, MotionStorage.Volume> byId = new HashMap<>();
        for (MotionStorage.Volume volume : volumes) byId.put(volume.id, volume);

        for (MotionMediaIndex.Record row : index.records()) {
            if (!MotionMediaIndex.RECORDING.equals(row.status)) continue;
            MotionStorage.Volume volume = byId.get(row.volumeId);
            if (volume == null || !volume.mounted || MotionMediaLease.isHeld(leaseDirectory, row.uri)) continue;
            Uri finalUri = Uri.parse(row.uri);
            String inspectionUri = row.uri;
            if (row.stagingUri != null && "file".equals(finalUri.getScheme())
                    && !new File(finalUri.getPath()).isFile()) inspectionUri = row.stagingUri;
            MediaFacts facts = inspect(inspectionUri);
            try {
                if (facts != null && facts.hasVideo
                        && storage.recoverInterrupted(volume, Uri.parse(row.uri), row.stagingUri)) {
                    long bytes = sizeOf(Uri.parse(row.uri));
                    index.update(row.uri, MotionMediaIndex.INTERRUPTED, facts.durationMs, Math.max(0, bytes));
                    changed++;
                } else if (storage.discardUnplayableInterrupted(volume, Uri.parse(row.uri), row.stagingUri)) {
                    index.remove(row.uri);
                    if (thumbnailStore != null)
                        thumbnailStore.removeForRecord(row, storage.mediaStoreId(volume, Uri.parse(row.uri)));
                    changed++;
                }
            } catch (IOException | RuntimeException ignored) {
                // Keep the index row for a later retry; volume ejection and permission loss are transient.
            }
        }

        for (MotionStorage.Volume volume : volumes) {
            if (!volume.mounted) continue;
            List<MotionStorage.OwnedMedia> media;
            try { media = storage.ownedMedia(volume); }
            catch (IOException unavailable) { continue; }
            for (MotionStorage.OwnedMedia item : media) {
                String uri = item.uri.toString();
                if (index.find(uri) != null) continue;
                if (item.pending) {
                    if (System.currentTimeMillis() - item.recordedAtMs < 60_000L
                            || MotionMediaLease.isHeld(leaseDirectory, uri)) continue;
                    MediaFacts facts = inspect(uri);
                    try {
                        if (facts != null && facts.hasVideo && storage.recoverInterrupted(volume, item.uri, null)) {
                            MotionMediaIndex.Record recovered = new MotionMediaIndex.Record(uri, volume.id,
                                    item.name, item.name == null ? uri : item.name, item.recordedAtMs,
                                    0, item.width, item.height, MotionMediaIndex.INTERRUPTED);
                            recovered.durationMs = facts.durationMs;
                            recovered.sizeBytes = sizeOf(item.uri);
                            index.put(recovered);
                            changed++;
                        } else if (storage.discardUnplayableInterrupted(volume, item.uri, null)) changed++;
                    } catch (IOException | RuntimeException ignored) {
                        // Retain uncertain pending media for a later scan rather than deleting it.
                    }
                    continue;
                }
                String segmentId = item.name == null ? uri : item.name;
                MotionMediaIndex.Record record = new MotionMediaIndex.Record(uri, volume.id, item.name,
                        segmentId, item.recordedAtMs, 0, item.width, item.height, MotionMediaIndex.COMPLETE);
                record.durationMs = item.durationMs;
                record.sizeBytes = item.sizeBytes;
                try { index.put(record); changed++; }
                catch (IOException ignored) { }
            }
        }
        if (thumbnailStore != null) thumbnailStore.scanAvailable();
        return changed;
    }

    public List<Entry> query(Query query) {
        Query actual = query == null ? Query.recent() : query;
        if (access != null) return queryRemote(actual, MotionConfig.read(context)).entries;
        ArrayList<Entry> result = new ArrayList<>();
        Map<String, MotionStorage.Volume> volumes = volumeMap();
        List<MotionMediaIndex.Record> records = index.records();
        Map<String, Set<String>> ownedByVolume = new HashMap<>();
        for (MotionMediaIndex.Record record : records) {
            if (!matches(actual, record) || ownedByVolume.containsKey(record.volumeId)) continue;
            MotionStorage.Volume volume = volumes.get(record.volumeId);
            if (volume == null || !volume.mounted) continue;
            Set<String> owned = new HashSet<>();
            try {
                for (MotionStorage.OwnedMedia item : storage.ownedMedia(volume)) {
                    if (!item.pending) owned.add(item.uri.toString());
                }
            } catch (IOException unavailable) {
                throw new IllegalStateException("Cannot verify Motion archive availability for "
                        + volume.id, unavailable);
            }
            ownedByVolume.put(record.volumeId, owned);
        }
        for (MotionMediaIndex.Record record : records) {
            if (!matches(actual, record)) continue;
            MotionStorage.Volume volume = volumes.get(record.volumeId);
            boolean mounted = volume != null && volume.mounted;
            Uri uri = Uri.parse(record.uri);
            Set<String> owned = ownedByVolume.get(record.volumeId);
            boolean available = mounted && owned != null && owned.contains(record.uri);
            long mediaStoreId = storage.mediaStoreId(volume, uri);
            if (mediaStoreId <= 0) mediaStoreId = indexedMediaStoreId(uri);
            Status status = status(record.status);
            if (!mounted) status = Status.UNAVAILABLE;
            ArrayList<Marker> markers = new ArrayList<>();
            for (MotionMediaIndex.Marker marker : record.markers) {
                markers.add(new Marker(marker.eventId, marker.wallTimeMs, marker.elapsedRealtimeMs,
                        marker.endWallTimeMs, marker.endElapsedRealtimeMs,
                        marker.elapsedRealtimeMs - record.startElapsedRealtimeMs, marker.level));
            }
            result.add(new Entry(uri, record.name, record.volumeId,
                    mediaStoreId, record.segmentId,
                    record.recordedAtMs, record.durationMs, record.sizeBytes, status, markers,
                    available, record.protectedFile, record.cameraOrder));
        }
        Comparator<Entry> order = Comparator.comparingLong(entry -> entry.recordedAtMillis);
        if (actual.sort == Sort.NEWEST_FIRST) order = order.reversed();
        result.sort(order.thenComparing(entry -> entry.contentUri.toString()));
        return result;
    }

    /** Reads the archive and statistics in one pagination cycle, without a second storage scan. */
    public Snapshot loadSnapshot(MotionConfig config) {
        MotionConfig actual = config == null ? MotionConfig.read(context) : config;
        if (access != null) return queryRemote(Query.recent(), actual);
        return new Snapshot(query(Query.recent()), queryStats(actual));
    }

    private Snapshot queryRemote(Query query, MotionConfig config) {
        ArrayList<Entry> result = new ArrayList<>();
        Stats stats = null;
        String pageToken = null;
        java.util.HashSet<String> tokens = new java.util.HashSet<>();
        for (int pages = 0; pages < 10000; pages++) {
            JSONObject response = parseResponse(access.query(query, config, pageToken));
            try {
                if (stats == null) stats = readStats(response.getJSONObject("stats"));
                JSONArray entries = response.getJSONArray("entries");
                for (int i = 0; i < entries.length(); i++) result.add(readEntry(entries.getJSONObject(i)));
                if (response.isNull("nextPageToken")) break;
                String next = response.getString("nextPageToken");
                if (next.isEmpty() || !tokens.add(next))
                    throw new IllegalStateException("Worker returned a repeated Motion page token");
                pageToken = next;
            } catch (JSONException e) {
                throw new IllegalStateException("Invalid worker Motion media page", e);
            }
            if (pages == 9999) throw new IllegalStateException("Worker Motion media page limit exceeded");
        }
        Comparator<Entry> order = Comparator.comparingLong(entry -> entry.recordedAtMillis);
        if (query.sort == Sort.NEWEST_FIRST) order = order.reversed();
        result.sort(order.thenComparing(entry -> entry.contentUri.toString()));
        return new Snapshot(result, stats);
    }

    private static JSONObject parseResponse(String encoded) {
        if (encoded == null || encoded.isEmpty()) throw new IllegalStateException("Worker returned no Motion data");
        try {
            JSONObject root = new JSONObject(encoded);
            if (root.optInt("version", 0) != 1) throw new IllegalStateException("Unsupported worker Motion response");
            return root;
        } catch (JSONException e) {
            throw new IllegalStateException("Invalid worker Motion response", e);
        }
    }

    private static Entry readEntry(JSONObject row) throws JSONException {
        Uri uri = Uri.parse(row.getString("contentUri"));
        if (!"content".equals(uri.getScheme()) || !"media".equals(uri.getAuthority()))
            throw new JSONException("Worker Motion entry URI is not MediaStore media");
        String statusValue = row.optString("status", Status.UNKNOWN.name());
        Status entryStatus;
        try { entryStatus = Status.valueOf(statusValue.toUpperCase(java.util.Locale.ROOT)); }
        catch (IllegalArgumentException unknown) { entryStatus = Status.UNKNOWN; }
        JSONArray encodedMarkers = row.optJSONArray("markers");
        ArrayList<Marker> markers = new ArrayList<>();
        if (encodedMarkers != null) for (int i = 0; i < encodedMarkers.length(); i++) {
            JSONObject marker = encodedMarkers.getJSONObject(i);
            long elapsed = marker.getLong("elapsedRealtimeMs");
            long endElapsed = marker.optLong("endElapsedRealtimeMs", elapsed);
            markers.add(new Marker(marker.optString("eventId", ""),
                    marker.getLong("recordedAtMillis"), elapsed,
                    marker.optLong("endRecordedAtMillis", marker.getLong("recordedAtMillis")),
                    endElapsed, marker.optLong("positionMs", 0), marker.getInt("level")));
        }
        long mediaStoreId = row.optLong("mediaStoreId", -1);
        if (mediaStoreId <= 0 || !Long.toString(mediaStoreId).equals(uri.getLastPathSegment()))
            throw new JSONException("Worker Motion entry has no matching MediaStore ID");
        return new Entry(uri, row.optString("title", ""), row.getString("volumeId"), mediaStoreId,
                row.optString("segmentId", ""), row.optLong("recordedAtMillis", 0),
                row.optLong("durationMs", 0), row.optLong("sizeBytes", 0), entryStatus,
                markers, row.optBoolean("available", false), row.optBoolean("protectedFile", false),
                MotionMediaIndex.readCameraOrder(row.optJSONArray("cameraOrder")));
    }

    private static Stats readStats(JSONObject json) throws JSONException {
        JSONArray encodedVolumes = json.getJSONArray("volumes");
        ArrayList<VolumeStats> volumes = new ArrayList<>();
        HashMap<String, VolumeStats> byId = new HashMap<>();
        for (int i = 0; i < encodedVolumes.length(); i++) {
            JSONObject row = encodedVolumes.getJSONObject(i);
            VolumeStats volume = new VolumeStats(row.getString("id"), row.optString("label", ""),
                    row.optBoolean("primary"), row.optBoolean("removable"), row.optBoolean("mounted"),
                    row.optBoolean("readOnly"), row.optLong("capacityBytes", -1),
                    row.optLong("freeBytes", -1), row.optLong("otherBytes", -1),
                    row.optLong("ownBytes", -1), row.optLong("oemRecorderBytes", -1),
                    row.optLong("ownAllowanceBytes", -1), row.optLong("oemAllowanceBytes", -1),
                    row.optLong("unallocatedBytes", -1));
            volumes.add(volume);
            byId.put(volume.id, volume);
        }
        String primaryId = json.isNull("primary") ? null : json.getJSONObject("primary").optString("id", null);
        String secondaryId = json.isNull("secondary") ? null : json.getJSONObject("secondary").optString("id", null);
        return new Stats(volumes, primaryId == null ? null : byId.get(primaryId),
                secondaryId == null ? null : byId.get(secondaryId));
    }

    /** Aggregates only entries in the supplied interval/volume, using the device-local calendar date. */
    public List<Day> queryDays(Query query) {
        Query actual = query == null ? Query.recent() : query;
        TreeMap<LocalDate, MutableDay> days = new TreeMap<>();
        ZoneId zone = ZoneId.systemDefault();
        for (Entry entry : query(actual)) {
            LocalDate date = Instant.ofEpochMilli(entry.recordedAtMillis).atZone(zone).toLocalDate();
            MutableDay day = days.computeIfAbsent(date, MutableDay::new);
            day.entryCount++;
            day.durationMs += entry.durationMs;
            day.sizeBytes += entry.sizeBytes;
            for (Marker marker : entry.markers) {
                String key = marker.eventId.isEmpty()
                        ? entry.contentUri + "|" + marker.elapsedRealtimeMs + "|" + marker.level
                        : marker.eventId;
                if (day.events.add(key)) {
                    if (marker.level == 1) day.yellow++;
                    else if (marker.level == 2) day.red++;
                }
            }
        }
        ArrayList<Day> result = new ArrayList<>();
        for (MutableDay day : days.values()) result.add(day.toDay());
        if (actual.sort == Sort.NEWEST_FIRST) Collections.reverse(result);
        return result;
    }

    public Stats queryStats() { return queryStats(MotionConfig.read(context)); }

    public Stats queryStats(MotionConfig config) {
        MotionConfig actual = config == null ? MotionConfig.read(context) : config;
        if (access != null) {
            JSONObject response = parseResponse(access.query(Query.recent(), actual, null));
            try { return readStats(response.getJSONObject("stats")); }
            catch (JSONException e) { throw new IllegalStateException("Invalid worker Motion statistics", e); }
        }
        List<MotionStorage.Volume> volumes = storage.volumes();
        ArrayList<VolumeStats> result = new ArrayList<>();
        VolumeStats primary = null;
        VolumeStats secondary = null;
        String selectedId = selectedVolumeId(actual.primarySd);
        for (MotionStorage.Volume volume : volumes) {
            long own = storage.queryOwnedBytes(volume);
            long oem = storage.queryRecorderBytes(volume);
            long total = volume.totalBytes;
            long free = volume.freeBytes;
            long other = -1;
            long ownAllowance = canRecordHere(volume, actual, selectedId) ? -1 : 0;
            Long oemLimit = storage.oemLimitBytes(actual, volume);
            long oemAllowance = oemLimit != null ? -1 : 0;
            long unallocated = -1;
            if (total >= 0 && free >= 0 && own >= 0 && oem >= 0) {
                MotionStoragePolicy.Parts parts = MotionStoragePolicy.calculate(total, free, own,
                        limitBytes(volume, actual), canRecordHere(volume, actual, selectedId),
                        keepFreeBytes(volume, actual), oem,
                        oemLimit);
                other = parts.other;
                ownAllowance = parts.allowance;
                oemAllowance = parts.oemAllowance;
                unallocated = parts.unallocated;
            } else if (total >= 0 && free >= 0 && own >= 0 && oem < 0
                    && oemLimit == null) {
                MotionStoragePolicy.Parts parts = MotionStoragePolicy.calculate(total, free, own,
                        limitBytes(volume, actual), canRecordHere(volume, actual, selectedId),
                        keepFreeBytes(volume, actual), 0, null);
                ownAllowance = parts.allowance;
                unallocated = parts.unallocated;
            } else if (total >= 0 && free >= 0 && own >= 0 && oem < 0) {
                other = -1; // Without permission, Recorder and non-OEM bytes cannot honestly be separated.
            }
            VolumeStats stats = new VolumeStats(volume.id, volume.label, volume.primary, volume.removable,
                    volume.mounted, volume.readOnly, total, free, other, own, oem,
                    ownAllowance, oemAllowance, unallocated);
            result.add(stats);
            if (volume.primary) primary = stats;
            else if (secondary == null && volume.removable) secondary = stats;
        }
        return new Stats(result, primary, secondary);
    }

    public DeleteResult deleteSelected(Collection<Entry> selected) {
        if (selected == null || selected.isEmpty()) return new DeleteResult(0, 0, 0);
        int deleted = 0, failed = 0, skipped = 0;
        if (access != null) {
            for (Entry entry : selected) {
                if (entry == null || entry.mediaStoreId <= 0 || entry.protectedFile
                        || (entry.status != Status.COMPLETE && entry.status != Status.INTERRUPTED
                        && entry.status != Status.UNKNOWN)) {
                    skipped++;
                } else if (access.delete(entry.volumeId, entry.mediaStoreId)) deleted++;
                else failed++;
            }
            return new DeleteResult(deleted, failed, skipped);
        }
        for (Entry entry : selected) {
            if (entry == null || entry.contentUri == null || entry.protectedFile
                    || (entry.status != Status.COMPLETE && entry.status != Status.INTERRUPTED
                    && entry.status != Status.UNKNOWN)) {
                skipped++;
                continue;
            }
            MotionMediaIndex.Record current = index.find(entry.contentUri.toString());
            if (current == null || MotionMediaIndex.RECORDING.equals(current.status)
                    || current.protectedFile || !current.segmentId.equals(entry.segmentId)) {
                skipped++;
                continue;
            }
            if (MotionMediaLease.isHeld(leaseDirectory, current.uri)) {
                skipped++;
                continue;
            }
            MotionStorage.Volume volume = storage.findVolume(current.volumeId);
            if (volume == null || !volume.mounted || volume.readOnly) {
                failed++;
                continue;
            }
            try {
                MotionStorage.MediaStoreOwnership ownership =
                        storage.inspectMediaStoreOwnership(volume, entry.contentUri);
                if (ownership == MotionStorage.MediaStoreOwnership.OTHER) {
                    failed++;
                    continue;
                }
                if (ownership == MotionStorage.MediaStoreOwnership.ABSENT) {
                    MotionStorage.Volume confirmedVolume = storage.findVolume(current.volumeId);
                    if (!storage.canConfirmAbsent(confirmedVolume, entry.contentUri)) {
                        failed++;
                        continue;
                    }
                    volume = confirmedVolume;
                }
                MotionMediaIndex.Record latest = index.find(current.uri);
                if (latest == null || MotionMediaIndex.RECORDING.equals(latest.status)
                        || latest.protectedFile || !latest.segmentId.equals(entry.segmentId)
                        || MotionMediaLease.isHeld(leaseDirectory, latest.uri)) {
                    skipped++;
                    continue;
                }
                if (ownership == MotionStorage.MediaStoreOwnership.OWNED
                        && storage.deleteOwned(volume, entry.contentUri) != 1) {
                    failed++;
                    continue;
                }
                if (!index.remove(latest.uri)) {
                    failed++;
                    continue;
                }
                if (thumbnailStore != null)
                    thumbnailStore.removeForRecord(latest, storage.mediaStoreId(volume, entry.contentUri));
                deleted++;
            } catch (IOException | RuntimeException e) {
                failed++;
            }
        }
        return new DeleteResult(deleted, failed, skipped);
    }

    String selectedVolumeId(boolean sd) {
        List<MotionStorage.Volume> volumes = storage.volumes();
        if (!sd) {
            for (MotionStorage.Volume volume : volumes) if (volume.primary) return volume.id;
        } else {
            for (MotionStorage.Volume volume : volumes) if (volume.removable && volume.mounted) return volume.id;
        }
        return null;
    }

    MotionMediaIndex index() {
        if (index == null) throw new IllegalStateException("Remote Motion library has no local index");
        return index;
    }
    MotionStorage storage() {
        if (storage == null) throw new IllegalStateException("Remote Motion library has no local storage");
        return storage;
    }
    File leaseDirectory() { return leaseDirectory; }

    public Entry read(String volumeId, long mediaStoreId) {
        if (access == null) return null;
        String encoded = access.read(volumeId, mediaStoreId);
        if (encoded == null || encoded.isEmpty()) return null;
        JSONObject root = parseResponse(encoded);
        try { return root.isNull("entry") ? null : readEntry(root.getJSONObject("entry")); }
        catch (JSONException e) { throw new IllegalStateException("Invalid worker Motion record", e); }
    }

    public void close() {
        if (storage != null) {
            if (thumbnailStore != null) thumbnailStore.close();
            storage.close();
            if (ownedShellMediaContext != null) ownedShellMediaContext.close();
        } else if (access instanceof AutoCloseable) {
            try { ((AutoCloseable) access).close(); }
            catch (Exception ignored) { }
        }
    }

    /** Deletes only oldest indexed, complete, unprotected own clips until this segment's byte budget fits. */
    StorageDecision prepareOwnSpace(String volumeId, MotionConfig config, long requiredBytes) {
        return prepareOwnSpace(volumeId, config, requiredBytes, -1, 0);
    }

    StorageDecision prepareOwnSpace(String volumeId, MotionConfig config, long requiredBytes,
            long baseOwnBytes, long activeBytes) {
        if (!storage.hasMotionStoragePermission())
            return new StorageDecision(false, -1, -1, "motion_storage_permission_denied");
        MotionConfig actual = config == null ? MotionConfig.read(context) : config;
        MotionStorage.Volume volume = storage.findVolume(volumeId);
        if (volume == null || !volume.mounted || volume.readOnly)
            return new StorageDecision(false, -1, -1, "motion_volume_not_writable");
        String selectedId = selectedVolumeId(actual.primarySd);
        if (!canRecordHere(volume, actual, selectedId))
            return new StorageDecision(false, -1, -1, "motion_volume_not_selected_or_fallback");
        long needed = Math.max(0, requiredBytes);
        java.util.HashSet<String> attempted = new java.util.HashSet<>();
        boolean deletionFailed = false;
        while (true) {
            MotionStorage.Volume currentVolume = storage.findVolume(volumeId);
            if (currentVolume == null || !currentVolume.mounted || currentVolume.readOnly)
                return new StorageDecision(false, -1, -1, "motion_volume_not_writable");
            if (currentVolume.totalBytes <= 0 || currentVolume.freeBytes < 0)
                return new StorageDecision(false, -1, -1, "motion_free_space_unavailable");
            long own = storage.queryOwnedBytes(currentVolume);
            if (own < 0) return new StorageDecision(false, -1, -1, "motion_archive_accounting_unavailable");
            if (baseOwnBytes >= 0 && activeBytes > 0)
                own = Math.max(own, Math.min(Math.max(0, currentVolume.totalBytes), baseOwnBytes + activeBytes));
            long oem = storage.queryRecorderBytes(currentVolume);
            Long oemLimit = storage.oemLimitBytes(actual, currentVolume);
            if (oem < 0 && oemLimit != null)
                return new StorageDecision(false, own, -1, "oem_recorder_accounting_unavailable");
            MotionStoragePolicy.Parts parts = MotionStoragePolicy.calculate(currentVolume.totalBytes,
                    currentVolume.freeBytes, own, limitBytes(currentVolume, actual), true,
                    keepFreeBytes(currentVolume, actual), Math.max(0, oem),
                    oemLimit);
            if (parts.allowance >= needed)
                return new StorageDecision(true, own, parts.allowance, null);

            MotionMediaIndex.Record candidate = null;
            for (MotionMediaIndex.Record record : index.records()) {
                if (!currentVolume.id.equals(record.volumeId) || !MotionMediaIndex.COMPLETE.equals(record.status)
                        || record.protectedFile || attempted.contains(record.uri)
                        || MotionMediaLease.isHeld(leaseDirectory, record.uri)) continue;
                Uri uri = Uri.parse(record.uri);
                if (!storage.isOwnedMedia(currentVolume, uri)) continue;
                if (candidate == null || record.recordedAtMs < candidate.recordedAtMs) candidate = record;
            }
            if (candidate == null) {
                if (deletionFailed)
                    return new StorageDecision(false, own, parts.allowance, "motion_archive_retention_delete_failed");
                return new StorageDecision(false, own, parts.allowance,
                        storageFailureReason(currentVolume, actual, own, parts.allowance, needed));
            }
            attempted.add(candidate.uri);
            if (storage.deleteOwned(currentVolume, Uri.parse(candidate.uri)) != 1) {
                deletionFailed = true;
                continue;
            }
            try {
                index.remove(candidate.uri);
                if (thumbnailStore != null)
                    thumbnailStore.removeForRecord(candidate, storage.mediaStoreId(currentVolume,
                            Uri.parse(candidate.uri)));
            } catch (IOException failure) {
                return new StorageDecision(false, own, parts.allowance, "motion_archive_index_update_failed");
            }
        }
    }

    /** Remaining policy allowance for an active segment, including its bytes if MediaStore metadata lags. */
    long currentOwnGrowthAllowance(String volumeId, MotionConfig config, long baseOwnBytes, long activeBytes) {
        MotionConfig actual = config == null ? MotionConfig.read(context) : config;
        MotionStorage.Volume volume = storage.findVolume(volumeId);
        if (volume == null || !volume.mounted || volume.readOnly || volume.totalBytes < 0 || volume.freeBytes < 0)
            return -1;
        long own = storage.queryOwnedBytes(volume);
        if (own < 0) return -1;
        if (baseOwnBytes >= 0 && activeBytes > 0)
            own = Math.max(own, Math.min(volume.totalBytes, baseOwnBytes + activeBytes));
        long oem = storage.queryRecorderBytes(volume);
        Long oemLimit = storage.oemLimitBytes(actual, volume);
        if (oem < 0) {
            if (oemLimit != null) return -1;
            oem = 0;
        }
        String selectedId = selectedVolumeId(actual.primarySd);
        MotionStoragePolicy.Parts parts = MotionStoragePolicy.calculate(volume.totalBytes, volume.freeBytes,
                own, limitBytes(volume, actual), canRecordHere(volume, actual, selectedId),
                keepFreeBytes(volume, actual), Math.max(0, oem),
                oemLimit);
        return parts.allowance;
    }

    private static String storageFailureReason(MotionStorage.Volume volume, MotionConfig config,
            long ownBytes, long allowance, long requiredBytes) {
        long floor = keepFreeBytes(volume, config);
        if (volume.totalBytes < 0 || volume.freeBytes < 0) return "motion_free_space_unavailable";
        if (volume.freeBytes <= floor) return "motion_keep_free_floor_reached";
        if (ownBytes >= limitBytes(volume, config)) return "motion_archive_limit_reached";
        if (allowance < requiredBytes) return "motion_shared_growth_allowance_insufficient";
        return "motion_storage_unavailable";
    }

    private Map<String, MotionStorage.Volume> volumeMap() {
        HashMap<String, MotionStorage.Volume> result = new HashMap<>();
        for (MotionStorage.Volume volume : storage.volumes()) result.put(volume.id, volume);
        return result;
    }

    private static boolean matches(Query query, MotionMediaIndex.Record record) {
        return (query.volumeId == null || query.volumeId.equals(record.volumeId))
                && (query.startInclusiveMs == null || record.recordedAtMs >= query.startInclusiveMs)
                && (query.endExclusiveMs == null || record.recordedAtMs < query.endExclusiveMs);
    }

    private static long indexedMediaStoreId(Uri uri) {
        return MotionMediaIndex.mediaStoreId(uri == null ? null : uri.toString());
    }

    private static Status status(String value) {
        if (MotionMediaIndex.RECORDING.equals(value)) return Status.RECORDING;
        if (MotionMediaIndex.COMPLETE.equals(value)) return Status.COMPLETE;
        if (MotionMediaIndex.INTERRUPTED.equals(value)) return Status.INTERRUPTED;
        return Status.UNKNOWN;
    }

    private static long limitBytes(MotionStorage.Volume volume, MotionConfig config) {
        long limitGb = volume.primary ? config.internalLimitGb : config.sdLimitGb;
        return limitGb * MotionStoragePolicy.GB;
    }

    private static Long keepFreeBytes(MotionStorage.Volume volume, MotionConfig config) {
        boolean enabled = volume.primary ? config.internalKeepFree : config.sdKeepFree;
        int freeGb = volume.primary ? config.internalFreeGb : config.sdFreeGb;
        return enabled ? freeGb * MotionStoragePolicy.GB : 0L;
    }

    private static boolean canRecordHere(MotionStorage.Volume volume, MotionConfig config, String selectedId) {
        if (!config.recordingEnabled || !volume.mounted || volume.readOnly) return false;
        if (volume.id.equals(selectedId)) return true;
        if (!config.fallbackEnabled) return false;
        if (selectedId != null) return volume.primary != "primary".equals(selectedId);
        return config.primarySd && volume.primary;
    }

    private MediaFacts inspect(String uriValue) {
        MediaExtractor extractor = new MediaExtractor();
        try {
            Uri uri = Uri.parse(uriValue);
            if ("file".equals(uri.getScheme())) extractor.setDataSource(uri.getPath());
            else try (ParcelFileDescriptor descriptor = storage.openMediaFileDescriptor(uri, "r")) {
                if (descriptor == null) return null;
                extractor.setDataSource(descriptor.getFileDescriptor());
            }
            boolean video = false;
            long duration = 0;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                android.media.MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(android.media.MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) video = true;
                if (format.containsKey(android.media.MediaFormat.KEY_DURATION))
                    duration = Math.max(duration, format.getLong(android.media.MediaFormat.KEY_DURATION) / 1000L);
            }
            return new MediaFacts(video, duration);
        } catch (IOException | RuntimeException invalid) {
            return null;
        } finally {
            extractor.release();
        }
    }

    private long sizeOf(Uri uri) {
        if ("file".equals(uri.getScheme())) {
            File file = new File(uri.getPath());
            return file.isFile() ? file.length() : 0;
        }
        try (ParcelFileDescriptor descriptor = storage.openMediaFileDescriptor(uri, "r")) {
            return descriptor == null ? 0 : descriptor.getStatSize();
        } catch (IOException | RuntimeException unavailable) { return 0; }
    }

    private static final class MediaFacts {
        final boolean hasVideo;
        final long durationMs;
        MediaFacts(boolean hasVideo, long durationMs) { this.hasVideo = hasVideo; this.durationMs = durationMs; }
    }

    private static final class MutableDay {
        final LocalDate date;
        int entryCount, yellow, red;
        long durationMs, sizeBytes;
        final java.util.HashSet<String> events = new java.util.HashSet<>();
        MutableDay(LocalDate date) { this.date = date; }
        Day toDay() { return new Day(date, entryCount, durationMs, sizeBytes, yellow, red); }
    }
}
