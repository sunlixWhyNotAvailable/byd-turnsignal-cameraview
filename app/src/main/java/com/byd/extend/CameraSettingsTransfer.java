package com.byd.extend;

import android.content.SharedPreferences;

import org.json.JSONObject;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

/** Versioned, allowlisted camera-preset and legacy-settings transfer codec. */
public final class CameraSettingsTransfer {
    public static final int MAX_INPUT_BYTES = 1_048_576;
    private static final String SCHEMA = "byd-extend-camera-preset";
    private static final int VERSION = 2;
    private static final int LEGACY_VERSION = 1;
    private static final String SETTINGS = "settings";

    private CameraSettingsTransfer() {}

    /** Serializes every effective camera visual setting, including defaults. */
    public static String exportCameraPreset(SharedPreferences preferences) {
        if (preferences == null) throw new IllegalArgumentException("preferences is null");
        Map<String, Object> values = effectiveCameraSettings(preferences);
        JSONObject result = new JSONObject();
        try {
            result.put("schema", SCHEMA).put("version", VERSION);
            JSONObject settings = new JSONObject();
            for (Map.Entry<String, Object> entry : values.entrySet()) {
                settings.put(entry.getKey(), entry.getValue());
            }
            result.put(SETTINGS, settings);
            return result.toString();
        } catch (Exception error) {
            throw new IllegalStateException("camera preset serialization failed", error);
        }
    }

    /** Parses and validates a camera preset without changing preferences. */
    public static Map<String, Object> parseCameraPreset(String input) {
        requireInputSize(input);
        try {
            JSONObject root = new JSONObject(input);
            requireKeys(root, "schema", "version", SETTINGS);
            Object version = root.get("version");
            int parsedVersion = versionInt(version);
            if (!SCHEMA.equals(root.getString("schema"))
                    || !(version instanceof Number)
                    || !Double.isFinite(((Number) version).doubleValue())
                    || (parsedVersion != LEGACY_VERSION && parsedVersion != VERSION)) {
                throw new IllegalArgumentException("unsupported camera preset schema");
            }
            JSONObject settingsObject = root.getJSONObject(SETTINGS);
            Set<String> expected = cameraPresetKeys();
            Set<String> actual = names(settingsObject);
            for (String key : actual) {
                if (!expected.contains(key)) {
                    throw new IllegalArgumentException("unexpected camera preset field");
                }
            }
            for (String key : expected) {
                if (!actual.contains(key) && !isOptionalCameraPresetKey(key)) {
                    throw new IllegalArgumentException("incomplete camera preset");
                }
            }
            Map<String, Object> settings = new LinkedHashMap<>();
            for (String key : actual) settings.put(key, settingsObject.get(key));
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("schema", SCHEMA);
            result.put("version", parsedVersion);
            result.put(SETTINGS, settings);
            validateCameraSettings(settings);
            return result;
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalArgumentException("invalid camera preset", error);
        }
    }

    /** Parses Android SharedPreferences XML through the explicit legacy allowlist. */
    public static Map<String, Object> parseLegacySettings(String input) {
        requireInputSize(input);
        rejectDoctype(input);
        try {
            DocumentBuilderFactory factory = secureDocumentBuilderFactory();
            javax.xml.parsers.DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setEntityResolver((publicId, systemId) -> {
                throw new SAXException("external entity is not allowed");
            });
            Document document = builder.parse(new InputSource(new StringReader(input)));
            Element root = document.getDocumentElement();
            if (root == null || !"map".equals(root.getTagName())) {
                throw new IllegalArgumentException("legacy settings root must be map");
            }
            Map<String, Object> result = new LinkedHashMap<>();
            NodeList children = root.getChildNodes();
            for (int index = 0; index < children.getLength(); index++) {
                Node node = children.item(index);
                if (node.getNodeType() != Node.ELEMENT_NODE) continue;
                Element element = (Element) node;
                String key = element.getAttribute("name");
                if (!isLegacyAllowedKey(key)) continue;
                if (result.containsKey(key)) throw new IllegalArgumentException("duplicate legacy key");
                result.put(key, readLegacyValue(element));
            }
            validateLegacySettings(result);
            return result;
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalArgumentException("invalid legacy settings XML", error);
        }
    }

    /** Applies a fully validated camera preset in one preferences transaction. */
    public static void applyCameraPreset(
            SharedPreferences preferences, Map<String, Object> parsed) {
        if (preferences == null || parsed == null) throw new IllegalArgumentException("null argument");
        Map<String, Object> settings = cameraSettingsMap(parsed);
        validateCameraSettings(settings);
        // Mirror's active state is replaced only when the file carries its
        // active group.  A v1 file, or a v2 file that omits the optional
        // group, must leave active Mirror and its local hide/preset state
        // untouched.
        boolean preserveMirror = !containsMirror(settings);
        SharedPreferences.Editor editor = preferences.edit();
        for (String key : cameraClearKeys(preserveMirror)) if (preferences.contains(key)) editor.remove(key);
        preserveMissingFrameAspects(editor, preferences, settings);
        preserveMissingPanoramaSuppression(editor, preferences, settings);
        for (Map.Entry<String, Object> entry : settings.entrySet()) {
            putCameraValue(editor, entry.getKey(), entry.getValue());
        }
        if (!editor.commit()) throw new IllegalStateException("camera preset commit failed");
    }

