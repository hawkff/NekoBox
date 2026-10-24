#!/bin/bash
# Build the Mieru client binary for all Android ABIs and install them as
# bundled native executables (app/executableSo/<abi>/libmieru.so).
#
# PluginManager resolves "mieru-plugin" to the bundled libmieru.so before
# looking for an external APK that implements the current plugin contract.
#
# Usage: buildScript/lib/mieru.sh (from the repository root)
set -e
set -o pipefail

source "buildScript/init/env.sh"

if [ -z "$ANDROID_NDK_HOME" ]; then
  echo "Error: ANDROID_NDK_HOME is not set (NDK required to cross-compile Mieru)." >&2
  exit 1
fi

# Mieru release tag to build from source.
MIERU_VERSION="${MIERU_VERSION:-v3.39.0}"
# Immutable commit that MIERU_VERSION points to (pinned for reproducible builds;
# update together with MIERU_VERSION on any bump).
MIERU_COMMIT="${MIERU_COMMIT:-bcd14b88d0fb7e7707bb80e5d7f6c33675d26c06}"

DEPS="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin"
# macOS NDK host dirs are darwin-x86_64 / darwin-arm64; fall back if linux is absent.
if [ ! -d "$DEPS" ]; then
  DEPS="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/darwin-x86_64/bin"
fi
if [ ! -d "$DEPS" ]; then
  DEPS="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/darwin-arm64/bin"
fi
if [ ! -d "$DEPS" ]; then
  echo "Error: NDK LLVM toolchain not found under $ANDROID_NDK_HOME (unsupported host or NDK layout)." >&2
  exit 1
fi

WORK="$(pwd)/.mieru-build"
OUT="$(pwd)/app/executableSo"
CLIENT_PATCHES=("$(pwd)/buildScript/lib/mieru-protect.patch")

# Fetch source (shallow clone at the pinned tag). Re-clone fresh if the existing
# checkout is missing or its git operations fail (e.g. corrupted by a prior build).
need_clone=1
if [ -d "$WORK/.git" ]; then
  if git -C "$WORK" fetch --depth 1 origin "$MIERU_COMMIT" \
     && git -C "$WORK" checkout -q FETCH_HEAD; then
    need_clone=0
  else
    echo ">> existing $WORK is unusable; re-cloning"
  fi
fi
if [ "$need_clone" -eq 1 ]; then
  rm -rf "$WORK"
  git init -q "$WORK"
  git -C "$WORK" remote add origin https://github.com/enfein/mieru.git
  git -C "$WORK" fetch --depth 1 origin "$MIERU_COMMIT"
  git -C "$WORK" checkout -q FETCH_HEAD
fi

pushd "$WORK" >/dev/null

# Propagate Android VPN protection failures.
for patch in "${CLIENT_PATCHES[@]}"; do
  if git apply --check --whitespace=error-all "$patch" 2>/dev/null; then
    git apply --whitespace=error-all "$patch"
  elif ! git apply --reverse --check "$patch" 2>/dev/null; then
    echo "Error: Mieru patch does not match the source: $(basename "$patch")" >&2
    exit 1
  fi
done

build_abi() {
  local abi="$1" goarch="$2" cc="$3" goarm="$4"
  echo ">> building libmieru.so for $abi"
  mkdir -p "$OUT/$abi"
  env GOOS=android GOARCH="$goarch" ${goarm:+GOARM=$goarm} CGO_ENABLED=1 \
    CC="$DEPS/$cc" \
    go build -trimpath -ldflags='-s -w' \
      -o "$OUT/$abi/libmieru.so" ./cmd/mieru
}

build_abi "arm64-v8a"   "arm64" "aarch64-linux-android21-clang"
build_abi "armeabi-v7a" "arm"   "armv7a-linux-androideabi21-clang" "7"
build_abi "x86"         "386"   "i686-linux-android21-clang"
build_abi "x86_64"      "amd64" "x86_64-linux-android21-clang"

popd >/dev/null

echo ">> installed Mieru binaries:"
ls -la "$OUT"/*/libmieru.so
