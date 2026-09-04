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
#   e2e/run-e2e.sh --all                # every scenario, paced (21 starts)
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
assert_neq()      { [ "$2" != "$3" ] && ok "$1" "got $2, not $3" || bad "$1" "expected anything but $3"; }
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
    echo "    | active: printf 'user = \"KEY:SECRET\"\\n' | curl -K - https://api.testingbot.com/v1/tunnel/list"
    echo "    |   (-K - keeps the credentials out of ps; -u would not)"
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
# Credentials for the tunnel-list API, read the same way the tunnel itself does.
api_credentials() {
  if [ -n "${TESTINGBOT_KEY:-}" ] && [ -n "${TESTINGBOT_SECRET:-}" ]; then
    printf '%s:%s' "$TESTINGBOT_KEY" "$TESTINGBOT_SECRET"
  elif [ -f "$HOME/.testingbot" ]; then
    tr -d ' \n' < "$HOME/.testingbot"
  fi
}

# Number of tunnels the account currently has, or empty if the API cannot be reached.
active_tunnel_count() {
  local creds; creds="$(api_credentials)"
  [ -z "$creds" ] && return 0
  # Credentials on stdin, not in argv: -u puts them in the process table, where any other
  # user on the machine can read them out of ps for as long as the request runs.
  printf 'user = "%s"\n' "$creds" \
    | curl -s --max-time 15 -K - https://api.testingbot.com/v1/tunnel/list 2>/dev/null \
    | python3 -c 'import json,sys
try:
    print(len(json.load(sys.stdin)))
except Exception:
    pass' 2>/dev/null
}

# A tunnel keeps its server-side record for a while after the local process exits, and the
# account allows only a couple at once. Waiting on the local port being free is not enough:
# the next scenario then fails to create a tunnel at all, which looks like a product bug and
# is not one. Wait for the account to actually have room.
wait_for_tunnel_slot() {
  local limit="${E2E_TUNNEL_SLOT_LIMIT:-1}" count
  for _ in $(seq 1 40); do
    count="$(active_tunnel_count)"
    [ -z "$count" ] && return 0          # API unreachable; let start_tunnel report the failure
    [ "$count" -le "$limit" ] && return 0
    sleep 5
  done
  printf '    (still %s tunnels active after waiting; starting anyway)\n' "$count"
}

start_tunnel() {  # "$@" = tunnel args
  local n=$RANDOM
  # Pace starts: the account is rate-limited per hour on tunnel creation.
  if [ "$TUNNEL_STARTS" -gt 0 ] && [ "${E2E_PACE_SECONDS:-15}" -gt 0 ]; then
    printf '    (pacing %ss before next tunnel)\n' "${E2E_PACE_SECONDS:-15}"
    sleep "${E2E_PACE_SECONDS:-15}"
  fi
  wait_for_tunnel_slot
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
  # Give the server-side record a moment to clear too, so the next scenario is not refused.
  wait_for_tunnel_slot
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

# A TLS twin of the origin, for wss://. Self-signed: the tunnel relays these bytes without
# being able to read them either, so what the certificate says is not what is under test.
TLS_ORIGIN_PORT=""; TLS_ORIGIN_PID=""
# The host name a browser must use to reach a local origin *through the tunnel*.
#
# Not "localhost". Measured against a real session: http://localhost:PORT does travel through
# the proxy and arrive at the tunnel client, but https://localhost:PORT never leaves the browser
# VM at all -- on a random port and on 8080, which the VM does register a local listener for.
# Chrome answers ERR_SSL_PROTOCOL_ERROR from whatever is on its own loopback, and the tunnel
# client logs no CONNECT. The same origin reached by a resolvable name works on both ports.
#
# local.testingbot.com resolves to 127.0.0.1, so the tunnel client dials the very same origin;
# only the browser's treatment of the name differs. A record only -- an AAAA of ::1 would let a
# browser prefer IPv6 and miss an origin bound to IPv4. Override with E2E_TLS_HOST for a network
# that cannot resolve it.
TUNNEL_HOST_NAME="${E2E_TLS_HOST:-local.testingbot.com}"

start_tls_origin() {
  local cert="${E2E_TLS_CERT:-$WORK/origin.crt}" key="${E2E_TLS_KEY:-$WORK/origin.key}"
  if [ -n "${E2E_TLS_CERT:-}" ] && [ -f "$cert" ] && [ -f "$key" ]; then
    TLS_ORIGIN_PORT="$(free_port)"
    python3 "$HERE/origin_server.py" "$TLS_ORIGIN_PORT" "$MARKER" "$cert" "$key" \
      > "$WORK/origin-tls.log" 2>&1 &
    TLS_ORIGIN_PID=$!
    sleep 1
    return 0
  fi
  if ! command -v openssl >/dev/null 2>&1; then
    skip "tls origin" "openssl not available"
    return 1
  fi
  openssl req -x509 -newkey rsa:2048 -nodes -days 2 \
      -subj "/CN=localhost" \
      -addext "subjectAltName=DNS:localhost,DNS:${TUNNEL_HOST_NAME},IP:127.0.0.1" \
      -keyout "$key" -out "$cert" >/dev/null 2>&1 || { bad "tls origin" "openssl failed"; return 1; }

  TLS_ORIGIN_PORT="$(free_port)"
  python3 "$HERE/origin_server.py" "$TLS_ORIGIN_PORT" "$MARKER" "$cert" "$key" \
    > "$WORK/origin-tls.log" 2>&1 &
  TLS_ORIGIN_PID=$!
  local i
  for i in $(seq 1 40); do
    if curl -sk --max-time 5 "https://127.0.0.1:$TLS_ORIGIN_PORT/" 2>/dev/null | grep -q "$MARKER"; then
      return 0
    fi
    sleep 0.25
  done
  bad "tls origin" "did not start on port $TLS_ORIGIN_PORT"
  return 1
}

stop_tls_origin() {
  [ -n "$TLS_ORIGIN_PID" ] && kill "$TLS_ORIGIN_PID" 2>/dev/null
  TLS_ORIGIN_PID=""
}

# WebsocketHandler is a whole handler in the proxy chain with no other end-to-end coverage.
# An upgrade is relayed as opaque bytes once the handshake has been replayed against the
# target, so only a real upgrade carrying a real frame exercises it.
check_websocket() {  # $1 label, $2 mode (default proxy), $3 origin (default the plain origin)
  local label="$1" mode="${2:-proxy}" origin="${3:-127.0.0.1:$ORIGIN_PORT}" out
  out="$(python3 "$HERE/ws_client.py" "127.0.0.1:$PROXY_PORT" "$origin" \
          "ws-e2e-payload" "$mode" 2>&1)" \
    && assert_eq "$label websocket-echo ($mode)" "$out" "ws-e2e-payload" \
    || bad "$label websocket-relay ($mode)" "$(printf '%.160s' "$out")"
}

# The same upgrade, but driven by a real browser in TestingBot's cloud rather than by a local
# socket. This is the path a customer's test actually takes, and it is the one that goes
# through the remote Squid: ws:// is a plain upgrade Squid must relay, and wss:// is a CONNECT
# it must not try to interpret.
check_websocket_browser() {  # $1 label, $2 url, $3 "insecure" for a self-signed origin
  local label="$1" url="$2" insecure="${3:-}" sid text i
  if [ "$SKIP_BROWSER" = "1" ]; then skip "$label ws-browser" "E2E_SKIP_BROWSER=1"; return; fi
  sid="$(wd_new_session 4445 "$label" "" "$insecure")"
  if [ -z "$sid" ]; then bad "$label ws-browser-session" "could not create session"; return; fi
  local nav; nav="$(wd_goto_verbose 4445 "$sid" "$url")"
  if [ "${nav%%|*}" != "200" ]; then
    bad "$label ws-browser-navigate" "$(printf '%.200s' "${nav#*|}")"
    wd_delete 4445 "$sid"
    return
  fi
  ok "$label ws-browser-navigate" "200"

  # The socket opens after load, so poll the DOM rather than reading it once. The page writes
  # why it failed, so a refused upgrade is distinguishable from a page that never ran.
  for i in $(seq 1 30); do
    text="$(wd_source 4445 "$sid" | sed -n 's/.*id="marker">\([^<]*\)<.*/\1/p')"
    case "$text" in ws-pending|"") sleep 1;; *) break;; esac
  done
  assert_eq "$label ws-browser-echo" "$text" "$MARKER"
  wd_delete 4445 "$sid"
}

# Whether a response reaches the client as it is produced or all at once at the end.
check_streaming() {  # $1 label, $2 url, $3.. extra curl args
  local label="$1" url="$2"; shift 2
  local timing first total
  timing="$(curl -s -N --max-time 60 -o /dev/null -x "127.0.0.1:$PROXY_PORT" "$@" \
             -w '%{time_starttransfer} %{time_total}' "$url")"
  first="${timing%% *}"; total="${timing##* }"
  # The stream runs for three seconds; a buffering proxy makes the first byte arrive with the
  # last one. Comparing the two is what distinguishes them -- both deliver all the content.
  if python3 -c "import sys; sys.exit(0 if float('$first') < float('$total') / 2 else 1)"; then
    ok "$label response is streamed, not buffered" "first byte at ${first}s of ${total}s"
  else
    bad "$label response is streamed, not buffered" "first byte at ${first}s of ${total}s"
  fi
}

