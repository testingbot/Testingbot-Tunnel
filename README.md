# TestingBot Tunnel

A secure tunnel that lets browsers and real devices in the [TestingBot](https://testingbot.com)
cloud reach websites that only your machine or network can see — a development server on
`localhost`, a staging environment behind a firewall, an internal application.

Tests run against `localhost:4445` as if the grid were local; the tunnel forwards Selenium
traffic to TestingBot and routes the browser's web requests back through your network.

> **Running tunnel 4.x or older?** This page documents **5.0**, which needs Java 17 and changes
> a few defaults. The 4.x source and its documentation stay on the
> [`v4.x` branch](https://github.com/testingbot/testingbot-tunnel/tree/v4.x). If you are upgrading, read
> [Upgrading from 4.x](#upgrading-from-4x) first — it is short, and two of the changes are
> ones you will notice immediately.

## Requirements

| Tunnel version | Minimum JDK | Notes |
|---|---|---|
| **5.0** and later | **17** | Built on Jetty 12. Tested on 17, 21 and 25. |
| 4.x | 11 | Jetty 11, which is end-of-life and no longer receives security fixes. |
| 3.x and earlier | 8 | Unsupported. |

## Upgrading from 4.x

5.0 adds 28 options and removes none, so existing command lines keep working. Six things do
change, and the first three are the ones people notice:

| | 4.x | 5.0 | To keep the 4.x behaviour |
|---|---|---|---|
| **Java** | 11 | **17** | — (hard requirement) |
| **What the listeners bind** | every interface | `127.0.0.1` | `--bind-address 0.0.0.0` |
| **Per-request logging** | one `INFO` line per request | only failures and 5xx | `--log-http url` |
| **Selenium relay logging** | always logged | honours `--log-http` | `--log-http forwarder:url` |
| **Docker image** | — | sets `TESTINGBOT_BIND_ADDRESS=0.0.0.0` | — (published ports keep working) |
| **Embedding the jar** | Jetty 11 + Servlet API | Jetty 12 core handlers | — (source change) |

**Java 17.** The jar's classes cannot be loaded by an older JVM. The tunnel checks the version
itself and says so plainly, rather than failing as `A JNI error has occurred`.

**Listeners bind loopback.** In 4.x the Selenium relay (`4445`), the local proxy (`8087`), the
insight endpoints (`8003`) and `--web` (`8080`) accepted connections from any machine that could
route to yours. None of them authenticates: the relay attaches your TestingBot key and secret to
everything it forwards, and the proxy will connect anywhere your machine can, including its own
loopback. They now bind `127.0.0.1`.

If your tests run on the same machine as the tunnel — the normal case — nothing changes. If they
run elsewhere, add `--bind-address 0.0.0.0` and restrict the port with a firewall. The Docker
image sets that for you, because a loopback bind inside a container makes published ports
unreachable; narrow it there on the host side of the publish instead
(`-p 127.0.0.1:4445:4445`).

**Quieter logs.** 4.x logged a line for every proxied request. 5.0 logs failures and 5xx only.
`--log-http url` restores a line per request, and `--log-http` takes a level per module —
`--log-http proxy:url,forwarder:none`.

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

### Keeping credentials out of the process list

Anything on the command line is visible to other users of the machine in `ps`. Every long option
has a `TESTINGBOT_*` alias derived from its name, so the ones carrying a secret can be set in the
environment instead:

| Option | Environment variable | Format |
|---|---|---|
| `--auth` | `TESTINGBOT_AUTH` | `host:port:user:password`, comma-separated for several |
| `--proxy-userpwd` | `TESTINGBOT_PROXY_USERPWD` | `user:password` |
| `--proxy-testingbot-userpwd` | `TESTINGBOT_PROXY_TESTINGBOT_USERPWD` | `user:password` |
| `--metrics-auth` | `TESTINGBOT_METRICS_AUTH` | `user:password` |

The API key and secret can come from `TESTINGBOT_KEY` / `TESTINGBOT_SECRET` or from
`~/.testingbot` instead of being passed as arguments. The flag always wins over the variable.

> The positional `API_KEY API_SECRET` form shown above is still supported, but it does put the
> secret in `ps` for as long as the tunnel runs. On a shared machine, prefer the environment
> variables or `~/.testingbot`.

`--help` lists everything. The options people reach for most:

| Option | Purpose |
|---|---|
| `--se-port` | Local Selenium port (default 4445) |
| `--localproxy` | Local HTTP proxy port (default 8087) |
| `--tunnel-identifier` | Name this tunnel, so several can run at once |
| `--allow-hosts` | Reach only these hosts; everything else gets 403 |
| `--fast-fail-regexps` | Refuse matching hosts. Prefix `!` for an exception: `.*,!ok\.com` blocks everything except `ok.com` and its subdomains |
| `--bind-address` | Which interface the local listeners use: `127.0.0.1` (default) or `0.0.0.0` |
| `--proxy` | Upstream proxy for egress — browser traffic and, unless `--proxy-testingbot` is set, the tunnel's own connection |
| `--doctor` | Run diagnostics and exit |

### Restricting what the tunnel can reach

A tunnel can reach whatever the machine running it can reach. `--fast-fail-regexps` names what to
refuse, which only helps for destinations somebody thought of in advance. `--allow-hosts` is the
other way round — name what the tunnel is *for*, and everything else is refused:

```bash
java -jar testingbot-tunnel.jar --allow-hosts 'staging.example.com,*.internal.example'
```

`*.internal.example` covers subdomains but not `internal.example` itself, so widening to the apex
has to be written down. Omit the option and any host is reachable, as before.

Both, and `--localhost-policy deny`, are enforced on every way out: plain HTTP, `CONNECT`, and
WebSocket upgrades.

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

`--krb5-hosts` sends the same credentials to *sites* rather than to the proxy, for intranets that
authenticate with Kerberos. It is a list, never a wildcard: a service ticket names the user, so
every host that may receive one has to be written down.

Where egress is filtered by destination, the proxy allowed out to the internet is often not the
one that reaches internal test targets. `--proxy-testingbot` gives TestingBot's own traffic — the
API and the tunnel's connection — a different proxy from the one test traffic uses:

```bash
java -jar testingbot-tunnel.jar --proxy 10.0.0.9:8080 \
     --proxy-testingbot corp-egress:3128 --proxy-testingbot-userpwd user:password
```

Credentials are deliberately not shared between the two: `--proxy-userpwd` belongs to one proxy
operator and is not sent to another. The same rule applies to a proxy chosen by `--pac-local` —
it receives no credentials, and the tunnel logs that it withheld them, so the resulting `407`
explains itself.

If a proxy intercepts TLS and re-signs it with an internal authority, the JVM will not trust it
and the tunnel cannot even register itself. Point it at the authority:

```bash
java -jar testingbot-tunnel.jar --proxy proxy.corp:8080 --cacert-file /etc/ssl/corp-ca.pem
```

It is added to the platform's trust store rather than replacing it, so the public roots keep
working. `--doctor` prints the subjects it loaded.

### Choosing egress per destination (PAC)

`--pac-local` points the tunnel at a proxy auto-config file and lets it decide, per destination,
whether to go direct or through a proxy:

```bash
java -jar testingbot-tunnel.jar --pac-local /etc/corp.pac
java -jar testingbot-tunnel.jar --pac-local https://corp.example/proxy.pac
```

The file decides where traffic goes and, on the `CONNECT` path, which proxy receives
`--proxy-userpwd`. Fetched over plain `http://` it is whatever the network says it is, so a
plain `http://` URL is refused unless you pin the document:

```bash
java -jar testingbot-tunnel.jar --pac-local http://wpad.corp/proxy.pac \
     --pac-local-sha256 3b1f...64hex
```

Use `https://` or a local file where you can. Redirects are refused rather than followed, so
what is fetched is what you named.

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

`--dns` resolves through servers you name rather than the platform resolver, taking a list: the
first is primary and the rest are tried in order when it does not answer. `--dns-round-robin`
spreads queries across them instead, and `--dns-timeout` bounds each one.

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

Browser traffic and the Selenium relay can be turned up independently, which saves drowning in
one while debugging the other:

```bash
java -jar testingbot-tunnel.jar --log-http proxy:none,forwarder:headers
```

`forwarder:body` adds the relay's request body. That body is WebDriver capabilities, which
routinely carry your access key, so it is redacted before it is written: values are matched by
key name, and anything that cannot be parsed — an unknown content type, or a body too large to
parse whole — is described rather than printed. `proxy:body` is refused, because browser traffic
has to stream and cannot be buffered for logging.

`--log-format json` writes one JSON object per record, so a collector need not guess where a
multi-line message or a stack trace ends.

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
