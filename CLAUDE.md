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
java -jar target/TestingBotTunnel-4.9-shaded.jar <API_KEY> <API_SECRET>
```
Or use environment variables:
```bash
export TESTINGBOT_KEY=<your_key>
export TESTINGBOT_SECRET=<your_secret>
java -jar target/TestingBotTunnel-4.9.jar
```

**Docker build:**
```bash
docker buildx build --platform linux/amd64,linux/arm64 \
  --no-cache \
  --push \
  -t testingbot/tunnel:4.9 \
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
- Uses `ch.ethz.ssh2` library (embedded/modified version) for SSH connectivity
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
- `--fast-fail-regexps`: Domains to bypass proxy
- `--auth`: Basic authentication for specific hosts
- `--proxy`: Upstream proxy configuration
- `--extra-headers`: Custom HTTP headers injection
- `--tunnel-identifier`: Multiple tunnel support
- `--nobump`: Disable SSL certificate rewriting
- `--nocache`: Bypass TestingBot caching layer
- `--metrics-port`: Port for the insight endpoints (default 8003)
- `--ready`: Query a running tunnel's `/readyz` and exit 0 (ready) or 1 (not ready)

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
- Custom SSH2 implementation embedded (not standard JSch)
- Jetty 12.1.x core Handler API for HTTP/proxy functionality; no Servlet/Jakarta EE dependency