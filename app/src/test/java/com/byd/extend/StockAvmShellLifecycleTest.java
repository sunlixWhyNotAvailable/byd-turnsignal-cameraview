package com.byd.extend;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

public final class StockAvmShellLifecycleTest {
    @Test
    public void postOpenHandoffFailureClosesCommittedPreviewAndPreservesError() {
        AtomicInteger closes = new AtomicInteger();
        Exception handoffFailure = new Exception("input unavailable");
        Exception cleanupFailure = new Exception("terminate failed");

        Exception thrown = null;
        try {
            StockAvmShellMain.openOwned(new StockAvmShellMain.OpenLifecycle<Object>() {
                @Override public boolean open() { return true; }
                @Override public Object handoff() throws Exception { throw handoffFailure; }
                @Override public void close() throws Exception {
                    closes.incrementAndGet();
                    throw cleanupFailure;
                }
            });
        } catch (Exception expected) {
            thrown = expected;
        }

        assertSame(handoffFailure, thrown);
        assertEquals(1, closes.get());
        assertEquals(1, thrown.getSuppressed().length);
        assertSame(cleanupFailure, thrown.getSuppressed()[0]);
    }

    @Test
    public void successfulHandoffKeepsPreviewOpen() throws Exception {
        Object input = new Object();
        AtomicInteger closes = new AtomicInteger();

        StockAvmShellMain.OpenResult<Object> result = StockAvmShellMain.openOwned(
                new StockAvmShellMain.OpenLifecycle<Object>() {
                    @Override public boolean open() { return false; }
                    @Override public Object handoff() { return input; }
                    @Override public void close() { closes.incrementAndGet(); }
                });

        assertEquals(false, result.initialized);
        assertSame(input, result.value);
        assertEquals(0, closes.get());
    }

    @Test
    public void failedOpenKeepsCleanupWithTheOpeningOperation() {
        AtomicInteger releases = new AtomicInteger();
        AtomicInteger closes = new AtomicInteger();
        Exception openFailure = new Exception("initialize failed");

        Exception thrown = null;
        try {
            StockAvmShellMain.openOwned(new StockAvmShellMain.OpenLifecycle<Object>() {
                @Override public boolean open() throws Exception {
                    releases.incrementAndGet();
                    throw openFailure;
                }
                @Override public Object handoff() { return new Object(); }
                @Override public void close() { closes.incrementAndGet(); }
            });
        } catch (Exception expected) {
            thrown = expected;
            StockAvmShellMain.releaseBeforeTransfer(true, releases::incrementAndGet);
        }

        assertSame(openFailure, thrown);
        assertEquals(1, releases.get());
        assertEquals(0, closes.get());
    }

    @Test
    public void failureBeforeTaskTransferReleasesCallerOwnedSurface() {
        AtomicInteger releases = new AtomicInteger();

        StockAvmShellMain.releaseBeforeTransfer(false, releases::incrementAndGet);

        assertEquals(1, releases.get());
    }
}