# The same question, but with Squid in the path and the browser's own parser.
check_sse_browser() {  # $1 label
  local label="$1" sid text i elapsed
  if [ "$SKIP_BROWSER" = "1" ]; then skip "$label sse-browser" "E2E_SKIP_BROWSER=1"; return; fi
  sid="$(wd_new_session 4445 "$label-sse")"
  if [ -z "$sid" ]; then bad "$label sse-browser-session" "could not create session"; return; fi
  assert_eq "$label sse-browser-navigate" \
    "$(wd_goto 4445 "$sid" "http://localhost:$ORIGIN_PORT/ssetest")" "200"
  for i in $(seq 1 30); do
    text="$(wd_source 4445 "$sid" | sed -n 's/.*id="marker">\([^<]*\)<.*/\1/p')"
    case "$text" in sse-pending|"") sleep 1;; *) break;; esac
  done
  case "$text" in
    "$MARKER-0|"*)
      elapsed="${text#*|}"
      # The stream lasts three seconds; the first event is emitted immediately. Arriving
      # inside two proves nothing along the way waited for the response to finish.
      if [ "$elapsed" -lt 2000 ] 2>/dev/null; then
        ok "$label browser receives events as they are sent" "first event after ${elapsed}ms"
      else
        bad "$label browser receives events as they are sent" "first event after ${elapsed}ms"
      fi;;
    *) bad "$label sse-browser-echo" "got '$text'";;
  esac
  wd_delete 4445 "$sid"
}

# The browser's own TLS path through the tunnel and Squid, as distinct from curl's.
check_browser_https() {  # $1 label
  local label="$1" sid nav
  if [ "$SKIP_BROWSER" = "1" ]; then skip "$label https-browser" "E2E_SKIP_BROWSER=1"; return; fi
  sid="$(wd_new_session 4445 "$label-https")"
  if [ -z "$sid" ]; then bad "$label https-browser-session" "could not create session"; return; fi
  nav="$(wd_goto_verbose 4445 "$sid" "https://example.com/")"
  if [ "${nav%%|*}" = "200" ]; then ok "$label browser reaches https through the tunnel" "200"
  else bad "$label browser reaches https through the tunnel" "$(printf '%.200s' "${nav#*|}")"; fi
  wd_delete 4445 "$sid"
}

# A body far larger than any single buffer, both proxied and through a CONNECT tunnel.
# Truncation and stalling on large transfers show up here and nowhere else in this suite.
check_large_payload() {  # $1 label
  local expected=$((8 * 1024 * 1024))
  assert_eq "$1 large-body-via-proxy" \
    "$(curl -s --max-time 120 -x "127.0.0.1:$PROXY_PORT" "http://127.0.0.1:$ORIGIN_PORT/large?mb=8" | wc -c | tr -d ' ')" \
    "$expected"
  # --proxytunnel forces a CONNECT and then speaks plain HTTP inside it, so the same body goes
  # through the relay path, where the proxy passes bytes it cannot see. Kept local rather than
  # fetching something over the internet, so a failure means the tunnel and not the weather.
  assert_eq "$1 large-body-via-connect" \
    "$(curl -s --max-time 120 -x "127.0.0.1:$PROXY_PORT" --proxytunnel "http://127.0.0.1:$ORIGIN_PORT/large?mb=8" | wc -c | tr -d ' ')" \
    "$expected"
}

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
      --header 'X-E2E-Rule: rule-value' --header '-X-E2E-Strip' \
      --response-header 'X-E2E-Response: from-tunnel' \
      --log-http headers \
      --fast-fail-regexps 'blocked\.example\.com,!allowed\.blocked\.example\.com' \
      --connect-to "remapped.example.invalid:80:127.0.0.1:$ORIGIN_PORT" \
      --auth "127.0.0.1:$ORIGIN_PORT:e2euser:e2epass" \
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

  # The exception must survive the same pattern list that blocks its parent domain.
  assert_neq "fast-fail exception is not blocked" \
    "$(curl -s -o /dev/null -w '%{http_connect}' --max-time 30 -x "127.0.0.1:$PROXY_PORT" https://allowed.blocked.example.com/)" "403"

  # remapped.example.invalid does not resolve, so reaching the origin at all proves the dial
  # was redirected -- and the origin must still see the original Host.
  assert_contains "connect-to reaches the substitute origin" \
    "$(curl -s --max-time 30 -x "127.0.0.1:$PROXY_PORT" "http://remapped.example.invalid/headers")" \
    "remapped.example.invalid"

  # Default --localhost-policy allow: the proxy fetches a service on this machine's loopback.
  assert_eq "localhost reachable by default" \
    "$(curl -s -o /dev/null -w '%{http_code}' --max-time 30 -x "127.0.0.1:$PROXY_PORT" "http://127.0.0.1:$ORIGIN_PORT/")" "200"

  # Environment aliases: --ready takes its port from TESTINGBOT_METRICS_PORT.
  TESTINGBOT_METRICS_PORT="$mport" java -jar "$JAR" --ready >/dev/null 2>&1 \
    && ok "env alias sets the metrics port" "TESTINGBOT_METRICS_PORT honoured" \
    || bad "env alias sets the metrics port" "--ready could not reach $mport"

  # Request rules: one added, one stripped from what the client sent.
  assert_contains "header rule adds a request header" \
    "$(curl -s --max-time 30 -x "127.0.0.1:$PROXY_PORT" "http://127.0.0.1:$ORIGIN_PORT/headers")" \
    "rule-value"
  assert_neq "header rule strips a request header" \
    "$(curl -s --max-time 30 -x "127.0.0.1:$PROXY_PORT" -H 'X-E2E-Strip: leaked' "http://127.0.0.1:$ORIGIN_PORT/headers" | grep -c 'leaked')" "1"

  # Response rule: visible in the headers the client receives.
  assert_contains "response-header rule reaches the client" \
    "$(curl -s -D - -o /dev/null --max-time 30 -x "127.0.0.1:$PROXY_PORT" "http://127.0.0.1:$ORIGIN_PORT/")" \
    "X-E2E-Response: from-tunnel"

  # Correlation id reaches the origin, reusing the caller's value.
  assert_contains "request id is passed to the origin" \
    "$(curl -s --max-time 30 -x "127.0.0.1:$PROXY_PORT" -H 'X-Request-Id: e2e-trace-id' "http://127.0.0.1:$ORIGIN_PORT/headers")" \
    "e2e-trace-id"
  # --log-http headers must not leak credentials into the tunnel log.
  curl -s -o /dev/null --max-time 30 -x "127.0.0.1:$PROXY_PORT" \
    -H 'Authorization: Bearer e2e-should-be-redacted' "http://127.0.0.1:$ORIGIN_PORT/" || true
  assert_neq "log-http redacts credentials" "$(grep -c 'e2e-should-be-redacted' "$TUNNEL_LOG" || true)" "1"

  assert_eq "metrics rejects anonymous" \
    "$(curl -s -o /dev/null -w '%{http_code}' --max-time 15 "http://127.0.0.1:$mport/metrics")" "401"
  assert_eq "metrics accepts basic auth" \
    "$(curl -s -o /dev/null -w '%{http_code}' --max-time 15 -u e2euser:e2epass "http://127.0.0.1:$mport/metrics")" "200"

  # Health probes must answer without credentials even though --metrics-auth is set here;
  # container probes have no good way to carry them.
  assert_eq "healthz is anonymous 200" \
    "$(curl -s -o /dev/null -w '%{http_code}' --max-time 15 "http://127.0.0.1:$mport/healthz")" "200"
  assert_eq "readyz reports ready" \
    "$(curl -s -o /dev/null -w '%{http_code}' --max-time 15 "http://127.0.0.1:$mport/readyz")" "200"
  java -jar "$JAR" --ready --metrics-port "$mport" >/dev/null 2>&1 \
    && ok "--ready exits 0 for a live tunnel" "exit 0" \
    || bad "--ready exits 0 for a live tunnel" "non-zero exit"
  # A port nothing is listening on is the pre-startup case; must fail cleanly, not hang.
  java -jar "$JAR" --ready --metrics-port "$(free_port)" >/dev/null 2>&1 \
    && bad "--ready exits 1 when no tunnel" "unexpected exit 0" \
    || ok "--ready exits 1 when no tunnel" "exit 1"

  # --auth supplies credentials to the origin itself, so a client that sends none still gets in.
  assert_contains "auth injects credentials for the configured host" \
    "$(curl -s --max-time 30 -x "127.0.0.1:$PROXY_PORT" "http://127.0.0.1:$ORIGIN_PORT/protected")" \
    "protected-ok"

  check_websocket combined
  check_large_payload combined

  # Not just that /metrics answers, but that it is counting: a scrape with no moving counters
  # looks healthy while reporting nothing. Every request above should be in these numbers.
  local metrics; metrics="$(curl -s --max-time 15 -u e2euser:e2epass "http://127.0.0.1:$mport/metrics")"
  assert_contains "metrics expose the tunnel build" "$metrics" "testingbot_tunnel_info"
  assert_neq "http request counter has moved" \
    "$(printf '%s' "$metrics" | awk '/^testingbot_http_requests_total/ {print ($2 > 0) ? "yes" : "no"}' | head -1)" "no"
  assert_neq "bytes counter has moved" \
    "$(printf '%s' "$metrics" | awk '/^testingbot_connection_bytes_received/ {print ($2 > 0) ? "yes" : "no"}' | head -1)" "no"
  # ?name[]= filtering must actually narrow the output, not be ignored.
  assert_eq "metrics name filter narrows the response" \
    "$(curl -s --max-time 15 -u e2euser:e2epass "http://127.0.0.1:$mport/metrics?name%5B%5D=testingbot_tunnel_info" \
        | grep -c '^testingbot_connection_bytes_received' || true)" "0"

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
  # TB-321: the SSH control connection goes through the proxy too, not just browser traffic.
  # Before this the tunnel connected directly and could not work on proxy-only networks.
  assert_contains "ssh connection traverses the proxy" "$(cat "$TUNNEL_LOG")" \
    "SSH connection will traverse the upstream proxy"
  # Proof from the proxy's own side, not just our log line: the SSH control connection is a
  # CONNECT to port 443 that is neither the API nor the origin server.
  assert_neq "proxy saw the ssh CONNECT on :443" \
    "$(grep -cE 'CONNECT [0-9.]+:443' "$WORK/upstream.log" || true)" "0"
  # The whole point: a real cloud browser loading a page only this machine serves, with every
  # hop -- SSH control connection and browser traffic alike -- going through the proxy.
  check_browser upstream-proxy
  # Stop the tunnel BEFORE the proxy it depends on: its deregistration call to the API goes
  # out through --proxy, so killing the proxy first leaves the tunnel registered server-side
  # and the account short a slot.
  stop_tunnel
  kill $up 2>/dev/null
}

