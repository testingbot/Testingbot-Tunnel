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

    /** Re-login this long before the ticket actually expires, to absorb clock skew. */
    private static final long EXPIRY_SKEW_MS = 60_000;

    /** Used when the ticket carries no end time, which should not happen but must not hang. */
    private static final long FALLBACK_LIFETIME_MS = 10 * 60_000;

    private final String servicePrincipal;
    private final Path keyTab;
    private final String principal;

    private LoginContext loginContext;
    private Subject subject;
    private long subjectExpiresAtMs;
    private int loginCount;

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
            // The login is the expensive half -- reading the keytab and an AS-REQ to the KDC --
            // and it was being done on the request path for every single CONNECT. The ticket it
            // yields is valid for hours, so it is kept and reused; only the service ticket is
            // obtained per call, and the SPNEGO token itself must stay fresh for replay
            // protection.
            return Subject.doAs(cachedSubject(), (PrivilegedExceptionAction<String>) () ->
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

    /** The logged-in Subject, refreshed only once its Kerberos ticket is close to expiring. */
    private synchronized Subject cachedSubject() throws LoginException {
        long now = System.currentTimeMillis();
        if (subject != null && now < subjectExpiresAtMs) {
            return subject;
        }
        logoutQuietly();
        loginContext = loginWithKeyTab();
        loginCount++;
        subject = loginContext.getSubject();
        subjectExpiresAtMs = expiryOf(subject, now);
        return subject;
    }

    /**
     * Releases the previous login.
     *
     * <p>Krb5LoginModule holds the ticket and, with storeKey, the secret key in the Subject until
     * logout. Logging in per request without ever logging out accumulated those for the life of
     * the process.
     */
    private void logoutQuietly() {
        if (loginContext == null) {
            return;
        }
        try {
            loginContext.logout();
        } catch (LoginException ignored) {
            // Nothing useful to do; the replacement login is what matters.
        } finally {
            loginContext = null;
            subject = null;
        }
    }

    /** The earliest ticket expiry in the Subject, less a skew allowance. */
    private static long expiryOf(Subject subject, long now) {
        long earliest = Long.MAX_VALUE;
        for (javax.security.auth.kerberos.KerberosTicket ticket
                : subject.getPrivateCredentials(javax.security.auth.kerberos.KerberosTicket.class)) {
            if (ticket.getEndTime() != null) {
                earliest = Math.min(earliest, ticket.getEndTime().getTime());
            }
        }
        if (earliest == Long.MAX_VALUE) {
            return now + FALLBACK_LIFETIME_MS;
        }
        return Math.max(now, earliest - EXPIRY_SKEW_MS);
    }

    /** How many JAAS logins have been performed; for tests. */
    synchronized int getLoginCount() {
        return loginCount;
    }

    private LoginContext loginWithKeyTab() throws LoginException {
        if (principal == null || principal.isBlank()) {
            throw new LoginException("A keytab was configured without a principal to use it with");
        }
        if (!Files.isReadable(keyTab)) {
            throw new LoginException("Keytab is not readable: " + keyTab);
        }
        LoginContext login = new LoginContext(LOGIN_CONTEXT_NAME, null, null,
                new KeyTabConfiguration(keyTab, principal));
        login.login();
        return login;
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
