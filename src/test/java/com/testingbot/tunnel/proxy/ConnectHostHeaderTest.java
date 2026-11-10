package com.testingbot.tunnel.proxy;

import com.testingbot.tunnel.App;
import com.testingbot.tunnel.HttpProxy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CFNetwork (iOS and macOS: Safari, URLSession, every app) sends
 * {@code CONNECT host:443} with {@code Host: host} -- no port. Jetty 12 compares the two and
 * answers 400 "Authority!=Host" unless MISMATCHED_AUTHORITY is allowed, so with 5.0/5.1 no
 * HTTPS from an iPhone, an iOS simulator or macOS Safari got through a --nobump tunnel.
 * OkHttp, Chrome and curl send {@code Host: host:443}, which is why Android kept working.
 */
class ConnectHostHeaderTest {

    private App app;
    private HttpProxy httpProxy;
    private ServerSocket origin;

    @BeforeEach
    void setUp() throws Exception {
        origin = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress());
        app = new App();
        app.setJettyPort(freePort());
        app.setClientKey("test_key");
        app.setClientSecret("test_secret");
        httpProxy = new HttpProxy(app);
        httpProxy.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        httpProxy.stop();
        origin.close();
    }

    @Test
    void connect_withPortlessHostHeader_isRelayed() throws Exception {
        String response = connect("Host: 127.0.0.1\r\n");
        assertThat(response).startsWith("HTTP/1.1 200");
    }

    @Test
    void connect_withHostHeaderCarryingThePort_isRelayed() throws Exception {
        String response = connect("Host: 127.0.0.1:" + origin.getLocalPort() + "\r\n");
        assertThat(response).startsWith("HTTP/1.1 200");
    }

    private String connect(String hostHeader) throws Exception {
        try (Socket s = new Socket("127.0.0.1", app.getJettyPort())) {
            s.setSoTimeout(5000);
            OutputStream out = s.getOutputStream();
            out.write(("CONNECT 127.0.0.1:" + origin.getLocalPort() + " HTTP/1.1\r\n"
                    + hostHeader
                    + "Proxy-Connection: keep-alive\r\n"
                    + "Connection: keep-alive\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            InputStream in = s.getInputStream();
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] b = new byte[4096];
            int n;
            try {
                while ((n = in.read(b)) > 0) {
                    buf.write(b, 0, n);
                    if (buf.toString(StandardCharsets.ISO_8859_1).contains("\r\n\r\n")) {
                        break;
                    }
                }
            } catch (java.net.SocketTimeoutException ignored) {
                // a 200 has no body; whatever arrived is the answer
            }
            String response = buf.toString(StandardCharsets.ISO_8859_1);
            System.out.println("[" + hostHeader.trim() + "] -> " + response.split("\r\n\r\n")[0].replace("\r\n", " | "));
            return response;
        }
    }

    private static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }
}
