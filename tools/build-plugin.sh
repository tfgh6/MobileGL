#!/usr/bin/env bash
# SPDX-License-Identifier: LGPL-3.0-only
#
# Builds the MobileGL renderer plugin APK (debug variants, debug-signed).
# Requirements: JDK 17, Gradle 8.10.x, Android SDK 34 + build-tools 34,
# NDK 27.3.13750724, checked-out submodules.
#
# Usage:
#   ANDROID_HOME=$HOME/Android/Sdk ./tools/build-plugin.sh
#   ANDROID_HOME=... RELEASE=1 ./tools/build-plugin.sh
set -euo pipefail

REPO="$(cd "$(dirname "$0")/.." && pwd)"
: "${ANDROID_HOME:?Set ANDROID_HOME to the Android SDK path}"
NDK_VERSION="${NDK_VERSION:-27.3.13750724}"

command -v gradle >/dev/null || {
  echo "gradle not found; install Gradle 8.10.x or use .github/workflows/plugin-debug.yml" >&2
  exit 1
}

cat > "${REPO}/android-plugin/local.properties" <<EOF
sdk.dir=${ANDROID_HOME}
ndk.dir=${ANDROID_HOME}/ndk/${NDK_VERSION}
EOF

if [[ "${RELEASE:-0}" == "1" ]]; then
  TASKS=(:app:assembleEsprytPluginRelease :app:assembleMagmaPluginRelease)
else
  TASKS=(:app:assembleEsprytPluginDebug :app:assembleMagmaPluginDebug)
fi

cd "${REPO}"
gradle --no-daemon -p android-plugin "${TASKS[@]}" \
  -Pmobilegl.logLevel=MOBILEGL_LOG_LEVEL_INFO --parallel

echo "APKs:"
find "${REPO}/android-plugin/app/build/outputs/apk" -name '*.apk' -type f
