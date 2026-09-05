#!/usr/bin/env bash
#
# Packs a built bundle directory and writes its SHA-256.
#
# Separate from build-runtime.sh because on macOS the bundle is signed between
# being built and being packed: codesign rewrites every Mach-O in place, so an
# archive made before signing describes files that no longer exist. Both paths
# call this, so a signed and an unsigned release are packed and checksummed by
# the same code.
#
# Usage: dist/archive.sh <bundle-directory-name>       (relative to dist/)
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
NAME="${1:?usage: archive.sh <bundle-directory-name>}"
NAME="$(basename "$NAME")"

cd "$ROOT/dist"
[ -d "$NAME" ] || { echo "no such bundle: dist/$NAME" >&2; exit 1; }

# SHA-256 in the "HASH  FILE" form `sha256sum -c` reads back. The three tools
# available across the build platforms do not agree on output: sha256sum and
# `shasum -a 256` print "HASH  FILE", `openssl dgst -r` prints "HASH *FILE",
# so the last is rewritten. The path stays relative so the check works from
# whatever directory a user downloaded into.
checksum() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" > "$1.sha256"
  elif command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$1" > "$1.sha256"
  elif command -v openssl >/dev/null 2>&1; then
    openssl dgst -sha256 -r "$1" | sed 's/ \*/  /' > "$1.sha256"
  else
    echo "  !! no sha256 tool found; $1.sha256 not written" >&2
    return 1
  fi
  echo "  -> dist/$1.sha256  $(cut -d' ' -f1 < "$1.sha256")"
}

case "$NAME" in
  *windows*)
    rm -f "$NAME.zip"
    zip -qr "$NAME.zip" "$NAME"
    echo "  -> dist/$NAME.zip"
    checksum "$NAME.zip"
    ;;
  *)
    rm -f "$NAME.tar.gz"
    tar czf "$NAME.tar.gz" "$NAME"
    echo "  -> dist/$NAME.tar.gz"
    checksum "$NAME.tar.gz"
    ;;
esac
