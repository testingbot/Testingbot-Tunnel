package com.testingbot.tunnel.integration;

import com.testingbot.tunnel.App;
import com.testingbot.tunnel.HttpProxy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A large response that takes a long time but never stalls must not be cut off.
 *
 * <p>The distinction that matters: {@code Request.timeout()} in jetty-client bounds the TOTAL
 * request/response conversation, while the idle timeout bounds a gap between bytes. Using the
 * former meant a healthy multi-hundred-megabyte download was aborted purely for taking longer
 * than the configured window -- and a tunnel carries whatever the site under test serves.
 *
 * <p>Rather than wait out the real 120 second window, the idle timeout is turned down and the
 * origin trickles a body for several times that long. With a total timeout the exchange dies; with
 * an idle timeout it completes, because bytes keep arriving.
 */
class SlowLargeResponseTest {

    /** Short enough for a fast test, long enough that the trickle interval stays well inside it. */
    private static final long IDLE_TIMEOUT_MS = 1_000;

    private static final int CHUNKS = 15;
    private static final long CHUNK_INTERVAL_MS = 200;
    private static final String CHUNK = "0123456789";

    private ServerSocket origin;
    private ExecutorService pool;
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
        pool = Executors.newCachedThreadPool();

        // Sends a body slowly but steadily: never idle for long, but well past any total cap.
        acceptor = new Thread(() -> {
            while (!origin.isClosed()) {
                try {
                    Socket accepted = origin.accept();
                    pool.submit(() -> {
                        try (Socket socket = accepted) {
                            BufferedReader in = new BufferedReader(new InputStreamReader(
                                    socket.getInputStream(), StandardCharsets.UTF_8));
                            in.readLine();
                            String line;
                            while ((line = in.readLine()) != null && !line.isEmpty()) {
                                // drain headers
                            }
                            OutputStream out = socket.getOutputStream();
                            out.write(("HTTP/1.1 200 OK\r\nContent-Length: "
                                    + (CHUNKS * CHUNK.length())
                                    + "\r\nConnection: close\r\n\r\n")
                                    .getBytes(StandardCharsets.UTF_8));
                            out.flush();
                            for (int i = 0; i < CHUNKS; i++) {
                                Thread.sleep(CHUNK_INTERVAL_MS);
                                out.write(CHUNK.getBytes(StandardCharsets.UTF_8));
                                out.flush();
                            }
                        } catch (Exception ignored) {
                            // client went away
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
        turnDownIdleTimeout();
        waitForPort(proxyPort);
    }

    /**
     * Sets the proxy handler's timeout to something a test can wait out. It feeds the client's
     * idle timeout; before the fix it also became the total exchange timeout.
     */
    private void turnDownIdleTimeout() throws Exception {
        Field field = HttpProxy.class.getDeclaredField("httpProxy");
        field.setAccessible(true);
        org.eclipse.jetty.server.Server server = (org.eclipse.jetty.server.Server) field.get(httpProxy);
        for (com.testingbot.tunnel.proxy.TunnelProxyHandler handler
                : server.getContainedBeans(com.testingbot.tunnel.proxy.TunnelProxyHandler.class)) {
            handler.stop();
            handler.setIdleTimeoutMs(IDLE_TIMEOUT_MS);
            handler.start();
        }
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
        if (pool != null) {
            pool.shutdownNow();
            pool.awaitTermination(5, TimeUnit.SECONDS);
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

    @Test
    void aSteadyTransferLongerThanTheTimeoutWindowCompletes() throws Exception {
        long transferMs = CHUNKS * CHUNK_INTERVAL_MS;
        assertThat(transferMs)
                .as("the transfer must outlast the window to be a meaningful test")
                .isGreaterThan(IDLE_TIMEOUT_MS * 2);

        try (Socket socket = new Socket("127.0.0.1", proxyPort)) {
            socket.setSoTimeout(30_000);
            socket.getOutputStream().write(("GET http://127.0.0.1:" + originPort + "/big HTTP/1.1\r\n"
                    + "Host: 127.0.0.1:" + originPort + "\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();

            String response = new String(socket.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);

            assertThat(response).contains("200 OK");
            // The whole body, not a truncated one: a total timeout aborts mid-stream.
            assertThat(response).endsWith(CHUNK.repeat(CHUNKS));
        }
    }
}
