#!/usr/bin/env bash
#
# End-to-end test harness for TestingBot Tunnel.
#
# Each scenario boots the real tunnel with a different set of CLI arguments,
# then asserts against it: proxy-level checks over curl (cheap) and, where it
# adds coverage, a real Selenium session driving a remote browser back through
# the tunnel to a local origin server (costs TestingBot minutes).
#
# TestingBot rate-limits how many tunnels an account may start per hour and how
# many may run at once, and explicitly asks callers to "keep a tunnel running and
# reuse it across tests". So the default run uses ONE tunnel configured to
# exercise as many features as possible at once; scenarios that need genuinely
# different CLI arguments each cost another tunnel start and are opt-in.
#
# Usage:
#   e2e/run-e2e.sh                      # doctor + combined (2 tunnel starts)
#   e2e/run-e2e.sh --all                # every scenario, paced (7 starts)
#   e2e/run-e2e.sh nobump custom_ports  # named scenarios only
#   e2e/run-e2e.sh --list               # show scenarios and their tunnel cost
#   E2E_SKIP_BROWSER=1 e2e/run-e2e.sh   # proxy-level checks only, no browser VMs
#
# Env: E2E_PACE_SECONDS (default 15) sleep between tunnel starts
#      E2E_BROWSER / E2E_BROWSER_VERSION / E2E_PLATFORM
#      E2E_KEEP_LOGS=1 to retain tunnel logs
#
# Credentials come from TESTINGBOT_KEY/TESTINGBOT_SECRET or ~/.testingbot.

set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
# shellcheck source=e2e/webdriver.sh
source "$HERE/webdriver.sh"

JAR="$(ls "$ROOT"/target/TestingBotTunnel-*-shaded.jar 2>/dev/null | head -1)"
[ -z "$JAR" ] && { echo "No shaded jar found. Run: mvn package"; exit 1; }

WORK="$(mktemp -d "${TMPDIR:-/tmp}/tb-e2e.XXXXXX")"
MARKER="TB-E2E-$(date +%s)-$$"
# Pick a free port unless one was pinned, so a stale server from an earlier run
# cannot be mistaken for ours.
ORIGIN_PORT="${E2E_ORIGIN_PORT:-$(python3 -c 'import socket;s=socket.socket();s.bind(("127.0.0.1",0));print(s.getsockname()[1]);s.close()')}"
SKIP_BROWSER="${E2E_SKIP_BROWSER:-0}"
export E2E_BUILD="${E2E_BUILD:-tunnel-e2e-$(date +%Y%m%d-%H%M%S)}"

PASS=0; FAIL=0; SKIP=0
declare -a RESULTS

# ---------------------------------------------------------------- assertions
ok()   { PASS=$((PASS+1)); RESULTS+=("PASS|$1|$2"); printf '    \033[32m✓\033[0m %s — %s\n' "$1" "$2"; }
bad()  { FAIL=$((FAIL+1)); RESULTS+=("FAIL|$1|$2"); printf '    \033[31m✗\033[0m %s — %s\n' "$1" "$2"; }
skip() { SKIP=$((SKIP+1)); RESULTS+=("SKIP|$1|$2"); printf '    \033[33m-\033[0m %s — %s\n' "$1" "$2"; }

assert_eq()       { [ "$2" = "$3" ] && ok "$1" "got $2" || bad "$1" "expected $3, got $2"; }
assert_contains() { case "$2" in *"$3"*) ok "$1" "contains '$3'";; *) bad "$1" "missing '$3' in: $(printf '%.120s' "$2")";; esac; }

# Returns a port free on both the IPv4 loopback and the wildcard address.
# Checking only the wildcard is not enough: a process bound to 127.0.0.1:PORT can
# coexist with a Jetty bound to [::]:PORT on macOS, and the tunnel's reverse
# forward dials 127.0.0.1 -- so traffic would silently reach the other process.
free_port() {
  python3 - <<'PY'
import socket
for _ in range(200):
    probe = socket.socket()
    probe.bind(("127.0.0.1", 0))
    port = probe.getsockname()[1]
    probe.close()
    ok = True
    for host in ("127.0.0.1", "0.0.0.0"):
        s = socket.socket()
        s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        try:
            s.bind((host, port))
        except OSError:
            ok = False
        finally:
            s.close()
        if not ok:
            break
    if ok:
        print(port)
        break
PY
}

