package com.testingbot.tunnel.integration;

import com.testingbot.tunnel.App;
import com.testingbot.tunnel.HttpProxy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.xbill.DNS.ARecord;
import org.xbill.DNS.DClass;
import org.xbill.DNS.Flags;
import org.xbill.DNS.Message;
import org.xbill.DNS.Record;
import org.xbill.DNS.Section;
import org.xbill.DNS.Type;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves --dns actually reaches the proxy's dial path, not just the resolver class.
 *
 * <p>The test resolves a name the platform resolver cannot possibly know
 * ({@code tunnel-dns-test.invalid}) to loopback, then asks the proxy to fetch it. A response
 * can only arrive if the custom server was consulted, so this fails if the wiring is absent —
 * which is exactly how the option was broken for years while looking configured.
 */
class CustomDnsProxyTest {

    private DatagramSocket dnsServer;
    private Thread dnsThread;
    private ServerSocket origin;
    private Thread originThread;
    private HttpProxy httpProxy;
    private int proxyPort;
    private int originPort;
    private final AtomicInteger queriesSeen = new AtomicInteger();

    private static int findFreePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        origin = new ServerSocket(0, 10, InetAddress.getLoopbackAddress());
        originPort = origin.getLocalPort();
        originThread = new Thread(() -> {
            while (!origin.isClosed()) {
                try (Socket socket = origin.accept()) {
                    BufferedReader in = new BufferedReader(
                            new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                    in.readLine();
                    String line;
                    while ((line = in.readLine()) != null && !line.isEmpty()) {
                        // drain
                    }
                    byte[] body = "RESOLVED-VIA-CUSTOM-DNS".getBytes(StandardCharsets.UTF_8);
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

        dnsServer = new DatagramSocket(0, InetAddress.getLoopbackAddress());
        dnsThread = new Thread(() -> {
            byte[] buf = new byte[512];
            while (!dnsServer.isClosed()) {
                try {
                    DatagramPacket request = new DatagramPacket(buf, buf.length);
                    dnsServer.receive(request);
                    queriesSeen.incrementAndGet();

                    Message query = new Message(Arrays.copyOf(request.getData(), request.getLength()));
                    Record question = query.getQuestion();
                    Message response = new Message(query.getHeader().getID());
                    response.getHeader().setFlag(Flags.QR);
                    response.getHeader().setFlag(Flags.AA);
                    response.addRecord(question, Section.QUESTION);
                    if (question.getType() == Type.A) {
                        response.addRecord(new ARecord(question.getName(), DClass.IN, 60,
                                InetAddress.getByName("127.0.0.1")), Section.ANSWER);
                    }
                    byte[] wire = response.toWire();
                    dnsServer.send(new DatagramPacket(wire, wire.length,
                            request.getAddress(), request.getPort()));
                } catch (Exception e) {
                    return;
                }
            }
        });
        dnsThread.setDaemon(true);
        dnsThread.start();

        proxyPort = findFreePort();
        App app = new App();
        app.setJettyPort(proxyPort);
        app.setClientKey("test_key");
        app.setClientSecret("test_secret");
        app.setDnsServer("127.0.0.1:" + dnsServer.getLocalPort());
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
        if (origin != null && !origin.isClosed()) {
            origin.close();
        }
        if (dnsThread != null) {
            dnsThread.interrupt();
        }
        if (dnsServer != null && !dnsServer.isClosed()) {
            dnsServer.close();
        }
    }

    @Test
    void httpProxying_resolvesThroughTheConfiguredDnsServer() throws Exception {
        // .invalid is reserved by RFC 2606 and never resolves publicly, so success here can
        // only come from our own DNS server being consulted.
        try (Socket socket = new Socket("127.0.0.1", proxyPort)) {
            socket.setSoTimeout(15_000);
            String request = "GET http://tunnel-dns-test.invalid:" + originPort + "/ HTTP/1.1\r\n"
                    + "Host: tunnel-dns-test.invalid:" + originPort + "\r\n"
                    + "Connection: close\r\n\r\n";
            socket.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line).append('\n');
            }

            assertThat(response.toString()).contains("200 OK");
            assertThat(response.toString()).contains("RESOLVED-VIA-CUSTOM-DNS");
        }
        assertThat(queriesSeen.get())
                .as("the configured DNS server should have been queried")
                .isGreaterThan(0);
    }

    @Test
    void connectTunnelling_resolvesThroughTheConfiguredDnsServer() throws Exception {
        // CONNECT uses a different dial path (ConnectHandler.newConnectAddress), so it needs
        // its own coverage; wiring one and not the other is an easy mistake.
        try (Socket socket = new Socket("127.0.0.1", proxyPort)) {
            socket.setSoTimeout(15_000);
            socket.getOutputStream().write(("CONNECT tunnel-dns-test.invalid:" + originPort
                    + " HTTP/1.1\r\nHost: tunnel-dns-test.invalid:" + originPort
                    + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            assertThat(reader.readLine()).contains(" 200");
        }
        assertThat(queriesSeen.get()).isGreaterThan(0);
    }
}