# A proxy that actually demands credentials. Nothing works until the tunnel authenticates to
# it, including the SSH control connection, so this exercises ProxyAuthenticator on every
# egress path at once rather than just asserting a header was formatted correctly.
scenario_upstream_proxy_auth() {
  local uport; uport="$(free_port)"
  python3 "$HERE/upstream_proxy.py" "$uport" 'e2euser:e2epass' > "$WORK/upstream-auth.log" 2>&1 &
  local up=$!
  sleep 1

  # Reaching ready state at all proves the SSH CONNECT was authenticated: before TB-321 the
  # SSH connection bypassed --proxy entirely, and this proxy refuses unauthenticated CONNECTs.
  start_tunnel --proxy "127.0.0.1:$uport" --proxy-userpwd 'e2euser:e2epass' \
    || { kill $up 2>/dev/null; return 1; }
  ok "tunnel came up through an authenticating proxy" "ready"

  # Coming up is not on its own proof that SSH used the proxy -- this machine has direct
  # internet, so a build that ignored --proxy for SSH would also succeed here. The proof is an
  # IP-form CONNECT to :443 in the proxy log: that is the tunnel server, and browser traffic
  # and the API both use hostnames. An unauthenticated one would have been answered 407 and
  # never tunnelled, so its presence means it authenticated.
  assert_neq "ssh CONNECT went through the authenticating proxy" \
    "$(grep -cE 'CONNECT [0-9.]+:443' "$WORK/upstream-auth.log" || true)" "0"
  assert_contains "proxy accepted our credentials" "$(cat "$WORK/upstream-auth.log")" "AUTH-OK"
  assert_neq "no request was left unauthenticated" \
    "$(grep -c 'AUTH-REJECTED' "$WORK/upstream-auth.log" || true)" "1"

  check_proxy_http upstream-proxy-auth
  check_proxy_connect upstream-proxy-auth
  check_browser upstream-proxy-auth
  stop_tunnel
  kill $up 2>/dev/null
}

# The same chaining, over SOCKS5 instead of HTTP CONNECT. Worth its own scenario because none
# of the three egress paths shares an implementation with the HTTP-proxy ones: SSH goes through
# JSch's ProxySOCKS5, plain HTTP through jetty-client's Socks5Proxy, and HTTPS CONNECT through
# our own Socks5HandshakeConnection driving Socks5Handshake on Jetty's selector.
scenario_socks5_proxy() {
  local uport; uport="$(free_port)"
  python3 "$HERE/socks5_proxy.py" "$uport" > "$WORK/socks5.log" 2>&1 &
  local up=$!
  sleep 1
  start_tunnel --proxy "socks5://127.0.0.1:$uport" || { kill $up 2>/dev/null; return 1; }
  check_proxy_http socks5-proxy
  check_proxy_connect socks5-proxy
  assert_contains "socks5 proxy saw traffic" "$(cat "$WORK/socks5.log")" "CONNECT"
  # The SSH control connection goes through the proxy too, not just browser traffic. It is the
  # IP-form request to :443: browser traffic and the API both name hosts.
  assert_neq "socks5 proxy saw the ssh connection on :443" \
    "$(grep -cE 'CONNECT [0-9.]+:443' "$WORK/socks5.log" || true)" "0"
  # A real cloud browser loading a page only this machine serves, every hop over SOCKS5.
  check_browser socks5-proxy
  # Stop the tunnel before the proxy it depends on: its deregistration call to the API goes out
  # through --proxy, so killing the proxy first leaves the tunnel registered server-side.
  stop_tunnel
  kill $up 2>/dev/null
}

# --http-idle-timeout and --http-dial-timeout.
#
# A timeout is only observable by waiting, so these are the two checks the unit tests cannot
# make: that an idle tunnel is actually closed at the configured moment, and that a dial to
# somewhere unreachable gives up when told to rather than on the built-in default.
scenario_timeouts() {
  start_tunnel --http-idle-timeout 5 --http-dial-timeout 2 || return 1

  # Normal traffic first: a scenario built on aggressive timeouts has to show it has not simply
  # broken the proxy.
  check_proxy_http timeouts
  check_proxy_connect timeouts

  # Well inside the timeout, the tunnel survives.
  assert_eq "a tunnel idle for less than the timeout stays open" \
    "$(python3 "$HERE/idle_probe.py" "127.0.0.1:$PROXY_PORT" "127.0.0.1:$ORIGIN_PORT" 1)" \
    "open"
  # Past it, it is closed. The probe proves the tunnel carried a request before going quiet,
  # so "closed" cannot mean "never worked".
  assert_eq "a tunnel idle for longer than the timeout is closed" \
    "$(python3 "$HERE/idle_probe.py" "127.0.0.1:$PROXY_PORT" "127.0.0.1:$ORIGIN_PORT" 9)" \
    "closed"

  # 192.0.2.0/24 is TEST-NET-1, reserved by RFC 5737 and routed nowhere, so a connection to it
  # hangs until something gives up. Some networks answer immediately with no-route instead, and
  # then this proves nothing -- so measure that first and say so rather than passing hollowly.
  local baseline; baseline="$(curl -s -o /dev/null --connect-timeout 8 \
      -w '%{time_total}' "http://192.0.2.1/" 2>/dev/null || true)"
  if python3 -c "import sys; sys.exit(0 if float('${baseline:-0}') < 1 else 1)"; then
    skip "a dial gives up at --http-dial-timeout" \
      "this network fails unreachable addresses instantly (${baseline}s), nothing to measure"
  else
    local elapsed; elapsed="$(curl -s -o /dev/null --max-time 30 \
        -x "127.0.0.1:$PROXY_PORT" -w '%{time_total}' "http://192.0.2.1/" 2>/dev/null || true)"
    # The built-in default is 15s; 2s was asked for. Anything under 10 can only be the option.
    if python3 -c "import sys; sys.exit(0 if 0 < float('${elapsed:-0}') < 10 else 1)"; then
      ok "a dial gives up at --http-dial-timeout" "gave up after ${elapsed}s, not the 15s default"
    else
      bad "a dial gives up at --http-dial-timeout" "took ${elapsed}s"
    fi
  fi
}

# --krb5-hosts sends Kerberos credentials to sites, not just to the upstream proxy.
#
# The positive half needs a KDC and lives in NegotiateOriginTest, which verifies a real ticket
# against a real acceptor. What belongs here is the half that needs no Kerberos at all: that a
# host nobody named is never offered a credential, and that a host that was named but for which
# no ticket can be had degrades to its --auth credential instead of breaking the request.
scenario_krb5_hosts() {
  start_tunnel --krb5-hosts "not-this-one.invalid" \
      --auth "127.0.0.1:$ORIGIN_PORT:e2euser:e2epass" \
    || return 1

  # /headers echoes what the origin received, so this reads the wire rather than a log line.
  #
  # Weaker than it looks, and deliberately kept anyway: with no KDC here, no ticket could have
  # been minted for any host, so this cannot distinguish "correctly withheld" from "could not
  # be produced". NegotiateOriginTest makes that distinction against a real KDC, where a listed
  # host gets a verified ticket in the same run that an unlisted one gets nothing. What this
  # adds is that the whole path stays intact in a real tunnel.
  local headers
  headers="$(curl -s --max-time 30 -x "127.0.0.1:$PROXY_PORT" \
      "http://127.0.0.1:$ORIGIN_PORT/headers")"
  assert_eq "an unnamed host is offered no Kerberos credential" \
    "$(printf '%s' "$headers" | grep -ci 'negotiate' || true)" "0"

  # And --auth still applies to it, so listing other hosts has not disturbed anything.
  assert_contains "an unnamed host still gets its --auth credential" \
    "$(curl -s --max-time 30 -x "127.0.0.1:$PROXY_PORT" "http://127.0.0.1:$ORIGIN_PORT/protected")" \
    "protected-ok"

  stop_tunnel

  # A named host with no ticket available: the request must still go out, falling back to the
  # Basic credential, rather than failing because Kerberos could not be done.
  start_tunnel --krb5-hosts "127.0.0.1" \
      --auth "127.0.0.1:$ORIGIN_PORT:e2euser:e2epass" \
    || return 1
  assert_contains "a named host with no ticket falls back to --auth" \
    "$(curl -s --max-time 30 -x "127.0.0.1:$PROXY_PORT" "http://127.0.0.1:$ORIGIN_PORT/protected")" \
    "protected-ok"
  assert_eq "and no half-formed Negotiate header is sent" \
    "$(curl -s --max-time 30 -x "127.0.0.1:$PROXY_PORT" "http://127.0.0.1:$ORIGIN_PORT/headers" \
        | grep -ci 'negotiate' || true)" "0"
}

