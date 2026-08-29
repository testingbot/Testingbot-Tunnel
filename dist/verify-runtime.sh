#!/usr/bin/env bash
#
# Verifies a jlink distribution built by build-runtime.sh.
#
# The module list in build-runtime.sh cannot be derived reliably from jdeps: the
# shaded jar is minimized and loads by reflection, so omissions surface only at
# runtime -- a missing jdk.crypto.ec fails the first HTTPS handshake, a missing
# java.management yields metrics with no JVM data, a missing java.xml breaks
# logback's config parsing. This runs the paths that depend on each of them, with
# the system Java removed from PATH so only the bundled runtime can satisfy them.
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DIST="${1:-}"
if [ -z "$DIST" ]; then
  DIST="$(ls -d "$HERE"/testingbot-tunnel-*-*/ 2>/dev/null | head -1)"
fi
[ -z "$DIST" ] && { echo "usage: verify-runtime.sh <dist-dir>"; exit 1; }
LAUNCHER="$DIST/bin/testingbot-tunnel"
[ -x "$LAUNCHER" ] || { echo "launcher not found: $LAUNCHER"; exit 1; }

WORK="$(mktemp -d "${TMPDIR:-/tmp}/tb-runtime.XXXXXX")"
PID=""

# A tunnel left running holds a slot against the account's concurrent-tunnel limit
# and will block the next run, so tear it down on every exit path -- including the
# early `exit 1`s above and Ctrl-C -- and escalate to SIGKILL if it ignores SIGTERM.
stop_tunnel() {
  [ -z "${PID:-}" ] && return 0
  kill -TERM "$PID" 2>/dev/null
  for _ in $(seq 1 45); do kill -0 "$PID" 2>/dev/null || break; sleep 1; done
  kill -0 "$PID" 2>/dev/null && kill -9 "$PID" 2>/dev/null
  wait "$PID" 2>/dev/null
  PID=""
}
cleanup() { stop_tunnel; rm -rf "$WORK"; }
trap cleanup EXIT INT TERM

PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); printf '  \033[32m✓\033[0m %s\n' "$1"; }
bad() { FAIL=$((FAIL+1)); printf '  \033[31m✗\033[0m %s — %s\n' "$1" "$2"; }

# Deliberately strip the environment so a system JDK cannot rescue a missing module.
run_isolated() { env -i HOME="$HOME" PATH=/usr/bin:/bin "$@"; }

echo "Verifying $(basename "$DIST")"

out="$(run_isolated "$LAUNCHER" --version 2>&1)"
case "$out" in *"Version:"*) ok "starts without a system JDK";; *) bad "starts" "$out";; esac

# --doctor performs HTTPS requests: exercises jdk.crypto.ec and java.naming.
out="$(run_isolated "$LAUNCHER" --doctor 2>&1)"
checks="$(printf '%s' "$out" | grep -c 'OK -')"
[ "$checks" -ge 8 ] && ok "TLS + DNS ($checks doctor checks passed)" \
                    || bad "TLS + DNS" "only $checks checks passed"
case "$out" in *Exception*|*SEVERE*) bad "doctor clean" "errors in output";; *) ok "doctor clean";; esac

if [ -z "${TESTINGBOT_KEY:-}" ] && [ ! -f "$HOME/.testingbot" ]; then
  echo "  - live tunnel checks skipped (no credentials)"
else
  MPORT="$(python3 -c 'import socket;s=socket.socket();s.bind(("127.0.0.1",0));print(s.getsockname()[1]);s.close()')"
  # Launch directly rather than through run_isolated: backgrounding a shell function
  # makes $! the wrapping subshell, and killing that orphans the JVM underneath it --
  # which then keeps holding a tunnel slot on the account.
  env -i HOME="$HOME" PATH=/usr/bin:/bin \
      "$LAUNCHER" --readyfile "$WORK/ready" --metrics-port "$MPORT" > "$WORK/tunnel.log" 2>&1 &
  PID=$!
  for _ in $(seq 1 120); do [ -f "$WORK/ready" ] && break; kill -0 $PID 2>/dev/null || break; sleep 1; done

  if [ ! -f "$WORK/ready" ]; then
    bad "tunnel starts" "$(tail -3 "$WORK/tunnel.log")"
  else
    ok "tunnel starts and reports ready"
    PROXY="$(grep -oE 'Local Proxy Port [0-9]+' "$WORK/tunnel.log" | grep -oE '[0-9]+' | tail -1)"

    code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 30 -x "127.0.0.1:$PROXY" http://example.com/)"
    [ "$code" = "200" ] && ok "HTTP proxying" || bad "HTTP proxying" "got $code"

    # The decisive TLS check: a full handshake through the bundled runtime.
    code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 30 -x "127.0.0.1:$PROXY" https://example.com/)"
    [ "$code" = "200" ] && ok "HTTPS CONNECT (TLS ciphers present)" || bad "HTTPS CONNECT" "got $code"

    body="$(curl -s --max-time 15 "http://127.0.0.1:$MPORT/metrics")"
    case "$body" in *testingbot_*) ok "Prometheus endpoint";; *) bad "Prometheus endpoint" "no testingbot_ metrics";; esac
    # jvm_/process_ series come from the hotspot collectors, which need java.management.
    case "$body" in *jvm_*|*process_cpu*) ok "JVM metrics (java.management present)";;
                    *) bad "JVM metrics" "hotspot collectors produced nothing";; esac

    # logback parses logback.xml at startup; a missing java.xml shows up here.
    case "$(cat "$WORK/tunnel.log")" in
      *"logback"*"ERROR"*|*"SAXParser"*|*"ClassNotFoundException"*)
        bad "logback config" "parser errors in log";;
      *) ok "logback config parsed";;
    esac

    stop_tunnel
    ok "clean shutdown"
  fi
fi

echo
printf '  passed: %s  failed: %s\n' "$PASS" "$FAIL"
[ "$FAIL" -eq 0 ]
