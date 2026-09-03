# End-to-end tests

Boots the real tunnel against TestingBot and drives traffic through it, including
a real remote browser loading a page that only this machine serves — which is the
only way to prove the whole path works:

```
remote Chrome ─▶ TestingBot ─▶ SSH reverse tunnel ─▶ local Jetty proxy ─▶ local origin server
```

## Running

```bash
mvn package                  # the harness uses target/TestingBotTunnel-*-shaded.jar
e2e/run-e2e.sh               # default: doctor + combined (2 tunnel starts)
e2e/run-e2e.sh --all         # every scenario, paced
e2e/run-e2e.sh --list        # scenarios and their tunnel cost
e2e/run-e2e.sh nobump        # a single scenario
```

Credentials come from `TESTINGBOT_KEY`/`TESTINGBOT_SECRET` or `~/.testingbot`.

| Variable | Default | Purpose |
|---|---|---|
| `E2E_SKIP_BROWSER` | `0` | `1` runs proxy-level checks only — no browser VMs |
| `E2E_PACE_SECONDS` | `15` | Sleep between tunnel starts |
| `E2E_BROWSER` / `E2E_BROWSER_VERSION` / `E2E_PLATFORM` | `chrome` / `150` / `LINUX` | Browser under test |
| `E2E_KEEP_LOGS` | `0` | `1` keeps tunnel logs after the run |
| `E2E_ORIGIN_PORT` | random free port | Pin the local origin server port |

## Ports

Every port the harness uses is allocated at run time by `free_port`, which checks
both `127.0.0.1` and the wildcard address before accepting a candidate. That
second check matters: on macOS a process bound to `127.0.0.1:PORT` coexists
happily with a Jetty bound to `[::]:PORT`, so the tunnel starts without a
`BindException` — but its reverse forward dials `127.0.0.1`, and traffic from the
remote browser silently reaches the *other* process instead.

That failure is deceptive, because local checks still pass if the squatter
happens to be a working proxy. Each scenario therefore asserts
`proxy-port-exclusive`: the tunnel process must be the only listener on its proxy
port. If that assertion fails, look for the other listener rather than suspecting
the tunnel:

```bash
lsof -nP -iTCP:<port> -sTCP:LISTEN
```

## Account quotas

TestingBot limits both concurrent tunnels and tunnel starts per hour, and asks
callers to *"keep a tunnel running and reuse it across tests"*. The harness is
built around that:

- the default `combined` scenario exercises extra headers, fast-fail, metrics
  auth, proxying, CONNECT and a browser session on **one** tunnel;
- scenarios needing genuinely different CLI arguments cost one start each and
  are opt-in via `--all` or by name;
- starts are paced by `E2E_PACE_SECONDS`.

If you hit a quota the harness says so explicitly rather than timing out. Check
and clean up stale tunnels with:

```bash
curl -u "$TESTINGBOT_KEY:$TESTINGBOT_SECRET" https://api.testingbot.com/v1/tunnel/list
curl -u "$TESTINGBOT_KEY:$TESTINGBOT_SECRET" -X DELETE https://api.testingbot.com/v1/tunnel/<id>
```

Note other tools on the same machine (IDE plugins, other checkouts, CI agents)
may hold tunnel slots on the same account.

## Scenarios

