# android-lua-flasher

Android app (Kotlin, Jetpack Compose) that installs the LUA_WLAN firmware on smartex microBOX boards
(ESP8266 + NodeMCU) over USB-OTG. Contract: [PLAN.md](PLAN.md). Work plan and status: [STEPS.md](STEPS.md).

## Build

Requirements: JDK 17+ (Android Studio's JBR works), Android SDK with platform 36.

```sh
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug         # onto a phone connected with USB debugging
./gradlew test                 # JVM tests of :core, :progeo-core and :app
```

In VS Code: **Ctrl+Shift+B** runs *Android: Run on device* (install on the phone connected via adb,
then start the app); *Terminal → Run Task… → Android: Build APK* builds the debug APK. See `.vscode/tasks.json`.

The public signing keys come from `keys/firmware-signing.pub.json`; the build fails without it. Debug builds
can add a local test key with `-PextraFirmwareKeys=<file>` (same JSON format), for bundles signed with
`scripts/new_signing_key.ps1` and served from a local `dist/release-site/`.

Optional test inputs:

- `-PbundleZip=<path>`: a downloaded bundle zip with its `.zip.sig` next to it; checks signature and contents.
- `-PluaInterpreter=<path to lua5.3>`: round-trips the generated device config through real Lua.

## Layout

- `core/`: plain Kotlin, no Android: ROM bootloader (SLIP, sync, flash), NodeMCU console and upload,
  install sequence, bundle/signature validation, device config generation. Tested against simulated boards.
- `progeo-core/`: Android library shared with the ProGeo spin-off apps: corporate design, login
  (password or QR login code), token storage, permissions, QR scanner, Impressum. See its [README](progeo-core/README.md).
- `app/`: Android: USB serial, sync with `https://api.progeo.com/firmware/`, bundle store, profiles,
  flash wizard, serial monitor.
- `keys/`: public firmware signing keys.
- `scripts/`: copies of the PC scripts from the LUA_WLAN repo, for reference only.
