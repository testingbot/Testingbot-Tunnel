package com.testingbot.tunnel.proxy;

import com.testingbot.tunnel.TestPorts;
import com.testingbot.tunnel.App;
import com.testingbot.tunnel.HttpProxy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The CONNECT exchange with an upstream HTTP proxy, driven through a real proxy from a real
 * socket.
 *
 * <p>This used to call the private connectToProxy() reflectively. That method has been replaced
 * by an event-driven handshake on Jetty's selector, and a reflective test would have gone on
 * passing against code no longer reachable -- so it now exercises the path a client actually
 * takes: client socket to local proxy, local proxy to upstream proxy, and back.
 */
class ConnectFramingTest {

    private ServerSocket upstream;
    private ExecutorService pool;
    private HttpProxy httpProxy;
    private int proxyPort;
    private final List<String> requestLines = new CopyOnWriteArrayList<>();
    private final CountDownLatch requestReceived = new CountDownLatch(1);

    private static int findFreePort() throws IOException {
        return TestPorts.free();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (httpProxy != null) {
            httpProxy.stop();
        }
        if (upstream != null && !upstream.isClosed()) {
            upstream.close();
        }
        if (pool != null) {
            pool.shutdownNow();
            pool.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    /**
     * A stand-in upstream proxy.
     *
     * @param statusLine what to answer, or null to accept and stay silent
     * @param pipelined  bytes written in the same write as the response
     */
    private void startUpstream(String statusLine, String pipelined) throws IOException {
        startUpstream(statusLine, pipelined, 0);
    }

    /**
     * A stand-in upstream proxy.
     *
     * @param statusLine what to answer, or null to accept and stay silent
     * @param pipelined  bytes written in the same write as the response
     * @param dripMs     pause between individual response bytes, so the reply arrives in many
     *                   separate reads instead of one
     */
    private void startUpstream(String statusLine, String pipelined, long dripMs)
            throws IOException {
        upstream = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        pool = Executors.newCachedThreadPool();
        pool.submit(() -> {
            while (!upstream.isClosed()) {
                try {
                    Socket socket = upstream.accept();
                    pool.submit(() -> {
                        try {
                            BufferedReader in = new BufferedReader(new InputStreamReader(
                                    socket.getInputStream(), StandardCharsets.UTF_8));
                            List<String> lines = new ArrayList<>();
                            String line;
                            while ((line = in.readLine()) != null && !line.isEmpty()) {
                                lines.add(line);
                            }
                            requestLines.addAll(lines);
                            requestReceived.countDown();

                            if (statusLine != null) {
                                OutputStream out = socket.getOutputStream();
                                byte[] reply = (statusLine + "\r\n\r\n"
                                        + (pipelined == null ? "" : pipelined))
                                        .getBytes(StandardCharsets.US_ASCII);
                                if (dripMs <= 0) {
                                    out.write(reply);
                                    out.flush();
                                } else {
                                    for (byte b : reply) {
                                        out.write(b);
                                        out.flush();
                                        Thread.sleep(dripMs);
                                    }
                                }
                            }
                            Thread.sleep(30_000);
                        } catch (Exception ignored) {
                            // test finished
                        }
                    });
                } catch (IOException closed) {
                    return;
                }
            }
        });
    }

    private void startTunnel() throws Exception {
        proxyPort = findFreePort();
        App app = new App();
        app.setJettyPort(proxyPort);
        app.setClientKey("test_key");
        app.setClientSecret("test_secret");
        app.setProxy("127.0.0.1:" + upstream.getLocalPort());
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

    /** Opens a CONNECT through the tunnel and returns the socket, positioned after the headers. */
    private Socket connect(String extraHeaders, StringBuilder statusOut, int timeoutMs)
            throws Exception {
        Socket socket = new Socket("127.0.0.1", proxyPort);
        socket.setSoTimeout(timeoutMs);
        socket.getOutputStream().write(("CONNECT example.com:443 HTTP/1.1\r\n"
                + "Host: example.com:443\r\n" + extraHeaders + "\r\n")
                .getBytes(StandardCharsets.UTF_8));
        socket.getOutputStream().flush();
        InputStream in = socket.getInputStream();
        StringBuilder head = new StringBuilder();
        while (head.indexOf("\r\n\r\n") < 0) {
            int b = in.read();
            if (b < 0) {
                break;
            }
            head.append((char) b);
        }
        statusOut.append(head.length() == 0 ? "" : head.toString().split("\r\n")[0]);
        return socket;
    }

    @Test
    void aSuccessfulConnectIsFramedCorrectlyAndStripsHopByHopHeaders() throws Exception {
        startUpstream("HTTP/1.1 200 Connection Established", null);
        startTunnel();

        StringBuilder status = new StringBuilder();
        try (Socket ignored = connect("Proxy-Connection: keep-alive\r\nX-Keep: yes\r\n",
                status, 20_000)) {
            assertThat(status.toString()).contains("200");
        }

        assertThat(requestReceived.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(requestLines).first().asString()
                .isEqualTo("CONNECT example.com:443 HTTP/1.1");
        assertThat(requestLines).contains("Host: example.com:443");
        // Hop-by-hop headers belong to our hop, not the upstream proxy's.
        assertThat(requestLines).noneMatch(l -> l.toLowerCase().startsWith("proxy-connection"));
        // Everything else is replayed.
        assertThat(requestLines).contains("X-Keep: yes");
    }

    @Test
    void aRejectionFromTheUpstreamProxyIsNotReportedAsSuccess() throws Exception {
        startUpstream("HTTP/1.1 403 Forbidden", null);
        startTunnel();

        StringBuilder status = new StringBuilder();
        try (Socket ignored = connect("", status, 20_000)) {
            assertThat(status.toString()).doesNotContain("200");
        }
    }

    @Test
    void aStatusLineMentioning200InItsReasonIsStillARejection() throws Exception {
        // The status code is parsed, not searched for: "502 ... 200-style header bug" must not
        // read as success.
        startUpstream("HTTP/1.1 502 Bad Gateway 200-style header bug", null);
        startTunnel();

        StringBuilder status = new StringBuilder();
        try (Socket ignored = connect("", status, 20_000)) {
            assertThat(status.toString()).doesNotContain("200");
        }
    }

    @Test
    void aSilentUpstreamProxyDoesNotHangForever() throws Exception {
        // Accepts the connection and never answers. The handshake runs under its own 15s idle
        // timeout rather than the tunnel's, which is minutes long -- inheriting that one left
        // the client waiting over two minutes for a proxy that was never going to reply.
        startUpstream(null, null);
        startTunnel();

        StringBuilder status = new StringBuilder();
        long start = System.currentTimeMillis();
        try (Socket ignored = connect("", status, 90_000)) {
            long elapsed = System.currentTimeMillis() - start;
            assertThat(status.toString()).doesNotContain("200");
            assertThat(elapsed)
                    .as("should give up on the handshake timeout, not the tunnel's idle timeout")
                    .isLessThan(45_000);
        }
    }

    @Test
    void aReplyArrivingOneByteAtATimeIsReassembled() throws Exception {
        // The blocking version read the reply inside one select loop it owned. The event-driven
        // one is re-entered by the selector for every readable event, so the parse state has to
        // survive between callbacks -- and a fill() returning 0 must re-arm rather than be read
        // as end-of-stream. A drip forces dozens of those callbacks.
        startUpstream("HTTP/1.1 200 Connection Established", null, 5);
        startTunnel();

        StringBuilder status = new StringBuilder();
        try (Socket ignored = connect("", status, 20_000)) {
            assertThat(status.toString()).contains("200");
        }
    }

    @Test
    void aRejectionArrivingOneByteAtATimeIsStillARejection() throws Exception {
        startUpstream("HTTP/1.1 407 Proxy Authentication Required", null, 5);
        startTunnel();

        StringBuilder status = new StringBuilder();
        try (Socket ignored = connect("", status, 20_000)) {
            assertThat(status.toString()).doesNotContain("200");
        }
    }

    @Test
    void anUpstreamThatHangsUpMidReplyFailsRatherThanWaits() throws Exception {
        // Half a status line and then EOF. fill() returns -1; without treating that as a failure
        // the handshake would sit re-arming fillInterested until the idle timeout.
        upstream = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        pool = Executors.newCachedThreadPool();
        pool.submit(() -> {
            try (Socket socket = upstream.accept()) {
                socket.getInputStream().read(new byte[1024]);
                socket.getOutputStream().write("HTTP/1.1 200 Conn".getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().flush();
            } catch (IOException closed) {
                // test finished
            }
        });
        startTunnel();

        StringBuilder status = new StringBuilder();
        long start = System.currentTimeMillis();
        try (Socket ignored = connect("", status, 30_000)) {
            assertThat(status.toString()).doesNotContain("200");
            assertThat(System.currentTimeMillis() - start)
                    .as("EOF should fail the handshake at once, not wait for the timeout")
                    .isLessThan(10_000);
        }
    }

    @Test
    void bytesPipelinedAfterTheResponseAreNotSwallowed() throws Exception {
        // A proxy may put its 200 and the first bytes of the tunnelled stream in one segment.
        // Reading the reply in blocks took those bytes off the socket and dropped them, and the
        // TLS handshake inside failed with nothing to explain it. 8 KiB is large enough that a
        // block read must swallow part of it rather than depending on TCP segmentation.
        String pipelined = "X".repeat(8192);
        startUpstream("HTTP/1.1 200 Connection Established", pipelined);
        startTunnel();

        StringBuilder status = new StringBuilder();
        try (Socket socket = connect("", status, 20_000)) {
            assertThat(status.toString()).contains("200");

            byte[] relayed = new byte[pipelined.length()];
            int got = 0;
            InputStream in = socket.getInputStream();
            while (got < relayed.length) {
                int read = in.read(relayed, got, relayed.length - got);
                if (read < 0) {
                    break;
                }
                got += read;
            }

            assertThat(got).as("every pipelined byte should reach the client").isEqualTo(pipelined.length());
            assertThat(new String(relayed, StandardCharsets.US_ASCII)).isEqualTo(pipelined);
        }
    }
}
