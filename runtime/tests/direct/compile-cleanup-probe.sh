#!/usr/bin/env bash
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/../../.." && pwd)"
KOTLINC="${KOTLINC:-kotlinc}"
OUT="${1:?output jar path required}"
RUNTIME="$REPO/app/android/app/src/main/kotlin/io/github/ouy09103/dsh_go_root/runtime"
mkdir -p "$(dirname "$OUT")"
"$KOTLINC" "$RUNTIME/DshProcess.kt" "$RUNTIME/LogRedactor.kt" "$RUNTIME/ProotTempFiles.kt" "$RUNTIME/LaunchDiagnostics.kt" "$RUNTIME/DirectLaunch.kt" "$RUNTIME/L2sMigration.kt" "$RUNTIME/RuntimeProcessOwnership.kt" "$RUNTIME/SpeechGuest.kt" \
  "$HERE/cleanup-probe/"*.kt -include-runtime -d "$OUT"
