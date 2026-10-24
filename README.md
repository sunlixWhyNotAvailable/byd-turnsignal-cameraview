# BYD Extend

**English** | [Українська](README.uk.md)

![BYD Extend icon](docs/images/byd-extend.png)

BYD Extend adds configurable camera views, a rearview-mirror widget, turn-signal assistance, and optional music, weather, and exterior-audio integrations to compatible BYD vehicles.

**This is an independent companion app, not a replacement for the vehicle's safety systems. Unlike a read-only telemetry viewer, its enabled turn-signal functions can change the turn-signal state. Configure and test it only while parked.**

- **Compatibility:** primarily tested on the Chinese-market BYD Sea Lion 07 EV 2025 with DiLink 5.0. Features on other models and firmware, including instrument-cluster output, are not guaranteed.
- **Download:** [latest GitHub release](https://github.com/sunlixWhyNotAvailable/byd-turnsignal-cameraview/releases/latest).
- **Package:** `com.byd.extend` — a separate application from the older BYD Turn Signal.
- **Help:** [ADB requirements](#which-functions-need-adb) · [Remote cameras](#remote-camera-viewing-setup-and-everyday-use) · [Troubleshooting](#troubleshooting) · [Report a problem](#report-a-problem).

## Contents

- [Installation and first start](#installation-and-first-start)
  - [Which functions need ADB?](#which-functions-need-adb)
  - [Moving from BYD Turn Signal](#moving-from-byd-turn-signal)
- [Everyday use](#everyday-use)
  - [Turn signals](#turn-signals)
  - [Blind-zone cameras](#blind-zone-cameras)
  - [Parking cameras](#parking-cameras)
  - [Reverse cameras](#reverse-cameras)
  - [Rearview mirror](#rearview-mirror)
  - [Cameras integration](#cameras-integration)
- [Remote camera viewing: setup and everyday use](#remote-camera-viewing-setup-and-everyday-use)
  - [What you need](#what-you-need)
  - [Which method should I choose?](#which-method-should-i-choose)
  - [Using the Extend page (Tailscale and zrok)](#using-the-extend-page-tailscale-and-zrok)
  - [Access, passwords and safe use](#access-passwords-and-safe-use)
- [Optional integrations](#optional-integrations)
  - [Music, lighting, and weather](#music-lighting-and-weather)
  - [AVAS: event sounds, microphone, and engine simulation](#avas-event-sounds-microphone-and-engine-simulation)
- [Presets and diagnostics](#presets-and-diagnostics)
- [Background operation and updates](#background-operation-and-updates)
- [Dialogs and widgets](#dialogs-and-widgets)
- [Debug screens](#debug-screens)
- [Troubleshooting](#troubleshooting)
  - [Report a problem](#report-a-problem)
- [Building from source](#building-from-source)
- [Support the project](#support-the-project)
- [License](#license)

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
| Cameras integration: Motion warnings, recording and remote viewing | **Yes**, to start/restart the relevant helpers. Remote viewing also needs the vehicle's internet connection; the phone/PC viewer does not need ADB. Playing already saved local videos does not need a live camera source. |
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

![BYD Extend turn-signal settings](docs/screenshots/en/turn-signals.png)

### Blind-zone cameras

Configure the rear and front groups independently: enable the required views, choose their activation conditions, and adjust tablet or instrument-cluster placement.

- Available conditions include turn signals, speed, steering angle, and blind-spot detection where the vehicle supplies those signals.
- `Hold camera` can retain the corresponding view for **1–5 seconds** after a short turn signal; the default duration is three seconds.
- `Do not show while panorama is open` applies **only to tablet output**, not the instrument cluster.
- Adjust image crop, correction strength, rotation, mirroring, dimensions, and border for each view.

![BYD Extend blind-zone cameras](docs/screenshots/en/blind-zones.png)

<details>
<summary>Placement and calibration</summary>

#### Placement

![Blind-zone cameras — Placement](docs/screenshots/en/blind-zones-placement.png)

#### Calibration: original

![Blind-zone cameras — Calibration: original](docs/screenshots/en/blind-zones-calibration.png)

#### Calibration: correction

![Blind-zone cameras — Calibration: correction](docs/screenshots/en/blind-zones-correction.png)

#### Calibration: output

![Blind-zone cameras — Calibration: output](docs/screenshots/en/blind-zones-output.png)

</details>

### Parking cameras

Select the views you need and configure their distance and speed thresholds. Automatic activation requires compatible proximity and speed telemetry; support can differ between vehicles.

Each camera has its own visual settings. These views supplement the stock cameras and parking sensors, not replace them.

![BYD Extend parking cameras](docs/screenshots/en/parking.png)

<details>
<summary>Placement and calibration</summary>

#### Placement

![Parking cameras — Placement](docs/screenshots/en/parking-placement.png)

#### Calibration: original

![Parking cameras — Calibration: original](docs/screenshots/en/parking-calibration.png)

#### Calibration: correction

![Parking cameras — Calibration: correction](docs/screenshots/en/parking-correction.png)

#### Calibration: output

![Parking cameras — Calibration: output](docs/screenshots/en/parking-output.png)

</details>

### Reverse cameras

Enable `Enhanced reverse view`, choose the composition elements, and arrange them in the preview. Elements have separate tablet/cluster placement and size settings; front-camera integration adds a corresponding front profile where enabled.

- Entering Reverse requests the rear views without waiting for the stock panorama window.
- Optional `Switch cameras by gear` selects integrated front views in D and rear views in R. N and P retain the current selection during a session.
- Gear selection is available only when at least one front-camera integration is enabled. Nonintegrated cameras retain their rear view.
- The selector widget and a learned steering-wheel button can switch an active composition manually. The button uses a single short press and does not open an inactive composition.
- Background, camera, and selector-widget borders are configurable.

![BYD Extend reverse-camera composition](docs/screenshots/en/reverse-placement.png)

<details>
<summary>Reverse-camera parameters and calibration</summary>

#### Parameters

![Reverse cameras — parameters](docs/screenshots/en/reverse.png)

#### Calibration: original

![Reverse cameras — Calibration: original](docs/screenshots/en/reverse-calibration.png)

#### Calibration: correction

![Reverse cameras — Calibration: correction](docs/screenshots/en/reverse-correction.png)

#### Calibration: output

![Reverse cameras — Calibration: output](docs/screenshots/en/reverse-output.png)

</details>

### Rearview mirror

The independent mirror widget displays a rear camera on the tablet or a supported instrument cluster. Optional front integration adds a separately calibrated front view.

Drag the visible widget to move it. Hold it for **one second** to hide it until the app is opened again. Taps inside the visible widget do not pass through to apps underneath. The widget is hidden while BYD Extend is in the foreground.

Learn separate button actions for switching front/rear and showing/hiding the widget, with **Single, Hold, or Double** gestures. `Return when opening the app` controls whether a widget hidden by a button returns automatically. Panorama suppression applies only to tablet output.

Assigned buttons can replace their original actions. Reset a binding to release it; recognition depends on which key events the firmware exposes.

![BYD Extend rearview-mirror settings](docs/screenshots/en/mirror.png)

<details>
<summary>Placement and calibration</summary>

#### Placement

![Rearview mirror — Placement](docs/screenshots/en/mirror-placement.png)

#### Calibration: original

![Rearview mirror — Calibration: original](docs/screenshots/en/mirror-calibration.png)

#### Calibration: correction

![Rearview mirror — Calibration: correction](docs/screenshots/en/mirror-correction.png)

#### Calibration: output

![Rearview mirror — Calibration: output](docs/screenshots/en/mirror-output.png)

</details>

### Cameras integration

Open `BYD integrations → Cameras`. The three tabs are **Viewing**, **Tablet display**, and **Video recording**.

- **Tablet display:** shows your chosen warning template when motion is detected while the vehicle is off, subject to the selected lock condition.
- **Video recording:** saves Motion event videos with a configurable buffer before/after the event, optional audio, storage limits, and an archive.
- **Viewing:** lets you connect from a phone or computer. It does **not** require tablet warnings or recording to be enabled.

Tablet display and recording keep camera-related components active while parked and consume battery. Remote live viewing also consumes battery and data while connected. Do not assume consumption is identical to OEM Sentry.

<details>
<summary>Tablet display, recording and low-battery protection</summary>

![Cameras — tablet warning and shared charge limits](docs/screenshots/en/cameras-tablet.png)

![Cameras — recording, storage and archive](docs/screenshots/en/cameras-recording.png)

| Shared charge limit | Range | Default |
| --- | --- | --- |
| SOC | 10–90% in 1% steps | 20% |
| 12V battery | 12.0–13.0V in 0.1V steps | 12.4V |

At or below either valid limit, tablet warnings, Motion recording and remote viewing pause without changing their switches; an active recording is finalized. Driving cameras (reverse/blind zones/etc.) are not stopped by these limits. A confirmed low-charge stop persists until fresh valid readings exceed every triggered threshold; enabled features then resume under their normal conditions. If a reading is missing without an earlier confirmed low-charge stop, the app warns that protection for that reading is unavailable: continuing is your decision.

</details>

## Remote camera viewing: setup and everyday use

### What you need

**On the vehicle:**

- BYD Extend set up with authorized local ADB, working camera/helper access and the required permissions. A laptop does **not** have to remain connected.
- Internet that remains available **after you leave the vehicle**: for example, the vehicle's own mobile connection or a Wi-Fi connection that stays in range. A hotspot on the phone you take away will not do.
- Background operation allowed in DiLink. For unattended recovery, enable Extend's Auto-start and complete the background/ADB setup described above.
- `Cameras → Viewing → Allow remote viewing` enabled, a configured provider, and charge above the shared limits.
- Compatible camera firmware and a hardware H.264 encoder; there is no software-encoding fallback. A parked vehicle can be viewed only while its Android/network/helper path remains reachable. A link cannot wake a completely offline vehicle; recovery after deep sleep or reboot is not guaranteed on every firmware.

**On your phone or computer:** internet and a current browser with video playback support. No Extend APK is needed there. Only **private Tailscale Serve** additionally requires a connected Tailscale client with permission to reach the vehicle.

All three transports are embedded in Extend: do not install a second tunnel app on the car just for these instructions, open router ports, or set up a website/GitHub Pages. The viewing URL is an access address, not cloud storage. Traffic counts against the car's data allowance; provider availability and limits also apply.

### Which method should I choose?

| Method | Setup on the vehicle | Phone / computer | What you get |
| --- | --- | --- | --- |
| **VDO.Ninja** | Stream ID + viewer password; no provider account | Browser + password; no VPN | Easiest starting point. Live four-camera composition in the standard VDO player. **No** Extend page, individual-camera selector, archive or remote mode switches. |
| **Tailscale · Serve** | Tailscale account, approve the vehicle, MagicDNS + HTTPS | Tailscale connected to the same permitted private network, then browser | Extend's **Live / Recordings** page; access stays within your tailnet. |
| **Tailscale · Funnel** | Same account, plus Funnel permission and viewer password | Browser + password; VPN not required | The same Extend page through a public HTTPS address. |
| **zrok** | myzrok account token + connection name + viewer password | Browser + password; no VPN or zrok installation | The same Extend page through a public HTTPS address. |

“VDO works out of the box” means **no separate provider account or VPN setup**—it still needs internet, enabled viewing and a password. Serve is useful when you want private-network access; Funnel/zrok when you want to use an ordinary browser without joining that network.

The screenshots below are **Preview demonstrations**, not working vehicle links or evidence of real authorization. EN/UK images use the dark theme; scrollable screens are stitched.

<details>
<summary>VDO.Ninja — quickest setup, live composition only</summary>

**On the vehicle**

1. Open `BYD integrations → Cameras → Viewing`; enable **Allow remote viewing** and select **VDO.Ninja**.
2. Enter a **Stream ID**, or press **Generate ID**. You choose this ID in Extend; you do not need to copy an account/device ID from VDO. If reusing your own stream ID, paste only its value—not a complete VDO link. Your own ID may contain **1–64 Latin letters, digits or underscores**; no spaces/hyphens. It is case-sensitive.
3. Enter a **Viewer password**: **1–49 Latin letters or digits**, case-sensitive; no spaces or punctuation. These are VDO's format limits, not a recommendation to use a short password. Use a long, unique password. [VDO password reference](https://docs.vdo.ninja/advanced-settings/setup-parameters/and-password).
4. Press **Authorize**. For VDO this connects the saved stream configuration; there is no account sign-in page. Typing in a field alone does not authorize it.
5. Wait for **Ready to start viewing** and an actual viewer link. Use **Copy link** or **QR code**.

**On the phone / computer**

1. Scan the QR code on your phone, or transfer the copied link to your computer and open it in a browser.
2. Enter the **same viewer password**. Extend already prepares the password prompt; do not edit the URL or append a password yourself.
3. Wait for the live composition. If the browser requests a tap to start video, tap it.
4. Use VDO's **Hang Up** button to disconnect, or close the viewing tab.

![VDO.Ninja settings in Preview](docs/screenshots/en/cameras-vdo.png)

</details>

<details>
<summary>Tailscale Serve — private access from your own devices</summary>

**Account and phone / computer**

1. Create/sign in to a [Tailscale account](https://tailscale.com/). Its private network is called a **tailnet**.
2. In the [DNS settings](https://login.tailscale.com/admin/dns), enable **MagicDNS** and **HTTPS Certificates**. HTTPS publishes the device/domain name in certificate-transparency records: use a neutral name, not your registration plate or other private information. [HTTPS setup](https://tailscale.com/docs/how-to/set-up-https-certificates).
3. Install Tailscale on each phone/computer you will use, sign in to the permitted tailnet and connect it. Your access rules must allow that device/user to reach the vehicle's HTTPS service (port 443); avoid unnecessarily opening the whole network. [Serve access rules](https://tailscale.com/docs/features/tailscale-serve).

**On the vehicle**

1. Enable **Allow remote viewing**, select **Tailscale**, then **Private · Serve**.
2. Choose a neutral **Vehicle device name**, such as `byd-car`: **1–63 lowercase Latin letters, digits or hyphens**, no leading/trailing hyphen. This names the vehicle; it is not the ID/name of your phone or computer. **Do not copy an auth key, API key, IP address or device ID from Tailscale into this field.** Both Serve and Funnel use the browser approval in the next step, not a pasted token.
3. Press **Authorize**. Complete sign-in and device approval on the Tailscale page opened in the browser. If your tailnet requires administrator approval, complete that too.
4. Return to Extend and wait for the ready status and HTTPS link. **Browser approval alone is not confirmation that the tunnel is ready.**

**To view**

Keep Tailscale connected on the phone/computer, open the copied link or scan the QR code, and use Extend's page. Serve uses tailnet identity/access policy instead of an Extend viewer password.

![Private Tailscale Serve settings in Preview](docs/screenshots/en/cameras-tailscale-serve.png)

</details>

<details>
<summary>Tailscale Funnel — public link protected by a viewer password</summary>

1. Complete the Tailscale account, MagicDNS, HTTPS and vehicle-approval steps above.
2. In Tailscale's **Access controls**, allow the `funnel` node attribute for the account or tag that owns the vehicle. An administrator can use **Add Funnel to policy**; do not replace the entire policy or grant unrelated permissions. Authorizing the vehicle does not grant this capability by itself. [Funnel requirements and policy](https://tailscale.com/docs/features/tailscale-funnel).
3. On the vehicle, select **Public · Funnel** and set a strong, non-empty **Viewer password**.
4. Press **Authorize** for initial setup, or **Apply connection settings** when changing an already authorized profile. Wait for ready status; while changes are pending, the old link is not a confirmation of the new mode.
5. On your phone/computer, open the displayed HTTPS link and enter the viewer password. Tailscale VPN is **not required**. In Funnel mode, access through an already connected tailnet is also accepted, but still requires the viewer password.

**Serve and Funnel share a Tailscale identity/address, but only the applied mode is active.** The same URL does not mean both modes are enabled. Selecting a different provider in the form is different from applying a new Tailscale mode. Public DNS may take time to propagate when Funnel is first enabled.

![Public Tailscale Funnel settings in Preview](docs/screenshots/en/cameras-tailscale-funnel.png)

</details>

<details>
<summary>zrok — public viewing without a VPN client</summary>

**On the phone / computer**

1. Sign in to [myzrok.io](https://myzrok.io/).
2. Click the **zrok icon in the left sidebar** to open the API console, then the green **CLICK HERE TO GET STARTED!** button. In **step 2** of that wizard, copy the **account token**. This build uses the zrok **v2** service (`api-v2.zrok.io`). [Official token instructions](https://netfoundry.io/docs/zrok/get-started/get-token/).
3. Transfer **only the token value** to the vehicle's **Account token** field. If shown as `zrok2 enable YOUR_ACCOUNT_TOKEN`, copy only the value in place of `YOUR_ACCOUNT_TOKEN`—not the command, spaces, quotes or the placeholder itself. Do not use an environment ID (`EnvZId`), share ID/token, viewing URL, myzrok password or token from the older service. You do not need to run that command: Extend enables its own environment when you press **Authorize**. [Token versus environment](https://netfoundry.io/docs/zrok/get-started/enable-env/).

**On the vehicle**

1. Enable **Allow remote viewing**, select **Zrok** and enter that **Account token**. Treat it as a secret: never send it to viewers or include it in screenshots.
2. Choose a **Connection name**, for example `byd-car`: **1–63 lowercase Latin letters, digits or hyphens**, no hyphen at either end. This is your label for the connection, not another ID to copy from zrok.
3. Set a separate, strong **Viewer password**. This is what you enter when opening the page; it is not your myzrok password or account token.
4. Press **Authorize**. Extend registers the vehicle and prepares the viewing link. There is no second browser sign-in or separate “Start viewing” step.
5. Wait for ready status, then copy the link or display its QR code.

**To view**

Open the link in a phone/computer browser and enter the viewer password. No zrok program or VPN is needed on the viewing device. Extend reuses its saved publication across ordinary reconnects; revoking/replacing access can change the link, so use the current one shown in the app.

![zrok settings in Preview](docs/screenshots/en/cameras-zrok.png)

</details>

### Using the Extend page (Tailscale and zrok)

The page uses the language and theme selected in Extend.

- **Live:** choose Composition, Front, Rear, Left or Right. The output changes without deliberately closing and reopening the camera source. Live video has no audio and does not itself create recordings.
- **Recordings:** select a date in the calendar and a recording. Use the event timeline, seek, playback speed and fullscreen controls. Red event markers take priority where they overlap yellow ones. Recorded audio plays when present. Only Extend recordings are included—not OEM Sentry. Older videos without saved camera order support Composition only. Opening the archive does not start live cameras.
- **Car modes:** the desktop panel is on the right; on a phone, expand the collapsed panel. **Show on tablet** and **Record cameras** read/change the same saved switches as the app. They are not merely web-player settings: changing them affects the vehicle and remains in effect after you close the page. Enabling a switch does not bypass power/lock, permission or low-charge conditions. If required recording permissions are missing, grant them on the tablet first.
- **Disconnect:** press **End session** or close the tab. Viewing-owned resources are released; recording or other camera consumers remain active if independently enabled.

The examples below show the shared **Tailscale (Serve/Funnel) and zrok** page on a computer and a phone. They use the current web interface in the dark theme, with **illustrated camera frames and demonstration recordings**, not footage from a vehicle. The provider label shown is Tailscale; zrok has the same controls. VDO uses its own player instead.

<details>
<summary>PC / laptop examples — Live and Recordings</summary>

**Live:** camera selection, fullscreen and the vehicle-mode switches on the right.

![Desktop camera page: live composition with camera selection and vehicle-mode switches; demonstration data](docs/screenshots/en/viewer-desktop-live.png)

**Recordings:** calendar, thumbnails and yellow/red event counts.

![Desktop recording archive with calendar and video thumbnails; demonstration data](docs/screenshots/en/viewer-desktop-archive.png)

</details>

<details>
<summary>Phone examples — Live and Recordings</summary>

The vehicle-mode panel starts collapsed. The archive scrolls vertically.

![Phone live camera page with collapsed vehicle-mode panel; demonstration data](docs/screenshots/en/viewer-phone-live.png)

![Phone recording archive with calendar and video thumbnails; demonstration data](docs/screenshots/en/viewer-phone-archive.png)

</details>

### Access, passwords and safe use

One saved access/account is kept **per provider**, not per phone/browser. Choosing a provider in the settings form does not disable the other authorized providers. However, **only one viewer session is active across all providers**: a new authenticated viewer replaces the previous one. The Extend page explains the replacement; VDO's stock player controls its own messages.

**Revoke access** affects the selected provider, not all providers. Use it to remove that account/access—not as the normal way to disconnect. To replace a saved account or locked name, revoke the old access first. For a temporary connection error, use **Retry connection** after correcting the cause.

Public routes (VDO, Funnel and zrok) require a viewer password. Use a long, unique password even if a shorter one passes format validation. The link/QR does not contain the password; share it separately and only with trusted people. A permitted Tailscale/zrok viewer can also view the Extend archive and change the two vehicle-mode switches.

Secrets are encrypted using Android Keystore and excluded from settings export/Android backup. Restoring exported settings does not replace provider authorization. External providers still handle connection traffic; public-link protection is not a promise that a sleeping, offline or low-battery vehicle can always be reached.

<details>
<summary>If the page or video does not open</summary>

| Symptom | What to check |
| --- | --- |
| Authorize is disabled | Viewing enabled; valid non-empty name; required password; zrok account token. Entering fields alone does not start authorization. |
| Tailscale approved, but not ready | MagicDNS/HTTPS, any administrator device approval, access policy, and Funnel permission if selected. Check the error in Extend; do not repeatedly revoke a valid account. |
| Serve works only with VPN | Expected: connect the viewer to the permitted tailnet. Use Funnel only if you intentionally want public access. |
| Funnel not reachable after setup | Confirm the applied mode and account capability; allow for DNS propagation. Test once without another VPN to distinguish routing from service failure. |
| Password rejected | Use the viewer password for that provider, not its account password/token. VDO passwords are case-sensitive; check phone auto-capitalization. |
| Page opens, video does not | Check camera/helper status, charge protection, hardware encoding and the vehicle connection. A loaded page does not prove live camera readiness. |
| Worked while parked nearby, unavailable later | Check whether the car lost internet when your hotspot left or Android/network went to sleep. |
| Another device was disconnected | Only one viewer is allowed across providers. Close old viewing tabs before switching devices. |

If it persists, note local time, provider, Serve/Funnel mode, VPN state and exact browser error, then export diagnostic logs. Never include passwords or account tokens in a public issue.

</details>

Thanks to the [OverDrive project](https://github.com/yash-srivastava/Overdrive-release) for these specific technical references:

- **Camera optimization:** direct GPU output to a `SurfaceControl` layer instead of `TextureView`, and sharing one camera-frame source between Motion detection and GPU recording rather than opening duplicate recording streams.
- **Tailscale Serve:** the idea of using userspace Tailscale with Serve to provide private remote access to a local web interface on the vehicle. We adapted this idea for Extend's camera viewer; this credit does not cover Funnel.

These references informed individual optimizations and the Serve approach, not the overall concept of BYD Extend or a port of OverDrive's camera system or viewing interface.

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

![Music and lighting settings](docs/screenshots/en/music.png)

#### Weather

![Weather integration settings](docs/screenshots/en/weather.png)

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

![BYD Extend AVAS audio settings](docs/screenshots/en/avas.png)

## Presets and diagnostics

In `Settings -> Logs`:

- **Export/load camera presets:** save or load a JSON file containing camera visual settings, including placement, calibration, display selection, and borders. A camera preset is not a full backup: it excludes steering/speed/distance rules, learned buttons, and audio files.
- **Import settings:** migrate from the older BYD Turn Signal app; this is separate from loading a camera preset.
- **Share / Save as:** prepare a diagnostic ZIP and share it or choose a destination. Progress shows the current stage and, when known, processed/remaining volume and percentage. Cancel leaves source logs intact.
- **Compatibility package:** collect system details for investigating model or firmware compatibility.
- **Record logcat:** continuously record system logs. This is **off by default**; turn it on before reproducing an intermittent issue and off afterward.

Continuous logcat can grow very large and is not automatically rotated away. Turning recording off retains history. Clear logs after sending the required evidence, and keep enough free storage for both source files and the ZIP. Without continuous recording, export still captures the system-log buffer available at that moment; it cannot recover overwritten history from before a reboot.

**Review files before sharing.** System logs and compatibility packages may contain identifiers, locations, and information from other apps. Share only what is needed and do not publish credentials. Weather requests send coordinates to the weather provider; update checks contact GitHub.

![BYD Extend settings and diagnostics](docs/screenshots/en/settings.png)

<details>
<summary>Screenshot: camera output settings</summary>

![General camera output settings](docs/screenshots/en/settings-camera-output.png)

</details>

## Background operation and updates

`Auto-start` controls automatic startup and recovery, not whether manually opened features may run. Keep DiLink's background-app restriction disabled for BYD Extend. Use `Shutdown` in Settings to stop the app and its runtime until you open it again.

Optional ADB recovery attempts to restore previously authorized access. It needs previously granted `WRITE_SECURE_SETTINGS`; the Wi-Fi debugging path also needs a Wi-Fi connection and any system consent required by the firmware. A saved authorization key does not mean that ADB is currently listening.

The app uses notification-listener access for runtime recovery without reading notification contents. Background operation, camera services, and continuous logs can consume power and storage while the vehicle is parked. No Android process survives a kernel reboot; recovery depends on the system and available permissions.

Check updates manually in Settings or enable automatic checks. The update dialog includes release notes and download progress, with cancellation while downloading. An optional update-hint widget requires display-over-other-apps permission. Updating normally preserves settings; do not clear app data when installing an update.

<details>
<summary>Permissions, runtime, and ADB recovery</summary>

### Permissions and runtime

![Permissions, startup, and update settings](docs/screenshots/en/settings-permissions.png)

### ADB recovery

![ADB recovery settings](docs/screenshots/en/adb-recovery.png)

</details>

## Dialogs and widgets

The examples below show the English dark-theme Preview. Version numbers, archive sizes, and progress are demonstration data. Scrollable dialog content is stitched into a single image.

<details>
<summary>ADB recovery reminder</summary>

The reminder editor previews the Wi-Fi prompt and retry action and lets you adjust the widget's appearance and position. It is a reminder for recovery, not a first-time ADB authorization dialog.

![ADB recovery reminder editor](docs/screenshots/en/adb-reminder-dialog.png)

</details>

<details>
<summary>Update-hint widget</summary>

Configure the update hint's appearance, then see how the floating notification of a new version looks. This is separate from the update download dialog.

![Update-hint appearance settings](docs/screenshots/en/update-hint-dialog.png)

![Floating update-hint widget](docs/screenshots/en/update-hint-widget.png)

</details>

<details>
<summary>Learning a steering-wheel button</summary>

Open the button-learning dialog for the desired action, then press the steering-wheel button you want to assign. The same dialog is shared by the camera and audio controls; one example illustrates it.

![Steering-wheel button-learning dialog](docs/screenshots/en/button-learning-dialog.png)

</details>

<details>
<summary>AVAS sound library</summary>

Choose an imported sound for an event and audition it before assigning it. The library shows the available files and the current selection.

![AVAS sound library](docs/screenshots/en/avas-sound-library-dialog.png)

</details>

<details>
<summary>Diagnostic archive progress</summary>

When sharing or saving logs, the progress dialog shows the total, processed, and remaining data volume and the percentage when available. One example covers both export actions; Preview uses simulated progress.

![Diagnostic archive preparation progress](docs/screenshots/en/log-export-progress-dialog.png)

</details>

<details>
<summary>Border colour picker</summary>

Choose a camera-border colour visually or enter its hexadecimal value. One example illustrates the shared colour picker.

![Camera border colour picker](docs/screenshots/en/border-colour-dialog.png)

</details>

## Debug screens

<details>
<summary>Signals, AVM, and direct camera access</summary>

### Signals

![Debug — Signals](docs/screenshots/en/debug-signals.png)

### AVM

![Debug — AVM](docs/screenshots/en/debug-avm.png)

### Direct camera access

![Debug — Direct camera access](docs/screenshots/en/debug-direct.png)

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

`gradle/gradle-daemon-jvm.properties` selects Java 17 for the Gradle daemon in both Android Studio and the command line. Install JDK 17 locally; the current Gradle 8.9 wrapper does not download a missing daemon JDK automatically.

```sh
./gradlew :app:assemblePerformance
```

On Windows, use `.\gradlew.bat :app:assemblePerformance`. The APK is copied to `build_output/byd-extend-v<version>.apk`.

The embedded camera tunnels also require Windows PowerShell, Go **1.26.10** on `PATH`,
and Android NDK **30.0.15729638-beta2** (SDK directory `ndk/30.0.15729638`). Gradle
builds all four native ABIs from pinned sources into `app/build/generated/tunnelJniLibs`;
the first build downloads the Go dependencies. See the [native build notes](app/src/main/native/tunnel/README.md).

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

[![QR code for the Support BYD app donation Jar](docs/images/support-qr.png)](https://send.monobank.ua/jar/bKFV15i9e)

</details>

The same details are available under `Support` beside the app title. Donations are optional; the app never makes a payment automatically.

## License

BYD Extend is licensed under the [GNU Affero General Public License v3.0 only](LICENSE). It is an independent project, not affiliated with or endorsed by BYD or the services mentioned here. Trademarks belong to their respective owners.

Generative AI tools are used for development, testing, diagnostic analysis, and documentation.

Use the app at your own risk. Camera images and telemetry can be incomplete, delayed, or unavailable. Keep the vehicle's stock safety systems in use and install, configure, and troubleshoot only while parked.
