package com.testingbot.tunnel;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Authenticator;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The API calls have to travel through {@code --proxy} as well: on a network whose only egress
 * is a proxy, a tunnel that cannot register itself never starts.
 *
 * <p>Api parsed {@code --proxy} by hand with {@code split(":", 2)}, so
 * {@code socks5://host:port} became host "socks5" and port "//host:port" and the tunnel died on
 * a NumberFormatException before doing anything -- a SOCKS5 upstream proxy could not work at
 * all. {@code http://host:port} failed the same way. These tests dial through real proxies
 * rather than asserting on parsed fields, so they fail if the routing is wrong as well as if
 * the parsing is.
 */
class ApiProxyRoutingTest {

    private WireMockServer wireMock;
    private ServerSocket socks;
    private ExecutorService pool;
    private Authenticator previousAuthenticator;
    private final List<String> socksLog = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        wireMock = new WireMockServer(options().dynamicPort());
        wireMock.start();
        WireMock.configureFor("localhost", wireMock.port());
        wireMock.stubFor(post(urlPathEqualTo("/v1/tunnel/create"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"1\",\"state\":\"READY\"}")));
        previousAuthenticator = Authenticator.getDefault();
    }

    @AfterEach
    void tearDown() throws Exception {
        // Api installs a default Authenticator for SOCKS credentials; leaving it set would
        // follow the JVM into other tests.
        Authenticator.setDefault(previousAuthenticator);
        if (wireMock != null) {
            wireMock.stop();
        }
        if (socks != null && !socks.isClosed()) {
            socks.close();
        }
        if (pool != null) {
            pool.shutdownNow();
            pool.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    /**
     * A SOCKS5 server that relays to whatever is asked for, recording the destination and the
     * credentials it was given.
     *
     * @param requireAuth when true, the no-authentication method is refused
     */
    private void startSocks(boolean requireAuth) throws IOException {
        socks = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        pool = Executors.newCachedThreadPool();
        pool.submit(() -> {
            while (!socks.isClosed()) {
                Socket accepted;
                try {
                    accepted = socks.accept();
                } catch (IOException closed) {
                    return;
                }
                pool.submit(() -> relay(accepted, requireAuth));
            }
        });
    }

    private void relay(Socket client, boolean requireAuth) {
        try (Socket socket = client) {
            DataInputStream in = new DataInputStream(socket.getInputStream());
            OutputStream out = socket.getOutputStream();

            in.readUnsignedByte();                                   // version
            byte[] methods = new byte[in.readUnsignedByte()];
            in.readFully(methods);

            if (requireAuth) {
                boolean offered = false;
                for (byte method : methods) {
                    offered |= method == 0x02;
                }
                if (!offered) {
                    socksLog.add("no-userpass-offered");
                    out.write(new byte[]{0x05, (byte) 0xFF});
                    out.flush();
                    return;
                }
                out.write(new byte[]{0x05, 0x02});
                out.flush();
                in.readUnsignedByte();                               // sub-negotiation version
                byte[] user = new byte[in.readUnsignedByte()];
                in.readFully(user);
                byte[] password = new byte[in.readUnsignedByte()];
                in.readFully(password);
                socksLog.add("auth " + new String(user, StandardCharsets.UTF_8)
                        + ":" + new String(password, StandardCharsets.UTF_8));
                out.write(new byte[]{0x01, 0x00});
                out.flush();
            } else {
                out.write(new byte[]{0x05, 0x00});
                out.flush();
            }

            in.readUnsignedByte();                                   // version
            in.readUnsignedByte();                                   // command
            in.readUnsignedByte();                                   // reserved
            int atyp = in.readUnsignedByte();
            String host;
            if (atyp == 0x01) {
                byte[] address = new byte[4];
                in.readFully(address);
                host = InetAddress.getByAddress(address).getHostAddress();
            } else {
                byte[] name = new byte[in.readUnsignedByte()];
                in.readFully(name);
                host = new String(name, StandardCharsets.US_ASCII);
            }
            int port = in.readUnsignedShort();
            socksLog.add("connect " + host + ":" + port);

            try (Socket upstream = new Socket(host, port)) {
                out.write(new byte[]{0x05, 0x00, 0x00, 0x01, 127, 0, 0, 1, 0, 0});
                out.flush();
                InputStream fromClient = socket.getInputStream();
                OutputStream toUpstream = upstream.getOutputStream();
                pool.submit(() -> copy(fromClient, toUpstream));
                copy(upstream.getInputStream(), out);
            }
        } catch (Exception ignored) {
            // test finished
        }
    }

    private static void copy(InputStream from, OutputStream to) {
        try {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = from.read(buffer)) > 0) {
                to.write(buffer, 0, n);
                to.flush();
            }
        } catch (IOException ignored) {
            // connection closed
        }
    }

    private Api apiThroughProxy(String proxy, String credentials) {
        App app = new App();
        app.setClientKey("test_key");
        app.setClientSecret("test_secret");
        app.setProxy(proxy);
        if (credentials != null) {
            app.setProxyAuth(credentials);
        }
        Api api = new Api(app);
        api.setApiScheme("http");
        api.setApiHost("localhost:" + wireMock.port());
        return api;
    }

    @Test
    void aSocks5ProxyCarriesTheApiCall() throws Exception {
        startSocks(false);

        Api api = apiThroughProxy("socks5://127.0.0.1:" + socks.getLocalPort(), null);
        assertThatCode(api::createTunnel)
                .as("a socks5 proxy used to kill the tunnel before it started")
                .doesNotThrowAnyException();

        assertThat(socksLog).anyMatch(line -> line.startsWith("connect "));
        wireMock.verify(1, WireMock.postRequestedFor(urlPathEqualTo("/v1/tunnel/create")));
    }

    @Test
    void credentialsAreNegotiatedWithAnAuthenticatingSocks5Proxy() throws Exception {
        startSocks(true);

        Api api = apiThroughProxy("socks5://127.0.0.1:" + socks.getLocalPort(), "alice:s3cret");
        assertThatCode(api::createTunnel).doesNotThrowAnyException();

        assertThat(socksLog).contains("auth alice:s3cret");
        assertThat(socksLog).doesNotContain("no-userpass-offered");
        wireMock.verify(1, WireMock.postRequestedFor(urlPathEqualTo("/v1/tunnel/create")));
    }

    @Test
    void anHttpProxyGivenWithItsSchemeIsParsedAsAProxy() throws Exception {
        // "http://host:port" split on the first colon into host "http", and Integer.parseInt of
        // the rest threw. The failure looked like a broken proxy rather than a parse error.
        App app = new App();
        app.setClientKey("test_key");
        app.setClientSecret("test_secret");
        app.setProxy("http://127.0.0.1:" + wireMock.port());
        Api api = new Api(app);
        api.setApiScheme("http");
        api.setApiHost("localhost:" + wireMock.port());

        // WireMock answers absolute-URI requests from the same stub, so a request that arrives
        // proves the proxy host and port were understood.
        assertThatCode(api::createTunnel).doesNotThrowAnyException();
        wireMock.verify(1, WireMock.postRequestedFor(urlPathEqualTo("/v1/tunnel/create")));
    }

    @Test
    void theSocksAuthenticatorAnswersOnlyItsOwnProxy() throws Exception {
        // The JDK asks for SOCKS credentials through the global Authenticator, so the answer is
        // narrowed to this proxy: nothing else in the process should be handed them.
        startSocks(true);
        Api api = apiThroughProxy("socks5://127.0.0.1:" + socks.getLocalPort(), "alice:s3cret");
        api.createTunnel();

        int port = socks.getLocalPort();
        assertThat(Authenticator.requestPasswordAuthentication(
                "127.0.0.1", null, port, "SOCKS5", "", null))
                .as("its own proxy is answered")
                .isNotNull();
        assertThat(Authenticator.requestPasswordAuthentication(
                "some.other.host", null, port, "SOCKS5", "", null))
                .as("another host must not receive these credentials")
                .isNull();
        assertThat(Authenticator.requestPasswordAuthentication(
                "127.0.0.1", null, port + 1, "SOCKS5", "", null))
                .as("another port must not receive these credentials")
                .isNull();
        assertThat(Authenticator.requestPasswordAuthentication(
                "127.0.0.1", null, port, "http", "", null))
                .as("an ordinary HTTP challenge is not a SOCKS handshake")
                .isNull();
    }

    @Test
    void aPreviouslyInstalledAuthenticatorKeepsWorking() throws Exception {
        // setDefault is JVM-wide. When the tunnel is embedded rather than run as a CLI, simply
        // replacing whatever the host application installed would silently break its
        // authentication -- so anything this one does not recognise is handed back.
        java.util.List<String> askedOfHost = new java.util.ArrayList<>();
        Authenticator host = new Authenticator() {
            @Override
            protected java.net.PasswordAuthentication getPasswordAuthentication() {
                askedOfHost.add(getRequestingHost() + ":" + getRequestingPort());
                return new java.net.PasswordAuthentication("host-user", "host-pass".toCharArray());
            }
        };
        Authenticator.setDefault(host);

        startSocks(true);
        Api api = apiThroughProxy("socks5://127.0.0.1:" + socks.getLocalPort(), "alice:s3cret");
        api.createTunnel();

        // Ours still answers for its own proxy.
        assertThat(socksLog).contains("auth alice:s3cret");

        // And the host's authenticator is still reachable for everything else.
        java.net.PasswordAuthentication other = Authenticator.requestPasswordAuthentication(
                "some.other.host", null, 8080, "http", "", null, null,
                Authenticator.RequestorType.SERVER);
        assertThat(other).as("the host application's authenticator must still be consulted")
                .isNotNull();
        assertThat(other.getUserName()).isEqualTo("host-user");
        assertThat(askedOfHost).contains("some.other.host:8080");
        assertThat(new String(other.getPassword()))
                .as("and it must be the host's answer, not this tunnel's proxy password")
                .isEqualTo("host-pass");
    }
}
