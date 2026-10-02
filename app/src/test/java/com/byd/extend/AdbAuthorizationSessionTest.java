package com.byd.extend;

import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

public final class AdbAuthorizationSessionTest {
    @Test public void timeoutOrDenialKeepsBudgetUntilNewProcessButManualCanRetry() {
        AdbAuthorizationSession process = new AdbAuthorizationSession();
        try (AdbAuthorizationSession.Attempt ignored = process.begin()) {
            assertTrue(process.claimPrompt("key", false));
        }
        try (AdbAuthorizationSession.Attempt recreatedActivity = process.begin()) {
            assertFalse(process.claimPrompt("key", false));
            assertTrue(process.claimPrompt("key", true));
            assertFalse(process.claimPrompt("key", false));
        }
        assertTrue(new AdbAuthorizationSession().claimPrompt("key", false));
    }

    @Test public void manualFirstConsumesAutomaticBudgetAndNestedRequestsStayPending() {
        AdbAuthorizationSession process = new AdbAuthorizationSession();
        AdbAuthorizationSession.Attempt queued = process.begin();
        AdbAuthorizationSession.Attempt executing = process.begin();
        assertTrue(process.claimPrompt("key", true));
        assertFalse(process.claimPrompt("key", false));
        queued.close(); queued.close();
        assertTrue(process.pending());
        assertNull(process.beginRecovery());
        executing.close();
        assertFalse(process.pending());
        try (AdbAuthorizationSession.RecoveryOperation recovery = process.beginRecovery()) {
            assertNotNull(recovery);
        }
    }

    @Test public void acceptedRsaCancelsExistingRecoveryAndRejectsQueuedWrites() throws Exception {
        AdbAuthorizationSession process = new AdbAuthorizationSession();
        AtomicBoolean closed = new AtomicBoolean();
        AtomicBoolean written = new AtomicBoolean();
        try (AdbAuthorizationSession.RecoveryOperation recovery = process.beginRecovery()) {
            recovery.register(() -> closed.set(true));
            try (AdbAuthorizationSession.Attempt rsa = process.begin()) {
                assertTrue(closed.get());
                assertTrue(recovery.cancelled());
                try { recovery.write(() -> written.set(true)); fail(); }
                catch (IOException expected) { }
                AtomicBoolean lateClosed = new AtomicBoolean();
                try { recovery.register(() -> lateClosed.set(true)); fail(); }
                catch (IOException expected) { }
                assertTrue(lateClosed.get());
            }
            try { recovery.write(() -> written.set(true)); fail(); }
            catch (IOException expected) { }
            assertFalse(written.get());
        }
    }

    @Test public void cancelledRecoveryCannotSendTcpipEvenAfterRsaCompletes() throws Exception {
        AdbAuthorizationSession process = new AdbAuthorizationSession();
        ByteArrayOutputStream sent = new ByteArrayOutputStream();
        try (AdbAuthorizationSession.RecoveryOperation recovery = process.beginRecovery()) {
            process.begin().close();
            try {
                LocalAdbTlsClient.requestTcpip5555(new ByteArrayInputStream(new byte[0]), sent, recovery);
                fail();
            } catch (IOException expected) { }
            assertEquals(0, sent.size());
        }
    }

    @Test public void concurrentKeyCallersReceiveOnePersistedIdentity() throws Exception {
        Path directory = Files.createTempDirectory("byd-adb-keys-");
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<KeyPair>> results = new ArrayList<>();
            for (int i = 0; i < 8; i++) results.add(pool.submit(() -> {
                assertTrue(start.await(10, TimeUnit.SECONDS));
                return LocalAdbClient.loadOrCreateKeys(directory.toFile());
            }));
            start.countDown();
            KeyPair first = results.get(0).get(20, TimeUnit.SECONDS);
            for (Future<KeyPair> future : results) {
                KeyPair pair = future.get(20, TimeUnit.SECONDS);
                assertArrayEquals(first.getPrivate().getEncoded(), pair.getPrivate().getEncoded());
                assertArrayEquals(first.getPublic().getEncoded(), pair.getPublic().getEncoded());
            }
            assertArrayEquals(first.getPrivate().getEncoded(), Files.readAllBytes(directory.resolve("adb_key.priv")));
            assertArrayEquals(first.getPublic().getEncoded(), Files.readAllBytes(directory.resolve("adb_key.pub")));
            KeyPair existing = LocalAdbClient.loadOrCreateKeys(directory.toFile());
            assertArrayEquals(first.getPrivate().getEncoded(), existing.getPrivate().getEncoded());
            Files.write(directory.resolve("adb_key.priv"), new byte[]{1, 2, 3});
            try { LocalAdbClient.loadOrCreateKeys(directory.toFile()); fail(); }
            catch (IOException expected) { }
            assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(directory.resolve("adb_key.priv")));
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
            Files.deleteIfExists(directory.resolve("adb_key.pub"));
            Files.deleteIfExists(directory.resolve("adb_key.priv"));
            Files.delete(directory);
        }
    }
}
