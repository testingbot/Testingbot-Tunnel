# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Development Commands

**Build the project:**
```bash
mvn package
```
This creates a shaded JAR in the `target/` directory with all dependencies included.

**Run the application:** (the runnable uber jar is the `shaded` classifier; the main jar is thin)
```bash
java -jar target/TestingBotTunnel-5.0-shaded.jar <API_KEY> <API_SECRET>
```
Or use environment variables:
```bash
export TESTINGBOT_KEY=<your_key>
export TESTINGBOT_SECRET=<your_secret>
java -jar target/TestingBotTunnel-5.0.jar
```

**Docker build:**
```bash
docker buildx build --platform linux/amd64,linux/arm64 \
  --no-cache \
  --push \
  -t testingbot/tunnel:5.0 \
  -t testingbot/tunnel:latest .
```

**Run diagnostics:**
```bash
java -jar testingbot-tunnel.jar --doctor
```

## Architecture Overview

### Core Components

**Main Application (App.java)**: Central orchestrator that:
- Parses command-line arguments and configuration
- Manages SSH tunnel lifecycle through SSHTunnel
- Coordinates HTTP proxy setup through HttpProxy
- Handles API communication with TestingBot service
- Provides health checking via Doctor class

**SSH Tunnel Layer**: 
- Uses the `com.github.mwiede:jsch` fork for SSH connectivity
- `SSHTunnel` class establishes secure connection to TestingBot infrastructure on port 443
- Creates port forwarding: local proxy port → TestingBot's port 2010, and TestingBot hub → local SSH port
- Includes keep-alive mechanism and connection monitoring

**HTTP Proxy Layer**:
- Built on Eclipse Jetty 12's core Handler API (no Servlet layer)
- `HttpProxy` sets up local proxy server (default port 8087, configurable)
- Handler chain, outermost first: `GracefulHandler` -> `WebsocketHandler` ->
  `CustomConnectHandler` -> `TunnelProxyHandler`. Each wrapper takes the requests it
  owns and delegates the rest inward
- `TunnelProxyHandler` (`ProxyHandler.Forward`) proxies plain HTTP
- `CustomConnectHandler` (`ConnectHandler`) manages HTTPS CONNECT; fast-fail lives in
  its `validateDestination` override
- `WebsocketHandler` (`ConnectHandler`) relays WebSocket upgrades
- Supports upstream proxy chaining, basic authentication, and custom headers

**API Integration**:
- `Api` class manages TestingBot service communication
- Handles tunnel creation, status polling, and cleanup
- Manages tunnel lifecycle events and error handling

### Request Flow

1. Local Selenium tests connect to local port (default 4445)
2. `HttpForwarder` intercepts and forwards Selenium commands through SSH tunnel to TestingBot hub
3. Web traffic from tests routes through local HTTP proxy (port 8087)
4. HTTP proxy forwards requests through SSH tunnel to TestingBot infrastructure
5. TestingBot browsers access local/staging environments via reverse tunnel

### Key Configuration Options

- `--se-port`: Local Selenium port (default 4445)
- `--localproxy`: Local HTTP proxy port (default 8087)
- `--fast-fail-regexps`: Domains to refuse, comma separated. Prefix an entry with `!` to make
  it an exception, so `.*,!ok\.com` blocks everything except `ok.com`
- `--auth`: Basic authentication for specific hosts
- `--proxy`: Upstream proxy configuration (used by browser traffic *and* the SSH connection)
- `--proxy-auth-scheme`: `basic` (default) or `negotiate` (SPNEGO/Kerberos) for the upstream proxy
- `--proxy-spn`, `--krb5-keytab`, `--krb5-principal`: Kerberos settings for `negotiate`
- `--log-http`: HTTP logging detail -- `none`, `url`, `headers`, or `errors` (default)
- `--request-id-header`: Correlation header name (default `X-Request-Id`)
- `--extra-headers`: Custom HTTP request headers to add (JSON map)
- `--header` / `--response-header`: Edit request/response headers. Repeatable. Grammar:
  `name: value` sets, `name;` sets empty, `-name` removes, `-name*` removes by prefix
- `--tunnel-identifier`: Multiple tunnel support
- `--nobump`: Disable SSL certificate rewriting
- `--nocache`: Bypass TestingBot caching layer
- `--connect-to`: Dial `HOST2:PORT2` for requests naming `HOST1:PORT1`, leaving the URL, Host
  header and TLS SNI untouched (`HOST1:PORT1:HOST2:PORT2`, comma separated)
- `--localhost-policy`: `allow` (default) or `deny` for tunnel traffic reaching this machine's
  loopback interface
- `--pac-local`: Evaluate a PAC file locally to choose egress per destination. Distinct from
  `--pac`, which forwards a PAC URL to the remote browser
- `--pac-test`: Evaluate `--pac-local` against one URL and exit
- `--metrics-port`: Port for the insight endpoints (default 8003)
- `--ready`: Query a running tunnel's `/readyz` and exit 0 (ready) or 1 (not ready)

