package com.byd.extend;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

/** Builds and reuses immutable camera meshes without doing disk or mesh work on callers. */
final class CameraMeshCache implements AutoCloseable {
    private static final int MAGIC = 0x434D5348; // CMSH
    private static final int FORMAT_VERSION = 1;
    // Bump these when the mapping formula/layout or bundled stock calibration changes.
    private static final int ALGORITHM_REVISION = 1;
    private static final int CALIBRATION_REVISION = 1;
    private static final int RAM_ENTRIES = 32;
    private static final int DISK_ENTRIES = 64;
    private static final int PREWARM_ENTRIES = 64;
    private static final int MESH_FLOATS = CameraFisheyeMapping.MESH_COLUMNS
            * CameraFisheyeMapping.MESH_ROWS * 4;
    private static final int MAX_INDICES = (CameraFisheyeMapping.MESH_COLUMNS - 1)
            * (CameraFisheyeMapping.MESH_ROWS - 1) * 6;
    private static final int IDENTITY_FLOATS = 16;
    private static final int MAX_FILE_BYTES = 1_000_000;

    private final File directory;
    private final BiConsumer<String, Throwable> diagnostic;
    private final Object lock = new Object();
    private final LinkedHashMap<Key, Prepared> memory = new LinkedHashMap<>(RAM_ENTRIES, 0.75f, true);
    private final Map<Key, Work> inFlight = new java.util.HashMap<>();
    private final Map<Key, Work> pendingPrewarm = new java.util.HashMap<>();
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0L,
            TimeUnit.MILLISECONDS,
            new PriorityBlockingQueue<>(11, (first, second) ->
                    ((Work) first).compareTo((Work) second)), runnable -> {
                Thread thread = new Thread(runnable, "camera-mesh-cache");
                thread.setDaemon(true);
                return thread;
            });
    private long nextSequence;
    private boolean closed;

    CameraMeshCache(File directory, BiConsumer<String, Throwable> diagnostic) {
        if (directory == null) throw new IllegalArgumentException("mesh cache directory is required");
        if (diagnostic == null) throw new IllegalArgumentException("mesh cache diagnostic is required");
        this.directory = directory;
        this.diagnostic = diagnostic;
    }

    CompletableFuture<Prepared> request(
            CameraDewarpConfig config, int outputWidth, int outputHeight) {
        if (config == null) return failed(new IllegalArgumentException("dewarp config is required"));
        if (outputWidth <= 0 || outputHeight <= 0) {
            return failed(new IllegalArgumentException("output size must be positive"));
        }

        Key key = new Key(config, outputWidth, outputHeight);
        CompletableFuture<Prepared> result = null;
        Prepared cached;
        boolean requestAfterClose = false;
        Throwable schedulingFailure = null;
        synchronized (lock) {
            if (closed) {
                cached = null;
                requestAfterClose = true;
            } else {
                cached = memory.get(key);
                if (cached == null) {
                    Work work = inFlight.get(key);
                    if (work == null) {
                        work = new Work(key, config, outputWidth, outputHeight,
                                false, ++nextSequence);
                        inFlight.put(key, work);
                        try {
                            worker.execute(work);
                        } catch (RuntimeException | Error rejected) {
                            inFlight.remove(key, work);
                            schedulingFailure = rejected;
                        }
                    } else {
                        boolean removed = worker.remove(work);
                        if (removed) {
                            pendingPrewarm.remove(key, work);
                            work.prewarm = false;
                            work.sequence = ++nextSequence;
                            try {
                                worker.execute(work);
                            } catch (RuntimeException | Error rejected) {
                                inFlight.remove(key, work);
                                schedulingFailure = rejected;
                            }
                        } else if (work.prewarm) {
                            pendingPrewarm.remove(key, work);
                            work.prewarm = false;
                        }
                    }
                    result = work.future;
                }
            }
        }
        if (requestAfterClose) return failed(new IllegalStateException("mesh cache is closed"));
        if (cached != null) return CompletableFuture.completedFuture(cached);
        if (schedulingFailure != null) result.completeExceptionally(schedulingFailure);
        return result;
    }

    List<CompletableFuture<Prepared>> replacePrewarm(List<Request> requests) {
        if (requests == null) throw new IllegalArgumentException("mesh prewarm requests are required");
        LinkedHashMap<Key, Request> desired = new LinkedHashMap<>();
        boolean limited = false;
        for (Request request : requests) {
            if (request == null) throw new IllegalArgumentException("mesh prewarm request is required");
            Key key = new Key(request.config, request.width, request.height);
            if (desired.containsKey(key)) continue;
            if (desired.size() >= PREWARM_ENTRIES) {
                limited = true;
                continue;
            }
            desired.put(key, request);
        }

        List<Work> cancelled = new ArrayList<>();
        List<CompletableFuture<Prepared>> futures = new ArrayList<>(desired.size());
        List<Prepared> ready = new ArrayList<>(desired.size());
        List<Throwable> failures = new ArrayList<>(desired.size());
        Throwable closedFailure = new IllegalStateException("mesh cache is closed");
        synchronized (lock) {
            if (closed) {
                for (int i = 0; i < desired.size(); i++) {
                    futures.add(new CompletableFuture<>());
                    ready.add(null);
                    failures.add(closedFailure);
                }
            } else {
                Iterator<Map.Entry<Key, Work>> queued = pendingPrewarm.entrySet().iterator();
                while (queued.hasNext()) {
                    Map.Entry<Key, Work> entry = queued.next();
                    if (desired.containsKey(entry.getKey())) continue;
                    Work work = entry.getValue();
                    queued.remove();
                    if (worker.remove(work)) {
                        inFlight.remove(entry.getKey(), work);
                        cancelled.add(work);
                    }
                }

                for (Map.Entry<Key, Request> entry : desired.entrySet()) {
                    Key key = entry.getKey();
                    Request request = entry.getValue();
                    Prepared cached = memory.get(key);
                    if (cached != null) {
                        futures.add(null);
                        ready.add(cached);
                        failures.add(null);
                        continue;
                    }
                    Work work = inFlight.get(key);
                    if (work == null) {
                        work = new Work(key, request.config, request.width, request.height,
                                true, ++nextSequence);
                        inFlight.put(key, work);
                        pendingPrewarm.put(key, work);
                        Throwable schedulingFailure = null;
                        try {
                            worker.execute(work);
                        } catch (RuntimeException | Error rejected) {
                            inFlight.remove(key, work);
                            pendingPrewarm.remove(key, work);
                            schedulingFailure = rejected;
                        }
                        futures.add(work.future);
                        ready.add(null);
                        failures.add(schedulingFailure);
                    } else {
                        futures.add(work.future);
                        ready.add(null);
                        failures.add(null);
                    }
                }
            }
        }

        for (Work work : cancelled) {
            work.future.completeExceptionally(
                    new CancellationException("camera mesh prewarm superseded"));
        }
        for (int i = 0; i < futures.size(); i++) {
            Prepared cached = ready.get(i);
            if (cached != null) futures.set(i, CompletableFuture.completedFuture(cached));
            else if (failures.get(i) != null) futures.get(i).completeExceptionally(failures.get(i));
        }
        if (limited) {
            report("limit camera mesh prewarm", new IllegalArgumentException(
                    "camera mesh prewarm limited to " + PREWARM_ENTRIES + " unique requests"));
        }
        return futures;
    }

    private void prepare(Work work) {
        Prepared prepared = null;
        Throwable failure = null;
        try {
            boolean storageReady = prepareDirectory();
            prepared = storageReady ? read(work.key) : null;
            if (prepared == null) {
                prepared = build(work.config, work.width, work.height);
                if (storageReady) write(work.key, prepared);
            }
        } catch (Throwable error) {
            failure = error;
        }
        synchronized (lock) {
            inFlight.remove(work.key, work);
            if (failure == null) {
                memory.put(work.key, prepared.cacheHit());
                while (memory.size() > RAM_ENTRIES) {
                    memory.remove(memory.keySet().iterator().next());
                }
            }
        }
        if (failure == null) work.future.complete(prepared);
        else work.future.completeExceptionally(failure);
    }

    private Prepared build(CameraDewarpConfig config, int width, int height) {
        long startedNs = System.nanoTime();
        CameraFisheyeMapping.Mesh mesh;
        boolean rawFallback = false;
        try {
            mesh = CameraFisheyeMapping.buildMesh(config, width, height);
        } catch (CameraFisheyeMapping.CalibratedDomainException unsupported) {
            report("calibrated mesh unavailable; using raw fallback", unsupported);
            rawFallback = true;
            try {
                mesh = CameraFisheyeMapping.buildMesh(
                        CameraDewarpConfig.disabled(config.lens), width, height);
            } catch (RuntimeException | Error fallbackFailure) {
                fallbackFailure.addSuppressed(unsupported);
                throw fallbackFailure;
            }
        }
        return new Prepared(mesh.vertices, mesh.indices, rawFallback, startedNs, false);
    }

    private Prepared read(Key key) {
        File file = fileFor(key);
        try {
            if (!file.exists()) return null;
            if (!file.isFile()) throw new IOException("mesh cache entry is not a file: " + file);
            long fileBytes = file.length();
            if (fileBytes < 0 || fileBytes > MAX_FILE_BYTES) {
                throw new IOException("invalid mesh cache file size: " + fileBytes);
            }
            try (DataInputStream input = new DataInputStream(
                    new BufferedInputStream(new FileInputStream(file)))) {
                if (input.readInt() != MAGIC) throw new IOException("invalid mesh cache magic");
                if (input.readInt() != FORMAT_VERSION) throw new IOException("unsupported mesh cache format");
                int keyLength = input.readInt();
                if (keyLength != key.bytes.length) throw new IOException("invalid mesh cache key length");
                byte[] storedKey = new byte[keyLength];
                input.readFully(storedKey);
                if (!Arrays.equals(storedKey, key.bytes)) throw new IOException("mesh cache key mismatch");
                boolean rawFallback = input.readBoolean();
                int floatCount = input.readInt();
                int indexCount = input.readInt();
                int expectedFloatCount = key.enabled && !rawFallback ? MESH_FLOATS : IDENTITY_FLOATS;
                if ((rawFallback && !key.enabled) || floatCount != expectedFloatCount
                        || indexCount < 0 || indexCount > MAX_INDICES || indexCount % 3 != 0
                        || ((!key.enabled || rawFallback) && indexCount != 6)) {
                    throw new IOException("invalid mesh cache metadata");
                }
                long expectedBytes = 4L * 5 + keyLength + 1
                        + floatCount * 4L + indexCount * 2L;
                if (fileBytes != expectedBytes) throw new IOException("invalid mesh cache payload size");
                float[] vertices = new float[floatCount];
                for (int i = 0; i < floatCount; i++) {
                    vertices[i] = input.readFloat();
                    if (!Float.isFinite(vertices[i])) throw new IOException("non-finite mesh cache vertex");
                }
                short[] indices = new short[indexCount];
                int vertexCount = floatCount / 4;
                for (int i = 0; i < indexCount; i++) {
                    indices[i] = input.readShort();
                    if (indices[i] < 0 || indices[i] >= vertexCount) {
                        throw new IOException("mesh cache index outside vertex bounds");
                    }
                }
                if (input.read() != -1) throw new IOException("trailing mesh cache data");
                return new Prepared(vertices, indices, rawFallback, 0L, true);
            }
        } catch (IOException | RuntimeException invalid) {
            report("read mesh cache", invalid);
            deleteCorrupt(file, invalid);
            return null;
        }
    }

    private void write(Key key, Prepared prepared) {
        File temporary = null;
        Throwable failure = null;
        try {
            File destination = fileFor(key);
            temporary = File.createTempFile("mesh-", ".tmp", directory);
            try (DataOutputStream output = new DataOutputStream(
                    new BufferedOutputStream(new FileOutputStream(temporary)))) {
                output.writeInt(MAGIC);
                output.writeInt(FORMAT_VERSION);
                output.writeInt(key.bytes.length);
                output.write(key.bytes);
                output.writeBoolean(prepared.rawFallback);
                FloatBuffer vertices = prepared.vertices();
                ShortBuffer indices = prepared.indices();
                output.writeInt(vertices.remaining());
                output.writeInt(indices.remaining());
                while (vertices.hasRemaining()) output.writeFloat(vertices.get());
                while (indices.hasRemaining()) output.writeShort(indices.get());
            }
            try {
                Files.move(temporary.toPath(), destination.toPath(),
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                try {
                    Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException moveFailure) {
                    moveFailure.addSuppressed(unsupported);
                    throw moveFailure;
                }
            }
            temporary = null;
            evictDisk(destination);
        } catch (IOException | RuntimeException error) {
            failure = error;
            report("write mesh cache", error);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary.toPath());
                } catch (IOException | RuntimeException cleanup) {
                    if (failure != null) failure.addSuppressed(cleanup);
                    report("remove temporary mesh cache file", cleanup);
                }
            }
        }
    }

    private boolean prepareDirectory() {
        EnumSet<PosixFilePermission> ownerOnly = EnumSet.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE);
        try {
            if (!Files.isDirectory(directory.toPath())) {
                try {
                    Files.createDirectories(directory.toPath(),
                            PosixFilePermissions.asFileAttribute(ownerOnly));
                } catch (UnsupportedOperationException unsupported) {
                    Files.createDirectories(directory.toPath());
                }
            }
            if (!Files.isDirectory(directory.toPath())) {
                throw new IOException("mesh cache path is not a directory: " + directory);
            }
            try {
                Files.setPosixFilePermissions(directory.toPath(), ownerOnly);
            } catch (UnsupportedOperationException unsupported) {
                // Non-POSIX filesystems cannot apply owner-only POSIX modes.
            }
            return true;
        } catch (IOException | RuntimeException error) {
            report("prepare mesh cache directory", error);
            return false;
        }
    }

    private void evictDisk(File keep) {
        File[] files = directory.listFiles((dir, name) -> name.startsWith("mesh-")
                && name.endsWith(".bin") && name.length() == 73);
        if (files == null) {
            report("list mesh cache", new IOException("failed to list mesh cache directory: " + directory));
            return;
        }
        if (files.length <= DISK_ENTRIES) return;
        Arrays.sort(files, Comparator.comparingLong(File::lastModified).thenComparing(File::getName));
        int remaining = files.length;
        for (File file : files) {
            if (remaining <= DISK_ENTRIES) break;
            if (!file.equals(keep) && file.exists()) {
                if (file.delete()) remaining--;
                else report("evict mesh cache", new IOException("failed to delete mesh cache file: " + file));
            }
        }
    }

    private void deleteCorrupt(File file, Throwable cause) {
        try {
            Files.deleteIfExists(file.toPath());
        } catch (IOException | RuntimeException cleanup) {
            cause.addSuppressed(cleanup);
            report("remove invalid mesh cache", cleanup);
        }
    }

    private File fileFor(Key key) {
        return new File(directory, "mesh-" + key.digest + ".bin");
    }

    private void report(String operation, Throwable error) {
        try {
            diagnostic.accept(operation, error);
        } catch (Throwable listenerFailure) {
            if (listenerFailure != error) error.addSuppressed(listenerFailure);
        }
    }

    private static CompletableFuture<Prepared> failed(Throwable error) {
        CompletableFuture<Prepared> result = new CompletableFuture<>();
        result.completeExceptionally(error);
        return result;
    }

    @Override public void close() {
        synchronized (lock) {
            if (closed) return;
            closed = true;
        }
        worker.shutdown();
    }

    static final class Request {
        final CameraDewarpConfig config;
        final int width;
        final int height;

        Request(CameraDewarpConfig config, int width, int height) {
            if (config == null || width <= 0 || height <= 0) {
                throw new IllegalArgumentException("valid mesh configuration and size are required");
            }
            this.config = config;
            this.width = width;
            this.height = height;
        }
    }

    private final class Work implements Runnable, Comparable<Work> {
        final Key key;
        final CameraDewarpConfig config;
        final int width;
        final int height;
        final CompletableFuture<Prepared> future = new CompletableFuture<>();
        boolean prewarm;
        long sequence;

        Work(Key key, CameraDewarpConfig config, int width, int height,
                boolean prewarm, long sequence) {
            this.key = key;
            this.config = config;
            this.width = width;
            this.height = height;
            this.prewarm = prewarm;
            this.sequence = sequence;
        }

        @Override public void run() {
            synchronized (lock) {
                pendingPrewarm.remove(key, this);
            }
            prepare(this);
        }

        @Override public int compareTo(Work other) {
            if (prewarm != other.prewarm) return prewarm ? 1 : -1;
            return prewarm ? Long.compare(sequence, other.sequence)
                    : Long.compare(other.sequence, sequence);
        }
    }

    static final class Prepared {
        private final FloatBuffer vertices;
        private final ShortBuffer indices;
        final boolean rawFallback;
        final long generationNs;
        final boolean diskCacheHit;

        private Prepared(float[] vertices, short[] indices, boolean rawFallback,
                long generationStartedNs, boolean diskCacheHit) {
            this.vertices = floatBuffer(vertices).asReadOnlyBuffer();
            this.indices = shortBuffer(indices).asReadOnlyBuffer();
            this.rawFallback = rawFallback;
            this.generationNs = diskCacheHit ? 0L : System.nanoTime() - generationStartedNs;
            this.diskCacheHit = diskCacheHit;
        }

        private Prepared(FloatBuffer vertices, ShortBuffer indices, boolean rawFallback,
                long generationNs, boolean diskCacheHit) {
            this.vertices = vertices.asReadOnlyBuffer();
            this.indices = indices.asReadOnlyBuffer();
            this.rawFallback = rawFallback;
            this.generationNs = generationNs;
            this.diskCacheHit = diskCacheHit;
        }

        FloatBuffer vertices() {
            FloatBuffer result = vertices.asReadOnlyBuffer();
            result.position(0);
            return result;
        }

        ShortBuffer indices() {
            ShortBuffer result = indices.asReadOnlyBuffer();
            result.position(0);
            return result;
        }

        boolean rawFallback() { return rawFallback; }
        long generationNs() { return generationNs; }
        boolean diskCacheHit() { return diskCacheHit; }

        Prepared cacheHit() {
            return new Prepared(vertices, indices, rawFallback, 0L, diskCacheHit);
        }

        private static FloatBuffer floatBuffer(float[] values) {
            FloatBuffer result = ByteBuffer.allocateDirect(values.length * 4)
                    .order(ByteOrder.nativeOrder()).asFloatBuffer();
            result.put(values).position(0);
            return result;
        }

        private static ShortBuffer shortBuffer(short[] values) {
            ShortBuffer result = ByteBuffer.allocateDirect(values.length * 2)
                    .order(ByteOrder.nativeOrder()).asShortBuffer();
            result.put(values).position(0);
            return result;
        }
    }

    private static final class Key {
        final byte[] bytes;
        final String digest;
        final int hashCode;
        final boolean enabled;

        Key(CameraDewarpConfig config, int width, int height) {
            enabled = config.enabled;
            bytes = ByteBuffer.allocate(13 * 4)
                    .putInt(ALGORITHM_REVISION)
                    .putInt(CALIBRATION_REVISION)
                    .putInt(config.lens)
                    .putInt(config.enabled ? 1 : 0)
                    .putInt(config.fovDegrees)
                    .putInt(config.projection)
                    .putInt(config.strengthPercent)
                    .putInt(Float.floatToRawIntBits(config.preciseFovDegrees))
                    .putInt(Float.floatToRawIntBits(config.roiCenterX))
                    .putInt(Float.floatToRawIntBits(config.roiCenterY))
                    .putInt(width)
                    .putInt(height)
                    .putInt(FORMAT_VERSION)
                    .array();
            digest = sha256(bytes);
            hashCode = Arrays.hashCode(bytes);
        }

        @Override public boolean equals(Object other) {
            return other instanceof Key && Arrays.equals(bytes, ((Key) other).bytes);
        }

        @Override public int hashCode() { return hashCode; }

        private static String sha256(byte[] values) {
            final byte[] hash;
            try {
                hash = MessageDigest.getInstance("SHA-256").digest(values);
            } catch (NoSuchAlgorithmException impossible) {
                throw new AssertionError("SHA-256 is required by the Java platform", impossible);
            }
            char[] hex = new char[hash.length * 2];
            char[] digits = "0123456789abcdef".toCharArray();
            for (int i = 0; i < hash.length; i++) {
                int value = hash[i] & 0xff;
                hex[i * 2] = digits[value >>> 4];
                hex[i * 2 + 1] = digits[value & 0x0f];
            }
            return new String(hex);
        }
    }
}
