package com.testingbot.tunnel.proxy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import java.util.Base64;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import javax.security.auth.Subject;
import javax.security.auth.login.AppConfigurationEntry;
import javax.security.auth.login.Configuration;
import javax.security.auth.login.LoginContext;
import javax.security.auth.login.LoginException;
import org.ietf.jgss.GSSContext;
import org.ietf.jgss.GSSException;
import org.ietf.jgss.GSSManager;
import org.ietf.jgss.GSSName;
import org.ietf.jgss.Oid;

/**
 * Produces {@code Negotiate} tokens for authenticating to an upstream corporate proxy.
 *
 * <p>Everything needed is in the JDK: JGSS speaks SPNEGO and Krb5LoginModule can read either a
 * keytab or the ambient ticket cache, so no third-party Kerberos stack is involved.
 *
 * <p>Credentials come from one of two places. With a keytab and principal configured, this logs
 * in explicitly -- the only option for CI and containers, where nobody has run {@code kinit}.
 * Otherwise it uses the ticket cache the user already has, which is what a developer on a
 * domain-joined machine expects.
 *
 * <p>The service principal defaults to {@code HTTP/<proxy-host>}, which is what proxies
 * register. Guessing it wrong is one of the most common ways Negotiate fails, so it can be
 * overridden and {@code --doctor} reports which one would be used.
 */
public class SpnegoClient {

    /** SPNEGO, RFC 4178. Proxies advertise this rather than raw Kerberos. */
    private static final String SPNEGO_OID = "1.3.6.1.5.5.2";

    private static final String LOGIN_CONTEXT_NAME = "TestingBotTunnelKerberos";

    private final String servicePrincipal;
    private final Path keyTab;
    private final String principal;

    /**
     * @param servicePrincipal SPN in {@code HTTP/host} or {@code HTTP@host} form; when null it is
     *                         derived from the proxy host
     * @param keyTab           keytab to log in with, or null to use the ambient ticket cache
     * @param principal        principal to log in as; required when {@code keyTab} is set
     */
    public SpnegoClient(String servicePrincipal, Path keyTab, String principal) {
        this.servicePrincipal = servicePrincipal;
        this.keyTab = keyTab;
        this.principal = principal;
    }

    /** The SPN this client will request for {@code proxyHost}. */
    public String servicePrincipalFor(String proxyHost) {
        if (servicePrincipal != null && !servicePrincipal.isBlank()) {
            return normalise(servicePrincipal.trim());
        }
        return "HTTP@" + (proxyHost == null ? "" : proxyHost.toLowerCase(Locale.ROOT));
    }

    /**
     * JGSS wants {@code service@host} for a host-based name, but everyone writes SPNs as
     * {@code service/host}. Accept either.
     */
    static String normalise(String spn) {
        int slash = spn.indexOf('/');
        return slash > 0 ? spn.substring(0, slash) + "@" + spn.substring(slash + 1) : spn;
    }

    /**
     * @return the base64 token for a {@code Proxy-Authorization: Negotiate} header
     * @throws GSSException if no usable credentials exist or the KDC refuses the service ticket
     */
    public String initialToken(String proxyHost) throws GSSException {
        String spn = servicePrincipalFor(proxyHost);
        if (keyTab == null) {
            return Base64.getEncoder().encodeToString(token(spn));
        }
        try {
            Subject subject = loginWithKeyTab();
            return Subject.doAs(subject, (PrivilegedExceptionAction<String>) () ->
                    Base64.getEncoder().encodeToString(token(spn)));
        } catch (PrivilegedActionException ex) {
            if (ex.getCause() instanceof GSSException gss) {
                throw gss;
            }
            throw new IllegalStateException(
                    "Kerberos login with keytab " + keyTab + " failed: " + ex.getCause(), ex);
        } catch (LoginException ex) {
            throw new IllegalStateException(
                    "Kerberos login with keytab " + keyTab + " failed: " + ex.getMessage(), ex);
        }
    }

    private byte[] token(String spn) throws GSSException {
        GSSManager manager = GSSManager.getInstance();
        Oid spnego = new Oid(SPNEGO_OID);
        GSSName service = manager.createName(spn, GSSName.NT_HOSTBASED_SERVICE);
        GSSContext context = manager.createContext(service, spnego, null, GSSContext.DEFAULT_LIFETIME);
        try {
            // No mutual auth: we only need to prove who we are to the proxy, and requiring the
            // proxy to prove itself back makes more deployments fail than it protects.
            context.requestMutualAuth(false);
            // No delegation: the proxy has no business acting as the user elsewhere.
            context.requestCredDeleg(false);
            return context.initSecContext(new byte[0], 0, 0);
        } finally {
            try {
                context.dispose();
            } catch (GSSException ignored) {
                // nothing useful to do while cleaning up
            }
        }
    }

    private Subject loginWithKeyTab() throws LoginException {
        if (principal == null || principal.isBlank()) {
            throw new LoginException("A keytab was configured without a principal to use it with");
        }
        if (!Files.isReadable(keyTab)) {
            throw new LoginException("Keytab is not readable: " + keyTab);
        }
        LoginContext login = new LoginContext(LOGIN_CONTEXT_NAME, null, null,
                new KeyTabConfiguration(keyTab, principal));
        login.login();
        return login.getSubject();
    }

    /** A Krb5LoginModule configured for unattended keytab login, built in code so no external
     * JAAS file is needed. */
    private static final class KeyTabConfiguration extends Configuration {
        private final Path keyTab;
        private final String principal;

        private KeyTabConfiguration(Path keyTab, String principal) {
            this.keyTab = keyTab;
            this.principal = principal;
        }

        @Override
        public AppConfigurationEntry[] getAppConfigurationEntry(String name) {
            Map<String, Object> options = new HashMap<>();
            options.put("keyTab", keyTab.toAbsolutePath().toString());
            options.put("principal", principal);
            options.put("useKeyTab", "true");
            options.put("storeKey", "true");
            options.put("doNotPrompt", "true");
            options.put("isInitiator", "true");
            options.put("refreshKrb5Config", "true");
            return new AppConfigurationEntry[]{new AppConfigurationEntry(
                    "com.sun.security.auth.module.Krb5LoginModule",
                    AppConfigurationEntry.LoginModuleControlFlag.REQUIRED,
                    options)};
        }
    }
}
