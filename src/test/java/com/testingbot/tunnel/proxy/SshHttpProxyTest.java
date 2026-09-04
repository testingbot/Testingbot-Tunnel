package com.testingbot.tunnel.proxy;

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
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The SSH connection's own CONNECT through an upstream proxy.
 *
 * <p>This path did not exist before TB-321: {@code --proxy} affected browser traffic only, so on
 * a network whose only egress is a proxy the SSH control connection could not be made at all.
 */
class SshHttpProxyTest {

    private ServerSocket proxy;
    private Thread acceptor;
    private SshHttpProxy client;

    @AfterEach
    void tearDown() throws Exception {
        if (client != null) {
            client.close();
        }
        if (acceptor != null) {
            acceptor.interrupt();
        }
        if (proxy != null && !proxy.isClosed()) {
            proxy.close();
        }
    }

    /** Stub proxy that records the CONNECT it was sent and replies with {@code response}. */
    private AtomicReference<List<String>> startProxy(String response) throws Exception {
        proxy = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        AtomicReference<List<String>> seen = new AtomicReference<>(new ArrayList<>());
        acceptor = new Thread(() -> {
            try (Socket socket = proxy.accept()) {
                BufferedReader in = new BufferedReader(
                        new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                List<String> lines = new ArrayList<>();
                String line;
                while ((line = in.readLine()) != null && !line.isEmpty()) {
                    lines.add(line);
                }
                seen.set(lines);
                OutputStream out = socket.getOutputStream();
                out.write(response.getBytes(StandardCharsets.UTF_8));
                out.flush();
                // Stand in for the SSH banner the real server would send after the tunnel opens.
                out.write("SSH-2.0-Test\r\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
                Thread.sleep(500);
            } catch (Exception ignored) {
                // test finished
            }
        });
        acceptor.setDaemon(true);
        acceptor.start();
        return seen;
    }

    @Test
    void sendsConnectAndSucceedsOn200() throws Exception {
        AtomicReference<List<String>> seen = startProxy("HTTP/1.1 200 Connection established\r\n\r\n");
        client = new SshHttpProxy("127.0.0.1", proxy.getLocalPort(), ProxyAuthenticator.none());

        client.connect(null, "hub.testingbot.com", 443, 5_000);

        assertThat(seen.get()).first().asString()
                .isEqualTo("CONNECT hub.testingbot.com:443 HTTP/1.1");
        assertThat(seen.get()).contains("Host: hub.testingbot.com:443");
        assertThat(client.getSocket()).isNotNull();
    }

    @Test
    void sendsBasicCredentialsWhenConfigured() throws Exception {
        AtomicReference<List<String>> seen = startProxy("HTTP/1.1 200 OK\r\n\r\n");
        client = new SshHttpProxy("127.0.0.1", proxy.getLocalPort(),
                ProxyAuthenticator.basic("user:secret", null));

        client.connect(null, "hub.testingbot.com", 443, 5_000);

        assertThat(seen.get()).anyMatch(h -> h.startsWith("Proxy-Authorization: Basic "));
    }

    @Test
    void sendsNoAuthorizationHeaderWhenNothingIsConfigured() throws Exception {
        AtomicReference<List<String>> seen = startProxy("HTTP/1.1 200 OK\r\n\r\n");
        client = new SshHttpProxy("127.0.0.1", proxy.getLocalPort(), ProxyAuthenticator.none());

        client.connect(null, "hub.testingbot.com", 443, 5_000);

        assertThat(seen.get()).noneMatch(h -> h.startsWith("Proxy-Authorization"));
    }

    @Test
    void refusalIsReportedWithTheProxyStatus() throws Exception {
        startProxy("HTTP/1.1 407 Proxy Authentication Required\r\n"
                + "Proxy-Authenticate: Negotiate\r\n\r\n");
        client = new SshHttpProxy("127.0.0.1", proxy.getLocalPort(), ProxyAuthenticator.none());

        assertThatThrownBy(() -> client.connect(null, "hub.testingbot.com", 443, 5_000))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("407")
                // A 407 here is nearly always a misconfigured scheme, so point at the fix.
                .hasMessageContaining("--proxy-auth-scheme");
    }

    @Test
    void leavesTheSshBannerUnread() throws Exception {
        // Over-reading past the header block would swallow the start of the SSH protocol
        // exchange, which fails later and much less obviously.
        startProxy("HTTP/1.1 200 OK\r\nX-Padding: filler\r\n\r\n");
        client = new SshHttpProxy("127.0.0.1", proxy.getLocalPort(), ProxyAuthenticator.none());

        client.connect(null, "hub.testingbot.com", 443, 5_000);

        byte[] buffer = new byte[13];
        int read = client.getInputStream().read(buffer);
        assertThat(new String(buffer, 0, read, StandardCharsets.UTF_8)).isEqualTo("SSH-2.0-Test\r");
    }

    @Test
    void connectRequestIsWellFormed() {
        SshHttpProxy noAuth = new SshHttpProxy("proxy.example", 8080, ProxyAuthenticator.none());

        assertThat(noAuth.connectRequest("target.example", 443))
                .isEqualTo("CONNECT target.example:443 HTTP/1.1\r\n"
                        + "Host: target.example:443\r\n"
                        + "Proxy-Connection: keep-alive\r\n\r\n");
    }

    @Test
    void statusLineParsing() {
        assertThat(SshHttpProxy.isSuccess("HTTP/1.1 200 Connection established")).isTrue();
        assertThat(SshHttpProxy.isSuccess("HTTP/1.0 200 OK")).isTrue();
        assertThat(SshHttpProxy.isSuccess("HTTP/1.1 299 Odd")).isTrue();
        assertThat(SshHttpProxy.isSuccess("HTTP/1.1 407 Proxy Authentication Required")).isFalse();
        assertThat(SshHttpProxy.isSuccess("HTTP/1.1 502 Bad Gateway")).isFalse();
        assertThat(SshHttpProxy.isSuccess("garbage")).isFalse();
        assertThat(SshHttpProxy.isSuccess("")).isFalse();
    }

    @Test
    void handshakeFailureNamesTheProxyRatherThanTheTarget() throws Exception {
        proxy = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        acceptor = new Thread(() -> {
            try (Socket socket = proxy.accept()) {
                socket.close();   // accept, then hang up without answering
            } catch (Exception ignored) {
                // test finished
            }
        });
        acceptor.setDaemon(true);
        acceptor.start();
        client = new SshHttpProxy("127.0.0.1", proxy.getLocalPort(), ProxyAuthenticator.none());

        // Whether this surfaces as EOF or a reset depends on timing; either way the message
        // must point at the proxy, not at the TestingBot endpoint behind it.
        assertThatThrownBy(() -> client.connect(null, "hub.testingbot.com", 443, 5_000))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("upstream proxy")
                .hasMessageContaining("127.0.0.1");
    }
}
