# DRAFT: Teltonika router setup in the app

Status: concept, first implementation in progress (section 12). 2026-11-23.

Decided: `minSdk` 29 (Android 10, the minimum for `WifiNetworkSpecifier`; an installer phone runs Android 10);
routers go into this app as a separate module (gap 13, option a); the app handles setup and
diagnostics on site (gap 9); firmware is updated before the configuration (gap 4).

Goal: an installer scans the QR code on a Teltonika router and the app logs in without anyone typing a
password. From there they can see the router's status, apply our configuration, update the firmware and
leave the router in a known, documented state. Models: **RUT241** (US), **RUT986** (EU), **RUT901** (bonus).

Tags used below: **[verified]** = checked in Teltonika's public docs, **[unverified]** = expected but not
confirmed. Each unverified item must be tried on a real device before we build on it (see M0).

---

## 1. Facts we checked

### The label QR code holds the factory login [verified]

Teltonika's label pages for RUT241, RUT986 and RUT901 show the same two codes:

| Where | Example payload (placeholders from the wiki) |
|---|---|
| **Device label** (sticker or engraving) | `WIFI:T:WPA;S:XX9999_9999;P:99999999;SN:9999999999;I:999999999999999;M:AABBCCDDEEFF;U:admin;PW:99999999;B:999;` |
| **Box** | `SN:9999999999;I:999999999999999;M:AABBCCDDEEFF;B:999;` |

Keys: `T/S/P` = Wi-Fi security/SSID/passphrase, `SN` = 10-digit serial, `I` = IMEI, `M` = LAN MAC,
`U/PW` = WebUI/API user and password, `B` = batch. The RUT241 page says the format applies "from batch 006".
Older RUT241 batches may have a different label.

What this means for us:
- The device QR alone gets us onto the router's Wi-Fi **and** logged in, until someone changes the password.
- The box QR only identifies the device. It is enough when the backend already knows the credentials.
- The `WIFI:` part is the standard Android/ZXing format, so `;`, `:` and `\` can be escaped with `\`. The
  parser must handle that. `SN/I/M/U/PW/B` are Teltonika's own extensions.
- **Anyone who can read the sticker has admin.** Changing the factory password is required, not optional
  (section 5).

### API basics [partly verified]

- Login: `POST /api/login` with username and password returns a token. Send it as
  `Authorization: Bearer <token>`. **The session expires after 5 minutes** [verified], so the client must log
  in again on its own, and long operations like a firmware upload must not depend on one session.
- The API changes between firmware versions. Teltonika's docs are published **per model and per
  firmware version** [verified]. A forum thread shows calls returning "Endpoint not implemented" on older
  firmware, and Teltonika said full API support starts with **RutOS 07.06** [verified].
- Firmware: `POST /firmware/actions/upload_device_firmware` (multipart field `file`), then
  `POST /firmware/actions/verify` (answer: `valid`, `hw_support`, `authorized`, `sha256`, …), then
  `POST /firmware/actions/upgrade` with `data.keep_settings` **[verified in the spec]**. The docs site renders
  with JavaScript, but its OpenAPI specs can be read directly: the index is
  `https://developers.teltonika-networks.com/docs/available-docs.json` (model → firmware version → file),
  each spec is `https://developers.teltonika-networks.com/docs/<file>` (e.g. `7.24.8_v1.16.7.json`).
  On 2026-11-25 every path in `RutosPaths` was checked against the specs for 7.10, 7.19 and 7.24.8.
- The router uses HTTPS with a self-signed certificate at `192.168.1.1` by default **[unverified for our
  units]**.

### Models

| | RUT241 | RUT986 | RUT901 |
|---|---|---|---|
| Market | US | EU | bonus |
| Mobile | LTE Cat 4 | LTE Cat 4 (Telit) | LTE Cat 4 |
| SIM | 1 slot [unverified] | dual SIM **+ eSIM** [verified] | dual SIM [unverified] |
| Extras | | RS232/RS485, GNSS, digital I/O [verified] | I/O [unverified] |
| Firmware family | RUT2M [unverified] | RUT9M? [unverified] | RUT9M [unverified] |

The UI should not hard-code these. It reads what the device reports (SIM slots, eSIM, GNSS, serial ports)
and shows only what exists. The model only selects the firmware family and the default template.

---

## 2. Where the plan has gaps

These are the things the first idea doesn't cover yet. Each one can break the feature or cause a
support case.

