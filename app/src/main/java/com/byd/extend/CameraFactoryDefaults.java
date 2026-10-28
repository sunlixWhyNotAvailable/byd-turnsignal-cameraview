package com.byd.extend;

import android.content.SharedPreferences;

/** Approved v1.9.0 camera geometry defaults, shared by fresh-install seeding and reset actions. */
final class CameraFactoryDefaults {
    static final String PREF_INITIALIZED = "camera_factory_geometry_v1_seeded";

    private CameraFactoryDefaults() {}

    static DirectCameraCrop blindRaw(CameraProfile profile) {
        switch (profileId(profile)) {
            case CameraProfile.REAR_LEFT:
                return crop(0.08894998f, 0.21130778f, 0.59938854f, 0.5773845f,
                        32, CameraRotation.MODE_FILL, true);
            case CameraProfile.REAR_RIGHT:
                return crop(0.31166148f, 0.21130778f, 0.59938854f, 0.5773845f,
                        -32, CameraRotation.MODE_FILL, true);
            case CameraProfile.FRONT_LEFT:
                return crop(0.47745365f, 0.318708f, 0.46608025f, 0.5074271f,
                        45, CameraRotation.MODE_ALIGNED, false);
            case CameraProfile.FRONT_RIGHT:
                return crop(0.056466103f, 0.318708f, 0.46608025f, 0.5074271f,
                        -45, CameraRotation.MODE_ALIGNED, false);
            default:
                throw new IllegalArgumentException("invalid camera profile");
        }
    }

    static DirectCameraCrop blindCorrected(CameraProfile profile) {
        DirectCameraCrop raw = blindRaw(profile);
        DirectCameraCrop geometry;
        switch (profileId(profile)) {
            case CameraProfile.REAR_LEFT:
                geometry = DirectCameraCrop.of(0.0022686953f, 0.07893269f,
                        0.6590216f, 0.72866434f, DirectCameraCrop.ASPECT_FREE);
                break;
            case CameraProfile.REAR_RIGHT:
                geometry = DirectCameraCrop.of(0.3387097f, 0.07893269f,
                        0.6590216f, 0.72866434f, DirectCameraCrop.ASPECT_FREE);
                break;
            case CameraProfile.FRONT_LEFT:
                geometry = DirectCameraCrop.of(0.29754815f, 0.29489756f,
                        0.41666844f, 0.4414549f, DirectCameraCrop.ASPECT_FREE);
                break;
            case CameraProfile.FRONT_RIGHT:
                geometry = DirectCameraCrop.of(0.28578338f, 0.29489756f,
                        0.41666844f, 0.4414549f, DirectCameraCrop.ASPECT_FREE);
                break;
            default:
                throw new IllegalArgumentException("invalid camera profile");
        }
        return raw.withGeometry(geometry);
    }

    static CameraDewarpConfig blindDewarp(CameraProfile profile) {
        int id = profileId(profile);
        boolean rear = id == CameraProfile.REAR_LEFT || id == CameraProfile.REAR_RIGHT;
        return CameraDewarpConfig.of(CameraDewarpConfig.lensFor(profile), true,
                rear ? 165 : 130,
                rear ? CameraDewarpConfig.PROJECTION_CYLINDRICAL
                        : CameraDewarpConfig.PROJECTION_RECTILINEAR,
                100, Float.NaN);
    }

    static float blindFrameAspect(CameraProfile profile) {
        switch (profileId(profile)) {
            case CameraProfile.REAR_LEFT: return 1.6173527f;
            case CameraProfile.REAR_RIGHT: return 1.6154981f;
            case CameraProfile.FRONT_LEFT: return 1.393998f;
            case CameraProfile.FRONT_RIGHT: return 1.3889601f;
            default: throw new IllegalArgumentException("invalid camera profile");
        }
    }

