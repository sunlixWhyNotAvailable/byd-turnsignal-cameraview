# BYD Extend

> Experimental open-source companion app for selected Chinese BYD vehicles running DiLink.
>
> This project is not affiliated with or endorsed by BYD. Configure and test all driving-related
> features while the vehicle is parked.

## Project status

Current source target: `1.2.0` (version code `102`), with Ukrainian, English and Simplified Chinese
interfaces, dark and light themes, and a Compose UI. Published builds are listed in the
[GitHub Releases](https://github.com/sunlixWhyNotAvailable/byd-turnsignal-cameraview/releases).

The source target and published release are separate states; a local test APK is not a publication.

## Features

- Turn-signal guard with configurable steering, delay, speed, telemetry, and cancellation safety
  checks.
- Turn-triggered side-camera overlays on the tablet or instrument cluster.
- Independent parking-camera views driven by compatible proximity and speed telemetry.
- Reverse composition, direct camera preview, calibration, fisheye correction, mirroring, crop,
  rotation, placement, scale, transparency, corner rounding, and image-quality controls.
- User-selectable Reverse panes with optional integrated front side and central camera profiles.
- Optional gear-driven front/rear selection while Reverse gear or the stock camera UI is active.
- An independent rearview-mirror widget on the tablet or a supported instrument cluster,
  with optional front-camera integration and separate source/visibility button gestures.
- A learned steering-wheel button for switching the active Reverse widget and integrated cameras
  between front and rear views.
- Optional music metadata and local weather for the stock BYD weather UI.
- Four independent AVAS exterior-audio profiles for locking, unlocking, powering off and on.
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
The learned steering-button bindings are also excluded and preserved when loading camera presets
or importing legacy settings.

Preset format v3 stores both tablet and instrument-cluster rectangles for Mirror and all four
Blind profiles. Existing v1/v2 files remain supported: their placement is applied only to the
declared display (the current display when omitted), preserving the other display. Importing
a file without Mirror leaves its settings unchanged.
Optional Mirror front integration, selected source and front calibration fields are included without
changing the v2/v3 format versions. Older files without these fields preserve the destination values.
Panorama-suppression preferences are included when present; older presets without these fields
preserve the current values, with suppression enabled by default on fresh settings.
Local saved calibration slots and temporary Mirror hiding are not part of a camera preset.
Built-in camera geometry and calibration use the approved reference baseline only for absent
values and explicit section resets; updating the application preserves existing effective settings,
including never-edited values. New cluster placements and placement resets are centered using the
corresponding factory tablet width/height. Editing or resetting one display does not change the other.

Reverse output rotation, display mode and mirroring are saved for the selected camera only.
Moving, resizing or reordering composition elements does not change RAW or corrected calibration
areas. Existing presets remain available; an update does not automatically restore earlier values.

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

In Reverse, select `Widget` and use `Select button…` below `Enhanced reverse view`. A confirmed
single short steering-wheel press assigns its Android key code without switching cameras. Cancel or Android
Back leaves the previous assignment unchanged; the square reset clears only the assignment.

Subsequent single short presses switch the existing composition and its widget together after
Android's inclusive multi-press window expires. Double presses and holds do not switch cameras
or fall back to single presses. After their release, the next press starts a new gesture: three
quick short presses are Double + Single; four are two Doubles. A known OEM long code means Hold,
which has no Reverse action; otherwise Hold is timed from the ordinary DOWN. Timer/native feedback
for the same hold is deduplicated. Learning stores the base button code and consumes its release
without executing a camera action.
Enhanced reverse, the widget and at least one front-camera integration must be
enabled, and the composition must already be active. Existing per-camera visibility/integration
rules apply; the button does not open cameras, change calibration or add automatic switching in D.
Calibration and other foreground app tabs do not redirect the button to a background composition.

The separate global `Switch by gear` setting is off by default. Its row appears immediately below
front-camera integration for Rear, Left and Right, and below `Enhanced reverse view` for Background
and Widget. It is available only while at least one camera is integrated; disabling every integration
does not erase the saved preference. Unavailable switches use a neutral track and dimmed thumb/text
while retaining their saved ON/OFF position, in both themes. R activation never waits for the stock
camera UI. With gear selection enabled, the session remains active while R or the stock camera UI is active. Entering D selects
integrated front cameras, and entering R selects rear cameras. N and P retain the selected view
during a session, or select Front on a cold opening. Repeated telemetry does not undo manual
selection. Nonintegrated panes keep their rear view in either mode. Automatic selection does not
require the selector widget to be visible.

All assigned-button gestures, repeats and releases are consumed immediately, including when no
eligible composition is active. Only our single-press action is delayed; consumed events are not
forwarded or replayed. Other eligible Accessibility key filters receive their own event copies,
but arbitrary apps and downstream stock handlers are not guaranteed delivery.
Reset the assignment to restore normal handling. Explicit app Shutdown
ends interception until the app is reopened. The assignment survives app restarts.

This feature uses the app's existing Accessibility service. If the key filter is unavailable,
learning reports that condition instead of saving a simulated assignment. Some BYD firmware
handles keys before Android Accessibility receives them; support for every physical button is
not guaranteed.

## Rearview mirror and UI preferences

Front-camera integration is off by default. When enabled, the rear/front selector remembers its
choice; each source has independent calibration and a local preset, with an explicit rear-to-front
copy. Turning integration off returns to the rear source without erasing front settings or bindings.
The sources share one widget's placement, display target and border.

Two independent learned-button rows switch source or show/hide the widget using Single, Hold or
Double. Reset clears only that row's button, retaining its gesture. Duplicate assignments are
allowed: every eligible matching action runs, and simultaneous Mirror effects use one state update.
Source switching requires Mirror and front integration; visibility requires Mirror enabled and never
enables it. Switching a hidden source does not reveal the widget, and normal app/panorama gates remain.
Confirmed base/long pairs are `305/306` (star), `304/312` (microphone), `88/303` (previous track),
`87/302` (next track). Panorama `294` and wheel `353` have no assumed native-long code and use timed Hold.

Enable the rearview mirror and grant Android overlay permission when prompted. Its independent
placement, calibration, preset and border settings do not modify Reverse or Blind profiles.
Drag the external widget to move it; touch and hold to hide it until BYD Extend is opened again.
It is hidden while BYD Extend is open. The default-enabled `Do not show while panorama is open`
option also hides tablet output during the stock camera UI, not cluster output; switching it off does not disable
app-foreground or long-press hiding. A hidden widget leaves no input window blocking other apps,
while taps inside the visible widget do not pass through.

Blind cameras have the same default-enabled panorama option independently for the rear and front
groups, directly below each group's enable switch. Panorama opening/error status appears next
to the Reverse camera-section title; direct-camera readiness remains a separate status.

Global Auto-start controls autonomous startup and recovery; turning it off still permits manual
Mirror use. An unavailable instrument cluster does not redirect the widget to the tablet.
Blind and Mirror editors support bounded independent width/height and drag/resize, with 0.1%
placement precision. Tab/subsection scroll positions are remembered within the application process
and cleared on explicit Shutdown or process death.

Fresh installations start in English regardless of the tablet language. Existing effective
language choices are preserved, and the Chinese selector label remains `中文` in every language.

Switches retain their ON/OFF appearance while an operation is pending, without a yellow or centered
intermediate state; normal toggle and press feedback remain. Mirror uses the standard compact
enable switch. The six text navigation tabs have equal widths; Debug remains a compact square,
bug-icon-only button with a localized accessibility label.

## BYD integrations and exterior sounds

BYD integrations uses the same category layout as Settings: Turn signals, Music and lighting,
Weather, and AVAS (external speaker). Existing integration settings are preserved.

AVAS has separate Lock, Unlock, Power off and Power on profiles. Each starts disabled, with a
protected one-second Test sound, random playback off and volume15%. Existing settings and
selections, including an empty selection, are preserved on upgrade. Add audio files through the
system document picker; supported audio is copied privately and prepared before playback.
Each profile has its own file list and selection. Imports preserve the selected file and do not
overwrite duplicates. Audio libraries are not included in camera-preset JSON files.

The profile header toggles its automation. Volume is independent per profile, from0% (silent)
to100%, with the slider and percentage below its title. Start plays the selected ready
file through the exterior route even when that profile's automatic switch is off. Stop cancels
only the same profile's manual playback or queued request, not automatic event sounds.
Automatic random playback excludes Test and skips an event when no imported file is ready.
Nearby ordinary events
play sequentially; a ready enabled Power-off sound replaces its associated automatic Unlock.
Initial, repeated and unknown vehicle states do not trigger playback.

The file list stays open when selecting a sound and shows its duration. Long names are shortened
visually without changing the stored filename. Imported files can be deleted; deleting the
selected file selects Test. Only private copies are removed, never the original document.
The note button plays that exact row through the OEM driver-navigation speaker, not ordinary
cabin media, regardless of selection, random mode or automation enablement. It becomes Stop
while playing; another note replaces it. Closing the list, leaving the screen/backgrounding,
process death or deleting that file stops only this listening session. Start and automatic
events take precedence and use the exterior speaker; a note cannot interrupt or queue behind
exterior playback. Stopped notes do not resume. Both routes apply the profile's PCM gain and
restore their saved route/volume state without a media-channel fallback.

The existing shell helper owns playback so losing the application process does not itself
destroy the player. A helper restart starts from current telemetry without replaying old events.
Explicit Shutdown stops playback and automatic recovery until BYD Extend is opened again.
The exterior route is based on the owner's confirmed firmware23 probe. The combined Production
path and recovery after kernel reboot still require vehicle validation; compatibility with other
firmware is not established. Configure and audition at low volume while parked. AVAS does not
replace the vehicle's stock pedestrian-warning sounds or silently fall back to a cabin speaker.

## Sharing diagnostics

Settings can share logs or create a compatibility package. Compatibility export displays the
current phase, file, file count, and transferred bytes, and can be cancelled without sharing a
partial archive. It supports individual files up to 2 GiB and a total source payload up to 4 GiB;
missing or inaccessible optional files are reported in the package instead of stopping the export.
Individual text captures remain limited to 16 MiB. Data is streamed rather than held in RAM;
temporary files and ZIP overhead can require additional disk space beyond the source-data budget.

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
