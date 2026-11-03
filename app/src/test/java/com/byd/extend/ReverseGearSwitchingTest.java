package com.byd.extend;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static com.byd.extend.ReverseGearSessionPolicy.*;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/** Real preference/import and direction decisions; no fabricated OEM or Binder responses. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 33)
public final class ReverseGearSwitchingTest {
    private SharedPreferences settings(String name) {
        SharedPreferences result = RuntimeEnvironment.getApplication().getSharedPreferences(name, Context.MODE_PRIVATE);
        result.edit().clear().commit();
        return result;
    }

    @Test public void migrationPreservesOnWithoutFrontIntegrationAndDoesNotOverwriteNewChoice() {
        SharedPreferences p = settings("gear-migration");
        ReverseGearSwitching.migrate(p);
        assertEquals(ReverseGearSwitching.NONE, ReverseGearSwitching.read(p));
        p.edit().clear().putBoolean(ReverseGearSwitching.LEGACY_KEY, false).commit();
        ReverseGearSwitching.migrate(p);
        assertEquals(ReverseGearSwitching.NONE, ReverseGearSwitching.read(p));
        p.edit().clear().putBoolean(ReverseGearSwitching.LEGACY_KEY, true).commit();
        ReverseGearSwitching.migrate(p);
        SharedPreferences reopened = RuntimeEnvironment.getApplication().getSharedPreferences("gear-migration", Context.MODE_PRIVATE);
        assertEquals(ReverseGearSwitching.OURS, ReverseGearSwitching.read(reopened));
        assertFalse(reopened.contains(ReverseGearSwitching.LEGACY_KEY));
        assertFalse(ReverseCameraController.switchByGear(reopened));
        reopened.edit().putBoolean(ReverseCameraController.PREF_CENTRAL_FRONT_INTEGRATED, true).commit();
        assertTrue(ReverseCameraController.switchByGear(reopened));
        reopened.edit().putInt(ReverseGearSwitching.KEY, ReverseGearSwitching.NATIVE)
                .putBoolean(ReverseGearSwitching.LEGACY_KEY, true).commit();
        ReverseGearSwitching.migrate(reopened);
        assertEquals(ReverseGearSwitching.NATIVE, ReverseGearSwitching.read(reopened));
    }

    @Test public void presetsAndOldBackupsRestoreDestinationWithoutPartialWrites() throws Exception {
        SharedPreferences p = settings("gear-transfer");
        p.edit().putInt(ReverseGearSwitching.KEY, ReverseGearSwitching.BOTH).commit();
        String exported = CameraSettingsTransfer.exportCameraPreset(p);
        p.edit().putInt(ReverseGearSwitching.KEY, ReverseGearSwitching.NATIVE).commit();
        CameraSettingsTransfer.applyCameraPreset(p, CameraSettingsTransfer.parseCameraPreset(exported));
        assertEquals(ReverseGearSwitching.BOTH, ReverseGearSwitching.read(p));
        JSONObject oldPreset = new JSONObject(exported);
        oldPreset.getJSONObject("settings").remove(ReverseGearSwitching.KEY);
        CameraSettingsTransfer.applyCameraPreset(p, CameraSettingsTransfer.parseCameraPreset(oldPreset.toString()));
        assertEquals(ReverseGearSwitching.BOTH, ReverseGearSwitching.read(p));
        CameraSettingsTransfer.applyLegacySettings(p, CameraSettingsTransfer.parseLegacySettings(
                "<map><boolean name=\"reverse_camera_switch_by_gear\" value=\"true\"/></map>"));
        assertEquals(ReverseGearSwitching.OURS, ReverseGearSwitching.read(p));
        CameraSettingsTransfer.applyLegacySettings(p, CameraSettingsTransfer.parseLegacySettings(
                "<map><boolean name=\"reverse_camera_switch_by_gear\" value=\"false\"/></map>"));
        assertEquals(ReverseGearSwitching.NONE, ReverseGearSwitching.read(p));
        assertThrows(IllegalArgumentException.class, () -> CameraSettingsTransfer.applyLegacySettings(p,
                java.util.Map.of(ReverseGearSwitching.KEY, 4)));
        assertEquals(ReverseGearSwitching.NONE, ReverseGearSwitching.read(p));
    }

    @Test public void destinationsAndPanoramaSessionDoNotRequireAnExtendFrontOutputForNative() {
        assertFalse(ReverseGearSwitching.nativeView(ReverseGearSwitching.NONE));
        assertFalse(ReverseGearSwitching.own(ReverseGearSwitching.NONE, true));
        assertTrue(ReverseGearSwitching.nativeView(ReverseGearSwitching.NATIVE));
        assertFalse(ReverseGearSwitching.own(ReverseGearSwitching.NATIVE, true));
        assertFalse(ReverseGearSwitching.nativeView(ReverseGearSwitching.OURS));
        assertTrue(ReverseGearSwitching.own(ReverseGearSwitching.OURS, true));
        assertTrue(ReverseGearSwitching.nativeView(ReverseGearSwitching.BOTH));
        assertTrue(ReverseGearSwitching.own(ReverseGearSwitching.BOTH, true));
        assertFalse(ReverseGearSwitching.own(ReverseGearSwitching.BOTH, false));
        State state = new State();
        boolean follow = ReverseGearSwitching.followsPanorama(ReverseGearSwitching.NATIVE, false);
        assertEquals(MODE_REAR, update(state, follow, true, RAW_REVERSE, true).targetMode);
        assertEquals(MODE_FRONT, update(state, follow, true, RAW_DRIVE, true).targetMode);
        assertTrue(state.eligible);
        assertEquals(-1, update(state, follow, true, RAW_NEUTRAL, true).targetMode);
        assertEquals(MODE_FRONT, update(state, follow, true, RAW_DRIVE, true).targetMode);
        assertEquals(-1, update(state, follow, true, RAW_DRIVE, true).targetMode);
        assertEquals(MODE_REAR, update(state, follow, true, RAW_REVERSE, true).targetMode);
        assertFalse(update(state, follow, true, RAW_DRIVE, false).eligible);
    }

    @Test public void nativeCommandsCoalesceUntilConnectedAndAcknowledgedCommandsDoNotReplay() {
        NativeDirection state = new NativeDirection();
        assertFalse(state.update(false, true, RAW_REVERSE));
        assertEquals(-1, state.pendingTarget());
        assertTrue(state.update(true, true, RAW_REVERSE));
        long rear = state.revision;
        assertEquals(MODE_REAR, state.pendingTarget());
        assertTrue(state.update(true, true, RAW_DRIVE));
        assertEquals(MODE_FRONT, state.pendingTarget());
        state.acknowledge(rear);
        assertEquals(MODE_FRONT, state.pendingTarget());
        state.acknowledge(state.revision);
        assertFalse(state.update(true, true, RAW_DRIVE));
        assertEquals(-1, state.pendingTarget());
        assertFalse(state.update(true, false, -1));
        assertFalse(state.update(true, true, RAW_DRIVE));
        assertEquals(-1, state.pendingTarget());
        assertTrue(state.update(true, true, RAW_REVERSE));
        assertEquals(MODE_REAR, state.pendingTarget());
        assertTrue(state.update(true, false, -1));
        assertEquals(-1, state.pendingTarget());
        assertTrue(state.update(true, true, RAW_REVERSE));
        assertEquals(MODE_REAR, state.pendingTarget());
        assertFalse(state.update(true, true, RAW_NEUTRAL));
        assertEquals(MODE_REAR, state.pendingTarget());
        assertTrue(state.update(false, true, RAW_REVERSE));
        assertEquals(-1, state.pendingTarget());
    }

    @Test public void nativeIntentDoesNotDependOnExtendOutputsAndStopsAtPowerAndVisibilityGates() throws Exception {
        SharedPreferences p = settings("settings");
        p.edit().putBoolean(ReverseCameraController.PREF_ENABLED, true)
                .putInt(ReverseGearSwitching.KEY, ReverseGearSwitching.NATIVE).commit();
        java.util.List<java.util.Map<String, Object>> events = new java.util.ArrayList<>();
        ReverseCameraController controller = new ReverseCameraController(RuntimeEnvironment.getApplication(),
                new Handler(Looper.getMainLooper()), (kind, fields) -> {
                    if (!"reverse_native_direction".equals(kind)) return;
                    java.util.Map<String, Object> record = new java.util.HashMap<>();
                    for (int i = 0; i < fields.length; i += 2) record.put((String) fields[i], fields[i + 1]);
                    events.add(record);
                }, ignored -> {});
        // No HelperBinder, central output or surfaces: only actual telemetry and visibility inputs.
        controller.acceptEvent(new JSONObject().put("kind", "reverse_gear_state")
                .put("valid", true).put("listener_ok", true).put("raw", RAW_REVERSE));
        shadowOf(Looper.getMainLooper()).idle();
        assertTrue(events.isEmpty());
        controller.oemVisibility(true, true, "test");
        java.util.Map<String, Object> rear = events.get(events.size() - 1);
        assertEquals(MODE_REAR, rear.get("target"));
        controller.acknowledgeNativeDirection((long) rear.get("source_epoch"), (long) rear.get("revision"));
        controller.reportNativeDirection("reconnected");
        assertEquals(-1, events.get(events.size() - 1).get("target"));
        controller.acceptEvent(new JSONObject().put("kind", "reverse_gear_state")
                .put("valid", true).put("listener_ok", true).put("raw", RAW_DRIVE));
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(MODE_FRONT, events.get(events.size() - 1).get("target"));
        controller.setRuntimeAllowed(false);
        assertEquals(false, events.get(events.size() - 1).get("active"));
        assertEquals(-1, events.get(events.size() - 1).get("target"));
        controller.setRuntimeAllowed(true);
        assertEquals(MODE_FRONT, events.get(events.size() - 1).get("target"));
        controller.oemVisibility(true, false, "test");
        assertEquals(false, events.get(events.size() - 1).get("active"));
        controller.oemVisibility(true, true, "test");
        p.edit().putInt(ReverseGearSwitching.KEY, ReverseGearSwitching.NONE).commit();
        controller.gearSwitchingChanged();
        assertEquals(false, events.get(events.size() - 1).get("active"));
        controller.shutdown();
    }

    @Test public void inactiveAndNewHelperIntentsRejectLateCommands() {
        NativePanoramaController.IntentState state = new NativePanoramaController.IntentState();
        assertTrue(state.accept(100, 1, 1, true, MODE_REAR));
        assertTrue(state.accept(100, 2, 1, true, MODE_FRONT));
        assertFalse(state.accept(100, 1, 1, true, MODE_REAR));
        assertEquals(MODE_FRONT, state.target);
        assertTrue(state.accept(100, 3, 1, false, -1));
        assertFalse(state.accept(100, 2, 1, true, MODE_FRONT));
        assertFalse(state.active);
        assertTrue(state.accept(200, 0, 0, false, -1));
        assertFalse(state.accept(100, 4, 2, true, MODE_REAR));
        assertThrows(IllegalArgumentException.class, () -> state.accept(200, 1, 1, true, 2));
        assertEquals(-1, state.target);
    }
}
