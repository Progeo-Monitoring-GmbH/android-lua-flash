# progeo-core

What every ProGeo Android app shares, so a spin-off app only builds its own features:

| Package | What |
|---|---|
| `ui.theme` | `ProGeoTheme`: the portal's corporate design for Material 3 (navy `#0b3659`, orange `#eb633b`, warm-gray panels, pill buttons, Plus Jakarta Sans). `ProGeoColors`, `StatusColors`. |
| `ui` | `BrandHeader` (logo + app name), `BrandPanel`, `CompanyFooter`, `MessageCard`, `Section`, clipboard helpers. |
| `ui.login` | `AuthGate` (the app behind the login), `LoginScreen` (password, 2FA, QR login code). |
| `auth` | `BackendClient` (JWT with refresh and rotation, login, logout), `EncryptedTokenStore`, `LoginCode`, `ModulePermissions`. |
| `qr` | `CodeScanDialog` / `CodeScanner`: free scanner (CameraX + ZXing), no Google Play services. QR, Data Matrix, Code 128. |
| `permission` | `PermissionGate`: asks for a runtime permission (camera, …) with rationale and the way to the settings. |
| `legal` | `ImpressumScreen`, `Imprint`, `Company`, open-source notices. |
| `security` | `SecretCipher`: AES-GCM with a non-exportable Android Keystore key. |

The module knows nothing about any app. It lives in this repository for now; when the second app
starts, move it into its own repository and publish it (e.g. to a private Maven repository) or
include it as a Git submodule / composite build.

## Using it in an app

```kotlin
// settings.gradle.kts: include(":progeo-core")   build.gradle.kts: implementation(project(":progeo-core"))

val backend = BackendClient(
    baseUrl = { "https://api.progeo.com/" },
    tokens = EncryptedTokenStore(File(context.noBackupFilesDir, "backend-tokens.bin"), SecretCipher("backend-tokens")),
    base = OkHttpClient(),
)

setContent {
    ProGeoTheme {
        AuthGate(
            backend = backend,
            appName = "My App",
            appVersion = BuildConfig.VERSION_NAME,
            requiredPermissions = setOf("module_devices_enabled"), // portal modules the app needs
        ) {
            MyAppNavigation() // only reached logged in; stays composed if the session ends
        }
    }
}
```

`AuthGate` shows the login until there is a session, and a notice with logout when the access request
is still under review, two-factor setup is missing or a required module permission is missing.
Tokens are kept encrypted in `noBackupFilesDir`, so the app stays logged in until the refresh token
expires or the user logs out. Add the Impressum to the app's settings with `ImpressumScreen`.

Every app must show the open-source notices; `ImpressumScreen` lists progeo-core's, pass the app's
own as `extraLicenses`. The font's license (SIL OFL 1.1) is bundled in `assets/progeo-licenses/`.

## Login QR code

The login screen scans a QR code and logs in with it. Accepted contents (`LoginCode.parse`):

| Content | Exchanged at |
|---|---|
| `https://<portal>/login/link/<token>`: the portal's one-time login link | `POST api/auth-support/login-link/exchange/` `{token, otp?}` |
| `progeo://login?token=<token>` or `{"token": "<token>"}` or the bare token | same |
| `progeo://login?refresh=<jwt>` or `{"refresh": "<jwt>"}` or the bare refresh JWT | `POST api/token/refresh/` |

Both are single use: the login link is marked used, the refresh token is blacklisted on rotation.
A login link asks for the two-factor code if the account has 2FA (the link stays valid meanwhile);
staff accounts get no login links unless the server allows it (`MAGIC_LINK_ALLOW_STAFF`).
The code never chooses the server: tokens only go to the server the app is configured for.

The portal does not show login QR codes yet. The simplest addition there is a button on the
profile page that creates a login link for the logged-in user (`helper/login_links.create_link`)
and shows it as a QR code (`setup_viewset.qr_code_data_uri`), valid for `MAGIC_LINK_LOGIN_MINUTES`.

## Tests

`./gradlew :progeo-core:test`: login codes, login with a code and module permissions against a mock
server, the decoder on generated QR codes and barcodes (row padding, inverted, rotated).
