# BYD Turn Signal and Camera View

> Personal open-source project for Chinese BYD vehicles running DiLink.
>
> Adds a configurable turn-signal guard and rear side-camera overlays.
>
> AI was used to analyze vehicle logs and assist with implementation.

## Features

- configurable long-turn guard using raw stalk, blink, steering-angle, and speed telemetry
- configurable steering thresholds, correction delay, and maximum guard speed
- left/right rear side-camera overlays triggered by the corresponding turn signal and speed condition
- per-side crop calibration, aspect-ratio presets, free crop, scale, and screen placement
- AVM layout and direct-camera diagnostic tabs
- persistent settings and lifetime turn/correction counters

## Safety Boundary

The application does not expose arbitrary shell commands, CAN/OBD writes, caller-selected FIDs, or generic BYD framework transactions.

The only vehicle write path is the fixed turn-state FID `871366669` with payloads `0..3`. It remains behind the existing raw-stalk, blink confirmation, speed, hazard, fault, telemetry-health, and manual-cancel safety gates. Camera rendering does not add vehicle-control writes.

## Installation

Download and install the latest APK from GitHub Releases.

After first launch:

1. Grant the requested camera and overlay permissions.
2. Accept the Android local-ADB RSA prompt. The key is generated and stored privately by the application.
3. Enable `Авто-запуск` if the service should recover after vehicle boot and application updates.
4. Configure the turn guard and camera speed limits.
5. Open `Калібрування камер` to select the visible crop for each side.
6. Use the placement editor to set the overlay size and independent left/right screen positions.

## Known Limitations

- The application targets undocumented BYD/DiLink framework APIs and has only been validated on the tested vehicle/software combination.
- Camera overlay opacity is controlled by DiLink's `TYPE_APPLICATION_OVERLAY` composition and may remain translucent even with an opaque window format.
- Direct-camera startup can take several seconds from a cold provider state.
- Local ADB must be enabled on the tablet.
- Turn correction remains experimental because BYD FID `871366669` is a retained state rather than a pulse command.

## Tested

Tested on the Chinese version of `BYD Sea Lion 07 EV 2025` with `DiLink 5.0`.

## To Do

- add red visual when car is in view;
- rework reverse-moving camera control to add wheels view;
- add compose UI;
- add proper logging and storage.