| Scenario | Tunnels | Covers |
|---|---|---|
| `doctor` | 0 | `--doctor` diagnostics |
| `combined` | 1 | proxying, CONNECT, WebSocket relay, 8 MiB payloads, `--extra-headers`, `--auth`, `--fast-fail-regexps`, `--metrics-auth` and moving counters, Selenium session |
| `nobump` | 1 | `--nobump` + browser |
| `nocache` | 1 | `--nocache` |
| `custom_ports` | 1 | `--se-port`, `--localproxy`, `--metrics-port` + browser |
| `localproxy_only` | 1 | `--localproxy` alone, to isolate it from the other port flags |
| `tunnel_identifier` | 1 | `--tunnel-identifier` + browser using that identifier |
| `pac` | 0 | `--pac-local` routing decisions via `--pac-test`, and refusal of unsupported syntax |
| `localhost_deny` | 1 | `--localhost-policy deny` over CONNECT and plain HTTP |
| `dns` | 1 | `--dns` server list against local servers, using `.invalid` names nothing else can resolve, including fall-through past a dead entry |
| `websocket` | 1 | `ws://` through both handler paths and a real browser; `wss://` through the CONNECT relay |
| `protocols` | 1 | which HTTP versions survive the tunnel, and streamed-vs-buffered responses |
| `sslbump` | 1 | `--nobump` passes TLS through untouched, asserted via the tunnel client's own log |
| `reconnect` | 1 | severs the SSH connection and asserts recovery, readiness and traffic afterwards |
| `upstream_proxy` | 1 | `--proxy` chaining through a local upstream proxy |
| `upstream_proxy_auth` | 1 | the same, through a proxy that demands Basic credentials |
| `split_proxy` | 1 | `--proxy` and `--proxy-testingbot` as two different proxies with different credentials, each asserted to see only its own traffic |
| `cacert` | 1 | `--cacert-file` against a proxy that really intercepts TLS: the tunnel must fail to start without it |
| `timeouts` | 1 | `--http-idle-timeout` closes an idle tunnel; `--http-dial-timeout` gives up early |
| `krb5_hosts` | 2 | `--krb5-hosts` withholds credentials and falls back to `--auth` when no ticket can be had |
| `socks5_proxy` | 1 | `--proxy socks5://` chaining through a local SOCKS5 proxy |
| `socks5_proxy_auth` | 1 | the same, through a SOCKS5 proxy that demands RFC 1929 credentials |

## Soak

`e2e/run-e2e.sh soak` is opt-in and not part of `--all`: it runs for many minutes. It cycles
bulk transfer, a concurrent burst across all four handler paths, and a quiet period, then checks
that threads, file descriptors and open connections come back to about the baseline -- the shape
of the SelectorManager leak that once grew the pool until the proxy could not start.

Two things it now guards against, both learned by getting them wrong:

* **A dead tunnel must not read as a clean result.** Every measurement comes from the metrics
  endpoint, so a process that has gone reports zero of everything. The first run announced
  "threads grew by -72" and passed. The scenario now refuses to interpret any reading unless the
  process is answering, and says whether a failed probe means a dead process or a live one that
  did not respond.
* **Give it far more time than you think.** The first two runs were killed by an outer
  `timeout`, which takes the tunnel down with the process group and looks identical to a crash.
  The scenario prints its plan and a lower bound for the wall clock before it starts. Tune with
  `E2E_SOAK_CYCLES`, `E2E_SOAK_QUIET`, `E2E_SOAK_BURST`, `E2E_SOAK_BULK_MB`, `E2E_SOAK_BULK_REPS`.

Recorded baseline, 12 cycles at 40-way burst (~5 minutes), browser check skipped:

| | baseline | peak | final |
|---|---|---|---|
| threads | 72 | 87 | 87 |
| file descriptors | 106 | 111 | 109 |
| open connections | 1 | — | 0 |

Growth plateaus rather than accumulating: +9 threads over 3 cycles and +15 over 12, with the
peak equal to the final, which is pool sizing settling rather than a leak. `readyz` answered 200
after every cycle, connections drained to zero on every quiet period, and proxied HTTP, CONNECT,
WebSocket and a real cloud browser all still worked at the end.

A third bug lived here too, and it was the cause of the timeouts above: the burst phase ended in
a bare `wait`, which waits for every background job of the shell -- including the origin server,
which never exits. The phase hung forever. It now waits on the worker pids it collected, and a
cycle takes about twenty seconds instead of never finishing.

### Soak with reconnects

`E2E_SOAK_MINUTES` turns the cycle count into a deadline, so a long run is asked for as "two
hours" rather than as a cycle arithmetic problem.

