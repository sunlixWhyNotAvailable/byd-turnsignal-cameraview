package com.byd.extend;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ReverseSideSelectorArtworkTest {
    @Test
    public void selectorKeepsMinimalHoodlessCarArtwork() throws Exception {
        Path source = Path.of("app/src/main/java/com/byd/extend/ReverseSideSelectorView.java");
        if (!Files.exists(source)) {
            source = Path.of("src/main/java/com/byd/extend/ReverseSideSelectorView.java");
        }
        String code = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);

        assertFalse(code.contains("drawPath("));
        assertFalse(code.contains("new Path("));
        assertTrue(code.contains("canvas.drawRoundRect(body"));
        assertTrue(code.contains("drawWheel(canvas"));
        assertTrue(code.contains("? \"Перед\" : \"Зад\""));
    }
}