1. **How does the phone reach the router?** "Use the API" assumes a network path. On site the phone has to
   join the router's Wi-Fi. That network often has **no internet** yet (no SIM, no APN), and then Android
   either refuses it or sends our traffic over mobile data. We need `WifiNetworkSpecifier` and bind *only*
   the router's HTTP client to that network (`network.socketFactory`). The backend client keeps using
   mobile data. Without this, nothing works. Also: `WifiNetworkSpecifier` needs Android 10, and our
   `minSdk` is 26. Either raise it to 29 or keep a legacy path.
2. **We can cut our own connection.** Changing the LAN IP, Wi-Fi SSID/password/country, the admin password
   or firewall rules can disconnect the phone in the middle of a run. The apply engine has to know which
   settings affect its own connection, apply them last and reconnect with the new values.
3. **A crash during a password change locks us out.** If the app changes the password and then crashes
   or loses network before the backend has the new one, nobody can log in again except by factory
   reset. The password change needs a two-phase commit (section 5).
4. **The API differs between firmware versions.** Supporting three models across every firmware version
   in the field does not scale. Proposal: **firmware first**. Every setup brings the router to *our
   approved version* before it configures anything. Then the config code only talks to 1–2 known API
   versions. Older versions only need the endpoints for status, login and firmware upgrade.
5. **"Credentials in our profiles" has to mean the backend, not the phone.** Today's profiles are
   ESP `device_config` presets stored only on the phone. Router passwords stored on one phone are lost
   with that phone and can't be used by colleagues. We need a **device record per serial number** in the
   backend. A template (what to configure) is a separate thing from a device record (this router, its
   credentials, its state).
6. **Is the router the one we scanned?** In a workshop with five routers powered on, the phone can join
   the wrong Wi-Fi. After login, always compare the serial and MAC from the API with the scanned QR, and
   stop if they differ.
7. **Trust and TLS.** The certificate is self-signed. Accepting any certificate leaves the admin password
   open to anyone in Wi-Fi range. Pin the certificate on first contact (TOFU) and keep the fingerprint
   in the device record. After a factory reset the fingerprint changes, so the app needs a "re-pin"
   step that requires the factory password from the QR.
8. **The site has no internet.** That's why the site needs a router. The app must work **offline-first**:
   before driving out, it downloads templates, firmware, and credentials for the routers on the job. It
   queues reports and password confirmations and syncs them later. A password change made offline stays
   "pending" until it is synced, and the app has to show that clearly.
9. **Remote access is a separate decision.** Everything in this draft works on site only. Configuring or
   updating routers in the field remotely is a different product: either Teltonika RMS (paid, already
   does firmware and config at scale) or our own backend through a VPN. **We need to decide what the app is
   for and what RMS is for, so we don't build a worse copy of RMS.** Recommendation: the app handles
   on-site setup and diagnostics. Remote management comes later, if we need it.
10. **US specifics for RUT241.** There are carrier-certified variants, and the product code on the box
    shows which one. APN, band settings and possibly modem firmware differ by carrier, and the Wi-Fi
    country code must be US. Templates must be **regional**: APN, Wi-Fi country, time zone, NTP.
11. **Firmware is not one file.** RutOS images belong to a family (RUT2M, RUT9M, …). Some hardware
    revisions can't be downgraded below a minimum version. The **modem firmware** is updated separately
    from RutOS. The backend has to deliver firmware with a manifest (section 6), not as a bare file.
12. **Which account does the app use?** The QR gives `admin`. Options: keep `admin` with a new password, or
    create a dedicated `progeo-service` user for the API. Whether a non-admin user can use the API, and
    which group it needs, is [unverified].
13. **Which app?** This repo is the *Lua Flasher*. Routers make it an installer toolkit. Options:
    (a) add it here as a new module and rename the app later ("ProGeo Installer"), or (b) build a separate
    spin-off app on `progeo-core`, which was designed for that. **Recommendation: (a).** Installers need
    both tools on the same site visit, and section 8 links them. Keep router code in its own module so it
    can still be split out later.
14. **Who may do what?** Permissions in the portal, for example `module_routers_view`,
    `module_routers_configure`, `module_routers_firmware`, `module_routers_credentials` (reveal the
    password). Every change is recorded in an audit log: who, which router, what was changed, from which
    phone.

---

## 3. Setup flow (wizard)

```
Scan QR ─► Parse ─► Look up serial in backend ─► Join router Wi-Fi ─► TLS pin ─► Login
   ─► Check identity (serial/MAC) ─► Backup ─► Firmware to approved version ─► Apply template
   ─► Set password (two-phase) ─► Mobile check (SIM, signal, ping) ─► Report ─► Done
```

