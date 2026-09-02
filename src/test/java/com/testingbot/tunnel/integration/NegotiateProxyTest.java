package com.testingbot.tunnel.integration;

import com.testingbot.tunnel.TestPorts;
import com.testingbot.tunnel.App;
import com.testingbot.tunnel.HttpProxy;
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
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.PrivilegedExceptionAction;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code --proxy-auth-scheme negotiate} against a proxy that actually verifies the ticket.
 *
 * <p>SpnegoKdcTest proves a token can be obtained; ProxyAuthSchemeConnectTest proves what goes on
 * the wire when no credentials exist. Neither shows a request completing because the proxy was
 * satisfied -- so a scheme that produced a perfectly good token nothing ever accepted would
 * have looked fully covered.
 *
 * <p>Here the upstream proxy holds the service principal's keytab and runs
 * {@code GSSContext.acceptSecContext} on what it receives. It answers 200 only for a token it
 * could verify, and it records which client principal that token names. Both egress paths a
 * browser uses are driven through it: CONNECT and plain HTTP.
 */
class NegotiateProxyTest {

    private static final String REALM = "TESTINGBOT.TEST";
    // "localhost" rather than a made-up name: the proxy has to be connectable, and the SPN the
    // tunnel derives is HTTP/<proxy-host>, so the two have to be the same string.
    private static final String PROXY_HOST = "localhost";
    private static final String SERVICE_PRINCIPAL = "HTTP/" + PROXY_HOST;
    private static final String CLIENT_PRINCIPAL = "tunnel-svc";
    private static final String SPNEGO_OID = "1.3.6.1.5.5.2";

    private static SimpleKdcServer kdc;
    private static Path clientKeyTab;
    private static Path serviceKeyTab;
    private static String previousKrb5Conf;
    private static String previousUseSubjectCredsOnly;
    private static String previousLoginConfig;

    private ServerSocket upstream;
    private ExecutorService pool;
    private HttpProxy httpProxy;
    private int proxyPort;
    /** One entry per token the proxy verified: the client principal it named. */
    private final List<String> verifiedClients = new CopyOnWriteArrayList<>();
    private final List<String> requestLines = new CopyOnWriteArrayList<>();

    private static int freePort() throws IOException {
        return TestPorts.free();
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
        previousLoginConfig = System.getProperty("java.security.auth.login.config");
        System.setProperty("java.security.krb5.conf",
                new File(workDir, "krb5.conf").getAbsolutePath());
        // JGSS otherwise insists on credentials already in the Subject; both sides here log in
        // for themselves.
        System.setProperty("javax.security.auth.useSubjectCredsOnly", "false");
    }

    @AfterAll
    static void stopKdc() throws Exception {
        if (kdc != null) {
            kdc.stop();
        }
        restore("java.security.krb5.conf", previousKrb5Conf);
        restore("javax.security.auth.useSubjectCredsOnly", previousUseSubjectCredsOnly);
        restore("java.security.auth.login.config", previousLoginConfig);
    }

    private static void restore(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
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

    /* ----------------------------------------------------------- the verifying proxy */

    /** Logs in as the service principal so it can accept tokens addressed to it. */
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
        LoginContext login = new LoginContext("TestingBotTunnelAcceptor", null, null, configuration);
        login.login();
        return login.getSubject();
    }

    /**
     * Verifies one Negotiate token and returns the client principal it names.
     *
     * @throws Exception if the token is not a valid ticket for this service
     */
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

    /**
     * An upstream proxy that demands Negotiate and means it: 407 without a token, 407 with one
     * it cannot verify, and only then 200.
     */
    private void startUpstream() throws Exception {
        Subject subject = serviceSubject();
        upstream = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        pool = Executors.newCachedThreadPool();
        pool.submit(() -> {
            while (!upstream.isClosed()) {
                Socket accepted;
                try {
                    accepted = upstream.accept();
                } catch (IOException closed) {
                    return;
                }
                pool.submit(() -> serve(accepted, subject));
            }
        });
    }

