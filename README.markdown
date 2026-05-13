# TestingBot Tunnel

This is a Java-based application to test websites on your local computer or staging environment with the TestingBot.com browser cloud.
A secure connection is established between your machine and the TestingBot.com cloud.
You can find more information on https://testingbot.com/support/tunnel

About
-------

Whether you want to test on your local computer (localhost), on a staging server inside your LAN, or on a computer across VPN, TestingBot Tunnel makes all of this possible in a secure and reliable way.

You will no longer need to open up ports on your firewall or whitelist our IP range to run tests on your staging environment.
Below are some of the features of the TestingBot Tunnel:

* Fast: at TestingBot's end of the tunnel we keep a cache of static content, to reduce traffic inside the tunnel.
* Secure: based on an SSH Tunnel.
* Robust: full HTTP(S) support, coded in Java
* Easy to set up and use, supported on all operating systems.

Prerequisites
-------

This version of the tunnel requires Java 11 or higher.

- If you need Java 8, you can use TestingBot Tunnel 4.0(https://github.com/testingbot/Testingbot-Tunnel/tree/TestingBotTunnel-4.0).
- If you'd like to run on Java < 8, please use [TestingBot Tunnel 1.21](https://github.com/testingbot/Testingbot-Tunnel/tree/TestingBotTunnel-1.21) (no longer maintained)

**NOTE:** If you use the containerized tunnel, Java is not needed. See below under the *Docker*-header.

Install
-------

To start the tunnel, enter the following command:
    `java -jar testingbot-tunnel.jar key secret`

You can obtain a free key and secret from https://testingbot.com/members/user/edit

**Hint:** Instead of passing the key and secret to the command, you can have them as environment variables `${TESTINGBOT_KEY}` and `${TESTINGBOT_SECRET}`.

### Avoiding credentials in the process list

CLI options whose value contains a password show up in `ps` / process listings. Three options can be set via environment variables instead. The CLI flag still takes precedence; the env var is used as a fallback when the flag is absent.

| CLI flag | Env var | Format |
|---------|---------|--------|
| `--auth` | `TESTINGBOT_AUTH` | `host:port:user:password` — comma-separated for multiple entries |
| `--proxy-userpwd` | `TESTINGBOT_PROXY_USERPWD` | `user:password` |
| `--metrics-auth` | `TESTINGBOT_METRICS_AUTH` | `user:password` |

Options
-------

The tunnel comes with various options:

|Command|Description|
|---------|-------------|
|-a,--auth <host:port:user:passwd>|Performs Basic Authentication for specific hosts.|
|-b,--nobump|Do not perform SSL bumping.|
|-d,--debug|Enables debug messages. Will output request/response headers. Alias for `--log-level debug`.|
|-dns,--dns|Use a custom DNS server. For example: 8.8.8.8|
|--doctor|Perform sanity/health checks to detect possible misconfiguration or problems.|
|--extra-headers <JSON Map with Header Key and Value>|Inject extra headers in the requests the tunnel makes.|
|-f,--readyfile <FILE>|This file will be touched when the tunnel is ready for usage|
|-F,--fast-fail-regexps <OPTIONS>|Specify domains you don't want to proxy, comma separated.|
|-h,--help|Displays help text|
|-i,--tunnel-identifier <id>|Add an identifier to this tunnel connection. In case of multiple tunnels, specify this identifier in your desired capabilities to use this specific tunnel.|
|-j,--localproxy <port>|The port to launch the local proxy on (default 8087).|
|-l,--logfile <FILE>|Write logging to a file.|
|--log-level <LEVEL>|Set log verbosity. One of: `error`, `warn`, `info` (default), `debug`, `trace`. Overrides `--debug` if both are given.|
|--metrics-auth <user:password>|Require HTTP Basic auth on the `/metrics` (Prometheus) endpoint. Off by default.|
|--metrics-port <port>|Use the specified port to access metrics. Default port 8003|
|-P,--se-port <PORT>|The local port your Selenium test should connect to. Default port is 4445|
|-p,--hubport <HUBPORT>|Use this if you want to connect to port 80 on our hub instead of the default port 4444|
|--pac <arg>|Proxy autoconfiguration. Should be a http(s) URL|
|-q,--nocache|Bypass our Caching Proxy running on our tunnel VM.|
|-s,--shared|Share this tunnel among team members.|
|-v,--version|Displays the current version of the Tunnel|
|-w,--web <directory>|Point to a directory for testing. Creates a local webserver.|
|-x,--noproxy|Do not start a local proxy (requires user provided proxy server on port 8087)|
|-Y,--proxy <PROXYHOST:PROXYPORT>|Specify an upstream proxy.|
|-z,--proxy-userpwd <user:pwd>|Username and password required to access the proxy configured with --proxy.|


Example
-------
To use the tunnel, simply start it:

```
$ java -jar testingbot-tunnel.jar <key> <secret>
```

Now point your tests to use the tunnel's IP (localhost if the .jar is running on your local computer) and port 4445

### Ruby

```ruby
require "selenium-webdriver"

options = Selenium::WebDriver::Chrome::Options.new
options.add_argument("--start-maximized")

caps = Selenium::WebDriver::Remote::Capabilities.new(
  browser_name: "chrome",
  browser_version: "latest",
  platform_name: "WIN10"
)

urlhub = "http://key:secret@localhost:4445/wd/hub"
client = Selenium::WebDriver::Remote::Http::Default.new
client.read_timeout = 120

driver = Selenium::WebDriver.for :remote,
  url: urlhub,
  capabilities: [caps, options],
  http_client: client

driver.navigate.to "http://staging.local"
puts driver.title
driver.quit
```

### Python

```python
from selenium import webdriver
from selenium.webdriver.chrome.options import Options

options = Options()
options.browser_version = "latest"
options.platform_name = "WIN10"

driver = webdriver.Remote(
    command_executor="http://key:secret@localhost:4445/wd/hub",
    options=options
)

driver.get("http://staging.local")
print(driver.title)
driver.quit()
```

### Java

```java
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.remote.RemoteWebDriver;
import java.net.URL;

public class TestingBotTest {
    public static void main(String[] args) throws Exception {
        ChromeOptions options = new ChromeOptions();
        options.setPlatformName("WIN10");
        options.setBrowserVersion("latest");

        WebDriver driver = new RemoteWebDriver(
            new URL("http://key:secret@localhost:4445/wd/hub"),
            options
        );

        driver.get("http://staging.local");
        System.out.println(driver.getTitle());
        driver.quit();
    }
}
```

### JavaScript (Node.js)

```javascript
const { Builder } = require('selenium-webdriver');
const chrome = require('selenium-webdriver/chrome');

(async function example() {
    const options = new chrome.Options();
    options.setBrowserVersion('latest');
    options.setPlatform('WIN10');

    const driver = await new Builder()
        .usingServer('http://key:secret@localhost:4445/wd/hub')
        .forBrowser('chrome')
        .setChromeOptions(options)
        .build();

    try {
        await driver.get('http://staging.local');
        console.log(await driver.getTitle());
    } finally {
        await driver.quit();
    }
})();
```

### C#

```csharp
using OpenQA.Selenium;
using OpenQA.Selenium.Chrome;
using OpenQA.Selenium.Remote;
using System;

class TestingBotTest
{
    static void Main(string[] args)
    {
        var options = new ChromeOptions();
        options.BrowserVersion = "latest";
        options.PlatformName = "WIN10";

        IWebDriver driver = new RemoteWebDriver(
            new Uri("http://key:secret@localhost:4445/wd/hub"),
            options
        );

        driver.Navigate().GoToUrl("http://staging.local");
        Console.WriteLine(driver.Title);
        driver.Quit();
    }
}
```

Node
-------
We have created a NodeJS based launcher which you can use in your NodeJS tests and projects:
[testingbot-tunnel-launcher](https://github.com/testingbot/testingbot-tunnel-launcher)

More documentation about this tunnel is available on https://testingbot.com/support/other/tunnel

Docker
------
For those who don't want to deal with Java, we also provide a containerized version of the tunnel.

To start the tunnel, run:
```
$ docker run -p 4445:4445 testingbot/tunnel:latest <key> <secret> <options>
```

Alternatively:
```
$ docker run -p 4445:4445 -e TESTINGBOT_KEY=<key> -e TESTINGBOT_SECRET=<secret> testingbot/tunnel:latest <options>
```

To build the docker image locally, first build the JAR, then build the image:
```
mvn package -DskipTests
docker build -t testingbot/tunnel:latest .
```

For multi-platform builds (requires docker buildx):
```
mvn package -DskipTests
docker buildx build --platform linux/amd64,linux/arm64 \
  --push \
  -t testingbot/tunnel:4.6 \
  -t testingbot/tunnel:latest .
```


Monitoring
----------

The tunnel exposes two endpoints on the metrics port (default `8003`, configurable with `--metrics-port`):

* `GET /` — a small JSON status payload (version, uptime, request count, bytes transferred). Useful as a liveness probe.
* `GET /metrics` — Prometheus exposition format, ready to be scraped by Prometheus, Grafana Agent, VictoriaMetrics, or any compatible collector.

### What's exposed

All tunnel-specific series use the `testingbot_` prefix. Standard `jvm_*` and `process_*` series are exposed in addition.

| Metric | Type | Labels | Description |
|--------|------|--------|-------------|
| `testingbot_tunnel_info` | gauge | `version`, `tunnel_id`, `identifier` | Static info about the active tunnel. Value is `1`. |
| `testingbot_tunnel_up` | gauge | — | `1` when the SSH tunnel is connected, `0` otherwise. |
| `testingbot_tunnel_uptime_seconds` | gauge | — | Seconds since the process started. |
| `testingbot_tunnel_connect_duration_seconds` | histogram | — | Duration of the SSH connect handshake. |
| `testingbot_tunnel_connects_total` | counter | — | Successful SSH connects (initial + reconnects). |
| `testingbot_tunnel_reconnects_total` | counter | — | SSH reconnect attempts. |
| `testingbot_active_connections` | gauge | — | Currently open client connections to the local proxy. |
| `testingbot_http_requests_total` | counter | `method`, `code` | HTTP requests proxied through the tunnel. |
| `testingbot_http_request_duration_seconds` | histogram | `method` | Request duration observed by the local proxy. |
| `testingbot_http_request_size_bytes` | histogram | `method` | Request payload size (when `Content-Length` is set). |
| `testingbot_http_response_size_bytes` | histogram | `method` | Response payload size. |
| `testingbot_http_requests_in_flight` | gauge | `method` | Requests currently being proxied. |
| `testingbot_https_connect_total` | counter | `code` | HTTPS `CONNECT` requests handled. |
| `testingbot_https_connect_errors_total` | counter | `reason` | HTTPS `CONNECT` failures (`blacklisted`, `status_4xx`, `upstream_connect_failed`, …). |
| `testingbot_https_connect_duration_seconds` | histogram | — | Time to establish the HTTPS CONNECT tunnel. |
| `testingbot_proxy_bytes_transferred_total` | counter | — | Response bytes sent back to clients through the proxy. |
| `testingbot_errors_total` | counter | `name` | Tunnel errors grouped by name (`ssh_connect`, `ssh_auth`, `ssh_connection_lost`, `client_request_failure`, `blacklisted`). |

### Example scrape config

```yaml
scrape_configs:
  - job_name: testingbot-tunnel
    static_configs:
      - targets: ['localhost:8003']
```

To require authentication on `/metrics`, start the tunnel with `--metrics-auth user:password`:

```
$ java -jar testingbot-tunnel.jar <key> <secret> --metrics-auth ops:s3cret
```

…and add `basic_auth` to the scrape job:

```yaml
scrape_configs:
  - job_name: testingbot-tunnel
    basic_auth:
      username: ops
      password: s3cret
    static_configs:
      - targets: ['localhost:8003']
```

The legacy JSON endpoint at `/` is unaffected by `--metrics-auth`.

### Pre-built Grafana dashboard

A ready-made dashboard is included under [`examples/`](./examples/):

- [`examples/docker-compose-prometheus-grafana/`](./examples/docker-compose-prometheus-grafana/) — a full Docker Compose stack (tunnel + Prometheus + Grafana, with the dashboard auto-provisioned). Run `docker compose up` and open <http://localhost:3000>.
- [`examples/grafana-dashboard/`](./examples/grafana-dashboard/) — the standalone dashboard JSON, for importing into an existing Grafana instance.


Compile from Source
-------------------

To compile and test the Jar yourself you can use the following command:

    mvn package

Copyright
---------

Copyright (c) TestingBot.com
See the LICENSE for more information.
