# BYD Turn Signal and Camera View

> Personal open-source project for Chinese BYD vehicles running DiLink.
>
> Adds a configurable turn-signal guard and rear side-camera overlays.
>
> AI was used to analyze vehicle logs and assist with implementation.

## Features

- configurable long-turn guard using raw stalk, blink, steering-angle, and speed telemetry
- configurable steering thresholds, correction delay, and maximum guard speed
- independently configured rear-left, rear-right, front-left, and front-right camera overlays
- separate rear/front enable switches, speed thresholds, and an optional front-camera turn-signal requirement
- optional enhanced reverse view combining rear, rear-left, and rear-right cameras over the stock reverse screen, with configurable static parking-distance guides
- touch-based reverse-layout editor with independent pane position, size, crop sliders, layer order, and a movable black background pane
- four-camera crop calibration with aspect-ratio presets, free crop, Fit/Fill/aligned rotation modes, scale, placement, and optional per-lens GPU fisheye correction
- independent tablet or instrument-cluster destination for every side camera
- configurable production-camera corner radius and a separate application settings tab
- optional stock ambient-light synchronization for third-party Android music sources
- AVM layout and direct-camera diagnostic tabs
- persistent settings and lifetime turn/correction counters

## Safety Boundary

The application does not expose arbitrary shell commands, CAN/OBD writes, caller-selected FIDs, or generic BYD framework transactions.

The only vehicle write path is the fixed turn-state FID `871366669` with payloads `0..3`. It remains behind the existing raw-stalk, blink confirmation, speed, hazard, fault, telemetry-health, and manual-cancel safety gates. Camera rendering does not add vehicle-control writes.

## Installation

Download and install the latest APK from GitHub Releases.

After first launch:

1. Grant the requested camera permission.
2. In DiLink `Disable background Apps`, deselect `BYD Turn Signal Guard` to allow boot recovery.
3. Accept the Android local-ADB RSA prompt. The key is generated and stored privately by the application.
4. Enable `Авто-запуск` if the service should recover after vehicle boot and application updates.
5. Configure the turn guard and camera speed limits.
6. Open `Калібрування камер` to select the visible crop for each rear/front side camera.
7. For each camera, choose the tablet or instrument cluster, then set its size and position.
8. Open `Задній хід` to configure the mirrored central rear pane, side panes, and black background. `Покращений задній вид` is off by default.
9. To use `Музика`, first enable BYD's stock music-rhythm lighting mode, then enable the app switch. The same switch also forwards title, artist, playback state, and timeline from common third-party Android media players to the instrument cluster. The app switch is off by default.

## Known Limitations

- The application targets undocumented BYD/DiLink framework APIs and has only been validated on the tested vehicle/software combination.
- Direct-camera startup can take several seconds from a cold provider state.
- Local ADB must be enabled on the tablet.
- Instrument-cluster output depends on the tested BYD projection display and dashboard-layout service.
- Turn correction remains experimental because BYD FID `871366669` is a retained state rather than a pulse command.
- The enhanced reverse view uses undocumented direct AVM outputs and should first be validated while parked.
- Fisheye correction is off by default and adds one GPU render pass per corrected physical camera stream. Calibrate and performance-test it while parked before regular use.
- The red/yellow parking guides are static distance references, not the stock steering-dependent predicted trajectory.
- Simultaneous rear/front crops from the same physical side depend on duplicate-index Surface support and must pass the bundled parked probe before public release.
- Music synchronization supports normal Android PCM output. Hardware radio and direct, tunneled, or offload audio routes may bypass the system visualizer.
- Third-party metadata forwarding does not include album artwork. BYD's protected cover provider is unavailable to the shell helper, while stock Bluetooth, Local Media, and supported OEM players keep their native metadata path.

## Tested

Tested on the Chinese version of `BYD Sea Lion 07 EV 2025` with `DiLink 5.0`.

## License

Copyright (C) 2026 sunlixWhyNotAvailable.

This project is free software licensed under the
[GNU Affero General Public License v3.0 only](LICENSE). Modified versions that
are distributed or offered for remote network use must provide their
corresponding source under the same license.

## To Do

- add red visual when car is in view;
- rework reverse-moving camera control to add wheels view;
- add compose UI;
- add proper logging and storage.
