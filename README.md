# Personal MDM

A single-device Mobile Device Management app for a **Samsung Galaxy A17
(SM-A175F)**, enrolled as **device owner** to lock the phone down to a
whitelisted set of apps with a strict, self-hosted security policy. Built and
managed from a Mac over adb — **no cloud, no third-party MDM service, no
network access in the app itself.**

This README is written so the entire setup can be **recreated from scratch on
a brand-new computer** if the original Mac is lost. Read
[RECOVERY.md](RECOVERY.md) for the "I lost my Mac" runbook.

---

## What this app does

Personal MDM is an Android **Device Policy Controller (DPC)**. Once it is the
device owner, it enforces policy the user cannot override in Settings. Every
policy is an **in-app toggle** (nothing is hardcoded-on), and the app's own UI
is gated by **fingerprint only** (no PIN/face fallback).

### Policy features (all toggleable in-app)

| Feature | What it does | Mechanism |
|---|---|---|
| App whitelist | Suspends every launchable app not on the list | `setPackagesSuspended` |
| New-app gate | New installs are quarantined + Approve/Deny notification | `PACKAGE_ADDED` receiver |
| Block installs | No app can be installed from any source | `DISALLOW_INSTALL_APPS` |
| Block uninstalls | Whitelisted apps can't be removed | `setUninstallBlocked` |
| Block sideloading | No unknown-source installs | `DISALLOW_INSTALL_UNKNOWN_SOURCES` |
| Per-app internet firewall | Only allowed apps reach the network; "Necessary" apps locked on | blocking-only `VpnService` sinkhole + always-on VPN |
| Block downloads / file saving | No browser downloads; storage perms denied except camera/gallery/WhatsApp; USB transfer + external media blocked | Chrome `DownloadRestrictions=3`, `setPermissionGrantState`, `DISALLOW_USB_FILE_TRANSFER`, `DISALLOW_MOUNT_PHYSICAL_MEDIA` |
| HTTPS only | Blocks http/ftp/ftps/sftp/file/ws/wss/javascript/rtsp/mms/content/data; only https opens | Chrome `HttpsOnlyMode` + `URLBlocklist`; system schemes routed to dead-end sink |
| Link Guard | Every tapped web link opens an inert "blocked" screen showing the URL as text | forced preferred activity for http/https |
| Secure DNS | Forces DNS-over-TLS device-wide, locks the Private DNS setting | `setGlobalPrivateDnsModeSpecifiedHost` + `DISALLOW_CONFIG_PRIVATE_DNS` |
| Chrome cookie allowlist | Blocks all cookies except an allowlist | Chrome managed config |
| Chrome auto-clear | Wipes history/cookies/cache/downloads/passwords on exit | Chrome `ClearBrowsingDataOnExitList` |
| Kiosk home | Whitelisted apps fill a custom home screen | persistent preferred HOME activity |
| Microphone: calls only | `RECORD_AUDIO` denied to all apps except the dialer stack | `setPermissionGrantState` |
| Session time limits | An app auto-closes after N continuous minutes open (default 120); 30-min cooldown; Clock/Phone/Messages exempt | `UsageStatsManager` + `setPackagesSuspended` |
| Block user CA certs | No user-added root certificates (stops TLS interception) | `DISALLOW_CONFIG_CREDENTIALS` |
| Disable safe boot | Can't boot to Safe Mode to bypass policy | `DISALLOW_SAFE_BOOT` |
| Password hardening | Enforces high-complexity lock, 60s auto-lock, wipe after 10 fails | `setRequiredPasswordComplexity` |
| Disable screenshots | No screenshots/recording device-wide | `setScreenCaptureDisabled` |
| Disable NFC | Turns off NFC entirely (also stops tap-to-pay) | `DISALLOW_NEAR_FIELD_COMMUNICATION_RADIO` |
| Settings lockdown | Factory reset, add/remove user, tethering, app force-stop/uninstall all blocked | user restrictions |
| App screenshot guard | The MDM's own screen can't be screenshotted/cast (default on) | `FLAG_SECURE` |
| Cert scanner | Flags apps that may break under the CA block; logs to audit | heuristic scan |
| Local audit log | Append-only encrypted log of installs/approvals/policy/boot | private file |

**Security of the app itself:** fingerprint-only unlock, `FLAG_SECURE`,
excluded from Recents, `allowBackup=false`, EncryptedSharedPreferences
(AES-256-GCM, hardware-backed key), and — critically — **no INTERNET
permission** (stripped at manifest-merge; verify with
`aapt dump permissions`).

---

## Repository layout

```
personal-mdm/
├── README.md              # this file
├── RECOVERY.md            # lost-Mac runbook
├── build.sh               # builds a signed release APK (no Xcode/Android Studio)
├── backup-phone.sh        # pulls shared storage + settings inventory to the Mac
├── settings.gradle.kts, build.gradle.kts, gradle.properties
└── app/
    ├── build.gradle.kts
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/com/pahtrik/warden/*.kt   # policy engine + UI + receivers/services
        └── res/…
```

`com.pahtrik.warden` is the package/applicationId. (The app is *labelled*
"Personal MDM"; the internal package keeps the original `warden` name — do not
change it, the device-owner enrollment is bound to it.)

---

## Toolchain — building without Android Studio

Everything is command-line. On a fresh Mac (Apple Silicon) you need a JDK, the
Android SDK command-line tools, Gradle, and platform-tools (adb). The Mac's
built-in `git`/`clang` may be gated behind an unaccepted Xcode license — that
does **not** affect this build, which uses its own JDK.

