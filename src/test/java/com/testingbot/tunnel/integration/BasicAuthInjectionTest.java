package com.testingbot.tunnel.integration;

import com.testingbot.tunnel.App;
import com.testingbot.tunnel.HttpProxy;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code --auth host:port:user:password} exists so a test can reach a staging site that demands
 * Basic credentials without the test itself knowing them.
 *
 * <p>BasicAuthTest checks that the credential reaches jetty-client's AuthenticationStore, which
 * is a different claim: a credential can be stored correctly and still never be sent. This
 * drives a real request through the proxy at an origin that actually challenges.
 */
class BasicAuthInjectionTest {

    private HttpServer origin;
    private HttpProxy httpProxy;
    private final List<String> authorizations = new CopyOnWriteArrayList<>();

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
        if (origin != null) {
            origin.stop(0);
        }
    }

    /** An origin that answers 401 until it is given {@code e2euser:e2epass}. */
    private int startOrigin() throws IOException {
        int port = findFreePort();
        origin = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        origin.createContext("/protected", exchange -> {
            String header = exchange.getRequestHeaders().getFirst("Authorization");
            authorizations.add(header == null ? "<none>" : header);
            String expected = "Basic " + Base64.getEncoder().encodeToString(
                    "e2euser:e2epass".getBytes(StandardCharsets.UTF_8));
            byte[] body;
            int status;
            if (expected.equals(header)) {
                status = 200;
                body = "protected-ok".getBytes(StandardCharsets.UTF_8);
            } else {
                status = 401;
                body = "unauthorized".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("WWW-Authenticate", "Basic realm=\"test\"");
            }
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        origin.start();
        return port;
    }

    private int startProxy(String basicAuth) throws Exception {
        int port = findFreePort();
        App app = new App();
        app.setJettyPort(port);
        app.setClientKey("test_key");
        app.setClientSecret("test_secret");
        if (basicAuth != null) {
            app.setBasicAuth(new String[]{basicAuth});
        }
        httpProxy = new HttpProxy(app);
        for (int i = 0; i < 100; i++) {
            try (Socket s = new Socket("127.0.0.1", port)) {
                return port;
            } catch (IOException retry) {
                Thread.sleep(50);
            }
        }
        throw new IllegalStateException("proxy did not start");
    }

    /** A proxied GET, written by hand so the client sends no credentials of its own. */
    private String fetchThroughProxy(int proxyPort, String url) throws Exception {
        return fetchThroughProxy(proxyPort, url, "");
    }

    private String fetchThroughProxy(int proxyPort, String url, String extraHeaders)
            throws Exception {
        try (Socket socket = new Socket("127.0.0.1", proxyPort)) {
            socket.setSoTimeout(20_000);
            String host = url.substring(url.indexOf("//") + 2, url.indexOf('/', url.indexOf("//") + 2));
            socket.getOutputStream().write(("GET " + url + " HTTP/1.1\r\nHost: " + host
                    + "\r\n" + extraHeaders
                    + "Connection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
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
    void theConfiguredCredentialsAreSentToTheOrigin() throws Exception {
        int originPort = startOrigin();
        int proxyPort = startProxy("127.0.0.1:" + originPort + ":e2euser:e2epass");

        String response = fetchThroughProxy(proxyPort,
                "http://127.0.0.1:" + originPort + "/protected");

        assertThat(response)
                .as("the client sent no credentials, so --auth has to supply them")
                .contains("protected-ok");
        assertThat(authorizations)
                .as("the origin must have received a Basic credential")
                .anyMatch(header -> header.startsWith("Basic "));
    }

    @Test
    void aCredentialTheClientSuppliedIsNotOverwritten() throws Exception {
        // The client meant what it sent; a proxy quietly substituting its own credential would
        // be a surprising thing to debug.
        int originPort = startOrigin();
        int proxyPort = startProxy("127.0.0.1:" + originPort + ":wrong-user:wrong-pass");

        String encoded = Base64.getEncoder().encodeToString(
                "e2euser:e2epass".getBytes(StandardCharsets.UTF_8));
        String response = fetchThroughProxy(proxyPort,
                "http://127.0.0.1:" + originPort + "/protected",
                "Authorization: Basic " + encoded + "\r\n");

        assertThat(response).contains("protected-ok");
        assertThat(authorizations).containsExactly("Basic " + encoded);
    }

    @Test
    void anotherHostDoesNotReceiveTheCredential() throws Exception {
        // Configured for a port nothing is listening on, so a request to the real origin must
        // not pick it up.
        int originPort = startOrigin();
        int proxyPort = startProxy("127.0.0.1:" + (originPort + 1) + ":e2euser:e2epass");

        fetchThroughProxy(proxyPort, "http://127.0.0.1:" + originPort + "/protected");

        assertThat(authorizations).containsExactly("<none>");
    }

    @Test
    void withoutTheOptionTheChallengeIsPassedBackUntouched() throws Exception {
        int originPort = startOrigin();
        int proxyPort = startProxy(null);

        String response = fetchThroughProxy(proxyPort,
                "http://127.0.0.1:" + originPort + "/protected");

        assertThat(response).contains("401");
        assertThat(authorizations).containsExactly("<none>");
    }
}
