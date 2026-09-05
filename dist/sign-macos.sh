#!/usr/bin/env bash
#
# Signs, notarizes and repacks a macOS bundle built by build-runtime.sh.
#
# Without this, a bundle downloaded from a release is quarantined: macOS reports
# "cannot be opened because the developer cannot be verified" and the tunnel does
# not start. That is a poor first impression for a tool whose job is security.
#
# Why every binary and not just the launcher: the bundle is a jlink runtime, a
# directory of Mach-O files -- bin/java, jspawnhelper, and thirty-odd dylibs. There
# is no enclosing .app whose signature would cover them, so each is signed on its
# own. Deepest first, because signing a binary invalidates any signature of
# something that contains it.
#
# Usage: dist/sign-macos.sh <bundle-directory-name> [notary-keychain-profile]
#
# Expects an unlocked keychain holding a "Developer ID Application" identity, and
# for notarization a stored notarytool profile (default: testingbot-notary).
# Set SKIP_NOTARIZE=1 to sign only, which is useful locally where the credentials
# are not present.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
NAME="$(basename "${1:?usage: sign-macos.sh <bundle-directory-name> [notary-profile]}")"
NOTARY_PROFILE="${2:-testingbot-notary}"
BUNDLE="$ROOT/dist/$NAME"
ENTITLEMENTS="$ROOT/dist/macos-entitlements.plist"

[ -d "$BUNDLE" ] || { echo "no such bundle: dist/$NAME" >&2; exit 1; }
[ -f "$ENTITLEMENTS" ] || { echo "missing $ENTITLEMENTS" >&2; exit 1; }

# The identity is looked up rather than passed in, so the workflow does not have to
# know the certificate's common name -- it changes when the certificate is renewed.
IDENTITY="$(security find-identity -v -p codesigning \
            | grep 'Developer ID Application' | head -1 \
            | sed -E 's/.*\) ([A-F0-9]{40}) ".*/\1/')"
[ -n "$IDENTITY" ] || { echo "no Developer ID Application identity in the keychain" >&2; exit 1; }
echo "Signing $NAME with $IDENTITY"

# Every Mach-O in the bundle, deepest path first. `file` is the test rather than the
# executable bit: the runtime's dylibs are not executable, and shell scripts are.
# Written to a file rather than an array: macOS ships bash 3.2, which has no
# mapfile, and the runners use it.
BINARY_LIST="$(mktemp)"
trap 'rm -f "$BINARY_LIST"' EXIT
find "$BUNDLE" -type f -print0 \
  | xargs -0 file --mime-type \
  | grep -E ':[[:space:]]*application/x-mach-binary' \
  | sed 's/:[[:space:]]*application\/x-mach-binary$//' \
  | awk '{ print length($0)"\t"$0 }' | sort -rn | cut -f2- > "$BINARY_LIST"

COUNT="$(wc -l < "$BINARY_LIST" | tr -d ' ')"
[ "$COUNT" -gt 0 ] || { echo "no Mach-O files found under dist/$NAME" >&2; exit 1; }
echo "  $COUNT Mach-O files to sign"

while IFS= read -r binary; do
  codesign --force --timestamp --options runtime \
           --entitlements "$ENTITLEMENTS" \
           --sign "$IDENTITY" "$binary"
done < "$BINARY_LIST"

# Verify before spending a notarization round trip on a bundle that cannot pass.
while IFS= read -r binary; do
  codesign --verify --strict "$binary"
done < "$BINARY_LIST"
echo "  all binaries signed and verified"

if [ "${SKIP_NOTARIZE:-}" = "1" ]; then
  echo "  SKIP_NOTARIZE=1, not submitting"
else
  # Notarization takes a zip whatever the release format is; it is a transport for
  # the submission, not the artifact users download.
  SUBMISSION="$ROOT/dist/$NAME-notarize.zip"
  rm -f "$SUBMISSION"
  ditto -c -k --keepParent "$BUNDLE" "$SUBMISSION"

  echo "  submitting to notarytool (profile: $NOTARY_PROFILE)"
  xcrun notarytool submit "$SUBMISSION" \
    --keychain-profile "$NOTARY_PROFILE" --wait --timeout 30m
  rm -f "$SUBMISSION"

  # Deliberately no `stapler staple`. It only writes tickets into containers that
  # have somewhere to put one -- .app, .dmg, .pkg, .kext -- and this ships as a
  # tarball of plain binaries. Gatekeeper resolves the ticket from Apple on first
  # run instead, which works but does need the machine to be online once. Shipping
  # a .pkg as well is the way to make it offline-proof, and is a separate job.
  echo "  notarized (ticket served online; a tarball cannot be stapled)"
fi

# Repack: codesign rewrote every binary, so the archive built before signing and its
# checksum both describe files that no longer exist.
"$ROOT/dist/archive.sh" "$NAME"