    static CameraPlacement blindPlacement(CameraProfile profile, int target) {
        boolean cluster = target == CameraDisplayTarget.CLUSTER;
        switch (profileId(profile)) {
            case CameraProfile.REAR_LEFT:
                return cluster ? placement(0.0f, 0.25f, 0.3f, 0.5f)
                        : placement(0.0125f, 0.09166667f, 0.3f, 0.32962963f);
            case CameraProfile.REAR_RIGHT:
                return cluster ? placement(0.7f, 0.25f, 0.3f, 0.5f)
                        : placement(0.6875f, 0.08611111f, 0.3f, 0.33055556f);
            case CameraProfile.FRONT_LEFT:
                return cluster ? placement(0.0f, 0.202f, 0.3f, 0.57361114f)
                        : placement(0.0125f, 0.49537036f, 0.3f, 0.3824074f);
            case CameraProfile.FRONT_RIGHT:
                return cluster ? placement(0.7f, 0.229f, 0.3f, 0.5763889f)
                        : placement(0.6875f, 0.49351853f, 0.3f, 0.38425925f);
            default: throw new IllegalArgumentException("invalid camera profile");
        }
    }

    static RearviewMirrorSettings.Calibration mirrorCalibration(boolean front) {
        return front
                ? new RearviewMirrorSettings.Calibration(
                        percentRect(12.207357f, 9.530277f, 77.09032f, 72.62138f),
                        percentRect(0.0f, 0.0f, 99.31455f, 99.580345f),
                        false, 103, CameraDewarpConfig.PROJECTION_CYLINDRICAL,
                        false, 0, CameraRotation.MODE_FILL, 100, Float.NaN)
                : new RearviewMirrorSettings.Calibration(
                        percentRect(12.207357f, 0.5077227f, 77.09032f, 56.080025f),
                        percentRect(0.0f, 0.0f, 99.31455f, 99.580345f),
                        false, 140, CameraDewarpConfig.PROJECTION_RECTILINEAR,
                        true, 0, CameraRotation.MODE_FILL, 100, Float.NaN);
    }

    static DirectCameraCrop mirrorRaw(boolean front) {
        RearviewMirrorSettings.Calibration value = mirrorCalibration(front);
        return DirectCameraCrop.of(value.raw.x, value.raw.y, value.raw.width,
                value.raw.height, DirectCameraCrop.ASPECT_FREE,
                value.rotationDegrees, value.rotationMode)
                .withMirrorHorizontally(value.mirrored);
    }

    static DirectCameraCrop mirrorCorrected(boolean front) {
        RearviewMirrorSettings.Calibration value = mirrorCalibration(front);
        return DirectCameraCrop.of(value.corrected.x, value.corrected.y,
                value.corrected.width, value.corrected.height,
                DirectCameraCrop.ASPECT_FREE, value.rotationDegrees,
                value.rotationMode).withMirrorHorizontally(value.mirrored);
    }

    static CameraDewarpConfig mirrorDewarp(boolean front) {
        return mirrorCalibration(front).dewarp(front
                ? CameraDewarpConfig.LENS_FRONT : CameraDewarpConfig.LENS_REAR);
    }

    static CameraPlacement mirrorPlacement(int target) {
        if (target == CameraDisplayTarget.CLUSTER) {
            return percentRect(16.9f, 21.6f, 64.200005f, 62.800003f);
        }
        return percentRect(36.0f, 9.099999f, 27.4f, 23.4f);
    }

