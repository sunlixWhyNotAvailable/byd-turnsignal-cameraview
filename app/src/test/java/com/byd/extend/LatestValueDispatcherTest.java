package com.byd.extend;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import static org.junit.Assert.*;

public class LatestValueDispatcherTest {
    @Test public void blockedRealExecutorKeepsOnlyLatestAndYieldsToControl() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch blocked = new CountDownLatch(1), release = new CountDownLatch(1), delivered = new CountDownLatch(1);
        List<Double> received = new CopyOnWriteArrayList<>();
        try {
            executor.execute(() -> { blocked.countDown(); await(release); });
            assertTrue(blocked.await(3,TimeUnit.SECONDS));
            LatestValueDispatcher<AdasDistanceModel.Obstacle> queue = new LatestValueDispatcher<>(executor,(box,token) -> {
                received.add(AdasDistanceModel.distances(Collections.singletonList(box))[4]);
                delivered.countDown();
            });
            for(int i=0;i<100;i++) queue.offer(new AdasDistanceModel.Obstacle(-120.25-i,-.75,10,10,0));
            assertEquals(99,queue.replaced());
            release.countDown();
            assertTrue(delivered.await(3,TimeUnit.SECONDS));
            assertEquals(Collections.singletonList(129.0),received);
        } finally { release.countDown(); executor.shutdownNow(); assertTrue(executor.awaitTermination(3,TimeUnit.SECONDS)); }
    }

    @Test public void controlInvalidatesInFlightAndPendingThenRunsBeforeNewData() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch decoding = new CountDownLatch(1), release = new CountDownLatch(1), delivered = new CountDownLatch(1);
        List<String> output = new CopyOnWriteArrayList<>();
        AtomicReference<LatestValueDispatcher<String>> reference = new AtomicReference<>();
        try {
            LatestValueDispatcher<String> queue = new LatestValueDispatcher<>(executor,(value,token) -> {
                if(value.equals("old")) { decoding.countDown(); await(release); }
                if(reference.get().current(token)) { output.add(value); delivered.countDown(); }
            });
            reference.set(queue);
            queue.offer("old"); assertTrue(decoding.await(3,TimeUnit.SECONDS));
            queue.offer("pending-old");
            queue.control(() -> output.add("disconnected"));
            queue.offer("new"); release.countDown();
            assertTrue(delivered.await(3,TimeUnit.SECONDS));
            assertEquals(Arrays.asList("disconnected","new"),output);
        } finally { release.countDown(); executor.shutdownNow(); assertTrue(executor.awaitTermination(3,TimeUnit.SECONDS)); }
    }

    @Test public void queuedOldDrainCannotOvertakeControlWithNewData() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch release = new CountDownLatch(1), delivered = new CountDownLatch(1);
        List<String> output = new CopyOnWriteArrayList<>();
        try {
            executor.execute(() -> await(release));
            LatestValueDispatcher<String> queue = new LatestValueDispatcher<>(executor,(value,token) -> {
                output.add(value); delivered.countDown();
            });
            queue.offer("old"); queue.control(() -> output.add("stop")); queue.offer("new");
            release.countDown(); assertTrue(delivered.await(3,TimeUnit.SECONDS));
            assertEquals(Arrays.asList("stop","new"),output);
        } finally { release.countDown(); executor.shutdownNow(); assertTrue(executor.awaitTermination(3,TimeUnit.SECONDS)); }
    }
    private static void await(CountDownLatch latch) {
        try { if(!latch.await(3,TimeUnit.SECONDS)) throw new AssertionError("test executor did not unblock"); }
        catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }
}
