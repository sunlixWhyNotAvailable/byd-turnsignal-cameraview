package com.byd.extend;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;

import android.os.IBinder;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public final class AvasAutoServiceTest {
    @Test public void liveCacheAvoidsLookup() throws Exception {
        IBinder cached = binder(true);
        assertSame(cached, AvasAutoService.resolve(cached, () -> {
            throw new AssertionError("live Binder must be reused");
        }));
    }

    @Test public void missingOrDeadCacheUsesOneLookup() throws Exception {
        IBinder replacement = binder(true);
        AtomicInteger calls = new AtomicInteger();
        for (IBinder cached : new IBinder[]{null, binder(false)}) {
            assertSame(replacement, AvasAutoService.resolve(cached, () -> {
                calls.incrementAndGet();
                return replacement;
            }));
        }
        assertEquals(2, calls.get());
    }

    @Test public void missingServiceKeepsExistingFailure() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> AvasAutoService.resolve(null, () -> null));
        assertEquals("autoservice unavailable", error.getMessage());
    }

    @Test public void lookupFailureIsNotHiddenOrRetried() {
        Exception failure = new ReflectiveOperationException("unavailable API");
        assertSame(failure, assertThrows(Exception.class,
                () -> AvasAutoService.resolve(null, () -> { throw failure; })));
    }

    private static IBinder binder(boolean alive) {
        return (IBinder) Proxy.newProxyInstance(IBinder.class.getClassLoader(),
                new Class<?>[]{IBinder.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("isBinderAlive")) return alive;
                    throw new AssertionError("unexpected Binder call: " + method.getName());
                });
    }
}