    static ReverseCameraLayout.Rect reverseRaw(int cameraIndex, boolean front) {
        if (front) {
            switch (cameraIndex) {
                case ReverseCameraLayout.REAR_CAMERA_INDEX:
                    return source(0.06f, 0.0f, 0.88f, 0.85f);
                case ReverseCameraLayout.REAR_LEFT_CAMERA_INDEX:
                    return source(0.5376343f, 0.096544795f, 0.45903558f, 0.9034552f);
                case ReverseCameraLayout.REAR_RIGHT_CAMERA_INDEX:
                    return source(0.0033301115f, 0.096544795f, 0.45903558f, 0.9034552f);
                default: throw new IllegalArgumentException("invalid reverse front profile");
            }
        }
        switch (cameraIndex) {
            case ReverseCameraLayout.REAR_CAMERA_INDEX:
                return source(0.06f, 0.0f, 0.88f, 0.85f);
            case ReverseCameraLayout.REAR_LEFT_CAMERA_INDEX:
                return source(2.9802322e-8f, 0.25f, 0.384127f, 0.55f);
            case ReverseCameraLayout.REAR_RIGHT_CAMERA_INDEX:
                return source(0.615873f, 0.25f, 0.384127f, 0.55f);
            default: throw new IllegalArgumentException("invalid reverse profile");
        }
    }

    static ReverseCameraLayout.Rect reverseCorrected(int cameraIndex, boolean front) {
        if (front) {
            switch (cameraIndex) {
                case ReverseCameraLayout.REAR_CAMERA_INDEX:
                    return source(0.0f, 0.0f, 1.0f, 1.0f);
                case ReverseCameraLayout.REAR_LEFT_CAMERA_INDEX:
                    return source(0.0018574595f, 0.009921577f,
                            0.84360945f, 0.98990417f);
                case ReverseCameraLayout.REAR_RIGHT_CAMERA_INDEX:
                    return source(0.15453309f, 0.009921577f,
                            0.84360945f, 0.98990417f);
                default: throw new IllegalArgumentException("invalid reverse front profile");
            }
        }
        switch (cameraIndex) {
            case ReverseCameraLayout.REAR_CAMERA_INDEX:
                return source(0.0f, 0.0f, 1.0f, 1.0f);
            case ReverseCameraLayout.REAR_LEFT_CAMERA_INDEX:
                return source(0.30299506f, 0.14090702f,
                        0.45387408f, 0.6340929f);
            case ReverseCameraLayout.REAR_RIGHT_CAMERA_INDEX:
                return source(0.24313083f, 0.14090702f,
                        0.45387408f, 0.6340929f);
            default: throw new IllegalArgumentException("invalid reverse profile");
        }
    }

    static CameraDewarpConfig reverseDewarp(int cameraIndex, boolean front) {
        int lens = front ? CameraDewarpConfig.lensForReverseFrontCamera(cameraIndex)
                : CameraDewarpConfig.lensForReverseCamera(cameraIndex);
        if (front) {
            if (cameraIndex == ReverseCameraLayout.REAR_CAMERA_INDEX) {
                return CameraDewarpConfig.of(lens, false, 170,
                        CameraDewarpConfig.PROJECTION_CYLINDRICAL, 100, Float.NaN);
            }
            return CameraDewarpConfig.of(lens, true, 130,
                    CameraDewarpConfig.PROJECTION_RECTILINEAR, 50, Float.NaN);
        }
        if (cameraIndex == ReverseCameraLayout.REAR_CAMERA_INDEX) {
            return CameraDewarpConfig.of(lens, false, 170,
                    CameraDewarpConfig.PROJECTION_CYLINDRICAL, 100, Float.NaN);
        }
        return CameraDewarpConfig.of(lens, true, 163,
                CameraDewarpConfig.PROJECTION_CYLINDRICAL, 100,
                cameraIndex == ReverseCameraLayout.REAR_LEFT_CAMERA_INDEX ? 163.0f : Float.NaN);
    }

    static int reverseRotationDegrees(int cameraIndex, boolean front) {
        if (front) {
            switch (cameraIndex) {
                case ReverseCameraLayout.REAR_CAMERA_INDEX: return 0;
                case ReverseCameraLayout.REAR_LEFT_CAMERA_INDEX: return -40;
                case ReverseCameraLayout.REAR_RIGHT_CAMERA_INDEX: return 40;
                default: throw new IllegalArgumentException("invalid reverse front profile");
            }
        }
        switch (cameraIndex) {
            case ReverseCameraLayout.REAR_CAMERA_INDEX: return 0;
            case ReverseCameraLayout.REAR_LEFT_CAMERA_INDEX: return 35;
            case ReverseCameraLayout.REAR_RIGHT_CAMERA_INDEX: return -35;
            default: throw new IllegalArgumentException("invalid reverse profile");
        }
    }

