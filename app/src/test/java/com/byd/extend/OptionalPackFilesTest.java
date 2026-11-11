package com.byd.extend;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;
import static org.junit.Assert.*;

/** Real ZIP/digest/filesystem behavior; no network, helper or completion substitutes. */
public class OptionalPackFilesTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void installationReopensAndBadReplacementCannotDamageIt() throws Exception {
        byte[] bytes = zip("sample.txt", "pack content");
        OptionalPackCatalog.Pack pack = description(bytes, "sample.txt", 12);
        File root = temporary.newFolder();
        OptionalPackFiles.install(root, pack, new ByteArrayInputStream(bytes), () -> false);
        assertTrue(OptionalPackFiles.ready(new File(root.getPath()), pack));
        assertEquals("pack content", new String(Files.readAllBytes(new File(OptionalPackFiles.content(root, pack), "sample.txt").toPath()), StandardCharsets.UTF_8));
        byte[] damaged = bytes.clone(); damaged[damaged.length / 2] ^= 1;
        assertThrows(IOException.class, () -> OptionalPackFiles.install(root, pack,
                new ByteArrayInputStream(damaged), () -> false));
        assertTrue(OptionalPackFiles.ready(root, pack));
        assertArrayEquals(bytes, Files.readAllBytes(OptionalPackFiles.archive(root, pack).toPath()));
        assertEquals(1, Objects.requireNonNull(root.list()).length);
    }

    @Test public void allowedDigestDoesNotPermitTraversalOrUndeclaredFiles() throws Exception {
        for (String name : List.of("../escape.txt", "unexpected.txt")) {
            byte[] bytes = zip(name, "pack content");
            OptionalPackCatalog.Pack pack = description(bytes, "sample.txt", 12);
            File root = temporary.newFolder();
            assertThrows(IOException.class, () -> OptionalPackFiles.install(root, pack,
                    new ByteArrayInputStream(bytes), () -> false));
            assertEquals(0, Objects.requireNonNull(root.list()).length);
            assertFalse(new File(root.getParentFile(), "escape.txt").exists());
        }
    }

    @Test public void cancelledInstallLeavesNoPartialPackage() throws Exception {
        byte[] bytes = zip("sample.txt", "pack content");
        OptionalPackCatalog.Pack pack = description(bytes, "sample.txt", 12);
        File root = temporary.newFolder();
        Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedIOException.class, () -> OptionalPackFiles.install(root, pack,
                    new ByteArrayInputStream(bytes), () -> Thread.currentThread().isInterrupted()));
        } finally { Thread.interrupted(); }
        assertFalse(OptionalPackFiles.ready(root, pack));
        assertEquals(0, Objects.requireNonNull(root.list()).length);
        OptionalPackFiles.install(root, pack, new ByteArrayInputStream(bytes), () -> false);
        assertTrue(OptionalPackFiles.ready(root, pack));
    }

    private static byte[] zip(String name, String value) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry(name)); zip.write(value.getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
        }
        return bytes.toByteArray();
    }
    private static OptionalPackCatalog.Pack description(byte[] zip, String expected, int size) throws Exception {
        StringBuilder hash = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(zip)) hash.append(String.format(Locale.ROOT, "%02x", b & 255));
        return new OptionalPackCatalog.Pack("font", "sample", "fonts-v1", "sample.zip", hash.toString(),
                zip.length, size, new String[] {expected}, new long[] {size});
    }
}
