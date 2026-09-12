package com.byd.extend;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.Random;

import static org.junit.Assert.*;

public final class ArchiveDocumentWriterTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test public void copyPreservesEveryByteAcrossBufferBoundaries() throws Exception {
        byte[] bytes = new byte[150_003];
        new Random(42).nextBytes(bytes);
        File archive = temporary.newFile("archive.zip");
        Files.write(archive.toPath(), bytes);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertEquals(bytes.length, ArchiveDocumentWriter.copy(archive, output));
        assertArrayEquals(bytes, output.toByteArray());
    }

    @Test public void restoreAcceptsOnlyExistingZipInExportCache() throws Exception {
        File cache = temporary.newFolder("cache");
        File exports = new File(cache, "shared_logs");
        assertTrue(exports.mkdir());
        File archive = new File(exports, "archive.zip");
        Files.write(archive.toPath(), new byte[]{1});
        assertEquals(archive, ArchiveDocumentWriter.restore(cache, "archive.zip"));
        assertNull(ArchiveDocumentWriter.restore(cache, null));
        assertNull(ArchiveDocumentWriter.restore(cache, "../archive.zip"));
        assertNull(ArchiveDocumentWriter.restore(cache, archive.getAbsolutePath()));
        assertNull(ArchiveDocumentWriter.restore(cache, "missing.zip"));
        assertNull(ArchiveDocumentWriter.restore(cache, "archive.txt"));
    }

    @Test public void unavailableProviderAndWriteFailureCannotReportSuccess() throws Exception {
        File archive = temporary.newFile("archive.zip");
        Files.write(archive.toPath(), new byte[]{1, 2, 3});
        try {
            ArchiveDocumentWriter.copy(archive, null);
            fail("No stream must fail");
        } catch (IOException expected) { }
        IOException denied = new IOException("USB detached");
        try {
            ArchiveDocumentWriter.copy(archive, new OutputStream() {
                @Override public void write(int value) throws IOException { throw denied; }
            });
            fail("A failed write must fail");
        } catch (IOException expected) { assertSame(denied, expected); }
    }

    @Test public void interruptedCopyDoesNotWriteAnyMoreBytes() throws Exception {
        File archive = temporary.newFile("archive.zip");
        Files.write(archive.toPath(), new byte[]{1, 2, 3});
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Thread.currentThread().interrupt();
        try {
            ArchiveDocumentWriter.copy(archive, output);
            fail("Destroyed Activity interrupts its export executor");
        } catch (InterruptedIOException expected) {
            assertEquals(0, output.size());
        } finally { Thread.interrupted(); }
    }
}
