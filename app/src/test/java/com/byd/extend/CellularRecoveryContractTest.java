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

    @Test public void offCycleChecksEveryFiveSecondsForThirtySecondsThenEveryThirty() {
        CellularApnSchedule schedule = new CellularApnSchedule();
        assertTrue(schedule.powerChanged(0, 100_000));
        for (long at : new long[] {100_000, 105_000, 110_000, 115_000, 120_000, 125_000, 130_000}) {
            assertEquals(0, schedule.delayMs(at));
            assertFalse(schedule.checkStarted(at));
            schedule.checkFinished(at + 300);
            assertEquals(at < 130_000 ? 4_700 : 29_700, schedule.delayMs(at + 300));
        }
        assertEquals(1, schedule.delayMs(159_999));
        assertEquals(0, schedule.delayMs(160_000));
        schedule.checkStarted(160_000);
        schedule.checkFinished(160_300);
        assertEquals(29_700, schedule.delayMs(160_300));
    }

    @Test public void networkEventsCoalesceAndSurviveAnInProgressCheckWithoutMovingTimer() {
        CellularApnSchedule schedule = new CellularApnSchedule();
        schedule.powerChanged(0, 100_000);
        schedule.checkStarted(100_000);
        schedule.checkFinished(100_300);
        assertTrue(schedule.networkChanged());
        assertFalse(schedule.networkChanged());
        assertEquals(0, schedule.delayMs(102_000));
        assertTrue(schedule.checkStarted(102_000));
        assertTrue(schedule.networkChanged());
        schedule.checkFinished(102_300);
        assertEquals(0, schedule.delayMs(102_300));
        assertTrue(schedule.checkStarted(102_300));
        schedule.checkFinished(102_600);
        assertEquals(2_400, schedule.delayMs(102_600));
        schedule.checkStarted(130_000);
        schedule.checkFinished(130_300);
        assertTrue(schedule.networkChanged());
        assertTrue(schedule.checkStarted(140_000));
        schedule.checkFinished(140_300);
        assertEquals(19_700, schedule.delayMs(140_300));
    }

    @Test public void powerOnCancelsPendingWorkAndRepeatedOffOrTelemetryGapDoesNotRestartWindow() {
        CellularApnSchedule schedule = new CellularApnSchedule();
        schedule.powerChanged(0, 100_000);
        schedule.checkStarted(100_000);
        schedule.checkFinished(100_300);
        assertFalse(schedule.powerChanged(0, 129_000));
        assertTrue(schedule.powerChanged(-1, 129_100));
        assertTrue(schedule.networkChanged());
        assertTrue(schedule.powerChanged(0, 129_200));
        assertTrue(schedule.checkStarted(129_200));
        schedule.checkFinished(129_500);
        assertEquals(500, schedule.delayMs(129_500));
        schedule.checkStarted(130_000);
        schedule.checkFinished(130_300);
        assertEquals(29_700, schedule.delayMs(130_300));
        assertTrue(schedule.networkChanged());
        schedule.powerChanged(1, 131_000);
        assertFalse(schedule.networkChanged());
        schedule.checkFinished(132_000);
        assertEquals(Long.MAX_VALUE, schedule.delayMs(132_000));
        schedule.powerChanged(0, 150_000);
        assertEquals(0, schedule.delayMs(150_000));
        schedule.checkStarted(150_000);
        schedule.checkFinished(150_300);
        assertEquals(4_700, schedule.delayMs(150_300));
    }

    @Test public void lateChecksSkipMissedTicksAndInitialPowerReadbackRetainsNetworkEvent() {
        CellularApnSchedule schedule = new CellularApnSchedule();
        assertEquals(0, schedule.delayMs(99_000));
        schedule.checkStarted(99_000);
        assertTrue(schedule.networkChanged());
        schedule.powerChanged(0, 100_000);
        schedule.checkFinished(100_300);
        assertEquals(0, schedule.delayMs(100_300));
        assertTrue(schedule.checkStarted(100_300));
        schedule.checkFinished(112_000);
        assertEquals(3_000, schedule.delayMs(112_000));
        schedule.checkStarted(115_000);
        schedule.checkFinished(131_000);
        assertEquals(29_000, schedule.delayMs(131_000));
        schedule.checkStarted(231_000);
        schedule.checkFinished(231_300);
        assertEquals(18_700, schedule.delayMs(231_300));
    }

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
        assertTrue(MotionScreenOwnership.mayDarken(true, true, true, 0, false));
        assertFalse(MotionScreenOwnership.mayDarken(false, true, true, 0, false));
        assertFalse(MotionScreenOwnership.mayDarken(true, false, true, 0, false));
        assertFalse(MotionScreenOwnership.mayDarken(true, true, false, 0, false));
        assertFalse(MotionScreenOwnership.mayDarken(true, true, true, 1, false));
        assertFalse(MotionScreenOwnership.mayDarken(true, true, true, -1, false));
        assertFalse(MotionScreenOwnership.mayDarken(true, true, true, 0, true));
    }

    @Test public void interfacePresenceUsesActualHostInterfaceWithoutIpOrInternet() throws Exception {
        java.util.Enumeration<java.net.NetworkInterface> interfaces = java.net.NetworkInterface.getNetworkInterfaces();
        assertTrue("Host must expose an interface for this real OS check", interfaces.hasMoreElements());
        assertTrue(CellularApnPlatform.apnInterfacePresent(interfaces.nextElement().getName()));
    }

    @Test public void emptyApnMappingAllowsRecoveryButInvalidMappingsRemainErrors() throws Exception {
        assertFalse(CellularApnPlatform.apnInterfacePresent(""));
        for (String invalid : new String[] { null, " ", "invalid/name" }) {
            try { CellularApnPlatform.apnInterfacePresent(invalid); fail("Invalid APN identity must remain an error"); }
            catch (java.io.IOException expected) { assertTrue(expected.getMessage().contains("identity")); }
        }
    }

    @Test public void persistedBacklightOwnershipIsLimitedToTheRecordedBoot() throws Exception {
        File directory = temporary.newFolder();
        JSONObject owned = new JSONObject().put("backlight_boot", "boot-a").put("backlight_owned", true);
        CellularRecoveryDiagnostics.write(directory, owned);
        JSONObject reopened = new JSONObject(new String(Files.readAllBytes(new File(directory,
                CellularRecoveryDiagnostics.FILE_NAME).toPath()), StandardCharsets.UTF_8)).getJSONObject("snapshot");
        assertTrue(CellularRecovery.ownsBacklight(reopened, "boot-a"));
        assertEquals(MotionScreenOwnership.Inherited.KEEP,
                MotionScreenOwnership.inherited(CellularRecovery.ownsBacklight(reopened, "boot-a"), true, 0, 0));
        assertEquals(MotionScreenOwnership.Inherited.WAIT, MotionScreenOwnership.inherited(true, true, -1, 0));
        assertEquals(MotionScreenOwnership.Inherited.RESTORE, MotionScreenOwnership.inherited(true, true, 1, 0));
        assertEquals(MotionScreenOwnership.Inherited.RESTORE, MotionScreenOwnership.inherited(true, false, 0, 0));
        assertEquals(MotionScreenOwnership.Inherited.RELEASE, MotionScreenOwnership.inherited(true, true, 0, 1));
        assertEquals(MotionScreenOwnership.Inherited.RELEASE,
                MotionScreenOwnership.inherited(CellularRecovery.ownsBacklight(reopened, "boot-b"), true, 0, 0));
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

    @Test public void recoveredCheckRetainsOriginalErrorAndItsTimeInPersistedSnapshot() throws Exception {
        File directory = temporary.newFolder();
        JSONObject snapshot = new JSONObject();
        try { Files.readAllBytes(new File(directory, "absent").toPath()); fail(); }
        catch (java.io.IOException error) { CellularRecovery.recordCheck(snapshot, "check-1", 100, error); }
        assertTrue(snapshot.getBoolean("check_error_active"));
        String original = snapshot.getString("check_error");
        CellularRecoveryDiagnostics.write(directory, snapshot);
        Files.readAllBytes(new File(directory, CellularRecoveryDiagnostics.FILE_NAME).toPath());
        CellularRecovery.recordCheck(snapshot, "check-2", 200, null);
        CellularRecoveryDiagnostics.write(directory, snapshot);
        JSONObject reopened = new JSONObject(new String(Files.readAllBytes(new File(directory,
                CellularRecoveryDiagnostics.FILE_NAME).toPath()), StandardCharsets.UTF_8)).getJSONObject("snapshot");
        assertFalse(reopened.getBoolean("check_error_active"));
        assertEquals(original, reopened.getString("check_error"));
        assertEquals(100, reopened.getLong("check_error_at_ms"));
        assertEquals(200, reopened.getLong("check_recovered_at_ms"));
    }
}
