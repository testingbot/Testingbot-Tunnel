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
- `--bind-address`: Interface for every local listener -- the Selenium relay, the local proxy,
  the insight endpoints and `--web`. Default `127.0.0.1`. These listeners do not authenticate:
  the relay attaches the account key and secret to everything it forwards, and the proxy will
  connect anywhere this machine can, including its own loopback. `0.0.0.0` hands both to any
  host that can route here, so it is opt-in and logs a warning. The reverse SSH forward
  delivers to `127.0.0.1`, so the default costs the product's own path nothing. The Docker
  image sets `TESTINGBOT_BIND_ADDRESS=0.0.0.0`, because a loopback bind is per network
  namespace and the container has its own -- there the narrowing belongs on the host side of
  the port publish (`-p 127.0.0.1:4445:4445`)
- `--allow-hosts`: Only these hosts may be reached; everything else gets 403. An entry is an
  exact host or `*.suffix` for subdomains, which does *not* match the apex. The inverse of
  `--fast-fail-regexps`: that denies a named few and permits the rest, this permits a named few
  and denies the rest. Omitted means any host, so the default is unchanged
- `--fast-fail-regexps`: Domains to refuse, comma separated. Prefix an entry with `!` to make
  it an exception, so `.*,!ok\.com` blocks everything except `ok.com`
- `--auth`: Basic authentication for specific hosts
- `--proxy`: Upstream proxy for test traffic, and for reaching TestingBot unless
  `--proxy-testingbot` is given
- `--krb5-hosts`: Send SPNEGO/Negotiate credentials to these hosts as well as to the upstream
  proxy, for intranet sites that authenticate with Kerberos. Empty by default, never wildcarded,
  plain HTTP only
- `--http-dial-timeout`, `--http-idle-timeout`: Seconds for TCP connect and for an idle
  connection. Absent means each path keeps its own default, which differ deliberately -- the API
  gives up in 5s, a CONNECT tunnel idles for 300s. There is no total-response timeout: that
  would abort a healthy large download for taking a while, and stalls are what the idle timeout
  is for
- `--cacert-file`: Trust an additional certificate authority (PEM). Repeatable. For networks
  where a proxy intercepts TLS and re-signs with an internal CA -- without it the API
  connection fails and the tunnel never starts. Added to the platform trust store, not
  replacing it
- `--proxy-testingbot`, `--proxy-testingbot-userpwd`: A separate upstream proxy for reaching
  TestingBot itself -- the API and the SSH control connection. For networks where the proxy
  allowed out to the internet is not the one that reaches internal test targets. Credentials are
  deliberately *not* inherited from `--proxy-userpwd`: they belong to a different proxy
- `--proxy-auth-scheme`: `basic` (default) or `negotiate` (SPNEGO/Kerberos) for the upstream proxy
- `--proxy-spn`, `--krb5-keytab`, `--krb5-principal`: Kerberos settings for `negotiate`
- `--log-format`: `text` (default) or `json`. JSON emits one object per record, so a collector
  need not guess where a multi-line message or a stack trace ends
- `--log-http`: HTTP logging detail -- `none`, `url`, `headers`, or `errors` (default).
  Per module as `proxy:LEVEL` / `forwarder:LEVEL`, comma separated. `body` adds the redacted
  request body and is available for `forwarder` only
- `--request-id-header`: Correlation header name (default `X-Request-Id`)
- `--extra-headers`: Custom HTTP request headers to add (JSON map)
- `--header` / `--response-header`: Edit request/response headers. Repeatable. Grammar:
  `name: value` sets, `name;` sets empty, `-name` removes, `-name*` removes by prefix
- `--tunnel-identifier`: Multiple tunnel support
- `--nobump`: Disable SSL certificate rewriting for every host
- `--nobump-domains`: Disable it for named hosts only, comma separated. For a tunnel reaching
  several environments where one presents a certificate Squid cannot re-sign. Ignored when
  `--nobump` is given, which already covers everything
- `--nocache`: Bypass TestingBot caching layer
- `--dns`: Resolve through specific DNS servers, comma separated. The first is primary and the
  rest are tried in order when it does not answer; an unusable entry is dropped rather than
  costing the ones that work. Falls back to the platform resolver when none can answer