    /** Applies allowlisted legacy settings in one preferences transaction. */
    public static void applyLegacySettings(
            SharedPreferences preferences, Map<String, Object> values) {
        if (preferences == null || values == null) throw new IllegalArgumentException("null argument");
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            if (!isLegacyAllowedKey(entry.getKey())) {
                throw new IllegalArgumentException("legacy key is not allowlisted: " + entry.getKey());
            }
            copy.put(entry.getKey(), entry.getValue());
        }
        validateLegacySettings(copy);
        SharedPreferences.Editor editor = preferences.edit();
        for (String key : preferences.getAll().keySet()) {
            if (isLegacyAllowedKey(key)) editor.remove(key);
        }
        preserveMissingFrameAspects(editor, preferences, copy);
        preserveMissingPanoramaSuppression(editor, preferences, copy);
        for (Map.Entry<String, Object> entry : copy.entrySet()) put(editor, entry.getKey(), entry.getValue());
        if (!editor.commit()) throw new IllegalStateException("legacy settings commit failed");
    }

    private static void preserveMissingFrameAspects(SharedPreferences.Editor editor,
            SharedPreferences preferences, Map<String, Object> imported) {
        for (CameraProfile profile : CameraProfile.values()) {
            String key = BlindSpotOverlayController.frameAspectKey(profile);
            if (!imported.containsKey(key)) {
                editor.putFloat(key, BlindSpotOverlayController.readFrameAspect(preferences, profile));
            }
        }
    }

    private static void preserveMissingPanoramaSuppression(
            SharedPreferences.Editor editor, SharedPreferences preferences,
            Map<String, Object> imported) {
        for (String key : new String[]{
                BlindSpotOverlayController.PREF_REAR_SUPPRESS_WHILE_PANORAMA,
                BlindSpotOverlayController.PREF_FRONT_SUPPRESS_WHILE_PANORAMA,
                RearviewMirrorSettings.PREF_SUPPRESS_WHILE_PANORAMA}) {
            if (!imported.containsKey(key)) editor.putBoolean(key, bool(preferences, key, true));
        }
    }

    private static Map<String, Object> effectiveCameraSettings(SharedPreferences p) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        out.put(BlindSpotOverlayController.PREF_ENABLED, bool(p, BlindSpotOverlayController.PREF_ENABLED, false));
        out.put(BlindSpotOverlayController.PREF_FRONT_ENABLED, bool(p, BlindSpotOverlayController.PREF_FRONT_ENABLED, false));
        out.put(BlindSpotOverlayController.PREF_REAR_SUPPRESS_WHILE_PANORAMA,
                BlindSpotOverlayController.readPanoramaSuppression(p, false));
        out.put(BlindSpotOverlayController.PREF_FRONT_SUPPRESS_WHILE_PANORAMA,
                BlindSpotOverlayController.readPanoramaSuppression(p, true));
        out.put(BlindSpotOverlayController.PREF_FRONT_TURN_REQUIRED, bool(p, BlindSpotOverlayController.PREF_FRONT_TURN_REQUIRED, true));
        out.put(BlindSpotOverlayController.PREF_REAR_SHARP_TURN_ENABLED, bool(p, BlindSpotOverlayController.PREF_REAR_SHARP_TURN_ENABLED, false));
        out.put(BlindSpotOverlayController.PREF_REAR_BSD_ONLY, bool(p, BlindSpotOverlayController.PREF_REAR_BSD_ONLY, false));
        out.put(BlindSpotOverlayController.PREF_WARNING_MODE, warningMode(p));
        out.put(BlindSpotOverlayController.PREF_CORNER_RADIUS, BlindSpotOverlayController.readCornerRadius(p));
        out.put(BlindSpotOverlayController.PREF_TRANSPARENCY_PERCENT, BlindSpotOverlayController.readTransparencyPercent(p));
        out.put(CameraBufferQuality.PREF_QUALITY, CameraBufferQuality.load(p));
        out.put(ParkingCameraSettings.PREF_ALLOW_DURING_REVERSE,
                ParkingCameraSettings.readAllowDuringReverse(p));
        out.put("parking_camera_scale_sync", bool(p, "parking_camera_scale_sync", false));
        out.put(ReverseCameraController.PREF_ENABLED,
                bool(p, ReverseCameraController.PREF_ENABLED, ReverseCameraController.DEFAULT_ENABLED));
        out.put(ReverseCameraController.PREF_BACKGROUND_VISIBLE,
                ReverseCameraController.loadVisibility(p, ReverseCameraLayout.BACKGROUND_PANE_ID));
        out.put(ReverseCameraController.PREF_WIDGET_VISIBLE,
                ReverseCameraController.loadWidgetVisible(p));
        out.put(ReverseCameraController.PREF_CENTRAL_FRONT_INTEGRATED,
                ReverseCameraController.loadCentralFrontIntegrated(p));
        addMirror(out, p);

        for (CameraProfile profile : CameraProfile.values()) {
            DirectCameraCrop raw = DirectCameraCrop.load(p, profile);
            DirectCameraCrop corrected = DirectCameraCrop.loadCorrected(p, profile, raw);
            CameraDewarpConfig dewarp = CameraDewarpConfig.loadForProfile(p, profile);
            addBlind(out, profile, raw, corrected, dewarp,
                    BlindSpotOverlayController.readPosition(p, profile, false),
                    BlindSpotOverlayController.readPosition(p, profile, true),
                    BlindSpotOverlayController.readScale(p, profile),
                    BlindSpotOverlayController.readTarget(p, profile),
                    BlindSpotOverlayController.readFrameAspect(p, profile));
            if (p.contains(BlindSpotOverlayController.placementWidthKey(profile))
                    && p.contains(BlindSpotOverlayController.placementHeightKey(profile))) {
                out.put(BlindSpotOverlayController.placementWidthKey(profile),
                        floatValue(p, BlindSpotOverlayController.placementWidthKey(profile), 0.0f));
                out.put(BlindSpotOverlayController.placementHeightKey(profile),
                        floatValue(p, BlindSpotOverlayController.placementHeightKey(profile), 0.0f));
            }
            addOptionalCorrectedAspect(out, p, DirectCameraCrop.correctedAspectKey(profile));
        }
        for (ParkingCameraProfile profile : ParkingCameraProfile.values()) {
            DirectCameraCrop raw = DirectCameraCrop.load(p, profile);
            DirectCameraCrop corrected = DirectCameraCrop.loadCorrected(p, profile, raw);
            addParking(out, p, profile, raw, corrected, CameraDewarpConfig.loadForParking(p, profile));
            addOptionalCorrectedAspect(out, p, DirectCameraCrop.correctedAspectKey(profile));
        }

        ReverseCameraLayout layout = ReverseCameraController.loadRawLayout(p);
        addRect(out, "reverse_camera_background_left", layout.background);
        addRect(out, "reverse_camera_widget_left", layout.widget);
        for (ReverseCameraLayout.Pane pane : layout.panes()) {
            String prefix = "reverse_camera_" + pane.cameraIndex + "_";
            addRect(out, prefix + "left", pane.destination);
            putReverseCrop(out, p, pane.cameraIndex, false, pane.sourceCrop);
            putReverseCrop(out, p, pane.cameraIndex, true,
                    ReverseCameraController.loadCorrectedSourceCrop(p, pane.cameraIndex,
                            ReverseCameraLayout.centeredSourceCrop(pane.sourceCrop)));
            out.put(prefix + "rotation_degrees", pane.rotationDegrees);
            out.put(prefix + "display_mode", pane.displayMode);
            out.put(prefix + "mirror", pane.mirrorHorizontally);
            out.put(prefix + "visible", ReverseCameraController.loadVisibility(p, pane.cameraIndex));
            out.put("reverse_camera_z_" + pane.zOrder, pane.cameraIndex);
            putDewarp(out, "camera_dewarp_v3_reverse_" + pane.cameraIndex + "_",
                    CameraDewarpConfig.loadForReverse(p, pane.cameraIndex));
        }
        ReverseCameraLayout front = ReverseCameraController.loadFrontRawLayout(p);
        for (int index : new int[]{ReverseCameraLayout.REAR_CAMERA_INDEX,
                ReverseCameraLayout.REAR_LEFT_CAMERA_INDEX,
                ReverseCameraLayout.REAR_RIGHT_CAMERA_INDEX}) {
            ReverseCameraLayout.Pane pane = front.pane(index);
            String prefix = "reverse_camera_front_" + index + "_";
            putReverseFrontCrop(out, p, index, false, pane.sourceCrop);
            putReverseFrontCrop(out, p, index, true,
                    ReverseCameraController.loadFrontCorrectedSourceCrop(p, index));
            out.put(prefix + "rotation_degrees", pane.rotationDegrees);
            out.put(prefix + "display_mode", pane.displayMode);
            out.put(prefix + "mirror", pane.mirrorHorizontally);
            out.put(prefix + "integrated", ReverseCameraController.loadFrontIntegrated(p, index));
            putDewarp(out, "camera_dewarp_v3_reverse_front_" + index + "_",
                    CameraDewarpConfig.loadForReverseFront(p, index));
        }
        return out;
    }

    private static void addBlind(Map<String, Object> out, CameraProfile profile,
            DirectCameraCrop raw, DirectCameraCrop corrected, CameraDewarpConfig dewarp,
            float x, float y, int scale, int target, float aspect) {
        for (int field = 0; field < 8; field++) out.put(DirectCameraCrop.preferenceKey(profile, field), cropField(raw, field));
        String correctedPrefix = "direct_crop_v3_corrected_" + profile.id + "_";
        out.put(correctedPrefix + "left", corrected.left); out.put(correctedPrefix + "top", corrected.top);
        out.put(correctedPrefix + "width", corrected.width); out.put(correctedPrefix + "height", corrected.height);
        out.put(BlindSpotOverlayController.positionKey(profile, false), x);
        out.put(BlindSpotOverlayController.positionKey(profile, true), y);
        out.put(BlindSpotOverlayController.scaleKey(profile), scale);
        out.put(BlindSpotOverlayController.targetKey(profile), target);
        out.put(BlindSpotOverlayController.frameAspectKey(profile), aspect);
        putDewarp(out, "camera_dewarp_v3_overlay_" + profile.wireName + "_", dewarp);
    }

    /** v2's active independent Mirror group; local hide/preset state is never exported. */
    private static void addMirror(Map<String, Object> out, SharedPreferences preferences) {
        RearviewMirrorSettings.Settings value = new RearviewMirrorSettings(preferences).load();
        out.put(RearviewMirrorSettings.PREF_ENABLED, value.enabled);
        out.put(RearviewMirrorSettings.PREF_SUPPRESS_WHILE_PANORAMA,
                RearviewMirrorSettings.suppressWhilePanorama(preferences));
        out.put(RearviewMirrorSettings.PREF_TARGET,
                value.target == RearviewMirrorSettings.TARGET_CLUSTER ? "Cluster" : "Tablet");
        out.put(RearviewMirrorSettings.PREF_X, value.placement.x * 100.0f);
        out.put(RearviewMirrorSettings.PREF_Y, value.placement.y * 100.0f);
        out.put(RearviewMirrorSettings.PREF_WIDTH, value.placement.width * 100.0f);
        out.put(RearviewMirrorSettings.PREF_HEIGHT, value.placement.height * 100.0f);
        out.put(RearviewMirrorSettings.PREF_BORDER_DP, value.borderDp);
        out.put(RearviewMirrorSettings.PREF_BORDER_ARGB, value.borderArgb);
        addMirrorCalibration(out, value.calibration);
    }

    private static void addMirrorCalibration(
            Map<String, Object> out, RearviewMirrorSettings.Calibration value) {
        out.put("mirror_original_x", value.raw.x * 100.0f);
        out.put("mirror_original_y", value.raw.y * 100.0f);
        out.put("mirror_original_width", value.raw.width * 100.0f);
        out.put("mirror_original_height", value.raw.height * 100.0f);
        out.put("mirror_correction", value.enabled);
        out.put("mirror_fov", value.fovDegrees);
        out.put("mirror_projection", value.projection);
        out.put("mirror_corrected_x", value.corrected.x * 100.0f);
        out.put("mirror_corrected_y", value.corrected.y * 100.0f);
        out.put("mirror_corrected_width", value.corrected.width * 100.0f);
        out.put("mirror_corrected_height", value.corrected.height * 100.0f);
        out.put("mirror_mirrored", value.mirrored);
        out.put("mirror_output_mode", value.rotationMode);
        out.put("mirror_rotation", value.rotationDegrees);
    }

    private static void addParking(Map<String, Object> out, SharedPreferences p,
            ParkingCameraProfile profile, DirectCameraCrop raw, DirectCameraCrop corrected,
            CameraDewarpConfig dewarp) {
        String prefix = "parking_camera_" + profile.wireName.toLowerCase(java.util.Locale.US) + "_";
        ParkingCameraSettings.Rule rule = ParkingCameraSettings.readRule(p, profile);
        out.put(prefix + "enabled", rule.enabled); out.put(prefix + "add_central", rule.addCentral);
        int[] defaultScale = {
                CameraDefaults.parkingScalePercent(), CameraDefaults.parkingScalePercent(),
                CameraDefaults.parkingScalePercent(), CameraDefaults.parkingScalePercent(),
                CameraDefaults.parkingScalePercent(), CameraDefaults.parkingScalePercent(),
                CameraDefaults.parkingScalePercent(), CameraDefaults.parkingScalePercent()};
        float[] defaultX = {0.0f, 0.5f, 1.0f, 1.0f, 0.5f, 0.0f, 0.0f, 1.0f};
        float[] defaultY = {0.0f, 0.0f, 0.0f, 1.0f, 1.0f, 1.0f, 0.5f, 0.5f};
        out.put(prefix + "scale", intValue(p, prefix + "scale", defaultScale[profile.id]));
        out.put(prefix + "x", floatValue(p, prefix + "x", defaultX[profile.id]));
        out.put(prefix + "y", floatValue(p, prefix + "y", defaultY[profile.id]));
        for (int field = 0; field < 8; field++) out.put(parkingCropKey(profile, field), cropField(raw, field));
        String correctedPrefix = "parking_direct_crop_v1_" + profile.wireName.toLowerCase(java.util.Locale.US) + "_corrected_";
        out.put(correctedPrefix + "x", corrected.left); out.put(correctedPrefix + "y", corrected.top);
        out.put(correctedPrefix + "width", corrected.width); out.put(correctedPrefix + "height", corrected.height);
        putDewarp(out, "camera_dewarp_v3_parking_" + profile.wireName.toLowerCase(java.util.Locale.US) + "_", dewarp);
    }

    private static Object cropField(DirectCameraCrop crop, int field) {
        switch (field) {
            case 0: return crop.left; case 1: return crop.top; case 2: return crop.width; case 3: return crop.height;
            case 4: return crop.aspectMode; case 5: return crop.rotationDegrees; case 6: return crop.rotationMode;
            case 7: return crop.mirrorHorizontally; default: throw new IllegalArgumentException("crop field");
        }
    }

    private static void addOptionalCorrectedAspect(
            Map<String, Object> out, SharedPreferences preferences, String key) {
        if (!preferences.contains(key)) return;
        try {
            int value = preferences.getInt(key, -1);
            if (value == DirectCameraCrop.ASPECT_FREE) out.put(key, value);
        } catch (RuntimeException ignored) {
            // Invalid optional markers are omitted from exports and fail closed on import.
        }
    }

    private static String parkingCropKey(ParkingCameraProfile profile, int field) {
        String prefix = "parking_direct_crop_v1_"
                + profile.wireName.toLowerCase(java.util.Locale.US) + "_";
        switch (field) {
            case 0: return prefix + "x";
            case 1: return prefix + "y";
            case 2: return prefix + "width";
            case 3: return prefix + "height";
            case 4: return prefix + "aspect";
            case 5: return prefix + "rotation";
            case 6: return prefix + "rotation_mode";
            case 7: return prefix + "mirror";
            default: throw new IllegalArgumentException("crop field");
        }
    }

    private static void putReverseCrop(Map<String, Object> out, SharedPreferences p,
            int index, boolean corrected, ReverseCameraLayout.Rect rect) {
        String prefix = corrected
                ? "reverse_camera_" + index + "_corrected_v3_crop_left"
                : ReverseCameraController.sourceCropKey(index, "left", false);
        String base = prefix.substring(0, prefix.length() - 4);
        out.put(base + "left", rect.left); out.put(base + "top", rect.top);
        out.put(base + "width", rect.width); out.put(base + "height", rect.height);
    }

    private static void putReverseFrontCrop(Map<String, Object> out, SharedPreferences p,
            int index, boolean corrected, ReverseCameraLayout.Rect rect) {
        String base = "reverse_camera_front_" + index + "_" + (corrected ? "corrected_crop_" : "crop_");
        out.put(base + "left", rect.left); out.put(base + "top", rect.top);
        out.put(base + "width", rect.width); out.put(base + "height", rect.height);
    }

    private static void addRect(Map<String, Object> out, String leftKey, ReverseCameraLayout.Rect rect) {
        String prefix = leftKey.substring(0, leftKey.length() - "left".length());
        out.put(prefix + "left", rect.left); out.put(prefix + "top", rect.top);
        out.put(prefix + "width", rect.width); out.put(prefix + "height", rect.height);
    }

    private static void putDewarp(Map<String, Object> out, String prefix, CameraDewarpConfig value) {
        out.put(prefix + "enabled", value.enabled); out.put(prefix + "fov", value.fovDegrees);
        out.put(prefix + "projection", value.projection);
    }

    private static Map<String, Object> cameraSettingsMap(Map<String, Object> parsed) {
        Object version = parsed.get("version");
        int parsedVersion = version instanceof Number ? versionInt(version) : -1;
        if (!SCHEMA.equals(parsed.get("schema"))
                || (parsedVersion != LEGACY_VERSION && parsedVersion != VERSION)) {
            throw new IllegalArgumentException("unsupported camera preset schema");
        }
        Object value = parsed.get(SETTINGS);
        if (!(value instanceof Map)) throw new IllegalArgumentException("camera settings missing");
        Map<?, ?> source = (Map<?, ?>) value;
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (!(entry.getKey() instanceof String)) throw new IllegalArgumentException("invalid camera key");
            result.put((String) entry.getKey(), entry.getValue());
        }
        for (String key : result.keySet()) {
            if (!cameraPresetKeys().contains(key)) {
                throw new IllegalArgumentException("unexpected camera preset field");
            }
        }
        for (String key : cameraPresetKeys()) {
            if (!result.containsKey(key) && !isOptionalCameraPresetKey(key)) {
                throw new IllegalArgumentException("incomplete camera preset");
            }
        }
        return result;
    }

    private static void validateCameraSettings(Map<String, Object> values) {
        for (String key : values.keySet()) {
            if (!cameraPresetKeys().contains(key)) {
                throw new IllegalArgumentException("unexpected camera preset field");
            }
        }
        for (String key : cameraPresetKeys()) {
            if (!values.containsKey(key) && !isOptionalCameraPresetKey(key)) {
                throw new IllegalArgumentException("incomplete camera preset");
            }
        }
        for (Map.Entry<String, Object> entry : values.entrySet()) validateCameraValue(entry.getKey(), entry.getValue());
        requireMirror(values);
        for (CameraProfile profile : CameraProfile.values()) {
            String key = DirectCameraCrop.preferenceKey(profile, 0);
            requireCrop(values, key, profile, false);
            requireCorrected(values, "direct_crop_v3_corrected_" + profile.id + "_", profile, false);
            requireDewarp(values, "camera_dewarp_v3_overlay_" + profile.wireName + "_");
            requireBlindPlacement(values, profile);
        }
        for (ParkingCameraProfile profile : ParkingCameraProfile.values()) {
            String prefix = "parking_direct_crop_v1_" + profile.wireName.toLowerCase(java.util.Locale.US) + "_";
            requireParkingCrop(values, prefix, false);
            requireParkingCrop(values, prefix + "corrected_", true);
            requireDewarp(values, "camera_dewarp_v3_parking_"
                    + profile.wireName.toLowerCase(java.util.Locale.US) + "_");
        }
        for (int index : new int[]{1, 2, 3}) {
            String prefix = "reverse_camera_" + index + "_";
            requireRect(values, prefix, true);
            requireRect(values, ReverseCameraController.sourceCropKey(index, "left", false).substring(0,
                    ReverseCameraController.sourceCropKey(index, "left", false).length() - 4), false);
            requireRect(values, "reverse_camera_" + index + "_corrected_v3_crop_", false);
            requireDewarp(values, "camera_dewarp_v3_reverse_" + index + "_");
        }
        for (int index : new int[]{1, 2, 3}) {
            if (index == ReverseCameraLayout.REAR_CAMERA_INDEX
                    && !values.containsKey("reverse_camera_front_1_crop_left")) {
                continue;
            }
            requireRect(values, "reverse_camera_front_" + index + "_crop_", false);
            requireRect(values, "reverse_camera_front_" + index + "_corrected_crop_", false);
            requireDewarp(values, "camera_dewarp_v3_reverse_front_" + index + "_");
        }
        requireRect(values, "reverse_camera_background_", true);
        requireRect(values, "reverse_camera_widget_", true);
        requireReverseZOrder(values);
    }

    private static void requireReverseZOrder(Map<String, Object> values) {
        boolean[] zSeen = new boolean[4];
        for (int z = 0; z < 3; z++) {
            String key = "reverse_camera_z_" + z;
            int camera = values.containsKey(key) ? integer(values, key) : z + 1;
            if (camera < 1 || camera > 3) throw new IllegalArgumentException("invalid z order");
            if (zSeen[camera]) throw new IllegalArgumentException("duplicate reverse z order");
            zSeen[camera] = true;
        }
    }

    private static void validateCameraValue(String key, Object value) {
        if (value == null || value == JSONObject.NULL) throw new IllegalArgumentException("null camera value");
        if (isStringKey(key)) {
            if (!(value instanceof String)
                    || (!RearviewMirrorSettings.PREF_TARGET.equals(key)
                    || !("Tablet".equalsIgnoreCase((String) value)
                    || "Cluster".equalsIgnoreCase((String) value)))) {
                throw new IllegalArgumentException("string required: " + key);
            }
            return;
        }
        if (isBooleanKey(key)) { if (!(value instanceof Boolean)) throw new IllegalArgumentException("boolean required: " + key); return; }
        if (isIntegerKey(key)) {
            int number = intValue(value, key);
            if ((key.endsWith("_scale") || key.endsWith("_scale_percent"))
                    && (number < BlindSpotOverlayController.MIN_SCALE_PERCENT
                    || number > BlindSpotOverlayController.MAX_SCALE_PERCENT)) throw new IllegalArgumentException("invalid scale");
            if (key.endsWith("_target") && !CameraDisplayTarget.isValid(number)) throw new IllegalArgumentException("invalid display target");
            if (key.equals(BlindSpotOverlayController.PREF_CORNER_RADIUS)
                    && (number < 0 || number > BlindSpotOverlayController.MAX_CORNER_RADIUS_DP)) throw new IllegalArgumentException("invalid corner radius");
            if (key.equals(BlindSpotOverlayController.PREF_TRANSPARENCY_PERCENT)
                    && (number < 0 || number > 100)) throw new IllegalArgumentException("invalid transparency");
            if (key.equals(CameraBufferQuality.PREF_QUALITY) && !CameraBufferQuality.isValid(number)) throw new IllegalArgumentException("invalid quality");
            if (key.equals(BlindSpotOverlayController.PREF_WARNING_MODE)
                    && (number < CameraShellProtocol.WARNING_MODE_OFF || number > CameraShellProtocol.WARNING_MODE_PULSE)) throw new IllegalArgumentException("invalid warning mode");
            if (key.endsWith("_rotation_degrees") && !CameraRotation.isValid(number)) throw new IllegalArgumentException("invalid rotation");
            if (key.endsWith("_display_mode") && !ReverseCameraLayout.isValidDisplayMode(number)) throw new IllegalArgumentException("invalid display mode");
            if (key.startsWith("reverse_camera_z_") && (number < 1 || number > 3)) throw new IllegalArgumentException("invalid z order");
            return;
        }
        float number = floatValue(value, key);
        if (key.startsWith("mirror_")
                && (key.endsWith("_x") || key.endsWith("_y") || key.endsWith("_width")
                || key.endsWith("_height"))
                && (number < 0.0f || number > 100.0f)) {
            throw new IllegalArgumentException("invalid mirror geometry");
        }
        if (isBlindPlacementKey(key) && (number < 0.0f || number > 1.0f)) {
            throw new IllegalArgumentException("invalid blind placement");
        }
        if (!key.startsWith("mirror_") && (key.endsWith("_x") || key.endsWith("_y"))
                && (number < 0.0f || number > 1.0f)) throw new IllegalArgumentException("invalid position");
        if (key.endsWith("_frame_aspect") && (!(number > 0.0f) || number > 100.0f)) throw new IllegalArgumentException("invalid frame aspect");
    }

    private static void requireCrop(Map<String, Object> values, String firstKey,
            CameraProfile profile, boolean parking) {
        String prefix = firstKey.substring(0, firstKey.length() - "x".length());
        DirectCameraCrop.requireNormalized(number(values, prefix + "x"), number(values, prefix + "y"),
                number(values, prefix + "width"), number(values, prefix + "height"),
                integer(values, prefix + "aspect"), integer(values, prefix + "rotation"),
                integer(values, prefix + "rotation_mode"));
        if (!(values.get(prefix + "mirror") instanceof Boolean)) throw new IllegalArgumentException("crop mirror required");
    }

    private static void requireMirror(Map<String, Object> values) {
        boolean present = false;
        for (String key : values.keySet()) {
            if (key.startsWith("mirror_")) {
                present = true;
                break;
            }
        }
        if (!present) return; // v1 presets predate the independent Mirror group.

        String[] required = {
                RearviewMirrorSettings.PREF_ENABLED,
                RearviewMirrorSettings.PREF_TARGET,
                RearviewMirrorSettings.PREF_X, RearviewMirrorSettings.PREF_Y,
                RearviewMirrorSettings.PREF_WIDTH, RearviewMirrorSettings.PREF_HEIGHT,
                RearviewMirrorSettings.PREF_BORDER_DP,
                RearviewMirrorSettings.PREF_BORDER_ARGB,
                "mirror_original_x", "mirror_original_y", "mirror_original_width",
                "mirror_original_height", "mirror_correction", "mirror_fov",
                "mirror_projection", "mirror_corrected_x", "mirror_corrected_y",
                "mirror_corrected_width", "mirror_corrected_height", "mirror_mirrored",
                "mirror_output_mode", "mirror_rotation"};
        for (String key : required) {
            if (!values.containsKey(key)) throw new IllegalArgumentException("incomplete mirror group");
        }
        String target = (String) values.get(RearviewMirrorSettings.PREF_TARGET);
        if (!"Tablet".equalsIgnoreCase(target) && !"Cluster".equalsIgnoreCase(target)) {
            throw new IllegalArgumentException("invalid mirror target");
        }
        float x = number(values, RearviewMirrorSettings.PREF_X) / 100.0f;
        float y = number(values, RearviewMirrorSettings.PREF_Y) / 100.0f;
        float width = number(values, RearviewMirrorSettings.PREF_WIDTH) / 100.0f;
        float height = number(values, RearviewMirrorSettings.PREF_HEIGHT) / 100.0f;
        if (width < CameraPlacement.MIN_SIZE || height < CameraPlacement.MIN_SIZE) {
            throw new IllegalArgumentException("mirror placement below minimum");
        }
        CameraPlacement.of(x, y, width, height);
        if (integer(values, RearviewMirrorSettings.PREF_BORDER_DP)
                < RearviewMirrorSettings.MIN_BORDER_DP
                || integer(values, RearviewMirrorSettings.PREF_BORDER_DP)
                > RearviewMirrorSettings.MAX_BORDER_DP) {
            throw new IllegalArgumentException("invalid mirror border");
        }
        requireMirrorCalibration(values, "mirror_original_", "mirror_corrected_", "mirror_");
    }

    private static void requireMirrorCalibration(
            Map<String, Object> values, String rawPrefix, String correctedPrefix,
            String valuePrefix) {
        CameraPlacement.source(number(values, rawPrefix + "x") / 100.0f,
                number(values, rawPrefix + "y") / 100.0f,
                number(values, rawPrefix + "width") / 100.0f,
                number(values, rawPrefix + "height") / 100.0f);
        CameraPlacement.source(number(values, correctedPrefix + "x") / 100.0f,
                number(values, correctedPrefix + "y") / 100.0f,
                number(values, correctedPrefix + "width") / 100.0f,
                number(values, correctedPrefix + "height") / 100.0f);
        int fov = integer(values, valuePrefix + "fov");
        int projection = integer(values, valuePrefix + "projection");
        if (fov < CameraDewarpConfig.MIN_FOV_DEGREES || fov > CameraDewarpConfig.MAX_FOV_DEGREES
                || !CameraDewarpConfig.isValidProjection(projection)) {
            throw new IllegalArgumentException("invalid mirror calibration");
        }
        int mode = integer(values, valuePrefix + "output_mode");
        int rotation = integer(values, valuePrefix + "rotation");
        if (!CameraRotation.isValidMode(mode) || !CameraRotation.isValid(rotation)) {
            throw new IllegalArgumentException("invalid mirror output");
        }
    }

    private static void requireBlindPlacement(Map<String, Object> values, CameraProfile profile) {
        String widthKey = BlindSpotOverlayController.placementWidthKey(profile);
        String heightKey = BlindSpotOverlayController.placementHeightKey(profile);
        boolean hasWidth = values.containsKey(widthKey);
        boolean hasHeight = values.containsKey(heightKey);
        if (!hasWidth && !hasHeight) return; // v1 anchor/scale representation
        if (!hasWidth || !hasHeight) {
            throw new IllegalArgumentException("incomplete blind placement");
        }
        CameraPlacement.of(
                number(values, BlindSpotOverlayController.positionKey(profile, false)),
                number(values, BlindSpotOverlayController.positionKey(profile, true)),
                number(values, widthKey), number(values, heightKey));
    }

    private static void requireCorrected(Map<String, Object> values, String prefix,
            CameraProfile profile, boolean parking) {
        int rawAspect = profile == null ? DirectCameraCrop.ASPECT_FREE : integer(values,
                DirectCameraCrop.preferenceKey(profile, 4));
        String marker = prefix + "aspect";
        if (values.containsKey(marker)
                && integer(values, marker) != DirectCameraCrop.ASPECT_FREE) {
            throw new IllegalArgumentException("corrected geometry marker must be FREE");
        }
        int aspect = values.containsKey(marker) ? integer(values, marker) : rawAspect;
        requireCorrectedGeometry(values, prefix, aspect, profile == null ? 0 : integer(values,
                DirectCameraCrop.preferenceKey(profile, 5)), profile == null ? 0 : integer(values,
                DirectCameraCrop.preferenceKey(profile, 6)));
    }

    private static void requireParkingCrop(Map<String, Object> values, String prefix, boolean corrected) {
        if (corrected) {
            String marker = prefix + "aspect";
            if (values.containsKey(marker)
                    && integer(values, marker) != DirectCameraCrop.ASPECT_FREE) {
                throw new IllegalArgumentException("corrected geometry marker must be FREE");
            }
            DirectCameraCrop.requireNormalized(number(values, prefix + "x"), number(values, prefix + "y"),
                    number(values, prefix + "width"), number(values, prefix + "height"),
                    DirectCameraCrop.ASPECT_FREE, 0, CameraRotation.MODE_FIT);
            return;
        }
        DirectCameraCrop.requireNormalized(number(values, prefix + "x"), number(values, prefix + "y"),
                number(values, prefix + "width"), number(values, prefix + "height"),
                integer(values, prefix + "aspect"), integer(values, prefix + "rotation"),
                integer(values, prefix + "rotation_mode"));
        if (!(values.get(prefix + "mirror") instanceof Boolean)) throw new IllegalArgumentException("crop mirror required");
    }

    private static void requireCorrectedGeometry(Map<String, Object> values, String prefix,
            int aspect, int rotation, int mode) {
        DirectCameraCrop.requireNormalized(number(values, prefix + "left"), number(values, prefix + "top"),
                number(values, prefix + "width"), number(values, prefix + "height"), aspect, rotation, mode);
    }

    private static void requireRect(Map<String, Object> values, String prefix, boolean destination) {
        float x = number(values, prefix + (destination ? "left" : "left"));
        float y = number(values, prefix + "top");
        float w = number(values, prefix + (destination ? "width" : "width"));
        float h = number(values, prefix + (destination ? "height" : "height"));
        if (destination) {
            float minWidth = prefix.equals("reverse_camera_widget_")
                    ? ReverseCameraLayout.MIN_WIDGET_WIDTH
                    : ReverseCameraLayout.MIN_DESTINATION_SIZE;
            float minHeight = prefix.equals("reverse_camera_widget_")
                    ? ReverseCameraLayout.MIN_WIDGET_HEIGHT
                    : ReverseCameraLayout.MIN_DESTINATION_SIZE;
            if (x < 0 || y < 0 || w < minWidth || h < minHeight
                    || x + w > 1 || y + h > 1) {
                throw new IllegalArgumentException("invalid destination geometry");
            }
        } else {
            SourceCropPolicy.requireValid(x, y, w, h);
        }
    }

    private static void requireDewarp(Map<String, Object> values, String prefix) {
        if (!(values.get(prefix + "enabled") instanceof Boolean)) throw new IllegalArgumentException("dewarp enabled required");
        int fov = integer(values, prefix + "fov");
        int projection = integer(values, prefix + "projection");
        if (fov < CameraDewarpConfig.MIN_FOV_DEGREES || fov > CameraDewarpConfig.MAX_FOV_DEGREES
                || !CameraDewarpConfig.isValidProjection(projection)) throw new IllegalArgumentException("invalid dewarp");
    }

    private static Set<String> cameraPresetKeys() {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        keys.add(BlindSpotOverlayController.PREF_ENABLED); keys.add(BlindSpotOverlayController.PREF_FRONT_ENABLED);
        keys.add(BlindSpotOverlayController.PREF_REAR_SUPPRESS_WHILE_PANORAMA);
        keys.add(BlindSpotOverlayController.PREF_FRONT_SUPPRESS_WHILE_PANORAMA);
        keys.add(BlindSpotOverlayController.PREF_FRONT_TURN_REQUIRED); keys.add(BlindSpotOverlayController.PREF_REAR_SHARP_TURN_ENABLED);
        keys.add(BlindSpotOverlayController.PREF_REAR_BSD_ONLY); keys.add(BlindSpotOverlayController.PREF_WARNING_MODE);
        keys.add(BlindSpotOverlayController.PREF_CORNER_RADIUS); keys.add(BlindSpotOverlayController.PREF_TRANSPARENCY_PERCENT);
        keys.add(CameraBufferQuality.PREF_QUALITY); keys.add(ParkingCameraSettings.PREF_ALLOW_DURING_REVERSE);
        keys.add("parking_camera_scale_sync"); keys.add(ReverseCameraController.PREF_ENABLED);
        keys.add(ReverseCameraController.PREF_BACKGROUND_VISIBLE); keys.add(ReverseCameraController.PREF_WIDGET_VISIBLE);
        keys.add(ReverseCameraController.PREF_CENTRAL_FRONT_INTEGRATED);
        keys.add(RearviewMirrorSettings.PREF_ENABLED);
        keys.add(RearviewMirrorSettings.PREF_SUPPRESS_WHILE_PANORAMA);
        keys.add(RearviewMirrorSettings.PREF_TARGET);
        keys.add(RearviewMirrorSettings.PREF_X); keys.add(RearviewMirrorSettings.PREF_Y);
        keys.add(RearviewMirrorSettings.PREF_WIDTH); keys.add(RearviewMirrorSettings.PREF_HEIGHT);
        keys.add(RearviewMirrorSettings.PREF_BORDER_DP);
        keys.add(RearviewMirrorSettings.PREF_BORDER_ARGB);
        for (String key : new String[]{"mirror_original_x", "mirror_original_y",
                "mirror_original_width", "mirror_original_height", "mirror_correction",
                "mirror_fov", "mirror_projection", "mirror_corrected_x",
                "mirror_corrected_y", "mirror_corrected_width", "mirror_corrected_height",
                "mirror_mirrored", "mirror_output_mode", "mirror_rotation"}) keys.add(key);
        for (CameraProfile profile : CameraProfile.values()) {
            for (int field = 0; field < 8; field++) keys.add(DirectCameraCrop.preferenceKey(profile, field));
            String corrected = "direct_crop_v3_corrected_" + profile.id + "_";
            keys.add(corrected + "left"); keys.add(corrected + "top"); keys.add(corrected + "width"); keys.add(corrected + "height");
            keys.add(corrected + "aspect");
            keys.add(BlindSpotOverlayController.positionKey(profile, false)); keys.add(BlindSpotOverlayController.positionKey(profile, true));
            keys.add(BlindSpotOverlayController.placementWidthKey(profile));
            keys.add(BlindSpotOverlayController.placementHeightKey(profile));
            keys.add(BlindSpotOverlayController.scaleKey(profile)); keys.add(BlindSpotOverlayController.targetKey(profile));
            keys.add(BlindSpotOverlayController.frameAspectKey(profile));
            addDewarpKeys(keys, "camera_dewarp_v3_overlay_" + profile.wireName + "_");
        }
        for (ParkingCameraProfile profile : ParkingCameraProfile.values()) {
            String p = "parking_camera_" + profile.wireName.toLowerCase(java.util.Locale.US) + "_";
            keys.add(p + "enabled"); keys.add(p + "add_central"); keys.add(p + "scale"); keys.add(p + "x"); keys.add(p + "y");
            for (int field = 0; field < 8; field++) keys.add(parkingCropKey(profile, field));
            String c = "parking_direct_crop_v1_" + profile.wireName.toLowerCase(java.util.Locale.US) + "_corrected_";
            keys.add(c + "x"); keys.add(c + "y"); keys.add(c + "width"); keys.add(c + "height");
            keys.add(c + "aspect");
            addDewarpKeys(keys, "camera_dewarp_v3_parking_" + profile.wireName.toLowerCase(java.util.Locale.US) + "_");
        }
        for (int index : new int[]{1, 2, 3}) {
            String p = "reverse_camera_" + index + "_";
            keys.add(p + "left"); keys.add(p + "top"); keys.add(p + "width"); keys.add(p + "height");
            keys.add(p + "rotation_degrees"); keys.add(p + "display_mode"); keys.add(p + "mirror"); keys.add(p + "visible");
            for (String field : new String[]{"left", "top", "width", "height"}) {
                keys.add(ReverseCameraController.sourceCropKey(index, field, false));
                keys.add("reverse_camera_" + index + "_corrected_v3_crop_" + field);
            }
            keys.add("reverse_camera_z_0"); keys.add("reverse_camera_z_1"); keys.add("reverse_camera_z_2");
            addDewarpKeys(keys, "camera_dewarp_v3_reverse_" + index + "_");
        }
        for (String prefix : new String[]{"reverse_camera_background_", "reverse_camera_widget_"}) {
            keys.add(prefix + "left"); keys.add(prefix + "top"); keys.add(prefix + "width"); keys.add(prefix + "height");
        }
        for (int index : new int[]{1, 2, 3}) {
            String p = "reverse_camera_front_" + index + "_";
            for (String field : new String[]{"left", "top", "width", "height"}) {
                keys.add(p + "crop_" + field); keys.add(p + "corrected_crop_" + field);
            }
            keys.add(p + "rotation_degrees"); keys.add(p + "display_mode"); keys.add(p + "mirror"); keys.add(p + "integrated");
            addDewarpKeys(keys, "camera_dewarp_v3_reverse_front_" + index + "_");
        }
        return Collections.unmodifiableSet(keys);
    }

    private static boolean isOptionalCameraPresetKey(String key) {
        return key != null && (key.startsWith("reverse_camera_front_1_")
                || key.endsWith("_frame_aspect")
                || isBlindPlacementKey(key)
                || key.startsWith("camera_dewarp_v3_reverse_front_1_")
                || key.equals(BlindSpotOverlayController.PREF_REAR_SUPPRESS_WHILE_PANORAMA)
                || key.equals(BlindSpotOverlayController.PREF_FRONT_SUPPRESS_WHILE_PANORAMA)
                || key.equals(RearviewMirrorSettings.PREF_SUPPRESS_WHILE_PANORAMA)
                || key.startsWith("mirror_")
                || key.matches("direct_crop_v3_corrected_[0-9]+_aspect")
                || key.startsWith("parking_direct_crop_v1_") && key.endsWith("_corrected_aspect"));
    }

    private static boolean isBlindPlacementKey(String key) {
        return BlindSpotOverlayController.PREF_LEFT_WIDTH.equals(key)
                || BlindSpotOverlayController.PREF_LEFT_HEIGHT.equals(key)
                || BlindSpotOverlayController.PREF_RIGHT_WIDTH.equals(key)
                || BlindSpotOverlayController.PREF_RIGHT_HEIGHT.equals(key)
                || BlindSpotOverlayController.PREF_FRONT_LEFT_WIDTH.equals(key)
                || BlindSpotOverlayController.PREF_FRONT_LEFT_HEIGHT.equals(key)
                || BlindSpotOverlayController.PREF_FRONT_RIGHT_WIDTH.equals(key)
                || BlindSpotOverlayController.PREF_FRONT_RIGHT_HEIGHT.equals(key);
    }

    private static void addDewarpKeys(Set<String> keys, String prefix) {
        keys.add(prefix + "enabled"); keys.add(prefix + "fov"); keys.add(prefix + "projection");
    }

    private static Set<String> cameraClearKeys(boolean preserveMirror) {
        LinkedHashSet<String> keys = new LinkedHashSet<>(cameraPresetKeys());
        if (preserveMirror) {
            // An omitted optional Mirror group must not reset active Mirror.
            // Local hidden/preset keys are not in the transfer allowlist and
            // therefore remain untouched for every import.
            keys.removeIf(key -> key.startsWith("mirror_"));
        }
        keys.add(BlindSpotOverlayController.PREF_SCALE);
        // Remove the pre-profile grid positions so they cannot reappear if a
        // later reader falls back before the imported x/y values are materialized.
        keys.add(BlindSpotOverlayController.PREF_LEFT_POSITION);
        keys.add(BlindSpotOverlayController.PREF_RIGHT_POSITION);
        for (int lens = CameraDewarpConfig.LENS_LEFT; lens <= CameraDewarpConfig.LENS_FRONT; lens++) {
            String prefix = lens == 1 ? "camera_dewarp_v2_left_" : lens == 2 ? "camera_dewarp_v2_right_" : lens == 3 ? "camera_dewarp_v2_rear_" : "camera_dewarp_v2_front_";
            addDewarpKeys(keys, prefix);
        }
        return keys;
    }

    private static boolean containsMirror(Map<String, Object> values) {
        for (String key : values.keySet()) if (key.startsWith("mirror_")) return true;
        return false;
    }

    private static boolean isBooleanKey(String key) {
        return key.endsWith("_enabled") || key.endsWith("_turn_required") || key.endsWith("_bsd_only")
                || key.endsWith("_mirror") || key.endsWith("_visible") || key.endsWith("_integrated")
                || key.endsWith("_add_central") || key.endsWith("_correction")
                || key.endsWith("_mirrored")
                || key.equals(ParkingCameraSettings.PREF_ALLOW_DURING_REVERSE)
                || key.equals("parking_camera_scale_sync")
                || key.equals(RearviewMirrorSettings.PREF_ENABLED)
                || key.equals(BlindSpotOverlayController.PREF_REAR_SUPPRESS_WHILE_PANORAMA)
                || key.equals(BlindSpotOverlayController.PREF_FRONT_SUPPRESS_WHILE_PANORAMA)
                || key.equals(RearviewMirrorSettings.PREF_SUPPRESS_WHILE_PANORAMA)
                || key.equals("mirror_correction") || key.equals("mirror_mirrored");
    }

    private static boolean isIntegerKey(String key) {
        return (!key.endsWith("_frame_aspect") && key.endsWith("_aspect"))
                || key.endsWith("_rotation") || key.endsWith("_rotation_mode")
                || key.endsWith("_rotation_degrees") || key.endsWith("_display_mode") || key.endsWith("_fov")
                || key.endsWith("_projection") || key.endsWith("_scale") || key.endsWith("_target")
                || key.endsWith("_scale_percent") || key.endsWith("_mode")
                || key.startsWith("reverse_camera_z_") || key.equals(BlindSpotOverlayController.PREF_WARNING_MODE)
                || key.equals(BlindSpotOverlayController.PREF_CORNER_RADIUS)
                || key.equals(BlindSpotOverlayController.PREF_TRANSPARENCY_PERCENT)
                || key.equals(CameraBufferQuality.PREF_QUALITY)
                || key.contains("_speed") || key.endsWith("_distance_cm")
                || key.equals(RearviewMirrorSettings.PREF_BORDER_DP)
                || key.equals(RearviewMirrorSettings.PREF_BORDER_ARGB)
                || key.equals("mirror_fov") || key.equals("mirror_projection")
                || key.equals("mirror_output_mode") || key.equals("mirror_rotation")
                || (key.startsWith("camera_dewarp_")
                    && (key.endsWith("_zoom") || key.endsWith("_strength")
                        || key.endsWith("_center_x") || key.endsWith("_center_y")));
    }

    private static void validateLegacySettings(Map<String, Object> values) {
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            if (!isLegacyAllowedKey(entry.getKey())) throw new IllegalArgumentException("legacy key is not allowlisted");
            Object value = entry.getValue();
            if (value == null || value == JSONObject.NULL) throw new IllegalArgumentException("null legacy value");
            validateLegacyType(entry.getKey(), value);
            if (value instanceof Float && !Float.isFinite((Float) value)) throw new IllegalArgumentException("nonfinite legacy value");
            if (value instanceof Double && !Double.isFinite((Double) value)) throw new IllegalArgumentException("nonfinite legacy value");
            validateLegacyBounds(entry.getKey(), value);
        }
        requireReverseZOrder(values);
    }

    private static void validateLegacyBounds(String key, Object value) {
        if (value instanceof Number) {
            double n = ((Number) value).doubleValue();
            if (!Double.isFinite(n)) throw new IllegalArgumentException("nonfinite legacy value");
            if (isCorrectedAspectMarker(key)
                    && n != DirectCameraCrop.ASPECT_FREE) {
                throw new IllegalArgumentException("corrected geometry marker must be FREE");
            }
            if (key.contains("speed") || key.equals("max_speed_kph")) requireRange(n, 0, 300, key);
            if (key.contains("distance_cm")) requireRange(n, 0, 150, key);
            if (key.contains("angle") || key.endsWith("_deg")) requireRange(n, 0, 780, key);
            if (key.contains("delay_ms")) requireRange(n, 0, 1000, key);
            boolean legacyDewarpCenter = key.startsWith("camera_dewarp_")
                    && (key.endsWith("_center_x") || key.endsWith("_center_y"));
            if (!legacyDewarpCenter && (key.endsWith("_x") || key.endsWith("_y") || key.endsWith("_width") || key.endsWith("_height")
                    || key.endsWith("_w") || key.endsWith("_h")
                    || key.endsWith("_left") || key.endsWith("_top"))) requireRange(n, 0, 1, key);
            if (key.endsWith("_scale") || key.endsWith("_scale_percent"))
                requireRange(n, BlindSpotOverlayController.MIN_SCALE_PERCENT,
                    BlindSpotOverlayController.MAX_SCALE_PERCENT, key);
            if (key.equals(BlindSpotOverlayController.PREF_SCALE)) requireRange(n,
                    BlindSpotOverlayController.MIN_SCALE_PERCENT, BlindSpotOverlayController.MAX_SCALE_PERCENT, key);
            if (key.endsWith("_position")) requireRange(n, 0, 8, key);
            if (key.endsWith("_rotation") || key.endsWith("_rotation_degrees")) requireRange(n, -180, 180, key);
            if (key.endsWith("_rotation_mode") || key.endsWith("_display_mode")
                    || key.endsWith("_mode")) requireRange(n, 0, 2, key);
            if (!key.endsWith("_frame_aspect") && key.endsWith("_aspect")) requireRange(n, 0, 3, key);
            if (key.endsWith("_fov")) requireRange(n, CameraDewarpConfig.MIN_FOV_DEGREES,
                    CameraDewarpConfig.MAX_FOV_DEGREES, key);
            if (key.endsWith("_projection")) requireRange(n, 0, 1, key);
            if (key.endsWith("_frame_aspect")) requireRange(n, Float.MIN_NORMAL, 100, key);
            if (key.endsWith("_version")) requireRange(n, 1, 1, key);
            if (key.endsWith("_zoom")) requireRange(n, 0, 1000, key);
            if (key.endsWith("_strength")) requireRange(n, 0, 100, key);
            if (legacyDewarpCenter) requireRange(n, 0, 100, key);
            if (key.equals(BlindSpotOverlayController.PREF_CORNER_RADIUS)) requireRange(n, 0, 48, key);
            if (key.equals(BlindSpotOverlayController.PREF_TRANSPARENCY_PERCENT)) requireRange(n, 0, 100, key);
            if (key.equals(CameraBufferQuality.PREF_QUALITY)) requireRange(n, 0, 3, key);
            if (key.equals(BlindSpotOverlayController.PREF_WARNING_MODE)) requireRange(n, 0, 2, key);
            if (key.equals("outward_deg")) requireRange(n, 0, 360, key);
            if (key.equals("center_deg")) requireRange(n, 0, 45, key);
        }
    }

    private static void validateLegacyType(String key, Object value) {
        if (legacyBooleanKey(key)) {
            if (!(value instanceof Boolean)) throw new IllegalArgumentException("boolean required: " + key);
        } else if (legacyIntegerKey(key)) {
            if (!(value instanceof Integer)) throw new IllegalArgumentException("integer required: " + key);
        } else if (legacyFloatKey(key)) {
            if (!(value instanceof Float)) throw new IllegalArgumentException("float required: " + key);
        } else {
            throw new IllegalArgumentException("unsupported legacy setting type: " + key);
        }
    }

    private static boolean legacyBooleanKey(String key) {
        return isBooleanKey(key) || key.equals("guard_enabled") || key.equals("music_visualizer_enabled")
                || key.equals("parking_any_enabled") || key.equals("weather_enabled")
                || key.equals("auto_start_enabled");
    }

    private static boolean legacyIntegerKey(String key) {
        return isIntegerKey(key) || key.endsWith("_distance_cm") || key.endsWith("_minutes")
                || key.endsWith("_version") || key.startsWith("reverse_camera_z_")
                || key.endsWith("_position") || key.equals(BlindSpotOverlayController.PREF_SCALE)
                || key.equals("correction_delay_ms") || key.equals("max_speed_kph");
    }

    private static boolean legacyFloatKey(String key) {
        return !legacyBooleanKey(key) && !legacyIntegerKey(key);
    }

    private static void requireRange(double value, double minimum, double maximum, String key) {
        if (value < minimum || value > maximum) throw new IllegalArgumentException("out of bounds: " + key);
    }

    private static boolean isLegacyAllowedKey(String key) {
        if (key == null || key.isEmpty()) return false;
        // Removed from the app, but retained in settings files upgraded from older versions.
        if (key.equals("reverse_camera_parking_guidelines")) return false;
        // These flags only record one-time migration/initialization state.
        if (key.endsWith("_seeded")) return false;
        if (key.equals("camera_selected_profile") || key.equals("camera_tab_selected")
                || key.equals("parking_camera_selected_profile") || key.equals("selected_tab")
                || key.equals("reverse_camera_editor_selection") || key.equals("weather_last_success_ms")
                || key.equals("helper_apk_marker") || key.equals("assumed_latch_state")
                || key.equals("user_shutdown_active") || key.equals("service_heartbeat_ms")) return false;
        if (key.startsWith("camera_") || key.startsWith("direct_crop_")
                || key.startsWith("camera_dewarp_v2_") || key.startsWith("camera_dewarp_v3_")
                || key.startsWith("camera_calibration_preset_v1_")) return true;
        if (key.startsWith("parking_camera_") || key.startsWith("parking_direct_crop_v1_")
                || key.startsWith("parking_calibration_preset_v1_")) return true;
        if (key.startsWith("reverse_camera_") || key.startsWith("reverse_calibration_preset_v1_")
                || key.startsWith("reverse_front_calibration_preset_v1_")) return true;
        return key.equals("guard_enabled") || key.equals("outward_deg") || key.equals("center_deg")
                || key.equals("correction_delay_ms") || key.equals("max_speed_kph")
                || key.equals("music_visualizer_enabled") || key.equals("parking_any_enabled")
                || key.equals("weather_enabled") || key.equals("weather_interval_minutes")
                || key.equals("auto_start_enabled");
    }

    private static boolean isCorrectedAspectMarker(String key) {
        return key != null && (key.matches("direct_crop_v3_corrected_[0-9]+_aspect")
                || key.startsWith("parking_direct_crop_v1_")
                && key.endsWith("_corrected_aspect"));
    }

    private static Object readLegacyValue(Element element) {
        String tag = element.getTagName();
        String text = element.hasAttribute("value") ? element.getAttribute("value") : element.getTextContent();
        try {
            if ("boolean".equals(tag)) return Boolean.parseBoolean(requireBoolean(text));
            if ("int".equals(tag)) return Integer.parseInt(text.trim());
            if ("long".equals(tag)) return Long.parseLong(text.trim());
            if ("float".equals(tag)) return Float.parseFloat(text.trim());
            if ("string".equals(tag)) return text;
            throw new IllegalArgumentException("unsupported legacy value type");
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("malformed legacy value", error);
        }
    }

    private static String requireBoolean(String value) {
        if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
            throw new IllegalArgumentException("malformed boolean");
        }
        return value;
    }

    private static DocumentBuilderFactory secureDocumentBuilderFactory() throws ParserConfigurationException {
        // Android's built-in Harmony parser exposes only the standard namespace
        // and validation features.  Keep this factory portable and reject all
        // DTDs before parsing; SharedPreferences XML never needs one.
        return DocumentBuilderFactory.newInstance();
    }

    private static void rejectDoctype(String input) {
        String lower = input.toLowerCase(java.util.Locale.US);
        if (lower.contains("<!doctype")) {
            throw new IllegalArgumentException("DOCTYPE is not allowed");
        }
    }

    private static void requireInputSize(String input) {
        if (input == null) throw new IllegalArgumentException("input is null");
        if (input.getBytes(StandardCharsets.UTF_8).length > MAX_INPUT_BYTES) {
            throw new IllegalArgumentException("input exceeds size limit");
        }
    }

    private static Set<String> names(JSONObject object) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        java.util.Iterator<String> iterator = object.keys();
        while (iterator.hasNext()) result.add(iterator.next());
        return result;
    }

    private static void requireKeys(JSONObject object, String... keys) {
        Set<String> expected = new LinkedHashSet<>();
        Collections.addAll(expected, keys);
        if (!names(object).equals(expected)) throw new IllegalArgumentException("unexpected preset fields");
    }

    private static int warningMode(SharedPreferences p) {
        int value = intValue(p, BlindSpotOverlayController.PREF_WARNING_MODE,
                BlindSpotOverlayController.DEFAULT_WARNING_MODE);
        return value >= CameraShellProtocol.WARNING_MODE_OFF && value <= CameraShellProtocol.WARNING_MODE_PULSE
                ? value : BlindSpotOverlayController.DEFAULT_WARNING_MODE;
    }

    private static boolean bool(SharedPreferences p, String key, boolean fallback) {
        try { return p.getBoolean(key, fallback); } catch (RuntimeException ignored) { return fallback; }
    }

    private static int intValue(SharedPreferences p, String key, int fallback) {
        try { return p.getInt(key, fallback); } catch (RuntimeException ignored) { return fallback; }
    }

    private static float floatValue(SharedPreferences p, String key, float fallback) {
        try { float value = p.getFloat(key, fallback); return Float.isFinite(value) ? value : fallback; }
        catch (RuntimeException ignored) { return fallback; }
    }

    private static int intValue(Object value, String key) {
        if (!(value instanceof Number)) throw new IllegalArgumentException("integer required: " + key);
        double number = ((Number) value).doubleValue();
        if (!Double.isFinite(number) || number != Math.rint(number)
                || number < Integer.MIN_VALUE || number > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("invalid integer: " + key);
        }
        return (int) number;
    }

    private static float floatValue(Object value, String key) {
        if (!(value instanceof Number)) throw new IllegalArgumentException("number required: " + key);
        float number = ((Number) value).floatValue();
        if (!Float.isFinite(number)) throw new IllegalArgumentException("invalid number: " + key);
        return number;
    }

    private static int integer(Map<String, Object> values, String key) { return intValue(values.get(key), key); }
    private static float number(Map<String, Object> values, String key) { return floatValue(values.get(key), key); }

    private static void put(SharedPreferences.Editor editor, String key, Object value) {
        if (value instanceof Boolean) editor.putBoolean(key, (Boolean) value);
        else if (value instanceof Integer) editor.putInt(key, (Integer) value);
        else if (value instanceof Long) editor.putLong(key, (Long) value);
        else if (value instanceof Float) editor.putFloat(key, (Float) value);
        else if (value instanceof Double) editor.putFloat(key, ((Double) value).floatValue());
        else if (value instanceof String) editor.putString(key, (String) value);
        else throw new IllegalArgumentException("unsupported preference value: " + key);
    }

    /** Writes JSON numbers using the declared preference type, not JSONObject's inferred type. */
    private static void putCameraValue(
            SharedPreferences.Editor editor, String key, Object value) {
        if (isStringKey(key)) {
            if (!(value instanceof String)) throw new IllegalArgumentException("string required: " + key);
            editor.putString(key, (String) value);
        } else if (isBooleanKey(key)) {
            if (!(value instanceof Boolean)) throw new IllegalArgumentException("boolean required: " + key);
            editor.putBoolean(key, (Boolean) value);
        } else if (isIntegerKey(key)) {
            editor.putInt(key, intValue(value, key));
        } else {
            editor.putFloat(key, floatValue(value, key));
        }
    }

    private static boolean isStringKey(String key) {
        return RearviewMirrorSettings.PREF_TARGET.equals(key);
    }

    private static int versionInt(Object value) {
        if (!(value instanceof Number)) return -1;
        double number = ((Number) value).doubleValue();
        if (!Double.isFinite(number) || number != Math.rint(number)
                || number < Integer.MIN_VALUE || number > Integer.MAX_VALUE) return -1;
        return (int) number;
    }
}