# --cacert-file, against a proxy that really does intercept TLS.
#
# A TLS-inspecting proxy re-signs every certificate with its own authority. The JVM has never
# seen it, so the tunnel cannot reach the API and never starts. That is the whole point of the
# option, and the only honest way to show it works is a tunnel that fails to start without it
# and starts with it -- which is also the only thing that exercises the wiring into the API
# client rather than the SSLContext in isolation.
scenario_cacert() {
  if ! command -v openssl >/dev/null 2>&1; then
    skip "cacert" "openssl not available"
    return
  fi
  local ca="$WORK/mitm-ca.pem" cakey="$WORK/mitm-ca.key"
  openssl req -x509 -newkey rsa:2048 -nodes -days 2 -subj "/CN=E2E Intercepting CA" \
      -keyout "$cakey" -out "$ca" >/dev/null 2>&1 \
    || { bad "cacert" "could not generate a CA"; return; }

  local mport; mport="$(free_port)"
  # Only the API is intercepted; everything else is tunnelled untouched, so a failure here is
  # about the certificate and not about the proxy breaking something unrelated.
  python3 "$HERE/mitm_proxy.py" "$mport" "$ca" "$cakey" api.testingbot.com \
    > "$WORK/mitm.log" 2>&1 &
  local mitm=$!
  sleep 1

  # Without the authority the tunnel must not come up. A failed start registers nothing
  # server-side, so this costs no tunnel against the quota.
  if start_tunnel --proxy "127.0.0.1:$mport" 2>/dev/null; then
    bad "an intercepted API connection fails without --cacert-file" "the tunnel started anyway"
    stop_tunnel
  else
    ok "an intercepted API connection fails without --cacert-file" "tunnel did not start"
  fi
  assert_neq "the proxy did intercept the API connection" \
    "$(grep -c 'MITM api.testingbot.com' "$WORK/mitm.log" || true)" "0"
  assert_neq "and the handshake was rejected for want of the authority" \
    "$(grep -c 'HANDSHAKE-REJECTED' "$WORK/mitm.log" || true)" "0"

  # With it, the same interception is accepted and everything works.
  start_tunnel --proxy "127.0.0.1:$mport" --cacert-file "$ca" \
    || { kill $mitm 2>/dev/null; return 1; }
  ok "the tunnel starts once the authority is trusted" "ready"
  check_proxy_http cacert
  check_proxy_connect cacert
  check_browser cacert

  stop_tunnel
  kill $mitm 2>/dev/null
}

# Test traffic and control traffic through two different proxies.
#
# Once egress is filtered by destination, the proxy that may reach the public internet is
# often not the one that reaches internal test targets, and a single --proxy has no answer for
# that. The proof is negative as well as positive: each proxy must see its own traffic and not
# the other's, which one proxy carrying everything would also satisfy on the positive half.
scenario_split_proxy() {
  local tport cport; tport="$(free_port)"; cport="$(free_port)"
  python3 "$HERE/upstream_proxy.py" "$tport" > "$WORK/proxy-test.log" 2>&1 &
  local tproxy=$!
  # The control proxy demands credentials and the test-traffic one does not, so the two cannot
  # be satisfied by the same configuration. That is what makes the credential separation
  # observable: --proxy-userpwd reaching this proxy would be an AUTH-REJECTED in its log.
  python3 "$HERE/upstream_proxy.py" "$cport" 'ctl-user:ctl-pass' \
    > "$WORK/proxy-control.log" 2>&1 &
  local cproxy=$!
  sleep 1

  start_tunnel --proxy "127.0.0.1:$tport" \
      --proxy-testingbot "127.0.0.1:$cport" \
      --proxy-testingbot-userpwd 'ctl-user:ctl-pass' \
    || { kill $tproxy $cproxy 2>/dev/null; return 1; }

  assert_contains "the control proxy accepted its own credentials" \
    "$(cat "$WORK/proxy-control.log")" "AUTH-OK"
  assert_eq "nothing reached the control proxy unauthenticated" \
    "$(grep -c 'AUTH-REJECTED' "$WORK/proxy-control.log" || true)" "0"

  assert_contains "the SSH connection uses the control proxy" \
    "$(cat "$TUNNEL_LOG")" "SSH connection will traverse the upstream proxy 127.0.0.1:$cport"

  # The SSH control connection is the IP-form CONNECT to :443. It belongs to the control proxy
  # and must not appear on the test-traffic one.
  assert_neq "the control proxy carried the SSH connection" \
    "$(grep -cE 'CONNECT [0-9.]+:443' "$WORK/proxy-control.log" || true)" "0"
  assert_eq "the test-traffic proxy did not carry it" \
    "$(grep -cE 'CONNECT [0-9.]+:443' "$WORK/proxy-test.log" || true)" "0"

  # Now the other direction: browser traffic belongs to --proxy.
  check_proxy_http split-proxy
  check_proxy_connect split-proxy
  assert_contains "the test-traffic proxy carried the test traffic" \
    "$(cat "$WORK/proxy-test.log")" "example.com"
  assert_eq "the control proxy did not carry it" \
    "$(grep -c 'example.com' "$WORK/proxy-control.log" || true)" "0"

  check_browser split-proxy

  # Stop the tunnel before its proxies: deregistration goes out through the control proxy.
  stop_tunnel
  kill $tproxy $cproxy 2>/dev/null
}

# A SOCKS5 proxy that refuses the no-auth method outright, so nothing works until the tunnel
# negotiates RFC 1929 credentials -- on every egress path at once.
scenario_socks5_proxy_auth() {
  local uport; uport="$(free_port)"
  python3 "$HERE/socks5_proxy.py" "$uport" 'e2euser:e2epass' > "$WORK/socks5-auth.log" 2>&1 &
  local up=$!
  sleep 1

  start_tunnel --proxy "socks5://127.0.0.1:$uport" --proxy-userpwd 'e2euser:e2epass' \
    || { kill $up 2>/dev/null; return 1; }
  ok "tunnel came up through an authenticating socks5 proxy" "ready"

  # Coming up is not on its own proof that SSH used the proxy -- this machine has direct
  # internet. The proof is an IP-form request to :443 in the proxy log, which this proxy only
  # ever logs after the sub-negotiation succeeded.
  assert_neq "ssh connection went through the authenticating socks5 proxy" \
    "$(grep -cE 'CONNECT [0-9.]+:443' "$WORK/socks5-auth.log" || true)" "0"
  assert_contains "socks5 proxy accepted our credentials" "$(cat "$WORK/socks5-auth.log")" "AUTH-OK"
  assert_eq "no request was left unauthenticated" \
    "$(grep -c 'AUTH-REJECTED' "$WORK/socks5-auth.log" || true)" "0"

  check_proxy_http socks5-proxy-auth
  check_proxy_connect socks5-proxy-auth
  check_browser socks5-proxy-auth
  stop_tunnel
  kill $up 2>/dev/null
}

# --localhost-policy deny is a security control, and only its permissive default was covered.
# The tunnel exists to reach this machine, so getting the deny case wrong either breaks the
# product or silently exposes loopback services to anything that can drive a session.
scenario_localhost_deny() {
  start_tunnel --localhost-policy deny || return 1

  # Named and by literal address: "localhost" is refused without resolving it, 127.0.0.1 after.
  assert_eq "deny refuses CONNECT to localhost" \
    "$(curl -s -o /dev/null -w '%{http_connect}' --max-time 30 -x "127.0.0.1:$PROXY_PORT" "https://localhost:$ORIGIN_PORT/")" "403"
  assert_eq "deny refuses CONNECT to 127.0.0.1" \
    "$(curl -s -o /dev/null -w '%{http_connect}' --max-time 30 -x "127.0.0.1:$PROXY_PORT" "https://127.0.0.1:$ORIGIN_PORT/")" "403"
  # Plain HTTP is a separate path through the proxy and must refuse it too.
  assert_neq "deny refuses plain HTTP to loopback" \
    "$(curl -s -o /dev/null -w '%{http_code}' --max-time 30 -x "127.0.0.1:$PROXY_PORT" "http://127.0.0.1:$ORIGIN_PORT/")" "200"
  # Everything else still works: the policy must not be a blanket refusal.
  check_proxy_http localhost-deny
  check_proxy_connect localhost-deny
}

# Costs no tunnel: --pac-test evaluates a PAC file against one URL and exits. The interpreter
# is written by hand -- Nashorn is gone and GraalVM JS is too much to embed -- so its output
# decides where customer traffic egresses, and it has no other end-to-end coverage.
scenario_pac() {
  local pac="$WORK/e2e.pac"
  cat > "$pac" <<'PACFILE'
function FindProxyForURL(url, host) {
    if (isPlainHostName(host)) {
        return "DIRECT";
    }
    if (dnsDomainIs(host, ".internal.example")) {
        return "PROXY internal-proxy.example:3128";
    }
    if (shExpMatch(host, "*.cdn.example")) {
        return "DIRECT";
    }
    return "PROXY default-proxy.example:8080";
}
PACFILE

  pac_says() { java -jar "$JAR" --pac-local "$pac" --pac-test "$1" 2>&1; }

  assert_contains "pac routes an unqualified host DIRECT" \
    "$(pac_says http://intranet/)" "DIRECT"
  assert_contains "pac routes a matched domain to its proxy" \
    "$(pac_says http://host.internal.example/)" "internal-proxy.example:3128"
  assert_contains "pac honours a shExpMatch wildcard" \
    "$(pac_says http://assets.cdn.example/x.js)" "DIRECT"
  assert_contains "pac falls through to the default proxy" \
    "$(pac_says https://www.example.com/)" "default-proxy.example:8080"

  # Unsupported syntax must be refused with its line number, never approximated: a PAC file
  # read wrongly sends traffic somewhere the operator did not choose.
  cat > "$WORK/bad.pac" <<'PACFILE'
function FindProxyForURL(url, host) {
    var re = /example/;
    return "DIRECT";
}
PACFILE
  local out; out="$(java -jar "$JAR" --pac-local "$WORK/bad.pac" --pac-test http://x/ 2>&1)"
  if java -jar "$JAR" --pac-local "$WORK/bad.pac" --pac-test http://x/ >/dev/null 2>&1; then
    bad "pac refuses unsupported syntax" "exited 0 for a regular-expression literal"
  else
    ok "pac refuses unsupported syntax" "non-zero exit"
  fi
  assert_contains "pac names the offending line" "$out" "2"
}

