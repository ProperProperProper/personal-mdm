# RECOVERY — "I lost my Mac" runbook

Everything needed to re-manage the Galaxy A17 from a **brand-new computer**.
No cloud, no Android Studio. Follow top to bottom.

---

## 0. What you need

- The phone (Galaxy A17, SM-A175F).
- A **USB-C data cable** (charge-only cables will NOT work — the Mac must see
  the device under `adb devices`).
- This repo (clone from the private GitHub repo).
- ~2 GB disk for the toolchain.
- If updating the *existing* install: the backed-up `warden-release.keystore`.
  If you don't have it, you'll do a clean re-enroll (Section 5) instead.

---

## 1. Rebuild the toolchain

Follow **README.md → Toolchain**. In brief, in `~/android-tools`:

```sh
curl -sL -o jdk17.tar.gz "https://api.adoptium.net/v3/binary/latest/17/ga/mac/aarch64/jdk/hotspot/normal/eclipse"; tar xzf jdk17.tar.gz
curl -sLO https://dl.google.com/android/repository/commandlinetools-mac-11076708_latest.zip; unzip -q commandlinetools-mac-11076708_latest.zip
curl -sLO https://services.gradle.org/distributions/gradle-8.7-bin.zip; unzip -q gradle-8.7-bin.zip
curl -sLO https://dl.google.com/android/repository/platform-tools-latest-darwin.zip; unzip -q platform-tools-latest-darwin.zip
export JAVA_HOME="$(ls -d jdk-*/Contents/Home | head -1)"
mkdir -p sdk
yes | cmdline-tools/bin/sdkmanager --sdk_root=$PWD/sdk --licenses
cmdline-tools/bin/sdkmanager --sdk_root=$PWD/sdk "platforms;android-34" "build-tools;34.0.0" "platform-tools"
```

`adb` is at `~/android-tools/platform-tools/adb`. Add it to PATH or call it by
full path below.

---

## 2. Connect the phone

1. On the phone: **Settings → About phone → Software information → tap Build
   number 7×** to enable Developer options.
2. **Settings → Developer options → enable USB debugging.**
3. Plug in with a **data** cable, tap **Allow** on the debugging prompt.
4. Verify: `adb devices` should show `RFGL43C7J9E   device`
   (not `unauthorized`, not empty). If empty → cable/port is charge-only.

> If USB debugging is currently **blocked by policy** on the enrolled phone
> (only if you turned that toggle on — by default it is off), you cannot adb
> in. Recovery then requires a **factory reset** (Section 6), which also clears
> device owner. This is the intended hardening trade-off.

---

## 3. Build the app

```sh
git clone <your-private-repo-url> personal-mdm && cd personal-mdm
export ANDROID_TOOLS=~/android-tools

# If you HAVE the original keystore, copy it in first:
cp /path/to/warden-release.keystore .

./build.sh
# -> app/build/outputs/apk/release/app-release.apk
```

Verify it has no network permission:

```sh
~/android-tools/sdk/build-tools/34.0.0/aapt dump permissions \
  app/build/outputs/apk/release/app-release.apk | grep -i internet
# (no output = correct)
```

---

## 4. Re-manage an ALREADY-ENROLLED phone (Mac lost, phone untouched)

If the phone is still enrolled and you only lost the managing computer, you do
**not** need to re-enroll. You just need adb back (Section 2). From there:

- Change policy live from the **Personal MDM app** on the phone (fingerprint).
- Or drive it over adb, e.g. lift everything in an emergency:
  ```sh
  adb shell dpm remove-active-admin com.pahtrik.warden/.WardenAdminReceiver
  ```
- To update the app in place you need the **matching keystore** (Section 3).
  Without it, go to Section 5.

---

## 5. Clean re-enroll (no keystore, or fresh phone)

Device owner can only be set when the device has **no accounts**.

```sh
# 1. Remove ALL accounts on the phone:
#    Settings → Accounts and backup → Manage accounts → remove each one
#    (Google AND Samsung). Confirm none remain:
adb shell dumpsys account | grep 'Account {'      # must print nothing

# 2. If a previous Personal MDM is installed, remove its device-owner + app:
adb shell dpm remove-active-admin com.pahtrik.warden/.WardenAdminReceiver
adb uninstall com.pahtrik.warden

# 3. Install the freshly built APK:
adb install -r app-release.apk

# 4. Promote to device owner:
adb shell dpm set-device-owner com.pahtrik.warden/.WardenAdminReceiver
# Expect: "Success: Device owner set to package com.pahtrik.warden/..."

# 5. Grant usage access (REQUIRED for the "session time limit" feature —
#    GET_USAGE_STATS is an app-op, not a runtime permission, so it must be
#    granted here, not from inside the app):
adb shell appops set com.pahtrik.warden GET_USAGE_STATS allow

# 5b. Exempt from battery optimization / Doze so background enforcement
#     (new-app gate, time-limit checker) is never throttled or frozen:
adb shell dumpsys deviceidle whitelist +com.pahtrik.warden
adb shell cmd appops set com.pahtrik.warden RUN_ANY_IN_BACKGROUND allow

# 6. Re-add the Samsung account on the phone (stays permanently).
```

Then open **Personal MDM** (fingerprint) and switch on your policies. The
default whitelist and network lists are baked into the build, so the app comes
up pre-configured; you only need to flip the policy toggles.

---

## 6. Full factory-reset recovery (last resort)

If the phone is locked out of adb (USB-debugging policy was enabled) or
otherwise unmanageable:

1. Power off. Boot to recovery: hold **Volume Up + Power** (Samsung) while
   plugging USB, or use **Settings → General management → Reset → Factory data
   reset** if you can still reach Settings.
2. After reset, the device owner is gone. Re-do initial setup, **skip adding a
   Google account during setup** (needed for clean enroll), then Section 2 → 5.

> **Factory Reset Protection:** if the password-hardening/FRP policy was active,
> after reset the phone may demand the last Google account that was on it.
> Keep those credentials available offline.

---

## 7. Re-do the phone debloat (only after a factory reset)

The original debloat disabled ~52 stock packages. It is not required for the
MDM to work, but to return the phone to the minimal state, disable Google/Samsung
bloat via:

```sh
adb shell pm disable-user --user 0 <package>
adb shell pm uninstall --user 0 <package>     # per-user removal, restorable
```

The authoritative list of what was disabled is the app whitelist inversion:
anything NOT in `Warden.kt defaultWhitelist()` and not core system plumbing was
disabled. Re-enable anything with `adb shell pm enable --user 0 <package>` or
`adb shell cmd package install-existing <package>`.

Set Samsung Messages as default SMS after reinstalling it:
```sh
adb shell cmd role add-role-holder --user 0 android.app.role.SMS com.samsung.android.messaging
```

---

## Quick reference — key commands

| Task | Command |
|---|---|
| List devices | `adb devices -l` |
| Set device owner | `adb shell dpm set-device-owner com.pahtrik.warden/.WardenAdminReceiver` |
| Remove device owner | `adb shell dpm remove-active-admin com.pahtrik.warden/.WardenAdminReceiver` |
| Install/update | `adb install -r app-release.apk` |
| Check accounts | `adb shell dumpsys account \| grep 'Account {'` |
| Verify no-internet | `aapt dump permissions app-release.apk \| grep -i internet` |
| Backup phone | `./backup-phone.sh` |
