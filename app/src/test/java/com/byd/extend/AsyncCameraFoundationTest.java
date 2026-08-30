package com.byd.extend;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class AsyncCameraFoundationTest {
    @Test
    public void rendererCallbackRequiresExactGenerationTextureAndRenderer() {
        Object texture = new Object();
        Object renderer = new Object();

        assertTrue(BlindSpotCameraView.isCurrentRendererCallback(
                7, 7, texture, texture, renderer, renderer));
        assertFalse(BlindSpotCameraView.isCurrentRendererCallback(
                6, 7, texture, texture, renderer, renderer));
        assertFalse(BlindSpotCameraView.isCurrentRendererCallback(
                7, 7, texture, new Object(), renderer, renderer));
        assertFalse(BlindSpotCameraView.isCurrentRendererCallback(
                7, 7, texture, texture, renderer, new Object()));
    }

    @Test
    public void cameraFoundationSourceContainsNoBlockingLifecycleWaits()
            throws Exception {
        String renderer = readMainSource("CameraDewarpRenderer.java");
        String view = readMainSource("BlindSpotCameraView.java");

        assertTrue(renderer.contains("startAsync("));
        assertTrue(renderer.contains("releaseAsync()"));
        assertFalse(renderer.contains("CountDownLatch"));
        assertFalse(renderer.contains(".await("));
        assertTrue(view.contains("deferredReleaseTexture"));
    }

    private static String readMainSource(String name) throws Exception {
        Path path = Paths.get("src/main/java/com/byd/extend", name);
        if (!Files.exists(path)) path = Paths.get("app/src/main/java/com/byd/extend", name);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
