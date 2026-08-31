# TestingBot Tunnel

A secure tunnel that lets browsers and real devices in the [TestingBot](https://testingbot.com)
cloud reach websites that only your machine or network can see — a development server on
`localhost`, a staging environment behind a firewall, an internal application.

Tests run against `localhost:4445` as if the grid were local; the tunnel forwards Selenium
traffic to TestingBot and routes the browser's web requests back through your network.

## Requirements

| Tunnel version | Minimum JDK | Notes |
|---|---|---|
| **5.0** and later | **17** | Built on Jetty 12. Tested on 17, 21 and 25. |
| 4.x | 11 | Jetty 11, which is end-of-life and no longer receives security fixes. |
| 3.x and earlier | 8 | Unsupported. |

Upgrading from 4.x to 5.0 requires a Java 17 runtime. The jar's class files cannot be loaded by
an older JVM, so the tunnel reports the problem and exits rather than failing obscurely.

## Getting started

```bash
java -jar testingbot-tunnel.jar API_KEY API_SECRET
```

Credentials can also come from `TESTINGBOT_KEY` / `TESTINGBOT_SECRET`, or from `~/.testingbot`
containing `key:secret`. Get yours from
[your account page](https://testingbot.com/members/user/edit).

When you see `You may start your tests`, point your Selenium client at
`http://localhost:4445/wd/hub`.

### Docker

```bash
docker run -e TESTINGBOT_KEY=... -e TESTINGBOT_SECRET=... testingbot/tunnel:5.0
```

The image has a `HEALTHCHECK` that reports healthy once the tunnel is actually forwarding.

### Checking a setup

```bash
java -jar testingbot-tunnel.jar --doctor
```

Verifies DNS, connectivity to TestingBot, whether the ports it needs are free, and — when
Kerberos is configured — the whole Negotiate chain.

## Configuration

Every option can be supplied three ways. Precedence is **command line**, then **`--config`
file**, then **environment**. Each long option has a `TESTINGBOT_*` alias derived from its name,
so `--se-port` reads `TESTINGBOT_SE_PORT`. That is what lets a container be configured without
assembling a command line.

```bash
# these three are equivalent
java -jar testingbot-tunnel.jar --se-port 4446
echo "se-port = 4446" > tunnel.conf && java -jar testingbot-tunnel.jar --config tunnel.conf
TESTINGBOT_SE_PORT=4446 java -jar testingbot-tunnel.jar
```

`--help` lists everything. The options people reach for most:

| Option | Purpose |
|---|---|
| `--se-port` | Local Selenium port (default 4445) |
| `--localproxy` | Local HTTP proxy port (default 8087) |
| `--tunnel-identifier` | Name this tunnel, so several can run at once |
| `--fast-fail-regexps` | Refuse matching hosts. Prefix `!` for an exception: `.*,!ok\.com` blocks everything except `ok.com` |
| `--proxy` | Upstream proxy for egress — used by browser traffic **and** the tunnel's own SSH connection |
| `--doctor` | Run diagnostics and exit |

### Reaching an upstream proxy

On a network whose only route out is a corporate proxy, `--proxy` covers everything the tunnel
does, including its control connection:

```bash
java -jar testingbot-tunnel.jar --proxy proxy.corp:8080 --proxy-userpwd user:password
```

For proxies that require Kerberos rather than Basic:

```bash
java -jar testingbot-tunnel.jar --proxy proxy.corp:8080 --proxy-auth-scheme negotiate
```

Credentials come from your existing Kerberos ticket cache, or from `--krb5-keytab` with
`--krb5-principal` where nobody has run `kinit`. `--doctor` reports exactly which step fails,
which matters because every Negotiate misconfiguration otherwise looks like the same 407.

### Choosing egress per destination (PAC)

`--pac-local` points the tunnel at a proxy auto-config file and lets it decide, per destination,
whether to go direct or through a proxy:

```bash
java -jar testingbot-tunnel.jar --pac-local /etc/corp.pac
java -jar testingbot-tunnel.jar --pac-local https://corp.example/proxy.pac
```

The file is evaluated by a restricted interpreter built for this purpose — **no JavaScript
engine is embedded**. That keeps the dependency surface small for a process that already sits in
the network path, at the cost of supporting only the subset PAC files actually use: functions,
variables, conditionals, loops, the usual operators, and the standard helpers (`isPlainHostName`,
`dnsDomainIs`, `shExpMatch`, `isInNet`, `dnsResolve`, `myIpAddress`, `weekdayRange`, `dateRange`,
`timeRange` and friends). Anything outside it — object literals, regular expressions, `new` — is
**reported with its line number rather than guessed at**, because a misread PAC file silently
sends traffic to the wrong place.

Check a file before relying on it:

```bash
java -jar testingbot-tunnel.jar --pac-local corp.pac --pac-test https://internal.corp/page
```

> `--pac-local` is not the same as `--pac`. `--pac` tells the *remote browser* which PAC URL to
> use; `--pac-local` decides where *this tunnel* sends its own traffic.

### Rewriting headers

`--header` and `--response-header` edit headers on plain HTTP traffic, repeatable:

```
name: value   set, replacing whatever the peer sent
name;         set to an empty value
-name         remove
-name*        remove by prefix
```

```bash
java -jar testingbot-tunnel.jar --response-header '-Content-Security-Policy'
```

HTTPS arrives as a `CONNECT` and is relayed as opaque bytes, so these apply to plain HTTP only.

## Monitoring

On the metrics port (default 8003, `--metrics-port`):

| Path | Purpose |
|---|---|
| `/` | JSON status: version, uptime, requests, bytes transferred |
| `/metrics` | Prometheus exposition; honours `--metrics-auth` |
| `/healthz` | Liveness — 200 whenever the process is answering, including mid-reconnect |
| `/readyz` | Readiness — 200 when the tunnel is forwarding, 503 otherwise |

`--ready` queries `/readyz` and exits 0 or 1, for use in health checks.

The health endpoints are deliberately not behind `--metrics-auth`: container probes have no good
way to carry credentials, and they disclose nothing beyond up/down.

### Logging

`--log-http` controls per-request logging: `none`, `url`, `headers`, or `errors` (the default,
which logs only failed or 5xx responses). Every request carries a correlation id, logged in
brackets and passed to the target in `--request-id-header`. Header values that carry credentials
are redacted.

## Building

```bash
mvn package          # produces target/TestingBotTunnel-5.0-shaded.jar
mvn verify           # plus the full test suite
e2e/run-e2e.sh       # end-to-end against real browsers (needs credentials)
```

`dist/build-runtime.sh` produces a self-contained runtime image via `jlink` for machines with no
JDK installed.

## Support

Questions and problems: [testingbot.com](https://testingbot.com) or
[support@testingbot.com](mailto:support@testingbot.com). Including the output of `--doctor`
usually saves a round trip.
