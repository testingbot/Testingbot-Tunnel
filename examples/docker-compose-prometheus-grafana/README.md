# TestingBot Tunnel with Prometheus and Grafana

Run [TestingBot Tunnel](https://testingbot.com/support/tunnel) with [Prometheus](https://prometheus.io/) scraping its `/metrics` endpoint and a pre-built [Grafana](https://grafana.com/) dashboard for monitoring.

## Quick start

If you already run a tunnel on the host (default metrics port `8003`):

```sh
docker compose up
```

This brings up only Prometheus and Grafana. Prometheus scrapes `host.docker.internal:8003`, which resolves to your host machine — so it finds the tunnel running natively (`java -jar testingbot-tunnel.jar …`).

If you don't have a tunnel running, opt into the bundled `testingbot-tunnel` service with the `tunnel` profile, and provide your credentials:

```sh
export TESTINGBOT_KEY=<your-key>
export TESTINGBOT_SECRET=<your-secret>

docker compose --profile tunnel up
```

(You can also drop those into a `.env` file next to `docker-compose.yaml`.) Either way, the in-compose tunnel publishes port `8003` on the host so the same scrape config works.

The stack runs:

| Service | Port | Always on? | What it does |
|---------|------|-----------|--------------|
| `prometheus` | `9090` | yes | Scrapes the tunnel every 5s |
| `grafana` | `3000` | yes | Pre-provisioned with the Prometheus datasource and the **TestingBot Tunnel** dashboard |
| `testingbot-tunnel` | `4445` (Selenium), `8087` (local proxy) | only with `--profile tunnel` | A tunnel running inside the compose stack |

Every port is published on `127.0.0.1` only, so nothing here is reachable from
other machines. That is deliberate and worth keeping:

- **4445** is the Selenium relay, and it attaches your TestingBot key and secret to
  every request it forwards. Anyone who can reach it can start browser sessions on
  your account and drive them at whatever the tunnel can reach.
- **8087** is a forward proxy with no authentication, into whatever this host can
  reach — including its own loopback.
- **9090** answers any query about this stack, and **3000** is a Grafana admin login.

Published Docker ports bypass the host firewall on Linux, so `"4445:4445"` really
does mean the whole network. If you need access from another machine, put it behind
something that authenticates rather than widening these.

The tunnel's metrics port is not published at all — Prometheus reaches it as
`testingbot-tunnel:8003` over the compose network. The scrape config also lists
`host.docker.internal:8003` for a tunnel running natively on your host, so whichever
way you run it, one of those two targets is DOWN in Prometheus. That is expected.

## Open the dashboard

Set a Grafana admin password first — the stack will not start without one, and there
is no default:

```sh
export GRAFANA_ADMIN_PASSWORD=<something of your own>
```

(Or put it in a `.env` file next to `docker-compose.yaml`.)

Then browse to <http://localhost:3000/d/testingbot-tunnel/testingbot-tunnel> and log in
as `admin` with that password.

If port 3000 is taken on your machine, override the host port: `GRAFANA_PORT=3001 docker compose up`, then open <http://localhost:3001>.

The dashboard has five rows:

- **Overview** — tunnel up/down, build info, active connections, uptime, reconnect count
- **HTTP** — request rate and latency, split into `non-CONNECT` and HTTPS `CONNECT` traffic
- **Tunnel** — active connection trend, SSH connect latency histogram
- **Errors** — error rate by name, HTTPS CONNECT errors by reason
- **Resources (JVM)** — heap, threads, process CPU

## Configuration files

- [`docker-compose.yaml`](./docker-compose.yaml) — the three services
- [`prometheus/prometheus.yaml`](./prometheus/prometheus.yaml) — scrape config
- [`grafana/config.monitoring`](./grafana/config.monitoring) — Grafana admin user and sign-up policy (the password comes from `GRAFANA_ADMIN_PASSWORD`)
- [`grafana/provisioning/datasources/datasource.yml`](./grafana/provisioning/datasources/datasource.yml) — Prometheus datasource
- [`grafana/provisioning/dashboards/dashboard.yml`](./grafana/provisioning/dashboards/dashboard.yml) — dashboard provider
- [`grafana/provisioning/dashboards/testingbot_tunnel.json`](./grafana/provisioning/dashboards/testingbot_tunnel.json) — the dashboard

## Cleanup

```sh
docker compose down            # stop the stack
docker compose down -v         # also delete Prometheus + Grafana volumes
```

## Standalone import

If you already run Prometheus and Grafana, you can skip the compose stack and import the dashboard JSON directly. See [`../grafana-dashboard/`](../grafana-dashboard/).
