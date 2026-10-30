package com.byd.extend;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** Real configuration/files/archive paths and pure ownership decisions; no fake OEM replies. */
public class CellularRecoveryContractTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void independentRecoveryPersistsWithoutCameraDemandAndToggleRetainsSession() throws Exception {
        MotionConfig legacy = MotionConfig.parse("{\"remoteViewingEnabled\":true}");
        assertFalse(legacy.restoreCellular);
        assertTrue(legacy.screenOffAfterCellular);
        File root = temporary.newFolder();
        MotionSessionConfigStore store = new MotionSessionConfigStore(root, 10123);
        MotionSessionConfigStore.Snapshot only = store.write("{\"restoreCellular\":true,\"cellularGeneration\":1}");
        assertTrue(only.config.requiresHelper());
        assertFalse(only.config.anyEnabled());
        assertFalse(only.config.motionEnabled());
        assertEquals(only.config.toJson(), new MotionSessionConfigStore(root, 10123).read().config.toJson());
        MotionSessionConfigStore.Snapshot viewing = store.write("{\"remoteViewingEnabled\":true}");
        MotionSessionConfigStore.Snapshot enabled = store.write("{\"remoteViewingEnabled\":true,\"restoreCellular\":true,\"cellularGeneration\":2}");
        assertEquals(viewing.sessionId, enabled.sessionId);
        MotionSessionConfigStore.Snapshot disabled = store.write("{\"remoteViewingEnabled\":true,\"restoreCellular\":false,\"cellularGeneration\":2}");
        assertEquals(enabled.sessionId, disabled.sessionId);
        assertEquals(2, disabled.config.cellularGeneration);
        assertNull(store.write("{}"));
    }

    @Test public void cacheReopenRejectsStaleIdentityAndRetriesFailedOnlyForNewRequest() throws Exception {
        File path = new File(temporary.newFolder(), "cache.json");
        JSONObject input = new JSONObject().put("analyzer", OemWakeAnalyzer.VERSION)
                .put("identity", "a".repeat(64)).put("request", 3).put("status", "failed")
                .put("reason", 13).put("error", "source unavailable");
        WakeDiscoveryState.write(path.toPath(), input, false);
        JSONObject reopened = WakeDiscoveryState.read(path.toPath());
        assertEquals(input.toString(), reopened.toString());
        assertTrue(WakeDiscoveryState.reusable(reopened, "a".repeat(64), 3));
        assertFalse(WakeDiscoveryState.reusable(reopened, "a".repeat(64), 4));
        assertFalse(WakeDiscoveryState.reusable(reopened, "b".repeat(64), 3));
        input.put("status", "pending");
        assertFalse(WakeDiscoveryState.reusable(input, "a".repeat(64), 3));
        // Persisted records are input; the genuine-JAR matrix separately runs the analyzer.
        input.put("status", "found").put("evidence", "retained method path");
        WakeDiscoveryState.write(path.toPath(), input, false);
        assertTrue(WakeDiscoveryState.reusable(WakeDiscoveryState.read(path.toPath()), "a".repeat(64), 4));
        input.put("analyzer", OemWakeAnalyzer.VERSION + 1);
        WakeDiscoveryState.write(path.toPath(), input, false);
        assertNull(WakeDiscoveryState.read(path.toPath()));
        Files.write(path.toPath(), "{broken".getBytes(StandardCharsets.UTF_8));
        try { WakeDiscoveryState.read(path.toPath()); fail("Malformed cache must not select a reason"); }
        catch (org.json.JSONException expected) { assertNotNull(expected.getMessage()); }
    }

    @Test public void cellularToggleRetainsArmedMotionAndRecordingRevision() throws Exception {
        MotionSessionRuntime runtime = new MotionSessionRuntime();
        String id = "0123456789abcdef0123456789abcdef";
        MotionConfig initial = MotionConfig.parse("{\"warning\":true,\"enabled\":true,\"lock\":1}");
        runtime.configure(id, initial);
        runtime.telemetry("epoch", "power", 0, true, 1_000);
        runtime.telemetry("epoch", "lock", 2, true, 1_000);
        assertEquals(MotionSessionRuntime.Change.ARMED, runtime.advance(3_500));
        long revision = runtime.recordingRevision();
        JSONObject update = new JSONObject(initial.toJson()).put("restoreCellular", true).put("cellularGeneration", 1);
        MotionConfig enabled = MotionConfig.parse(update.toString());
        assertTrue(enabled.onlyCellularChangesDifferFrom(initial));
        assertEquals(MotionSessionRuntime.Change.NONE, runtime.updateInPlaceConfig(enabled, 4_000));
        assertTrue(runtime.armed());
        assertEquals(id, runtime.sessionId());
        assertEquals(revision, runtime.recordingRevision());
        update.put("restoreCellular", false);
        assertEquals(MotionSessionRuntime.Change.NONE,
                runtime.updateInPlaceConfig(MotionConfig.parse(update.toString()), 4_100));
        assertTrue(runtime.armed());
        assertEquals(revision, runtime.recordingRevision());
        update.put("screenOffAfterCellular", false);
        MotionConfig childDisabled = MotionConfig.parse(update.toString());
        assertEquals(MotionSessionRuntime.Change.NONE, runtime.updateInPlaceConfig(childDisabled, 4_200));
        assertTrue(runtime.armed());
        assertEquals(revision, runtime.recordingRevision());
        assertFalse(MotionConfig.parse(childDisabled.toJson()).screenOffAfterCellular);
    }

    @Test public void onlyOwnWakeWithoutRecoveryMayBeSlept() {
        assertFalse(MotionScreenOwnership.maySleep(true, false, false));
        assertFalse(MotionScreenOwnership.maySleep(true, true, false));
        assertFalse(MotionScreenOwnership.maySleep(false, true, true));
        assertFalse(MotionScreenOwnership.maySleep(false, false, false));
        assertTrue(MotionScreenOwnership.maySleep(false, true, false));
        // An enable arriving after warning start changes the decision at command dispatch.
        assertFalse(MotionScreenOwnership.maySleep(false, true, true));
    }

    @Test public void backlightRestorationRequiresOwnWakeBothSwitchesAndIdleMotion() {
        assertTrue(MotionScreenOwnership.mayDarken(true, true, true, 0, false, true));
        assertFalse(MotionScreenOwnership.mayDarken(false, true, true, 0, false, true));
        assertFalse(MotionScreenOwnership.mayDarken(true, false, true, 0, false, true));
        assertFalse(MotionScreenOwnership.mayDarken(true, true, false, 0, false, true));
        assertFalse(MotionScreenOwnership.mayDarken(true, true, true, 1, false, true));
        assertFalse(MotionScreenOwnership.mayDarken(true, true, true, -1, false, true));
        assertFalse(MotionScreenOwnership.mayDarken(true, true, true, 0, true, true));
        assertFalse(MotionScreenOwnership.mayDarken(true, true, true, 0, false, false));
    }

    @Test public void interfacePresenceUsesActualHostInterfaceWithoutIpOrInternet() throws Exception {
        java.util.Enumeration<java.net.NetworkInterface> interfaces = java.net.NetworkInterface.getNetworkInterfaces();
        assertTrue("Host must expose an interface for this real OS check", interfaces.hasMoreElements());
        assertTrue(CellularApnPlatform.apnInterfacePresent(interfaces.nextElement().getName()));
        try { CellularApnPlatform.apnInterfacePresent(""); fail("Unknown APN identity must not become absent"); }
        catch (java.io.IOException expected) { assertTrue(expected.getMessage().contains("identity")); }
    }

    @Test public void persistedBacklightOwnershipIsLimitedToTheRecordedBoot() throws Exception {
        File directory = temporary.newFolder();
        JSONObject owned = new JSONObject().put("backlight_boot", "boot-a").put("backlight_owned", true);
        CellularRecoveryDiagnostics.write(directory, owned);
        JSONObject reopened = new JSONObject(new String(Files.readAllBytes(new File(directory,
                CellularRecoveryDiagnostics.FILE_NAME).toPath()), StandardCharsets.UTF_8)).getJSONObject("snapshot");
        assertTrue(CellularRecovery.ownsBacklight(reopened, "boot-a"));
        assertFalse(CellularRecovery.ownsBacklight(reopened, "boot-b"));
        assertFalse(CellularRecovery.ownsBacklight(reopened, ""));
        owned.put("backlight_owned", false);
        CellularRecoveryDiagnostics.write(directory, owned);
        String last = new String(Files.readAllBytes(new File(directory,
                CellularRecoveryDiagnostics.FILE_NAME).toPath()), StandardCharsets.UTF_8);
        assertFalse(CellularRecovery.ownsBacklight(new JSONObject(last).getJSONObject("snapshot"), "boot-a"));
    }

    @Test public void completeDiagnosticSurvivesAppZipAndSaveCopyWithoutSystemCollector() throws Exception {
        File directory = temporary.newFolder();
        String actualError;
        try { Files.readAllBytes(new File(directory, "absent.jar").toPath()); fail(); return; }
        catch (java.io.IOException error) { actualError = CellularRecovery.diagnosticDetail(error, "stage=source_read"); }
        JSONObject snapshot = new JSONObject().put("discovery", new JSONObject().put("status", "failed")
                .put("reason", 13).put("error", actualError));
        CellularRecoveryDiagnostics.write(directory, snapshot);
        File archive = temporary.newFile("share.zip");
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(archive))) {
            DiagnosticLogExporter.writeAppLogs(zip, DiagnosticLogExporter.snapshot(directory), new ArrayList<>(),
                    new DiagnosticLogExporter.ProgressTracker(null));
        }
        File saved = temporary.newFile("saved.zip");
        try (FileOutputStream output = new FileOutputStream(saved)) { ArchiveDocumentWriter.copy(archive, output); }
        assertArrayEquals(Files.readAllBytes(archive.toPath()), Files.readAllBytes(saved.toPath()));
        try (ZipFile zip = new ZipFile(saved)) {
            String text = new String(zip.getInputStream(zip.getEntry("logs/" + CellularRecoveryDiagnostics.FILE_NAME))
                    .readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(actualError, new JSONObject(text).getJSONObject("snapshot")
                    .getJSONObject("discovery").getString("error"));
        }
    }
}