1. **Scan.** Use the existing `progeo-core` scanner (QR is supported already). Accept:
   the device QR (full); the box QR or Code 128 serial barcode (identity only); manual serial entry.
   If someone scans the box but the backend has no credentials, tell them: "scan the label on the device".
2. **Look up.** `GET` the device record by serial. Three outcomes:
   - *new*: no record. Create one (serial, IMEI, MAC, model, batch, factory credentials, encrypted).
   - *known*: use the stored credentials. **This is the automatic login.**
   - *offline*: use the local cache if this router is in the downloaded job, otherwise the QR factory
     credentials.
   Login order: current password → pending password (if any) → factory password from QR → ask.
   Show which one worked, because it tells the installer what state the router is in.
3. **Join Wi-Fi** with the SSID/passphrase from the QR (or the stored ones if we changed them). Android
   asks once in a system dialog.
4. **TLS pin**, **login**, **identity check** (gap 6).
5. **Backup.** Download the config backup and upload it to the backend (queue it if offline). Optional:
   the routers are fresh, so the firmware update does not wait for it, and applying a configuration only
   tries it (a failed backup does not stop the change).
6. **Firmware** (section 6). If the router is not on the approved version: upload it, upgrade, wait for
   the reboot, reconnect, check the version.
7. **Wi-Fi name and password.** Decided (2026-11-25): no templates on site, only a new SSID and WPA2 key
   (`PUT /api/wireless/interfaces/config/<id>` on every access point). Stored as pending on phone and
   server first, read back from the router, then the app reconnects with the new values. Section 4
   (templates) is parked; the library code stays, the app no longer uses it.
8. **Password** (section 5).
9. **Mobile check.** SIM detected, PIN state, registered to the network, operator, band, signal values,
   data session up, and a ping from the router to our backend. If the SIM or APN is wrong, show what to fix.
10. **Report** (section 8).

Every step can be resumed: the wizard stores its state, so a reboot, a lost Wi-Fi connection or a closed
app doesn't mean starting over. The existing `FlashService` foreground-service pattern applies here too.

---

## 4. Configuration: templates and desired state

- A **template** is declarative: "LAN 192.168.10.1/24, Wi-Fi SSID `PG-{serial}`, APN `x`, NTP, time zone,
  WAN failover, RMS off, remote WebUI off …". Templates come from the backend, per region and model,
  with a version and a signature (reuse the existing Ed25519 channel/bundle signing).
- **Per-device overrides** are stored in the device record (e.g. a static IP for one site).
- Apply = **read the current state → compute a diff → show it → apply in order → read back and compare**.
  Settings that affect our connection go last (gap 2).
- **Drift check**: on any later visit, the app compares the router with its template and lists the
  differences ("someone changed the APN by hand"). The installer can fix them or accept them.
- We do **not** use RutOS backup files as templates. They are tied to model and firmware and contain
  device-specific values. They are only for rollback.

Editable areas for v1 (each one has to be mapped to the endpoints of our firmware version):
mobile/APN/SIM priority and failover, LAN/DHCP, Wi-Fi (SSID, key, country, on/off), WAN and failover,
NTP/time zone, admin password, remote access (WebUI/SSH/API on WAN: must be **off**), RMS on/off, and
I/O and serial (RUT986) only if we need them. Other services come later, one by one, each with a real need.

---

## 5. Credentials

- Backend: the password is encrypted at rest. Reading it requires `module_routers_credentials`, and
  every reveal is logged.
- Phone: the cache is encrypted with `SecretCipher` (Keystore) and contains only routers from the current
  job. It expires after N days. It is wiped on logout, and remotely when the session is revoked.
- **The factory password is never left on a router we set up.** The new password is generated per device,
  with a length that meets RutOS rules (at least 8 characters, upper case, lower case, digit [verified]).
- **Two-phase change:**
  1. Generate the password and store it as *pending* in the backend, or locally if offline. Write the
     local copy to disk before step 2.
  2. Change it on the router.
  3. Log in with it, then mark it *confirmed* in the backend.
  Until step 3 succeeds, the record keeps both the old and the pending password, and the login tries both.
- The token (5 minutes) is only kept in memory. It is never logged, not even in debug builds.

---

## 6. Firmware

- The backend publishes a **firmware channel per family**, in the same style as the existing bundle
  channel: version, family, compatible product codes, minimum hardware revision, minimum version that
  can upgrade to it, size, SHA-256, release notes, **our signature**. The app checks all of it before
  uploading anything.
- Approved versions are set **per region and model**. "Latest" is not a policy.
- The upload goes over the local Wi-Fi (about 15–30 MB [unverified]). The session expires after 5
  minutes, so the client has to log in again on its own during upload and polling.