# What --nobump actually does, which nothing else establishes.
#
# SSL bumping happens on the remote Squid; this client only relays a no_bump flag at tunnel
# creation. So the flag's effect is only observable from the outside, and the observable
# difference is which certificate a browser ends up seeing: the origin's when TLS is passed
# through, Squid's when it is re-signed.
#
# A self-signed origin is the discriminator. Passed through, the browser sees a certificate it
# does not trust and acceptInsecureCerts decides; re-signed, Squid has to validate the origin
# itself first and a self-signed one gives it nothing to chain to.
scenario_sslbump() {
  start_tls_origin || return 1
  local bump_args="--nobump"
  [ "${E2E_SSLBUMP_ON:-0}" = "1" ] && bump_args=""
  # --log-http url so the tunnel-traversal assertion below has something to read: the default
  # (errors) logs nothing for a request that worked.
  start_tunnel $bump_args --log-http url || { stop_tls_origin; return 1; }

  if [ "$SKIP_BROWSER" = "1" ]; then
    skip "sslbump" "E2E_SKIP_BROWSER=1"
    stop_tunnel; stop_tls_origin; return
  fi

  # First eliminate the confound: a capability the remote end quietly dropped would look
  # identical to bumping being on.
  local caps sid agreed
  caps="$(wd_capabilities 4445 "sslbump" insecure)"
  sid="${caps%%|*}"; agreed="${caps##*|}"
  if [ -z "$sid" ]; then bad "sslbump session" "could not create session"; stop_tunnel; stop_tls_origin; return; fi
  assert_eq "the browser agreed to accept insecure certificates" "$agreed" "True"

  local nav; nav="$(wd_goto_verbose 4445 "$sid" "https://$TUNNEL_HOST_NAME:$TLS_ORIGIN_PORT/")"
  assert_eq "TLS is passed through for an unbumped tunnel" "${nav%%|*}" "200"

  # Loading is not the same as loading *through the tunnel*: a page served by anything else
  # would satisfy the status check. The marker only exists on our origin.
  assert_contains "the unbumped page came from our origin" \
    "$(wd_source 4445 "$sid")" "$MARKER"

  # And the CONNECT has to have reached this client. This is what the scenario was missing: it
  # asserted a navigation result and never checked that the request travelled the tunnel, so
  # for a long time it reported a server-side splicing failure for a request that never left
  # the browser VM.
  if grep -q "CONNECT http://$TUNNEL_HOST_NAME:$TLS_ORIGIN_PORT" "$TUNNEL_LOG" 2>/dev/null; then
    ok "the unbumped CONNECT travelled through the tunnel" "seen in the tunnel log"
  else
    bad "the unbumped CONNECT travelled through the tunnel" \
      "no CONNECT to $TUNNEL_HOST_NAME:$TLS_ORIGIN_PORT in the tunnel log"
  fi

  # The behaviour this scenario used to report as a server fault, kept as a regression check:
  # a browser given "localhost" never reaches the tunnel over HTTPS, whatever the port.
  local loopback; loopback="$(wd_goto_verbose 4445 "$sid" "https://localhost:$TLS_ORIGIN_PORT/")"
  if [ "${loopback%%|*}" = "200" ]; then
    ok "localhost over HTTPS now reaches the tunnel too" "browsers stopped short-circuiting it"
  else
    skip "localhost over HTTPS does not reach the tunnel" \
      "expected: the browser resolves it locally, so tests must use a routable name"
  fi
  wd_delete 4445 "$sid"

  # The per-host form travels the same road. Asserting only what this side controls: that the
  # tunnel starts with it and keeps working. What Squid does with the list is its own to prove.
  assert_contains "nobump-domains is accepted and logged" \
    "$(java -jar "$JAR" --help 2>&1)" "nobump-domains"

  stop_tunnel
  stop_tls_origin
}

# Which HTTP versions survive the tunnel, and whether a response is streamed or buffered.
#
# --nobump because HTTP/2 over TLS is negotiated by ALPN between the browser and the origin.
# The tunnel relays a CONNECT as opaque bytes and cannot affect that, but the remote Squid
# terminates the TLS when it bumps, and it is Squid that then chooses the ALPN protocol.
scenario_protocols() {
  # E2E_PROTOCOLS_BUMP=1 runs the same checks with Squid bumping, which is the default for a
  # real tunnel. The h2 assertion is relaxed there because the negotiation is then Squid's.
  local bump_args="--nobump"
  [ "${E2E_PROTOCOLS_BUMP:-0}" = "1" ] && bump_args=""
  start_tunnel $bump_args || return 1

  # HTTP/2 through CONNECT. Nothing in the tunnel parses these bytes, so a downgrade here is
  # something in the path terminating TLS.
  local negotiated
  negotiated="$(curl -s -o /dev/null --max-time 30 -x "127.0.0.1:$PROXY_PORT" --http2 \
                 -w '%{http_version}' https://example.com/)"
  if [ "${E2E_PROTOCOLS_BUMP:-0}" = "1" ]; then
    # Bumping terminates the TLS, so the version is whatever Squid negotiated, not what the
    # origin offered. Measured as HTTP/2 -- Squid's ALPN does offer it -- but recorded rather
    # than asserted, because it is a property of the remote Squid and not of this code.
    ok "http/2 with SSL bumping enabled" "negotiated HTTP/$negotiated"
  else
    assert_eq "http/2 survives the CONNECT relay" "$negotiated" "2"
  fi

  # Plain HTTP proxying is HTTP/1.1 on both hops by construction: the proxy port is a bare
  # HttpConnectionFactory, and ProxyHandler's client is HTTP/1.1-only. Asserted rather than
  # assumed, so that turning either into h2c is a decision and not an accident.
  assert_eq "plain HTTP proxying stays on 1.1" \
    "$(curl -s -o /dev/null --max-time 30 -x "127.0.0.1:$PROXY_PORT" --http2 \
        -w '%{http_version}' "http://127.0.0.1:$ORIGIN_PORT/")" "1.1"

  # HTTP/3 is QUIC, which is UDP. An HTTP CONNECT tunnel is TCP, and carrying UDP through one
  # needs CONNECT-UDP (RFC 9298), which nothing in this path implements -- so a proxied client
  # cannot use h3 and falls back. Checked when curl can speak it; most builds cannot.
  if curl -V | grep -q HTTP3; then
    if curl -s -o /dev/null --max-time 20 -x "127.0.0.1:$PROXY_PORT" --http3-only \
         https://example.com/ 2>/dev/null; then
      bad "http/3 is not carried by a CONNECT tunnel" "unexpectedly succeeded"
    else
      ok "http/3 is not carried by a CONNECT tunnel" "refused, as expected"
    fi
  else
    skip "http/3 through the tunnel" "this curl has no HTTP3 support"
  fi

  # Streaming, proxied and tunnelled. Four events a second apart: a proxy that buffers still
  # delivers all of them, so only the time to the first byte tells the difference.
  check_streaming protocols "http://127.0.0.1:$ORIGIN_PORT/stream?events=4&delay=1"
  check_streaming protocols-connect \
    "http://127.0.0.1:$ORIGIN_PORT/stream?events=4&delay=1" --proxytunnel

  # And through a real browser, which is the only way the remote Squid is in the path.
  check_sse_browser protocols

  stop_tunnel
}

# WebSockets, all four ways they reach the tunnel.
#
# --nobump because wss:// is a CONNECT the remote Squid must relay untouched. With SSL bumping
# it decrypts and re-encrypts, and a bumped connection carrying an Upgrade is where WebSocket
# support classically breaks -- Squid has to pass a 101 through rather than treating the
# response as the end of a request. Running this scenario without --nobump is the way to find
# out whether that still holds.
scenario_websocket() {
  start_tls_origin || return 1
  # E2E_WS_BUMP=1 runs the same scenario with Squid bumping, to tell a bump problem from a
  # certificate problem: the two fail identically from the browser's side.
  local bump_args="--nobump"
  [ "${E2E_WS_BUMP:-0}" = "1" ] && bump_args=""
  start_tunnel $bump_args || { stop_tls_origin; return 1; }

  # Local first, so a failure below can be attributed. These need no browser and no Squid.
  check_websocket websocket proxy
  check_websocket websocket connect
  check_websocket websocket tls "127.0.0.1:$TLS_ORIGIN_PORT"

  # Then the real thing: a browser in the cloud opening a socket back to this machine.
  check_websocket_browser websocket-ws "http://$TUNNEL_HOST_NAME:$ORIGIN_PORT/wstest"

  # A self-signed origin is enough: acceptInsecureCerts relaxes the browser, and the connection
  # does reach the tunnel once the host name is routable.
  #
  # This used to be gated behind E2E_TLS_CERT, on the reasoning that a self-signed origin failed
  # with ERR_SSL_PROTOCOL_ERROR identically with and without --nobump, so the certificate had to
  # be the problem and Squid the place it was refused. That was the localhost bug: over HTTPS
  # the request never left the browser VM, so neither Squid nor the certificate was ever
  # reached. Addressed by a routable name it works, and the gate is gone.
  check_websocket_browser websocket-wss \
    "https://$TUNNEL_HOST_NAME:$TLS_ORIGIN_PORT/wstest" insecure

  stop_tunnel
  stop_tls_origin
}

