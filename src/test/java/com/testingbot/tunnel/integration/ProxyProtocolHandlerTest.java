package com.testingbot.tunnel.integration;

import com.testingbot.tunnel.TestPorts;
import com.testingbot.tunnel.App;
import com.testingbot.tunnel.HttpProxy;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rest of what Jetty 12's {@code ProxyHandler.newHttpClient()} takes away.
 *
 * <p>It ends with {@code protocolHandlers.clear()} and puts back three of the seven. Two of the
 * missing four turned out to be live bugs -- {@code --auth} and {@code --proxy-auth-scheme
 * negotiate}, both fixed by sending the header pre-emptively. This covers the other two, which
 * are believed to be fine, so that "believed" stops being the basis for it.
 *
 * <p>RedirectProtocolHandler: a proxy must hand a 3xx to its client rather than following it,
 * or the client never learns the resource moved and ends up with a body from a URL it did not
 * ask for. Removing the handler is correct here, and this pins that behaviour down.
 *
 * <p>ContinueProtocolHandler is replaced by ProxyHandler's own variant, so {@code Expect:
 * 100-continue} should still work; a proxy that swallowed the interim response would hang every
 * client that waits for it before sending a body.
 */
class ProxyProtocolHandlerTest {

    private HttpServer origin;
    private HttpProxy httpProxy;
    private int proxyPort;
    private int originPort;
    private final List<String> bodiesSeen = new CopyOnWriteArrayList<>();

    private static int findFreePort() throws IOException {
        return TestPorts.free();
    }

    @BeforeEach
    void setUp() throws Exception {
        originPort = findFreePort();
        origin = HttpServer.create(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), originPort), 0);
        origin.createContext("/moved", exchange -> {
            exchange.getResponseHeaders().add("Location", "/destination");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        origin.createContext("/destination", exchange -> {
            byte[] body = "arrived".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        origin.createContext("/echo", exchange -> {
            byte[] received = exchange.getRequestBody().readAllBytes();
            bodiesSeen.add(new String(received, StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(200, received.length);
            exchange.getResponseBody().write(received);
            exchange.close();
        });
        origin.start();

        proxyPort = findFreePort();
        App app = new App();
        app.setJettyPort(proxyPort);
        app.setClientKey("test_key");
        app.setClientSecret("test_secret");
        httpProxy = new HttpProxy(app);
        for (int i = 0; i < 100; i++) {
            try (Socket s = new Socket("127.0.0.1", proxyPort)) {
                return;
            } catch (IOException retry) {
                Thread.sleep(50);
            }
        }
        throw new IllegalStateException("proxy did not start");
    }

    @AfterEach
    void tearDown() {
        if (httpProxy != null) {
            httpProxy.stop();
        }
        if (origin != null) {
            origin.stop(0);
        }
    }

    private String throughProxy(String request) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", proxyPort)) {
            socket.setSoTimeout(20_000);
            socket.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            InputStream in = socket.getInputStream();
            StringBuilder response = new StringBuilder();
            byte[] buffer = new byte[4096];
            int n;
            while ((n = in.read(buffer)) > 0) {
                response.append(new String(buffer, 0, n, StandardCharsets.UTF_8));
            }
            return response.toString();
        }
    }

    @Test
    void aRedirectIsHandedToTheClientRatherThanFollowed() throws Exception {
        String response = throughProxy("GET http://127.0.0.1:" + originPort + "/moved HTTP/1.1\r\n"
                + "Host: 127.0.0.1:" + originPort + "\r\nConnection: close\r\n\r\n");

        assertThat(response).startsWith("HTTP/1.1 302");
        assertThat(response).contains("Location: /destination");
        assertThat(response)
                .as("following it here would give the client a body from a URL it never asked for")
                .doesNotContain("arrived");
    }

    @Test
    void aBodySentWithExpectContinueStillReachesTheOrigin() throws Exception {
        // Written by hand rather than with a client that hides the interim response: the point
        // is that the 100 arrives and the body follows it.
        String body = "expect-continue-body";
        try (Socket socket = new Socket("127.0.0.1", proxyPort)) {
            socket.setSoTimeout(20_000);
            socket.getOutputStream().write(("POST http://127.0.0.1:" + originPort + "/echo HTTP/1.1\r\n"
                    + "Host: 127.0.0.1:" + originPort + "\r\n"
                    + "Content-Length: " + body.length() + "\r\n"
                    + "Expect: 100-continue\r\n"
                    + "Connection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();

            InputStream in = socket.getInputStream();
            StringBuilder interim = new StringBuilder();
            while (interim.indexOf("\r\n\r\n") < 0) {
                int b = in.read();
                if (b < 0) {
                    break;
                }
                interim.append((char) b);
            }
            assertThat(interim.toString())
                    .as("the client is waiting for this before it sends anything")
                    .startsWith("HTTP/1.1 100");

            socket.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();

            StringBuilder response = new StringBuilder();
            byte[] buffer = new byte[4096];
            int n;
            while ((n = in.read(buffer)) > 0) {
                response.append(new String(buffer, 0, n, StandardCharsets.UTF_8));
            }
            assertThat(response.toString()).contains("200");
        }

        assertThat(bodiesSeen).containsExactly(body);
    }
}
