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
| `combined` | 1 | proxying, CONNECT, `--extra-headers`, `--fast-fail-regexps`, `--metrics-auth`, Selenium session |
| `nobump` | 1 | `--nobump` + browser |
| `nocache` | 1 | `--nocache` |
| `custom_ports` | 1 | `--se-port`, `--localproxy`, `--metrics-port` + browser |
| `localproxy_only` | 1 | `--localproxy` alone, to isolate it from the other port flags |
| `tunnel_identifier` | 1 | `--tunnel-identifier` + browser using that identifier |
| `upstream_proxy` | 1 | `--proxy` chaining through a local upstream proxy |

## Files

- `run-e2e.sh` — harness and scenarios
- `webdriver.sh` — minimal W3C WebDriver client over `curl` (no Selenium bindings needed)
- `origin_server.py` — local origin: marker page, header echo, slow endpoint
- `upstream_proxy.py` — minimal upstream HTTP proxy for `--proxy`