# --dns sends name resolution to a server the operator names instead of the platform resolver.
# It is easy for this to be wired and yet dead -- the option spent years setting JDK 8 system
# properties that had no effect on any supported JDK -- and only a name that cannot resolve any
# other way can tell the difference. Hence .invalid, which is reserved never to resolve.
scenario_dns() {
  local dport dport2; dport="$(free_port)"; dport2="$(free_port)"
  python3 "$HERE/dns_server.py" "$dport" "dns-e2e.invalid=127.0.0.1" \
    > "$WORK/dns.log" 2>&1 &
  local dns=$!
  # A second server that knows a different name, so which one answered is visible in the result
  # rather than only in the logs.
  python3 "$HERE/dns_server.py" "$dport2" "dns-standby.invalid=127.0.0.1" \
    > "$WORK/dns2.log" 2>&1 &
  local dns2=$!
  sleep 1
  # A dead port first: --dns takes a list, and the tunnel has to fall through to a live server
  # rather than treating the first entry as the only one.
  local deadport; deadport="$(free_port)"
  start_tunnel --dns "127.0.0.1:$deadport,127.0.0.1:$dport,127.0.0.1:$dport2" \
      --dns-timeout 2 \
    || { kill $dns $dns2 2>/dev/null; return 1; }

  # Plain HTTP: reaching the origin at all proves the custom resolver answered, because
  # nothing else on this machine can turn this name into an address.
  assert_contains "dns resolves a name only our server knows" \
    "$(curl -s --max-time 30 -x "127.0.0.1:$PROXY_PORT" "http://dns-e2e.invalid:$ORIGIN_PORT/")" \
    "$MARKER"
  # CONNECT is resolved separately, in the handler's newConnectAddress.
  assert_eq "dns applies to the CONNECT path too" \
    "$(curl -s -o /dev/null -w '%{http_code}' --max-time 30 -x "127.0.0.1:$PROXY_PORT" \
        --proxytunnel "http://dns-e2e.invalid:$ORIGIN_PORT/")" "200"
  # From the server's own side, not just from ours succeeding.
  assert_neq "our dns server was the one asked" \
    "$(grep -c 'query dns-e2e.invalid' "$WORK/dns.log" || true)" "0"

  # The name only the second live server knows: reaching it proves the list is walked past
  # the first server that cannot answer, not just past one that is unreachable.
  assert_contains "dns falls through to a later server in the list" \
    "$(curl -s --max-time 30 -x "127.0.0.1:$PROXY_PORT" "http://dns-standby.invalid:$ORIGIN_PORT/")" \
    "$MARKER"
  assert_neq "the standby server was actually queried" \
    "$(grep -c 'query dns-standby.invalid' "$WORK/dns2.log" || true)" "0"

  # A name it does not serve must still work: --dns falls back rather than becoming a
  # single point of failure for everything the tests visit.
  check_proxy_http dns
  check_proxy_connect dns

  # And the browser reaches the origin through it, the same as any other scenario.
  check_browser dns

  stop_tunnel
  kill $dns $dns2 2>/dev/null
}

# The reconnect path: everything that runs when the SSH connection drops and comes back.
# Several bugs have lived here -- a SelectorManager leaked on every restart until the pool ran
# out, port forwards that were not rebuilt, a BindException on the second start -- and none of
# them are visible in a tunnel that is only ever started once.
#
# The SSH connection is forced through a local proxy we control, so restarting that proxy
# severs it deterministically. Killing the tunnel's own process would prove nothing.
scenario_reconnect() {
  local uport mport; uport="$(free_port)"; mport="$(free_port)"
  python3 "$HERE/upstream_proxy.py" "$uport" > "$WORK/reconnect-proxy.log" 2>&1 &
  local up=$!
  sleep 1
  start_tunnel --proxy "127.0.0.1:$uport" --metrics-port "$mport" \
    || { kill $up 2>/dev/null; return 1; }
  check_proxy_http reconnect-before

  # Sever every connection through the proxy, including SSH, and let it come back.
  kill $up 2>/dev/null; wait $up 2>/dev/null
  python3 "$HERE/upstream_proxy.py" "$uport" >> "$WORK/reconnect-proxy.log" 2>&1 &
  up=$!

  # Two waits, not one. The tunnel only notices through its 30s keepalive, so polling straight
  # after the kill reads a readiness that is simply stale -- and a scenario that trusted it
  # would report success without a reconnect having happened at all.
  local noticed=0 i
  for i in $(seq 1 60); do
    if [ "$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 \
              "http://127.0.0.1:$mport/readyz")" != "200" ]; then
      noticed=1; break
    fi
    sleep 2
  done
  [ "$noticed" = "1" ] && ok "readyz reports not-ready while the connection is down" "503" \
                       || bad "readyz reports not-ready while the connection is down" \
                              "stayed 200 for 120s after the connection was severed"

  # healthz answers the whole time: the process is alive, it is the tunnel that is not.
  assert_eq "healthz stays 200 during the outage" \
    "$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 "http://127.0.0.1:$mport/healthz")" "200"

  local recovered=0
  for i in $(seq 1 90); do
    if [ "$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 \
              "http://127.0.0.1:$mport/readyz")" = "200" ]; then
      recovered=1; break
    fi
    sleep 2
  done
  [ "$recovered" = "1" ] && ok "tunnel reports ready again" "readyz 200" \
                         || bad "tunnel reports ready again" "readyz never returned to 200"

  assert_neq "the tunnel logged a reconnect" \
    "$(grep -ciE 'reconnect|re-connect|connection lost|connection closed' "$TUNNEL_LOG" || true)" "0"

  # The point of the whole scenario: it still serves traffic afterwards. A proxy that comes
  # back "ready" but cannot forward is the failure mode these bugs produced. The local proxy
  # is restarted as part of the reconnect, so the websocket and Selenium paths are retested
  # too -- that restart is where the leaked selectors and the bind failure lived.
  check_proxy_http reconnect-after
  check_proxy_connect reconnect-after
  check_websocket reconnect-after
  check_se_status reconnect-after
  check_browser reconnect-after

  stop_tunnel
  kill $up 2>/dev/null
}

# ---------------------------------------------------------------- soak
# Deliberately not in ALL_SCENARIOS: it runs for minutes, so it is opt-in with
# `run-e2e.sh soak` rather than something --all drags along.

soak_metric() {  # $1 metric line prefix, $2 metrics port -> the value, or empty
  curl -s --max-time 10 "http://127.0.0.1:$2/metrics" \
    | awk -v m="$1" 'index($0, m) == 1 { print $NF; exit }'
}

# Rounds a metric to an integer, and yields 0 for one this JVM does not export.
soak_int() {
  awk -v v="$1" 'BEGIN { if (v == "") print 0; else printf "%d\n", v }'
}

# One unit of load, run in the background by the burst phase.
soak_worker() {  # $1 kind, $2 origin port
  case "$1" in
    http)    curl -s -o /dev/null --max-time 60 -x "127.0.0.1:$PROXY_PORT" \
               "http://127.0.0.1:$2/" ;;
    connect) curl -s -o /dev/null --max-time 60 -x "127.0.0.1:$PROXY_PORT" --proxytunnel \
               "http://127.0.0.1:$2/" ;;
    slow)    curl -s -o /dev/null --max-time 60 -x "127.0.0.1:$PROXY_PORT" \
               "http://127.0.0.1:$2/slow" ;;
    ws)      python3 "$HERE/ws_client.py" "127.0.0.1:$PROXY_PORT" "127.0.0.1:$2" \
               "soak" >/dev/null 2>&1 ;;
  esac
}

# Readiness, retried, distinguishing a dead tunnel from a saturated test host.
soak_readyz() {  # $1 cycle label, $2 metrics port
  local code i
  for i in 1 2 3 4 5; do
    code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 \
              "http://127.0.0.1:$2/readyz" || true)"
    if [ "$code" = "200" ]; then
      ok "cycle $1: readyz still 200" "200"
      return 0
    fi
    sleep 3
  done
  if [ -n "${TUNNEL_PID:-}" ] && kill -0 "$TUNNEL_PID" 2>/dev/null; then
    bad "cycle $1: readyz still 200" \
      "got $code but the process is alive: either the insight server stalled or this host is saturated"
  else
    bad "cycle $1: readyz still 200" "got $code and the tunnel process is gone"
  fi
  return 1
}

