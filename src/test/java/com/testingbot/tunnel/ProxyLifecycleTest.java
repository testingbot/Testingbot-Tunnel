package com.testingbot.tunnel;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * What happens to the local proxy across the stop/start cycles a long session performs.
 *
 * <p>The SSH reconnect stops and starts this proxy in place, and after enough failed reconnects
 * the monitor rebuilds the whole tunnel. Both run only once something has already gone wrong, so
 * neither was covered -- and both were broken.
 */
class ProxyLifecycleTest {

    private static int findFreePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private static boolean isListening(int port) {
        try (Socket s = new Socket("127.0.0.1", port)) {
            return true;
        } catch (IOException notListening) {
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private static int shutdownHookCount() throws Exception {
        Class<?> hooks = Class.forName("java.lang.ApplicationShutdownHooks");
        Field field = hooks.getDeclaredField("hooks");
        field.setAccessible(true);
        return ((Map<Thread, Thread>) field.get(null)).size();
    }

    private static App app(int port) {
        App app = new App();
        app.setClientKey("k");
        app.setClientSecret("s");
        app.setJettyPort(port);
        return app;
    }

    @Test
    void restartingKeepsTheShutdownHookRegistered() throws Exception {
        // stop() removes the hook. Without start() re-adding it, the proxy lost its hook at the
        // first SSH reconnect, so a long session was never drained on exit again.
        int port = findFreePort();
        HttpProxy proxy = new HttpProxy(app(port));
        try {
            int afterConstruct = shutdownHookCount();

            for (int i = 0; i < 3; i++) {
                proxy.stop();
                proxy.start();
            }

            assertThat(shutdownHookCount())
                    .as("hooks should not be shed by restarting")
                    .isEqualTo(afterConstruct);
        } finally {
            proxy.stop();
        }
    }

    @Test
    void restartingDoesNotAccumulateShutdownHooks() throws Exception {
        // The opposite failure: re-adding unconditionally would leak one hook per reconnect.
        int port = findFreePort();
        HttpProxy proxy = new HttpProxy(app(port));
        try {
            int before = shutdownHookCount();

            proxy.start();
            proxy.start();

            assertThat(shutdownHookCount()).isEqualTo(before);
        } finally {
            proxy.stop();
        }
    }

    @Test
    void appStopReleasesThePortSoTheTunnelCanBeRebuilt() throws Exception {
        // The reconnect monitor's last resort after the retry limit is app.stop() then
        // app.boot(), and boot() constructs a new HttpProxy on the same port. While stop() left
        // the old one bound that rebuild always failed, so after roughly two and a half minutes
        // of outage the tunnel died instead of recovering.
        int port = findFreePort();
        App app = app(port);
        app.setHttpProxy(new HttpProxy(app));
        assertThat(isListening(port)).isTrue();

        app.stop();

        assertThat(app.getHttpProxy()).isNull();
        assertThat(isListening(port)).isFalse();

        HttpProxy rebuilt = new HttpProxy(app);
        try {
            assertThat(isListening(port)).isTrue();
        } finally {
            rebuilt.stop();
        }
    }

    @Test
    void appStopIsSafeWithoutAProxy() {
        // boot() can fail before startProxies() ever runs.
        App app = app(0);

        assertThatCode(app::stop).doesNotThrowAnyException();
    }

    @Test
    void appStopIsSafeTwice() throws Exception {
        int port = findFreePort();
        App app = app(port);
        app.setHttpProxy(new HttpProxy(app));

        app.stop();

        assertThatCode(app::stop).doesNotThrowAnyException();
    }
}
