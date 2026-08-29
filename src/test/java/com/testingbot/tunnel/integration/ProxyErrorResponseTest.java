package com.testingbot.tunnel.integration;

import com.testingbot.tunnel.App;
import com.testingbot.tunnel.HttpProxy;
import com.testingbot.tunnel.proxy.ProxyErrors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives real failures through the proxy and asserts the classified answer.
 *
 * <p>Deliberately not mock-based: the point of the ticket is that a genuine DNS failure and a
 * genuine refused connection must be told apart, and only real sockets prove that the
 * classification survives however Jetty wraps the exception on the way out.
 */
class ProxyErrorResponseTest {

    private HttpProxy httpProxy;
    private ServerSocket liveOrigin;
    private Thread originThread;
    private int proxyPort;
    private int deadPort;
    private int livePort;

    private static int findFreePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        // A port nothing listens on, for connection-refused.
        deadPort = findFreePort();

        // A live origin, so a test about URI handling is not decided by a refused connection.
        liveOrigin = new ServerSocket(0, 10, InetAddress.getLoopbackAddress());
        livePort = liveOrigin.getLocalPort();
        originThread = new Thread(() -> {
            while (!liveOrigin.isClosed()) {
                try (Socket socket = liveOrigin.accept()) {
                    BufferedReader in = new BufferedReader(
                            new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                    in.readLine();
                    String header;
                    while ((header = in.readLine()) != null && !header.isEmpty()) {
                        // drain
                    }
                    byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
                    socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Length: "
                            + body.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                    socket.getOutputStream().write(body);
                    socket.getOutputStream().flush();
                } catch (IOException closed) {
                    return;
                }
            }
        });
        originThread.setDaemon(true);
        originThread.start();

        proxyPort = findFreePort();
        App app = new App();
        app.setJettyPort(proxyPort);
        app.setClientKey("test_key");
        app.setClientSecret("test_secret");
        app.setFastFail(new String[]{"blocked\\.example\\.com"});
        httpProxy = new HttpProxy(app);

        for (int i = 0; i < 100; i++) {
            try (Socket s = new Socket("127.0.0.1", proxyPort)) {
                break;
            } catch (IOException retry) {
                Thread.sleep(50);
            }
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        if (httpProxy != null) {
            httpProxy.stop();
        }
        if (originThread != null) {
            originThread.interrupt();
        }
        if (liveOrigin != null && !liveOrigin.isClosed()) {
            liveOrigin.close();
        }
    }

    private String send(String requestLine, String host) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", proxyPort)) {
            socket.setSoTimeout(20_000);
            String request = requestLine + "\r\nHost: " + host + "\r\nConnection: close\r\n\r\n";
            socket.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder response = new StringBuilder();
            int contentLength = 0;
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                response.append(line).append('\n');
                if (line.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:")) {
                    contentLength = Integer.parseInt(line.substring(line.indexOf(':') + 1).trim());
                }
            }
            // Read exactly the advertised body. A failed CONNECT leaves the connection open,
            // so reading to EOF would just block until the socket timeout.
            if (contentLength > 0) {
                char[] body = new char[contentLength];
                int read = reader.read(body, 0, contentLength);
                if (read > 0) {
                    response.append(new String(body, 0, read));
                }
            }
            return response.toString();
        }
    }

    @Test
    void unresolvableHost_reportsDnsError() throws Exception {
        // .invalid is reserved by RFC 2606 and never resolves.
        String response = send("GET http://nothing-here.invalid/ HTTP/1.1", "nothing-here.invalid");

        assertThat(response).contains(ProxyErrors.ERROR_HEADER + ": dns-error");
        assertThat(response).contains("dns-error:");
        assertThat(response).contains("502");
    }

    @Test
    void refusedConnection_reportsConnectionRefused() throws Exception {
        String response = send("GET http://127.0.0.1:" + deadPort + "/ HTTP/1.1",
                "127.0.0.1:" + deadPort);

        assertThat(response).contains(ProxyErrors.ERROR_HEADER + ": connection-refused");
        // The distinction from dns-error is the entire point: same 502, different cause.
        assertThat(response).doesNotContain("dns-error");
    }

    @Test
    void fastFailedHost_reportsDeniedByFastFail() throws Exception {
        String response = send("GET http://blocked.example.com/ HTTP/1.1", "blocked.example.com");

        assertThat(response).contains("403");
        assertThat(response).contains(ProxyErrors.ERROR_HEADER + ": denied-by-fast-fail");
    }

    @Test
    void malformedUri_reportsMalformedRequestUri() throws Exception {
        // A "%" not followed by two hex digits: refused, but by us, not the origin (TB-314).
        String response = send("GET http://127.0.0.1:" + livePort + "/?q=100% HTTP/1.1",
                "127.0.0.1:" + livePort);

        assertThat(response).contains("400");
        assertThat(response).contains(ProxyErrors.ERROR_HEADER + ": malformed-request-uri");
    }

    @Test
    void connectToUnresolvableHost_reportsDnsError() throws Exception {
        // CONNECT uses a different failure path than plain HTTP, and previously every CONNECT
        // failure looked the same regardless of cause.
        String response = send("CONNECT nothing-here.invalid:443 HTTP/1.1", "nothing-here.invalid:443");

        assertThat(response).contains(ProxyErrors.ERROR_HEADER + ": dns-error");
    }

    @Test
    void connectToRefusedPort_reportsConnectionRefused() throws Exception {
        String response = send("CONNECT 127.0.0.1:" + deadPort + " HTTP/1.1",
                "127.0.0.1:" + deadPort);

        assertThat(response).contains(ProxyErrors.ERROR_HEADER + ": connection-refused");
    }

    @Test
    void connectToFastFailedHost_reportsDeniedByFastFail() throws Exception {
        String response = send("CONNECT blocked.example.com:443 HTTP/1.1", "blocked.example.com:443");

        assertThat(response).contains("403");
    }
}
