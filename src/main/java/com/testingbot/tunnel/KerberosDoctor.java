package com.testingbot.tunnel;

import com.testingbot.tunnel.proxy.ProxyAuthenticator;
import com.testingbot.tunnel.proxy.ProxySpec;
import com.testingbot.tunnel.proxy.SpnegoClient;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Explains why {@code --proxy-auth-scheme negotiate} is or is not going to work.
 *
 * <p>Real-world Negotiate fails in a small number of well-known ways -- no ticket, an
 * unreadable keytab, a missing realm, a guessed service principal the proxy has not registered --
 * and each produces the same opaque 407. Sauce Connect shipped Negotiate and had to hotfix it,
 * then added a diagnostics mode; this project starts with one instead, so the first question
 * asked of a failing enterprise setup has an answer that does not require a debugger.
 *
 * <p>Runs as part of {@code --doctor}, and only when Negotiate is actually configured.
 */
public final class KerberosDoctor {

    /** One diagnostic line, with enough structure that Doctor can decide overall pass/fail. */
    public record Finding(boolean ok, String message) {
    }

    private final App app;

    public KerberosDoctor(App app) {
        this.app = app;
    }

    /** True when Kerberos is configured at all: against the upstream proxy, or for origins. */
    public boolean isApplicable() {
        return usesNegotiateForProxy()
                || (app.getNegotiateHosts() != null && !app.getNegotiateHosts().isEmpty());
    }

    private boolean usesNegotiateForProxy() {
        return ProxyAuthenticator.Scheme.parse(app.getProxyAuthScheme())
                == ProxyAuthenticator.Scheme.NEGOTIATE;
    }

    /**
     * @return the diagnostics, in the order they should be shown; a failing entry is the reason
     *         the whole setup will not work
     */
    public List<Finding> check() {
        List<Finding> findings = new ArrayList<>();
        boolean forProxy = usesNegotiateForProxy();
        boolean forHosts = app.getNegotiateHosts() != null && !app.getNegotiateHosts().isEmpty();
        ProxySpec spec = forProxy ? ProxySpec.parse(app.getProxy()) : null;

        // A contradiction in the proxy configuration is the whole answer when there is nothing
        // else configured: three lines about the local Kerberos environment would bury the one
        // sentence that explains why this cannot work.
        if (forProxy && spec == null) {
            findings.add(new Finding(false,
                    "--proxy-auth-scheme negotiate is set but --proxy is not, so there is no "
                    + "upstream proxy to authenticate to."));
            if (!forHosts) {
                return findings;
            }
        } else if (forProxy && spec.isSocks5()) {
            findings.add(new Finding(false,
                    "--proxy-auth-scheme negotiate does not apply to a SOCKS5 proxy; SOCKS "
                    + "authenticates inside its own handshake."));
            if (!forHosts) {
                return findings;
            }
        }

        // These three describe the environment rather than the proxy, so they belong to any
        // usable Kerberos configuration. Running them only on the proxy path meant a
        // --krb5-hosts-only setup -- which isApplicable() deliberately admits -- never saw "no
        // keytab and no ticket cache, run kinit", the most useful thing this can say.
        findings.add(jgssAvailable());
        findings.add(krb5Config());
        findings.add(credentials());

        // --krb5-hosts works without an upstream proxy at all.
        findings.addAll(negotiateHosts());

        if (!forProxy || spec == null || spec.isSocks5()) {
            return findings;
        }

        SpnegoClient client = new SpnegoClient(app.getProxySpn(),
                app.getKrb5KeyTab() == null ? null : Path.of(app.getKrb5KeyTab()),
                app.getKrb5Principal());
        String spn = client.servicePrincipalFor(spec.getHost());
        findings.add(new Finding(true, "Service principal that will be requested: " + spn
                + (app.getProxySpn() == null ? " (derived from --proxy; override with --proxy-spn)" : "")));
        findings.add(serviceTicket(client, spec.getHost(), spn));

        // A separate control proxy is a second host to authenticate to, needing its own ticket
        // and usually its own SPN. Reporting only the first would leave the other failing as an
        // indistinguishable 407, which is the whole reason this diagnostic exists.
        ProxySpec control = ProxySpec.parse(app.getControlProxy());
        if (app.hasSeparateControlProxy() && control != null && !control.isSocks5()
                && !control.getHost().equalsIgnoreCase(spec.getHost())) {
            String controlSpn = client.servicePrincipalFor(control.getHost());
            findings.add(new Finding(true,
                    "--proxy-testingbot is a different host, so a second service principal is "
                    + "needed: " + controlSpn));
            findings.add(serviceTicket(client, control.getHost(), controlSpn));
        }
        return findings;
    }

    /**
     * Whether a ticket can actually be had for each {@code --krb5-hosts} entry.
     *
     * <p>A host named here but with no principal registered fails as a 401 from the site, which
     * looks like a site problem rather than a Kerberos one.
     */
    private List<Finding> negotiateHosts() {
        List<Finding> findings = new ArrayList<>();
        com.testingbot.tunnel.proxy.NegotiateHosts hosts = app.getNegotiateHosts();
        if (hosts == null || hosts.isEmpty()) {
            return findings;
        }
        SpnegoClient client = new SpnegoClient(null,
                app.getKrb5KeyTab() == null ? null : Path.of(app.getKrb5KeyTab()),
                app.getKrb5Principal());
        for (String host : hosts.hosts()) {
            findings.add(serviceTicket(client, host, client.servicePrincipalFor(host), false));
        }
        return findings;
    }

