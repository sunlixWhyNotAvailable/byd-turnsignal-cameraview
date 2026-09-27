package com.byd.extend;

import java.io.File;
import java.io.FilenameFilter;
import java.nio.file.Files;
import org.junit.Test;
import static org.junit.Assert.*;

public final class LogClearResultTest {
    @Test
    public void deletesOnlyCaptureLogsAndAcceptsAnEmptyDirectory() throws Exception {
        File directory = Files.createTempDirectory("extend-log-clear-").toFile();
        File capture = new File(directory, "capture.jsonl");
        File unrelated = new File(directory, "preset.json");
        try {
            assertTrue(capture.createNewFile());
            assertTrue(unrelated.createNewFile());
            CameraProbeActivity.LogClearResult result = CameraProbeActivity.deleteCaptureLogFiles(directory);
            assertEquals(1, result.deleted);
            assertEquals(0, result.failed);
            assertEquals(R.string.runtime_logs_cleared, result.messageResource());
            assertFalse(capture.exists());
            assertTrue(unrelated.exists());
            result = CameraProbeActivity.deleteCaptureLogFiles(directory);
            assertEquals(0, result.deleted);
            assertEquals(0, result.failed);
            assertEquals(R.string.runtime_logs_cleared, result.messageResource());
        } finally {
            Files.deleteIfExists(capture.toPath());
            Files.deleteIfExists(unrelated.toPath());
            Files.deleteIfExists(directory.toPath());
        }
    }

    @Test
    public void missingOrUnreadableDirectoryIsNotSuccessfulCleanup() {
        for (File directory : new File[]{null, new File("unreadable") {
            @Override public File[] listFiles(FilenameFilter filter) { return null; }
        }, new File("denied") {
            @Override public File[] listFiles(FilenameFilter filter) { throw new SecurityException(); }
        }}) {
            CameraProbeActivity.LogClearResult result = CameraProbeActivity.deleteCaptureLogFiles(directory);
            assertEquals(0, result.deleted);
            assertEquals(1, result.failed);
            assertEquals(R.string.runtime_logs_clear_failed, result.messageResource());
        }
    }

    @Test
    public void deletionFailuresDoNotSkipRemainingFilesAndClassifyPartialFailure() {
        File directory = new File("mixed") {
            @Override public File[] listFiles(FilenameFilter filter) {
                return new File[]{new File("denied.jsonl") {
                    @Override public boolean delete() { throw new SecurityException(); }
                }, new File("busy.jsonl") {
                    @Override public boolean delete() { return false; }
                }, new File("deleted.jsonl") {
                    @Override public boolean delete() { return true; }
                }};
            }
        };
        CameraProbeActivity.LogClearResult result = CameraProbeActivity.deleteCaptureLogFiles(directory);
        assertEquals(1, result.deleted);
        assertEquals(2, result.failed);
        assertEquals(R.string.runtime_logs_clear_partial, result.messageResource());
        result.deleted = 0;
        assertEquals(R.string.runtime_logs_clear_failed, result.messageResource());
    }
}
