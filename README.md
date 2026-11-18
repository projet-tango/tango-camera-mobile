# tango-camera-mobile

Turns a phone into the camera of the [Tango middleware](https://github.com/projet-tango/tango-middleware).
The phone films the arena and streams JPEG frames over Wi-Fi; the middleware detects the AprilTags exactly as
with a USB camera.

| | Android | iPhone / any phone |
|---|---|---|
| What | native app (this repository) | web page served by the middleware |
| Install | sideload the APK, no store, no developer account | nothing: scan the QR code |
| Finds the middleware | automatically (UDP broadcast on the LAN) | via the QR code / link |
| Screen locked / app in background | keeps streaming (foreground service) | page must stay open and visible |
| Frame rate (Pixel 9a) | 30 fps up to 1920×1080, ~50–60 fps at `fps: 60` | not measured (browser dependent) |
| After a phone reboot | restarts on its own (see [Auto-start](#auto-start-after-a-reboot)) | reopen the page (home screen shortcut) |
| Reconnects (Wi-Fi drop, middleware restart) | yes, within ~2 s | yes |

No iOS app is provided: installing one on a device requires an Apple developer account. The web page does
the same job from Safari.

## Quick start

1. **Middleware**: in the web UI, open *Camera & detection* and click **Use a phone as camera**
   (or start it with `python -m tango_mw run --camera mobile`). The *Phone as camera* card shows a
   6-digit **pairing code** and a **QR code**.
   On Windows, allow Python through the firewall on *private* networks the first time
   (ports 47800 TCP+UDP and 47801 TCP).
2. **Android**: install the APK (see below), open **Tango Camera**, allow the camera and notifications.
   The app finds the middleware by itself; type the 6-digit code (or tap *Scan QR code*) once.
   That's it: the code is remembered, and streaming resumes on its own from then on.
3. **iPhone**: scan the QR code with the Camera app, accept the self-signed certificate
   (*Show details → visit this website*), then tap **Start camera**. Add the page to the home screen
   (*Share → Add to Home Screen*) and use *Guided Access* to keep it in front.

Mount the phone above the arena, plugged into a charger, then lock the screen: streaming continues and
the phone uses far less power and heats less. Frames are sent in the sensor orientation
(landscape on almost every phone) whatever the way the phone is held: use the middleware's
`camera.rotate` / `camera.flip` if needed.

## Installing the APK

- **From GitHub**: every push builds `tango-camera.apk` (Actions → *Android APK* → artifact); pushing a
  tag `v*` attaches it to a GitHub release. Download it on the phone, open it and allow
  "install unknown apps" for the browser / file manager.
- **From a computer** (USB or wireless debugging enabled):
  ```bash
  ./gradlew installDebug          # or: adb install -r app/build/outputs/apk/release/app-release.apk
  ```

The release APK is signed with the debug key so that it installs anywhere without a keystore. That is fine
for a lab tool; set up a real signing configuration before any store upload.

## Auto-start after a reboot

The app restarts streaming by itself when it is launched, after an update, and after a reboot,
unless *Stop* was pressed. Android forbids background apps from opening the camera, so at boot:

- **Android ≤ 9**: the service starts directly.
- **Android 10+**: tap *Allow opening at boot ("display over other apps")* in the app (shown when missing);
  the app then opens itself at boot, above the lock screen, starts streaming and lets the screen turn off.
  Without it, a notification "Tap to restart the camera" appears instead.
- **Dedicated phone (most robust)**: tick *Home screen mode* and choose Tango Camera as the home app.
  Android then opens it at every boot, with nothing to grant. Untick it to get the normal launcher back.

Also tap *Disable battery optimization* (shown when needed). Some vendors (Xiaomi, Huawei, Samsung…)
add their own "autostart" / "sleeping apps" lists: allow Tango Camera there too.

## Settings

All camera settings are driven by the middleware and pushed live to the phone (no need to touch it):
`camera.width`, `camera.height`, `camera.fps` and `camera.mobile.*`:

| Key | Default | |
|---|---|---|
| `lens` | `back` | `back` / `front` |
| `jpeg_quality` | 80 | 30–100 |
| `focus` | `auto` | `infinity` locks focus (no autofocus "pumping") |
| `exposure_compensation` | 0 | steps, phone dependent |
| `torch` | false | flash always on |
| `max_exposure_ms` | empty | cap the exposure time to limit motion blur, see below |
| `port` / `https_port` | 47800 / 47801 | app + discovery / web page |
| `require_code` | true | pairing code required |

On the phone itself: the pairing code, an optional fixed server (`host:port`, empty = automatic discovery),
auto-start options, and a low-rate preview of what is sent.

## Exposure cap (motion blur)

Phones lengthen the exposure up to the frame period before raising the gain, even in good light: a Pixel 9a
stays at ~30 ms at 30 fps, and a robot at 0.5 m/s moves 15 mm during one frame. Set
`camera.mobile.max_exposure_ms` (e.g. 10) and the app:

1. lets auto-exposure settle for ~0.7 s and keeps the image brightness it reached as the target
   (`exposure_compensation` shifts that target);
2. switches to manual exposure: exposure time = the cap, ISO raised by the same factor;
3. keeps the brightness on target with a slow loop (lights switched on/off, flash, daylight).

Measured on a Pixel 9a in a dim room, 30 fps: auto = 30 ms / ISO 1866; cap 10 ms = 10 ms / ISO 7216 (the
maximum: the room is too dark, the *Light level* check says so); cap 10 ms with the flash on = 10 ms / ISO 66,
same brightness. Remove the setting to go back to the phone's auto-exposure.

Under mains-powered lights (LED, fluorescent) prefer a multiple of 10 ms (50 Hz grid) or 8.3 ms (60 Hz),
otherwise the brightness can pulse from frame to frame: the middleware's *Light flicker* check detects it.
Phones without manual sensor control ignore the setting (`exposure_mode: unsupported` in the stats). The
browser page (iPhone) cannot control the exposure.

## How it works

```
 phone ──UDP broadcast {"type":"tango_discover"} :47800──► middleware
       ◄──────────── {"type":"tango_here","port":47800} ──
       ──WebSocket ws://host:47800/ws ──────────────────►
           hello {code, device id, name}   ◄── welcome + settings
           binary JPEG frames ───────────►     settings again on every config change
           stats every 2 s {fps, camera_fps, encode_ms, kbps, exposure_ms, iso, exposure_mode,
                            brightness, battery, temperature, screen_on}
```

- The phone tries, in order: the last server reached, the server typed / scanned, then discovery.
  It retries every 1 to 5 s forever, and immediately when Wi-Fi comes back.
- A WebSocket ping every 5 s detects a dead link within about 10 s.
- One phone streams at a time. The same phone reconnecting replaces its old connection; another phone is
  refused (`busy`) until the first one has been silent for `camera.mobile.stale_after` seconds.
- Frames are dropped, not queued, when Wi-Fi is congested, so latency stays low.
- If the pairing code is changed in the middleware, connected phones are disconnected and must be
  paired again.

The protocol is documented in `tango_mw/vision/mobile.py` (middleware repository); any client that speaks it
can feed the middleware.

## Building

Requirements: JDK 17+ and the Android SDK (platform 36), or simply Android Studio.

```bash
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew lintDebug
```

Code layout (`app/src/main/java/fr/projettango/camera/`):

| File | Role |
|---|---|
| `MainActivity.kt` | status, preview, pairing (code / QR), auto-start options |
| `StreamService.kt` | foreground service: CameraX capture, YUV → JPEG, Wi-Fi/wake locks, stats |
| `Connection.kt` | discovery, WebSocket, reconnection, settings from the middleware |
| `BootReceiver.kt` | restart after reboot / update |
| `ExposureController.kt` | exposure cap: manual exposure + ISO keeping auto-exposure's brightness |
| `Prefs.kt` | persisted settings (code, server, device id) |