### PAC evaluation

`--pac-local` is evaluated by `com.testingbot.tunnel.pac`, a restricted interpreter written for
this purpose -- Nashorn is gone as of Java 15 and embedding GraalVM JS would be a large
dependency and a large attack surface for a process in the network path.

`PacLexer` -> `PacParser` -> `PacInterpreter`, with `PacDateTime` for the time predicates,
`PacResult` for the returned directive list and `PacPolicy` for loading and per-host caching.

The design rule is that unsupported syntax is **refused with its line number**, never
approximated: a misread PAC file silently routes customer traffic to the wrong place. Object
literals, regular expressions, `new`, closures and top-level statements are all rejected.

### Upstream proxy authentication

`--proxy-auth-scheme negotiate` authenticates to the upstream proxy with SPNEGO/Kerberos, for
enterprise networks that require it. Scope is the **upstream proxy only** -- per-site `--auth`
stays Basic.

Credentials come from the ambient ticket cache, or from `--krb5-keytab` with
`--krb5-principal` for unattended use where nobody has run `kinit`. The service principal
defaults to `HTTP/<proxy-host>` and can be overridden with `--proxy-spn`.

All three egress paths use it: the CONNECT tunnel, the plain-HTTP client, and the SSH
connection. The CONNECT and SSH paths send the header pre-emptively; the plain-HTTP path uses
jetty-client's 407-challenge handling.

`--doctor` reports the whole chain -- JGSS availability, krb5 config, ticket cache or keytab,
the SPN that will be requested, and whether a service ticket can actually be obtained. Every one
of these otherwise fails as an indistinguishable 407.

### HTTP logging

`--log-http` controls per-request logging, emitted by `HttpLogHandler`, which sits outermost in
the proxy chain so plain HTTP, CONNECT and WebSocket upgrades share one switch:

| Mode | Logs |
|---|---|
| `none` | nothing |
| `url` | one line per request: method, target, status, duration |
| `headers` | as `url`, plus request headers |
| `errors` (default) | as `headers`, but only for failed or 5xx responses |

**This changed the default behaviour**: before TB-319 every request produced an INFO line.
With `errors`, successful requests log nothing. Use `--log-http url` to get the old volume back.

Every request carries a correlation id, logged in `[brackets]` and passed to the target in
`--request-id-header`. An incoming value is reused so a trace starting in the test framework
stays joined up. The id is forwarded even when `--log-http none` is set.

Header values are redacted via `SensitiveHeaders.redactValue`, so `Authorization` and
TestingBot credentials do not reach whatever collects these logs.

### Header rules

`--header` and `--response-header` apply to **plain HTTP only**. HTTPS arrives as a CONNECT and
is relayed as opaque bytes, so there is nothing local to edit; changing headers on HTTPS
requires the remote SSL-bump path (i.e. not `--nobump`).

Removals are applied before sets, so `-X` and `X: 1` together always mean "replace",
independent of argument order. Rules run after `--extra-headers` and after the `X-Forwarded-*`
headers are generated, so they can override either.

### Configuration sources

Every option can come from three places. Precedence is command line, then `--config` file, then
environment. Each long option has a `TESTINGBOT_*` alias derived from its name, so `--se-port`
reads `TESTINGBOT_SE_PORT` -- this is what lets a container be configured without building a
command line. `--help`, `--version`, `--doctor`, `--ready` and `--config` are excluded, as is
`TESTINGBOT_AUTH`, which is comma-split into several `--auth` values elsewhere.

### Insight endpoints (metrics port, default 8003)

| Path | Purpose |
|---|---|
| `/` | JSON status: version, uptime, request count, bytes transferred |
| `/metrics` | Prometheus exposition; honours `--metrics-auth` and `?name[]=` filtering |
| `/healthz` | Liveness -- 200 whenever the process is answering, including during a reconnect |
| `/readyz` | Readiness -- 200 when the tunnel is forwarding, 503 otherwise |

`/healthz` and `/readyz` are deliberately not behind `--metrics-auth`: container probes cannot
easily carry credentials, and they disclose nothing beyond up/down. Prefer them over
`--readyfile`, which is written once and never removed, so it cannot express a tunnel that was
ready and has since lost its connection.

## Development Notes

- Main class: `com.testingbot.tunnel.App`
- Requires Java 17+ (compiled with release 17)
- Uses Maven Shade plugin to create fat JAR with minimized dependencies
- Logging configured via Logback (src/main/resources/logback.xml)
- 209 unit/integration tests (`mvn test`); end-to-end suite against real browsers in `e2e/`
- SSH via the maintained JSch fork `com.github.mwiede:jsch`
- The SSH connection honours `--proxy` (HTTP CONNECT or SOCKS5), so it works on
  networks whose only egress is a proxy
- Jetty 12.1.x core Handler API for HTTP/proxy functionality; no Servlet/Jakarta EE dependency