### 1. Download the toolchain

```sh
mkdir -p ~/android-tools && cd ~/android-tools

# JDK 17 (Temurin, aarch64)
curl -sL -o jdk17.tar.gz \
  "https://api.adoptium.net/v3/binary/latest/17/ga/mac/aarch64/jdk/hotspot/normal/eclipse"
tar xzf jdk17.tar.gz            # -> jdk-17.x.y+z/

# Android command-line tools
curl -sLO https://dl.google.com/android/repository/commandlinetools-mac-11076708_latest.zip
unzip -q commandlinetools-mac-11076708_latest.zip   # -> cmdline-tools/

# Gradle 8.7
curl -sLO https://services.gradle.org/distributions/gradle-8.7-bin.zip
unzip -q gradle-8.7-bin.zip     # -> gradle-8.7/

# platform-tools (adb)
curl -sLO https://dl.google.com/android/repository/platform-tools-latest-darwin.zip
unzip -q platform-tools-latest-darwin.zip   # -> platform-tools/
```

### 2. Install SDK packages

```sh
cd ~/android-tools
export JAVA_HOME="$(ls -d jdk-*/Contents/Home | head -1)"
mkdir -p sdk
yes | cmdline-tools/bin/sdkmanager --sdk_root=$PWD/sdk --licenses
cmdline-tools/bin/sdkmanager --sdk_root=$PWD/sdk \
  "platforms;android-34" "build-tools;34.0.0" "platform-tools"
```

### 3. Point build.sh at the toolchain

`build.sh` defaults `ANDROID_TOOLS` to a path; override it:

```sh
export ANDROID_TOOLS=~/android-tools
./build.sh
# -> app/build/outputs/apk/release/app-release.apk
```

`build.sh` auto-generates a signing keystore (`warden-release.keystore`) on
first run if none exists. **See the keystore note below.**

---

## Signing keystore — IMPORTANT

The release APK is signed with `warden-release.keystore` (gitignored, never
committed). Android requires that **updates to an installed app be signed with
the same key.**

- If you still have the phone enrolled and want to **update** the app, you
  need the original keystore. **Back it up to local/encrypted media** (no
  cloud) — it is the one artifact this repo intentionally does not contain.
- If you **lost the keystore**, you can't update in place, but you can still
  fully recover: remove the device owner (see RECOVERY.md), uninstall, then
  build + install fresh with a new keystore. You lose nothing but the in-app
  saved toggle state (which you'll re-set anyway).

Default keystore password is `warden-local-only` (override with
`WARDEN_KS_PASS`). Change it for real use.

---

## Installing & enrolling on the phone

Full step-by-step (including the account-removal requirement) is in
[RECOVERY.md](RECOVERY.md). Short version:

```sh
adb install -r app-release.apk
# Device owner requires ZERO accounts on the phone — remove Google & Samsung
# accounts in Settings first, re-add Samsung afterwards:
adb shell dpm set-device-owner com.pahtrik.warden/.WardenAdminReceiver
```

Then open **Personal MDM** on the phone (fingerprint), and switch on the
policies you want.

## Un-enrolling

```sh
adb shell dpm remove-active-admin com.pahtrik.warden/.WardenAdminReceiver
```

If Settings-lockdown/USB-debugging policies block that, a **factory reset**
is the fallback — by design, this is a hardened device.

---

## The phone as configured

- **Model:** Samsung Galaxy A17, SM-A175F. **Serial:** RFGL43C7J9E.
- **Debloat:** ~52 stock packages disabled; Kids Home removed; Samsung
  Messages is the default SMS app (Google Messages disabled).
- **Whitelist (apps allowed to run):** Phone, Contacts, Samsung Messages,
  Camera, Gallery, Google Maps, Microsoft Authenticator, LastPass, LastPass
  Authenticator, Claude, Bybit, PayPal, TradingView, UniFi, WhatsApp, Great
  Southern Bank, myGov, VicRoads, Linkt, Suncorp Bank, Suncorp Secured,
  LinkedIn, Amazon, Audible, CoinSpot.
- The full, authoritative whitelist lives in `Warden.kt` → `defaultWhitelist()`.
  Internet-allowed defaults and the locked "Necessary" set are in
  `netAllowed()` / `NET_NECESSARY` in the same file.

To change what's allowed, edit those lists, rebuild, reinstall — or just use
the in-app toggles on the running device.

---

## Backups (local only — no cloud)

This device runs a **strict no-cloud-backup policy.** Google Photos, Samsung
Cloud, WhatsApp cloud chat backup, and authenticator cloud backup are all
disabled and must stay disabled. Backups happen **only** by pulling data to a
local machine over adb.

`./backup-phone.sh` pulls `/sdcard` (photos, downloads, music, documents) plus
a settings/app inventory to `~/Documents/phone-backups/A17-<date>/`. Run it on
a schedule you keep manually; store the output on local/encrypted media only.

**Limitation to accept:** unrooted Android cannot copy app-internal data
(WhatsApp chats, authenticator seeds, app sessions), and cloud backup is not an
option here by policy. Therefore:
- **Authenticator seeds** — keep the original setup/recovery codes offline when
  you first enrol; re-enrol from those if the phone is wiped.
- **WhatsApp** — a local-only chat backup can be written to `/sdcard` and will
  be captured by `backup-phone.sh`; there is no cloud copy.
- **LastPass** — the vault is server-side by the vendor's design (that is the
  product, not a "backup" you enabled); nothing extra is stored on-device.