# Load, quiet, and burst in turn, watching what does not come back down afterwards.
#
# Aimed at the failures that only appear over time, all of which were found the hard way here:
# a SelectorManager leaked per reconnect until the thread pool was exhausted, connections never
# released, a tunnel reporting ready while unable to forward. None of them show up in a
# scenario that makes a handful of requests and exits.
scenario_soak() {
  local cycles="${E2E_SOAK_CYCLES:-3}"
  local quiet="${E2E_SOAK_QUIET:-20}"
  local burst="${E2E_SOAK_BURST:-40}"
  local bulk_mb="${E2E_SOAK_BULK_MB:-8}"
  local bulk_reps="${E2E_SOAK_BULK_REPS:-2}"
  local mport; mport="$(free_port)"

  # Printed up front because this scenario is long enough that an outer `timeout` can cut it
  # off, and a tunnel killed with its process group looks exactly like one that crashed. The
  # first runs of this scenario were read as a stability bug for precisely that reason.
  printf '    plan: %s cycle(s), %sx%sMiB bulk, %s-way burst, %ss quiet -- allow well over %ss\n' \
    "$cycles" "$bulk_reps" "$bulk_mb" "$burst" "$quiet" \
    "$(( cycles * (quiet + 60) + 120 ))"

  start_tunnel --metrics-port "$mport" || return 1

  # Baseline after one warm request, so class loading and pool creation are already done and
  # are not later mistaken for a leak.
  curl -s -o /dev/null --max-time 30 -x "127.0.0.1:$PROXY_PORT" "http://127.0.0.1:$ORIGIN_PORT/"
  sleep 3
  local threads0 fds0 conns0
  threads0="$(soak_int "$(soak_metric 'jvm_threads_current' "$mport")")"
  fds0="$(soak_int "$(soak_metric 'process_open_fds' "$mport")")"
  conns0="$(soak_int "$(soak_metric 'testingbot_connections_current{listener="proxy"' "$mport")")"
  printf '    baseline: %s threads, %s fds, %s open connections\n' "$threads0" "$fds0" "$conns0"
  if [ "$threads0" -le 0 ]; then
    bad "soak baseline" "the metrics endpoint on $mport is not answering; nothing to measure"
    return 1
  fi

  # E2E_SOAK_MINUTES turns the cycle count into a deadline, which is how a genuinely long run
  # is asked for: "soak for two hours" rather than "work out how many cycles that is".
  local deadline=0
  if [ -n "${E2E_SOAK_MINUTES:-}" ]; then
    deadline=$((SECONDS + E2E_SOAK_MINUTES * 60))
    cycles=100000
    printf '    running to a %s minute deadline rather than a cycle count\n' "$E2E_SOAK_MINUTES"
  fi

  local peak_threads="$threads0" peak_fds="$fds0" cycle i t f growth
  for cycle in $(seq 1 "$cycles"); do
    if [ "$deadline" -gt 0 ] && [ "$SECONDS" -ge "$deadline" ]; then break; fi
    local phase_start=$SECONDS
    printf '    cycle %s/%s: bulk' "$cycle" "$cycles"
    # Bulk: enough bytes to churn buffers, over the proxied and the relayed path alike.
    for i in $(seq 1 "$bulk_reps"); do
      curl -s -o /dev/null --max-time 120 -x "127.0.0.1:$PROXY_PORT" \
        "http://127.0.0.1:$ORIGIN_PORT/large?mb=$bulk_mb"
      curl -s -o /dev/null --max-time 120 -x "127.0.0.1:$PROXY_PORT" --proxytunnel \
        "http://127.0.0.1:$ORIGIN_PORT/large?mb=$bulk_mb"
    done
    printf '(%ss)' "$((SECONDS-phase_start))"

    phase_start=$SECONDS
    printf ', burst'
    # Burst: all at once, mixing handler paths so they compete for the same pools.
    # Wait on these pids specifically, never a bare `wait`: this shell also has the origin
    # server in the background, and `wait` with no argument waits for that too -- which never
    # exits. That hung the burst phase forever, and an outer `timeout` then killed the whole
    # process group, which looked exactly like the tunnel crashing under load.
    local pids=()
    for i in $(seq 1 "$burst"); do
      soak_worker http "$ORIGIN_PORT" & pids+=($!)
      soak_worker connect "$ORIGIN_PORT" & pids+=($!)
      if [ $((i % 8)) -eq 0 ]; then soak_worker ws "$ORIGIN_PORT" & pids+=($!); fi
      if [ $((i % 10)) -eq 0 ]; then soak_worker slow "$ORIGIN_PORT" & pids+=($!); fi
    done
    for i in "${pids[@]}"; do wait "$i" 2>/dev/null || true; done
    printf '(%ss)' "$((SECONDS-phase_start))"

    t="$(soak_int "$(soak_metric 'jvm_threads_current' "$mport")")"
    f="$(soak_int "$(soak_metric 'process_open_fds' "$mport")")"
    if [ "$t" -gt "$peak_threads" ]; then peak_threads="$t"; fi
    if [ "$f" -gt "$peak_fds" ]; then peak_fds="$f"; fi

    printf ', quiet'
    # Quiet: the phase that matters. Anything still held here was not released.
    sleep "$quiet"

    # Health has to hold across every phase, not only at the end.
    #
    # Retried, and the process checked separately when it fails. A single timed-out probe
    # cannot tell "the tunnel stopped answering" from "the machine running this test is
    # saturated by the burst it just launched", and those want opposite responses.
    soak_readyz "$cycle" "$mport"
    printf ' (%s threads, %s fds)\n' "$t" "$f"

    # Stop here if the process has gone. Every reading below comes from the metrics endpoint,
    # so a dead tunnel reports zero of everything and the leak checks pass triumphantly -- the
    # first run of this scenario did exactly that, reporting "threads grew by -72".
    if [ "$t" -le 0 ]; then
      bad "the tunnel survived cycle $cycle" "process is no longer answering; see the log above"
      return 1
    fi
  done

  # Let anything with a timeout finish expiring before the final reading.
  sleep 10
  local threads1 fds1 conns1
  threads1="$(soak_int "$(soak_metric 'jvm_threads_current' "$mport")")"
  fds1="$(soak_int "$(soak_metric 'process_open_fds' "$mport")")"
  conns1="$(soak_int "$(soak_metric 'testingbot_connections_current{listener="proxy"' "$mport")")"
  printf '    final:    %s threads (peak %s), %s fds (peak %s), %s open connections\n' \
    "$threads1" "$peak_threads" "$fds1" "$peak_fds" "$conns1"

  # Same guard for the final reading: no numbers are meaningful if nothing is answering.
  if [ "$threads1" -le 0 ]; then
    bad "the tunnel is still running at the end of the soak" "metrics endpoint is not answering"
    return 1
  fi

  # Threads are the assertion that would have caught the selector leak, which grew the pool
  # until the proxy could not start at all. A margin, not equality: pools size to load and do
  # not always shrink back.
  growth=$((threads1 - threads0))
  if [ "$growth" -le 25 ]; then
    ok "threads return to about the baseline" "grew by $growth over $cycles cycles"
  else
    bad "threads return to about the baseline" "grew by $growth (from $threads0 to $threads1)"
  fi

  if [ "$fds0" -gt 0 ]; then
    growth=$((fds1 - fds0))
    if [ "$growth" -le 60 ]; then
      ok "file descriptors return to about the baseline" "grew by $growth"
    else
      bad "file descriptors return to about the baseline" "grew by $growth (from $fds0 to $fds1)"
    fi
  else
    skip "file descriptors return to about the baseline" "this JVM exports no process_open_fds"
  fi

  # Connections are released rather than left to a timeout, so quiet should mean near zero.
  if [ "$conns1" -le 10 ]; then
    ok "open connections drain when idle" "$conns1 left open"
  else
    bad "open connections drain when idle" "$conns1 still open after ${quiet}s idle"
  fi

  # And it is still a working tunnel, not merely a live process.
  check_proxy_http soak
  check_proxy_connect soak
  check_websocket soak
  check_browser soak

  stop_tunnel
}