# Fails loudly if anything other than our tunnel is listening on the proxy port.
# Without this, a foreign listener on 127.0.0.1:PORT looks like a working proxy
# to local curl while the tunnel's reverse forward is quietly hijacked.
assert_proxy_port_ours() {
  local owners
  owners="$(lsof -nP -iTCP:"$PROXY_PORT" -sTCP:LISTEN 2>/dev/null | awk 'NR>1 {print $2}' | sort -u | tr '\n' ' ')"
  case " $owners " in
    *" $TUNNEL_PID "*)
      if [ "$(printf '%s' "$owners" | wc -w | tr -d ' ')" = "1" ]; then
        ok "$1 proxy-port-exclusive" "pid $TUNNEL_PID owns $PROXY_PORT"
      else
        bad "$1 proxy-port-exclusive" "port $PROXY_PORT shared with pid(s): $owners"
      fi;;
    *) bad "$1 proxy-port-exclusive" "port $PROXY_PORT owned by pid(s) [$owners], not our tunnel ($TUNNEL_PID)";;
  esac
}

# ------------------------------------------------------------------ fixtures
start_origin() {
  python3 "$HERE/origin_server.py" "$ORIGIN_PORT" "$MARKER" > "$WORK/origin.log" 2>&1 &
  ORIGIN_PID=$!
  for _ in $(seq 1 40); do
    # Match on our own marker: proves we are talking to this run's server and
    # not something else that happens to hold the port.
    if curl -sf --max-time 5 "http://127.0.0.1:$ORIGIN_PORT/" 2>/dev/null | grep -q "$MARKER"; then
      return 0
    fi
    kill -0 "$ORIGIN_PID" 2>/dev/null || { echo "origin server died:"; cat "$WORK/origin.log"; return 1; }
    sleep 0.25
  done
  echo "origin server failed to start on port $ORIGIN_PORT"; cat "$WORK/origin.log"; return 1
}
stop_origin() {
  [ -z "${ORIGIN_PID:-}" ] && return 0
  kill "$ORIGIN_PID" 2>/dev/null
  wait "$ORIGIN_PID" 2>/dev/null
  ORIGIN_PID=""
}

TUNNEL_PID=""; PROXY_PORT=""; TUNNEL_LOG=""; READY_FILE=""
# Returns 0 (and prints an explanation) when the log shows a known fatal error.
classify_tunnel_failure() {
  [ -f "$TUNNEL_LOG" ] || return 1
  if grep -qE "tunnels active|Too many tunnels" "$TUNNEL_LOG" 2>/dev/null; then
    echo "    tunnel quota reached:"
    grep -m1 -E "tunnels active|Too many tunnels" "$TUNNEL_LOG" | sed 's/^/    | /'
    echo "    | active: curl -u KEY:SECRET https://api.testingbot.com/v1/tunnel/list"
    echo "    | tip: run fewer scenarios, or raise E2E_PACE_SECONDS"
    QUOTA_HIT=1
    return 0
  fi
  if grep -q "Address already in use" "$TUNNEL_LOG" 2>/dev/null; then
    echo "    port conflict — something else holds the port:"
    grep -m1 -B2 "Address already in use" "$TUNNEL_LOG" | sed 's/^/    | /'
    return 0
  fi
  if grep -q "401 Unauthorized" "$TUNNEL_LOG" 2>/dev/null; then
    echo "    credentials rejected (401) — check TESTINGBOT_KEY/SECRET or ~/.testingbot"
    return 0
  fi
  return 1
}

