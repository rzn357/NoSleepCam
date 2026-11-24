<div align="center">

<img src="docs/logo.svg" alt="NoSleepCam logo" width="120" height="120">

# NoSleepCam

**An Android camera that never falls asleep.**<br>
It opens the camera and keeps it live for as long as the app is open — and starts in a fraction of a second.

[![CI](https://github.com/rzn357/NoSleepCam/actions/workflows/ci.yml/badge.svg)](https://github.com/rzn357/NoSleepCam/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/github/license/rzn357/NoSleepCam?color=blue)](LICENSE)
![Platform](https://img.shields.io/badge/Android-7.0%2B_(API_24)-3DDC84?logo=android&logoColor=white)
![Language](https://img.shields.io/badge/Kotlin-100%25-7F52FF?logo=kotlin&logoColor=white)

<br>

<img src="docs/screenshots/demo.webp" alt="NoSleepCam demo: zoom, flash, tap to focus, brightness, settings and photo capture" width="300">

<sub>Zoom · flash · tap to focus · brightness · settings · photo on double tap</sub>

</div>

<br>

## Why this app exists

The author uses a phone as a **digital microscope** — for example, to repair microelectronics: soldering SMD parts, inspecting
traces and chips under magnification. For that the camera image must be live **all the time**.

The stock camera app on the phone, and every other camera app the author could find on Google Play, do not keep the camera
active: after a while the camera "falls asleep" and the preview stops, so the picture has to be woken up again in the middle of
the work. NoSleepCam does the opposite — the screen stays on, the camera stays open for as long as the app is open, and if
the camera is taken away (another app, a driver error) the app reconnects on its own.

Everything else — a minimal interface, a Huawei-style zoom slider with up to 10x, tap to focus, brightness, the real frame rate,
flash as a work light, full-size photos on a double tap — serves that one purpose.

| | |
|---|---|
| 🌙 **Never sleeps** | the screen stays on and the camera reconnects on its own if it is lost |
| ⚡ **Fast** | camera opened ≈ 100 ms after process start, `TotalTime` ≈ 230–450 ms on a Honor 10 |
| 🎯 **Minimal** | just the camera, a zoom slider, the real frame rate, flash and settings |
| 🪶 **Lean** | no AndroidX, no Material — only framework widgets and the Kotlin stdlib |

## Contents

- [Why this app exists](#why-this-app-exists)
- [Screenshots](#screenshots)
- [Features](#features)
- [Build and install](#build-and-install)
- [Development](#development)
- [Architecture](#architecture)
- [Fast startup](#fast-startup)
- [Design decisions](#design-decisions)
- [Tap to focus on the Honor 10](#tap-to-focus-on-the-honor-10)
- [Project structure](#project-structure)
- [Known limitations](#known-limitations)
- [License](#license)

## Screenshots

<table>
  <tr>
    <td align="center" width="33%">
      <img src="docs/screenshots/main.webp" alt="Main screen" width="230"><br>
      <sub><b>Always-on camera</b><br>frame rate, flash, settings and the zoom slider</sub>
    </td>
    <td align="center" width="33%">
      <img src="docs/screenshots/zoom.webp" alt="Zoom scale" width="230"><br>
      <sub><b>Zoom scale</b><br>logarithmic, up to 10x on the Honor 10</sub>
    </td>
    <td align="center" width="33%">
      <img src="docs/screenshots/zoomed.webp" alt="Zoomed to 3.4x" width="230"><br>
      <sub><b>Zoomed in</b><br>the ring shows the current value</sub>
    </td>
  </tr>
  <tr>
    <td align="center" width="33%">
      <img src="docs/screenshots/focus.webp" alt="Tap to focus" width="230"><br>
      <sub><b>Tap to focus</b><br>four-corner indicator and the brightness “sun”</sub>
    </td>
    <td align="center" width="33%">
      <img src="docs/screenshots/brightness.webp" alt="Exposure compensation" width="230"><br>
      <sub><b>Brightness</b><br>drag the sun for exposure compensation</sub>
    </td>
    <td align="center" width="33%">
      <img src="docs/screenshots/settings.webp" alt="Settings" width="230"><br>
      <sub><b>Settings</b><br>resolution, high-speed mode, camera, language</sub>
    </td>
  </tr>
</table>

## Features

### Always on

- **The camera is always on.** The screen does not turn off while the app is open. If the camera is lost (taken by another
  app, a driver error), the app reconnects on its own. When the app is minimized the camera is released, and when you come
  back it is turned on again.
- **Minimum of elements on screen:** the camera (fitted with black bars, without distortion, any orientation), a zoom slider
  on the right and, in the top right corner, the **actual frame rate** badge, the flash and the gear (settings).
  In **landscape** the system bars are hidden (the camera fills the whole screen, a swipe from the edge shows them
  temporarily), and the zoom slider always sits below the icon row — they never overlap.
  The app computes the frame rate itself from the sensor timestamps once a second; when the camera delivers no frames the
  badge is hidden.

### Zoom

Zoom goes up to the maximum. The slider is modelled on the Huawei camera's slider (all sizes and boundaries were measured on
the Honor 10):

- **At rest** — a ring with the current value and a short dotted tail (three faint dots and a bright end one). The ring sits
  in a slot at the frame centre while the zoom is below **2x**, and 40 dp higher from 2x on; the tail is always on the
  other side (up at low zoom, down at high zoom), and the bright dot is where the ring will be after switching.
- **Touches.** The scale is opened **only by a touch on the ring** (±35 dp horizontally, ±16 dp vertically). A tap on the
  bright end dot switches the zoom in a jump (1x ↔ 2x). Any other touch, including right next to the ring, is **focus**,
  not zoom (otherwise focus would not work next to the slider).
- **Scale** (while a finger is on the ring and for ≈1 s after release): a dotted line of ≈240 dp symmetric around the
  frame centre, labels "10x" at the top and "1x" at the bottom, a big number in the middle of the screen ("2.0x"), and the
  ring with a dot follows the finger 1:1 (relatively). The other buttons (fps, flash, gear) are hidden meanwhile. After
  release the ring shows the value again, and after ≈1 s the scale collapses (0.4 s) and the ring returns to its slot.
  The scale is logarithmic.
- **Maximum** — `availableMaxDigitalZoom` from Camera2. The exception is the **Huawei/Honor back camera**: the standard
  API says 6x, while the manufacturer's own camera app gives 10x, so we offer 10x (beyond that the Honor 10 driver hangs).
  If the camera silently clamps the requested zoom, the maximum is lowered.

### Flash

Torch mode: lit continuously. The icon is always visible; for cameras without a flash it is dimmed, and tapping it
explains why.

### Image quality and settings

- **Image quality** (gear). A list of the resolutions the camera really supports, with the highest frame rate for each.
  The choice is remembered separately for each camera. If there are several cameras, the camera picker is there too.
- **High-speed mode** (a switch; only where the camera declares `CONSTRAINED_HIGH_SPEED_VIDEO`; on the Honor 10 — the back
  camera, 1920×1080, measured ≈119 fps). No flash or tap to focus; in low light the image is dark. Not persisted between
  launches.

### Focus and brightness

- **Autofocus.** Continuous + tap to focus (with exposure metering at that point). The indicator is a square of four corner
  brackets, as in the Huawei camera: white — searching, green — in focus, red — failed.
- **Brightness.** After a touch a "sun" appears to the right of the square. Drag it up/down: the square hides, a dotted
  scale appears, and on the left the exposure compensation value (`+1.0`, `−1.5`). The range and step are taken from the
  camera (Honor 10: ±2 EV in 0.5 steps). The brightness stays after release, and a new touch resets it. On cameras without
  autofocus (the front one) a touch only meters the exposure and gives brightness adjustment. Unavailable in high-speed mode.

### Photo on double tap

A double tap on the screen takes a full-size photo (JPEG, on the Honor 10 — 4608×3456, with the same aspect ratio as the
preview; quality 95) with the current zoom, brightness and flash.

- As always, the first tap of the double tap starts focusing, so the photo waits for its result and is taken with the lens
  locked; the brightness reset of a regular tap is postponed for the duration of the double-tap window, so the photo has
  the brightness you set.
- The photo is saved to the gallery (`DCIM/Camera/IMG_YYYYMMDD_HHMMSS.jpg`, as in the Huawei camera) without any
  permissions on Android 10+ (on Android 9 and older the app asks once for the storage permission).
- **Animation:** the screen briefly "blinks" (shutter), the frozen image shrinks, rounds off and flies into a small circle
  (40 dp) in the bottom-left corner, where the last photo stays (it is kept between launches); tapping the circle opens
  the photo in the gallery.
- In high-speed mode photos are unavailable (such a session has no JPEG stream).

### Permissions and languages

- **No network:** the app declares no `INTERNET` permission and never uses the network.
- **Camera permission:** requested on the first launch; if denied — a panel with the "Grant access" and "Open settings" buttons.
- **Languages:** English (`values/strings.xml`, the default) and Ukrainian (`values-uk/strings.xml`). Until the user
  chooses, the app uses the phone's system language if it is one of these two, and English otherwise. In the settings
  (gear → "Language") the user can pick English or Ukrainian; the choice is stored in the app's preferences and applied by
  `AppLanguage` (the base context is wrapped in `attachBaseContext`, the activity is recreated when the choice changes).
  All code comments and this README are in English.

## Build and install

```bash
./gradlew assembleDebug
./gradlew installRelease      # Release is signed with the debug key (a personal app), so it installs without setting up keys
```

The minimum Android version is 7.0 (API 24). There are no AndroidX/Material dependencies — only framework widgets and the
Kotlin stdlib.

> [!TIP]
> Evaluate speed on a **Release** build: Debug is always much slower.

## Development

Code quality is checked automatically. The fast checks (formatting and static analysis) also run in a git hook before every
commit; everything, including the tests, runs in CI:

| Tool | What it checks | Command | Runs automatically |
|---|---|---|---|
| [ktlint](https://pinterest.github.io/ktlint/) via [Spotless](https://github.com/diffplug/spotless) | formatting (`ktlint_official` style, rules in `.editorconfig`) | `./gradlew spotlessCheck` · fix: `./gradlew spotlessApply` | pre-commit hook, CI |
| [detekt](https://detekt.dev/) | static analysis (config in `config/detekt/detekt.yml`) | `./gradlew detekt` | pre-commit hook, CI |
| Android Lint | Android-specific problems; **any warning fails the build** | `./gradlew lintDebug` | CI |
| JUnit 4 | unit tests of the pure logic (geometry, camera rules, photo maths, control rules, …) | `./gradlew testDebugUnitTest` | CI only — **not** in the hook |

**Git hook.** `.githooks/pre-commit` runs ktlint and detekt (only these two, so a commit stays fast; no tests) whenever Kotlin
or Gradle files are part of a commit. Enable it once per clone:

```bash
git config core.hooksPath .githooks
```

**CI.** [GitHub Actions](.github/workflows/ci.yml) runs formatting, detekt, Lint, the unit tests and the debug and release
(R8) builds on every push and pull request, and uploads the reports and the debug APK. Dependabot keeps the Gradle
dependencies and the actions up to date.

## Architecture

The camera layer (`camera/`) knows nothing about views, strings or preferences: it reports data (`CameraCapabilities`,
`CameraStatus`, `CameraDescriptor`) to a `CameraListener`, and the UI (`ui/`, `MainActivity`) turns it into text and pictures.
The camera controller is created by `NoSleepCamApp` and handed to the activity (manual dependency injection, no singleton).

### Three threads

```mermaid
flowchart LR
    subgraph main["Main thread"]
        UI["MainActivity<br/>CameraScreenState → render()"]
    end

    subgraph cam["Camera thread (HandlerThread)"]
        CC["CameraController<br/>reconcile()"]
        parts["TapFocus · StillCapture · RequestFactory<br/>CameraCatalog · FrameRateReporter"]
        C2[("Camera2")]
    end

    subgraph save["Photo-saving thread (single executor)"]
        PS["PhotoSaver<br/>JPEG → MediaStore"]
    end

    UI -- "setZoom / setFlash / setResolution …<br/>(remember the wish, post to the camera thread)" --> CC
    CC <--> parts
    CC <--> C2
    CC -- "CameraListener events<br/>(re-posted to the main thread)" --> UI
    parts -- "JPEG bytes" --> PS
    PS -- "onPhotoSaved / onPhotoFailed<br/>(re-posted to the main thread)" --> UI
```

Every Camera2 call and callback lives on the camera thread, so the order of events is the order the camera produced them and
no locks are needed there. The main thread never touches the camera; writing the JPEG to the gallery is slow, so it is moved to
its own thread and cannot stall the preview.

### Desired state → reconcile → actual state

```mermaid
flowchart LR
    want["<b>Desired state</b><br/>wantActive · wantCameraId · wantResolution<br/>wantFlash · wantEv<br/><i>@Volatile, written from any thread</i>"]
    rec{{"<b>reconcile()</b><br/>camera thread"}}
    have["<b>Actual state</b><br/>device · session · surface · spec<br/><i>camera thread only</i>"]

    want -- "a setter stores the wish<br/>and posts reconcile()" --> rec
    rec -- "open · close · createSession<br/>· applyRepeating" --> have
    have -- "onOpened · onDisconnected · onError<br/>onConfigured · surface available" --> rec
    rec -. "camera lost → retry with backoff<br/>(1 s, 2 s, 4 s … up to 8 s)" .-> rec
```

The setters never open or close anything themselves: they store what is wanted and ask `reconcile()` to make reality match.
`reconcile()` is idempotent and is also called from every camera callback, so it does not matter in which order the user
rotates the phone, switches the camera, minimizes the app or loses the camera to another app — the next pass converges on the
desired state. This is what keeps the camera "never asleep".

### Layers

- **Camera layer.** `CameraController` owns the device, the session and the desired state, all on one camera thread. The rest
  runs on the same thread and gets the running camera as an `ActiveCamera`: `TapFocus` (regions, AUTO-mode switch, triggers),
  `StillCapture` (the JPEG stream and the hand-over to the gallery), `RequestFactory` (building requests), `CameraCatalog`
  (the cameras and their specs), `ZoomControl`, `HighSpeedPolicy` and `FrameRateReporter`. `CameraSpec` is immutable: limits found
  while running live in the controller.
- **Pure logic, unit-tested.** Everything that needs no device: `FocusRules` (what a tap does and the per-frame focus state
  machine, including the Honor 10 workaround), `ZoomControl`, `HighSpeedPolicy`, `CameraSpecRules`, `SensorGeometry`,
  `FrameRateMeter`, `PhotoMath`, `ControlRules`.
- **Screen state.** `MainActivity` holds one immutable `CameraScreenState`. An event replaces it, and `render()` applies the
  difference to the camera, the overlay and the settings panel — one-way data flow without a ViewModel or a library.

## Fast startup

- **Camera warm-up.** `Application.onCreate` immediately (on the camera thread) starts opening the camera, in parallel with
  the UI creation. The controller is shared by the whole process. Without the camera permission the warm-up does not
  start. The warm-up also picks up the saved resolution, so the first session starts with the right one right away.
- **The camera is opened before its characteristics are read** (`openCamera()` → then `getCameraCharacteristics`), and the
  full list of cameras for the UI is assembled **after** the frame stream has started — session callbacks arrive on the same thread.
- **Staged UI.** At startup there is only the camera. The slider, icons and status (`CameraOverlay`) are added after the
  first frame (or after 1.5 s), and the settings panel only on the first tap on the gear.
- **A black splash and window**, no AppCompat/Material, black system bars.

Measurements on the Honor 10 (COL-L29, Android 10), Release, cold start (`am start -W`, `TotalTime`):

| | Honor 10, release |
|---|---|
| camera opened (from process start) | 66–116 ms |
| `TotalTime` | 222–640 ms (series of 5–7 launches; depends on the phone's load, typically ≈230–450 ms) |
| first camera frame | 556–1129 ms |
| APK size (release) | ≈126 KB |

What remains: after the camera is opened, the Honor 10 driver delivers the first frame only after ≈0.5–1.1 s — this is
hardware latency, and it can only be hidden by opening early (which is what is done).

**Startup timestamps:** `./gradlew installRelease -PstartupTrace=true`, then `adb logcat -s NSC-Startup` (ms since process
start), `NSC-Fps` (measured vs. declared frame rate), `NSC-Req` (camera requests). In a normal build they are removed by R8.

## Design decisions

The app deliberately does not use the "modern Android stack" (Compose, ViewModel, Hilt, Coroutines/Flow, CameraX). Every
choice below follows from one requirement: **the camera must be live as early as possible and must never be interrupted.**
The numbers are measured on this app (Release build, Honor 10 — see [Fast startup](#fast-startup)); no twin build with
Jetpack was made, so the comparisons are by architecture, not by stopwatch.

| Decision | Instead of | Why | Evidence |
|---|---|---|---|
| Framework views only, no AndroidX/Material | Compose, AppCompat, Material | nothing but opening the camera sits on the cold-start path | 0 runtime dependencies, APK ≈126 KB (`classes.dex` ≈110 KB) |
| One shared `CameraController` created in `Application` | ViewModel + Hilt | the camera warm-up starts before any Activity exists | camera opened 66–116 ms after process start |
| One `HandlerThread` and plain callbacks | Coroutines / Flow | Camera2 is callback-based and already delivers everything in order on one thread | no dispatcher hops between a frame and its handler |
| Camera2 | CameraX | per-request control of what the Honor 10 driver needs | tap focus, ≈119 fps high-speed session, 10x zoom override |
| One Activity with `configChanges` | recreate on rotation | rotating must not close the camera | a reopen costs ≈0.5–1.1 s to the first frame |

### Why no Jetpack

- **Cold start is class loading.** Everything that is initialised before the first frame delays the camera. A framework
  `Activity` with a `TextureView` and a few `Canvas`-drawn views (`ZoomSlider`, `FocusIndicatorView`, …) needs none of
  Compose's runtime, AppCompat's theme inflation or Material's styles. Result: 27 Kotlin files, ≈5.3k lines, a 126 KB APK.
- **Each library's job is already done another way.** ViewModel survives rotation — here the activity is not recreated and the
  controller lives in `Application`. Hilt wires an object graph — here the graph is one object. Flow models streams — here
  the only stream is camera callbacks on a single thread, whose ordering the code relies on (the camera list for the UI is
  assembled *after* the frame stream starts because both run on the same thread).
- **This is not "no architecture".** The camera layer reports plain data to a `CameraListener` and knows nothing about views
  or strings; the pure logic (`SensorGeometry`, `FrameRateMeter`, `CameraSpecRules`, `ControlRules`, `PhotoMath`) is separated
  and covered by 118 unit tests; ktlint, detekt and Android Lint (warnings are errors) gate every change.
- **When I would choose Jetpack:** a multi-screen app, a team, or anything where a cold start of a few hundred milliseconds
  is not the product. Here it is.

### Why Camera2, not CameraX

- **The camera is opened first, on its own thread, before the UI exists** (`Application.onCreate` → `openCamera()` →
  only then `getCameraCharacteristics`). CameraX binds to a `LifecycleOwner` and runs its own initialisation and availability
  checks first; that is the wrong order for a warm-up.
- **The Honor 10 needs request-level control that CameraX hides.** Tap focus works only after switching to
  `CONTROL_AF_MODE_AUTO` for the duration of the touch (see [below](#tap-to-focus-on-the-honor-10)); the real frame rate is
  computed from per-frame sensor timestamps; the 119 fps mode is a `createConstrainedHighSpeedCaptureSession`; the Huawei
  back camera reports 6x zoom while 10x works. Through CameraX all of these would go via `Camera2Interop` — Camera2 anyway,
  plus a second layer to carry.
- **The price:** `CameraController` (≈800 lines with comments) is still the biggest file — the open/session/reconnect state
  machine that CameraX would otherwise own. It is isolated behind a listener interface, tap focus, photos and request building
  are separate classes, and the rules that do not need a device are extracted and tested.

### Why one Activity with `configChanges`

- **A rotation must not touch the camera.** `MainActivity.onStop` releases the camera, so a recreated activity would close it
  and open it again, and the first frame after an open takes ≈0.5–1.1 s on this phone: a black preview on every rotation.
  With `configChanges="screenSize|orientation|uiMode|screenLayout|smallestScreenSize|density"` the activity survives;
  `onConfigurationChanged` only re-applies the system bars and the preview view refits the frame.
- **One screen means no navigation.** Settings is a panel in the same window (`SettingsPanel`, built on the first tap on the
  gear), so there is no back stack, no Fragments and no second Activity to start. `launchMode="singleTop"` keeps one instance.
- **The one deliberate recreate** is switching the language (`recreate()`): resources must be re-read, and the camera reopening
  once on an explicit user action is acceptable.

## Tap to focus on the Honor 10

In `CONTROL_AF_MODE_CONTINUOUS_PICTURE` mode the Honor 10 driver on an `AF_TRIGGER_START` trigger only "locks" the current
lens position: the state goes `INACTIVE → PASSIVE_FOCUSED → PASSIVE_SCAN → FOCUSED_LOCKED` in ~4 frames, while
`LENS_FOCUS_DISTANCE` does not change — i.e. tap focus does nothing, even though it reports success (the camera accepts the
region). So for the duration of a touch the app switches to `CONTROL_AF_MODE_AUTO` (where the same trigger starts a real
`ACTIVE_SCAN`), and after a short hold returns to continuous mode with the same region; the lens stays in place. Verified on
the Honor 10: different points give different, reproducible lens distances, and the sharpness of the chosen area grows ≈2.2 times.
Diagnostics: a build with `-PstartupTrace=true`, `adb logcat -s NSC-Req` (AF state, region in the response, lens distance).
The decisions (the tap plan, the AF mode of a request, the per-frame state machine) are in `FocusRules` and covered by unit tests.

## Project structure

| File | Purpose |
|---|---|
| `NoSleepCamApp.kt` | `Application`: creates the camera controller and warms the camera up before the UI is created |
| `MainActivity.kt` | the single screen: lifecycle, permission, events → `CameraScreenState` → `render()` |
| `ControlRules.kt` | what a tap on the flash or on the photo gesture should do (pure, tested) |
| `camera/CameraController.kt` | the Camera2 state machine: opening, the session, the desired state, reconnection |
| `camera/TapFocus.kt`, `camera/FocusRules.kt` | tap to focus: regions, AUTO-mode switch, triggers; the pure decisions and the per-frame state machine (tested) |
| `camera/StillCapture.kt`, `camera/RequestFactory.kt` | photos (JPEG stream, gallery hand-over); building preview, still and high-speed requests |
| `camera/CameraCatalog.kt`, `camera/ZoomControl.kt`, `camera/HighSpeedPolicy.kt`, `camera/FrameRateReporter.kt` | the cameras and their cached specs; zoom limits; high-speed rejections; the measured frame rate |
| `camera/CameraListener.kt`, `camera/ActiveCamera.kt` | what the camera reports to the UI; the running camera and what the parts need from the controller |
| `camera/CameraSpec.kt`, `camera/CameraSpecReader.kt`, `camera/CameraSpecRules.kt` | what a camera can do: read from `CameraCharacteristics`, and the pure rules built on it (AF mode, sizes, frame rates, zoom limit) |
| `camera/SensorGeometry.kt`, `camera/FrameRateMeter.kt` | zoom crop, visible area and tap-focus regions in sensor space; frame rate from sensor timestamps |
| `camera/CameraModels.kt` | data passed to the UI: resolutions, capabilities, camera descriptors, statuses |
| `ui/preview/CameraPreviewView.kt` | `TextureView`, fitting and rotating the frame, touch |
| `ui/preview/FocusIndicatorView.kt` | focus square, brightness "sun", dotted scale and EV value |
| `ui/CameraScreenState.kt` | everything the screen shows as one immutable value, with the tested transitions |
| `camera/ExposureCompensation.kt` | "sun" position ↔ EV index and label |
| `camera/PhotoSaver.kt`, `camera/PhotoMath.kt` | writing to the gallery (MediaStore) and the thumbnail; JPEG orientation, size selection, file name |
| `ui/ZoomSlider.kt`, `ui/ZoomZones.kt` | the zoom slider (drawn on a `Canvas`) and its touch zones |
| `ui/CameraOverlay.kt`, `ui/SettingsPanel.kt`, `ui/CameraTexts.kt` | controls over the camera; the settings panel; user-facing texts for camera names and statuses |
| `ui/CaptureAnimationView.kt`, `ui/ThumbnailView.kt` | capture animation (the "blink" and the flight into the circle); the circle with the last photo |
| `Prefs.kt`, `AppLanguage.kt`, `StartupTrace.kt`, `SystemBarInsets.kt`, `BackInterception.kt`, `NumberFormat.kt`, `LogTag.kt` | settings; app language (English / Ukrainian); startup diagnostics; system bars (insets, full-screen landscape); the Android 13+ back gesture; locale-aware number formatting; the log tag |

## Known limitations

- The Android emulator does not apply zoom and has no autofocus — these features are verified only on a real phone.
- Tap to focus works only where the camera supports AF regions.
- A photo adds a second (JPEG) stream to the session. If the device rejects such a combination for the chosen resolution,
  the preview works without it, and a double tap shows "Photos are unavailable for this resolution".
- The capture animation is modelled on the description and final look of the Huawei camera (a 40 dp circle in the bottom-left
  corner): on this phone its duration (<0.3 s) could not be measured, so the timings (≈0.5 s in total) were chosen by eye.
  For debugging, a build with `-PstartupTrace=true` slows it down: `adb shell am start -n com.exciteddev.nosleepcam/.MainActivity --ef nsc_slow 10`.
- Landscape was verified on the Honor 10 with a forced orientation (both directions). Not verified on Android 15+ (insets
  from the system bars in portrait).

## License

[MIT](LICENSE) © 2026 ExcitedDev
