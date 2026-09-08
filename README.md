# BYD Extend

> Experimental open-source companion app for selected Chinese BYD vehicles running DiLink.
>
> This project is not affiliated with or endorsed by BYD. Configure and test all driving-related
> features while the vehicle is parked.

## Project status

Current source target: `1.1.0` (version code `100`), with Ukrainian, English and Simplified Chinese
interfaces, dark and light themes, and a Compose UI. Published builds are listed in the
[GitHub Releases](https://github.com/sunlixWhyNotAvailable/byd-turnsignal-cameraview/releases).

## Features

- Turn-signal guard with configurable steering, delay, speed, telemetry, and cancellation safety
  checks.
- Turn-triggered side-camera overlays on the tablet or instrument cluster.
- Independent parking-camera views driven by compatible proximity and speed telemetry.
- Reverse composition, direct camera preview, calibration, fisheye correction, mirroring, crop,
  rotation, placement, scale, transparency, corner rounding, and image-quality controls.
- User-selectable Reverse panes with optional integrated front side and central camera profiles.
- Optional gear-driven front/rear selection while Reverse gear or the stock camera UI is active.
- An independent rearview-mirror widget on the tablet or a supported instrument cluster.
- A learned steering-wheel button for switching the active Reverse widget and integrated cameras
  between front and rear views.
- Optional music metadata and local weather for the stock BYD weather UI.
- Diagnostics and explicit log sharing.

## Camera presets and settings migration

`Налаштування` provides three file/settings actions:

- `Вивантажити пресети камер` shares a versioned JSON camera preset through Android.
- `Завантажити пресети камер` loads a selected JSON preset through the native document picker.
- `Імпортувати всі налаштування` imports all user settings from the installed legacy
  `com.byd.turnsignalguard.capture` `0.52.1` / code `96` app through authorized local ADB.

Camera presets include the active Blind Zone, Parking, Reverse and Rearview Mirror visual configuration, including
integrated Reverse Front profiles, display targets, placement, scale, aspect, crop, correction,
rotation, mirror, fill mode, camera switches, Reverse background, widget/stacking, and global
buffer quality, transparency, and corner radius. They deliberately exclude numeric speed,
steering-angle, and distance triggers, non-camera settings, and saved local calibration slots.
The learned steering-button binding is also excluded and is preserved when loading camera presets
or importing legacy settings.

Preset format v2 adds independent Blind rectangles and the active Mirror configuration. Existing
v1 files remain supported; importing one does not reset Mirror settings that it does not contain.
Local saved calibration slots and temporary Mirror hiding are not part of a camera preset.
Built-in camera geometry and calibration use the approved reference baseline only for absent
values and explicit section resets; updating the application does not replace stored user settings.

Full migration includes those numeric rules, guard, music, weather, auto-start, and saved
calibration slots. It does not copy transient runtime data, logs, ADB credentials, or Android-granted
permissions. When imported weather is enabled, BYD Extend requests its own location permission
without requiring the weather switch to be toggled again. Denying the request leaves weather off.
Existing local ADB authorization is retained when updating BYD Extend.

Validation, read, or cancellation failure leaves the destination unchanged. If the
settings have already been saved but shutting down the old app or stopping its helpers fails, the
imported settings remain and BYD Extend keeps its runtime blocked until the handover is retried.
The legacy source data is never deleted.

After a validated migration, the old application is shut down before BYD Extend starts. Its APK,
data, and launcher icon remain available, so it can still be opened or uninstalled normally.
For an earlier migration that disabled the old application, Settings offers a confirmed action
to restore its launcher access while keeping its runtime stopped; current BYD Extend settings
are not imported again or overwritten. Shut down BYD Extend before deliberately reopening the
old application. A first legacy-to-Extend installation is a manual APK install because the
package identity changes.

## Reverse Widget steering button

In Reverse, select `Widget` and use `Select button…` below `Enhanced reverse view`. The first
steering-wheel press assigns its Android key code without switching cameras. Cancel or Android
Back leaves the previous assignment unchanged; the square reset clears only the assignment.

Subsequent assigned presses switch the existing composition and its widget together, once per
physical press. Enhanced reverse, the widget and at least one front-camera integration must be
enabled, and the composition must already be active. Existing per-camera visibility/integration
rules apply; the button does not open cameras, change calibration or add automatic switching in D.
Calibration and other foreground app tabs do not redirect the button to a background composition.

The separate `Switch by gear` setting is off by default. When enabled, R prepares the composition
immediately; the session remains active while R or the stock camera UI is active. Entering D selects
integrated front cameras, and entering R selects rear cameras. N and P retain the selected view
during a session, or select Front on a cold opening. Repeated telemetry does not undo manual
selection. Automatic selection does not require the selector widget to be visible.

The assigned press, repeats and release replace the stock key action, including when no eligible
composition is active. Reset the assignment to restore normal handling. Explicit app Shutdown
ends interception until the app is reopened. The assignment survives app restarts.

This feature uses the app's existing Accessibility service. If the key filter is unavailable,
learning reports that condition instead of saving a simulated assignment. Some BYD firmware
handles keys before Android Accessibility receives them; support for every physical button is
not guaranteed.

## Rearview mirror and UI preferences

Enable the rearview mirror and grant Android overlay permission when prompted. Its independent
placement, calibration, preset and border settings do not modify Reverse or Blind profiles.
Drag the external widget to move it; touch and hold to hide it until BYD Extend is opened again.
It is hidden while BYD Extend or the stock camera UI is open. A hidden widget leaves no input
window blocking other apps, while taps inside the visible widget do not pass through.

Global Auto-start controls autonomous startup and recovery; turning it off still permits manual
Mirror use. An unavailable instrument cluster does not redirect the widget to the tablet.
Blind and Mirror editors support bounded independent width/height and drag/resize, with 0.1%
placement precision. Tab/subsection scroll positions are remembered within the application process
and cleared on explicit Shutdown or process death.

Fresh installations start in English regardless of the tablet language. Existing effective
language choices are preserved, and the Chinese selector label remains `中文` in every language.

## Sharing diagnostics

Settings can share logs or create a compatibility package. Compatibility export displays the
current phase, file, file count, and transferred bytes, and can be cancelled without sharing a
partial archive. It supports individual files up to 512 MiB and a total payload up to 1 GiB;
missing or inaccessible optional files are reported in the package instead of stopping the export.

## Compatibility and requirements

- Primarily tested on the Chinese `BYD Sea Lion 07 EV 2025` with `DiLink 5.0`.
- Camera overlays have also been exercised on a `BYD Sea Lion 06 EV` with older DiLink firmware;
  this does not establish full feature parity.
- Other BYD models and firmware versions are not confirmed compatible.
- BYD Extend has its own Android permissions and local ADB authorization. Enable local ADB on the
  tablet and accept its authorization prompt; authorization is not shared with the legacy package.
- Camera access is required. Parking views require compatible proximity and speed telemetry.
- Weather requires location permission and internet access. Stock weather refresh and the optional
  learned Reverse button share the app's Accessibility service, enabled through local ADB.
- Instrument-cluster camera and music output require compatible BYD display services.

## Installation

1. Download `byd-extend-v1.0.0.apk` from
   [GitHub Releases](https://github.com/sunlixWhyNotAvailable/byd-turnsignal-cameraview/releases).
2. Install it manually, grant camera/location access as requested, and accept the BYD Extend local
   ADB authorization prompt.
3. Open `Налаштування`, configure background start, and enable `Авто-запуск` if recovery after
   boot and application replacement is required.
4. Configure and validate the guard and every camera view while parked before normal use.

Do not uninstall the legacy app before a successful full migration if its settings or calibration
slots are needed. Do not run both applications as competing vehicle-control owners.

## License

Copyright (C) 2026 sunlixWhyNotAvailable.

This project is free software licensed under the
[GNU Affero General Public License v3.0 only](LICENSE). Modified versions that are distributed or
offered for remote network use must provide their corresponding source under the same license.
