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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The request line has to follow the route that was actually chosen, not the one {@code --proxy}
 * describes.
 *
 * <p>{@link UpstreamProxyAbsoluteFormTest} fixed origin-form leaking to a static upstream proxy,
 * but the test for it asked {@code --proxy} whether a proxy was in use. A proxy named only by a
 * PAC file was therefore still sent origin-form -- the same 400 from Squid, on the path where
 * nothing looked -- and a PAC file answering DIRECT while {@code --proxy} was configured sent
 * absolute-form straight at an origin server, which is not required to understand it.
 */
class PacAbsoluteFormTest {

    @TempDir
    Path tempDir;

    private ServerSocket peer;
    private ExecutorService pool;
    private Thread acceptor;
    private HttpProxy httpProxy;
    private int proxyPort;

    @BeforeEach
    void setUp() throws Exception {
        // Stands in for either end of the decision: a forward proxy or an origin server. Both
        // answer 200 and echo the request line, so the test can see which form it was given.
        peer = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        pool = Executors.newCachedThreadPool();
        acceptor = new Thread(() -> {
            while (!peer.isClosed()) {
                try {
                    Socket accepted = peer.accept();
                    pool.submit(() -> {
                        try (Socket socket = accepted) {
                            BufferedReader in = new BufferedReader(new InputStreamReader(
                                    socket.getInputStream(), StandardCharsets.UTF_8));
                            String requestLine = in.readLine();
                            String line;
                            while ((line = in.readLine()) != null && !line.isEmpty()) {
                                // drain headers
                            }
                            byte[] body = ("SAW=" + requestLine).getBytes(StandardCharsets.UTF_8);
                            OutputStream out = socket.getOutputStream();
                            out.write(("HTTP/1.1 200 OK\r\nContent-Length: " + body.length
                                    + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                            out.write(body);
                            out.flush();
                        } catch (Exception ignored) {
                            // client went away; nothing useful to do
                        }
                    });
                } catch (IOException closed) {
                    return;
                }
            }
        });
        acceptor.setDaemon(true);
        acceptor.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (httpProxy != null) {
            httpProxy.stop();
        }
        if (acceptor != null) {
            acceptor.interrupt();
        }
        if (peer != null && !peer.isClosed()) {
            peer.close();
        }
        if (pool != null) {
            pool.shutdownNow();
            Thread.interrupted();
            pool.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private void startTunnelProxy(String pacScript, String staticProxy) throws Exception {
        Path pac = tempDir.resolve("routing.pac");
        Files.writeString(pac, pacScript);

        proxyPort = TestPorts.free();
        App app = new App();
        app.setJettyPort(proxyPort);
        app.setClientKey("test_key");
        app.setClientSecret("test_secret");
        if (staticProxy != null) {
            app.setProxy(staticProxy);
        }
        app.setPacLocal(pac.toString());
        httpProxy = new HttpProxy(app);
        waitForPort(proxyPort);
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

    private String proxyGet(String host, String pathQuery) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", proxyPort)) {
            socket.setSoTimeout(10_000);
            String request = "GET http://" + host + pathQuery + " HTTP/1.1\r\n"
                    + "Host: " + host + "\r\nConnection: close\r\n\r\n";
            socket.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            BufferedReader reader = new BufferedReader(new InputStreamReader(
                    socket.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder all = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                all.append(line).append('\n');
            }
            return all.toString();
        }
    }

    @Test
    void aProxyNamedOnlyByPacStillGetsAbsoluteForm() throws Exception {
        // The lenient query string is what makes this visible: jetty-client leaves getURI() null
        // for it, so nothing rewrites the target and the form is whatever this code decided.
        startTunnelProxy("function FindProxyForURL(url, host) {"
                + " return \"PROXY 127.0.0.1:" + peer.getLocalPort() + "\"; }", null);

        String response = proxyGet("example.test", "/?a=1&b={json}");

        assertThat(response)
                .as("a PAC-selected proxy is still a proxy, and RFC 9112 3.2.2 applies to it")
                .contains("SAW=GET http://example.test/?a=1&b={json} HTTP/1.1");
    }

    @Test
    void aPacDirectAnswerSendsOriginFormEvenWhenAStaticProxyIsConfigured() throws Exception {
        // --proxy is configured but the file overrules it for this host, so the request is
        // addressed to the origin. An origin server may reject an absolute-form target.
        startTunnelProxy("function FindProxyForURL(url, host) { return \"DIRECT\"; }",
                "127.0.0.1:1");

        String response = proxyGet("127.0.0.1:" + peer.getLocalPort(), "/?a=1&b={json}");

        assertThat(response).contains("SAW=GET /?a=1&b={json} HTTP/1.1");
    }

    @Test
    void thePacFileSeesThePathTheRequestActuallyUses() throws Exception {
        // The routing method used to substitute "scheme://host:port/" for every request, so a
        // rule written on the path matched nothing here while --pac-test, which evaluates the
        // URL it is given, said it did. A file could be validated and then not followed.
        startTunnelProxy("function FindProxyForURL(url, host) {"
                + " if (shExpMatch(url, \"http://*/private*\"))"
                + "   return \"PROXY 127.0.0.1:" + peer.getLocalPort() + "\";"
                + " return \"DIRECT\"; }", null);

        String proxied = proxyGet("example.test", "/private/thing");

        assertThat(proxied)
                .as("the rule matches the real path, so this goes through the named proxy")
                .contains("SAW=GET http://example.test/private/thing HTTP/1.1");

        String direct = proxyGet("127.0.0.1:" + peer.getLocalPort(), "/public/thing");

        assertThat(direct)
                .as("a path the rule does not match is dialled directly")
                .contains("SAW=GET /public/thing HTTP/1.1");
    }
}
