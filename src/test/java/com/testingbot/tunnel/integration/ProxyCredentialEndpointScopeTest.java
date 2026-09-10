package com.testingbot.tunnel.integration;

import com.testingbot.tunnel.App;
import com.testingbot.tunnel.Await;
import com.testingbot.tunnel.HttpProxy;
import com.testingbot.tunnel.TestPorts;
import org.junit.jupiter.api.AfterEach;
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
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code --proxy-userpwd} belongs to one endpoint, not to one hostname.
 *
 * <p>The CONNECT and WebSocket paths asked whether the chosen upstream's <em>host</em> matched
 * {@code --proxy}, which on the loopback interface every peer does. A PAC file naming
 * {@code 127.0.0.1} on any other port therefore received the customer's proxy password -- and
 * another service or another local user can own that port. The plain-HTTP path already withheld
 * it, so the same tunnel's answer depended on the scheme.
 */
class ProxyCredentialEndpointScopeTest {

    private static final String USER_PASSWORD = "audit-user:audit-password";

    @TempDir
    Path tempDir;

    private final List<String> configuredProxyLog = new CopyOnWriteArrayList<>();
    private final List<String> pacProxyLog = new CopyOnWriteArrayList<>();

    private ServerSocket configuredProxy;
    private ServerSocket pacProxy;
    private HttpProxy tunnel;
    private int tunnelPort;

    @AfterEach
    void tearDown() throws Exception {
        if (tunnel != null) {
            tunnel.stop();
        }
        for (ServerSocket socket : new ServerSocket[]{configuredProxy, pacProxy}) {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        }
    }

    /** Records the request head it is given and refuses it; nothing has to be relayed. */
    private ServerSocket recordingProxy(List<String> log) throws Exception {
        ServerSocket server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Thread acceptor = new Thread(() -> {
            while (!server.isClosed()) {
                try (Socket socket = server.accept()) {
                    BufferedReader in = new BufferedReader(new InputStreamReader(
                            socket.getInputStream(), StandardCharsets.UTF_8));
                    List<String> head = new ArrayList<>();
                    String line;
                    while ((line = in.readLine()) != null && !line.isEmpty()) {
                        head.add(line);
                    }
                    log.addAll(head);
                    OutputStream out = socket.getOutputStream();
                    out.write(("HTTP/1.1 407 Proxy Authentication Required\r\n"
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
        return server;
    }

    /**
     * @param pacTarget the endpoint the PAC file routes everything to
     */
    private void startTunnel(int pacTarget, String wsProxyMode) throws Exception {
        Path pac = tempDir.resolve("routing.pac");
        Files.writeString(pac, "function FindProxyForURL(url, host) {"
                + " return \"PROXY 127.0.0.1:" + pacTarget + "\"; }");

        tunnelPort = TestPorts.free();
        App app = new App();
        app.setJettyPort(tunnelPort);
        app.setClientKey("test_key");
        app.setClientSecret("test_secret");
        app.setProxy("127.0.0.1:" + configuredProxy.getLocalPort());
        app.setProxyAuth(USER_PASSWORD);
        app.setPacLocal(pac.toString());
        if (wsProxyMode != null) {
            app.setWsProxyMode(wsProxyMode);
        }
        tunnel = new HttpProxy(app);
        waitForPort(tunnelPort);
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

    private void send(String requestHead, List<String> expectRecordedIn) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", tunnelPort)) {
            socket.setSoTimeout(10_000);
            socket.getOutputStream().write(requestHead.getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            new BufferedReader(new InputStreamReader(
                    socket.getInputStream(), StandardCharsets.UTF_8)).readLine();
            Await.until("the upstream to record the request head",
                    () -> !expectRecordedIn.isEmpty());
        }
    }

    private static String connectRequest() {
        return "CONNECT target.example.com:443 HTTP/1.1\r\nHost: target.example.com:443\r\n\r\n";
    }

    private static String upgradeRequest() {
        return "GET http://target.example.com/ws HTTP/1.1\r\nHost: target.example.com\r\n"
                + "Upgrade: websocket\r\nConnection: Upgrade\r\n"
                + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n\r\n";
    }

    @Test
    void connectWithholdsTheCredentialFromAProxyOnAnotherPort() throws Exception {
        configuredProxy = recordingProxy(configuredProxyLog);
        pacProxy = recordingProxy(pacProxyLog);
        startTunnel(pacProxy.getLocalPort(), null);

        send(connectRequest(), pacProxyLog);

        assertThat(pacProxyLog).anyMatch(h -> h.startsWith("CONNECT target.example.com:443"));
        assertThat(pacProxyLog)
                .as("same host, different port: not the proxy --proxy-userpwd names")
                .noneMatch(h -> h.startsWith("Proxy-Authorization"));
        assertThat(configuredProxyLog).isEmpty();
    }

    @Test
    void connectStillSendsItToTheProxyThatWasConfigured() throws Exception {
        // The other half of the rule: narrowing must not withhold the credential from its
        // rightful recipient when the file routes there.
        configuredProxy = recordingProxy(configuredProxyLog);
        pacProxy = recordingProxy(pacProxyLog);
        startTunnel(configuredProxy.getLocalPort(), null);

        send(connectRequest(), configuredProxyLog);

        assertThat(configuredProxyLog).anyMatch(h -> h.startsWith("Proxy-Authorization: Basic "));
        assertThat(pacProxyLog).isEmpty();
    }

    @Test
    void aWebsocketUpgradeWithholdsItFromAProxyOnAnotherPort() throws Exception {
        // WebSocket upgrades are intercepted before the CONNECT handler, so they carry their own
        // copy of this decision -- and had their own copy of the bug.
        configuredProxy = recordingProxy(configuredProxyLog);
        pacProxy = recordingProxy(pacProxyLog);
        startTunnel(pacProxy.getLocalPort(), null);

        send(upgradeRequest(), pacProxyLog);

        assertThat(pacProxyLog).anyMatch(h -> h.startsWith("CONNECT target.example.com:80"));
        assertThat(pacProxyLog).noneMatch(h -> h.startsWith("Proxy-Authorization"));
        assertThat(configuredProxyLog).isEmpty();
    }

    @Test
    void anUpgradeSentAsGetWithholdsItToo() throws Exception {
        // --ws-proxy-mode get puts the upgrade on the wire as an absolute-URI request instead of
        // tunnelling it, which is a second place the header is written.
        configuredProxy = recordingProxy(configuredProxyLog);
        pacProxy = recordingProxy(pacProxyLog);
        startTunnel(pacProxy.getLocalPort(), "get");

        send(upgradeRequest(), pacProxyLog);

        assertThat(pacProxyLog).anyMatch(h -> h.startsWith("GET http://target.example.com/ws"));
        assertThat(pacProxyLog).noneMatch(h -> h.startsWith("Proxy-Authorization"));
        assertThat(configuredProxyLog).isEmpty();
    }

    @Test
    void aWebsocketUpgradeStillSendsItToTheConfiguredProxy() throws Exception {
        configuredProxy = recordingProxy(configuredProxyLog);
        pacProxy = recordingProxy(pacProxyLog);
        startTunnel(configuredProxy.getLocalPort(), "get");

        send(upgradeRequest(), configuredProxyLog);

        assertThat(configuredProxyLog).anyMatch(h -> h.startsWith("Proxy-Authorization: Basic "));
        assertThat(pacProxyLog).isEmpty();
    }
}
