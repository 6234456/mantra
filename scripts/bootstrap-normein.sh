#!/usr/bin/env bash
# Creates the pinned Normein checkout used by the composite build (.deps/normein).
# NORMEIN_SOURCE may point to a local clone or a remote URL (default: git@github.com:6234456/normein.git).
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
commit="$(sed -n 's/^normeinCommit=//p' "$root/normein-build.lock")"
source="${NORMEIN_SOURCE:-git@github.com:6234456/normein.git}"
target="$root/.deps/normein"
if [ ! -d "$target/.git" ]; then
  mkdir -p "$root/.deps"
  git clone --no-checkout "$source" "$target"
fi
git -C "$target" fetch --quiet origin "$commit" 2>/dev/null || true
git -C "$target" checkout --quiet --detach "$commit"
echo "Normein pinned at $(git -C "$target" rev-parse HEAD) in $target"
