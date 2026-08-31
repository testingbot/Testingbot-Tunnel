package com.testingbot.tunnel.proxy;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Extra certificate authorities to trust, from {@code --cacert-file}.
 *
 * <p>Needed because a corporate proxy that intercepts TLS re-signs everything with its own CA.
 * The JVM does not trust that CA, so the tunnel cannot reach the TestingBot API and never
 * starts -- on exactly the networks where {@code --proxy} is already required.
 *
 * <p>These are <em>added</em> to the platform's trust store rather than replacing it. Replacing
 * would mean the operator has to supply the public roots as well, and forgetting to would break
 * the API connection in a way that looks identical to the problem being solved.
 */
public final class CaCertificates {

    private final List<X509Certificate> certificates;

    private CaCertificates(List<X509Certificate> certificates) {
        this.certificates = List.copyOf(certificates);
    }

    /**
     * @param files PEM files, each holding one or more certificates
     * @return the loaded authorities, or null when nothing was configured
     * @throws IllegalArgumentException if a file is missing, unreadable, or holds no certificate
     */
    public static CaCertificates load(String[] files) {
        if (files == null || files.length == 0) {
            return null;
        }
        List<X509Certificate> loaded = new ArrayList<>();
        for (String file : files) {
            if (file == null || file.isBlank()) {
                continue;
            }
            loaded.addAll(read(Path.of(file.trim())));
        }
        return loaded.isEmpty() ? null : new CaCertificates(loaded);
    }

    private static List<X509Certificate> read(Path file) {
        if (!Files.isReadable(file)) {
            throw new IllegalArgumentException(
                    "Cannot read --cacert-file '" + file + "'.");
        }
        try (InputStream in = Files.newInputStream(file)) {
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            // generateCertificates, not generateCertificate: a CA bundle is normally a chain,
            // and reading only the first would trust the leaf and not its issuer.
            Collection<? extends Certificate> parsed = factory.generateCertificates(in);
            List<X509Certificate> out = new ArrayList<>();
            for (Certificate certificate : parsed) {
                if (certificate instanceof X509Certificate x509) {
                    out.add(x509);
                }
            }
            if (out.isEmpty()) {
                throw new IllegalArgumentException("--cacert-file '" + file
                        + "' contains no X.509 certificate. It should be PEM, beginning"
                        + " -----BEGIN CERTIFICATE-----.");
            }
            return out;
        } catch (CertificateException malformed) {
            // The JDK's own message here is along the lines of "Invalid lenByte", which tells a
            // user nothing. Pointing at a private key instead of the certificate is the
            // likeliest cause, so say what the file should look like.
            throw new IllegalArgumentException("--cacert-file '" + file
                    + "' could not be parsed. It should be PEM, beginning"
                    + " -----BEGIN CERTIFICATE----- (a private key is not a certificate)."
                    + " Underlying error: " + malformed.getMessage());
        } catch (IOException unreadable) {
            throw new IllegalArgumentException("Cannot read --cacert-file '" + file
                    + "': " + unreadable.getMessage());
        }
    }

    /** How many authorities were loaded, for diagnostics. */
    public int size() {
        return certificates.size();
    }

    /** Their subjects, for {@code --doctor} to show what is actually being trusted. */
    public List<String> subjects() {
        List<String> names = new ArrayList<>(certificates.size());
        for (X509Certificate certificate : certificates) {
            names.add(certificate.getSubjectX500Principal().getName());
        }
        return names;
    }

    /**
     * An {@link SSLContext} trusting the platform's authorities and these as well.
     *
     * <p>Built as two trust managers consulted in turn rather than one merged key store: the
     * platform's set is reached through its own TrustManagerFactory, so this does not depend on
     * where {@code cacerts} lives or what its password is, both of which vary by distribution.
     */
    public SSLContext sslContext() throws GeneralSecurityException {
        X509TrustManager platform = trustManagerFor(null);
        X509TrustManager extra = trustManagerFor(keyStoreOf(certificates));

        TrustManager composite = new X509TrustManager() {
            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType)
                    throws CertificateException {
                try {
                    platform.checkServerTrusted(chain, authType);
                } catch (CertificateException notPublic) {
                    // The usual case for this option: an internally issued certificate the
                    // platform has never heard of. The original failure is kept as the cause,
                    // so a chain that neither trusts still reports why.
                    try {
                        extra.checkServerTrusted(chain, authType);
                    } catch (CertificateException notOursEither) {
                        notOursEither.addSuppressed(notPublic);
                        throw notOursEither;
                    }
                }
            }

            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType)
                    throws CertificateException {
                platform.checkClientTrusted(chain, authType);
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                List<X509Certificate> all = new ArrayList<>();
                all.addAll(List.of(platform.getAcceptedIssuers()));
                all.addAll(List.of(extra.getAcceptedIssuers()));
                return all.toArray(new X509Certificate[0]);
            }
        };

        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, new TrustManager[]{composite}, null);
        return context;
    }

    private static KeyStore keyStoreOf(List<X509Certificate> certificates)
            throws GeneralSecurityException {
        try {
            KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
            store.load(null, null);
            // Aliases must be unique; two certificates with the same subject would otherwise
            // silently collapse into one, trusting fewer authorities than were supplied.
            Map<String, Integer> used = new LinkedHashMap<>();
            for (X509Certificate certificate : certificates) {
                String subject = certificate.getSubjectX500Principal().getName();
                int seen = used.merge(subject, 1, Integer::sum);
                store.setCertificateEntry(subject + "#" + seen, certificate);
            }
            return store;
        } catch (IOException impossible) {
            throw new GeneralSecurityException("Could not build an in-memory trust store",
                    impossible);
        }
    }

    private static X509TrustManager trustManagerFor(KeyStore store)
            throws GeneralSecurityException {
        TrustManagerFactory factory =
                TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init(store);
        for (TrustManager manager : factory.getTrustManagers()) {
            if (manager instanceof X509TrustManager x509) {
                return x509;
            }
        }
        throw new GeneralSecurityException("No X509TrustManager available in this JVM");
    }
}