- `keep_settings`: normally *true*. With *false*, the router goes back to factory Wi-Fi and password,
  and the app has to reconnect with the QR credentials.
- After the upgrade the router reboots (several minutes). The app waits, rejoins Wi-Fi, checks the
  version and pins the certificate again if it changed.
- Never start an upgrade when the phone battery is low. Hold a wake lock, and show the
  "don't unplug the router" instruction.
- **Modem firmware** is separate. Shown read-only for now, updates are a later step.
- Do we redistribute Teltonika images from our backend? Check their licence terms. Technically it's
  fine, the files are public.

---

## 7. Monitoring and diagnostics

Read-only, available for any router we can log into:
- **System**: model, serial, firmware, uptime, temperature, CPU and memory if reported, time and NTP state.
- **Mobile**: operator, technology, band, RSSI/RSRP/RSRQ/SINR, cell ID, data session, data usage per SIM.
- **SIM**: slot, state, PIN status, ICCID, IMSI, eSIM profile (RUT986).
- **Interfaces**: up/down, IP, traffic counters, a live throughput graph.
- **Wi-Fi**: clients, channel. **LAN**: DHCP leases, so you can see whether the microBOX is connected.
- **Logs and events**: export them and attach them to a support ticket.
- **Actions**: reboot, reconnect mobile, switch SIM, ping or traceroute from the router.

---

## 8. Additional ideas

- **Antenna placement mode.** Full screen, live RSRP/SINR with a large quality rating and a tone, so
  the installer can move the antenna around and see the best spot right away. This is the most useful
  feature for installers.
- **Connect it to the microBOX.** Once the router is set up, the app knows its SSID and Wi-Fi key. It
  can fill those into the ESP `device_config` profile, so the next step is flashing the microBOX with
  the right Wi-Fi. One site visit, one flow. Then check the router's DHCP leases to confirm the box is
  online.
- **Installation report.** Serial, IMEI, ICCID, firmware, template version, signal values, GPS position
  of the phone, photos of the mounted router and antenna, installer, timestamp. Sent to the backend and
  linked to the site or project, with a PDF for the customer.
- **Workbench mode.** Prepare a batch of routers in the office: scan them one after another, firmware
  and template run in a queue. Connect over a USB-C Ethernet adapter instead of Wi-Fi, so several
  routers powered on at once don't get mixed up.
- **Job download before going on site.** One button: "prepare for job X" downloads templates, firmware
  and credentials so the app works fully offline.
- **eSIM (RUT986).** Scan the eSIM activation QR code with the same scanner, if the API supports
  installing eSIM profiles [unverified].
- **Factory reset help.** Instructions per model (button and timing), and afterwards the app falls back
  to the QR credentials and pins the certificate again.
- **Health check on revisit.** Firmware behind? Config drift? Signal worse than at installation? Shown as
  one traffic-light screen.

---

## 9. Architecture (in this repo)

- **`:teltonika`**, a new plain-Kotlin module like `:core`, with no Android code: `RutosClient`
  (OkHttp, kotlinx.serialization, automatic re-login), `LabelCode.parse` (QR/box/barcode), typed models,
  per-version adapters, a template diff/apply engine and firmware manifest checks. Tested against a
  **fake RutOS server** (like `FakeEsp8266Rom`) and against **responses recorded per firmware version**
  from real devices.
- **`:app`**: a "Routers" tab, the setup wizard, network binding (`RouterNetwork`: Wi-Fi specifier plus
  socket factory), the device-record cache, the firmware store (like `BundleStore`) and a foreground
  service for long runs.
- **`:progeo-core`**: reuse `CodeScanner`, `SecretCipher`, `BackendClient`, `ModulePermissions`. No changes
  expected.
- **Backend** (new, needs an API contract):
  `GET/POST routers/{serial}` (record and credentials), `POST routers/{serial}/password/pending|confirm`,
  `GET routers/templates?region&model`, `GET routers/firmware/{family}/channel.json`,
  `POST routers/{serial}/backups`, `POST routers/{serial}/reports`, `GET jobs/{id}/routers`.

---

## 10. Milestones

| | Content | Done when |
|---|---|---|
| **M0 spike** | One of each model on the desk. Read the API docs for our firmware in a browser. Check every [unverified] item, record API responses, check network binding on 2–3 phones | Open questions answered, recordings in the repo |
| **M1 read-only** | Scan → Wi-Fi → TLS pin → login with QR password → status/signal/SIM screens, antenna mode | An installer can check signal on site |
| **M2 credentials** | Backend device record, automatic login, two-phase password change, offline cache, audit | No router keeps its factory password |
| **M3 config** | Templates, diff, apply, read back, drift check, backups | One template per region rolled out on a real router |
| **M4 firmware** | Channel, manifest checks, upload/upgrade/reconnect | Update from shipping version to approved version works on all 3 models |
| **M5 extras** | Report, microBOX link, workbench mode, job download | |