- `--dns-timeout`: Seconds to wait per DNS server (default 5)
- `--dns-round-robin`: Spread queries across the `--dns` servers instead of preferring the first
- `--connect-to`: Dial `HOST2:PORT2` for requests naming `HOST1:PORT1`, leaving the URL, Host
  header and TLS SNI untouched (`HOST1:PORT1:HOST2:PORT2`, comma separated)
- `--ws-proxy-mode`: How a `ws://` upgrade traverses `--proxy`. `connect` (default) asks the
  proxy for a tunnel and upgrades inside it, which is what RFC 6455 s4.1 specifies -- "connect to
  that proxy and ask it to open a TCP connection to the host", phrased for both schemes, with a
  worked example that is a plain CONNECT to port 80 -- and what browsers do. `get` sends the
  upgrade as an absolute-URI request, for proxies that forward `Upgrade` but only allow CONNECT
  to 443. Squid needs `http_upgrade_request_protocols` for `get` to work at all. Does not affect
  `wss://`, which arrives as a CONNECT and never reaches this decision
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

### Global Authenticator state

Two places install a JVM-wide `Authenticator.setDefault`: `App.setProxyAuth` for
`--proxy-userpwd`, and `Api` for SOCKS5 credentials, because the JDK's SOCKS client offers no
other hook. Both are scoped and both chain.

`ProxyAuth` used to answer **every** request in the process with the proxy password -- no
requestor type, host, port or protocol check -- so anything that consulted the default
authenticator got the customer's credentials, including, when the tunnel is embedded, the host
application's own traffic. It now answers only a proxy challenge from the configured proxy, and
delegates everything else to whatever authenticator it replaced.

`Api`'s keeps SOCKS credentials in a registry keyed by host:port rather than closing over the
first proxy it sees, installs itself only when it is not already the default, and likewise
delegates what it does not recognise.

### Upstream proxy authentication

`--proxy-auth-scheme negotiate` authenticates to the upstream proxy with SPNEGO/Kerberos, for
enterprise networks that require it. Scope is the **upstream proxy only** -- per-site `--auth`
stays Basic.

Credentials come from the ambient ticket cache, or from `--krb5-keytab` with
`--krb5-principal` for unattended use where nobody has run `kinit`. The service principal
defaults to `HTTP/<proxy-host>` and can be overridden with `--proxy-spn`.

All three egress paths use it: the CONNECT tunnel, the plain-HTTP client, and the SSH
connection. With `--proxy-testingbot` the SSH and API paths authenticate to that proxy instead,
with their own credentials and their own SPN; `--doctor` reports a second service principal when
the two proxies are different hosts.

All three send the header pre-emptively. The plain-HTTP path used to rely on jetty-client's
407-challenge handling, but Jetty 12's `ProxyHandler.newHttpClient()` ends with
`protocolHandlers.clear()`, which removes the handler that answers the challenge -- so the
configured `SPNEGOAuthentication` could never fire. `--auth` was broken the same way.

`--doctor` reports the whole chain -- JGSS availability, krb5 config, ticket cache or keytab,
the SPN that will be requested, and whether a service ticket can actually be obtained. Every one
of these otherwise fails as an indistinguishable 407.

### Kerberos to origins

`--proxy-auth-scheme negotiate` authenticates to the *proxy*. `--krb5-hosts` is the other
direction: SPNEGO sent to the origin itself, for intranet sites that authenticate with Kerberos.
Same credentials -- ticket cache or `--krb5-keytab` -- and the SPN is derived per host as
`HTTP/<host>`.

It is an allowlist, empty by default, and wildcards are refused. A service ticket names the user
and a host that receives one can prove to the KDC that the user talked to it, so a host gets a
ticket only if it was written down. `NegotiateOriginTest` asserts the negative case directly: an
unlisted host receives no `Authorization` header at all, not a rejected one.

A fresh token per request, because SPNEGO tokens carry a timestamp and sequence and replaying
one is what a service is meant to reject. A credential the client supplied itself is never
overwritten; where a host appears under both `--krb5-hosts` and `--auth`, Negotiate wins as the
more specific statement of intent.

