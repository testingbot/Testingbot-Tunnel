package com.testingbot.tunnel;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import net.iharder.Base64;
import org.apache.http.HttpHost;
import org.apache.http.HttpResponse;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.CloseableHttpClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.http.NameValuePair;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.client.ProxyAuthenticationStrategy;
import org.apache.http.message.BasicNameValuePair;

/**
 *
 * @author TestingBot
 */
public class Api {

    private final String clientKey;
    private final String clientSecret;
    private final String apiHost = "api.testingbot.com";
    private final App app;
    private int tunnelID;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public Api(App app) {
        this.app = app;
        this.clientKey = app.getClientKey();
        this.clientSecret = app.getClientSecret();
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
            return this._post("https://" + apiHost + "/v1/tunnel/create", nameValuePairs);
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
            return this._get("https://" + apiHost + "/v1/tunnel/" + tunnelID);
        }
        catch (Exception e) {
            throw new Exception("Could not get tunnel info: " + e.getMessage());
        }
    }

    public void destroyTunnel() throws Exception {
        RequestConfig.Builder requestBuilder = RequestConfig.custom();
        requestBuilder.setConnectTimeout(1000);
        requestBuilder.setConnectionRequestTimeout(1000);

        HttpClientBuilder builder = HttpClientBuilder.create();
        builder.setDefaultRequestConfig(requestBuilder.build());

        if (app.getProxy() != null) {
            String[] splitted = app.getProxy().split(":");
            int port = splitted.length > 1 ? Integer.parseInt(splitted[1]) : 80;
            if (app.getProxyAuth() != null) {
                String[] credentials = app.getProxyAuth().split(":");
                CredentialsProvider credsProvider = new BasicCredentialsProvider();
                credsProvider.setCredentials(new AuthScope(splitted[0], port),
                    new UsernamePasswordCredentials(credentials[0], credentials[1]));

                builder.setProxyAuthenticationStrategy(new ProxyAuthenticationStrategy())
                    .setDefaultCredentialsProvider(credsProvider);
            }

            HttpHost proxy = new HttpHost(splitted[0], port, "http");
            builder.setProxy(proxy);
        }

        try (CloseableHttpClient httpClient = builder.build()) {
            String auth = this.clientKey + ":" + this.clientSecret;
            String encoding = Base64.encodeBytes(auth.getBytes(StandardCharsets.UTF_8));

            HttpDelete deleteRequest = new HttpDelete("https://" + apiHost + "/v1/tunnel/" + this.tunnelID);
            deleteRequest.addHeader("accept", "application/json");
            deleteRequest.setHeader("Authorization", "Basic " + encoding);

            httpClient.execute(deleteRequest);
        }
    }

    private JsonNode _post(String url, List<NameValuePair> postData)  throws Exception {
        try {
            HttpClientBuilder builder = HttpClientBuilder.create();

            if (app.getProxy() != null) {
                String[] splitted = app.getProxy().split(":");
                int port = splitted.length > 1 ? Integer.parseInt(splitted[1]) : 80;
                if (app.getProxyAuth() != null) {
                    String[] credentials = app.getProxyAuth().split(":");
                    CredentialsProvider credsProvider = new BasicCredentialsProvider();
                    credsProvider.setCredentials(new AuthScope(splitted[0], port),
                        new UsernamePasswordCredentials(credentials[0], credentials[1]));

                    builder.setProxyAuthenticationStrategy(new ProxyAuthenticationStrategy())
                        .setDefaultCredentialsProvider(credsProvider);
                }

                HttpHost proxy = new HttpHost(splitted[0], port, "http");
                builder.setProxy(proxy);
            }

            StringBuilder sb;
            try (CloseableHttpClient httpClient = builder.build()) {
                String auth = this.clientKey + ":" + this.clientSecret;
                String encoding = Base64.encodeBytes(auth.getBytes(StandardCharsets.UTF_8));
                HttpPost postRequest = new HttpPost(url);
                postRequest.addHeader("accept", "application/json");
                postRequest.setHeader("Authorization", "Basic " + encoding);
                postRequest.setEntity(new UrlEncodedFormEntity(postData));
                HttpResponse response = httpClient.execute(postRequest);
                BufferedReader br = new BufferedReader(
                        new InputStreamReader((response.getEntity().getContent()), StandardCharsets.UTF_8));
                String output;
                sb = new StringBuilder();
                while ((output = br.readLine()) != null) {
                    sb.append(output);
                }
            }

            try {
                String jsonData = sb.toString().replaceAll("\\\\", "");
                if (!jsonData.startsWith("{")) {
                    jsonData = jsonData.substring(1, (jsonData.length() - 1));
                }

                return objectMapper.readTree(jsonData);
            }
            catch (Exception e) {
                throw new Exception("Json parse error: " + e.getMessage() + " for " + sb.toString());
            }

        } catch (IOException e) {
             throw new Exception(e.getMessage());
	    }
    }

    private JsonNode _get(String url) throws Exception {
        try {
            HttpClientBuilder builder = HttpClientBuilder.create();
            if (app.getProxy() != null) {
                String[] splitted = app.getProxy().split(":");
                int port = splitted.length > 1 ? Integer.parseInt(splitted[1]) : 80;
                if (app.getProxyAuth() != null) {
                    String[] credentials = app.getProxyAuth().split(":");
                    CredentialsProvider credsProvider = new BasicCredentialsProvider();
                    credsProvider.setCredentials(new AuthScope(splitted[0], port),
                        new UsernamePasswordCredentials(credentials[0], credentials[1]));

                    builder.setProxyAuthenticationStrategy(new ProxyAuthenticationStrategy())
                        .setDefaultCredentialsProvider(credsProvider);
                }

                HttpHost proxy = new HttpHost(splitted[0], port, "http");
                builder.setProxy(proxy);
            }

            StringBuilder sb;
            try (CloseableHttpClient httpClient = builder.build()) {
                String auth = this.clientKey + ":" + this.clientSecret;
                String encoding = Base64.encodeBytes(auth.getBytes(StandardCharsets.UTF_8));
                HttpGet getRequest = new HttpGet(url);
                getRequest.addHeader("accept", "application/json");
                getRequest.setHeader("Authorization", "Basic " + encoding);
                HttpResponse response = httpClient.execute(getRequest);
                if (response.getStatusLine().getStatusCode() != 200) {
                    throw new RuntimeException("Failed : HTTP error code : "
                            + response.getStatusLine().getStatusCode());
                }   BufferedReader br = new BufferedReader(
                        new InputStreamReader((response.getEntity().getContent()), StandardCharsets.UTF_8));
                String output;
                sb = new StringBuilder();
                while ((output = br.readLine()) != null) {
                    sb.append(output);
                }
            }

            try {
                String jsonData = sb.toString().replaceAll("\\\\", "");
                if (!jsonData.startsWith("{")) {
                    jsonData = jsonData.substring(1, (jsonData.length() - 1));
                }

                return objectMapper.readTree(jsonData);
            }
            catch (Exception e) {
                throw new Exception("Json parse error: " + e.getMessage() + " for " + sb);
            }

        }
        catch (IOException e) {
             throw new Exception(e.getMessage());
	    }
    }
}
