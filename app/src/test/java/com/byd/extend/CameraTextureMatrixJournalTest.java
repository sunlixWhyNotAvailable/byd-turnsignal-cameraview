package com.byd.extend;

import android.app.Application;
import com.byd.extend.remote.CameraViewingDiagnostics;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
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
public class CameraTextureMatrixJournalTest {
    @Test public void fileDiagnosticsSurviveExportAndSaveAsWithoutLogcat() throws Exception {
        Application context = RuntimeEnvironment.getApplication();
        CameraViewingDiagnostics.initializeApp(context);
        String detail = new JSONObject().put("kind", "camera_texture_matrix")
                .put("source_index", 5).put("surface_texture_id", 123)
                .put("matrix", new org.json.JSONArray(new float[]{
                        -1, 0, 0, 0, 0, -1, 0, 0, 0, 0, 1, 0, 1, 1, 0, 1})).toString();
        CameraViewingDiagnostics.detail("camera_texture_matrix", "camera", detail);

        File directory = new File(context.getExternalFilesDir(null), "captures");
        File journal = new File(directory, AvasRecoveryJournal.FILE_NAME);
        String saved = new String(Files.readAllBytes(journal.toPath()), StandardCharsets.UTF_8);
        JSONObject event = new JSONObject(saved.trim());
        assertEquals("camera_texture_matrix", event.getString("stage"));
        assertEquals(detail, event.getString("details"));
        assertTrue(event.has("wall_time_ms"));
        assertTrue(event.has("pid"));

        // Exercise the existing app-file export and Save As copy without any system collector.
        File archive = new File(context.getCacheDir(), "share.zip");
        List<DiagnosticLogExporter.SourceRecord> records = new ArrayList<>();
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(archive))) {
            DiagnosticLogExporter.writeAppLogs(zip, DiagnosticLogExporter.snapshot(directory),
                    records, new DiagnosticLogExporter.ProgressTracker(null));
        }
        assertEquals(1, records.size());
        assertEquals("included", records.get(0).status);
        File document = new File(context.getCacheDir(), "saved-as.zip");
        try (FileOutputStream output = new FileOutputStream(document)) {
            assertEquals(archive.length(), ArchiveDocumentWriter.copy(archive, output));
        }
        assertArrayEquals(Files.readAllBytes(archive.toPath()), Files.readAllBytes(document.toPath()));
        for (File file : new File[]{archive, document}) {
            try (ZipFile zip = new ZipFile(file)) {
                assertEquals(1, zip.size());
                assertEquals(saved, new String(zip.getInputStream(
                        zip.getEntry("logs/" + journal.getName())).readAllBytes(), StandardCharsets.UTF_8));
            }
        }
    }
}
