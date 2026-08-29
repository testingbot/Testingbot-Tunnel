package com.testingbot.tunnel.integration;

import com.testingbot.tunnel.App;
import com.testingbot.tunnel.HttpProxy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Query strings that are not strictly RFC 3986 but which browsers, API clients and test
 * suites send constantly must survive the trip through the proxy.
 *
 * <p>These used to fail with 500: {@code ProxyHandler} builds the proxy-to-server request via
 * {@code HttpURI.toURI()}, and {@code java.net.URI} rejects characters that Jetty's own server
 * parser had already accepted. The request never reached the origin and the customer saw an
 * error that looked like a broken tunnel.
 *
 * <p>The origin here is a raw socket rather than {@code com.sun.net.httpserver}, which is
 * strict enough to reject these itself — it would mask what the proxy actually forwarded.
 */
class MalformedQueryStringTest {

    private ServerSocket origin;
    private ExecutorService originPool;
    private Thread acceptor;
    private HttpProxy httpProxy;
    private int originPort;
    private int proxyPort;

    private static int findFreePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        origin = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        originPort = origin.getLocalPort();
        originPool = Executors.newCachedThreadPool();

        // Echoes the request line back so the test can assert on exactly what was forwarded.
        acceptor = new Thread(() -> {
            while (!origin.isClosed()) {
                try {
                    Socket accepted = origin.accept();
                    originPool.submit(() -> {
                        try (Socket socket = accepted) {
                            BufferedReader in = new BufferedReader(
                                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
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
        if (acceptor != null) {
            acceptor.interrupt();
        }
        if (origin != null && !origin.isClosed()) {
            origin.close();
        }
        if (originPool != null) {
            originPool.shutdownNow();
            originPool.awaitTermination(5, TimeUnit.SECONDS);
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

    /** Sends an absolute-form request through the proxy and returns "status\nbody". */
    private String proxyGet(String pathQuery) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", proxyPort)) {
            socket.setSoTimeout(10_000);
            String request = "GET http://127.0.0.1:" + originPort + pathQuery + " HTTP/1.1\r\n"
                    + "Host: 127.0.0.1:" + originPort + "\r\n"
                    + "Connection: close\r\n\r\n";
            socket.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            String status = reader.readLine();
            StringBuilder rest = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                rest.append(line).append('\n');
            }
            return status + "\n" + rest;
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "/?a=1&b={json}",            // JSON in a query parameter
            "/?tpl={{name}}",            // template placeholders
            "/?redirect=http://x|y",     // pipe
            "/?path=a[0]",               // array indexing
            "/?q=a^b`c",                 // caret and backtick
            "/?sql=a<b>c",               // angle brackets
            "/?q=\"quoted\"",            // double quotes
            "/?json=%7B%22a%22%3A1%7D",  // properly percent-encoded
    })
    void queryStringsTheServerAccepts_areForwardedVerbatim(String pathQuery) throws Exception {
        String response = proxyGet(pathQuery);

        assertThat(response).as("status for %s", pathQuery).contains("200 OK");
        // The origin echoes the request line, so this proves the characters survived the hop
        // rather than being dropped or re-encoded.
        assertThat(response).as("forwarded target for %s", pathQuery)
                .contains("SAW=GET " + pathQuery + " HTTP/1.1");
    }

    @Test
    void bareTrailingPercent_isRejectedAsBadRequestNotBadGateway() throws Exception {
        // "%" not followed by two hex digits is genuinely malformed, so refusing it is right.
        // What matters is which refusal: 502 would blame the customer's website for a request
        // we never sent, and send them debugging a server that is fine.
        String response = proxyGet("/?q=100%");

        assertThat(response).contains("400 Bad Request");
        assertThat(response).doesNotContain("502");
    }

    @Test
    void rawSpaceInRequestLine_isRejected() throws Exception {
        // Documents intended behaviour rather than a defect: a raw space makes the request
        // line ambiguous, so no conforming client sends one — browsers percent-encode it.
        String response = proxyGet("/?q=hello world");

        assertThat(response).contains("400 Bad Request");
    }

    @Test
    void rawNonAsciiQuery_isProxiedButNotByteExact() throws Exception {
        // Documents current behaviour, and is deliberately not part of the verbatim set above.
        //
        // A raw UTF-8 query is proxied successfully, but the bytes do not survive unchanged:
        // an HTTP request line is ISO-8859-1 by specification, so the two bytes of "é" are not
        // reproduced on the far side. That is an encoding question, separate from this
        // ticket's subject (characters that are legal bytes but illegal URI syntax), and it
        // behaved this way both before and after the fix. Clients that need non-ASCII in a
        // query should percent-encode it, as browsers do.
        String response = proxyGet("/?q=café");

        assertThat(response).contains("200 OK");
        assertThat(response).contains("SAW=GET /?q=caf");
    }

    @Test
    void encodedSpace_isForwarded() throws Exception {
        String response = proxyGet("/?q=hello%20world");

        assertThat(response).contains("200 OK");
        assertThat(response).contains("SAW=GET /?q=hello%20world HTTP/1.1");
    }
}
