package com.byd.extend;

import org.junit.Test;
import static org.junit.Assert.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public class CameraRuntimeContinuityTest {
    @Test public void uiDeathAndRepeatedAttachNeverRestartLiveSource() {
        CameraRuntimeLifetime<Object> session = new CameraRuntimeLifetime<>();
        AtomicInteger opens = new AtomicInteger(), closes = new AtomicInteger(), frames = new AtomicInteger();
        Object first = new Object(), second = new Object();
        Runnable open = opens::incrementAndGet;
        assertFalse(session.attach(first, open));
        frames.incrementAndGet();
        assertTrue(session.detach(first));
        assertTrue(session.started());
        assertFalse(session.stopped());
        frames.incrementAndGet(); // producer is still owned by the same runtime, with no client
        assertTrue(session.attach(second, open));
        assertTrue(session.attach(second, open));
        assertFalse(session.detach(first)); // late death of an obsolete app cannot detach its successor
        assertSame(second, session.client());
        assertEquals(1, opens.get());
        assertEquals(0, closes.get());
        assertEquals(2, frames.get());
        session.shutdown(closes::incrementAndGet);
        session.shutdown(closes::incrementAndGet);
        assertEquals(1, closes.get());
        assertNull(session.client());
        assertThrows(IllegalStateException.class, () -> session.attach(first, open));
    }

    @Test public void unfinishedInitializationDoesNotMasqueradeAsAHealthySession() {
        CameraRuntimeLifetime<Object> session = new CameraRuntimeLifetime<>();
        assertThrows(IllegalStateException.class, () -> session.attach(new Object(), () -> {
            throw new IllegalStateException("source unavailable");
        }));
        assertFalse(session.started());
        assertNull(session.client());
    }

    @Test public void settingsChangedWithoutUiSurviveStaleReattachAndOldAcknowledgement() {
        CameraRuntimePreferences preferences = new CameraRuntimePreferences();
        Map<String, Object> disk = new HashMap<>();
        disk.put("mirror_x", 10f); disk.put("mirror_enabled", true);
        preferences.reconcile(disk);
        preferences.edit().putFloat("mirror_x", 35f).apply();
        assertEquals(35f, preferences.reconcile(disk).get("mirror_x"));
        preferences.acknowledge("mirror_x", 10f);
        assertEquals(35f, preferences.reconcile(disk).get("mirror_x"));
        preferences.acknowledge("mirror_x", 35f);
        disk.put("mirror_x", 35f);
        assertTrue(preferences.reconcile(disk).isEmpty());
        assertEquals(35f, preferences.getFloat("mirror_x", 0), 0);
        assertTrue(preferences.getBoolean("mirror_enabled", false));
    }

    @Test public void clientOnlySendsActualEditsNotAStaleFullSettingsReplacement() {
        Map<String, Object> before = new HashMap<>(), after = new HashMap<>();
        before.put("mirror_x", 10f); before.put("camera_width", 50f); before.put("removed", true);
        after.put("mirror_x", 10f); after.put("camera_width", 60f);
        Map<String, Object> delta = CameraRuntimeClient.changes(before, after);
        assertFalse(delta.containsKey("mirror_x"));
        assertEquals(60f, delta.get("camera_width"));
        assertTrue(delta.containsKey("removed"));
        assertNull(delta.get("removed"));
    }

    @Test public void parcelStringSetsRemainEqualToPersistedPreferences() {
        Object fromParcel = CameraRuntimePreferences.normalize(
                new java.util.ArrayList<>(java.util.Arrays.asList("left", "right")));
        assertEquals(new java.util.HashSet<>(java.util.Arrays.asList("right", "left")), fromParcel);
    }

    @Test public void reconnectChecksHelperIdentityBeforeAttachButReusesLiveEndpoint() throws Exception {
        String client = source("CameraRuntimeClient");
        String connect = client.substring(client.indexOf("synchronized boolean connect()"),
                client.indexOf("private synchronized void applyRuntimeSettings"));
        int reuse = connect.indexOf("if (camera != null && camera.isBinderAlive()) return true;");
        int identity = connect.indexOf("!TurnSignalController.ping(candidate).healthy()");
        int attach = connect.indexOf("data.writeInt(CameraRuntimeHost.ATTACH)");
        assertTrue(reuse >= 0);
        assertTrue("Check identity only when reconnecting, before any settings/ATTACH", identity > reuse);
        assertTrue(attach > identity);
        assertTrue(connect.substring(identity, attach).contains("return false;"));
        assertFalse(connect.contains("TX_SHUTDOWN"));

        String controller = source("TurnSignalController");
        assertTrue(controller.contains("static Ping ping(IBinder value)"));
        assertTrue(controller.contains("TurnSignalShellProtocol.compatibilityError(protocol, build)"));
        assertEquals("", TurnSignalShellProtocol.compatibilityError(
                TurnSignalShellProtocol.VERSION, BuildConfig.VERSION_CODE));
        assertEquals("protocol_mismatch", TurnSignalShellProtocol.compatibilityError(
                TurnSignalShellProtocol.VERSION - 1, BuildConfig.VERSION_CODE));
        assertEquals("build_mismatch", TurnSignalShellProtocol.compatibilityError(
                TurnSignalShellProtocol.VERSION, BuildConfig.VERSION_CODE - 1));
    }

    @Test public void descriptorQueryBypassesCustomTokenButNotCallerCheck() throws Exception {
        String shell = source("CameraShellMain");
        int dispatch = shell.indexOf("protected boolean onTransact(");
        int caller = shell.indexOf("CameraShellProtocol.isCallerAllowed", dispatch);
        int descriptor = shell.indexOf("if (code == IBinder.INTERFACE_TRANSACTION)", dispatch);
        int nativeDispatch = shell.indexOf("return super.onTransact(code, data, reply, flags);", descriptor);
        int token = shell.indexOf("data.enforceInterface(CameraShellProtocol.DESCRIPTOR)", dispatch);
        assertTrue(caller > dispatch);
        assertTrue("Descriptor queries still enforce caller identity", descriptor > caller);
        assertTrue(nativeDispatch > descriptor);
        assertTrue("Native descriptor query has no custom interface token", token > nativeDispatch);
        assertTrue(shell.contains("binder.attachInterface(null, CameraShellProtocol.DESCRIPTOR)"));
        assertTrue(CameraShellProtocol.isCallerAllowed(2000, 10123));
        assertTrue(CameraShellProtocol.isCallerAllowed(10123, 10123));
        assertFalse(CameraShellProtocol.isCallerAllowed(10124, 10123));
    }

    @Test public void productionOwnsSourcesAndControllersOutsideServiceAndRestrictsIpc() throws Exception {
        String service = source("CameraHelperService"), host = source("CameraRuntimeHost"),
                helper = source("CameraHelperMain"), turn = source("TurnSignalController"),
                shell = source("TurnSignalShellMain");
        assertFalse(service.contains("new BlindSpotOverlayController("));
        assertFalse(service.contains("new ReverseCameraController("));
        assertFalse(service.contains("new RearviewMirrorController("));
        assertTrue(host.contains("new CameraHelperMain.HelperBinder(context, handler"));
        assertTrue(host.contains("lifetime.attach(callback, this::initialize)"));
        assertTrue(host.contains("catch (RuntimeException error) { stopOnHandler(); throw error; }"));
        assertTrue(host.contains("handler.post(() -> send(code, line, settings))"));
        assertTrue(service.contains("cameraRuntime.settingsChanged(\"logging\")"));
        assertTrue(source("CameraRuntimeClient").contains("return camera != null && camera.isBinderAlive()"));
        String client = source("CameraRuntimeClient");
        assertTrue(client.contains("settings.registerOnSharedPreferenceChangeListener(preferenceListener)"));
        assertTrue(client.contains("settings.unregisterOnSharedPreferenceChangeListener(preferenceListener)"));
        assertTrue(client.contains("command(CameraRuntimeHost.DETACH"));
        String shutdown = client.substring(client.indexOf("synchronized void shutdown()"),
                client.indexOf("synchronized void detach()"));
        assertFalse(shutdown.contains("connect()"));
        assertFalse(client.contains("synchronized void setUiState"));
        assertTrue(helper.contains("if (cameraRuntime != null) cameraRuntime.detach()"));
        String death = host.substring(host.indexOf("private void clientDied"), host.indexOf("private void applySettings"));
        assertFalse(death.contains("shutdown("));
        assertFalse(death.contains("initialize("));
        assertTrue(death.contains("CAMERA_OWNER_ACTIVITY"));
        assertTrue(helper.contains("cameraRuntime.transactCamera(code, data, reply, flags)"));
        assertTrue(host.contains("Binder.getCallingUid() != appUid"));
        assertTrue(host.contains("CameraRuntimeClient.isCameraTransaction(code)"));
        assertTrue(shell.contains("cameras.acceptTelemetry(line)"));
        assertTrue(turn.contains("else if (cameraRuntimeHost && canceledOpen == null)"));
        assertTrue(turn.contains("else if (cameraRuntimeHost)"));
        assertFalse(CameraRuntimeClient.isCameraTransaction(CameraHelperMain.TX_SET_TURN_STATE));
        assertFalse(CameraRuntimeClient.isCameraTransaction(CameraHelperMain.TX_RETRY_ADB_AUTH));
        assertTrue(CameraRuntimeClient.isCameraTransaction(CameraHelperMain.TX_OPEN_DIRECT));
        assertTrue(CameraShellProtocol.isCallerAllowed(2000, 10123));
        assertFalse(CameraShellProtocol.isCallerAllowed(10124, 10123));
        assertFalse(TurnSignalShellProtocol.isCallerAllowed(2000, 10123));
    }

    static String source(String name) throws Exception {
        Path path = Path.of("src/main/java/com/byd/extend/" + name + ".java");
        if (!Files.exists(path)) path = Path.of("app").resolve(path);
        return new String(Files.readAllBytes(path), java.nio.charset.StandardCharsets.UTF_8);
    }
}
