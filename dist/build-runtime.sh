#!/usr/bin/env bash
#
# Builds a self-contained TestingBot Tunnel distribution: a trimmed JDK runtime
# (jlink) plus the shaded jar and a launcher, so users need no Java installed.
#
#   mvn package && dist/build-runtime.sh
#
# Produces dist/testingbot-tunnel-<os>-<arch>/ and a matching .tar.gz (.zip on
# Windows). Run it on the platform you are targeting: jlink emits a runtime for
# the JDK it runs on.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 17 2>/dev/null || dirname "$(dirname "$(readlink -f "$(command -v java)")")")}"

JAR="$(ls "$ROOT"/target/TestingBotTunnel-*-shaded.jar 2>/dev/null | head -1)"
[ -z "$JAR" ] && { echo "No shaded jar. Run: mvn package"; exit 1; }

case "$(uname -s)" in
  Darwin) OS=macos;;
  Linux)  OS=linux;;
  MINGW*|MSYS*|CYGWIN*) OS=windows;;
  *) OS="$(uname -s | tr '[:upper:]' '[:lower:]')";;
esac
case "$(uname -m)" in
  x86_64|amd64) ARCH=x64;;
  arm64|aarch64) ARCH=arm64;;
  *) ARCH="$(uname -m)";;
esac

NAME="testingbot-tunnel-${OS}-${ARCH}"
OUT="$ROOT/dist/$NAME"
rm -rf "$OUT"

# The module set is curated rather than taken from `jdeps --print-module-deps`.
# The shaded jar is minimized and loads plenty by reflection, so jdeps under-reports:
# it omits java.logging despite the code using java.util.logging throughout, and it
# cannot see the JCE providers TLS needs. Getting this wrong fails at runtime, not
# build time -- typically as a handshake error on the first HTTPS request -- so the
# list errs wide and verify-runtime.sh exercises the paths that depend on it.
MODULES=(
  java.base
  java.logging          # java.util.logging, used throughout
  java.xml              # logback parses logback.xml
  java.naming           # DNS/JNDI lookups
  java.management       # Prometheus hotspot collectors (JVM metrics)
  jdk.management
  java.security.jgss    # SPNEGO/Kerberos auth against an upstream proxy
  jdk.security.auth     # Krb5LoginModule, for --krb5-keytab login
  java.security.sasl
  java.sql              # logback references it
  jdk.crypto.ec         # TLS elliptic-curve ciphers -- HTTPS fails without it
  jdk.unsupported       # sun.misc.Unsafe, used by several dependencies
  jdk.net
  jdk.zipfs
)

# jlink's compression flag changed spelling in JDK 21 (--compress=2 -> zip-N).
JDK_MAJOR="$("$JAVA_HOME/bin/java" -version 2>&1 | head -1 | sed -E 's/.*"([0-9]+).*/\1/')"
if [ "${JDK_MAJOR:-17}" -ge 21 ]; then
  COMPRESS="--compress=zip-6"
else
  COMPRESS="--compress=2"
fi

echo "Building $NAME"
echo "  JDK: $("$JAVA_HOME/bin/java" -version 2>&1 | head -1)"

"$JAVA_HOME/bin/jlink" \
  --add-modules "$(IFS=, ; echo "${MODULES[*]}")" \
  --strip-debug --no-header-files --no-man-pages "$COMPRESS" \
  --output "$OUT/runtime"

mkdir -p "$OUT/lib" "$OUT/bin"
cp "$JAR" "$OUT/lib/testingbot-tunnel.jar"

if [ "$OS" = "windows" ]; then
  cat > "$OUT/bin/testingbot-tunnel.cmd" <<'CMD'
@echo off
set DIR=%~dp0
"%DIR%..\runtime\bin\java.exe" -jar "%DIR%..\lib\testingbot-tunnel.jar" %*
CMD
else
  cat > "$OUT/bin/testingbot-tunnel" <<'LAUNCH'
#!/usr/bin/env bash
# Resolve symlinks so the launcher works from a PATH entry.
SOURCE="${BASH_SOURCE[0]}"
while [ -L "$SOURCE" ]; do
  DIR="$(cd -P "$(dirname "$SOURCE")" && pwd)"
  SOURCE="$(readlink "$SOURCE")"
  [[ $SOURCE != /* ]] && SOURCE="$DIR/$SOURCE"
done
DIR="$(cd -P "$(dirname "$SOURCE")/.." && pwd)"
exec "$DIR/runtime/bin/java" -jar "$DIR/lib/testingbot-tunnel.jar" "$@"
LAUNCH
  chmod +x "$OUT/bin/testingbot-tunnel"
fi

cd "$ROOT/dist"
if [ "$OS" = "windows" ]; then
  zip -qr "$NAME.zip" "$NAME"
  echo "  -> dist/$NAME.zip"
else
  tar czf "$NAME.tar.gz" "$NAME"
  echo "  -> dist/$NAME.tar.gz"
fi
du -sh "$NAME" | awk '{print "  unpacked: "$1}'
