#!/usr/bin/env bash
# Publish a signed release APK without embedding server paths or credentials.
set -euo pipefail
cd "$(dirname "$0")/.."

APK_FILE="${APK_FILE:-android/app/build/outputs/apk/release/app-release.apk}"
PUBLISH_DIR="${PUBLISH_DIR:?set PUBLISH_DIR to the private deployment target}"
APKSIGNER="${APKSIGNER:-apksigner}"

if [[ ! -f "$APK_FILE" ]]; then
  echo "release APK not found: $APK_FILE" >&2
  exit 1
fi
if ! command -v "$APKSIGNER" >/dev/null 2>&1; then
  echo "apksigner not found; set APKSIGNER to its absolute path" >&2
  exit 1
fi

"$APKSIGNER" verify --print-certs "$APK_FILE"
install -d -m 0755 "$PUBLISH_DIR"
install -m 0644 "$APK_FILE" "$PUBLISH_DIR/qoderwake-mobile.apk"
sha256sum "$PUBLISH_DIR/qoderwake-mobile.apk" \
  > "$PUBLISH_DIR/qoderwake-mobile.apk.sha256"

echo "published signed APK and SHA-256 to $PUBLISH_DIR"