Severing alternates two styles, because they are different code paths and only the first was
ever tested. Killing the upstream proxy closes its sockets and the tunnel sees a FIN.
`--freeze-file` makes the proxy stop relaying while holding every socket open, which is what a
black-holing network looks like: nothing arrives, nothing closes, and the connection is only
discovered dead by a keepalive or a retransmit timeout. A freeze that is thawed before a
keepalive fires reports "ridden out" and passes -- the tunnel is not obliged to tear down a
connection it rode through.

Recorded run, 32 minutes, 21 sever/recover cycles (11 clean-close, 10 black-hole, one of which
was detected by the keepalive rather than ridden out):

    threads  71 76 70 77 70 76 71 77 71 81 71 76 71 78 71 80 71 78 71 77 71
    fds      101 for all 21 cycles

Drift +0.1 threads over 21 reconnects. The oscillation is the two sever styles, not a leak: odd
cycles are clean closes and read 71, even cycles are measured while a freeze still holds the
burst's connections open and read 76-81. The last reading is back at 71.

`e2e/run-e2e.sh soak_reconnect` severs the SSH connection on every cycle, because the leaks that
actually happened here were per-reconnect rather than per-request: a SelectorManager added on
every start and never removed, port forwards not rebuilt, a bind failure on the second start. A
soak that never drops the connection cannot see any of them, and the plain `reconnect` scenario
drops it once, which cannot tell a leak from a one-off.

**Waiting long enough to have measured anything.** The recovery poll used to run for about
three minutes and report anything slower as "never recovered". The client retries the server it
was given `MAX_RETRIES=30` times before giving up and re-registering for a new one; the retry
delay is 5s, but an attempt against a host that has gone away blocks on the connect first --
measured at 20s per attempt, so **ten minutes** before the rebuild path is even reached. A
two-hour run duly failed at 49 minutes on attempt 9 of 30, having proved nothing either way.

The wait is now `E2E_SOAK_RECOVER_SECONDS` (default 780) and reports which of three things
happened: ready again, the process exited, or still alive and not ready after the deadline --
the last naming how many reconnect attempts had been logged. A recovery slower than
`E2E_SOAK_SLOW_RECOVERY` (default 60s) still passes but is printed as `SLOW recovery Ns`,
because a rebuild is minutes of downtime and should not average quietly into "recovered".

It asserts a **trend**, not a total: the reading after the last reconnect against the one after
the second, when pools have warmed. That distinction matters — the real leak added about two
threads per reconnect, which four reconnects hide inside any reasonable absolute margin.

Verified by reinstating the leak rather than by choosing thresholds and hoping:

| | threads (c3 → c5 → final) | descriptors | verdict |
|---|---|---|---|
| fixed | 71 → 72 → 77 (drift 3) | 101 flat (drift 0) | passes |
| leak reinstated | 79 → 83 → 86 (drift 9) | 119 → 131 (drift 12) | **fails** |

That exercise also showed the first version of this scenario was too loose: it caught the leak on
threads by a margin of one, and missed the descriptor growth completely at an absolute threshold
of 40. Both checks are now warm-to-final drifts, which separate the two builds cleanly.

## Files

- `run-e2e.sh` — harness and scenarios
- `webdriver.sh` — minimal W3C WebDriver client over `curl` (no Selenium bindings needed)
- `origin_server.py` — local origin: marker page, header echo, slow endpoint, large body, Basic-auth-protected page, WebSocket echo
- `upstream_proxy.py` — minimal upstream HTTP proxy for `--proxy`
- `socks5_proxy.py` — minimal upstream SOCKS5 proxy for `--proxy socks5://`
- `ws_client.py` — WebSocket client that upgrades through the local proxy
- `dns_server.py` — minimal authoritative DNS server for `--dns`
- `mitm_proxy.py` — upstream proxy that intercepts TLS with its own CA, for `--cacert-file`
- `idle_probe.py` — holds a CONNECT tunnel idle to see whether the idle timeout closes it

