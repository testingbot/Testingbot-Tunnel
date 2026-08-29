package com.testingbot.tunnel;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the handler behind the tunnel's self-test.
 *
 * <p>{@link HttpProxy#testProxy()} stands this handler up on a loopback port and asks
 * TestingBot to fetch it back through the tunnel, expecting the body to contain
 * "test=&lt;number&gt;". If the handler stops emitting that, the tunnel reports itself
 * broken on startup -- and the only other way to catch it is a live tunnel.
 */
class HttpProxyTestHandlerTest {

    private Server server;

    @AfterEach
    void tearDown() throws Exception {
        if (server != null) {
            server.stop();
            server.destroy();
        }
    }

    private String fetch(int randomNumber) throws Exception {
        server = new Server();
        ServerConnector connector = new ServerConnector(server, 1, 1);
        connector.setHost("127.0.0.1");
        connector.setPort(0);
        server.addConnector(connector);
        server.setHandler(new HttpProxy.TestHandler(randomNumber));
        server.start();

        URL url = new URL("http://127.0.0.1:" + connector.getLocalPort() + "/");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(5_000);
        conn.setReadTimeout(5_000);
        assertThat(conn.getResponseCode()).isEqualTo(200);
        assertThat(conn.getContentType()).contains("text/plain");
        return new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    @Test
    void servesTheBodyTestingBotLooksFor() throws Exception {
        assertThat(fetch(42)).isEqualTo("test=42");
    }

    @Test
    void bodyTracksTheRandomNumber() throws Exception {
        assertThat(fetch(7)).isEqualTo("test=7");
    }

    @Test
    void respondsOnAnyPath() throws Exception {
        server = new Server();
        ServerConnector connector = new ServerConnector(server, 1, 1);
        connector.setHost("127.0.0.1");
        connector.setPort(0);
        server.addConnector(connector);
        server.setHandler(new HttpProxy.TestHandler(99));
        server.start();

        URL url = new URL("http://127.0.0.1:" + connector.getLocalPort() + "/anything?x=1");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(5_000);
        conn.setReadTimeout(5_000);
        assertThat(conn.getResponseCode()).isEqualTo(200);
        assertThat(new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8))
                .isEqualTo("test=99");
    }
}
