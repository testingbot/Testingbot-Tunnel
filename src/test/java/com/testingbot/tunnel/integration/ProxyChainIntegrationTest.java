package com.testingbot.tunnel.integration;

import com.sun.net.httpserver.HttpServer;
import com.testingbot.tunnel.App;
import com.testingbot.tunnel.HttpProxy;
import com.testingbot.tunnel.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives real traffic through the assembled local proxy to prove the handler chain
 * (WebsocketHandler -> CustomConnectHandler -> proxy servlet) routes each protocol to the
 * handler that owns it.
 *
 * <p>The WebSocket and CONNECT paths in particular have no other coverage, and the two handlers
 * both extend Jetty's ConnectHandler -- so a mistake in the chain silently sends CONNECT to the
 * wrong handler, losing fast-fail and upstream-proxy support without failing any unit test.
 */
class ProxyChainIntegrationTest {

    private HttpServer origin;
    private ServerSocket wsOrigin;
    private Thread wsThread;
    private HttpProxy httpProxy;
    private int proxyPort;
    private int originPort;
    private int wsPort;

    private static int findFreePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        origin = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        origin.createContext("/hello", exchange -> {
            byte[] body = "hello-from-origin".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        origin.start();
        originPort = origin.getAddress().getPort();

        // Minimal WebSocket origin: completes the handshake, then echoes whatever it receives.
        wsOrigin = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        wsPort = wsOrigin.getLocalPort();
        wsThread = new Thread(() -> {
            try (Socket socket = wsOrigin.accept()) {
                InputStream in = socket.getInputStream();
                ByteArrayOutputStream request = new ByteArrayOutputStream();
                int c;
                while ((c = in.read()) != -1) {
                    request.write(c);
                    if (request.toString(StandardCharsets.UTF_8).endsWith("\r\n\r\n")) {
                        break;
                    }
                }
                OutputStream out = socket.getOutputStream();
                out.write(("HTTP/1.1 101 Switching Protocols\r\n"
                        + "Upgrade: websocket\r\n"
                        + "Connection: Upgrade\r\n"
                        + "Sec-WebSocket-Accept: test-accept-value\r\n\r\n")
                        .getBytes(StandardCharsets.US_ASCII));
                out.flush();

                byte[] buffer = new byte[64];
                int n = in.read(buffer);
                if (n > 0) {
                    out.write(buffer, 0, n);
                    out.flush();
                }
                Thread.sleep(2_000);
            } catch (Exception ignored) {
                // socket closed by the test; nothing to do
            }
        });
        wsThread.setDaemon(true);
        wsThread.start();

        proxyPort = findFreePort();
        App app = new App();
        app.setJettyPort(proxyPort);
        app.setClientKey("test_key");
        app.setClientSecret("test_secret");
        httpProxy = new HttpProxy(app);
        waitForPort(proxyPort);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (httpProxy != null) {
            httpProxy.stop();
        }
        if (origin != null) {
            origin.stop(0);
        }
        if (wsThread != null) {
            wsThread.interrupt();
        }
        if (wsOrigin != null && !wsOrigin.isClosed()) {
            wsOrigin.close();
        }
    }

    private static void waitForPort(int port) throws Exception {
        for (int i = 0; i < 100; i++) {
            try (Socket s = new Socket("127.0.0.1", port)) {
                return;
            } catch (IOException retry) {
                Thread.sleep(50);
            }
        }
        throw new IllegalStateException("Proxy did not start on port " + port);
    }

    @Test
    void plainHttp_shouldBeProxiedToOrigin() throws Exception {
        URL url = new URL("http://127.0.0.1:" + originPort + "/hello");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection(
                new Proxy(Proxy.Type.HTTP, new InetSocketAddress("127.0.0.1", proxyPort)));
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(10_000);

        assertThat(conn.getResponseCode()).isEqualTo(200);
        assertThat(new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8))
                .isEqualTo("hello-from-origin");
    }

    @Test
    void connect_shouldReachCustomConnectHandlerAndEstablishTunnel() throws Exception {
        try (Socket socket = new Socket("127.0.0.1", proxyPort)) {
            socket.setSoTimeout(10_000);
            socket.getOutputStream().write(("CONNECT 127.0.0.1:" + originPort + " HTTP/1.1\r\n"
                    + "Host: 127.0.0.1:" + originPort + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
            assertThat(reader.readLine()).contains(" 200");
        }
    }

    @Test
    void websocketUpgrade_shouldRelayHandshakeAndBytes() throws Exception {
        try (Socket socket = new Socket("127.0.0.1", proxyPort)) {
            socket.setSoTimeout(10_000);
            socket.getOutputStream().write(("GET http://127.0.0.1:" + wsPort + "/ws HTTP/1.1\r\n"
                    + "Host: 127.0.0.1:" + wsPort + "\r\n"
                    + "Upgrade: websocket\r\n"
                    + "Connection: Upgrade\r\n"
                    + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n"
                    + "Sec-WebSocket-Version: 13\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));

            assertThat(reader.readLine()).contains("101");

            StringBuilder headers = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                headers.append(line).append('\n');
            }
            // The target's handshake headers must be passed back to the client verbatim.
            assertThat(headers.toString()).contains("Sec-WebSocket-Accept: test-accept-value");

            // And the tunnel must relay raw bytes in both directions afterwards.
            socket.getOutputStream().write("PING-RELAY".getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();

            char[] buffer = new char[32];
            int n = reader.read(buffer, 0, "PING-RELAY".length());
            assertThat(n).isGreaterThan(0);
            assertThat(new String(buffer, 0, n)).isEqualTo("PING-RELAY");
        }
    }

    @Test
    void tunnelledBytes_areCounted() throws Exception {
        // Byte totals used to come from the proxy servlet's response callback, which only
        // ever saw plain HTTP -- so CONNECT traffic, the bulk of what a tunnel carries, was
        // invisible. They now come from the connector, which sees everything.
        long before = Statistics.getBytesTransferred();

        try (Socket socket = new Socket("127.0.0.1", proxyPort)) {
            socket.setSoTimeout(10_000);
            socket.getOutputStream().write(("CONNECT 127.0.0.1:" + originPort + " HTTP/1.1\r\n"
                    + "Host: 127.0.0.1:" + originPort + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
            assertThat(reader.readLine()).contains(" 200");

            // Push bytes through the established tunnel.
            socket.getOutputStream().write(("GET / HTTP/1.1\r\nHost: 127.0.0.1\r\n"
                    + "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            reader.readLine();
        }

        // The connector flushes its counters as connections close; allow a moment.
        long after = before;
        for (int i = 0; i < 50 && after <= before; i++) {
            Thread.sleep(100);
            after = Statistics.getBytesTransferred();
        }
        assertThat(after).isGreaterThan(before);
    }
}
