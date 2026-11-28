#!/usr/bin/env bash
# Copy the DeltaGram patches into a telegram-tt checkout.
#
# Usage: ./apply.sh /path/to/telegram-tt
set -euo pipefail

TARGET=${1:-}
if [[ -z "$TARGET" || ! -d "$TARGET/src" ]]; then
  echo "usage: $0 /path/to/telegram-tt" >&2
  exit 1
fi

here=$(cd "$(dirname "$0")" && pwd)

install -D -m 644 "$here/lib/gramjs/crypto/RSA.ts" "$TARGET/src/lib/gramjs/crypto/RSA.ts"
install -D -m 644 "$here/lib/gramjs/extensions/PromisedWebSockets.ts" "$TARGET/src/lib/gramjs/extensions/PromisedWebSockets.ts"
install -D -m 644 "$here/global/initialState.ts" "$TARGET/src/global/initialState.ts"

echo "applied DeltaGram web patches to $TARGET"
