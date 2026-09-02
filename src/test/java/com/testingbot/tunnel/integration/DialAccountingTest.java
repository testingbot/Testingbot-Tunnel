package com.testingbot.tunnel.integration;

import com.testingbot.tunnel.App;
import com.testingbot.tunnel.HttpProxy;
import com.testingbot.tunnel.TestPorts;
import com.testingbot.tunnel.TunnelMetrics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
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
 * One dial, one count -- whichever route the dial took.
 *
 * <p>The counting used to be keyed on whether {@code --proxy} was set rather than on the path the
 * request actually took, which got three cases wrong: a TCP failure reaching the proxy counted
 * nothing, because the handshake connection that would have counted it only exists once the
 * connect has succeeded; {@code --pac-local} returning PROXY without {@code --proxy} counted
 * twice; and {@code --pac-local} returning DIRECT with {@code --proxy} set counted nothing.
 *
 * <p>The duration histogram matters as much as the counter: a timer started and never observed is
 * not a missing sample, it is a leaked one.
 */
class DialAccountingTest {

    private ServerSocket origin;
    private ServerSocket proxy;
    private ExecutorService pool;
    private HttpProxy tunnel;
    private int tunnelPort;
    private int originPort;
    private int proxyPort;

    @AfterEach
    void tearDown() throws Exception {
        if (tunnel != null) {
            tunnel.stop();
        }
        for (ServerSocket socket : new ServerSocket[]{origin, proxy}) {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        }
        if (pool != null) {
            pool.shutdownNow();
            pool.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private double successes() {
        return TunnelMetrics.DIAL_TOTAL.labels("connect", "success").get();
    }

    private double failures() {
        return TunnelMetrics.DIAL_TOTAL.labels("connect", "failure").get();
    }

    /** How many dial durations have been observed; a started-but-never-stopped timer is a leak. */
    private double observedDurations() {
        // The +Inf bucket is cumulative, so it is the observation count.
        double[] buckets = TunnelMetrics.DIAL_DURATION_SECONDS.labels("connect").get().buckets;
        return buckets[buckets.length - 1];
    }

    /** An origin that accepts and says nothing; a CONNECT to it only has to be granted. */
    private void startOrigin() throws Exception {
        originPort = TestPorts.free();
        origin = new ServerSocket(originPort, 50, InetAddress.getLoopbackAddress());
        pool.submit(() -> {
            while (!origin.isClosed()) {
                try {
                    origin.accept();
                } catch (IOException closed) {
                    return;
                }
            }
        });
    }

    /** An upstream proxy that grants CONNECT and relays. */
    private void startConnectProxy() throws Exception {
        proxyPort = TestPorts.free();
        proxy = new ServerSocket(proxyPort, 50, InetAddress.getLoopbackAddress());
        pool.submit(() -> {
            while (!proxy.isClosed()) {
                Socket accepted;
                try {
                    accepted = proxy.accept();
                } catch (IOException closed) {
                    return;
                }
                pool.submit(() -> {
                    try (Socket client = accepted) {
                        InputStream in = client.getInputStream();
                        StringBuilder head = new StringBuilder();
                        int c;
                        while (head.indexOf("\r\n\r\n") < 0 && (c = in.read()) >= 0) {
                            head.append((char) c);
                        }
                        OutputStream out = client.getOutputStream();
                        out.write("HTTP/1.1 200 Connection established\r\n\r\n"
                                .getBytes(StandardCharsets.UTF_8));
                        out.flush();
                        while (in.read() >= 0) {
                            // hold the tunnel open until the client goes away
                        }
                    } catch (IOException ignored) {
                        // test finished
                    }
                });
            }
        });
    }

    private void startTunnel(String proxySpec, String pacFile) throws Exception {
        tunnelPort = TestPorts.free();
        App app = new App();
        app.setJettyPort(tunnelPort);
        app.setClientKey("test_key");
        app.setClientSecret("test_secret");
        if (proxySpec != null) {
            app.setProxy(proxySpec);
        }
        if (pacFile != null) {
            app.setPacLocal(pacFile);
        }
        tunnel = new HttpProxy(app);
        for (int i = 0; i < 100; i++) {
            try (Socket probe = new Socket("127.0.0.1", tunnelPort)) {
                return;
            } catch (IOException retry) {
                Thread.sleep(50);
            }
        }
        throw new IllegalStateException("proxy did not start");
    }

    /** Sends one CONNECT and returns the status line. */
    private String connect() throws Exception {
        try (Socket socket = new Socket("127.0.0.1", tunnelPort)) {
            socket.setSoTimeout(20_000);
            socket.getOutputStream().write(("CONNECT 127.0.0.1:" + originPort + " HTTP/1.1\r\n"
                    + "Host: 127.0.0.1:" + originPort + "\r\n\r\n")
                    .getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            InputStream in = socket.getInputStream();
            StringBuilder head = new StringBuilder();
            int c;
            while (head.indexOf("\r\n\r\n") < 0 && (c = in.read()) >= 0) {
                head.append((char) c);
            }
            return head.toString();
        }
    }

    private static String pacReturning(String directive, Path dir) throws IOException {
        Path file = dir.resolve("proxy.pac");
        Files.writeString(file, "function FindProxyForURL(url, host) { return \""
                + directive + "\"; }", StandardCharsets.UTF_8);
        return file.toString();
    }

    @Test
    void aTcpFailureReachingTheUpstreamProxyIsCounted() throws Exception {
        pool = Executors.newCachedThreadPool();
        startOrigin();
        // A port with nothing on it: the dial to the proxy itself fails, so the handshake
        // connection -- which used to be the only thing that counted this path -- never exists.
        int dead = TestPorts.free();
        startTunnel("http://127.0.0.1:" + dead, null);

        double failuresBefore = failures();
        double observedBefore = observedDurations();

        assertThat(connect()).doesNotContain("200");

        assertThat(failures() - failuresBefore)
                .as("a dial that never reached the proxy is still a dial").isEqualTo(1.0);
        assertThat(observedDurations() - observedBefore)
                .as("the timer must be stopped, not left running").isEqualTo(1.0);
    }

    @Test
    void aPacProxyWithoutAStaticProxyIsCountedOnce(@TempDir Path dir) throws Exception {
        pool = Executors.newCachedThreadPool();
        startOrigin();
        startConnectProxy();
        // PROXY from PAC, nothing in --proxy: the handshake counted it, and then super.onOpen()
        // reached onConnectSuccess, which saw no static proxy and counted it again.
        startTunnel(null, pacReturning("PROXY 127.0.0.1:" + proxyPort, dir));

        double successesBefore = successes();
        double observedBefore = observedDurations();

        assertThat(connect()).contains("200");

        assertThat(successes() - successesBefore).isEqualTo(1.0);
        assertThat(observedDurations() - observedBefore).isEqualTo(1.0);
    }

    @Test
    void aPacDirectWithAStaticProxyIsCountedOnce(@TempDir Path dir) throws Exception {
        pool = Executors.newCachedThreadPool();
        startOrigin();
        startConnectProxy();
        // DIRECT from PAC with --proxy set: the direct path started a timer, and both guards
        // keyed on the static --proxy declined to stop it or count anything.
        startTunnel("http://127.0.0.1:" + proxyPort, pacReturning("DIRECT", dir));

        double successesBefore = successes();
        double observedBefore = observedDurations();

        assertThat(connect()).contains("200");

        assertThat(successes() - successesBefore).isEqualTo(1.0);
        assertThat(observedDurations() - observedBefore).isEqualTo(1.0);
    }

    @Test
    void aSuccessfulDialThroughTheUpstreamProxyIsStillCountedOnce() throws Exception {
        pool = Executors.newCachedThreadPool();
        startOrigin();
        startConnectProxy();
        startTunnel("http://127.0.0.1:" + proxyPort, null);

        double successesBefore = successes();
        double observedBefore = observedDurations();

        assertThat(connect()).contains("200");

        assertThat(successes() - successesBefore).isEqualTo(1.0);
        assertThat(observedDurations() - observedBefore).isEqualTo(1.0);
    }
}
