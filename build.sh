#!/bin/sh
# Build Personal MDM on this Mac without Xcode.
# Toolchain (JDK17 + Android SDK + Gradle) lives in the session scratchpad;
# re-download via the README if the scratchpad has been cleaned.
set -e
TOOLS="${ANDROID_TOOLS:-/private/tmp/claude-501/-Users-local-local/c78a99c0-9da3-43f5-99d9-24682a10e7fa/scratchpad/android-tools}"
export JAVA_HOME="$(ls -d "$TOOLS"/jdk-*/Contents/Home | head -1)"
export ANDROID_HOME="$TOOLS/sdk"
export PATH="$JAVA_HOME/bin:$TOOLS/gradle-8.7/bin:$PATH"

cd "$(dirname "$0")"

# One-time: generate local signing keystore (never commit)
if [ ! -f warden-release.keystore ]; then
  keytool -genkeypair -keystore warden-release.keystore -alias warden \
    -keyalg RSA -keysize 4096 -validity 10950 \
    -storepass "${WARDEN_KS_PASS:-warden-local-only}" \
    -dname "CN=Personal MDM, O=Local"
fi

gradle --no-daemon assembleRelease
echo "APK: app/build/outputs/apk/release/app-release.apk"
