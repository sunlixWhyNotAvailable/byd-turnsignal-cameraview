package com.byd.extend;

/** Exact v1.1.0 geometry/calibration baseline from the approved parking-derived preset. */
final class CameraDefaults {
    private CameraDefaults() {}

    static DirectCameraCrop blindRaw(CameraProfile profile) {
        switch (profile.id) {
            case CameraProfile.REAR_LEFT:
                return crop(0.08894998f, 0.21130778f, 0.59938854f, 0.5773845f,
                        DirectCameraCrop.ASPECT_FREE, -30, true);
            case CameraProfile.REAR_RIGHT:
                return crop(0.31166148f, 0.21130778f, 0.59938854f, 0.5773845f,
                        DirectCameraCrop.ASPECT_FREE, 30, true);
            case CameraProfile.FRONT_LEFT:
                return crop(0.47745365f, 0.318708f, 0.46608025f, 0.5074271f,
                        DirectCameraCrop.ASPECT_FREE, 45, false);
            case CameraProfile.FRONT_RIGHT:
                return crop(0.056466103f, 0.318708f, 0.46608025f, 0.5074271f,
                        DirectCameraCrop.ASPECT_FREE, -45, false);
            default: throw new IllegalArgumentException("invalid camera profile");
        }
    }

    static DirectCameraCrop blindCorrected(CameraProfile profile) {
        switch (profile.id) {
            case CameraProfile.REAR_LEFT:
                return DirectCameraCrop.of(0.10979915f, 0.18539657f,
                        0.53092563f, 0.54995716f, DirectCameraCrop.ASPECT_FREE);
            case CameraProfile.REAR_RIGHT:
                return DirectCameraCrop.of(0.35927522f, 0.18539657f,
                        0.53092563f, 0.54995716f, DirectCameraCrop.ASPECT_FREE);
            case CameraProfile.FRONT_LEFT:
                return DirectCameraCrop.of(0.29754817f, 0.29489756f,
                        0.41666844f, 0.4414549f, DirectCameraCrop.ASPECT_FREE);
            case CameraProfile.FRONT_RIGHT:
                return DirectCameraCrop.of(0.28578338f, 0.29489756f,
                        0.41666844f, 0.4414549f, DirectCameraCrop.ASPECT_FREE);
            default: throw new IllegalArgumentException("invalid camera profile");
        }
    }

    static float blindPosition(CameraProfile profile, boolean vertical) {
        switch (profile.id) {
            case CameraProfile.REAR_LEFT: return vertical ? 0.08281444f : 0.0f;
            case CameraProfile.REAR_RIGHT: return vertical ? 0.0721f : 1.0f;
            case CameraProfile.FRONT_LEFT: return vertical ? 1.0f : 0.0f;
            case CameraProfile.FRONT_RIGHT: return 1.0f;
            default: throw new IllegalArgumentException("invalid camera profile");
        }
    }

    static float blindFrameAspect(CameraProfile profile) {
        switch (profile.id) {
            case CameraProfile.REAR_LEFT: return 1.6173527f;
            case CameraProfile.REAR_RIGHT: return 1.6154981f;
            case CameraProfile.FRONT_LEFT: return 1.393998f;
            case CameraProfile.FRONT_RIGHT: return 1.3889601f;
            default: throw new IllegalArgumentException("invalid camera profile");
        }
    }

    static DirectCameraCrop parkingRaw(ParkingCameraProfile profile) {
        switch (profile.id) {
            case ParkingCameraProfile.FL:
                return crop(0.47745365f, 0.318708f, 0.46608025f, 0.5074271f,
                        DirectCameraCrop.ASPECT_FREE, 45, false);
            case ParkingCameraProfile.FR:
                return crop(0.056466103f, 0.318708f, 0.46608025f, 0.5074271f,
                        DirectCameraCrop.ASPECT_FREE, -45, false);
            case ParkingCameraProfile.RR:
                return crop(0.615873f, 0.25f, 0.384127f, 0.55f,
                        DirectCameraCrop.ASPECT_FREE, -35, true,
                        CameraRotation.MODE_FILL);
            case ParkingCameraProfile.RL:
                return crop(2.9802322e-8f, 0.25f, 0.384127f, 0.55f,
                        DirectCameraCrop.ASPECT_FREE, 35, true,
                        CameraRotation.MODE_FILL);
            case ParkingCameraProfile.FRONT:
                return crop(0.0f, 0.0f, 1.0f, 0.85f,
                        DirectCameraCrop.ASPECT_FREE, 0, false,
                        CameraRotation.MODE_FILL);
            case ParkingCameraProfile.REAR:
                return crop(0.0f, 0.0f, 1.0f, 0.85f,
                        DirectCameraCrop.ASPECT_FREE, 0, true,
                        CameraRotation.MODE_FILL);
            case ParkingCameraProfile.LEFT:
            case ParkingCameraProfile.RIGHT:
                return crop(0.0f, 0.0f, 1.0f, 0.98f,
                        DirectCameraCrop.ASPECT_FREE, 0, false,
                        CameraRotation.MODE_FILL);
            default: throw new IllegalArgumentException("invalid parking profile");
        }
    }

