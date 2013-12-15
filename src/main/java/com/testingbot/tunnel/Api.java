package com.testingbot.tunnel;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.iharder.Base64;
import org.apache.http.HttpResponse;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.impl.client.DefaultHttpClient;

import net.sf.json.*;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.params.HttpConnectionParams;
import org.apache.http.params.HttpParams;

/**
 *
 * @author TestingBot
 */
public class Api {
    
    private String clientKey;
    private String clientSecret;
    private String apiHost = "api.testingbot.com";
    private App app;
    private int tunnelID;
    
    public Api(App app) {
        this.app = app;
        this.clientKey = app.getClientKey();
        this.clientSecret = app.getClientSecret();
        this.apiHost = "api.testingbot.com";
    }
    
    public JSONObject createTunnel() throws Exception {
        try {
            return this._get("https://" + apiHost + "/v1/tunnel/start");
        } 
        catch (Exception e) {
            throw new Exception("Could not get tunnel info: " + e.getMessage());
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
    
    public void setupBrowserMob(JSONObject apiResponse) {
        DefaultHttpClient httpClient = new DefaultHttpClient();
        HttpPost postRequest = new HttpPost("http://" + apiResponse.getString("ip") + ":9090/proxy?httpProxy=" + apiResponse.getString("private_ip") + ":2009");
        try {
            HttpResponse response = httpClient.execute(postRequest);
            BufferedReader br = new BufferedReader(
                     new InputStreamReader((response.getEntity().getContent()), "UTF8"));

            String output;
            StringBuilder sb = new StringBuilder();
            while ((output = br.readLine()) != null) {
                    sb.append(output);
            }
            
            try {
                String jsonData = sb.toString().replaceAll("\\\\", "");
                if (!jsonData.startsWith("{")) {
                    jsonData = jsonData.substring(1, (jsonData.toString().length() - 1));
                }
                
                JSONObject jsonObject = (JSONObject) JSONSerializer.toJSON(jsonData);
                app.addCustomHeader("TB-Tunnel-Port", jsonObject.getString("port"));
            }
            catch (JSONException ex) {
                Logger.getLogger(Api.class.getName()).log(Level.SEVERE, null, ex);
            }
        } catch (IOException ex) {
            Logger.getLogger(Api.class.getName()).log(Level.SEVERE, null, ex);
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
                    jsonData = jsonData.substring(1, (jsonData.toString().length() - 1));
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
