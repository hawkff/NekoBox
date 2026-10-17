#!/bin/bash

set -e
set -o pipefail

GEOIP_VERSION="${GEOIP_VERSION:-20261012}"
GEOIP_SHA256="${GEOIP_SHA256:-f24050f4ce11783cd632fe3982f24895d959fa01afc8fab6ca8a6a3615b5de56}"
GEOSITE_VERSION="${GEOSITE_VERSION:-20261015074526}"
GEOSITE_SHA256="${GEOSITE_SHA256:-895e47b22b0950a5093bbc091ed4f6bc36ae71ee91facd2e56c3c22c9d0dd4e6}"
DASHBOARD_COMMIT="8402051bc17496e827f9a9fe808139cc10ad20be"
DASHBOARD_VERSION="20261011084505"
DASHBOARD_SHA256="83ee5649edbd0da97c9d8aeb9d5293909db6931c223f6e4a13cada76520ea01a"

sha256_tool() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | awk '{print $1}'
  else shasum -a 256 "$1" | awk '{print $1}'; fi
}

download_verified() {
  local url="$1" output="$2" expected="$3" tmp actual
  tmp="${output}.download"
  rm -f "$tmp"
  curl -fL --retry 3 --retry-delay 2 --max-time 300 "$url" -o "$tmp"
  actual="$(sha256_tool "$tmp")"
  if [ "$expected" != "$actual" ]; then
    rm -f "$tmp"
    echo "Error: checksum mismatch for $output (expected $expected, got $actual)" >&2
    exit 1
  fi
  mv "$tmp" "$output"
}

# Pin the published dashboard bundle rather than rebuilding a moving branch.
download_verified \
  "https://codeload.github.com/MetaCubeX/Yacd-meta/zip/$DASHBOARD_COMMIT" \
  app/src/main/assets/yacd.zip \
  "$DASHBOARD_SHA256"
printf '%s' "$DASHBOARD_VERSION" > app/src/main/assets/yacd.version.txt

DIR=app/src/main/assets/sing-box
rm -rf "$DIR"
mkdir -p "$DIR"
cd "$DIR"

####
echo VERSION_GEOIP=$GEOIP_VERSION
echo -n "$GEOIP_VERSION" > geoip.version.txt
download_verified \
  "https://github.com/SagerNet/sing-geoip/releases/download/$GEOIP_VERSION/geoip.db" \
  geoip.db \
  "$GEOIP_SHA256"
xz -9 geoip.db

####
echo VERSION_GEOSITE=$GEOSITE_VERSION
echo -n "$GEOSITE_VERSION" > geosite.version.txt
download_verified \
  "https://github.com/SagerNet/sing-geosite/releases/download/$GEOSITE_VERSION/geosite.db" \
  geosite.db \
  "$GEOSITE_SHA256"
xz -9 geosite.db