    static DirectCameraCrop parkingCorrected(ParkingCameraProfile profile) {
        switch (profile.id) {
            case ParkingCameraProfile.FL:
                return DirectCameraCrop.of(0.29754817f, 0.29489756f,
                        0.41666844f, 0.4414549f, DirectCameraCrop.ASPECT_FREE);
            case ParkingCameraProfile.FR:
                return DirectCameraCrop.of(0.28578338f, 0.29489756f,
                        0.41666844f, 0.4414549f, DirectCameraCrop.ASPECT_FREE);
            case ParkingCameraProfile.RR:
                return DirectCameraCrop.of(0.24313086f, 0.19213617f,
                        0.4346469f, 0.5828638f, DirectCameraCrop.ASPECT_FREE);
            case ParkingCameraProfile.RL:
                return DirectCameraCrop.of(0.32222223f, 0.19213617f,
                        0.4346469f, 0.5828638f, DirectCameraCrop.ASPECT_FREE);
            case ParkingCameraProfile.FRONT:
            case ParkingCameraProfile.REAR:
                return DirectCameraCrop.of(0.0f, 0.0f, 1.0f, 1.0f,
                        DirectCameraCrop.ASPECT_FREE);
            case ParkingCameraProfile.LEFT:
                return DirectCameraCrop.of(0.039029837f, 0.025311708f,
                        0.96097016f, 0.9746883f, DirectCameraCrop.ASPECT_FREE);
            case ParkingCameraProfile.RIGHT:
                return DirectCameraCrop.of(0.0f, 0.025311708f,
                        0.96097016f, 0.9746883f, DirectCameraCrop.ASPECT_FREE);
            default: throw new IllegalArgumentException("invalid parking profile");
        }
    }

    static CameraDewarpConfig parkingDewarp(ParkingCameraProfile profile) {
        boolean enabled;
        int fov;
        int projection;
        switch (profile.id) {
            case ParkingCameraProfile.FL:
            case ParkingCameraProfile.FR:
                enabled = true; fov = 130; projection = CameraDewarpConfig.PROJECTION_RECTILINEAR; break;
            case ParkingCameraProfile.RR:
            case ParkingCameraProfile.RL:
                enabled = true; fov = 163; projection = CameraDewarpConfig.PROJECTION_CYLINDRICAL; break;
            case ParkingCameraProfile.FRONT:
            case ParkingCameraProfile.REAR:
            case ParkingCameraProfile.LEFT:
            case ParkingCameraProfile.RIGHT:
                enabled = false; fov = 100; projection = CameraDewarpConfig.PROJECTION_RECTILINEAR; break;
            default: throw new IllegalArgumentException("invalid parking profile");
        }
        return CameraDewarpConfig.of(CameraDewarpConfig.lensFor(profile), enabled, fov, projection);
    }

    static int parkingScalePercent() { return ParkingCameraSettings.DEFAULT_SCALE_PERCENT; }

    static ReverseCameraLayout.Rect reverseBackground() {
        return ReverseCameraLayout.destination(0.42398763f, 0.0f, 0.5745265f, 1.0f);
    }

    static ReverseCameraLayout.Rect reverseWidget() {
        return ReverseCameraLayout.widgetDestination(0.6871753f, 0.7960598f,
                0.05931156f, 0.20394021f);
    }

    static DirectCameraCrop reverseFrontRaw(int cameraIndex) {
        switch (cameraIndex) {
            case ReverseCameraLayout.REAR_CAMERA_INDEX:
                return crop(0, 0, 1, .85f, DirectCameraCrop.ASPECT_FREE, 0, false, CameraRotation.MODE_FILL);
            case ReverseCameraLayout.REAR_LEFT_CAMERA_INDEX:
                return crop(.63275f, .23913038f, .3282324f, .7608696f,
                        DirectCameraCrop.ASPECT_FREE, -45, false, CameraRotation.MODE_FILL);
            case ReverseCameraLayout.REAR_RIGHT_CAMERA_INDEX:
                return crop(.039017618f, .23913038f, .3282324f, .7608696f,
                        DirectCameraCrop.ASPECT_FREE, 45, false, CameraRotation.MODE_FILL);
            default: throw new IllegalArgumentException("invalid reverse front profile");
        }
    }

    static DirectCameraCrop reverseFrontCorrected(int cameraIndex) {
        DirectCameraCrop raw = reverseFrontRaw(cameraIndex);
        switch (cameraIndex) {
            case ReverseCameraLayout.REAR_CAMERA_INDEX:
                return raw.withIndependentGeometry(0, 0, 1, 1);
            case ReverseCameraLayout.REAR_LEFT_CAMERA_INDEX:
                return raw.withIndependentGeometry(.10956764f, 0, .5366175f, .79890007f);
            case ReverseCameraLayout.REAR_RIGHT_CAMERA_INDEX:
                return raw.withIndependentGeometry(.35381484f, 0, .5366175f, .79890007f);
            default: throw new IllegalArgumentException("invalid reverse front profile");
        }
    }

    private static DirectCameraCrop crop(
            float left, float top, float width, float height,
            int aspect, int rotation, boolean mirror) {
        return crop(left, top, width, height, aspect, rotation, mirror,
                CameraRotation.MODE_ALIGNED);
    }

    private static DirectCameraCrop crop(
            float left, float top, float width, float height,
            int aspect, int rotation, boolean mirror, int mode) {
        return DirectCameraCrop.of(left, top, width, height, aspect, rotation, mode)
                .withMirrorHorizontally(mirror);
    }
}
