package com.byd.turnsignalguard.capture;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public final class CameraPresetFilesTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test public void sharesCompleteUtf8JsonOnlyInsidePresetCache() throws Exception {
        String json = "{\"label\":\"Передня ліва\",\"x\":0.123456789}";
        File first = CameraPresetFiles.write(temporary.getRoot(), json);
        File second = CameraPresetFiles.write(temporary.getRoot(), json);
        assertEquals(new File(temporary.getRoot(), "shared_presets"), first.getParentFile());
        assertTrue(first.getName().startsWith("byd-extend-camera-preset-"));
        assertTrue(first.getName().endsWith(".json"));
        assertFalse(first.equals(second));
        try (FileInputStream input = new FileInputStream(first)) {
            assertEquals(json, CameraPresetFiles.read(input));
        }
    }

    @Test public void boundsInputBeforeParsingAndRejectsInvalidEncoding() throws Exception {
        byte[] maximum = new byte[CameraSettingsTransfer.MAX_INPUT_BYTES];
        java.util.Arrays.fill(maximum, (byte) ' ');
        assertEquals(maximum.length,
                CameraPresetFiles.read(new ByteArrayInputStream(maximum)).length());
        assertThrows(IOException.class, () -> CameraPresetFiles.read(
                new ByteArrayInputStream(new byte[maximum.length + 1])));
        assertThrows(IOException.class, () -> CameraPresetFiles.read(
                new ByteArrayInputStream(new byte[]{(byte) 0xc3})));
        assertThrows(IOException.class, () -> CameraPresetFiles.read(null));
        assertEquals("{}", CameraPresetFiles.read(new ByteArrayInputStream(
                "{}".getBytes(StandardCharsets.UTF_8))));
    }

    @Test public void oldUiCannotPersistFieldsDuringImportOrReload() {
        assertTrue(CameraProbeActivity.shouldPersistEditableSettings(false, false, false));
        assertFalse(CameraProbeActivity.shouldPersistEditableSettings(true, false, false));
        assertFalse(CameraProbeActivity.shouldPersistEditableSettings(false, true, false));
        assertFalse(CameraProbeActivity.shouldPersistEditableSettings(false, false, true));
    }
}
