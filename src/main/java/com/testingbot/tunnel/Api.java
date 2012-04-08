/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package com.testingbot.tunnel;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import net.iharder.Base64;
import org.apache.http.HttpResponse;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.impl.client.DefaultHttpClient;

import net.sf.json.*;
import org.apache.http.params.HttpConnectionParams;
import org.apache.http.params.HttpParams;

/**
 *
 * @author jochen
 */
public class Api {
    
    private String clientKey;
    private String clientSecret;
    
    public Api(String clientKey, String clientSecret) {
        this.clientKey = clientKey;
        this.clientSecret = clientSecret;
    }
    
    public JSONObject createTunnel() throws Exception {
        try {
            return this._get("http://api.testingbot.com/v1/tunnel");
        } 
        catch (Exception e) {
            throw new Exception("Could not get tunnel info: " + e.getMessage());
        }
    }
    
    public JSONObject pollTunnel() throws Exception {
        try {
            return this._get("http://api.testingbot.com/v1/tunnel");
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

        HttpDelete deleteRequest = new HttpDelete("http://api.testingbot.com/v1/tunnel");
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
                throw new Exception("Json parse error: " + e.getMessage());
            }
            
        } 
        catch (ClientProtocolException e) {
            throw new Exception(e.getMessage());
 
        } catch (IOException e) {
             throw new Exception(e.getMessage());
	}
    }
}
