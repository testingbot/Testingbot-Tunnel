# Embedding the tunnel in another application

The tunnel can be run as a library rather than a process. Configure an `App` with the same
options the command line takes, `boot()` it, and `stop()` it when the work is done:

```java
App tunnel = new App();
tunnel.setClientKey(key);
tunnel.setClientSecret(secret);
tunnel.setSeleniumPort(4445);
tunnel.setJettyPort(8087);
tunnel.setMetricsPort(8003);

try {
    tunnel.boot();                 // throws TunnelFailedException instead of exiting the JVM
    if (tunnel.awaitReady(Duration.ofMinutes(2))) {
        // run the tests
    } else {
        throw new IllegalStateException("tunnel not ready: " + tunnel.getSetupFailure());
    }
} finally {
    tunnel.stop();
}
```

`boot()` returns once the tunnel has been *created*, which is not the same as it being able to
carry traffic: when the tunnel server is still starting, the rest happens on a background timer.
`awaitReady` waits for the state `/readyz` reports and returns false as soon as the setup gives
up, so a caller does not wait out a timeout for a tunnel that has already failed.
`isReady()` is the same state without the waiting.

Add the thin jar as a dependency (`com.testingbot:TestingBotTunnel`), not the shaded one — the
shaded jar bundles relocated copies of Jetty, Apache HttpClient and JSch that will collide with
your own.

What that guarantees:

* **Nothing calls `System.exit`.** Every failure on the boot path throws `TunnelFailedException`.
  A failure on a timer thread, where there is nobody to throw to, releases everything and leaves
  a stopped tunnel whose `isReady()` and `/readyz` say so.
* **`stop()` gives everything back**: the Selenium relay, the local proxy and the metrics port
  are unbound, the SSH session and every timer it owns are cancelled, the shutdown hooks are
  unregistered, the ready file is removed, and the JVM's default `Authenticator` is put back the
  way it was found. A tunnel per job does not accumulate.
* **Readiness is per tunnel.** Two tunnels in one process each answer for themselves on their own
  metrics port.
* **Logging is left alone.** The console handlers, log levels and `--log-format` wiring are set up
  by the command line entry point, not by `boot()`.

Three things are process-wide, because the things they are built on are:

* the Prometheus registry behind `/metrics`, and the request/byte counters on `/`, which
  aggregate across tunnels in the same JVM;
* `Authenticator.setDefault`, which the JDK offers no per-connection alternative to for proxy and
  SOCKS credentials. The tunnel's authenticator answers only for the proxy it was given and
  delegates everything else to whatever was installed before it;
* uptime, which is measured from the first tunnel to boot.
