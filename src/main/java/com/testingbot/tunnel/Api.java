package com.testingbot.tunnel;

import java.io.IOException;
import java.net.Authenticator;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.hc.client5.http.auth.AuthScope;
import org.apache.hc.client5.http.auth.UsernamePasswordCredentials;
import org.apache.hc.client5.http.classic.methods.HttpDelete;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.entity.UrlEncodedFormEntity;
import org.apache.hc.client5.http.impl.auth.BasicCredentialsProvider;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.DefaultClientTlsStrategy;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.NameValuePair;
import org.apache.hc.core5.http.io.SocketConfig;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.message.BasicNameValuePair;
import org.apache.hc.core5.util.Timeout;

import com.testingbot.tunnel.proxy.ProxySpec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 *
 * @author TestingBot
 */
public class Api {

    private static final Logger LOG = Logger.getLogger(Api.class.getName());

    private final String clientKey;
    private final String clientSecret;
    private String apiHost = "api.testingbot.com";
    private String apiScheme = "https";
    private final App app;
    private int tunnelID;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private Supplier<HttpClientBuilder> httpClientBuilderSupplier = HttpClientBuilder::create;

    // Connect timeout = how long to wait for TCP/TLS to come up.
    // Response timeout = how long to wait once the request is sent.
    static final Timeout DEFAULT_CONNECT_TIMEOUT = Timeout.of(5, TimeUnit.SECONDS);
    static final Timeout DEFAULT_RESPONSE_TIMEOUT = Timeout.of(30, TimeUnit.SECONDS);

    private Timeout connectTimeout = DEFAULT_CONNECT_TIMEOUT;
    private Timeout responseTimeout = DEFAULT_RESPONSE_TIMEOUT;

    public Api(App app) {
        this.app = app;
        this.clientKey = app.getClientKey();
        this.clientSecret = app.getClientSecret();
    }

    /** For testing: shrink the HTTP timeouts so tests don't have to wait 30s. */
    void setTimeoutsForTesting(Timeout connect, Timeout response) {
        this.connectTimeout = connect;
        this.responseTimeout = response;
    }

    private RequestConfig defaultRequestConfig() {
        return RequestConfig.custom()
            .setConnectTimeout(connectTimeout)
            .setConnectionRequestTimeout(connectTimeout)
            .setResponseTimeout(responseTimeout)
            .build();
    }

    /**
     * Routes the API calls through {@code --proxy}, so the tunnel can register and deregister
     * itself on a network whose only egress is a proxy.
     *
     * <p>Parsing is delegated to {@link ProxySpec}, the same as every other egress path. Doing it
     * here with a {@code split(":", 2)} meant {@code socks5://host:port} parsed as host
     * "socks5", port "//host:port", and the tunnel died on a NumberFormatException before it
     * could start -- so a SOCKS5 upstream proxy never worked at all. {@code http://host:port}
     * was broken the same way.
     */
    private HttpClientBuilder newBuilderWithProxy() {
        HttpClientBuilder builder = httpClientBuilderSupplier.get();
        builder.setDefaultRequestConfig(defaultRequestConfig());
        ProxySpec spec = ProxySpec.parse(app.getControlProxy());
        if (spec == null) {
            applyCaCertificates(builder, null);
            return builder;
        }
        String[] credentials = splitCredentials(app.getControlProxyAuth());
        if (spec.isSocks5()) {
            configureSocksProxy(builder, spec, credentials);
        } else {
            if (credentials != null) {
                BasicCredentialsProvider credsProvider = new BasicCredentialsProvider();
                credsProvider.setCredentials(
                    new AuthScope(spec.getHost(), spec.getPort()),
                    new UsernamePasswordCredentials(credentials[0], credentials[1].toCharArray())
                );
                builder.setDefaultCredentialsProvider(credsProvider);
            }
            builder.setProxy(new HttpHost("http", spec.getHost(), spec.getPort()));
            applyCaCertificates(builder, null);
        }
        return builder;
    }