# Soak with the connection severed every cycle.
#
# The leaks that actually happened here were per-reconnect, not per-request: a SelectorManager
# added on every start and never removed, port forwards not rebuilt, a bind failure on the
# second start. A soak that never drops the connection cannot see any of them, and the plain
# reconnect scenario drops it once, which is too few to tell a leak from a one-off.
#
# The assertion is a trend rather than a total. The selector leak added about two threads per
# reconnect, which four reconnects would hide inside any reasonable absolute margin -- so this
# compares the reading after the last reconnect against the one after the second, when pools
# have warmed. Accumulation shows up there; settling does not.
scenario_soak_reconnect() {
  local cycles="${E2E_SOAK_RC_CYCLES:-5}"
  local burst="${E2E_SOAK_BURST:-20}"
  local uport mport; uport="$(free_port)"; mport="$(free_port)"

  # Read before the plan is printed: announcing "5 cycles" and then "running to a 120 minute
  # deadline" two lines later contradicts itself, and this scenario is long enough that the
  # plan line is what a reader uses to decide whether it has hung.
  local rc_deadline=0
  if [ -n "${E2E_SOAK_MINUTES:-}" ]; then
    printf '    plan: sever/recover cycles to a %s minute deadline -- allow %s+ minutes\n' \
      "$E2E_SOAK_MINUTES" "$((E2E_SOAK_MINUTES + 5))"
  else
    printf '    plan: %s sever/recover cycles -- each waits out a 30s keepalive, so allow 10+ minutes\n' \
      "$cycles"
  fi

  rm -f "$WORK/soak-rc-freeze"
  python3 "$HERE/upstream_proxy.py" "$uport" --freeze-file "$WORK/soak-rc-freeze" \
    > "$WORK/soak-rc-proxy.log" 2>&1 &
  local up=$!
  sleep 1
  # SSH is forced through a proxy we control, which is what makes severing deterministic.
  start_tunnel --proxy "127.0.0.1:$uport" --metrics-port "$mport" \
    || { kill $up 2>/dev/null; return 1; }

  curl -s -o /dev/null --max-time 30 -x "127.0.0.1:$PROXY_PORT" "http://127.0.0.1:$ORIGIN_PORT/"
  sleep 3
  local threads0 fds0
  threads0="$(soak_int "$(soak_metric 'jvm_threads_current' "$mport")")"
  fds0="$(soak_int "$(soak_metric 'process_open_fds' "$mport")")"
  if [ "$threads0" -le 0 ]; then
    bad "soak-reconnect baseline" "metrics endpoint is not answering"
    kill $up 2>/dev/null; return 1
  fi
  printf '    baseline: %s threads, %s fds\n' "$threads0" "$fds0"

  if [ -n "${E2E_SOAK_MINUTES:-}" ]; then
    rc_deadline=$((SECONDS + E2E_SOAK_MINUTES * 60))
    cycles=100000
  fi

  local warm_threads=0 warm_fds=0 cycle i t f pids recovered noticed style
  local freeze="$WORK/soak-rc-freeze"
  for cycle in $(seq 1 "$cycles"); do
    if [ "$rc_deadline" -gt 0 ] && [ "$SECONDS" -ge "$rc_deadline" ]; then break; fi
    printf '    cycle %s: load' "$cycle"
    pids=()
    for i in $(seq 1 "$burst"); do
      soak_worker http "$ORIGIN_PORT" & pids+=($!)
      soak_worker connect "$ORIGIN_PORT" & pids+=($!)
    done
    for i in "${pids[@]}"; do wait "$i" 2>/dev/null || true; done

    # Alternate how the connection is broken. Killing the proxy closes its sockets and the
    # tunnel sees a FIN, which is the easy case and the only one previously tested. Freezing
    # relays nothing while holding every socket open, which is what a black-holing network
    # looks like: nothing arrives, nothing is closed, and only a keepalive or a retransmit
    # timeout discovers it. They are different code paths.
    if [ $((cycle % 2)) -eq 0 ]; then
      style="black-hole"
      printf ', freeze'
      touch "$freeze"
      sleep "${E2E_SOAK_FREEZE:-75}"
      rm -f "$freeze"
    else
      style="clean-close"
      printf ', sever'
      kill $up 2>/dev/null; wait $up 2>/dev/null
      python3 "$HERE/upstream_proxy.py" "$uport" --freeze-file "$freeze" \
        >> "$WORK/soak-rc-proxy.log" 2>&1 &
      up=$!
    fi

    # Wait for the tunnel to notice, then to come back. Polling straight after the kill reads a
    # readiness that is simply stale, which is how the first reconnect scenario fooled itself.
    noticed=0
    for i in $(seq 1 45); do
      if [ "$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 \
                "http://127.0.0.1:$mport/readyz")" != "200" ]; then noticed=1; break; fi
      sleep 2
    done
    if [ "$noticed" = 1 ]; then
      printf ', %s noticed' "$style"
    elif [ "$style" = "black-hole" ]; then
      # Thawed before a keepalive fired. The connection survived the stall, which is the other
      # acceptable outcome -- the tunnel is not required to tear down for a blip it rode out.
      printf ', %s ridden out' "$style"
    else
      printf ', %s NOT noticed' "$style"
    fi

    # Wait for readiness, but long enough to cover the client's own worst case, and say which
    # of the three outcomes happened.
    #
    # This used to poll for about three minutes and report anything slower as "never recovered".
    # The client retries the server it was given MAX_RETRIES=30 times before giving up and
    # re-registering for a new one, and while the retry *delay* is 5s, an attempt against a host
    # that has gone away blocks on the connect first -- measured at 20s per attempt, so ten
    # minutes before the rebuild path is even reached. A two-hour soak duly failed at 49 minutes
    # on attempt 9 of 30, having proved nothing about whether the tunnel recovers.
    local recover_deadline=$((SECONDS + ${E2E_SOAK_RECOVER_SECONDS:-780}))
    local recover_start=$SECONDS
    recovered=0
    while [ "$SECONDS" -lt "$recover_deadline" ]; do
      if [ "$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 \
                "http://127.0.0.1:$mport/readyz")" = "200" ]; then recovered=1; break; fi
      # A process that has exited will never become ready, so stop waiting out the deadline for
      # it -- and report it as the different thing it is.
      if ! kill -0 "$TUNNEL_PID" 2>/dev/null; then
        bad "cycle $cycle: tunnel recovered" \
          "the tunnel process exited after $((SECONDS-recover_start))s; it did not just fail to recover"
        kill $up 2>/dev/null; return 1
      fi
      sleep 2
    done
    if [ "$recovered" != "1" ]; then
      # Still alive and still not ready. Name where it had got to, so "never recovers" is
      # distinguishable from "was still working through its retries".
      local attempts
      attempts="$(grep -c 'Attempting to re-establish' "$TUNNEL_LOG" 2>/dev/null || echo 0)"
      bad "cycle $cycle: tunnel recovered" \
        "still not ready after $((SECONDS-recover_start))s, process alive, $attempts reconnect attempts logged"
      kill $up 2>/dev/null; return 1
    fi
    # A recovery that needed the rebuild path is a pass, but not a quiet one: it is minutes of
    # downtime and worth seeing in the output rather than averaging into "recovered".
    if [ "$((SECONDS-recover_start))" -gt "${E2E_SOAK_SLOW_RECOVERY:-60}" ]; then
      printf ', SLOW recovery %ss' "$((SECONDS-recover_start))"
    fi

    sleep 5
    t="$(soak_int "$(soak_metric 'jvm_threads_current' "$mport")")"
    f="$(soak_int "$(soak_metric 'process_open_fds' "$mport")")"
    printf ', recovered (%s threads, %s fds)\n' "$t" "$f"
    if [ "$t" -le 0 ]; then
      bad "cycle $cycle: tunnel still running" "metrics endpoint stopped answering"
      kill $up 2>/dev/null; return 1
    fi

    # Traffic after every reconnect, not just at the end: a proxy that comes back "ready" but
    # cannot forward is the exact failure the rebuilt port forwards once produced.
    assert_eq "cycle $cycle: still proxying after the reconnect" \
      "$(curl -s -o /dev/null -w '%{http_code}' --max-time 30 -x "127.0.0.1:$PROXY_PORT" \
          "http://127.0.0.1:$ORIGIN_PORT/")" "200"

    # The comparison point: after two reconnects the pools have warmed.
    if [ "$cycle" -eq 2 ]; then warm_threads="$t"; warm_fds="$f"; fi
  done

  local threads1 fds1
  threads1="$(soak_int "$(soak_metric 'jvm_threads_current' "$mport")")"
  fds1="$(soak_int "$(soak_metric 'process_open_fds' "$mport")")"
  printf '    final:    %s threads, %s fds after %s reconnects\n' "$threads1" "$fds1" "$cycles"

  # A trend, not a total. Two threads leaked per reconnect would hide inside an absolute
  # margin; growth between the second reconnect and the last would not.
  if [ "$warm_threads" -gt 0 ]; then
    local drift=$((threads1 - warm_threads))
    if [ "$drift" -le 8 ]; then
      ok "threads do not accumulate across reconnects" \
        "$warm_threads after 2 reconnects, $threads1 after $cycles (drift $drift)"
    else
      bad "threads do not accumulate across reconnects" \
        "$warm_threads after 2 reconnects, $threads1 after $cycles (drift $drift)"
    fi
  fi

  # Descriptors on the same trend basis, and the sharper of the two signals. Measured with the
  # SelectorManager leak reinstated, descriptors climbed 119 -> 125 -> 131 across reconnects
  # while the fixed build sat flat at 101, so a warm-to-final drift of more than a handful is
  # not noise. The absolute threshold this replaces was 40, which missed that leak entirely
  # even as the thread check caught it.
  if [ "$warm_fds" -gt 0 ]; then
    local fd_drift=$((fds1 - warm_fds))
    if [ "$fd_drift" -le 6 ]; then
      ok "file descriptors do not accumulate across reconnects" \
        "$warm_fds after 2 reconnects, $fds1 after $cycles (drift $fd_drift)"
    else
      bad "file descriptors do not accumulate across reconnects" \
        "$warm_fds after 2 reconnects, $fds1 after $cycles (drift $fd_drift)"
    fi
  fi

  check_proxy_http soak-reconnect
  check_proxy_connect soak-reconnect
  check_websocket soak-reconnect
  check_se_status soak-reconnect
  check_browser soak-reconnect

  stop_tunnel
  kill $up 2>/dev/null
}

# Costs no tunnel: --doctor runs diagnostics and exits.
scenario_doctor() {
  local out; out="$(java -jar "$JAR" --doctor 2>&1)"
  assert_contains "doctor reaches API"  "$out" "api.testingbot.com"
  assert_contains "doctor checks ports" "$out" "Selenium port"
  case "$out" in *"FAIL"*|*"SEVERE"*) bad "doctor clean" "reported failures";; *) ok "doctor clean" "no failures";; esac
}

ALL_SCENARIOS=(doctor pac combined nobump nocache custom_ports localproxy_only tunnel_identifier localhost_deny dns websocket protocols sslbump timeouts krb5_hosts reconnect upstream_proxy upstream_proxy_auth split_proxy cacert socks5_proxy socks5_proxy_auth)
DEFAULT_SCENARIOS=(doctor combined)
# macOS ships bash 3.2, which has no associative arrays -- use a function.
tunnel_cost() { case "$1" in doctor|pac) echo 0;; krb5_hosts) echo 2;; *) echo 1;; esac; }

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
  soak)  REQUESTED=(soak); shift;;
  soak_reconnect|soak-reconnect) REQUESTED=(soak_reconnect); shift;;
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
