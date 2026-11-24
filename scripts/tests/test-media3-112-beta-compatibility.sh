#!/bin/sh
set -eu
SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
ROOT_DIR="${1:-$(CDPATH= cd -- "$SCRIPT_DIR/../.." && pwd)}"
printf 'NOTICE: the historical Media3 1.12 guard is superseded; delegating to the custom-AAR compatibility guard.\n'
exec sh "$SCRIPT_DIR/test-media3-custom-aar-compatibility.sh" "$ROOT_DIR"