    /**
     * Trusts the authorities from {@code --cacert-file} in addition to the platform's.
     *
     * <p>This is the connection that fails first on a network with TLS interception: the tunnel
     * cannot register itself, so it never starts, and the error names a certificate rather than
     * the proxy that replaced it.
     *
     * @param socksAddress the SOCKS proxy to keep configured, or null when there is not one --
     *                     setting a connection manager here would otherwise discard the one
     *                     {@link #configureSocksProxy} built
     */
    private void applyCaCertificates(HttpClientBuilder builder, InetSocketAddress socksAddress) {
        com.testingbot.tunnel.proxy.CaCertificates authorities = app.getCaCertificates();
        if (authorities == null) {
            return;
        }
        try {
            PoolingHttpClientConnectionManagerBuilder manager =
                    PoolingHttpClientConnectionManagerBuilder.create()
                            .setTlsSocketStrategy(new DefaultClientTlsStrategy(
                                    authorities.sslContext()));
            if (socksAddress != null) {
                manager.setDefaultSocketConfig(SocketConfig.custom()
                        .setSocksProxyAddress(socksAddress)
                        .setSoTimeout(responseTimeout)
                        .build());
            }
            builder.setConnectionManager(manager.build());
        } catch (GeneralSecurityException unusable) {
            // Better to carry on with the platform's trust store than to refuse to start: the
            // connection may well succeed, and if it does not the TLS error says why.
            LOG.log(Level.WARNING,
                    "Could not apply --cacert-file, continuing with the default trust store: {0}",
                    unusable.getMessage());
        }
    }

    /**
     * SOCKS is below HTTP, so it is configured on the socket rather than as an HttpHost.
     *
     * <p>The JDK's SOCKS client is what performs the handshake here, and it asks for RFC 1929
     * credentials through the global {@link Authenticator}. That is unpleasant, but it is the
     * only hook it offers; the answer is therefore narrowed to a SOCKS5 request for this exact
     * host and port, so nothing else in the process can be handed these credentials.
     *
     * <p>It asks as {@code RequestorType.SERVER} with protocol "SOCKS5", not as a proxy request
     * -- matching on the requestor type never fires. When nothing answers it does not fail
     * either: it sends {@code user.name} with an empty password, which a proxy rejects for
     * reasons that look nothing like a misconfigured credential.
     */
    private void configureSocksProxy(HttpClientBuilder builder, ProxySpec spec,
                                     String[] credentials) {
        InetSocketAddress socksAddress =
                new InetSocketAddress(spec.getHost(), spec.getPort());
        if (app.getCaCertificates() != null) {
            // One connection manager carries both; building them separately would mean the
            // second silently replaced the first.
            applyCaCertificates(builder, socksAddress);
        } else {
            builder.setConnectionManager(PoolingHttpClientConnectionManagerBuilder.create()
                .setDefaultSocketConfig(SocketConfig.custom()
                    .setSocksProxyAddress(socksAddress)
                    .setSoTimeout(responseTimeout)
                    .build())
                .build());
        }

        if (credentials != null) {
            Authenticator.setDefault(new Authenticator() {
                @Override
                protected PasswordAuthentication getPasswordAuthentication() {
                    if ("SOCKS5".equalsIgnoreCase(getRequestingProtocol())
                            && spec.getHost().equals(getRequestingHost())
                            && spec.getPort() == getRequestingPort()) {
                        return new PasswordAuthentication(
                                credentials[0], credentials[1].toCharArray());
                    }
                    return null;
                }
            });
        }
    }

    /** {@code user:password}, or null when there is nothing usable. */
    private static String[] splitCredentials(String userPassword) {
        if (userPassword == null) {
            return null;
        }
        String[] parts = userPassword.split(":", 2);
        return parts.length == 2 ? parts : null;
    }

    /**
     * For testing purposes only - allows overriding the API host
     */
    void setApiHost(String apiHost) {
        this.apiHost = apiHost;
    }

    /**
     * For testing purposes only - allows overriding the API scheme (http/https)
     */
    void setApiScheme(String apiScheme) {
        this.apiScheme = apiScheme;
    }

    /**
     * For testing purposes only - allows providing a custom HttpClientBuilder
     */
    void setHttpClientBuilderSupplier(Supplier<HttpClientBuilder> supplier) {
        this.httpClientBuilderSupplier = supplier;
    }

