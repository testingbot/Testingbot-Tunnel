package com.testingbot.tunnel.integration;

import com.testingbot.tunnel.App;
import com.testingbot.tunnel.HttpProxy;
import com.testingbot.tunnel.proxy.NegotiateHosts;
import com.sun.net.httpserver.HttpServer;
import org.apache.kerby.kerberos.kerb.server.SimpleKdcServer;
import org.ietf.jgss.GSSContext;
import org.ietf.jgss.GSSCredential;
import org.ietf.jgss.GSSManager;
import org.ietf.jgss.GSSName;
import org.ietf.jgss.Oid;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.security.auth.Subject;
import javax.security.auth.login.AppConfigurationEntry;
import javax.security.auth.login.Configuration;
import javax.security.auth.login.LoginContext;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.PrivilegedExceptionAction;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code --krb5-hosts}: SPNEGO sent to an origin, not just to the upstream proxy.
 *
 * <p>The origin here holds its own service keytab and runs {@code acceptSecContext} on what
 * arrives, so a 200 means a genuine ticket for that service and nothing weaker.
 *
 * <p>The assertion that matters most is the negative one. A service ticket names the user, and a
 * host that receives one can prove to the KDC that the user talked to it. Sending that to
 * whatever a test navigates to would be a real leak, so a host not on the list must receive no
 * credential at all -- not a rejected one, none.
 */
class NegotiateOriginTest {

    private static final String REALM = "TESTINGBOT.TEST";
    private static final String ORIGIN_HOST = "localhost";
    private static final String SERVICE_PRINCIPAL = "HTTP/" + ORIGIN_HOST;
    private static final String CLIENT_PRINCIPAL = "tunnel-svc";
    private static final String SPNEGO_OID = "1.3.6.1.5.5.2";

    private static SimpleKdcServer kdc;
    private static Path clientKeyTab;
    private static Path serviceKeyTab;
    private static String previousKrb5Conf;
    private static String previousUseSubjectCredsOnly;

    private HttpServer origin;
    private HttpProxy httpProxy;
    private int proxyPort;
    private int originPort;
    /** One entry per request the origin saw: the Authorization header, or "<none>". */
    private final List<String> authorizations = new CopyOnWriteArrayList<>();
    private final List<String> verifiedClients = new CopyOnWriteArrayList<>();

    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    @BeforeAll
    static void startKdc(@TempDir Path tmp) throws Exception {
        File workDir = tmp.resolve("kdc").toFile();
        assertThat(workDir.mkdirs()).isTrue();

        kdc = new SimpleKdcServer();
        kdc.setKdcRealm(REALM);
        kdc.setKdcHost("127.0.0.1");
        kdc.setKdcTcpPort(freePort());
        kdc.setAllowUdp(false);
        kdc.setWorkDir(workDir);
        kdc.init();
        kdc.start();

        clientKeyTab = tmp.resolve("client.keytab");
        kdc.createAndExportPrincipals(clientKeyTab.toFile(), CLIENT_PRINCIPAL + "@" + REALM);
        serviceKeyTab = tmp.resolve("service.keytab");
        kdc.createAndExportPrincipals(serviceKeyTab.toFile(), SERVICE_PRINCIPAL + "@" + REALM);

        previousKrb5Conf = System.getProperty("java.security.krb5.conf");
        previousUseSubjectCredsOnly =
                System.getProperty("javax.security.auth.useSubjectCredsOnly");
        System.setProperty("java.security.krb5.conf",
                new File(workDir, "krb5.conf").getAbsolutePath());
        System.setProperty("javax.security.auth.useSubjectCredsOnly", "false");
    }

    @AfterAll
    static void stopKdc() throws Exception {
        if (kdc != null) {
            kdc.stop();
        }
        restore("java.security.krb5.conf", previousKrb5Conf);
        restore("javax.security.auth.useSubjectCredsOnly", previousUseSubjectCredsOnly);
    }

