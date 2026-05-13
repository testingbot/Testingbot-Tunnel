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

See the [main README → Monitoring](../../README.markdown#monitoring) for the full reference.
