package com.byd.extend;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.util.zip.ZipInputStream;
import java.io.ByteArrayInputStream;
import static org.junit.Assert.*;

public class SentryExportEvidenceTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void streamedArchiveHashesActualBytes() throws Exception {
        File source = temporary.newFile("lib.so");
        Files.write(source.toPath(), "abc".getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("remote/system/app/Sentry/lib/arm64/lib.so"));
            String hash = CompatibilityBundleExporter.copyFile(source, zip,
                    new CompatibilityBundleExporter.ExportControl(), null, source.getPath(), 1, 1, 0);
            assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", hash);
            zip.closeEntry();
        }
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            assertNotNull(zip.getNextEntry());
            assertArrayEquals(Files.readAllBytes(source.toPath()), zip.readAllBytes());
        }
    }

    @Test public void nativeInventoryPathsStayInsideInstalledPackageRoots() {
        assertEquals(2, CompatibilityBundleEvidence.huaweiNativeLibraryDirs(
                "nativeLibraryDir=/system/app/Sentry/lib/arm64\nsecondaryNativeLibraryDir=/system/app/Sentry/lib/arm\n"
                        + "nativeLibraryDir=/data/user/0/private\n").size());
        assertTrue(SentryExportEvidence.allowedResolved("/system/lib64/libSNPE.so"));
        assertFalse(SentryExportEvidence.allowedResolved("/data/user/0/private/key.so"));
        assertFalse(SentryExportEvidence.allowedResolved("/system/lib64/../../data/key.so"));
        assertFalse(SentryExportEvidence.allowedResolved("/system/lib64/lib.so\n/data/key"));
    }
}
