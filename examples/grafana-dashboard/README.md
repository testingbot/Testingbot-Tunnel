# Standalone Grafana dashboard

[`testingbot-tunnel.json`](./testingbot-tunnel.json) is the same dashboard used by the [docker-compose-prometheus-grafana](../docker-compose-prometheus-grafana/) example, packaged for direct import into an existing Grafana instance.

## Import

1. Make sure your Prometheus is scraping the tunnel's `/metrics` endpoint (default port `8003`).
2. In Grafana: **Dashboards → New → Import**, then paste the JSON or upload the file.
3. When prompted, pick the Prometheus datasource that has the tunnel metrics. The dashboard uses a `Datasource` template variable so any Prometheus datasource works — no need to edit the JSON.

## Required metric names

The dashboard expects the metric series exposed by TestingBot Tunnel 4.8+:

- `testingbot_tunnel_up`, `testingbot_tunnel_info`, `testingbot_tunnel_uptime_seconds`, `testingbot_tunnel_reconnects_total`, `testingbot_tunnel_connects_total`, `testingbot_tunnel_connect_duration_seconds`
- `testingbot_http_requests_total`, `testingbot_http_request_duration_seconds`
- `testingbot_https_connect_total`, `testingbot_https_connect_errors_total`
- `testingbot_proxy_bytes_transferred_total`
- `testingbot_active_connections`
- `testingbot_errors_total`
- Standard `jvm_*` and `process_*` exposed by the bundled Prometheus hotspot collectors

See the [main README → Monitoring](../../README.md#monitoring) for the full reference.

## Metrics used

| Series | Meaning |
|---|---|
| `testingbot_connections_current{listener}` | Open connections per listener (`proxy`, `selenium`, `metrics`) |
| `testingbot_connection_bytes_{received,sent}_total{listener}` | Bytes at the connector — **includes** tunnelled CONNECT and WebSocket traffic |
| `testingbot_proxy_bytes_transferred_total` | Plain-HTTP response bytes only, which is what "HTTP Response Throughput" charts |
| `testingbot_dial_total{path,outcome}` | Outbound connection attempts (`connect`, `websocket`) |
| `testingbot_dial_duration_seconds{path}` | Time to establish an outbound connection |
| `testingbot_proxy_errors_total{reason}` | Classified failures; the label matches the `X-TestingBot-Error` response header |

The two byte metrics deliberately differ: one is measured at the connector and
sees everything, the other counts only plain-HTTP response bodies, because
CONNECT and WebSocket payloads are opaque to the proxy handler.

## Publishing to grafana.com

Not done — it needs a grafana.com account, so it is a manual step:

1. Sign in at <https://grafana.com/grafana/dashboards/> and choose **Publish dashboard**.
2. Upload `testingbot-tunnel.json`.
3. Record the assigned dashboard ID here so users can install it by number.

This file stays the source of truth; re-upload it when panels change.
