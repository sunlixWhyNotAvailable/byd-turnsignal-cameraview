# BYD Extend

**English** | [Українська](README.uk.md)

<img align="right" src="app/src/main/res/drawable-nodpi/byd_extend_launcher.png" alt="BYD Extend icon" width="112">

BYD Extend adds configurable camera views, a rearview-mirror widget, turn-signal assistance, and optional music, weather, and exterior-audio integrations to compatible BYD vehicles.

**This is an independent companion app, not a replacement for the vehicle's safety systems. Unlike a read-only telemetry viewer, its enabled turn-signal functions can change the turn-signal state. Configure and test it only while parked.**

- **Compatibility:** primarily tested on the Chinese-market BYD Sea Lion 07 EV 2025 with DiLink 5.0. Features on other models and firmware, including instrument-cluster output, are not guaranteed.
- **Download:** [latest GitHub release](https://github.com/sunlixWhyNotAvailable/byd-turnsignal-cameraview/releases/latest).
- **Package:** `com.byd.extend` — a separate application from the older BYD Turn Signal.
- **Help:** [ADB requirements](#which-functions-need-adb) · [Troubleshooting](#troubleshooting) · [Report a problem](#report-a-problem).

## Installation and first start

You need Android 8.0 or newer, a compatible DiLink system, and permission to install APK files. Local ADB is required for the functions listed below, not for every screen or feature. It lets the app start privileged helper processes and perform system operations.

1. Download the **BYD Extend** APK from the release page. If several apps are attached, choose `byd-extend-v<version>.apk`. Compare its SHA-256 checksum with the published value when available.
2. Install and open the app while parked.
3. Follow the background-work prompt: in DiLink settings, set `Disable background Apps -> BYD Extend` to `OFF`.
4. For ADB-dependent features, enable local ADB using the method supported by your firmware, then accept Android's authorization prompt for BYD Extend. Use `Grant ADB` in Settings to provision the required access.
5. Grant the requested permissions: camera access for camera views, display over other apps for widgets, location for weather, and microphone access for exterior speech. Steering-wheel actions and some integrations also need the app's Accessibility service; follow the permission status in Settings.
6. Enable `Auto-start` if you want automatic startup and recovery. Configure only the features you need.
7. Check each camera's source, orientation, placement, and calibration before relying on its view.

**ADB recovery is not an initial unlock.** It requires an already authorized key and the necessary system permission. It cannot authorize a fresh installation while all ADB access is closed. Firmware updates and tablet resets can require setup again.

### Which functions need ADB?

Here, **Yes** means authorized local ADB is needed to start/restart a privileged helper or perform the specified operation. An already running helper can continue communicating with the app without a permanent ADB connection, but starting it again after a reboot, failure, or replacement requires ADB. A laptop does **not** need to remain connected.

| Function | Is authorized ADB needed? |
| --- | --- |
| Turn-signal guard and manual turn-signal controls | **Yes**, to start/restart the vehicle-control helper. |
| Blind-zone, parking, reverse, and mirror camera output | **Yes**, to start/restart camera and vehicle-state helpers, including the normal in-app camera preview. This applies to tablet and instrument-cluster output. |
| Music metadata, visualization/lighting, and steering-wheel player routing | **Yes**, to start/restart the music helper. Player routing also needs Accessibility. |
| AVAS event sounds, file audition, exterior microphone speech, and engine simulation | **Yes**, to start/restart the audio helper. Microphone speech additionally needs microphone permission and the app's connected Accessibility service. |
| Weather retrieval and updating the stock weather card | **No** for routine updates on supported firmware. Location permission, internet, and access to the stock weather provider are required. Intercepting the stock weather Refresh button also needs Accessibility. |
| Learning buttons and recognizing Single/Hold/Double | **No**, once Accessibility is enabled and connected. Executing the assigned camera, music, or audio action still has the requirements listed above. |
| Language/theme, editing saved settings, and camera-preset JSON import/export | **No** for local configuration and file operations. Applying them to a live camera or vehicle function still requires its working helper. |
| Update checks/downloads and the Support dialog | **No**. Network access and Android's APK-install permission are needed where applicable; hint widgets need overlay permission. |
| Automatic permission setup, including WRITE_SECURE_SETTINGS and Accessibility | **Yes**. An already granted permission is not the same as a currently available ADB connection. |
| Importing settings from BYD Turn Signal | **Yes**, to read the old app's data and complete the runtime handover. This is separate from loading a camera-preset JSON file. |
| Sharing/saving the app's own local diagnostic files | **No** for those files. Export still attempts the system collectors; without ADB, unavailable sections are marked and the archive is incomplete. |
| Full-system logcat snapshot, exporting continuous-logcat history, and compatibility collection | **Yes**, to read system data through the shell. Starting/restarting the continuous recorder also needs ADB. |

**ADB recovery is a separate case:** it is designed to run while ordinary ADB is unavailable, but needs a previously authorized key and previously granted WRITE_SECURE_SETTINGS. The Wi-Fi recovery path also needs Wi-Fi and any required system consent. It does not replace first-time setup.

ADB authorization does not replace camera, microphone, location, overlay, or Accessibility access. Conversely, granting these Android permissions does not make helper-dependent functions ADB-free.

### Moving from BYD Turn Signal

BYD Extend installs separately; it does not overwrite BYD Turn Signal. The first-start flow offers settings import, and the same action is available later in Settings.

Keep the old app installed until import succeeds. Import requires authorized ADB and access to the old app's data. It transfers settings and calibration, not Android permissions or ADB authorization. Successful handover stops the old runtime without deleting its app or source data. Do not run both applications as competing camera or turn-signal controllers.

## Everyday use

The interface supports English, Ukrainian, Simplified Chinese, and Russian, with dark and light themes. A new installation starts in English; the language selector is independent of the tablet's language.

### Turn signals

In `BYD integrations -> Turn signals`, configure the turn-signal guard using steering-angle, return-to-centre, delay, and speed settings. Manual left, right, hazard, and reset controls are also available.

This feature depends on valid vehicle telemetry and compatible BYD controls. It is not a lane-change assistant; always check the actual indicators and surroundings.

Key screenshots are shown inline; detailed views are available in expandable sections. All images show the English dark-theme Preview with demonstration data; scrollable pages are stitched.

<p align="center"><img src="docs/screenshots/en/turn-signals.png" alt="BYD Extend turn-signal settings" width="100%"></p>

### Blind-zone cameras

Configure the rear and front groups independently: enable the required views, choose their activation conditions, and adjust tablet or instrument-cluster placement.

- Available conditions include turn signals, speed, steering angle, and blind-spot detection where the vehicle supplies those signals.
- `Hold camera` can retain the corresponding view for **1–5 seconds** after a short turn signal; the default duration is three seconds.
- `Do not show while panorama is open` applies **only to tablet output**, not the instrument cluster.
- Adjust image crop, correction strength, rotation, mirroring, dimensions, and border for each view.

<p align="center"><img src="docs/screenshots/en/blind-zones.png" alt="BYD Extend blind-zone cameras" width="100%"></p>

<details>
<summary>Placement and calibration</summary>

#### Placement

<p align="center"><img src="docs/screenshots/en/blind-zones-placement.png" alt="Blind-zone cameras — Placement" width="100%"></p>

#### Calibration: original

<p align="center"><img src="docs/screenshots/en/blind-zones-calibration.png" alt="Blind-zone cameras — Calibration: original" width="100%"></p>

#### Calibration: correction

<p align="center"><img src="docs/screenshots/en/blind-zones-correction.png" alt="Blind-zone cameras — Calibration: correction" width="100%"></p>

#### Calibration: output

<p align="center"><img src="docs/screenshots/en/blind-zones-output.png" alt="Blind-zone cameras — Calibration: output" width="100%"></p>

</details>

### Parking cameras

Select the views you need and configure their distance and speed thresholds. Automatic activation requires compatible proximity and speed telemetry; support can differ between vehicles.

Each camera has its own visual settings. These views supplement the stock cameras and parking sensors, not replace them.

<p align="center"><img src="docs/screenshots/en/parking.png" alt="BYD Extend parking cameras" width="100%"></p>

<details>
<summary>Placement and calibration</summary>

#### Placement

<p align="center"><img src="docs/screenshots/en/parking-placement.png" alt="Parking cameras — Placement" width="100%"></p>

#### Calibration: original

<p align="center"><img src="docs/screenshots/en/parking-calibration.png" alt="Parking cameras — Calibration: original" width="100%"></p>

#### Calibration: correction

<p align="center"><img src="docs/screenshots/en/parking-correction.png" alt="Parking cameras — Calibration: correction" width="100%"></p>

#### Calibration: output

<p align="center"><img src="docs/screenshots/en/parking-output.png" alt="Parking cameras — Calibration: output" width="100%"></p>

</details>

### Reverse cameras

Enable `Enhanced reverse view`, choose the composition elements, and arrange them in the preview. Elements have separate tablet/cluster placement and size settings; front-camera integration adds a corresponding front profile where enabled.

- Entering Reverse requests the rear views without waiting for the stock panorama window.
- Optional `Switch cameras by gear` selects integrated front views in D and rear views in R. N and P retain the current selection during a session.
- Gear selection is available only when at least one front-camera integration is enabled. Nonintegrated cameras retain their rear view.
- The selector widget and a learned steering-wheel button can switch an active composition manually. The button uses a single short press and does not open an inactive composition.
- Background, camera, and selector-widget borders are configurable.

<p align="center"><img src="docs/screenshots/en/reverse-placement.png" alt="BYD Extend reverse-camera composition" width="100%"></p>

<details>
<summary>Reverse-camera parameters and calibration</summary>

#### Parameters

<p align="center"><img src="docs/screenshots/en/reverse.png" alt="Reverse cameras — parameters" width="100%"></p>

#### Calibration: original

<p align="center"><img src="docs/screenshots/en/reverse-calibration.png" alt="Reverse cameras — Calibration: original" width="100%"></p>

#### Calibration: correction

<p align="center"><img src="docs/screenshots/en/reverse-correction.png" alt="Reverse cameras — Calibration: correction" width="100%"></p>

#### Calibration: output

<p align="center"><img src="docs/screenshots/en/reverse-output.png" alt="Reverse cameras — Calibration: output" width="100%"></p>

</details>

### Rearview mirror

The independent mirror widget displays a rear camera on the tablet or a supported instrument cluster. Optional front integration adds a separately calibrated front view.

Drag the visible widget to move it. Hold it for **one second** to hide it until the app is opened again. Taps inside the visible widget do not pass through to apps underneath. The widget is hidden while BYD Extend is in the foreground.

Learn separate button actions for switching front/rear and showing/hiding the widget, with **Single, Hold, or Double** gestures. `Return when opening the app` controls whether a widget hidden by a button returns automatically. Panorama suppression applies only to tablet output.

Assigned buttons can replace their original actions. Reset a binding to release it; recognition depends on which key events the firmware exposes.

<p align="center"><img src="docs/screenshots/en/mirror.png" alt="BYD Extend rearview-mirror settings" width="100%"></p>

<details>
<summary>Placement and calibration</summary>

#### Placement

<p align="center"><img src="docs/screenshots/en/mirror-placement.png" alt="Rearview mirror — Placement" width="100%"></p>

#### Calibration: original

<p align="center"><img src="docs/screenshots/en/mirror-calibration.png" alt="Rearview mirror — Calibration: original" width="100%"></p>

#### Calibration: correction

<p align="center"><img src="docs/screenshots/en/mirror-correction.png" alt="Rearview mirror — Calibration: correction" width="100%"></p>

#### Calibration: output

<p align="center"><img src="docs/screenshots/en/mirror-output.png" alt="Rearview mirror — Calibration: output" width="100%"></p>

</details>

## Optional integrations

Enable these separately under `BYD integrations`.

### Music, lighting, and weather

- **Music and lighting:** send available player metadata to the BYD interface and enable audio-reactive visualization/lighting on supported firmware.
- **Steering-wheel player control:** the optional focus setting targets an eligible opened player. It does not automatically start playback and cannot make every third-party player compatible.
- **Engine visualization:** a separate, default-off option controls visualization of engine-only interior audio, not its playback. If other audio is mixed with it, the visualizer cannot isolate the engine from the combined signal.
- **Weather:** update the stock BYD weather card using location and Open-Meteo data. This needs location permission and internet access. The city name depends on the system geocoder; when unavailable, a localized “Current location” label is used.

<details>
<summary>Music and weather settings screenshots</summary>

#### Music and lighting

<p align="center"><img src="docs/screenshots/en/music.png" alt="Music and lighting settings" width="100%"></p>

#### Weather

<p align="center"><img src="docs/screenshots/en/weather.png" alt="Weather integration settings" width="100%"></p>

</details>

### AVAS: event sounds, microphone, and engine simulation

These optional features use the vehicle's supported audio routes. Start at low volume and observe local rules for exterior sound.

| Feature | How to use it |
| --- | --- |
| Lock, unlock, power-on, and power-off sounds | Import audio files, choose a sound or random selection, and set volume separately for each event. Event sounds play sequentially; power-event profiles can suppress the associated lock/unlock sound. |
| File audition | The note button previews a file through the interior navigation route. This is different from exterior playback and can have a different perceived volume. |
| Microphone speech | Enable the feature, grant microphone access, and use Start/Stop or a learned button to toggle exterior speech. An on-screen microphone indicator remains visible while broadcasting. Noise suppression and echo cancellation are available when supported by Android. |
| Engine simulation | Choose a built-in sound pack, enable exterior and/or interior output, and set their volumes independently. Available packs include V6, V8, V10, four-cylinder, flat-six, and V-twin sounds. |

Engine simulation starts automatically from the confirmed vehicle **OK / ready-to-drive** state, not merely ACC. It uses pedal input for stationary revving and available motor/speed signals in motion, with smoothed response. Engine sounds are simulated, not measurements of a combustion engine.

`Test` temporarily overrides live engine playback; `Stop test` returns to the current live state. You do not need to press Test for automatic operation. A learned **Start/stop engine** button can toggle live sound for the current power session, including before OK. If sound is already running, entering OK does not repeat the start sound. The manual state resets when the vehicle powers off.

High microphone volume can cause acoustic feedback; suppression does not guarantee its removal. AVAS features do not replace the stock pedestrian-warning system, and audible behaviour after vehicle sleep can vary by firmware.

<p align="center"><img src="docs/screenshots/en/avas.png" alt="BYD Extend AVAS audio settings" width="100%"></p>

## Presets and diagnostics

In `Settings -> Logs`:

- **Export/load camera presets:** save or load a JSON file containing camera visual settings, including placement, calibration, display selection, and borders. A camera preset is not a full backup: it excludes steering/speed/distance rules, learned buttons, and audio files.
- **Import settings:** migrate from the older BYD Turn Signal app; this is separate from loading a camera preset.
- **Share / Save as:** prepare a diagnostic ZIP and share it or choose a destination. Progress shows the current stage and, when known, processed/remaining volume and percentage. Cancel leaves source logs intact.
- **Compatibility package:** collect system details for investigating model or firmware compatibility.
- **Record logcat:** continuously record system logs. This is **off by default**; turn it on before reproducing an intermittent issue and off afterward.

Continuous logcat can grow very large and is not automatically rotated away. Turning recording off retains history. Clear logs after sending the required evidence, and keep enough free storage for both source files and the ZIP. Without continuous recording, export still captures the system-log buffer available at that moment; it cannot recover overwritten history from before a reboot.

**Review files before sharing.** System logs and compatibility packages may contain identifiers, locations, and information from other apps. Share only what is needed and do not publish credentials. Weather requests send coordinates to the weather provider; update checks contact GitHub.

<p align="center"><img src="docs/screenshots/en/settings.png" alt="BYD Extend settings and diagnostics" width="100%"></p>

<details>
<summary>Screenshot: camera output settings</summary>

<p align="center"><img src="docs/screenshots/en/settings-camera-output.png" alt="General camera output settings" width="100%"></p>

</details>

## Background operation and updates

`Auto-start` controls automatic startup and recovery, not whether manually opened features may run. Keep DiLink's background-app restriction disabled for BYD Extend. Use `Shutdown` in Settings to stop the app and its runtime until you open it again.

Optional ADB recovery attempts to restore previously authorized access. It needs previously granted `WRITE_SECURE_SETTINGS`; the Wi-Fi debugging path also needs a Wi-Fi connection and any system consent required by the firmware. A saved authorization key does not mean that ADB is currently listening.

The app uses notification-listener access for runtime recovery without reading notification contents. Background operation, camera services, and continuous logs can consume power and storage while the vehicle is parked. No Android process survives a kernel reboot; recovery depends on the system and available permissions.

Check updates manually in Settings or enable automatic checks. The update dialog includes release notes and download progress, with cancellation while downloading. An optional update-hint widget requires display-over-other-apps permission. Updating normally preserves settings; do not clear app data when installing an update.

<details>
<summary>Permissions, runtime, and ADB recovery</summary>

### Permissions and runtime

<p align="center"><img src="docs/screenshots/en/settings-permissions.png" alt="Permissions, startup, and update settings" width="100%"></p>

### ADB recovery

<p align="center"><img src="docs/screenshots/en/adb-recovery.png" alt="ADB recovery settings" width="100%"></p>

</details>

## Dialogs and widgets

The examples below show the English dark-theme Preview. Version numbers, archive sizes, and progress are demonstration data. Scrollable dialog content is stitched into a single image.

<details>
<summary>ADB recovery reminder</summary>

The reminder editor previews the Wi-Fi prompt and retry action and lets you adjust the widget's appearance and position. It is a reminder for recovery, not a first-time ADB authorization dialog.

<p align="center"><img src="docs/screenshots/en/adb-reminder-dialog.png" alt="ADB recovery reminder editor" width="100%"></p>

</details>

<details>
<summary>Update-hint widget</summary>

Configure the update hint's appearance, then see how the floating notification of a new version looks. This is separate from the update download dialog.

<p align="center"><img src="docs/screenshots/en/update-hint-dialog.png" alt="Update-hint appearance settings" width="100%"></p>

<p align="center"><img src="docs/screenshots/en/update-hint-widget.png" alt="Floating update-hint widget" width="100%"></p>

</details>

<details>
<summary>Learning a steering-wheel button</summary>

Open the button-learning dialog for the desired action, then press the steering-wheel button you want to assign. The same dialog is shared by the camera and audio controls; one example illustrates it.

<p align="center"><img src="docs/screenshots/en/button-learning-dialog.png" alt="Steering-wheel button-learning dialog" width="100%"></p>

</details>

<details>
<summary>AVAS sound library</summary>

Choose an imported sound for an event and audition it before assigning it. The library shows the available files and the current selection.

<p align="center"><img src="docs/screenshots/en/avas-sound-library-dialog.png" alt="AVAS sound library" width="100%"></p>

</details>

<details>
<summary>Diagnostic archive progress</summary>

When sharing or saving logs, the progress dialog shows the total, processed, and remaining data volume and the percentage when available. One example covers both export actions; Preview uses simulated progress.

<p align="center"><img src="docs/screenshots/en/log-export-progress-dialog.png" alt="Diagnostic archive preparation progress" width="100%"></p>

</details>

<details>
<summary>Border colour picker</summary>

Choose a camera-border colour visually or enter its hexadecimal value. One example illustrates the shared colour picker.

<p align="center"><img src="docs/screenshots/en/border-colour-dialog.png" alt="Camera border colour picker" width="100%"></p>

</details>

## Debug screens

<details>
<summary>Signals, AVM, and direct camera access</summary>

### Signals

<p align="center"><img src="docs/screenshots/en/debug-signals.png" alt="Debug — Signals" width="100%"></p>

### AVM

<p align="center"><img src="docs/screenshots/en/debug-avm.png" alt="Debug — AVM" width="100%"></p>

### Direct camera access

<p align="center"><img src="docs/screenshots/en/debug-direct.png" alt="Debug — Direct camera access" width="100%"></p>

</details>

## Troubleshooting

| Problem | What to check |
| --- | --- |
| ADB or permissions show an error | Keep the tablet awake, verify that local ADB is enabled, accept the authorization prompt, and use Grant ADB. Check the individual permissions as well; ADB access alone is not sufficient for every feature. |
| A camera is black or does not appear | Check its enable switch, activation conditions, selected display, panorama suppression, and camera access. Note the exact time and collect logs if it repeats. |
| Nothing appears on the instrument cluster | Verify that your firmware supports cluster output and that the selected view targets it. An unavailable cluster is not automatically replaced with tablet output. |
| A learned button does nothing | Check Accessibility access, the assigned gesture, and the feature's conditions. Not every firmware exposes every physical key. |
| No AVAS sound or microphone speech | Check feature/output switches, selected file or engine pack, volume, permissions, and the displayed status. Test while parked at low volume; interior audition and exterior playback use different routes. |
| Weather is missing or the city name differs | Check location permission, network access, and refresh status. Naming comes from the system geocoder, not a built-in list of translated cities. |
| Logs take a long time to export | Check the displayed phase and free space. Large continuous logs can require substantial time and temporary storage; use Cancel if needed. |

### Report a problem

1. If possible, enable `Settings -> Logs -> Record logcat` **before** reproducing the problem, then stop recording afterward.
2. Use `Share` or `Save as` for diagnostic logs. Include a compatibility package when the issue concerns a new vehicle or firmware.
3. Open a [GitHub issue](https://github.com/sunlixWhyNotAvailable/byd-turnsignal-cameraview/issues). Include the app version, vehicle/model year, firmware version, approximate local time, reproduction steps, and expected versus actual behaviour.

For camera issues, include the output display and relevant preset/settings. For audio issues, state whether the route was interior or exterior and whether it was the first playback after vehicle sleep. Avoid clearing logs before collecting the evidence.

## Building from source

Use JDK 17 and the Android SDK required by the Gradle project. Distribution builds use the non-debuggable `performance` variant without minification, obfuscation, or resource shrinking.

```sh
./gradlew :app:assemblePerformance
```

On Windows, use `.\gradlew.bat :app:assemblePerformance`. The APK is copied to `build_output/byd-extend-v<version>.apk`.

The current build script uses the existing `debug` signing configuration for update continuity; its name does not make this variant debuggable. A local build can update an installed copy only if its signing certificate matches. Do not confuse the source version with a published release.

## Support the project

Donations voluntarily support the development and improvement of BYD Extend and apps for BYD cars.

**Jar card number — primary donation method:**

```text
4874 1000 3354 3078
```

Copy this number and use your bank's card-to-card transfer feature. You do not need the mono app for this method; availability, limits, and fees depend on your bank or transfer provider. Check the recipient details before confirming.

<details>
<summary>Alternative: monobank Jar link and QR code</summary>

[Open the donation Jar](https://send.monobank.ua/jar/bKFV15i9e), or scan the QR code:

<p><a href="https://send.monobank.ua/jar/bKFV15i9e"><img src="app/src/main/res/drawable-nodpi/mono_support_qr.jpg" alt="QR code for the Support BYD app donation Jar" width="240"></a></p>

</details>

The same details are available under `Support` beside the app title. Donations are optional; the app never makes a payment automatically.

## License

BYD Extend is licensed under the [GNU Affero General Public License v3.0 only](LICENSE). It is an independent project, not affiliated with or endorsed by BYD or the services mentioned here. Trademarks belong to their respective owners.

Generative AI tools are used for development, testing, diagnostic analysis, and documentation.

Use the app at your own risk. Camera images and telemetry can be incomplete, delayed, or unavailable. Keep the vehicle's stock safety systems in use and install, configure, and troubleshoot only while parked.