    static int reverseDisplayMode(int cameraIndex, boolean front) {
        reverseRotationDegrees(cameraIndex, front);
        return ReverseCameraLayout.DISPLAY_MODE_FILL;
    }

    static boolean reverseMirrored(int cameraIndex, boolean front) {
        reverseRotationDegrees(cameraIndex, front);
        return !front;
    }

    static ReverseCameraLayout.Rect reverseDestination(int paneId, int target) {
        requireReverseTarget(target);
        boolean cluster = target == CameraDisplayTarget.CLUSTER;
        switch (paneId) {
            case ReverseCameraLayout.REAR_CAMERA_INDEX:
                return cluster ? destination(0.21756601f, 0.11205655f,
                        0.564868f, 0.7758869f)
                        : destination(0.43f, 0.0f, 0.57f, 0.79f);
            case ReverseCameraLayout.REAR_LEFT_CAMERA_INDEX:
                return cluster ? destination(0.0f, 0.22f, 0.45f, 0.63f)
                        : destination(0.43f, 0.7960598f, 0.25351316f, 0.20394021f);
            case ReverseCameraLayout.REAR_RIGHT_CAMERA_INDEX:
                return cluster ? destination(0.55f, 0.22f, 0.45f, 0.63f)
                        : destination(0.74648684f, 0.7960598f,
                                0.25351316f, 0.20394021f);
            default: throw new IllegalArgumentException("invalid reverse composition element");
        }
    }

    static ReverseCameraLayout.Rect reverseBackground(int target) {
        requireReverseTarget(target);
        return target == CameraDisplayTarget.CLUSTER
                ? destination(0.21273676f, 0.0f, 0.5745265f, 1.0f)
                : destination(0.42398763f, 0.0f, 0.5745265f, 1.0f);
    }

    static ReverseCameraLayout.Rect reverseWidget(int target) {
        requireReverseTarget(target);
        return target == CameraDisplayTarget.CLUSTER
                ? ReverseCameraLayout.widgetDestination(0.46f, 0.22f, 0.08f, 0.63f)
                : ReverseCameraLayout.widgetDestination(0.6855f, 0.7960598f,
                        0.05931156f, 0.20394021f);
    }

    /** Seeds only selected geometry once; existing stores receive only the marker. */
    static void initialize(SharedPreferences preferences, boolean existingInstall) {
        if (preferences == null) throw new IllegalArgumentException("preferences is null");
        if (preferences.contains(PREF_INITIALIZED)) return;
        SharedPreferences.Editor editor = preferences.edit().putBoolean(PREF_INITIALIZED, true);
        if (!existingInstall) writeFactoryValues(editor);
        editor.apply();
    }

    private static void writeFactoryValues(SharedPreferences.Editor editor) {
        for (CameraProfile profile : CameraProfile.values()) {
            DirectCameraCrop raw = blindRaw(profile);
            DirectCameraCrop corrected = blindCorrected(profile);
            DirectCameraCrop.write(editor, profile, raw);
            DirectCameraCrop.writeCorrected(editor, profile, corrected);
            editor.putInt(DirectCameraCrop.correctedAspectKey(profile), corrected.aspectMode)
                    .putFloat(BlindSpotOverlayController.frameAspectKey(profile),
                            blindFrameAspect(profile));
            CameraDewarpConfig.writeForProfile(editor, profile, blindDewarp(profile));
            CameraPlacement tablet = blindPlacement(profile, CameraDisplayTarget.TABLET);
            BlindSpotOverlayController.writePlacement(
                    editor, profile, CameraDisplayTarget.TABLET, tablet);
            BlindSpotOverlayController.writePlacement(editor, profile,
                    CameraDisplayTarget.CLUSTER,
                    blindPlacement(profile, CameraDisplayTarget.CLUSTER));
            editor.putFloat(BlindSpotOverlayController.positionKey(profile, false), tablet.x)
                    .putFloat(BlindSpotOverlayController.positionKey(profile, true), tablet.y)
                    .putFloat(BlindSpotOverlayController.placementWidthKey(profile), tablet.width)
                    .putFloat(BlindSpotOverlayController.placementHeightKey(profile), tablet.height);
        }
        writeMirror(editor, false);
        writeMirror(editor, true);
        writeReverse(editor);
    }