TUNNEL_STARTS=0
start_tunnel() {  # "$@" = tunnel args
  local n=$RANDOM
  # Pace starts: the account is rate-limited per hour on tunnel creation.
  if [ "$TUNNEL_STARTS" -gt 0 ] && [ "${E2E_PACE_SECONDS:-15}" -gt 0 ]; then
    printf '    (pacing %ss before next tunnel)\n' "${E2E_PACE_SECONDS:-15}"
    sleep "${E2E_PACE_SECONDS:-15}"
  fi
  TUNNEL_STARTS=$((TUNNEL_STARTS+1))
  TUNNEL_LOG="$WORK/tunnel-$n.log"
  READY_FILE="$WORK/ready-$n"
  rm -f "$READY_FILE"
  # --readyfile is the tunnel's own "I am up" signal. Grepping the log for
  # "You may start your tests" is wrong: that phrase also appears in the boot
  # message that explains what to wait for, so it matches immediately.
  java -jar "$JAR" --readyfile "$READY_FILE" "$@" > "$TUNNEL_LOG" 2>&1 &
  TUNNEL_PID=$!
  for _ in $(seq 1 120); do
    [ -f "$READY_FILE" ] && break
    # Classify known fatal errors before the liveness check: the tunnel exits
    # immediately on these, so checking liveness first would report only a
    # generic "exited early".
    if classify_tunnel_failure; then return 1; fi
    if ! kill -0 "$TUNNEL_PID" 2>/dev/null; then
      echo "    tunnel exited early:"; tail -12 "$TUNNEL_LOG" | sed 's/^/    | /'; return 1
    fi
    sleep 1
  done
  classify_tunnel_failure && return 1
  [ -f "$READY_FILE" ] || { echo "    tunnel never became ready:"; tail -12 "$TUNNEL_LOG" | sed 's/^/    | /'; return 1; }
  PROXY_PORT="$(grep -oE 'Local Proxy Port [0-9]+' "$TUNNEL_LOG" | grep -oE '[0-9]+' | tail -1)"
  printf '    tunnel up (pid %s, local proxy %s)\n' "$TUNNEL_PID" "${PROXY_PORT:-?}"
  return 0
}
stop_tunnel() {
  [ -z "${TUNNEL_PID:-}" ] && return 0
  kill -TERM "$TUNNEL_PID" 2>/dev/null
  for _ in $(seq 1 45); do kill -0 "$TUNNEL_PID" 2>/dev/null || break; sleep 1; done
  kill -0 "$TUNNEL_PID" 2>/dev/null && kill -9 "$TUNNEL_PID" 2>/dev/null
  wait "$TUNNEL_PID" 2>/dev/null
  TUNNEL_PID=""
  # The process is gone, but its listening sockets may take a moment to be
  # released. Starting the next scenario too eagerly fails with BindException.
  wait_ports_free 4445 "${PROXY_PORT:-}"
  PROXY_PORT=""
}

wait_ports_free() {
  local p
  for p in "$@"; do
    [ -z "$p" ] && continue
    for _ in $(seq 1 60); do
      python3 - "$p" <<'PY' && break
import socket, sys
s = socket.socket()
s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
try:
    s.bind(("0.0.0.0", int(sys.argv[1])))
except OSError:
    sys.exit(1)
finally:
    s.close()
PY
      sleep 0.5
    done
  done
}

# --------------------------------------------------------------- shared checks
check_proxy_http()    { assert_eq "$1 http-via-proxy"  "$(curl -s -o /dev/null -w '%{http_code}' --max-time 30 -x "127.0.0.1:$PROXY_PORT" http://example.com/)" "200"; }
check_proxy_connect() { assert_eq "$1 https-connect"   "$(curl -s -o /dev/null -w '%{http_code}' --max-time 30 -x "127.0.0.1:$PROXY_PORT" https://example.com/)" "200"; }
check_se_status()     { assert_contains "$1 se-hub-status" "$(curl -s --max-time 30 "http://127.0.0.1:${2:-4445}/wd/hub/status")" '"ready":true'; }

# The real end-to-end proof: a remote browser loads a page only this machine serves.
check_browser() {  # $1 label, $2 se-port, $3 optional tunnel identifier
  local label="$1" seport="${2:-4445}" ident="${3:-}" sid src
  if [ "$SKIP_BROWSER" = "1" ]; then skip "$label browser-e2e" "E2E_SKIP_BROWSER=1"; return; fi
  sid="$(wd_new_session "$seport" "$label" "$ident")"
  if [ -z "$sid" ]; then bad "$label browser-session" "could not create session"; return; fi
  ok "$label browser-session" "${sid:0:24}…"
  assert_eq "$label browser-navigate" "$(wd_goto "$seport" "$sid" "http://localhost:$ORIGIN_PORT/")" "200"
  src="$(wd_source "$seport" "$sid")"
  case "$src" in
    *"$MARKER"*) ok "$label browser-sees-local-origin" "marker present";;
    *) printf '%s\n' "$src" > "$WORK/$label-page.html"
       bad "$label browser-sees-local-origin" "marker missing; page saved to $WORK/$label-page.html"
       printf '    page text: %.400s\n' "$(printf '%s' "$src" | tr -d '\n' | sed 's/<[^>]*>//g')";;
  esac
  wd_delete "$seport" "$sid"
}

