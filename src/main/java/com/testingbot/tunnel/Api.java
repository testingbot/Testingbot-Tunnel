package com.testingbot.tunnel;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

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
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.NameValuePair;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.message.BasicNameValuePair;
import org.apache.hc.core5.util.Timeout;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 *
 * @author TestingBot
 */
public class Api {

    private final String clientKey;
    private final String clientSecret;
    private String apiHost = "api.testingbot.com";
    private String apiScheme = "https";
    private final App app;
    private int tunnelID;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private Supplier<HttpClientBuilder> httpClientBuilderSupplier = HttpClientBuilder::create;

    public Api(App app) {
        this.app = app;
        this.clientKey = app.getClientKey();
        this.clientSecret = app.getClientSecret();
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
        RequestConfig requestConfig = RequestConfig.custom()
            .setConnectTimeout(Timeout.of(1, TimeUnit.SECONDS))
            .setConnectionRequestTimeout(Timeout.of(1, TimeUnit.SECONDS))
            .build();

        HttpClientBuilder builder = httpClientBuilderSupplier.get();
        builder.setDefaultRequestConfig(requestConfig);

        if (app.getProxy() != null) {
            String[] splitted = app.getProxy().split(":");
            int port = splitted.length > 1 ? Integer.parseInt(splitted[1]) : 80;
            if (app.getProxyAuth() != null) {
                String[] credentials = app.getProxyAuth().split(":");
                BasicCredentialsProvider credsProvider = new BasicCredentialsProvider();
                credsProvider.setCredentials(
                    new AuthScope(splitted[0], port),
                    new UsernamePasswordCredentials(credentials[0], credentials[1].toCharArray())
                );
                builder.setDefaultCredentialsProvider(credsProvider);
            }

            HttpHost proxy = new HttpHost("http", splitted[0], port);
            builder.setProxy(proxy);
        }

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
            HttpClientBuilder builder = httpClientBuilderSupplier.get();

            if (app.getProxy() != null) {
                String[] splitted = app.getProxy().split(":");
                int port = splitted.length > 1 ? Integer.parseInt(splitted[1]) : 80;
                if (app.getProxyAuth() != null) {
                    String[] credentials = app.getProxyAuth().split(":");
                    BasicCredentialsProvider credsProvider = new BasicCredentialsProvider();
                    credsProvider.setCredentials(
                        new AuthScope(splitted[0], port),
                        new UsernamePasswordCredentials(credentials[0], credentials[1].toCharArray())
                    );
                    builder.setDefaultCredentialsProvider(credsProvider);
                }

                HttpHost proxy = new HttpHost("http", splitted[0], port);
                builder.setProxy(proxy);
            }

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
                String jsonData = responseBody.replaceAll("\\\\", "");
                if (!jsonData.startsWith("{")) {
                    jsonData = jsonData.substring(1, (jsonData.length() - 1));
                }

                return objectMapper.readTree(jsonData);
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
            HttpClientBuilder builder = httpClientBuilderSupplier.get();
            if (app.getProxy() != null) {
                String[] splitted = app.getProxy().split(":");
                int port = splitted.length > 1 ? Integer.parseInt(splitted[1]) : 80;
                if (app.getProxyAuth() != null) {
                    String[] credentials = app.getProxyAuth().split(":");
                    BasicCredentialsProvider credsProvider = new BasicCredentialsProvider();
                    credsProvider.setCredentials(
                        new AuthScope(splitted[0], port),
                        new UsernamePasswordCredentials(credentials[0], credentials[1].toCharArray())
                    );
                    builder.setDefaultCredentialsProvider(credsProvider);
                }

                HttpHost proxy = new HttpHost("http", splitted[0], port);
                builder.setProxy(proxy);
            }

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
                String jsonData = responseBody.replaceAll("\\\\", "");
                if (!jsonData.startsWith("{")) {
                    jsonData = jsonData.substring(1, (jsonData.length() - 1));
                }

                return objectMapper.readTree(jsonData);
            }
            catch (Exception e) {
                throw new Exception("Json parse error: " + e.getMessage() + " for " + responseBody);
            }

        } catch (IOException e) {
            throw new Exception(e.getMessage());
        }
    }
}