    private static void restore(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
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

    /** Logs in as the origin's service principal so it can accept tokens addressed to it. */
    private static Subject serviceSubject() throws Exception {
        Configuration configuration = new Configuration() {
            @Override
            public AppConfigurationEntry[] getAppConfigurationEntry(String name) {
                Map<String, String> options = new HashMap<>();
                options.put("useKeyTab", "true");
                options.put("keyTab", serviceKeyTab.toAbsolutePath().toString());
                options.put("principal", SERVICE_PRINCIPAL + "@" + REALM);
                options.put("storeKey", "true");
                options.put("doNotPrompt", "true");
                options.put("isInitiator", "false");
                options.put("refreshKrb5Config", "true");
                return new AppConfigurationEntry[]{new AppConfigurationEntry(
                        "com.sun.security.auth.module.Krb5LoginModule",
                        AppConfigurationEntry.LoginModuleControlFlag.REQUIRED, options)};
            }
        };
        LoginContext login = new LoginContext("TestingBotOriginAcceptor", null, null, configuration);
        login.login();
        return login.getSubject();
    }

    private static String verify(Subject subject, String base64Token) throws Exception {
        return Subject.doAs(subject, (PrivilegedExceptionAction<String>) () -> {
            GSSManager manager = GSSManager.getInstance();
            Oid spnego = new Oid(SPNEGO_OID);
            GSSName self = manager.createName(SERVICE_PRINCIPAL + "@" + REALM, null);
            GSSCredential credential = manager.createCredential(
                    self, GSSCredential.INDEFINITE_LIFETIME, spnego, GSSCredential.ACCEPT_ONLY);
            GSSContext context = manager.createContext(credential);
            try {
                byte[] token = Base64.getDecoder().decode(base64Token);
                context.acceptSecContext(token, 0, token.length);
                return context.getSrcName() == null ? "<anonymous>" : context.getSrcName().toString();
            } finally {
                context.dispose();
            }
        });
    }

    /** An origin that demands Negotiate and verifies what it is given. */
    private void startOrigin() throws Exception {
        Subject subject = serviceSubject();
        originPort = freePort();
        origin = HttpServer.create(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), originPort), 0);
        origin.createContext("/", exchange -> {
            String header = exchange.getRequestHeaders().getFirst("Authorization");
            authorizations.add(header == null ? "<none>" : header);
            byte[] body;
            int status;
            if (header != null && header.startsWith("Negotiate ")) {
                try {
                    verifiedClients.add(
                            verify(subject, header.substring("Negotiate ".length()).trim()));
                    status = 200;
                    body = "negotiated-ok".getBytes(StandardCharsets.UTF_8);
                } catch (Exception rejected) {
                    status = 401;
                    body = "bad-token".getBytes(StandardCharsets.UTF_8);
                }
            } else {
                status = 401;
                body = "unauthorized".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("WWW-Authenticate", "Negotiate");
            }
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        origin.start();
    }

    private void startTunnel(String krb5Hosts) throws Exception {
        proxyPort = freePort();
        App app = new App();
        app.setJettyPort(proxyPort);
        app.setClientKey("test_key");
        app.setClientSecret("test_secret");
        app.setKrb5KeyTab(clientKeyTab.toAbsolutePath().toString());
        app.setKrb5Principal(CLIENT_PRINCIPAL + "@" + REALM);
        app.setNegotiateHosts(NegotiateHosts.parse(krb5Hosts));
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

    /** A proxied GET written by hand, so the client itself sends no credentials. */
    private String fetch(String host) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", proxyPort)) {
            socket.setSoTimeout(20_000);
            String url = "http://" + host + ":" + originPort + "/";
            socket.getOutputStream().write(("GET " + url + " HTTP/1.1\r\nHost: " + host + ":"
                    + originPort + "\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.UTF_8));
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
    void aListedHostReceivesAGenuineServiceTicket() throws Exception {
        startOrigin();
        startTunnel(ORIGIN_HOST);

        assertThat(fetch(ORIGIN_HOST)).contains("negotiated-ok");
        assertThat(verifiedClients)
                .as("the ticket must name the principal the tunnel logged in as")
                .contains(CLIENT_PRINCIPAL + "@" + REALM);
    }

    @Test
    void anUnlistedHostReceivesNoCredentialAtAll() throws Exception {
        // The security property. Not a rejected credential -- none: a service ticket names the
        // user, and a host that gets one can prove to the KDC that the user talked to it.
        startOrigin();
        startTunnel("some.other.host");

        assertThat(fetch(ORIGIN_HOST)).contains("401");
        assertThat(authorizations)
                .as("nothing should have been offered to a host that was not named")
                .containsExactly("<none>");
        assertThat(verifiedClients).isEmpty();
    }

    @Test
    void withoutTheOptionNothingIsSentAnywhere() throws Exception {
        // Empty by default: configuring Kerberos for the proxy must not start handing tickets
        // to origins as a side effect.
        startOrigin();
        startTunnel(null);

        assertThat(fetch(ORIGIN_HOST)).contains("401");
        assertThat(authorizations).containsExactly("<none>");
    }

    @Test
    void aFreshTokenIsSentOnEachRequest() throws Exception {
        // SPNEGO tokens carry a timestamp and a sequence number; replaying one is what a
        // service is meant to reject, so caching the header would break on the second request.
        startOrigin();
        startTunnel(ORIGIN_HOST);

        assertThat(fetch(ORIGIN_HOST)).contains("negotiated-ok");
        assertThat(fetch(ORIGIN_HOST)).contains("negotiated-ok");

        assertThat(authorizations).hasSize(2);
        assertThat(authorizations.get(0))
                .as("a replayed token would be identical")
                .isNotEqualTo(authorizations.get(1));
    }
}
