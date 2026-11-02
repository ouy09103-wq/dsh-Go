#!/usr/bin/env bash
# Fetches the prebuilt runtime (rootfs + proot jniLibs) that the bundled APK needs.
#
#   tooling/fetch-runtime.sh arm64-v8a [version]
#
# The rootfs is a 150 MB archive, so it is not committed. This pulls the official
# dsh-go release APK for the ABI, extracts assets/runtime/* and lib/<abi>/*.so
# into runtime/out/<abi>/, exactly the layout app/android/app/build.gradle.kts reads.
# Idempotent: an already staged runtime is left alone unless FORCE=1.
set -euo pipefail

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
ABI=${1:-arm64-v8a}
VERSION=${2:-${DSH_RUNTIME_VERSION:-1.0.8}}
FORCE=${FORCE:-0}

case "$ABI" in
  arm64-v8a) apk_suffix=arm64 ;;
  x86_64) apk_suffix=x64 ;;
  *) echo "fetch-runtime: unknown abi $ABI" >&2; exit 1 ;;
esac

OUT="$ROOT/runtime/out/$ABI"
if [[ -f "$OUT/assets/runtime/manifest.json" && $FORCE != 1 ]]; then
  echo "fetch-runtime: $ABI already staged"
  exit 0
fi

CACHE="$ROOT/runtime/.cache/upstream"
mkdir -p "$CACHE" "$OUT"
APK="$CACHE/dsh-go-$VERSION-$apk_suffix.apk"

if [[ ! -s $APK ]]; then
  url="https://github.com/ccch1mneyyy/dsh-Go/releases/download/v$VERSION/dsh-go-$VERSION-$apk_suffix.apk"
  echo "fetch-runtime: $url"
  if command -v curl >/dev/null 2>&1; then
    curl_headers=()
    [[ -n ${GITHUB_TOKEN:-} ]] && curl_headers=(-H "Authorization: Bearer $GITHUB_TOKEN")
    curl -fL --retry 3 --retry-delay 3 --connect-timeout 20 "${curl_headers[@]}" -o "$APK.part" "$url"
  else
    python3 "$ROOT/tooling/download.py" "$url" "$APK.part" "${GITHUB_TOKEN:-}"
  fi
  mv "$APK.part" "$APK"
fi

python3 - "$APK" "$OUT" "$ABI" <<'PY'
import os, sys, zipfile

apk, out, abi = sys.argv[1], sys.argv[2], sys.argv[3]
assets = os.path.join(out, "assets")
jni = os.path.join(out, "jniLibs", abi)
os.makedirs(assets, exist_ok=True)
os.makedirs(jni, exist_ok=True)

libs = {"libproot.so", "libproot-loader.so", "libtalloc.so", "libandroid-shmem.so"}
copied = 0
with zipfile.ZipFile(apk) as z:
    for name in z.namelist():
        target = None
        if name.startswith("assets/runtime/"):
            target = os.path.join(assets, name[len("assets/"):])
        elif name.startswith(f"lib/{abi}/") and os.path.basename(name) in libs:
            target = os.path.join(jni, os.path.basename(name))
        if target is None or name.endswith("/"):
            continue
        os.makedirs(os.path.dirname(target), exist_ok=True)
        with z.open(name) as src, open(target, "wb") as dst:
            while True:
                chunk = src.read(1 << 20)
                if not chunk:
                    break
                dst.write(chunk)
        copied += 1
print(f"fetch-runtime: extracted {copied} files into {out}")
PY

[[ -f "$OUT/assets/runtime/manifest.json" ]] || { echo "fetch-runtime: manifest missing" >&2; exit 1; }