Firmware (M4) could move ahead of config (M3), because of the firmware-first argument in gap 4.

---

## 11. Open questions

1. App vs. RMS: on site only, or remote management too? Do we already have RMS licences?
2. Same app (rename later) or separate app?
3. Which firmware version do we approve per model, and do our routers ship with ≥ 07.06?
4. Do we keep `admin` or create a service user? Can a non-admin user use the API?
5. Which RUT241 product code and carrier do we buy for the US? Which APNs?
6. Which settings are actually in the v1 template? We need a list from operations, not "everything".
7. Do the microBOX boards connect to the router's Wi-Fi (section 8 depends on this)?
8. ~~Can `minSdk` go up to 29?~~ Decided: 29 (Android 10). Check on M0 whether Android 10 asks again to join the router Wi-Fi when reconnecting after a reboot (the firmware step waits for that).
9. Who in the backend team owns the router device records and the API contract?

---

## 12. Implementation status

Built and tested on the JVM. **Not yet run against a real router or on a phone with a router.**

| Part | Where | State |
|---|---|---|
| Label codes (device, box, serial barcode, escapes) | `teltonika/…/label/RouterLabel.kt` | done, tests |
| RutOS client: login, re-login before the 5 min expiry and after a 401, envelope, error codes, upload with progress, file download | `teltonika/…/api/RutosClient.kt` | done, tests against `FakeRutos` |
| Typed calls and lenient models (device, modems/SIM/signal, interfaces, DHCP) | `teltonika/…/api/` | done; all paths and field names checked against Teltonika's OpenAPI specs (7.10/7.19/7.24.8), not yet against a real router |
| Certificate pinning on first use, change detection | `teltonika/…/tls/PinningTrustManager.kt` | done, tests over real TLS |
| Firmware versions, signed channel format, checks (family, model, `min_from`, downgrade) | `teltonika/…/firmware/Firmware.kt` | done, tests |
| Templates: placeholders, overrides, diff, apply (connection last), read back | `teltonika/…/config/Template.kt` | done, tests |
| Passwords, login order, identity check | `teltonika/…/credentials/Passwords.kt` | done, tests |
| Router records + outbox, encrypted, atomic writes | `app/…/router/RouterStore.kt` | done |
| Backend client for the proposed contract (section 9) | `app/…/router/RouterBackend.kt` | done, tests; **backend not built yet** |
| Firmware channel `routers/channel.json` + images | `app/…/router/RouterFirmwareStore.kt` | done; **nothing published yet** |
| Joining the router Wi-Fi / USB Ethernet, binding the HTTP client | `app/…/router/RouterNetwork.kt` | done, untested on devices |
| Session: connect flow, setup steps, two-phase password, reconnect after reboot | `app/…/router/RouterController.kt` | done |
| Screens: Routers tab, router (checklist, status), antenna mode | `app/…/ui/routers/` | done |

Differences from this draft, on purpose for the first version:
- Templates come over the authenticated backend connection and are not signed separately.
- The report has no GPS position or photos yet.
- "Job download" is "Sync for offline use". It refreshes every router already on the phone, plus
  its firmware and the configurations. There is no job API yet.
- Wi-Fi changes made by a template use the same two phases as passwords
  (`pending_access` → `access`).

Next: M0 on real devices. Correct `RutosPaths` and record answers as test fixtures. Then the
backend team builds the contract from section 9 and publishes the firmware channel.

Sources: [RUT241 labels](https://wiki.teltonika-networks.com/view/RUT241_Product_Labels),
[RUT986 labels](https://wiki.teltonika-networks.com/view/RUT986_Product_Labels),
[RUT901 labels](https://wiki.teltonika-networks.com/view/RUT901_Product_Labels),
[RutOS API getting started](https://developers.teltonika-networks.com),
["Endpoint not implemented" / 07.06](https://community.teltonika.lt/t/code-122-error-endpoint-not-implemented/7023),
[firmware/actions/upgrade thread](https://community.teltonika.lt/t/error-api-syntax-for-firmware-actions-upgrade/5011),
[RUT986 product page](https://teltonika-networks.com/products/routers/rut986),
[RutOS firmware update](https://wiki.teltonika-networks.com/view/RutOS_and_TSWOS_firmware_update),
[RUT241 quick start](https://wiki.teltonika-networks.com/view/QSG_RUT241).
