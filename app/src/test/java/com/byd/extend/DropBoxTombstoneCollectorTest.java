package com.byd.extend;

import java.io.*;
import java.nio.file.Files;
import java.util.zip.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class DropBoxTombstoneCollectorTest {
    @Test public void binaryPayloadLargerThanDumpsysLimitSurvivesArchiveRoundTrip() throws Exception {
        File root = Files.createTempDirectory("tombstone-export").toFile();
        File source = new File(root, "source.pb"), archive = new File(root, "out.zip");
        byte[] bytes = new byte[600_123];
        new java.util.Random(7).nextBytes(bytes);
        try {
            Files.write(source.toPath(), bytes);
            try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(archive));
                    InputStream input = new FileInputStream(source)) {
                assertEquals(bytes.length, DropBoxTombstoneCollector.writePayload(zip, "entry.pb", input));
            }
            try (ZipFile zip = new ZipFile(archive);
                    InputStream input = zip.getInputStream(zip.getEntry("entry.pb"))) {
                assertArrayEquals(bytes, input.readAllBytes());
            }
        } finally {
            Files.deleteIfExists(source.toPath());
            Files.deleteIfExists(archive.toPath());
            Files.deleteIfExists(root.toPath());
        }
    }
}
