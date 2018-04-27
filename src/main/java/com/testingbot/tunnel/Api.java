package com.testingbot.tunnel;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import net.iharder.Base64;
import org.apache.http.HttpResponse;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.impl.client.DefaultHttpClient;

import net.sf.json.*;
import org.apache.http.NameValuePair;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.params.HttpConnectionParams;
import org.apache.http.params.HttpParams;

/**
 *
 * @author TestingBot
 */
public class Api {
    
    private final String clientKey;
    private final String clientSecret;
    private String apiHost = "api.testingbot.com";
    private final App app;
    private int tunnelID;
    
    public Api(App app) {
        this.app = app;
        this.clientKey = app.getClientKey();
        this.clientSecret = app.getClientSecret();
        this.apiHost = "api.testingbot.com";
    }
    
    public JSONObject createTunnel() throws Exception {
        try {
            List<NameValuePair> nameValuePairs = new ArrayList<NameValuePair>(2);
            nameValuePairs.add(new BasicNameValuePair("tunnel_version", App.VERSION.toString()));
            if (app.getTunnelIdentifier() != null && !app.getTunnelIdentifier().isEmpty()) {
                nameValuePairs.add(new BasicNameValuePair("tunnel_identifier", app.getTunnelIdentifier()));
            }
            if (app.isBypassingSquid()) {
                nameValuePairs.add(new BasicNameValuePair("no_cache", String.valueOf(app.isBypassingSquid())));
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
    
    public JSONObject pollTunnel(String tunnelID) throws Exception {
        try {
            return this._get("https://" + apiHost + "/v1/tunnel/" + tunnelID);
        } 
        catch (Exception e) {
            throw new Exception("Could not get tunnel info: " + e.getMessage());
        }
    }
    
    public void destroyTunnel() throws Exception {
        DefaultHttpClient httpClient = new DefaultHttpClient();
        HttpParams params = httpClient.getParams();
        HttpConnectionParams.setConnectionTimeout(params, 1000);
        HttpConnectionParams.setSoTimeout(params, 1000);
        String auth = this.clientKey + ":" + this.clientSecret;
        String encoding = Base64.encodeBytes(auth.getBytes("UTF-8"));

        HttpDelete deleteRequest = new HttpDelete("https://" + apiHost + "/v1/tunnel/" + this.tunnelID);
        deleteRequest.addHeader("accept", "application/json");
        deleteRequest.setHeader("Authorization", "Basic " + encoding);

        HttpResponse response = httpClient.execute(deleteRequest);
        httpClient.getConnectionManager().shutdown();
    }
    
    private JSONObject _post(String url, List<NameValuePair> postData)  throws Exception {
        try {
            DefaultHttpClient httpClient = new DefaultHttpClient();
            String auth = this.clientKey + ":" + this.clientSecret;
            String encoding = Base64.encodeBytes(auth.getBytes("UTF-8"));
            
            HttpPost postRequest = new HttpPost(url);
            
            postRequest.addHeader("accept", "application/json");
            postRequest.setHeader("Authorization", "Basic " + encoding);
            postRequest.setEntity(new UrlEncodedFormEntity(postData));
            
            HttpResponse response = httpClient.execute(postRequest);
            BufferedReader br = new BufferedReader(
                     new InputStreamReader((response.getEntity().getContent()), "UTF8"));

            String output;
            StringBuilder sb = new StringBuilder();
            while ((output = br.readLine()) != null) {
                    sb.append(output);
            }
            httpClient.getConnectionManager().shutdown();
            
            try {
                String jsonData = sb.toString().replaceAll("\\\\", "");
                if (!jsonData.startsWith("{")) {
                    jsonData = jsonData.substring(1, (jsonData.length() - 1));
                }
                
                JSONObject jsonObject = (JSONObject) JSONSerializer.toJSON(jsonData);
                return jsonObject;
            }
            catch (JSONException e) {
                throw new Exception("Json parse error: " + e.getMessage() + " for " + sb.toString());
            }
            
        } catch (ClientProtocolException e) {
            throw new Exception(e.getMessage());
 
        } catch (IOException e) {
             throw new Exception(e.getMessage());
	}
    }
    
    private JSONObject _get(String url) throws Exception {
        try {
            DefaultHttpClient httpClient = new DefaultHttpClient();
            String auth = this.clientKey + ":" + this.clientSecret;
            String encoding = Base64.encodeBytes(auth.getBytes("UTF-8"));
            
            HttpGet getRequest = new HttpGet(url);
            getRequest.addHeader("accept", "application/json");
            getRequest.setHeader("Authorization", "Basic " + encoding);

            HttpResponse response = httpClient.execute(getRequest);

            if (response.getStatusLine().getStatusCode() != 200) {
                    throw new RuntimeException("Failed : HTTP error code : "
                       + response.getStatusLine().getStatusCode());
            }

            BufferedReader br = new BufferedReader(
                     new InputStreamReader((response.getEntity().getContent()), "UTF8"));

            String output;
            StringBuilder sb = new StringBuilder();
            while ((output = br.readLine()) != null) {
                    sb.append(output);
            }

            httpClient.getConnectionManager().shutdown();
            
            try {
                String jsonData = sb.toString().replaceAll("\\\\", "");
                if (!jsonData.startsWith("{")) {
                    jsonData = jsonData.substring(1, (jsonData.length() - 1));
                }
                
                JSONObject jsonObject = (JSONObject) JSONSerializer.toJSON(jsonData);
                return jsonObject;
            }
            catch (JSONException e) {
                throw new Exception("Json parse error: " + e.getMessage() + " for " + sb.toString());
            }
            
        } 
        catch (ClientProtocolException e) {
            throw new Exception(e.getMessage());
 
        } catch (IOException e) {
             throw new Exception(e.getMessage());
	}
    }
}