### What is covered by integration tests rather than e2e

`--dns-round-robin` and `--dns-timeout` select between resolvers inside the client and never
reach the tunnel, so an e2e run would exercise nothing the unit tests do not.
`CustomDnsResolverMultiServerTest` drives them against real UDP servers, including ones that
accept a query and never answer, which is the case that matters. Spending a tunnel start on
them would buy no coverage.

`--nobump-domains` has no behavioural e2e because nothing server-side consumes the parameter:
it has no column and no code in either the API or the hub. It is also the one bump option that
port selection cannot serve, since a single `sslProxy` value cannot vary per domain. The
`sslbump` scenario is its acceptance test for when that is built.

`--nobump` itself is covered and passes. It previously did not, and the reason is a trap worth
naming: **a browser given `localhost` over HTTPS never reaches the tunnel.** It resolves the
name on its own VM and answers `ERR_SSL_PROTOCOL_ERROR` from whatever is on that loopback, on a
random port and on 8080 alike -- 8080 being one of the ports the VM registers a local listener
for, so this is not about which port is used. Plain HTTP to `localhost` *does* travel through
the tunnel, and that asymmetry is what hid it: two scenarios concluded "the server does not
splice" and "the certificate must be untrusted" from failures where no request had left the
browser VM.

Browser-facing scenarios therefore address the local origin as `$TUNNEL_HOST_NAME`
(`localtest.me` by default, a public name resolving to 127.0.0.1, so the tunnel client dials the
same origin). Override with `E2E_TLS_HOST` where that name cannot be resolved. The `sslbump`
scenario additionally asserts the CONNECT appears in the tunnel client's log, so a navigation
that succeeds *without* traversing the tunnel fails the test rather than passing it.

`--krb5-hosts` has only its negative half in e2e. Minting a ticket needs a KDC, and Kerby is a
Java test dependency the shaded jar cannot reach, so the positive case -- a listed host receiving
a ticket an origin verifies with `acceptSecContext` -- lives in `NegotiateOriginTest`. That test
also makes the distinction the e2e cannot: with no KDC in the harness, an absent `Negotiate`
header cannot tell "correctly withheld" from "could not be produced".

### HTTP versions

Measured by the `protocols` scenario rather than assumed:

| | Result |
|---|---|
| HTTP/1.1, plain proxying | by construction — the proxy port is a bare `HttpConnectionFactory` and ProxyHandler's client is HTTP/1.1-only |
| HTTP/2 over CONNECT | works. The relay is opaque, so ALPN is negotiated end to end |
| HTTP/2 with SSL bumping | works. Squid terminates the TLS and its ALPN does offer h2 |
| HTTP/3 | **not carried.** QUIC is UDP; an HTTP CONNECT tunnel is TCP. Carrying UDP through one needs CONNECT-UDP (RFC 9298), which nothing in this path implements, so a proxied client falls back to h2 or 1.1 |

The h3 check only runs on a curl built with HTTP3 support, which most are not; it is skipped
with that reason rather than silently passing.

Server-side streaming is checked by timing rather than content: a proxy that buffers a whole
response still delivers every byte, so only the gap between the first byte and the last
distinguishes the two. Checked proxied, through a CONNECT tunnel, and from a real browser
consuming an `EventSource` — the last of which is the only one with Squid in the path.

### wss:// from a browser

The `websocket` scenario checks `wss://` through the tunnel's own CONNECT relay, which is the
tunnel's actual responsibility, and skips the browser half unless `E2E_TLS_CERT` and
`E2E_TLS_KEY` point at a certificate the remote side trusts.

A self-signed origin fails from the browser with `ERR_SSL_PROTOCOL_ERROR`, **identically with
and without `--nobump`** -- so it is the certificate, not the SSL bumping. `acceptInsecureCerts`
does not help: it relaxes the browser, and the refusal happens before that. The scenario proves
the browser reaches `https://` through the tunnel in the same run, so the skip cannot hide a
genuinely broken TLS path.
