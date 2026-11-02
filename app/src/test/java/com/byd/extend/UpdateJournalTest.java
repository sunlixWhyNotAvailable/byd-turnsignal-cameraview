package com.byd.extend;

import android.app.Application;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class UpdateJournalTest {
    @Test public void updateDecisionsAndRotationSurviveShareAndSaveAsWithoutAdb() throws Exception {
        Application context = RuntimeEnvironment.getApplication();
        UpdateHintRuntime runtime = UpdateHintRuntime.get(context);
        runtime.offerDecision("export_in_progress");
        File directory = new File(context.getExternalFilesDir(null), "captures");
        File current = new File(directory, AvasRecoveryJournal.FILE_NAME);
        long firstSize = current.length();
        runtime.offerDecision("export_in_progress");
        assertEquals(firstSize, current.length());
        JSONObject decision = new JSONObject(new String(Files.readAllBytes(current.toPath()), StandardCharsets.UTF_8).trim());
        assertEquals("update_runtime", decision.getString("source"));
        assertTrue(decision.getString("detail").contains("export_in_progress"));
        // Actual writer rotation, not manufactured export records.
        String detail = "x".repeat(8_192);
        for (int i = 0; i < 35; i++) AvasRecoveryJournal.event(context, "update_check", "source", "update_runtime", "detail", detail);
        runtime.offerDecision("shown");
        File previous = new File(directory, AvasRecoveryJournal.PREVIOUS_FILE_NAME);
        assertTrue(previous.isFile());
        File archive = new File(context.getCacheDir(), "share-updates.zip");
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(archive))) {
            DiagnosticLogExporter.writeAppLogs(zip, DiagnosticLogExporter.snapshot(directory),
                    new ArrayList<>(), new DiagnosticLogExporter.ProgressTracker(null));
        }
        File saved = new File(context.getCacheDir(), "saved-updates.zip");
        try (FileOutputStream output = new FileOutputStream(saved)) { ArchiveDocumentWriter.copy(archive, output); }
        assertArrayEquals(Files.readAllBytes(archive.toPath()), Files.readAllBytes(saved.toPath()));
        try (ZipFile zip = new ZipFile(saved)) {
            for (File journal : new File[]{current, previous}) {
                assertArrayEquals(Files.readAllBytes(journal.toPath()),
                        zip.getInputStream(zip.getEntry("logs/" + journal.getName())).readAllBytes());
            }
        }
    }
}
