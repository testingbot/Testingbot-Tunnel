package ssh;

import com.testingbot.tunnel.App;
import com.testingbot.tunnel.HttpProxy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The adapter between the reconnect logic and {@link App}.
 *
 * <p>Small, but it decides what actually happens to the local proxy during an outage. The
 * reconnect logic itself is tested against fakes, so without this nothing checked that the fakes
 * correspond to anything real.
 */
class ReconnectHostTest {

    private HttpProxy httpProxy;
    private int proxyPort;

    private static int findFreePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    @AfterEach
    void tearDown() {
        if (httpProxy != null) {
            httpProxy.stop();
        }
    }

    private App appWithProxy() throws Exception {
        App app = new App();
        app.setClientKey("k");
        app.setClientSecret("s");
        proxyPort = findFreePort();
        app.setJettyPort(proxyPort);
        httpProxy = new HttpProxy(app);
        app.setHttpProxy(httpProxy);
        waitUntilListening(true);
        return app;
    }

    private void waitUntilListening(boolean expected) throws Exception {
        for (int i = 0; i < 100; i++) {
            if (isListening() == expected) {
                return;
            }
            Thread.sleep(50);
        }
        assertThat(isListening()).as("port %d listening", proxyPort).isEqualTo(expected);
    }

    private boolean isListening() {
        try (Socket s = new Socket("127.0.0.1", proxyPort)) {
            return true;
        } catch (IOException notListening) {
            return false;
        }
    }

    @Test
    void stopAndStartCycleTheLocalProxy() throws Exception {
        // What an outage does: the proxy comes down while the tunnel is gone and back up when
        // it returns, on the same port, so the reverse forward still has somewhere to deliver.
        App app = appWithProxy();
        ReconnectHost host = ReconnectHost.of(app);

        host.stopLocalProxy();
        waitUntilListening(false);
        assertThat(isListening()).isFalse();

        host.startLocalProxy();
        waitUntilListening(true);
        assertThat(isListening()).isTrue();
    }

    @Test
    void withNoLocalProxyBothCallsAreNoOps() {
        // boot() can fail before the proxy exists; the reconnect path must not die on a null.
        App app = new App();
        ReconnectHost host = ReconnectHost.of(app);

        assertThat(app.getHttpProxy()).isNull();
        assertThatCode(host::stopLocalProxy).doesNotThrowAnyException();
        assertThatCode(host::startLocalProxy).doesNotThrowAnyException();
    }

    @Test
    void stoppingTwiceIsSafe() throws Exception {
        App app = appWithProxy();
        ReconnectHost host = ReconnectHost.of(app);

        host.stopLocalProxy();
        assertThatCode(host::stopLocalProxy).doesNotThrowAnyException();
    }
}