    private static void writeMirror(SharedPreferences.Editor editor, boolean front) {
        RearviewMirrorSettings.Calibration value = mirrorCalibration(front);
        String original = front ? "mirror_front_original_" : "mirror_original_";
        String corrected = front ? "mirror_front_corrected_" : "mirror_corrected_";
        String controls = front ? "mirror_front_" : "mirror_";
        // The existing mirror writer applies its own Editor, so write its typed calibration here.
        putPercentRect(editor, original, value.raw);
        putPercentRect(editor, corrected, value.corrected);
        CameraDewarpConfig.writeControls(editor, controls, mirrorDewarp(front));
        editor.putBoolean(controls + "correction", value.enabled)
                .putInt(controls + "fov", value.fovDegrees)
                .putInt(controls + "projection", value.projection)
                .putBoolean(controls + "mirrored", value.mirrored)
                .putInt(controls + "rotation", value.rotationDegrees)
                .putInt(controls + "output_mode", value.rotationMode);
        if (front) return;
        CameraPlacement tablet = mirrorPlacement(CameraDisplayTarget.TABLET);
        RearviewMirrorSettings.writePlacement(
                editor, CameraDisplayTarget.TABLET, tablet);
        RearviewMirrorSettings.writePlacement(
                editor, CameraDisplayTarget.CLUSTER,
                mirrorPlacement(CameraDisplayTarget.CLUSTER));
        editor.putFloat(RearviewMirrorSettings.PREF_X, tablet.x * 100.0f)
                .putFloat(RearviewMirrorSettings.PREF_Y, tablet.y * 100.0f)
                .putFloat(RearviewMirrorSettings.PREF_WIDTH, tablet.width * 100.0f)
                .putFloat(RearviewMirrorSettings.PREF_HEIGHT, tablet.height * 100.0f);
    }