    private void serve(Socket socket, Subject subject) {
        try (Socket client = socket) {
            client.setSoTimeout(30_000);
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
            OutputStream out = client.getOutputStream();

            String requestLine = in.readLine();
            if (requestLine == null) {
                return;
            }
            requestLines.add(requestLine);
            List<String> headers = new ArrayList<>();
            String line;
            while ((line = in.readLine()) != null && !line.isEmpty()) {
                headers.add(line);
            }

            String token = null;
            for (String header : headers) {
                if (header.toLowerCase().startsWith("proxy-authorization: negotiate ")) {
                    token = header.substring("Proxy-Authorization: Negotiate ".length()).trim();
                }
            }

            if (token == null) {
                challenge(out);
                return;
            }
            try {
                verifiedClients.add(verify(subject, token));
            } catch (Exception rejected) {
                challenge(out);
                return;
            }

            if (requestLine.startsWith("CONNECT ")) {
                out.write("HTTP/1.1 200 Connection Established\r\n\r\n"
                        .getBytes(StandardCharsets.US_ASCII));
                out.flush();
                Thread.sleep(20_000);           // hold the tunnel open for the test's read
            } else {
                byte[] body = "negotiated-ok".getBytes(StandardCharsets.UTF_8);
                out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: "
                        + body.length + "\r\nConnection: close\r\n\r\n")
                        .getBytes(StandardCharsets.US_ASCII));
                out.write(body);
                out.flush();
            }
        } catch (Exception ignored) {
            // test finished
        }
    }

    private static void challenge(OutputStream out) throws IOException {
        out.write(("HTTP/1.1 407 Proxy Authentication Required\r\n"
                + "Proxy-Authenticate: Negotiate\r\n"
                + "Content-Length: 0\r\nConnection: close\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    /* ------------------------------------------------------------------- the tunnel */

    private void startTunnel() throws Exception {
        proxyPort = freePort();
        App app = new App();
        app.setJettyPort(proxyPort);
        app.setClientKey("test_key");
        app.setClientSecret("test_secret");
        app.setProxy(PROXY_HOST + ":" + upstream.getLocalPort());
        app.setProxyAuthScheme("negotiate");
        app.setKrb5KeyTab(clientKeyTab.toAbsolutePath().toString());
        app.setKrb5Principal(CLIENT_PRINCIPAL + "@" + REALM);
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

    /** Reads a response's status line, byte at a time so nothing after it is consumed. */
    private static String statusLine(InputStream in) throws IOException {
        StringBuilder head = new StringBuilder();
        while (head.indexOf("\r\n\r\n") < 0) {
            int b = in.read();
            if (b < 0) {
                break;
            }
            head.append((char) b);
        }
        return head.length() == 0 ? "" : head.toString().split("\r\n")[0];
    }

    /* --------------------------------------------------------------------- the tests */

    @Test
    void theConnectPathIsAcceptedByTheProxy() throws Exception {
        startUpstream();
        startTunnel();

        try (Socket socket = new Socket("127.0.0.1", proxyPort)) {
            socket.setSoTimeout(30_000);
            socket.getOutputStream().write(("CONNECT example.com:443 HTTP/1.1\r\n"
                    + "Host: example.com:443\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();

            assertThat(statusLine(socket.getInputStream()))
                    .as("the proxy verified the ticket, so the tunnel should be open")
                    .contains("200");
        }

        assertThat(verifiedClients)
                .as("the token must name the principal the tunnel logged in as")
                .contains(CLIENT_PRINCIPAL + "@" + REALM);
        // Pre-emptive on this path: no 407 should have been needed.
        assertThat(requestLines).hasSize(1);
    }

    @Test
    void thePlainHttpPathIsAcceptedByTheProxy() throws Exception {
        startUpstream();
        startTunnel();

        try (Socket socket = new Socket("127.0.0.1", proxyPort)) {
            socket.setSoTimeout(30_000);
            socket.getOutputStream().write(("GET http://example.com/ HTTP/1.1\r\n"
                    + "Host: example.com\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();

            StringBuilder response = new StringBuilder();
            byte[] buffer = new byte[4096];
            int n;
            while ((n = socket.getInputStream().read(buffer)) > 0) {
                response.append(new String(buffer, 0, n, StandardCharsets.UTF_8));
            }

            assertThat(response.toString())
                    .as("the proxy answers 200 only for a token it could verify")
                    .contains("negotiated-ok");
        }

        assertThat(verifiedClients).contains(CLIENT_PRINCIPAL + "@" + REALM);
    }

    @Test
    void aTokenForTheWrongServiceIsRefused() throws Exception {
        // The SPN is the single most common thing to get wrong, and getting it wrong fails as
        // an indistinguishable 407. This proves the proxy here would actually notice.
        startUpstream();
        proxyPort = freePort();
        App app = new App();
        app.setJettyPort(proxyPort);
        app.setClientKey("test_key");
        app.setClientSecret("test_secret");
        app.setProxy(PROXY_HOST + ":" + upstream.getLocalPort());
        app.setProxyAuthScheme("negotiate");
        app.setProxySpn("HTTP/" + CLIENT_PRINCIPAL);      // a principal that is not the proxy
        app.setKrb5KeyTab(clientKeyTab.toAbsolutePath().toString());
        app.setKrb5Principal(CLIENT_PRINCIPAL + "@" + REALM);
        httpProxy = new HttpProxy(app);
        for (int i = 0; i < 100; i++) {
            try (Socket s = new Socket("127.0.0.1", proxyPort)) {
                break;
            } catch (IOException retry) {
                Thread.sleep(50);
            }
        }

        try (Socket socket = new Socket("127.0.0.1", proxyPort)) {
            socket.setSoTimeout(30_000);
            socket.getOutputStream().write(("CONNECT example.com:443 HTTP/1.1\r\n"
                    + "Host: example.com:443\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            assertThat(statusLine(socket.getInputStream())).doesNotContain("200");
        }

        assertThat(verifiedClients)
                .as("a ticket for another service must not verify")
                .isEmpty();
    }
}
