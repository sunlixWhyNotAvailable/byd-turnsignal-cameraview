package com.byd.extend;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public class BlindHelperAttachmentTest {
    @Test public void shutdownBeforeDispatchDoesNotResurrectOldHelper() {
        List<String> effects = new ArrayList<>();
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        BlindSpotOverlayController.PendingHelperAttachment attachment =
                new BlindSpotOverlayController.PendingHelperAttachment(
                        () -> effects.add("old-helper-attached"));
        queue.add(attachment);
        queue.add(() -> effects.add("unrelated-controller"));
        attachment.cancel();
        // Also covers a callback already dequeued when removeCallbacks is attempted.
        while (!queue.isEmpty()) queue.remove().run();
        assertEquals(List.of("unrelated-controller"), effects);
    }

    @Test public void replacementInvalidatesOnlyItsPredecessor() {
        List<String> helpers = new ArrayList<>();
        BlindSpotOverlayController.PendingHelperAttachment old =
                new BlindSpotOverlayController.PendingHelperAttachment(() -> helpers.add("old"));
        BlindSpotOverlayController.PendingHelperAttachment current =
                new BlindSpotOverlayController.PendingHelperAttachment(() -> helpers.add("current"));
        old.cancel();
        old.run();
        current.run();
        assertEquals(List.of("current"), helpers);
    }

    @Test public void acceptedAttachmentRunsOnceAndCancellationIsIdempotent() {
        int[] count = {0};
        BlindSpotOverlayController.PendingHelperAttachment attachment =
                new BlindSpotOverlayController.PendingHelperAttachment(() -> count[0]++);
        attachment.run();
        attachment.run();
        attachment.cancel();
        attachment.cancel();
        attachment.run();
        assertEquals(1, count[0]);
    }

    @Test public void controllerUsesGuardedAttachmentAndOwnCallbackCancellation() throws Exception {
        Path source = Path.of("src/main/java/com/byd/extend/BlindSpotOverlayController.java");
        if (!Files.exists(source)) source = Path.of("app").resolve(source);
        String text = new String(Files.readAllBytes(source), StandardCharsets.UTF_8)
                .replace("\r\n", "\n");
        String attach = text.substring(text.indexOf("void attachHelper("),
                text.indexOf("void setSuspended("));
        assertTrue(attach.contains("if (shutdown) return;"));
        assertTrue(attach.contains("new PendingHelperAttachment(() -> {\n            if (shutdown) return;"));
        assertTrue(attach.contains("handler.removeCallbacks(pendingHelperAttachment)"));
        assertFalse(attach.contains("removeCallbacksAndMessages"));
        String teardown = text.substring(text.indexOf("void shutdown()"),
                text.indexOf("private String cameraRetryBlockReason()"));
        assertTrue(teardown.indexOf("cancelHelperAttachment();") > teardown.indexOf("shutdown = true;"));
        assertTrue(teardown.indexOf("cancelHelperAttachment();") < teardown.indexOf("destroyAll("));
    }
}