Plain HTTP only, the same limit `--auth` and `--header` have: HTTPS reaches the target as an
opaque CONNECT tunnel with nothing local to edit.

`--doctor` now runs its Kerberos checks when `--krb5-hosts` is set even with no upstream proxy,
and reports whether a ticket can actually be obtained for each host. A host named here with no
principal registered otherwise fails as a 401 from the site, which reads as a site problem.

### Destination policy

Three options decide what a tunnel may reach, and all three are enforced on every way out --
plain HTTP, CONNECT, and WebSocket upgrades:

| | shape |
|---|---|
| `--allow-hosts` | permit only what is named; refuse the rest |
| `--fast-fail-regexps` | permit everything; refuse what is named |
| `--localhost-policy deny` | refuse this machine's loopback |

`WebsocketHandler` sits outside `CustomConnectHandler` in the chain and intercepts upgrades
before they reach it, so until `--allow-hosts` was added it applied **no** destination policy at
all: a `ws://` URL walked straight past `--fast-fail-regexps` and `--localhost-policy deny`. All
three are now checked there too, which `AllowHostsEnforcementTest` covers per path.

`AllowedHosts` supports `*.suffix` where `NegotiateHosts` and `BumpPolicy` refuse wildcards. The
difference is who matches: those two are matched by a KDC and by Squid, so this client cannot
define what a pattern means, while this list is matched here.

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

The value can also be set per module -- `proxy` for browser traffic through the local proxy,
`forwarder` for the Selenium relay -- as `--log-http proxy:none,forwarder:headers`. A bare level
still sets everything at once, and a bare level alongside named modules acts as "and everything
else". An unknown module name is refused rather than ignored, since a typo would otherwise be
accepted and quietly change nothing.

**Second behaviour change**: the Selenium relay ignored `--log-http` entirely and logged an INFO
line for every request, including under `--log-http none`. It now honours it, and follows the
same `errors` default -- so relay lines are silent for successful requests unless asked for with
`--log-http url` or `--log-http forwarder:url`.

Every request carries a correlation id, logged in `[brackets]` and passed to the target in
`--request-id-header`. An incoming value is reused so a trace starting in the test framework
stays joined up. The id is forwarded even when `--log-http none` is set.

Header values are redacted via `SensitiveHeaders.redactValue`, so `Authorization` and
TestingBot credentials do not reach whatever collects these logs.

### Body logging and its redaction

`--log-http forwarder:body` adds the request body, and exists only for the Selenium relay.
`proxy:body` and a bare `body` are **refused**, not downgraded: browser traffic has to stream and
cannot be buffered for logging, and someone who asked to capture bodies should not be left
believing they are.

The relay's body is WebDriver capabilities, which routinely carry the customer's own access key,
so `BodyRedactor` decides what may be written:

1. **Redact by key, not by pattern.** The document is parsed and any value whose key looks like a
   credential is replaced. Scanning a blob for things resembling secrets fails open.
2. **Only show what parses.** JSON and form encoding are understood, so they are redacted and
   shown. Everything else is described by type and length -- a structure that cannot be parsed
   cannot be redacted.
3. **Oversized bodies are described, not truncated.** Half a document does not parse, and
   truncating first would print raw bytes exactly when there are most of them.

The tee copies from a `duplicate()` of each chunk's buffer, so the body reaches the hub
untouched; `ForwarderBodyLoggingTest` asserts the redaction and the byte-for-byte forwarding on
the same request, because a tee that read the real buffer would satisfy the first and silently
break every session.

**Residual risk, stated rather than hidden:** rule 1 is a denylist of key names. A credential
stored under a name it does not recognise -- `privateData`, say -- would be printed. It covers
the shapes that actually occur here (`key`, `secret`, `token`, `password`, `apiKey`,
`accessKey`, `auth`, `session` and spelling variants); it is not a guarantee about arbitrary
payloads. Rules 2 and 3 fail closed, so an unknown *format* is never shown.

### Header rules

`--header` and `--response-header` apply to **plain HTTP only**. HTTPS arrives as a CONNECT and
is relayed as opaque bytes, so there is nothing local to edit; changing headers on HTTPS
requires the remote SSL-bump path (i.e. not `--nobump`).