# ------------------------------------------------------------------ scenarios
# Cost annotation: each scenario that calls start_tunnel consumes one tunnel
# start against the hourly account quota.

# One tunnel, many features. This is the default because it gets the broadest
# coverage for a single tunnel start.
scenario_combined() {
  local mport; mport="$(free_port)"
  start_tunnel \
      --extra-headers '{"X-E2E-Injected":"tunnel-e2e-value"}' \
      --fast-fail-regexps 'blocked\.example\.com' \
      --metrics-port "$mport" --metrics-auth 'e2euser:e2epass' || return 1

  assert_proxy_port_ours combined
  check_proxy_http combined
  check_proxy_connect combined
  check_se_status combined

  assert_contains "extra-headers injected" \
    "$(curl -s --max-time 30 -x "127.0.0.1:$PROXY_PORT" "http://127.0.0.1:$ORIGIN_PORT/headers")" \
    "tunnel-e2e-value"

  # %{http_connect} is the proxy's CONNECT status. %{http_code} would be 000
  # here because a refused CONNECT means no TLS tunnel and no HTTP response.
  assert_eq "fast-fail blocks CONNECT" \
    "$(curl -s -o /dev/null -w '%{http_connect}' --max-time 30 -x "127.0.0.1:$PROXY_PORT" https://blocked.example.com/)" "403"

  assert_eq "metrics rejects anonymous" \
    "$(curl -s -o /dev/null -w '%{http_code}' --max-time 15 "http://127.0.0.1:$mport/metrics")" "401"
  assert_eq "metrics accepts basic auth" \
    "$(curl -s -o /dev/null -w '%{http_code}' --max-time 15 -u e2euser:e2epass "http://127.0.0.1:$mport/metrics")" "200"

  check_browser combined
}

scenario_nobump() {
  start_tunnel --nobump || return 1
  check_proxy_http nobump; check_proxy_connect nobump
  check_browser nobump
}

scenario_nocache() {
  start_tunnel --nocache || return 1
  check_proxy_http nocache; check_proxy_connect nocache
}

scenario_custom_ports() {
  local seport lport mport
  seport="$(free_port)"; lport="$(free_port)"; mport="$(free_port)"
  start_tunnel --se-port "$seport" --localproxy "$lport" --metrics-port "$mport" || return 1
  assert_eq "custom localproxy port" "$PROXY_PORT" "$lport"
  assert_proxy_port_ours custom-ports
  check_proxy_http custom-ports
  check_se_status custom-ports "$seport"
  assert_contains "custom metrics port" "$(curl -s --max-time 15 "http://127.0.0.1:$mport/")" '"version"'
  check_browser custom-ports "$seport"
}

# Isolates --localproxy from the other port flags. A non-default local proxy port
# has been seen to break the reverse leg (remote -> tunnel -> local proxy) while
# the proxy still serves local requests fine.
scenario_localproxy_only() {
  local lport; lport="$(free_port)"
  start_tunnel --localproxy "$lport" || return 1
  assert_eq "localproxy port" "$PROXY_PORT" "$lport"
  assert_proxy_port_ours localproxy-only
  check_proxy_http localproxy-only
  check_browser localproxy-only
}

scenario_tunnel_identifier() {
  start_tunnel --tunnel-identifier "e2e-$$" || return 1
  check_proxy_http tunnel-identifier
  check_browser tunnel-identifier 4445 "e2e-$$"
}

scenario_upstream_proxy() {
  # A real upstream proxy in front of the tunnel exercises CustomConnectHandler's
  # hand-rolled CONNECT path and TunnelProxyServlet's ProxyConfiguration wiring.
  local uport; uport="$(free_port)"
  python3 "$HERE/upstream_proxy.py" "$uport" > "$WORK/upstream.log" 2>&1 &
  local up=$!
  sleep 1
  start_tunnel --proxy "127.0.0.1:$uport" || { kill $up 2>/dev/null; return 1; }
  check_proxy_http upstream-proxy
  check_proxy_connect upstream-proxy
  assert_contains "upstream proxy saw traffic" "$(cat "$WORK/upstream.log")" "CONNECT"
  kill $up 2>/dev/null
}

