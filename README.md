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
- optional enhanced reverse view combining rear, rear-left, and rear-right cameras over the stock reverse screen
- touch-based reverse-layout editor with independent pane position, size, crop, and layer order
- per-side crop calibration, aspect-ratio presets, free crop, scale, and placement
- independent tablet or instrument-cluster destination for each side camera
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
6. Open `Калібрування камер` to select the visible crop for each side.
7. For each side, choose the tablet or instrument cluster, then set its size and position.
8. Open `Камери заднього ходу` to configure the three-pane reverse layout. `Покращений задній вид` is off by default.

## Known Limitations

- The application targets undocumented BYD/DiLink framework APIs and has only been validated on the tested vehicle/software combination.
- Direct-camera startup can take several seconds from a cold provider state.
- Local ADB must be enabled on the tablet.
- Instrument-cluster output depends on the tested BYD projection display and dashboard-layout service.
- Turn correction remains experimental because BYD FID `871366669` is a retained state rather than a pulse command.
- The enhanced reverse view uses undocumented direct AVM outputs and should first be validated while parked.

## Tested

Tested on the Chinese version of `BYD Sea Lion 07 EV 2025` with `DiLink 5.0`.

## To Do

- add red visual when car is in view;
- rework reverse-moving camera control to add wheels view;
- add compose UI;
- add proper logging and storage.