Removals are applied before sets, so `-X` and `X: 1` together always mean "replace",
independent of argument order. Rules run after `--extra-headers` and after the `X-Forwarded-*`
headers are generated, so they can override either.

### Trusted certificate authorities

`--cacert-file` exists for TLS-intercepting proxies: they re-sign every certificate with an
internal CA the JVM has never seen, so the API call fails and the tunnel cannot register. The
error names a certificate rather than the proxy that replaced it, which is why `--doctor` prints
the subjects of whatever was loaded.

`CaCertificates` composes two trust managers -- the platform's and one built from the supplied
PEMs -- rather than merging key stores, so it does not depend on where `cacerts` lives or what
its password is. The platform is consulted first and the extras only on failure, with the
original rejection kept as a suppressed exception so a chain neither trusts still explains
itself.

Applied to the API client. The jetty-client used for plain-HTTP proxying does no TLS worth
covering: proxied HTTPS arrives as a CONNECT and is relayed as opaque bytes.

### SSL bumping

Bumping happens on TestingBot's Squid, not here. This client only relays the decision when the
tunnel is created -- `no_bump` for the whole tunnel, `no_bump_domains` for named hosts -- so
`com.testingbot.tunnel.proxy.BumpPolicy` owns deciding what to ask for and refusing to ask for
something meaningless. An entry that is a URL or carries a port would match nothing on the
server, and the tunnel would bump the very host the user was exempting.

**`--nobump` works, end to end.** This previously read as a measured server-side gap: the e2e
`sslbump` scenario failed identically with and without the flag, which was taken to mean Squid
was not splicing. TB-352 was raised against the server on that basis. It was wrong, and the
reason is worth keeping because it invalidates a whole class of test:

The scenario navigated to `https://localhost:PORT`. A browser given `localhost` over **HTTPS**
never reaches the tunnel at all -- it resolves it on the browser VM and answers
`ERR_SSL_PROTOCOL_ERROR` from whatever is on that machine's own loopback. So both runs failed
before the proxy was ever consulted, which is exactly why they failed *identically*. Confirmed
on a random port and on 8080, which the VM does register a local listener for, so it is not
about the port. Note that plain **HTTP** to `localhost` does travel through the tunnel, which is
what made the asymmetry so easy to miss.

Addressed by a routable name (`local.testingbot.com`, which resolves to 127.0.0.1, so the tunnel
client dials the same origin) the self-signed origin loads over an unbumped tunnel and returns
its marker. Reading back the capabilities the hub hands out for a `--nobump` tunnel confirms the
other half: `sslProxy` is `<ip>:2010`, the raw SSH-forwarded port, so Squid is bypassed rather
than reconfigured. The whole chain -- client sends `no_bump`, the API persists it, the hub
selects port 2010 -- is correct.

`no_bump_domains` is a different matter and remains unimplemented server-side: nothing consumes
it, and it is the one case port selection cannot serve, since a single `sslProxy` value cannot
vary per domain.

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

- Main class: `com.testingbot.tunnel.Launcher`, which checks the Java version and then hands over
  to `com.testingbot.tunnel.App`. The launcher is compiled to Java 8 bytecode by its own
  `launcher-compat` compiler execution while everything else is 17: it is the only class an old
  JVM can load, and without it `App.checkJavaVersion()` never runs -- the class fails to load
  first, and Java 8 reports "A JNI error has occurred, please check your installation", which
  blames the installation and never mentions Java 17
- Requires Java 17+ (compiled with release 17)
- Uses Maven Shade plugin to create fat JAR with minimized dependencies
- Logging configured via Logback (src/main/resources/logback.xml)
- 808 unit/integration tests (`mvn test`); end-to-end suite against real browsers in `e2e/`
- SSH via the maintained JSch fork `com.github.mwiede:jsch`
- The SSH connection honours `--proxy` (HTTP CONNECT or SOCKS5), so it works on
  networks whose only egress is a proxy
- Jetty 12.1.x core Handler API for HTTP/proxy functionality; no Servlet/Jakarta EE dependency