package com.testingbot.tunnel;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.util.Collections;
import java.util.Enumeration;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every listener this program opens binds loopback unless asked otherwise.
 *
 * <p>The relay stamps the account key and secret onto every request it forwards and the local
 * proxy is an open CONNECT relay into this host's network and its own loopback, so a wildcard
 * bind handed both to anyone who could route here. None of them authenticates, which is why the
 * bind is the whole control.
 *
 * <p>The assertion is made by binding the same port on a routable address after the listener is
 * up: that succeeds only if the listener left that address free, and fails with "address already
 * in use" against a wildcard bind. Asking the connector what host it was configured with would
 * pass just as happily against a listener that never started.
 */
class BindAddressTest {

    /** A routable IPv4 address of this machine, or null when it has none. */
    private static InetAddress routableAddress() throws Exception {
        Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
        for (NetworkInterface each : Collections.list(interfaces)) {
            if (!each.isUp() || each.isLoopback()) {
                continue;
            }
            for (InetAddress address : Collections.list(each.getInetAddresses())) {
                if (address instanceof java.net.Inet4Address && !address.isLoopbackAddress()) {
                    return address;
                }
            }
        }
        return null;
    }

    /** True when {@code port} is still free on {@code address} — i.e. nothing wildcard-bound it. */
    private static boolean freeOn(InetAddress address, int port) {
        try (ServerSocket probe = new ServerSocket()) {
            probe.setReuseAddress(false);
            probe.bind(new InetSocketAddress(address, port));
            return true;
        } catch (IOException alreadyBound) {
            return false;
        }
    }

    private static void waitForPort(int port) throws Exception {
        for (int i = 0; i < 100; i++) {
            try (java.net.Socket s = new java.net.Socket("127.0.0.1", port)) {
                return;
            } catch (IOException retry) {
                Thread.sleep(50);
            }
        }
        throw new IllegalStateException("listener did not start on port " + port);
    }

    private static App appWithCredentials() {
        App app = new App();
        app.setClientKey("test_key");
        app.setClientSecret("test_secret");
        return app;
    }

    @Test
    void defaultBindAddress_isLoopback() {
        assertThat(new App().getBindAddress()).isEqualTo("127.0.0.1");
    }

    @Test
    void blankBindAddress_fallsBackToLoopback() {
        App app = appWithCredentials();
        app.setBindAddress("   ");
        assertThat(app.getBindAddress()).isEqualTo("127.0.0.1");
        app.setBindAddress(null);
        assertThat(app.getBindAddress()).isEqualTo("127.0.0.1");
    }

    @Test
    void bindAddress_isTrimmed() {
        App app = appWithCredentials();
        app.setBindAddress(" 0.0.0.0 ");
        assertThat(app.getBindAddress()).isEqualTo("0.0.0.0");
    }

    @Test
    void seleniumRelay_isNotReachableFromOtherHosts() throws Exception {
        InetAddress routable = routableAddress();
        Assumptions.assumeTrue(routable != null, "no routable interface on this machine");

        int port = TestPorts.free();
        App app = appWithCredentials();
        app.setSeleniumPort(port);

        HttpForwarder forwarder = new HttpForwarder(app);
        try {
            waitForPort(port);
            assertThat(freeOn(routable, port))
                .as("Selenium relay must not be bound on %s", routable.getHostAddress())
                .isTrue();
        } finally {
            forwarder.stop();
        }
    }

    @Test
    void insightServer_isNotReachableFromOtherHosts() throws Exception {
        InetAddress routable = routableAddress();
        Assumptions.assumeTrue(routable != null, "no routable interface on this machine");

        int port = TestPorts.free();
        App app = appWithCredentials();
        app.setMetricsPort(port);

        InsightServer insight = new InsightServer(app);
        try {
            waitForPort(port);
            assertThat(freeOn(routable, port))
                .as("insight endpoints must not be bound on %s", routable.getHostAddress())
                .isTrue();
        } finally {
            insight.stop();
        }
    }

    @Test
    void explicitWildcard_stillBindsEveryInterface() throws Exception {
        InetAddress routable = routableAddress();
        Assumptions.assumeTrue(routable != null, "no routable interface on this machine");

        int port = TestPorts.free();
        App app = appWithCredentials();
        app.setSeleniumPort(port);
        app.setBindAddress("0.0.0.0");

        HttpForwarder forwarder = new HttpForwarder(app);
        try {
            waitForPort(port);
            assertThat(freeOn(routable, port))
                .as("--bind-address 0.0.0.0 must still reach %s", routable.getHostAddress())
                .isFalse();
        } finally {
            forwarder.stop();
        }
    }
}
