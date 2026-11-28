# android-lua-flasher

Android app (Kotlin, Jetpack Compose) that installs the LUA_WLAN firmware on smartex microBOX boards
(ESP8266 + NodeMCU) over USB-OTG. Contract: [PLAN.md](PLAN.md). Work plan and status: [STEPS.md](STEPS.md).
It also sets up Teltonika routers (RUT241, RUT986, RUT901) over their web API; concept and status: [DRAFT.md](DRAFT.md).
And it services deployed M5Stack CoreS3 devices with the ProGeo MicroPython firmware (see [M5Stack](#m5stack)).
Needs Android 10 or newer.

## Build

Requirements: JDK 17+ (Android Studio's JBR works), Android SDK with platform 36, and read access to the
shared library `com.progeo:progeo-core` from [progeo-android-core](https://github.com/Progeo-Monitoring-GmbH/progeo-android-core)
(GitHub Packages): a GitHub token with `read:packages` as `gpr.user` / `gpr.key` in `~/.gradle/gradle.properties`.
To work on the library at the same time, check it out next to this repository and add
`progeo.core.dir=../progeo-android-core` to `local.properties`: the app then builds against that checkout.

```sh
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug         # onto a phone connected with USB debugging
./gradlew test                 # JVM tests of :core, :teltonika and :app
```

In VS Code: **Ctrl+Shift+B** runs *Android: Run on device* (install on the phone connected via adb,
then start the app); *Terminal → Run Task… → Android: Build APK* builds the debug APK. See `.vscode/tasks.json`.

*Android: Screenshots* (`tools/screenshots.ps1`) takes a screenshot of every view on an emulator (created if
missing) into `docs/screenshots/`, one image per page in its full height (scrolled and stitched by `tools/Stitcher.cs`). It asks for your ProGeo login (and two-factor code) in the terminal and never
stores it; the flash wizard steps come from the debug-only `DemoStateReceiver`.

The public signing keys come from `keys/firmware-signing.pub.json`; the build fails without it. Keys whose ID
starts with `mstack-` belong to the M5 release pipeline (m5-stack CI) and are accepted for M5 releases only. Debug builds
can add a local test key with `-PextraFirmwareKeys=<file>` (same JSON format), for bundles signed with
`scripts/new_signing_key.ps1` and served from a local `dist/release-site/`.

Optional test inputs:

- `-PbundleZip=<path>`: a downloaded bundle zip with its `.zip.sig` next to it; checks signature and contents.
- `-PluaInterpreter=<path to lua5.3>`: round-trips the generated device config through real Lua.

## Layout

- `core/`: plain Kotlin, no Android: ROM bootloader (SLIP, sync, flash), NodeMCU console and upload,
  install sequence, bundle/signature validation, device config generation; MicroPython raw REPL and file
  transfer, M5 config.py and release format, M5 installer. Tested against simulated boards.
- The shared library (design, login, token storage, permissions, QR scanner, Impressum) lives in its own
  repository, [progeo-android-core](https://github.com/Progeo-Monitoring-GmbH/progeo-android-core); version in `gradle/libs.versions.toml`.
- `app/`: Android: USB serial, sync with `https://api.progeo.com/firmware/`, bundle store, profiles,
  flash wizard, serial monitor, routers (Wi-Fi binding, session, setup steps, antenna mode), M5Stack (`m5/`).
- `teltonika/`: plain Kotlin, no Android: router label codes, RutOS API client, certificate pinning,
  firmware checks, configuration templates. Tested against a simulated router (`FakeRutos`).
- `keys/`: public firmware signing keys.
- `scripts/`: copies of the PC scripts from the LUA_WLAN repo, for reference only.

## M5Stack

The M5Stack CoreS3 with the AIN4-20mA modules runs the ProGeo MicroPython firmware on UIFlow2
([m5-stack](https://github.com/Progeo-Monitoring-GmbH/m5-stack), `mPy/`). The app reaches it over its native
USB port (Espressif, VID 0x303A, CDC-ACM) and the MicroPython raw REPL; no boot mode, no ROM flashing.

- **Config QR code:** the backend's device provisioning shows an M5 config QR code next to the config.py
  (detection-docker-setup, `render_m5_qr_payload`). Its text is a compact config.py whose first line is
  `# progeo:m5-config:1`. Scanning it under *Profiles → Scan QR code* stores an M5 profile (encrypted, it holds
  the device token) and opens it. *Read from M5Stack* reads the config.py of a connected M5 instead.
- **Write config.py:** the M5 profile screen writes the profile's config.py to `/flash/progeo/config.py`. If the
  M5 has a config.py for another device hash, the app asks first and keeps the old one as a profile. The firmware
  keeps interval, pulldown, poll time and thresholds the backend set in its NVS; a new config.py changes them
  only for a new device hash.
- **Firmware:** releases come from the signed channel `mstack` under the firmware base URL (published by the
  m5-stack CI, `.github/workflows/release.yml`). The app downloads the latest on sync and keeps it for offline
  use. An update copies changed files as `.new`, checks each one by SHA-256 on the M5 and only then renames them
  all, so an interrupted update leaves the old firmware running; config.py is never touched by a release.
- The app stops the firmware with Ctrl-C for the transfer and restarts the M5 at the end, also after an error.
  It never soft-resets: after Ctrl-C the firmware feeds its hardware watchdog from a timer, which a soft reset
  would stop.

Not yet tested on a real CoreS3 (the firmware itself awaits its on-device acceptance): USB detection of the
native port, transfer speed, and the watchdog behaviour during a long copy.
