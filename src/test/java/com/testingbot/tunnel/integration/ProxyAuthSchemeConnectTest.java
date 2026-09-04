package com.testingbot.tunnel.integration;

import com.testingbot.tunnel.TestPorts;
import com.testingbot.tunnel.App;
import com.testingbot.tunnel.HttpProxy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the CONNECT path actually puts on the wire for {@code --proxy-auth-scheme}.
 *
 * <p>The Kerberos exchange itself needs a KDC, so what is checked here is everything around it:
 * that Basic still works untouched, and that with Negotiate configured but no credentials
 * available the tunnel sends no bogus header and still produces a CONNECT the proxy can answer
 * with 407 -- rather than dying inside the connect path with a Kerberos error.
 */
class ProxyAuthSchemeConnectTest {

    private ServerSocket upstream;
    private Thread acceptor;
    private HttpProxy httpProxy;
    private int proxyPort;
    private final List<String> connectHeaders = new CopyOnWriteArrayList<>();

    private static int findFreePort() throws IOException {
        return TestPorts.free();
    }

    /** Records the CONNECT it receives and always refuses, so nothing has to be tunnelled. */
    private void startUpstream() throws Exception {
        upstream = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        acceptor = new Thread(() -> {
            while (!upstream.isClosed()) {
                try (Socket socket = upstream.accept()) {
                    BufferedReader in = new BufferedReader(
                            new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                    List<String> lines = new ArrayList<>();
                    String line;
                    while ((line = in.readLine()) != null && !line.isEmpty()) {
                        lines.add(line);
                    }
                    connectHeaders.addAll(lines);
                    OutputStream out = socket.getOutputStream();
                    out.write(("HTTP/1.1 407 Proxy Authentication Required\r\n"
                            + "Proxy-Authenticate: Negotiate\r\n"
                            + "Content-Length: 0\r\nConnection: close\r\n\r\n")
                            .getBytes(StandardCharsets.UTF_8));
                    out.flush();
                } catch (IOException closed) {
                    return;
                }
            }
        });
        acceptor.setDaemon(true);
        acceptor.start();
    }

    private void startTunnel(String scheme, String userPassword) throws Exception {
        proxyPort = findFreePort();
        App app = new App();
        app.setJettyPort(proxyPort);
        app.setClientKey("test_key");
        app.setClientSecret("test_secret");
        app.setProxy("127.0.0.1:" + upstream.getLocalPort());
        if (userPassword != null) {
            app.setProxyAuth(userPassword);
        }
        if (scheme != null) {
            app.setProxyAuthScheme(scheme);
        }
        httpProxy = new HttpProxy(app);
        waitForPort(proxyPort);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (httpProxy != null) {
            httpProxy.stop();
        }
        if (acceptor != null) {
            acceptor.interrupt();
        }
        if (upstream != null && !upstream.isClosed()) {
            upstream.close();
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

    /** Issues a CONNECT through the tunnel and returns its status line. */
    private String connectThroughTunnel() throws Exception {
        try (Socket socket = new Socket("127.0.0.1", proxyPort)) {
            socket.setSoTimeout(10_000);
            socket.getOutputStream().write(
                    ("CONNECT target.example.com:443 HTTP/1.1\r\nHost: target.example.com:443\r\n\r\n")
                            .getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            String status = reader.readLine();
            Thread.sleep(200);
            return status == null ? "" : status;
        }
    }

    @Test
    void basicIsStillTheDefaultAndSendsCredentials() throws Exception {
        startUpstream();
        startTunnel(null, "user:secret");

        connectThroughTunnel();

        assertThat(connectHeaders).anyMatch(h -> h.startsWith("Proxy-Authorization: Basic "));
        assertThat(connectHeaders).noneMatch(h -> h.contains("Negotiate"));
    }

    @Test
    void explicitBasicBehavesTheSame() throws Exception {
        startUpstream();
        startTunnel("basic", "user:secret");

        connectThroughTunnel();

        assertThat(connectHeaders).anyMatch(h -> h.startsWith("Proxy-Authorization: Basic "));
    }

    @Test
    void negotiateWithoutCredentials_sendsNoHeaderInsteadOfFailingTheDial() throws Exception {
        // No KDC here. The CONNECT must still be made so the proxy's 407 is what the user sees,
        // rather than a Kerberos exception thrown from inside the connect path.
        startUpstream();
        startTunnel("negotiate", null);

        String status = connectThroughTunnel();

        assertThat(connectHeaders).anyMatch(h -> h.startsWith("CONNECT target.example.com:443"));
        assertThat(connectHeaders).noneMatch(h -> h.startsWith("Proxy-Authorization"));
        assertThat(status).isNotBlank();
    }

    @Test
    void negotiateIgnoresProxyUserPassword() throws Exception {
        // Kerberos credentials never come from --proxy-userpwd, so a stray Basic header must
        // not be sent alongside a Negotiate configuration.
        startUpstream();
        startTunnel("negotiate", "user:secret");

        connectThroughTunnel();

        assertThat(connectHeaders)
                .as("the CONNECT must have reached the proxy, or the absence below means nothing")
                .anyMatch(h -> h.startsWith("CONNECT target.example.com:443"));
        assertThat(connectHeaders).noneMatch(h -> h.startsWith("Proxy-Authorization: Basic"));
    }
}
