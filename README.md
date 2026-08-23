# BYD Turn Signal Guard

> Experimental open-source companion app for selected Chinese BYD vehicles running DiLink.
>
> This project is not affiliated with or endorsed by BYD. Configure and test all driving-related
> features while the vehicle is parked.

Current source version: `0.50.4`. The application interface is currently Ukrainian.

## Features

### Turn-signal guard

- Helps restore a long turn signal when it is cancelled before the steering wheel returns to the
  configured centre range.
- Configurable steering thresholds, correction delay, and maximum operating speed.
- Safety checks for vehicle state, signal state, telemetry availability, and manual cancellation.
- Optional automatic background start after vehicle boot or an application update.
- Lifetime activation and correction counters.

### Blind-zone camera views

- Independent rear-left, rear-right, front-left, and front-right camera profiles.
- Separate trigger rules and speed ranges for the rear and front camera groups.
- Optional sharp-turn rear view, blind-spot-based visibility, warning highlights, and a front-camera
  turn-signal requirement.
- Independent size, position, and tablet or instrument-cluster destination for every camera
  profile.
- Camera calibration with crop, scale, placement, rotation, mirroring, and Fit, Fill, or Stretch
  display modes.
- Optional fisheye correction configured independently for every displayed profile.
- Four global image-quality choices in `Налаштування`: `Швидкодія` (default), `Баланс`, `Якість`,
  and `Оригінал`, applied consistently to camera views, previews, and calibration.
- Adjustable camera-window corner rounding.

Calibration now opens from the `Камери сліпих зон` page instead of using a separate top-level
tab.

### Parking-camera views

- Eight independent tablet views: front-left corner, front, front-right corner, right, rear-right
  corner, rear, rear-left corner, and left.
- Each view has its own enable switch and editable parking-sensor distance threshold from 0 to
  150 cm. A global maximum-speed setting limits the feature.
- Each corner view can optionally add the corresponding front or rear view when that central view
  was not already activated by its own distance rule.
- Independent size and position for every view, with an optional synchronized scale.
- Independent crop, rotation, mirroring, calibration preset, and optional fisheye correction for
  every view. The rear view starts mirrored by default.

Parking-camera rules are disabled by default and display only on the tablet.

### Enhanced reverse view

- Optional composition of the rear, rear-left, and rear-right cameras over the stock reverse view.
- Touch editor for pane position, size, crop, rotation, display mode, and layer order.
- Movable black background with independent size and position.
- `Відображати` control for the background and each of the three camera panes. Every element is
  visible by default and can be disabled without losing its layout or calibration.
- Per-camera calibration presets and optional fisheye correction.

The enhanced reverse view itself is disabled by default.

### Music, updates, and diagnostics

- Optional ambient-light synchronization for compatible third-party Android music players.
- Forwards available title, artist, playback state, and timeline information to a compatible
  instrument cluster.
- Built-in update check and installation from this project's GitHub Releases.
- Shareable application logs and a vehicle-compatibility information package.
- Persistent settings across application restarts.

## Safety

- The turn-signal guard can only adjust the indicator state. It does not control steering,
  acceleration, braking, or other vehicle-motion systems.
- Camera and music features do not add vehicle-control actions.
- Guard correction is suppressed when the required vehicle information is unavailable or unsafe.
- Manual indicator controls are available only while the vehicle is in Park.
- Recheck all guard and camera behaviour while parked after installation or a vehicle software
  update.

## Compatibility and requirements

- Tested on the Chinese `BYD Sea Lion 07 EV 2025` with `DiLink 5.0`.
- Other BYD models and firmware versions are not confirmed compatible.
- Local ADB must be enabled on the tablet and its authorization prompt must be accepted.
- Camera access permission is required.
- Instrument-cluster camera and music output requires compatible BYD display services.
- Camera availability, startup time, and image layout can vary between vehicle software versions.

## Installation

1. Download the latest APK from
   [GitHub Releases](https://github.com/sunlixWhyNotAvailable/byd-turnsignal-cameraview/releases/latest).
2. Install the APK and grant camera access when requested.
3. Accept the local ADB authorization prompt shown by Android.
4. Open `Налаштування` and use `Налаштувати фоновий запуск DiLink`. Exclude
   `BYD Turn Signal Guard` from DiLink's disabled-background-app list.
5. Enable `Авто-запуск` if the service should recover automatically after boot and application
   updates.
6. Configure and validate the guard and all camera views while parked before normal use.

## Quick setup

1. In the guard tab, set the steering thresholds, correction delay, and maximum speed before
   enabling `Захист поворотника`.
2. In `Камери сліпих зон`, choose where and when each side-camera profile appears, set its size and
   position, and open `Калібрування` to configure the image.
3. In `Камери паркування`, enable only the required views, set their distance thresholds and the
   global maximum speed, then configure placement and calibration while parked.
4. In `Задній хід`, arrange the rear composition, choose which elements are displayed, and enable
   `Покращений задній вид` only after a parked check.
5. In `Налаштування`, choose the preferred image quality, configure auto-start, check for updates,
   or export logs.
6. To use `Музика`, first enable BYD's stock music-rhythm lighting mode, then enable the app's music
   option.

## Known limitations

- The project relies on vehicle-specific DiLink integrations that may change after a firmware
  update.
- A cold camera start can take several seconds.
- Higher image-quality modes can increase system load; choose the best balance for the vehicle.
- Fisheye correction and the enhanced reverse view are experimental and disabled by default.
- Automatic parking-camera views depend on compatible parking-sensor telemetry and remain
  experimental; all eight rules are disabled by default.
- Music synchronization depends on how an audio application plays sound and publishes metadata;
  some sources are not supported.
- Third-party music metadata forwarding does not include album artwork.

## License

Copyright (C) 2026 sunlixWhyNotAvailable.

This project is free software licensed under the
[GNU Affero General Public License v3.0 only](LICENSE). Modified versions that are distributed or
offered for remote network use must provide their corresponding source under the same license.

AI-assisted log analysis and implementation were used during development.
