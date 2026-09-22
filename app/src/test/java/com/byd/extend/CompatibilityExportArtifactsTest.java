package com.byd.extend;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class CompatibilityExportArtifactsTest {
    private static final long NOW = 1_800_000_000_000L;
    private static final String CURRENT =
            "byd-extend-compatibility-20270115-080000-123.zip";
    private static final String LEGACY =
            "byd-turnsignal-compatibility-20270115-080000-2.zip";

    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void metadataUsesAbsoluteFifteenMinuteCompletionExpiryWithoutRenewal() throws Exception {
        File archive = file(CURRENT);
        CompatibilityExportArtifacts.completed(archive, NOW);
        File metadata = new File(archive.getPath() + ".expiry.json");
        JSONObject record = new JSONObject(new String(Files.readAllBytes(metadata.toPath()),
                StandardCharsets.UTF_8));
        assertEquals(NOW, record.getLong("createdAtMs"));
        assertEquals(NOW + CompatibilityExportArtifacts.RETENTION_MS,
                record.getLong("expiresAtMs"));

        assertEquals(NOW + CompatibilityExportArtifacts.RETENTION_MS,
                sweep(NOW + 60_000L));
        assertEquals(NOW + CompatibilityExportArtifacts.RETENTION_MS,
                sweep(NOW + 14L * 60_000L));
        sweep(NOW + CompatibilityExportArtifacts.RETENTION_MS);
        assertFalse(archive.exists());
        assertFalse(metadata.exists());
    }

    @Test
    public void legacyCollisionUsesLastModifiedAndOwnedPartialsAreRecovered() throws Exception {
        File legacy = file(LEGACY);
        assertTrue(legacy.setLastModified(NOW - 16L * 60_000L));
        File currentPart = file(CURRENT + ".part");
        File legacyPart = file(LEGACY + ".part");
        File staging = new File(temporary.getRoot(), ".compatibility-tmp-a1-b2");
        assertTrue(staging.mkdirs());
        Files.write(new File(staging, "source.bin").toPath(), new byte[]{1});
        File collision10 = file(
                "byd-extend-compatibility-20270115-080000-123-10.zip");
        File collision100 = file(
                "byd-extend-compatibility-20270115-080000-123-100.zip");
        assertTrue(collision10.setLastModified(NOW - 16L * 60_000L));
        assertTrue(collision100.setLastModified(NOW - 16L * 60_000L));

        sweep(NOW);

        assertFalse(legacy.exists());
        assertFalse(currentPart.exists());
        assertFalse(legacyPart.exists());
        assertFalse(staging.exists());
        assertFalse(collision10.exists());
        assertFalse(collision100.exists());
    }

    @Test
    public void activeAndExternalOrUnrelatedFilesRemainUntouched() throws Exception {
        File archive = file(CURRENT);
        File activePart = file(LEGACY + ".part");
        File staging = new File(temporary.getRoot(), ".compatibility-tmp-c-d");
        assertTrue(staging.mkdirs());
        File unrelatedStagingPrefix = new File(
                temporary.getRoot(), ".compatibility-tmp-user-notes");
        assertTrue(unrelatedStagingPrefix.mkdirs());
        File unrelated = file("byd-turnsignal-diagnostics-old.zip");
        File lookalike = file("byd-turnsignal-compatibility-previous.zip");
        File external = temporary.newFolder("external-cache");
        File externalArchive = new File(external, CURRENT);
        Files.write(externalArchive.toPath(), new byte[]{1});

        CompatibilityExportArtifacts.sweep(temporary.getRoot(), NOW,
                new java.util.HashSet<>(java.util.Arrays.asList(
                        archive.getAbsolutePath(),
                        new File(temporary.getRoot(), LEGACY).getAbsolutePath(),
                        staging.getAbsolutePath())), ignored -> { });

        assertTrue(archive.exists());
        assertTrue(activePart.exists());
        assertTrue(staging.exists());
        assertTrue(unrelatedStagingPrefix.exists());
        assertTrue(unrelated.exists());
        assertTrue(lookalike.exists());
        assertTrue(externalArchive.exists());
    }

    @Test
    public void freshLegacyArchiveKeepsOriginalRemainingTime() throws Exception {
        File archive = file(LEGACY);
        assertTrue(archive.setLastModified(NOW));
        assertEquals(NOW + CompatibilityExportArtifacts.RETENTION_MS,
                sweep(NOW + 10L * 60_000L));
        sweep(NOW + CompatibilityExportArtifacts.RETENTION_MS);
        assertFalse(archive.exists());
    }

    @Test
    public void failedDeletionRetriesAfterSixtySeconds() throws Exception {
        File archive = file(CURRENT);
        CompatibilityExportArtifacts.completed(archive, NOW);
        File refusesDelete = new File(archive.getPath()) {
            @Override public boolean delete() { return false; }
        };
        File directory = new File(temporary.getRoot().getPath()) {
            @Override public File[] listFiles() {
                return new File[]{refusesDelete,
                        new File(archive.getPath() + ".expiry.json")};
            }
        };
        List<String> events = new ArrayList<>();

        long next = CompatibilityExportArtifacts.sweep(directory,
                NOW + CompatibilityExportArtifacts.RETENTION_MS,
                Collections.emptySet(), events::add);

        assertEquals(NOW + CompatibilityExportArtifacts.RETENTION_MS
                + CompatibilityExportArtifacts.RETRY_MS, next);
        assertTrue(archive.exists());
        assertTrue(events.stream().anyMatch(value -> value.startsWith("cleanup_failed")));
    }

    private File file(String name) throws Exception {
        File result = new File(temporary.getRoot(), name);
        Files.write(result.toPath(), new byte[]{1, 2, 3});
        return result;
    }

    private long sweep(long now) {
        return CompatibilityExportArtifacts.sweep(temporary.getRoot(), now,
                Collections.emptySet(), ignored -> { });
    }
}
