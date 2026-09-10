# Upgrading from 4.x

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
