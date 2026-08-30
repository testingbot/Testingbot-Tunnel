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

    /** True when the tunnel is configured to use Negotiate against the upstream proxy. */
    public boolean isApplicable() {
        return ProxyAuthenticator.Scheme.parse(app.getProxyAuthScheme())
                == ProxyAuthenticator.Scheme.NEGOTIATE;
    }

    /**
     * @return the diagnostics, in the order they should be shown; a failing entry is the reason
     *         the whole setup will not work
     */
    public List<Finding> check() {
        List<Finding> findings = new ArrayList<>();

        ProxySpec spec = ProxySpec.parse(app.getProxy());
        if (spec == null) {
            findings.add(new Finding(false,
                    "--proxy-auth-scheme negotiate is set but --proxy is not, so there is no "
                    + "upstream proxy to authenticate to."));
            return findings;
        }
        if (spec.isSocks5()) {
            findings.add(new Finding(false,
                    "--proxy-auth-scheme negotiate does not apply to a SOCKS5 proxy; SOCKS "
                    + "authenticates inside its own handshake."));
            return findings;
        }

        findings.add(jgssAvailable());
        findings.add(krb5Config());
        findings.add(credentials());

        SpnegoClient client = new SpnegoClient(app.getProxySpn(),
                app.getKrb5KeyTab() == null ? null : Path.of(app.getKrb5KeyTab()),
                app.getKrb5Principal());
        String spn = client.servicePrincipalFor(spec.getHost());
        findings.add(new Finding(true, "Service principal that will be requested: " + spn
                + (app.getProxySpn() == null ? " (derived from --proxy; override with --proxy-spn)" : "")));
        findings.add(serviceTicket(client, spec.getHost(), spn));
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
    private Finding serviceTicket(SpnegoClient client, String proxyHost, String spn) {
        try {
            String token = client.initialToken(proxyHost);
            return new Finding(true, "Obtained a Kerberos service ticket for " + spn
                    + " (" + token.length() + " base64 characters).");
        } catch (Exception failure) {
            return new Finding(false, "Could not obtain a service ticket for " + spn + ": "
                    + rootCause(failure)
                    + ". Check that the SPN is registered for the proxy (--proxy-spn overrides it) "
                    + "and that the ticket cache or keytab is valid.");
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