    public JsonNode createTunnel() throws Exception {
        try {
            List<NameValuePair> nameValuePairs = new ArrayList<>(2);
            nameValuePairs.add(new BasicNameValuePair("tunnel_version", App.VERSION.toString()));
            if (app.getTunnelIdentifier() != null && !app.getTunnelIdentifier().isEmpty()) {
                nameValuePairs.add(new BasicNameValuePair("tunnel_identifier", app.getTunnelIdentifier()));
            }
            if (app.isBypassingSquid()) {
                nameValuePairs.add(new BasicNameValuePair("no_cache", String.valueOf(app.isBypassingSquid())));
            }
            if (app.isNoBump()) {
                nameValuePairs.add(new BasicNameValuePair("no_bump", String.valueOf(app.isNoBump())));
            }
            // Per-host bumping. Only sent when --nobump is absent: that already covers every
            // host, and sending both would leave the server to decide which was meant.
            String noBumpDomains = app.getBumpPolicy().apiValue();
            if (noBumpDomains != null) {
                nameValuePairs.add(new BasicNameValuePair("no_bump_domains", noBumpDomains));
            }
            nameValuePairs.add(new BasicNameValuePair("shared", String.valueOf(app.isShared())));
            return this._post(apiScheme + "://" + apiHost + "/v1/tunnel/create", nameValuePairs);
        }
        catch (Exception e) {
            throw new Exception("Could not start tunnel: " + e.getMessage());
        }
    }

    public void setTunnelID(int tunnelID) {
        this.tunnelID = tunnelID;
    }

    public JsonNode pollTunnel(String tunnelID) throws Exception {
        try {
            return this._get(apiScheme + "://" + apiHost + "/v1/tunnel/" + tunnelID);
        }
        catch (Exception e) {
            throw new Exception("Could not get tunnel info: " + e.getMessage());
        }
    }

    public void destroyTunnel() throws Exception {
        HttpClientBuilder builder = newBuilderWithProxy();

        try (CloseableHttpClient httpClient = builder.build()) {
            String auth = this.clientKey + ":" + this.clientSecret;
            String encoding = Base64.getEncoder().encodeToString(auth.getBytes(StandardCharsets.UTF_8));

            HttpDelete deleteRequest = new HttpDelete(apiScheme + "://" + apiHost + "/v1/tunnel/" + this.tunnelID);
            deleteRequest.addHeader("accept", "application/json");
            deleteRequest.setHeader("Authorization", "Basic " + encoding);

            httpClient.execute(deleteRequest, response -> null);
        }
    }

    private JsonNode _post(String url, List<NameValuePair> postData) throws Exception {
        try {
            HttpClientBuilder builder = newBuilderWithProxy();

            String responseBody;
            try (CloseableHttpClient httpClient = builder.build()) {
                String auth = this.clientKey + ":" + this.clientSecret;
                String encoding = Base64.getEncoder().encodeToString(auth.getBytes(StandardCharsets.UTF_8));
                HttpPost postRequest = new HttpPost(url);
                postRequest.addHeader("accept", "application/json");
                postRequest.setHeader("Authorization", "Basic " + encoding);
                postRequest.setEntity(new UrlEncodedFormEntity(postData));

                responseBody = httpClient.execute(postRequest, response ->
                    EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8)
                );
            }

            try {
                JsonNode root = objectMapper.readTree(responseBody);
                if (root.isTextual()) {
                    root = objectMapper.readTree(root.asText());
                }
                return root;
            }
            catch (Exception e) {
                throw new Exception("Json parse error: " + e.getMessage() + " for " + responseBody);
            }

        } catch (IOException e) {
            throw new Exception(e.getMessage());
        }
    }

    private JsonNode _get(String url) throws Exception {
        try {
            HttpClientBuilder builder = newBuilderWithProxy();

            String responseBody;
            try (CloseableHttpClient httpClient = builder.build()) {
                String auth = this.clientKey + ":" + this.clientSecret;
                String encoding = Base64.getEncoder().encodeToString(auth.getBytes(StandardCharsets.UTF_8));
                HttpGet getRequest = new HttpGet(url);
                getRequest.addHeader("accept", "application/json");
                getRequest.setHeader("Authorization", "Basic " + encoding);

                responseBody = httpClient.execute(getRequest, response -> {
                    if (response.getCode() != 200) {
                        throw new RuntimeException("Failed : HTTP error code : " + response.getCode());
                    }
                    return EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
                });
            }

            try {
                JsonNode root = objectMapper.readTree(responseBody);
                if (root.isTextual()) {
                    root = objectMapper.readTree(root.asText());
                }
                return root;
            }
            catch (Exception e) {
                throw new Exception("Json parse error: " + e.getMessage() + " for " + responseBody);
            }

        } catch (IOException e) {
            throw new Exception(e.getMessage());
        }
    }
}
