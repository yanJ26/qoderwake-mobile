#!/usr/bin/env bash
# 部署 APK 并生成更新清单 latest.json（app 内「检查更新」读取）
set -e
cd "$(dirname "$0")/.."

VERSION=$(node -p "require('./package.json').version")
CODE=$(node -p "const v=require('./package.json').version.split('.').map(Number); v[0]*10000+v[1]*100+v[2]")
NOTES="${1:-}"

cp android/app/build/outputs/apk/debug/app-debug.apk /var/www/dsh-app/dsh-mobile.apk
[ -f docs/dshdocs.html ] && mkdir -p /var/www/dshdocs && cp docs/dshdocs.html /var/www/dshdocs/index.html
node -e '
  const [version, code, notes] = process.argv.slice(1);
  const manifest = {
    version,
    versionCode: Number(code),
    url: "https://qhrc.work/dsh-app/dsh-mobile.apk",
    notes,
  };
  require("fs").writeFileSync("/var/www/dsh-app/latest.json", JSON.stringify(manifest, null, 2));
' "$VERSION" "$CODE" "$NOTES"

echo "deployed v$VERSION (versionCode $CODE) -> /var/www/dsh-app/"
