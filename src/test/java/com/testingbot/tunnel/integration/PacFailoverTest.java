package com.testingbot.tunnel.integration;

import com.testingbot.tunnel.App;
import com.testingbot.tunnel.HttpProxy;
import com.testingbot.tunnel.TestPorts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A PAC answer is a failover list, and a proxy that cannot be reached moves on to the next entry.
 *
 * <p>Only the first entry used to be dialled, on all three egress paths. With
 * {@code "PROXY <dead>; DIRECT"} -- the shape of nearly every corporate PAC file -- a browser
 * reaches the site directly while the tunnel answered every request 502.
 *
 * <p>Moving on is for a proxy that could not be reached, not for one that answered: a refusal is
 * the network's decision, and is reported rather than routed around.
 */
class PacFailoverTest {

    @TempDir
    Path tempDir;

    private ExecutorService pool;
    private final List<ServerSocket> servers = new ArrayList<>();
    private HttpProxy httpProxy;
    private int proxyPort;
    private int originPort;
    private int refusingProxyPort;

    @BeforeEach
    void setUp() throws Exception {
        pool = Executors.newCachedThreadPool();
        // The origin: echoes the request line, answers a WebSocket upgrade with 101, and after a
        // CONNECT carries on serving whatever is sent through the tunnel.
        originPort = serve(socket -> {
            BufferedReader in = reader(socket);
            String requestLine = in.readLine();
            boolean upgrade = false;
            String line;
            while ((line = in.readLine()) != null && !line.isEmpty()) {
                upgrade |= line.toLowerCase(java.util.Locale.ROOT).startsWith("upgrade:");
            }
            OutputStream out = socket.getOutputStream();
            if (upgrade) {
                out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\n"
                        + "Connection: Upgrade\r\n"
                        + "Sec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=\r\n\r\n")
                        .getBytes(StandardCharsets.UTF_8));
                out.flush();
                return;
            }
            byte[] body = ("SAW=" + requestLine).getBytes(StandardCharsets.UTF_8);
            out.write(("HTTP/1.1 200 OK\r\nContent-Length: " + body.length
                    + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            out.write(body);
            out.flush();
        });
        // A proxy that is up and says no to everything.
        refusingProxyPort = serve(socket -> {
            BufferedReader in = reader(socket);
            String line;
            while ((line = in.readLine()) != null && !line.isEmpty()) {
                // drain the request
            }
            OutputStream out = socket.getOutputStream();
            out.write("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                    .getBytes(StandardCharsets.UTF_8));
            out.flush();
        });
    }

    @AfterEach
    void tearDown() throws Exception {
        if (httpProxy != null) {
            httpProxy.stop();
        }
        for (ServerSocket server : servers) {
            server.close();
        }
        pool.shutdownNow();
        Thread.interrupted();
        pool.awaitTermination(5, TimeUnit.SECONDS);
    }

    private interface Exchange {
        void handle(Socket socket) throws Exception;
    }

    private int serve(Exchange exchange) throws IOException {
        ServerSocket server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        servers.add(server);
        pool.submit(() -> {
            while (!server.isClosed()) {
                Socket accepted;
                try {
                    accepted = server.accept();
                } catch (IOException closed) {
                    return;
                }
                pool.submit(() -> {
                    try (Socket socket = accepted) {
                        exchange.handle(socket);
                    } catch (Exception ignored) {
                        // client went away; nothing useful to do
                    }
                });
            }
        });
        return server.getLocalPort();
    }

    private static BufferedReader reader(Socket socket) throws IOException {
        return new BufferedReader(new InputStreamReader(socket.getInputStream(),
                StandardCharsets.UTF_8));
    }

    /** A port nothing listens on, so a dial to it is refused at once. */
    private static int deadPort() throws IOException {
        try (ServerSocket probe = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return probe.getLocalPort();
        }
    }

    private void startTunnelProxy(String directives) throws Exception {
        Path pac = tempDir.resolve("failover.pac");
        Files.writeString(pac, "function FindProxyForURL(url, host) { return \""
                + directives + "\"; }");

        proxyPort = TestPorts.free();
        App app = new App();
        app.setJettyPort(proxyPort);
        app.setClientKey("test_key");
        app.setClientSecret("test_secret");
        app.setPacLocal(pac.toString());
        httpProxy = new HttpProxy(app);
        for (int i = 0; i < 100; i++) {
            try (Socket s = new Socket("127.0.0.1", proxyPort)) {
                return;
            } catch (IOException retry) {
                Thread.sleep(50);
            }
        }
        throw new IllegalStateException("Proxy did not start on port " + proxyPort);
    }

    /** Sends {@code request} to the tunnel's proxy and reads until the far end closes. */
    private String send(String request) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", proxyPort)) {
            socket.setSoTimeout(10_000);
            socket.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            return new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** Opens a CONNECT tunnel; returns the status line, then the response to a GET inside it. */
    private String connectThenGet() throws Exception {
        try (Socket socket = new Socket("127.0.0.1", proxyPort)) {
            socket.setSoTimeout(10_000);
            OutputStream out = socket.getOutputStream();
            out.write(("CONNECT 127.0.0.1:" + originPort + " HTTP/1.1\r\nHost: 127.0.0.1:"
                    + originPort + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            BufferedReader in = reader(socket);
            StringBuilder reply = new StringBuilder();
            String line;
            while ((line = in.readLine()) != null && !line.isEmpty()) {
                reply.append(line).append('\n');
            }
            if (!reply.toString().startsWith("HTTP/1.1 200")) {
                return reply.toString();
            }
            out.write("GET /through HTTP/1.1\r\nHost: origin\r\nConnection: close\r\n\r\n"
                    .getBytes(StandardCharsets.UTF_8));
            out.flush();
            StringBuilder rest = new StringBuilder();
            while ((line = in.readLine()) != null) {
                rest.append(line).append('\n');
            }
            return reply.append(rest).toString();
        }
    }

    @Test
    void connectMovesPastADeadProxyToDirect() throws Exception {
        startTunnelProxy("PROXY 127.0.0.1:" + deadPort() + "; DIRECT");

        assertThat(connectThenGet())
                .startsWith("HTTP/1.1 200")
                .contains("SAW=GET /through HTTP/1.1");
    }

    @Test
    void connectMovesPastTwoDeadProxies() throws Exception {
        startTunnelProxy("PROXY 127.0.0.1:" + deadPort() + "; SOCKS 127.0.0.1:" + deadPort()
                + "; DIRECT");

        assertThat(connectThenGet()).contains("SAW=GET /through HTTP/1.1");
    }

    @Test
    void whenEveryEntryFailsTheLastFailureIsReported() throws Exception {
        startTunnelProxy("PROXY 127.0.0.1:" + deadPort() + "; PROXY 127.0.0.1:" + deadPort());

        assertThat(connectThenGet())
                .startsWith("HTTP/1.1 502")
                .containsIgnoringCase("X-TestingBot-Error:");
    }

    @Test
    void aProxyThatRefusesIsTheAnswerNotAReasonToGoDirect() throws Exception {
        // Reached and said no. Going DIRECT now would route around the network's decision.
        startTunnelProxy("PROXY 127.0.0.1:" + refusingProxyPort + "; DIRECT");

        assertThat(connectThenGet())
                .startsWith("HTTP/1.1 502")
                .containsIgnoringCase("X-TestingBot-Error: upstream-proxy-refused");
    }

    @Test
    void plainHttpMovesPastADeadProxyToDirect() throws Exception {
        startTunnelProxy("PROXY 127.0.0.1:" + deadPort() + "; DIRECT");

        String response = send("GET http://127.0.0.1:" + originPort + "/page HTTP/1.1\r\n"
                + "Host: 127.0.0.1:" + originPort + "\r\nConnection: close\r\n\r\n");

        assertThat(response).startsWith("HTTP/1.1 200").contains("SAW=GET /page HTTP/1.1");
    }

    @Test
    void plainHttpMovesPastADeadProxyToTheNextProxy() throws Exception {
        // The refusing proxy is up, so reaching it at all shows the second entry was used.
        startTunnelProxy("PROXY 127.0.0.1:" + deadPort() + "; PROXY 127.0.0.1:"
                + refusingProxyPort + "; DIRECT");

        String response = send("GET http://127.0.0.1:" + originPort + "/page HTTP/1.1\r\n"
                + "Host: 127.0.0.1:" + originPort + "\r\nConnection: close\r\n\r\n");

        assertThat(response)
                .as("the second proxy's own answer, not DIRECT and not a dial failure")
                .startsWith("HTTP/1.1 403");
    }

    @Test
    void aPlainHttpRequestWithABodyIsNotSentTwice() throws Exception {
        // A failed exchange fails the client's request stream, so a second attempt would forward
        // a broken body; this is reported instead.
        startTunnelProxy("PROXY 127.0.0.1:" + deadPort() + "; DIRECT");

        String response = send("POST http://127.0.0.1:" + originPort + "/form HTTP/1.1\r\n"
                + "Host: 127.0.0.1:" + originPort + "\r\nContent-Length: 3\r\n"
                + "Connection: close\r\n\r\nabc");

        assertThat(response).startsWith("HTTP/1.1 502").containsIgnoringCase("X-TestingBot-Error:");
    }

    @Test
    void aWebsocketUpgradeMovesPastADeadProxyToDirect() throws Exception {
        startTunnelProxy("PROXY 127.0.0.1:" + deadPort() + "; DIRECT");

        try (Socket socket = new Socket("127.0.0.1", proxyPort)) {
            socket.setSoTimeout(10_000);
            socket.getOutputStream().write(("GET http://127.0.0.1:" + originPort + "/ws HTTP/1.1\r\n"
                    + "Host: 127.0.0.1:" + originPort + "\r\n"
                    + "Upgrade: websocket\r\nConnection: Upgrade\r\n"
                    + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n"
                    + "Sec-WebSocket-Version: 13\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();

            assertThat(reader(socket).readLine()).startsWith("HTTP/1.1 101");
        }
    }
}
