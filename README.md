# BYD Turn Signal Guard

> Experimental open-source companion app for selected Chinese BYD vehicles running DiLink.
>
> This project is not affiliated with or endorsed by BYD. Configure and test all driving-related
> features while the vehicle is parked.

## Project status

Current source version: `0.52.1`. The application interface is currently Ukrainian.

This README describes the current `main` source tree. The
[latest published APK](https://github.com/sunlixWhyNotAvailable/byd-turnsignal-cameraview/releases/latest)
may be older, so check its version and release notes before installation.

## Current feature set

### `Поворотники` — turn-signal guard

- Helps restore a long turn signal when it is cancelled before the steering wheel returns to the
  configured centre range.
- Configurable steering thresholds, correction delay, and maximum operating speed.
- Safety checks for vehicle state, signal state, telemetry availability, and manual cancellation.
- Optional automatic background start after vehicle boot or an application update.
- Lifetime activation and correction counters.

### `Камери сліпих зон` — blind-zone cameras

- Independent rear-left, rear-right, front-left, and front-right camera profiles.
- Separate trigger rules and speed ranges for the rear and front camera groups.
- Optional sharp-turn rear view, blind-spot-based visibility, warning highlights, and a front-camera
  turn-signal requirement.
- Independent size from 5% to 60%, position, and tablet or instrument-cluster destination for every
  profile.
- Full-screen calibration with source crop, optional fisheye correction, result crop, rotation,
  final-image mirroring, and Fit, Fill, or Stretch display modes.
- Independent calibration and placement for every profile, with built-in defaults available through
  `Скинути`.

Calibration now opens from the `Камери сліпих зон` page instead of using a separate top-level
tab.

### `Камери паркування` — parking cameras

- Eight independent tablet views: front-left corner, front, front-right corner, right, rear-right
  corner, rear, rear-left corner, and left.
- Each view has its own enable switch and editable parking-sensor distance threshold from 0 to
  150 cm. Rules default to off with a 30 cm threshold; the global speed limit defaults to 10 km/h.
- Each corner view can optionally add the corresponding front or rear view when that central view
  was not already activated by its own distance rule.
- Global actions enable or disable all eight parking-camera rules at once.
- An optional setting allows distance-qualified parking views to remain visible together with the
  enhanced reverse view; it is disabled by default.
- Independent 5% to 60% size and tablet position for every view, with optional synchronized sizing.
- Separate calibration from blind-zone and reverse cameras, including crop, rotation, mirroring,
  built-in reset presets, and optional fisheye correction. The rear view starts mirrored by default.

Parking-camera rules are disabled by default and display only on the tablet.

### `Задній хід` — enhanced reverse view

- Optional composition of the rear, rear-left, and rear-right cameras over the stock reverse view.
- Touch editor for pane position, size, crop, rotation, display mode, and layer order.
- Movable black background with independent size and position.
- `Відображати` control for the background and each of the three camera panes. Every element is
  visible by default and can be disabled without losing its layout or calibration.
- Independent calibration, final-image mirroring, reset presets, and optional fisheye correction for
  each camera pane.
- Optional movable and resizable `Перед` / `Зад` selector with a vehicle-and-wheel status view; the
  selector is disabled by default.
- The selector can replace either side pane with its corresponding front camera. Front-side
  integration and calibration are independent for the left and right panes.

The enhanced reverse view itself is disabled by default.

### `Фічі` — music and weather

- Two-column page with independent music and weather controls.
- Optional ambient-light synchronization for compatible third-party Android music players, plus
  available title, artist, playback state, and timeline information on a compatible instrument
  cluster.
- Optional local weather for the stock BYD weather card using the vehicle's Android location and
  attributed Open-Meteo data.
- Configurable automatic weather updates from 5 to 180 minutes, defaulting to 15 minutes, plus
  manual refresh from the app or the stock BYD weather screen.
- A successful manual weather refresh restarts the normal automatic-update interval; failed updates
  preserve the last complete weather data.

Music and weather are disabled by default. The stock weather-screen refresh control requires the
app's Accessibility integration, which is enabled through local ADB; automatic updates and the
in-app weather button do not.

### `Налаштування`, updates, and diagnostics

- Automatic service start after boot or application updates, plus a shortcut to DiLink background
  application settings.
- Local ADB authorization status and a retry action.
- Four global camera image-quality modes: `Швидкодія` (default), `Баланс`, `Якість`, and
  `Оригінал`, applied consistently across supported camera views.
- Global camera-window corner rounding and transparency from 0% (opaque, default) to 100%.
- Built-in update check and installation from this project's GitHub Releases.
- Shareable diagnostic logs and a vehicle-compatibility information package.
- Persistent settings across application restarts.

### `Відладка` — camera diagnostics

- Manual previews for checking available stock camera layouts and individual camera feeds while
  parked.
- Intended for compatibility checks and troubleshooting rather than normal driving use.

## Safety

- The turn-signal guard can only adjust the indicator state. It does not control steering,
  acceleration, braking, or other vehicle-motion systems.
- Camera, parking-sensor, music, and weather features do not add vehicle-control actions.
- Parking-camera automation reads compatible distance and speed information but does not control the
  parking sensors or the vehicle.
- Guard correction is suppressed when the required vehicle information is unavailable or unsafe.
- Manual indicator controls are available only while the vehicle is in Park.
- Recheck all guard and camera behaviour while parked after installation or a vehicle software
  update.

## Compatibility and requirements

- Primarily tested on the Chinese `BYD Sea Lion 07 EV 2025` with `DiLink 5.0`.
- Camera overlays have also been exercised on a `BYD Sea Lion 06 EV` with older DiLink firmware;
  this does not establish full feature parity across the two models.
- Other BYD models and firmware versions are not confirmed compatible.
- Local ADB must be enabled on the tablet and its authorization prompt must be accepted.
- Camera access permission is required.
- Parking-camera automation requires compatible parking-sensor and speed telemetry.
- Weather requires location permission and an internet connection. Automatic updates and the
  in-app refresh button do not require Accessibility; local ADB enables the stock BYD refresh
  button to trigger the same update.
- Instrument-cluster camera and music output requires compatible BYD display services.
- Camera availability, startup time, and image layout can vary between vehicle software versions.

## Installation

1. Download an APK from
   [GitHub Releases](https://github.com/sunlixWhyNotAvailable/byd-turnsignal-cameraview/releases/latest).
   Confirm that the release version contains the features you intend to use; the current source tree
   may be newer.
2. Install the APK and grant camera access when requested.
3. Accept the local ADB authorization prompt shown by Android.
4. Open `Налаштування` and use `Налаштувати фоновий запуск DiLink`. Exclude
   `BYD Turn Signal Guard` from DiLink's disabled-background-app list.
5. Enable `Авто-запуск` if the service should recover automatically after boot and application
   updates.
6. Configure and validate the guard and all camera views while parked before normal use.

## Quick setup

1. In `Поворотники`, set the steering thresholds, correction delay, and maximum speed before
   enabling `Захист поворотника`.
2. In `Камери сліпих зон`, choose where and when each side-camera profile appears, set its size and
   position, and open `Калібрування` to configure the image.
3. In `Камери паркування`, enable only the required views, set their distance thresholds and the
   global maximum speed, then configure placement and calibration while parked.
4. In `Задній хід`, arrange the rear composition, choose which elements are displayed, and enable
   `Покращений задній вид` only after a parked check.
5. In `Налаштування`, choose the preferred image quality, configure auto-start, check for updates,
   or export logs.
6. In `Фічі`, enable music after activating BYD's stock music-rhythm lighting mode, or enable
   weather and grant location access. Weather is disabled by default.

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
- Weather depends on location, network access, Open-Meteo availability, and the stock BYD weather
  components present on the vehicle. Presentation and refresh behaviour can vary by firmware.

## License

Copyright (C) 2026 sunlixWhyNotAvailable.

This project is free software licensed under the
[GNU Affero General Public License v3.0 only](LICENSE). Modified versions that are distributed or
offered for remote network use must provide their corresponding source under the same license.

AI-assisted log analysis and implementation were used during development.