    private static void writeReverse(SharedPreferences.Editor editor) {
        for (int index = ReverseCameraLayout.REAR_CAMERA_INDEX;
                index <= ReverseCameraLayout.REAR_RIGHT_CAMERA_INDEX; index++) {
            ReverseCameraController.writeSourceCrop(editor, index, reverseRaw(index, false), false);
            ReverseCameraController.writeSourceCrop(
                    editor, index, reverseCorrected(index, false), true);
            editor.putInt(ReverseCameraController.paneSettingKey(index, "rotation_degrees"),
                            reverseRotationDegrees(index, false))
                    .putInt(ReverseCameraController.displayModeKey(index),
                            reverseDisplayMode(index, false))
                    .putBoolean(ReverseCameraController.mirrorKey(index),
                            reverseMirrored(index, false));
            CameraDewarpConfig.writeForReverse(editor, index, reverseDewarp(index, false));

            ReverseCameraController.writeFrontSourceCrop(
                    editor, index, reverseRaw(index, true), false);
            ReverseCameraController.writeFrontSourceCrop(
                    editor, index, reverseCorrected(index, true), true);
            editor.putInt(ReverseCameraController.frontPaneSettingKey(
                            index, "rotation_degrees"), reverseRotationDegrees(index, true))
                    .putInt(ReverseCameraController.frontPaneSettingKey(index, "display_mode"),
                            reverseDisplayMode(index, true))
                    .putBoolean(ReverseCameraController.frontPaneSettingKey(index, "mirror"),
                            reverseMirrored(index, true));
            CameraDewarpConfig.writeForReverseFront(
                    editor, index, reverseDewarp(index, true));
        }
        putReverseRect(editor, "reverse_camera_background_",
                reverseBackground(CameraDisplayTarget.TABLET));
        putReverseRect(editor, "reverse_camera_widget_",
                reverseWidget(CameraDisplayTarget.TABLET));
        putReverseRect(editor, ReverseCameraController.clusterGeometryPrefix(
                ReverseCameraLayout.BACKGROUND_PANE_ID),
                reverseBackground(CameraDisplayTarget.CLUSTER));
        putReverseRect(editor, ReverseCameraController.clusterGeometryPrefix(
                ReverseCameraLayout.WIDGET_PANE_ID),
                reverseWidget(CameraDisplayTarget.CLUSTER));
        for (int index = ReverseCameraLayout.REAR_CAMERA_INDEX;
                index <= ReverseCameraLayout.REAR_RIGHT_CAMERA_INDEX; index++) {
            ReverseCameraLayout.Rect tablet = reverseDestination(index, CameraDisplayTarget.TABLET);
            editor.putFloat(ReverseCameraController.paneSettingKey(index, "left"), tablet.left)
                    .putFloat(ReverseCameraController.paneSettingKey(index, "top"), tablet.top)
                    .putFloat(ReverseCameraController.paneSettingKey(index, "width"), tablet.width)
                    .putFloat(ReverseCameraController.paneSettingKey(index, "height"), tablet.height);
            putReverseRect(editor,
                    ReverseCameraController.clusterGeometryPrefix(index),
                    reverseDestination(index, CameraDisplayTarget.CLUSTER));
        }
    }

    private static void putPercentRect(
            SharedPreferences.Editor editor, String prefix, CameraPlacement value) {
        editor.putFloat(prefix + "x", value.x * 100.0f)
                .putFloat(prefix + "y", value.y * 100.0f)
                .putFloat(prefix + "width", value.width * 100.0f)
                .putFloat(prefix + "height", value.height * 100.0f);
    }

    private static void putReverseRect(
            SharedPreferences.Editor editor, String prefix, ReverseCameraLayout.Rect value) {
        editor.putFloat(prefix + "left", value.left)
                .putFloat(prefix + "top", value.top)
                .putFloat(prefix + "width", value.width)
                .putFloat(prefix + "height", value.height);
    }

    private static DirectCameraCrop crop(
            float left, float top, float width, float height,
            int rotation, int mode, boolean mirror) {
        return DirectCameraCrop.of(left, top, width, height,
                DirectCameraCrop.ASPECT_FREE, rotation, mode)
                .withMirrorHorizontally(mirror);
    }

    private static CameraPlacement percentRect(
            float x, float y, float width, float height) {
        return CameraPlacement.source(x / 100.0f, y / 100.0f,
                width / 100.0f, height / 100.0f);
    }

    private static CameraPlacement placement(float x, float y, float width, float height) {
        return CameraPlacement.of(x, y, width, height);
    }

    private static ReverseCameraLayout.Rect source(
            float left, float top, float width, float height) {
        return ReverseCameraLayout.sourceCrop(left, top, width, height);
    }

    private static ReverseCameraLayout.Rect destination(
            float left, float top, float width, float height) {
        return ReverseCameraLayout.destination(left, top, width, height);
    }

    private static void requireReverseTarget(int target) {
        if (!CameraDisplayTarget.isValid(target)) {
            throw new IllegalArgumentException("invalid reverse display target");
        }
    }

    private static int profileId(CameraProfile profile) {
        if (profile == null) throw new IllegalArgumentException("camera profile required");
        return profile.id;
    }
}
