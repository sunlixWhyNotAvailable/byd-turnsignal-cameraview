package com.byd.extend;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.FloatBuffer;
import java.nio.ReadOnlyBufferException;
import java.nio.ShortBuffer;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class CameraMeshCacheTest {
    @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test public void preparedMeshMatchesProductionMathAndReturnsIndependentReadOnlyViews()
            throws Exception {
        CameraDewarpConfig config = calibratedConfig();
        CameraFisheyeMapping.Mesh expected = CameraFisheyeMapping.buildMesh(config, 527, 357);
        List<Throwable> diagnostics = Collections.synchronizedList(new ArrayList<>());
        try (CameraMeshCache cache = new CameraMeshCache(
                temporaryFolder.newFolder("mesh"), (operation, error) -> diagnostics.add(error))) {
            CameraMeshCache.Prepared prepared = cache.request(config, 527, 357)
                    .get(30, TimeUnit.SECONDS);

            assertFalse(prepared.rawFallback());
            assertTrue(prepared.generationNs() >= 0);
            assertArrayEquals(expected.vertices, floats(prepared.vertices()), 0f);
            assertArrayEquals(expected.indices, shorts(prepared.indices()));
            assertEquals(0L, cache.request(config, 527, 357)
                    .get(30, TimeUnit.SECONDS).generationNs());

            FloatBuffer first = prepared.vertices();
            FloatBuffer second = prepared.vertices();
            first.get();
            assertTrue(first.isReadOnly());
            assertEquals(1, first.position());
            assertEquals(0, second.position());
            org.junit.Assert.assertThrows(ReadOnlyBufferException.class, () -> first.put(0, 0f));
            assertTrue(diagnostics.isEmpty());
        }
    }

    @Test public void diskReopenReusesExactMeshAndDifferentKeysDoNotHitIt() throws Exception {
        File directory = temporaryFolder.newFolder("persistent");
        CameraDewarpConfig config = calibratedConfig();
        List<Throwable> diagnostics = Collections.synchronizedList(new ArrayList<>());
        try (CameraMeshCache first = new CameraMeshCache(directory,
                (operation, error) -> diagnostics.add(error))) {
            assertFalse(first.request(config, 527, 357).get(30, TimeUnit.SECONDS).diskCacheHit());
        }

        try (CameraMeshCache reopened = new CameraMeshCache(directory,
                (operation, error) -> diagnostics.add(error))) {
            CameraMeshCache.Prepared persisted = reopened.request(config, 527, 357)
                    .get(30, TimeUnit.SECONDS);
            assertTrue(persisted.diskCacheHit());
            assertEquals(0L, persisted.generationNs());
            CameraFisheyeMapping.Mesh expected = CameraFisheyeMapping.buildMesh(config, 527, 357);
            assertArrayEquals(expected.vertices, floats(persisted.vertices()), 0f);
            assertArrayEquals(expected.indices, shorts(persisted.indices()));

            CameraDewarpConfig[] changedMappings = {
                    config.withHorizontalFov(124.25f),
                    config.withProjection(CameraDewarpConfig.PROJECTION_CYLINDRICAL),
                    config.withStrength(71),
                    config.withRoiCenter(0.53f, 0.48f),
                    CameraDewarpConfig.of(CameraDewarpConfig.LENS_RIGHT, true, 123,
                            CameraDewarpConfig.PROJECTION_RECTILINEAR, 68, 123.25f)
                            .withRoiCenter(0.48f, 0.52f)
            };
            for (CameraDewarpConfig changed : changedMappings) {
                assertFalse(reopened.request(changed, 527, 357)
                        .get(30, TimeUnit.SECONDS).diskCacheHit());
            }
            assertFalse(reopened.request(config, 528, 357)
                    .get(30, TimeUnit.SECONDS).diskCacheHit());
            assertFalse(reopened.request(CameraDewarpConfig.disabled(config.lens), 527, 357)
                    .get(30, TimeUnit.SECONDS).diskCacheHit());
        }
        assertTrue(diagnostics.isEmpty());
    }

    @Test public void concurrentIdenticalRequestsShareOneInFlightFuture() throws Exception {
        CameraDewarpConfig expensive = CameraDewarpConfig.of(
                CameraDewarpConfig.LENS_LEFT, true, 126,
                CameraDewarpConfig.PROJECTION_RECTILINEAR, 1, 126.25f);
        File directory = temporaryFolder.newFolder("coalesced");
        List<String> seedDiagnostics = Collections.synchronizedList(new ArrayList<>());
        try (CameraMeshCache seed = new CameraMeshCache(directory,
                (operation, error) -> seedDiagnostics.add(operation))) {
            seed.request(expensive, 527, 357).get(30, TimeUnit.SECONDS);
        }
        assertTrue(seedDiagnostics.isEmpty());
        File[] entries = directory.listFiles((dir, name) -> name.endsWith(".bin"));
        assertTrue(entries != null && entries.length == 1);
        try (FileOutputStream truncated = new FileOutputStream(entries[0])) {
            truncated.write(new byte[]{'C', 'M', 'S', 'H'});
        }
        CountDownLatch readFailed = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        List<String> diagnostics = Collections.synchronizedList(new ArrayList<>());
        try (CameraMeshCache cache = new CameraMeshCache(directory, (operation, error) -> {
            diagnostics.add(operation);
            if (!"read mesh cache".equals(operation)) return;
            readFailed.countDown();
            try {
                resume.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        })) {
            try {
                CompletableFuture<CameraMeshCache.Prepared> first = cache.request(expensive, 527, 357);
                assertTrue(readFailed.await(30, TimeUnit.SECONDS));
                CompletableFuture<CameraMeshCache.Prepared> second = cache.request(expensive, 527, 357);
                assertSame(first, second);
                resume.countDown();
                assertFalse(first.get(30, TimeUnit.SECONDS).rawFallback());
            } finally {
                resume.countDown();
            }
        }
        assertEquals(Collections.singletonList("read mesh cache"), diagnostics);
    }

    @Test public void truncatedCacheRebuildsAndCalibratedDomainUsesRawFallback() throws Exception {
        File directory = temporaryFolder.newFolder("recovery");
        CameraDewarpConfig outsideCalibration = CameraDewarpConfig.of(
                CameraDewarpConfig.LENS_LEFT, true, 130).withRoiCenter(0f, 0f);
        try {
            CameraFisheyeMapping.buildMesh(outsideCalibration, 527, 357);
            throw new AssertionError("expected calibrated-domain rejection");
        } catch (CameraFisheyeMapping.CalibratedDomainException expected) {
            // Confirms this is the production fallback path under test.
        }
        List<String> fallbackOperations = Collections.synchronizedList(new ArrayList<>());
        try (CameraMeshCache first = new CameraMeshCache(directory,
                (operation, error) -> fallbackOperations.add(operation))) {
            assertTrue(first.request(outsideCalibration, 527, 357)
                    .get(30, TimeUnit.SECONDS).rawFallback());
        }
        assertTrue(fallbackOperations.contains("calibrated mesh unavailable; using raw fallback"));
        File[] entries = directory.listFiles((dir, name) -> name.endsWith(".bin"));
        assertTrue(entries != null && entries.length == 1);
        try (FileOutputStream truncated = new FileOutputStream(entries[0])) {
            truncated.write(new byte[]{'C', 'M', 'S', 'H'});
        }

        List<String> operations = Collections.synchronizedList(new ArrayList<>());
        try (CameraMeshCache repaired = new CameraMeshCache(directory,
                (operation, error) -> operations.add(operation))) {
            CameraMeshCache.Prepared rebuilt = repaired.request(outsideCalibration, 527, 357)
                    .get(30, TimeUnit.SECONDS);
            assertFalse(rebuilt.diskCacheHit());
            assertTrue(rebuilt.rawFallback());
            CameraFisheyeMapping.Mesh identity = CameraFisheyeMapping.buildMesh(
                    CameraDewarpConfig.disabled(outsideCalibration.lens), 527, 357);
            assertArrayEquals(identity.vertices, floats(rebuilt.vertices()), 0f);
            assertArrayEquals(identity.indices, shorts(rebuilt.indices()));
            assertTrue(operations.contains("read mesh cache"));
        }
        List<String> reopenedDiagnostics = Collections.synchronizedList(new ArrayList<>());
        try (CameraMeshCache reopened = new CameraMeshCache(directory,
                (operation, error) -> reopenedDiagnostics.add(operation))) {
            CameraMeshCache.Prepared persistedFallback = reopened.request(outsideCalibration, 527, 357)
                    .get(30, TimeUnit.SECONDS);
            assertTrue(persistedFallback.diskCacheHit());
            assertTrue(persistedFallback.rawFallback());
        }
        assertTrue(reopenedDiagnostics.isEmpty());
    }

    @Test public void ramAndPersistentEntriesStayBounded() throws Exception {
        File directory = new File(temporaryFolder.getRoot(), "bounded");
        assertFalse(directory.exists());
        CameraDewarpConfig raw = CameraDewarpConfig.disabled(CameraDewarpConfig.LENS_LEFT);
        List<String> diagnostics = Collections.synchronizedList(new ArrayList<>());
        try (CameraMeshCache cache = new CameraMeshCache(directory,
                (operation, error) -> diagnostics.add(operation))) {
            for (int width = 1; width <= 33; width++) {
                cache.request(raw, width, 100).get(30, TimeUnit.SECONDS);
            }
            Set<PosixFilePermission> permissions = null;
            try {
                permissions = Files.getPosixFilePermissions(directory.toPath());
            } catch (UnsupportedOperationException unsupported) {
                // This filesystem cannot expose POSIX permissions.
            }
            if (permissions != null) {
                assertEquals(EnumSet.of(PosixFilePermission.OWNER_READ,
                                PosixFilePermission.OWNER_WRITE,
                                PosixFilePermission.OWNER_EXECUTE),
                        permissions);
            }
            assertTrue(cache.request(raw, 1, 100)
                    .get(30, TimeUnit.SECONDS).diskCacheHit());
            for (int width = 34; width <= 65; width++) {
                cache.request(raw, width, 100).get(30, TimeUnit.SECONDS);
            }
            File[] entries = directory.listFiles((dir, name) -> name.startsWith("mesh-")
                    && name.endsWith(".bin"));
            assertTrue(entries != null && entries.length == 64);
        }
        assertTrue(diagnostics.isEmpty());
    }

    @Test public void newestLiveRequestsPrecedeReplaceablePrewarmWork() throws Exception {
        File directory = temporaryFolder.newFolder("priority");
        CameraDewarpConfig raw = CameraDewarpConfig.disabled(CameraDewarpConfig.LENS_LEFT);
        CameraMeshCache.Request a = new CameraMeshCache.Request(raw, 10, 100);
        CameraMeshCache.Request b = new CameraMeshCache.Request(raw, 11, 100);
        CameraMeshCache.Request c = new CameraMeshCache.Request(raw, 12, 100);
        CameraMeshCache.Request d = new CameraMeshCache.Request(raw, 13, 100);
        CameraMeshCache.Request e = new CameraMeshCache.Request(raw, 14, 100);
        Map<Integer, File> files = new HashMap<>();
        Set<String> knownFiles = new HashSet<>();
        List<String> seedDiagnostics = Collections.synchronizedList(new ArrayList<>());
        try (CameraMeshCache seed = new CameraMeshCache(directory,
                (operation, error) -> seedDiagnostics.add(operation))) {
            for (CameraMeshCache.Request request : Arrays.asList(a, b, c, d, e)) {
                seed.request(request.config, request.width, request.height).get(30, TimeUnit.SECONDS);
                File[] entries = directory.listFiles((dir, name) -> name.endsWith(".bin"));
                assertTrue(entries != null);
                for (File entry : entries) {
                    if (knownFiles.add(entry.getName())) files.put(request.width, entry);
                }
            }
        }
        assertTrue(seedDiagnostics.isEmpty());
        assertTrue(files.containsKey(a.width));
        assertTrue(files.containsKey(b.width));
        assertTrue(files.containsKey(c.width));
        assertTrue(files.containsKey(d.width));
        assertTrue(files.containsKey(e.width));
        writeCacheBytes(files.get(a.width), new byte[]{0, 0, 0, 0});
        writeCacheBytes(files.get(b.width), new byte[]{0, 0, 0, 0});
        writeCacheBytes(files.get(c.width), new byte[]{'C', 'M', 'S', 'H', 0, 0, 0, 99});
        writeCacheBytes(files.get(d.width), new byte[]{
                'C', 'M', 'S', 'H', 0, 0, 0, 1, 0, 0, 0, 0});

        CountDownLatch aRead = new CountDownLatch(1);
        CountDownLatch releaseA = new CountDownLatch(1);
        CountDownLatch dRead = new CountDownLatch(1);
        AtomicInteger invalidMagicReads = new AtomicInteger();
        AtomicBoolean cRanBeforeB = new AtomicBoolean();
        AtomicBoolean bRanAfterC = new AtomicBoolean();
        AtomicBoolean dRanAfterLive = new AtomicBoolean();
        AtomicReference<CompletableFuture<CameraMeshCache.Prepared>> bLive = new AtomicReference<>();
        AtomicReference<CompletableFuture<CameraMeshCache.Prepared>> cLive = new AtomicReference<>();
        List<String> diagnostics = Collections.synchronizedList(new ArrayList<>());
        try (CameraMeshCache cache = new CameraMeshCache(directory, (operation, error) -> {
            diagnostics.add(operation + ":" + error.getMessage());
            if (!"read mesh cache".equals(operation)) return;
            String message = error.getMessage();
            if ("invalid mesh cache magic".equals(message)) {
                if (invalidMagicReads.getAndIncrement() == 0) {
                    aRead.countDown();
                    try {
                        releaseA.await();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                } else {
                    bRanAfterC.set(cLive.get().isDone());
                }
            } else if ("unsupported mesh cache format".equals(message)) {
                cRanBeforeB.set(!bLive.get().isDone());
            } else if ("invalid mesh cache key length".equals(message)) {
                dRanAfterLive.set(bLive.get().isDone() && cLive.get().isDone());
                dRead.countDown();
            }
        })) {
            try {
                List<CompletableFuture<CameraMeshCache.Prepared>> initial = cache.replacePrewarm(
                        Arrays.asList(a, b, c, e));
                assertEquals(4, initial.size());
                assertTrue(aRead.await(30, TimeUnit.SECONDS));
                assertFalse(initial.get(0).isDone()); // The running warm request may finish.

                CompletableFuture<CameraMeshCache.Prepared> latestB = cache.request(
                        b.config, b.width, b.height);
                bLive.set(latestB);
                assertSame(initial.get(1), latestB);
                CompletableFuture<CameraMeshCache.Prepared> latestC = cache.request(
                        c.config, c.width, c.height);
                cLive.set(latestC);
                assertSame(initial.get(2), latestC);

                List<CompletableFuture<CameraMeshCache.Prepared>> replacement =
                        cache.replacePrewarm(Collections.singletonList(d));
                assertEquals(1, replacement.size());
                assertTrue(initial.get(3).isCancelled());
                assertFalse(initial.get(1).isCancelled());
                assertFalse(initial.get(2).isCancelled());

                releaseA.countDown();
                assertFalse(initial.get(0).get(30, TimeUnit.SECONDS).rawFallback());
                assertFalse(latestC.get(30, TimeUnit.SECONDS).rawFallback());
                assertFalse(latestB.get(30, TimeUnit.SECONDS).rawFallback());
                assertTrue(dRead.await(30, TimeUnit.SECONDS));
                assertFalse(replacement.get(0).get(30, TimeUnit.SECONDS).rawFallback());
            } finally {
                releaseA.countDown();
            }
        }
        assertTrue(cRanBeforeB.get());
        assertTrue(bRanAfterC.get());
        assertTrue(dRanAfterLive.get());
        assertEquals(Arrays.asList(
                "read mesh cache:invalid mesh cache magic",
                "read mesh cache:unsupported mesh cache format",
                "read mesh cache:invalid mesh cache magic",
                "read mesh cache:invalid mesh cache key length"), diagnostics);
    }

    private static CameraDewarpConfig calibratedConfig() {
        return CameraDewarpConfig.of(CameraDewarpConfig.LENS_LEFT, true, 123,
                CameraDewarpConfig.PROJECTION_RECTILINEAR, 68, 123.25f)
                .withRoiCenter(0.48f, 0.52f);
    }

    private static void writeCacheBytes(File file, byte[] bytes) throws Exception {
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(bytes);
        }
    }

    private static float[] floats(FloatBuffer buffer) {
        float[] values = new float[buffer.remaining()];
        buffer.get(values);
        return values;
    }

    private static short[] shorts(ShortBuffer buffer) {
        short[] values = new short[buffer.remaining()];
        buffer.get(values);
        return values;
    }
}