    private Finding jgssAvailable() {
        try {
            Class.forName("org.ietf.jgss.GSSManager");
            return new Finding(true, "JGSS (Kerberos) support is available in this JVM.");
        } catch (ClassNotFoundException absent) {
            // A cut-down runtime built without java.security.jgss.
            return new Finding(false,
                    "This JVM has no JGSS support, so Negotiate cannot work. A custom runtime "
                    + "image must include the java.security.jgss module.");
        }
    }

    private Finding krb5Config() {
        String configured = System.getProperty("java.security.krb5.conf");
        if (configured != null) {
            return Files.isReadable(Path.of(configured))
                    ? new Finding(true, "krb5 configuration: " + configured)
                    : new Finding(false, "java.security.krb5.conf points at " + configured
                        + ", which cannot be read.");
        }
        for (String candidate : defaultKrb5Locations()) {
            if (Files.isReadable(Path.of(candidate))) {
                return new Finding(true, "krb5 configuration: " + candidate);
            }
        }
        // Not fatal: a realm can also come from DNS or from the keytab's principal.
        return new Finding(true,
                "No krb5.conf found in the usual locations. This is only a problem if the realm "
                + "cannot be determined another way; set -Djava.security.krb5.conf if login fails.");
    }

    private static List<String> defaultKrb5Locations() {
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        if (os.contains("win")) {
            return List.of(System.getenv("WINDIR") + "\\krb5.ini", "C:\\Windows\\krb5.ini");
        }
        if (os.contains("mac")) {
            return List.of("/etc/krb5.conf", "/Library/Preferences/edu.mit.Kerberos");
        }
        return List.of("/etc/krb5.conf");
    }

    private Finding credentials() {
        if (app.getKrb5KeyTab() != null) {
            Path keyTab = Path.of(app.getKrb5KeyTab());
            if (!Files.isReadable(keyTab)) {
                return new Finding(false, "Keytab " + keyTab + " does not exist or is not readable.");
            }
            if (app.getKrb5Principal() == null) {
                return new Finding(false, "--krb5-keytab needs --krb5-principal.");
            }
            return new Finding(true, "Will log in from keytab " + keyTab
                    + " as " + app.getKrb5Principal() + ".");
        }
        String cache = ticketCachePath();
        if (cache == null) {
            return new Finding(false,
                    "No keytab configured and no Kerberos ticket cache found. Run kinit, or use "
                    + "--krb5-keytab with --krb5-principal for unattended use.");
        }
        return new Finding(true, "Will use the existing ticket cache: " + cache
                + ". Run klist to check it has not expired.");
    }

    private static String ticketCachePath() {
        String fromEnv = System.getenv("KRB5CCNAME");
        if (fromEnv != null && !fromEnv.isBlank()) {
            String path = fromEnv.startsWith("FILE:") ? fromEnv.substring(5) : fromEnv;
            // A non-file cache (KEYRING:, KCM:) cannot be stat'ed, so report it as-is.
            return path.contains(":") || new File(path).canRead() ? fromEnv : null;
        }
        String uid = System.getProperty("user.name", "");
        for (String candidate : List.of("/tmp/krb5cc_" + uid,
                System.getProperty("user.home", "") + "/krb5cc_" + uid)) {
            if (new File(candidate).canRead()) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * The check that matters: everything above can look right and the KDC still refuse a ticket
     * for this SPN, which is the single most common Negotiate failure.
     */
    private Finding serviceTicket(SpnegoClient client, String host, String spn) {
        return serviceTicket(client, host, spn, true);
    }

    /**
     * @param forProxy true when this ticket is for the upstream proxy, so {@code --proxy-spn} is
     *                 worth naming. For a {@code --krb5-hosts} entry the SPN is always derived as
     *                 {@code HTTP/<host>} -- {@code --proxy-spn} is only read for the proxy -- so
     *                 advising it there sends the operator to set an option, see no change, and
     *                 conclude the wrong thing
     */
    private Finding serviceTicket(SpnegoClient client, String host, String spn, boolean forProxy) {
        try {
            String token = client.initialToken(host);
            return new Finding(true, "Obtained a Kerberos service ticket for " + spn
                    + " (" + token.length() + " base64 characters).");
        } catch (Exception failure) {
            return new Finding(false, "Could not obtain a service ticket for " + spn + ": "
                    + rootCause(failure)
                    + (forProxy
                        ? ". Check that the SPN is registered for the proxy (--proxy-spn overrides"
                          + " it) and that the ticket cache or keytab is valid."
                        : ". Check that HTTP/" + host + " is registered in the directory and that"
                          + " the ticket cache or keytab is valid. --proxy-spn does not apply"
                          + " here; it is only used for the upstream proxy."));
        }
    }

    private static String rootCause(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return message == null ? cause.toString() : message;
    }
}
