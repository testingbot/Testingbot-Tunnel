#!/usr/bin/env bash
# Minimal W3C WebDriver client over curl. No Selenium bindings required, and it
# talks to exactly the endpoint we want to exercise: the tunnel's local Selenium
# port, which HttpForwarder/ForwarderServlet relay to hub.testingbot.com.

wd_new_session() {   # $1 se-port, $2 test name, $3 optional tunnel identifier,
                     # $4 "insecure" to accept self-signed certificates
  local port="$1" name="$2" ident="${3:-}" insecure="${4:-}" tbopts extra=""
  tbopts="\"name\":\"${name}\",\"build\":\"${E2E_BUILD:-tunnel-e2e}\""
  [ -n "$ident" ] && tbopts="${tbopts},\"tunnel-identifier\":\"${ident}\""
  # wss:// to a local origin means a self-signed certificate, and without this the browser
  # refuses the socket before the tunnel is ever involved.
  [ "$insecure" = "insecure" ] && extra="\"acceptInsecureCerts\":true,"
  curl -s --max-time 300 -X POST "http://127.0.0.1:${port}/wd/hub/session" \
    -H 'Content-Type: application/json' \
    -d "{\"capabilities\":{\"alwaysMatch\":{
          \"browserName\":\"${E2E_BROWSER:-chrome}\",
          \"browserVersion\":\"${E2E_BROWSER_VERSION:-150}\",
          \"platformName\":\"${E2E_PLATFORM:-LINUX}\",
          ${extra}
          \"tb:options\":{${tbopts}}}}}" \
  | python3 -c 'import json,sys
try:
    v = json.load(sys.stdin).get("value", {})
except Exception:
    print(""); sys.exit(0)
print(v.get("sessionId") or "")'
}

wd_goto() {          # $1 se-port, $2 session, $3 url
  curl -s --max-time 180 -o /dev/null -w '%{http_code}' \
    -X POST "http://127.0.0.1:$1/wd/hub/session/$2/url" \
    -H 'Content-Type: application/json' -d "{\"url\":\"$3\"}"
}

# Navigation, with the driver's own explanation kept. A bare status code turns a certificate
# refusal, an unreachable host and a malformed capability into the same "500".
wd_goto_verbose() {  # $1 se-port, $2 session, $3 url -> "CODE|message"
  curl -s --max-time 180 -w '\n%{http_code}' \
    -X POST "http://127.0.0.1:$1/wd/hub/session/$2/url" \
    -H 'Content-Type: application/json' -d "{\"url\":\"$3\"}" \
  | python3 -c 'import json,sys
raw = sys.stdin.read().rsplit("\n", 1)
code = raw[-1].strip()
message = ""
try:
    value = json.loads(raw[0]).get("value") or {}
    if isinstance(value, dict):
        message = value.get("message") or value.get("error") or ""
except Exception:
    message = raw[0][:200]
print(code + "|" + message.replace("\n", " ")[:300])'
}

wd_source() {        # $1 se-port, $2 session
  curl -s --max-time 120 "http://127.0.0.1:$1/wd/hub/session/$2/source" \
  | python3 -c 'import json,sys
try:
    print(json.load(sys.stdin).get("value") or "")
except Exception:
    print("")'
}

wd_title() {         # $1 se-port, $2 session
  curl -s --max-time 120 "http://127.0.0.1:$1/wd/hub/session/$2/title" \
  | python3 -c 'import json,sys
try:
    print(json.load(sys.stdin).get("value") or "")
except Exception:
    print("")'
}

wd_delete() {        # $1 se-port, $2 session
  curl -s --max-time 120 -o /dev/null -X DELETE "http://127.0.0.1:$1/wd/hub/session/$2"
}
