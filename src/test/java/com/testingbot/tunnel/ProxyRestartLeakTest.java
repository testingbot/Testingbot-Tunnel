package com.testingbot.tunnel;

import org.eclipse.jetty.io.SelectorManager;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.ServerSocket;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The local proxy must survive being restarted many times.
 *
 * <p>An SSH reconnect stops and starts this Jetty server in place, and a tunnel on a flaky
 * network does that for hours. Jetty's ConnectHandler adds a fresh SelectorManager bean on every
 * start and ContainerLifeCycle never removes beans on stop, so without the doStop() overrides in
 * CustomConnectHandler and WebsocketHandler each cycle left two more live selectors behind, each
 * parking a pool thread in select() forever.
 *
 * <p>Measured before the fix: two selectors and two permanently busy threads per cycle, and at
 * restart 88 the proxy could no longer start -- "Insufficient configured threads: required=201 <
 * max=200". The failure is swallowed by the reconnect monitor's catch, so the tunnel would burn
 * every retry and then tear itself down.
 */
class ProxyRestartLeakTest {

    /** Enough cycles that a two-per-cycle leak is unmistakable, without a slow test. */
    private static final int RESTARTS = 40;

    private static int findFreePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private static Server serverOf(HttpProxy proxy) throws Exception {
        Field field = HttpProxy.class.getDeclaredField("httpProxy");
        field.setAccessible(true);
        return (Server) field.get(proxy);
    }

    @Test
    void restartingManyTimesLeaksNeitherSelectorsNorThreads() throws Exception {
        App app = new App();
        app.setClientKey("k");
        app.setClientSecret("s");
        app.setJettyPort(findFreePort());

        HttpProxy proxy = new HttpProxy(app);
        try {
            Server server = serverOf(proxy);
            QueuedThreadPool pool = server.getBean(QueuedThreadPool.class);

            int selectorsAfterFirstStart = server.getContainedBeans(SelectorManager.class).size();
            int busyAfterFirstStart = pool == null ? 0 : pool.getBusyThreads();
            assertThat(selectorsAfterFirstStart).as("sanity: the chain has selectors").isPositive();

            for (int i = 0; i < RESTARTS; i++) {
                proxy.stop();
                proxy.start();
            }

            assertThat(server.getContainedBeans(SelectorManager.class))
                    .as("a SelectorManager per restart would accumulate here")
                    .hasSize(selectorsAfterFirstStart);
            if (pool != null) {
                assertThat(pool.getBusyThreads())
                        .as("leaked selectors park a pool thread each")
                        .isEqualTo(busyAfterFirstStart);
            }
        } finally {
            proxy.stop();
        }
    }

    @Test
    void theProxyStillWorksAfterManyRestarts() throws Exception {
        // Counting beans proves nothing if the proxy is no longer functional afterwards.
        App app = new App();
        app.setClientKey("k");
        app.setClientSecret("s");
        int port = findFreePort();
        app.setJettyPort(port);

        HttpProxy proxy = new HttpProxy(app);
        try {
            for (int i = 0; i < RESTARTS; i++) {
                proxy.stop();
                proxy.start();
            }

            try (java.net.Socket client = new java.net.Socket("127.0.0.1", port)) {
                assertThat(client.isConnected()).isTrue();
            }
        } finally {
            proxy.stop();
        }
    }
}