# Costs no tunnel: --doctor runs diagnostics and exits.
scenario_doctor() {
  local out; out="$(java -jar "$JAR" --doctor 2>&1)"
  assert_contains "doctor reaches API"  "$out" "api.testingbot.com"
  assert_contains "doctor checks ports" "$out" "Selenium port"
  case "$out" in *"FAIL"*|*"SEVERE"*) bad "doctor clean" "reported failures";; *) ok "doctor clean" "no failures";; esac
}

ALL_SCENARIOS=(doctor combined nobump nocache custom_ports localproxy_only tunnel_identifier upstream_proxy)
DEFAULT_SCENARIOS=(doctor combined)
# macOS ships bash 3.2, which has no associative arrays -- use a function.
tunnel_cost() { case "$1" in doctor) echo 0;; *) echo 1;; esac; }

# ---------------------------------------------------------------------- main
cleanup() {
  stop_tunnel; stop_origin
  if [ "${E2E_KEEP_LOGS:-0}" = "1" ]; then echo "logs kept in $WORK"; else rm -rf "$WORK"; fi
}
trap cleanup EXIT INT TERM

if [ -z "${TESTINGBOT_KEY:-}" ] && [ ! -f "$HOME/.testingbot" ]; then
  echo "No credentials: set TESTINGBOT_KEY/TESTINGBOT_SECRET or create ~/.testingbot"; exit 1
fi

QUOTA_HIT=0
case "${1:-}" in
  --list)
    echo "scenario            tunnel starts"
    for x in "${ALL_SCENARIOS[@]}"; do printf '  %-18s %s\n' "$x" "$(tunnel_cost "$x")"; done
    echo; echo "default: ${DEFAULT_SCENARIOS[*]}"
    exit 0;;
  --all) REQUESTED=("${ALL_SCENARIOS[@]}"); shift;;
  "")    REQUESTED=("${DEFAULT_SCENARIOS[@]}");;
  *)     REQUESTED=("$@");;
esac

echo "TestingBot Tunnel E2E"
echo "  jar:     $(basename "$JAR")"
echo "  java:    $(java -version 2>&1 | head -1)"
echo "  marker:  $MARKER"
echo "  origin:  http://localhost:$ORIGIN_PORT/"
echo "  browser: $([ "$SKIP_BROWSER" = 1 ] && echo 'skipped (E2E_SKIP_BROWSER=1)' || echo "${E2E_BROWSER:-chrome} ${E2E_BROWSER_VERSION:-150} / ${E2E_PLATFORM:-LINUX}")"
echo

if pgrep -f "TestingBotTunnel-.*-shaded.jar" > /dev/null 2>&1; then
  echo "A tunnel is already running; stop it first (pkill -f TestingBotTunnel).}"; exit 1
fi

start_origin || exit 1

for s in "${REQUESTED[@]}"; do
  s="${s//-/_}"
  if ! declare -F "scenario_$s" > /dev/null; then
    echo "  ? unknown scenario: $s"; continue
  fi
  printf '\033[1m▶ %s\033[0m\n' "$s"
  started=$SECONDS
  before_fail=$FAIL
  "scenario_$s" || bad "$s" "scenario setup failed"
  if [ "$FAIL" -gt "$before_fail" ] && [ -n "$TUNNEL_LOG" ] && [ -f "$TUNNEL_LOG" ]; then
    echo "    ---- tunnel log (tail) ----"
    tail -15 "$TUNNEL_LOG" | sed 's/^/    | /'
    echo "    ---------------------------"
  fi
  stop_tunnel
  printf '  (%ss)\n\n' "$((SECONDS-started))"
done

echo "──────────────────────────────────────────"
printf '  passed: %s   failed: %s   skipped: %s   tunnels started: %s\n' "$PASS" "$FAIL" "$SKIP" "$TUNNEL_STARTS"
[ "$QUOTA_HIT" = "1" ] && echo "  note: account tunnel quota was hit; some failures are environmental"
echo "──────────────────────────────────────────"
if [ "$FAIL" -gt 0 ]; then
  echo "Failures:"
  for r in "${RESULTS[@]}"; do
    case "$r" in FAIL*) echo "  - ${r#FAIL|}";; esac
  done
  exit 1
fi
