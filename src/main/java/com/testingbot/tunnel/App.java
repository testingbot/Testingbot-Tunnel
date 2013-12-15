package com.testingbot.tunnel;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.FileHandler;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.sf.json.JSONObject;
import org.apache.commons.cli.*;
import ssh.SSHTunnel;
import ssh.TunnelPoller;

public class App {
    public static final String VERSION = "1.14";
    private Api api;
    private String clientKey;
    private String clientSecret;
    private String readyFile;
    private String seleniumPort = "4445";
    private String[] fastFail;
    private SSHTunnel tunnel;
    private String serverIP;
    private Map<String, String> customHeaders = new HashMap<String, String>();
    private boolean useBrowserMob = false;
    private int hubPort = 4444;
    private int tunnelID = 0;
    private boolean useBoost = false;
    private boolean noProxy = false;
    private boolean bypassSquid = false;
    
    public static void main(String... args) throws Exception {
        
        final CommandLineParser cmdLinePosixParser = new PosixParser();  
        final Options options = new Options();
        
        options.addOption("h", "help", false, "Displays help text");
        options.addOption("d", "debug", false, "Enables debug messages");
        
        Option readyfile = new Option("f", "readyfile", true, "This file will be touched when the tunnel is ready for usage");
        readyfile.setArgName("FILE");
        options.addOption(readyfile);
        
        Option seleniumPort = new Option("P", "se-port", true, "The local port your Selenium test should connect to.");
        seleniumPort.setArgName("PORT");
        options.addOption(seleniumPort);
        
        Option fastFail = new Option("F", "fast-fail-regexps", true, "Specify domains you don't want to proxy, comma separated.");
        fastFail.setArgName("OPTIONS");
        options.addOption(fastFail);
        
        Option logfile = new Option("l", "logfile", true, "Write logging to a file.");
        logfile.setArgName("FILE");
        options.addOption(logfile);
        
        Option hubPort = new Option("p", "hubport", true, "Use this if you want to connect to port 80 on our hub instead of the default port 4444");
        hubPort.setArgName("HUBPORT");
        options.addOption(hubPort);
        
        options.addOption("b", "boost", false, "Will use rabbIT to compress and optimize traffic");
        options.addOption("x", "noproxy", false, "Do not start a Jetty proxy (requires user provided proxy server on port 8087)");
        options.addOption("s", "ssl", false, "Will use a browsermob-proxy to fix self-signed certificates");
        options.addOption("q", "squid", false, "Bypass our Squid proxy running on the tunnel VM.");
        
        options.addOption("v", "version", false, "Displays the current version of this program");
        
        CommandLine commandLine;  
       try  
       {  
           commandLine = cmdLinePosixParser.parse(options, args);  
           if (commandLine.hasOption("help"))  
           {  
              HelpFormatter help = new HelpFormatter();
              help.printHelp("java -jar testingbot-tunnel.jar API_KEY API_SECRET [OPTIONS]", options);
              System.exit(0);
           } else if (commandLine.hasOption("version")) {
               System.out.println("Version: testingbot-tunnel.jar " + App.VERSION);
               System.exit(0);
           }
           if (commandLine.hasOption("debug")) {
               Logger.getLogger("").setLevel(Level.ALL);
           } else {
                Logger.getLogger("").setLevel(Level.INFO);
           }
           
           if (commandLine.hasOption("logfile")) {
               Handler handler = new FileHandler(commandLine.getOptionValue("logfile"));
               handler.setLevel(Level.ALL);
               Logger.getLogger("").addHandler(handler);
           }
           
           App app = new App();
           String clientKey = null;
           String clientSecret = null;
           
           if (commandLine.getArgs().length < 2) {
               String userdata[] = app.getUserData();
               if (userdata.length == 2) {
                   clientKey = userdata[0];
                   clientSecret = userdata[1];
               }
           }
           
           if ((commandLine.getArgs().length == 0) && (clientKey == null)) {
               throw new ParseException("Missing required arguments API_KEY API_SECRET");
           }
           
           if ((commandLine.getArgs().length == 1) && (clientKey == null)) {
               throw new ParseException("Missing required argument API_SECRET");
           }
           
           if ((clientKey != null) && (clientSecret != null)) {
               app.clientKey = clientKey;
               app.clientSecret = clientSecret;
           } else {
                app.clientKey = commandLine.getArgs()[0].trim();
                app.clientSecret = commandLine.getArgs()[1].trim();
           }
           
           if (commandLine.hasOption("fast-fail-regexps")) {
               String line = commandLine.getOptionValue("fast-fail-regexps");
               app.fastFail = line.split(",");
           }
           
           if (commandLine.hasOption("ssl")) {
               app.useBrowserMob = true;
           }
           
           if (commandLine.hasOption("boost")) {
               app.useBoost = true;
           }
           
           if (commandLine.hasOption("noproxy")) {
               app.noProxy = true;
           }

           if (commandLine.hasOption("readyfile")) {
               app.readyFile = commandLine.getOptionValue("readyfile");
           }
           
           if (commandLine.hasOption("squid")) {
               app.bypassSquid = true;
           }
           
           if (commandLine.hasOption("hubport")) {
               app.hubPort = Integer.parseInt(commandLine.getOptionValue("hubport"));
               if ((app.hubPort != 80) && (app.hubPort != 4444)) {
                   throw new ParseException("The hub port must either be 80 or 4444");
               }
           }
                      
           if (commandLine.hasOption("se-port")) {
               app.seleniumPort = commandLine.getOptionValue("se-port");
           }
           
           System.out.println("----------------------------------------------------------------");
           System.out.println("  TestingBot Tunnel v" + App.VERSION + "                        ");
           System.out.println("  Questions or suggestions, please visit http://testingbot.com  ");
           System.out.println("-----------------------------------------------------------------");
           
           app.boot();
        }  
        catch (ParseException parseException) 
        {  
           System.err.println(parseException.getMessage()); 
           HelpFormatter help = new HelpFormatter();
           help.printHelp("java -jar testingbot-tunnel.jar API_KEY API_SECRET [OPTIONS]", options);
           System.exit(0);
        } 
    }
    
    private String[] getUserData() {
        File dataFile = new File(System.getProperty("user.home") + "/.testingbot");
        if (dataFile.exists()) {
            try {
              FileInputStream fstream = new FileInputStream(System.getProperty("user.home") + "/.testingbot");
              // Get the object of DataInputStream
              DataInputStream in = new DataInputStream(fstream);
              BufferedReader br = new BufferedReader(new InputStreamReader(in));
              String strLine = br.readLine();
              return strLine.split(":");
            } catch (Exception e) {}
        }
        
        String[] empty = {""};
        
        return empty;
    }
    
    private void saveUserData() {
        File dataFile = new File(System.getProperty("user.home") + "/.testingbot");
        if (!dataFile.exists()) {
            try {
                FileWriter fstream = new FileWriter(System.getProperty("user.home") + "/.testingbot");
                BufferedWriter out = new BufferedWriter(fstream);
                out.write(this.clientKey + ":" + this.clientSecret);
                out.close();
            } catch (Exception e){}
        }
    }
    
    public void boot() throws Exception {
        Thread cleanupThread = new Thread() {
          @Override
          public void run() {
              if (tunnel != null) {
                 tunnel.stop();
              }
            try {
                System.out.println("Shutting down your personal Tunnel Server.");
                api.destroyTunnel();
            } catch (Exception ex) {
                Logger.getLogger(App.class.getName()).log(Level.SEVERE, null, ex);
            }
          }
        };
        
        Runtime.getRuntime().addShutdownHook(cleanupThread);
        
        if (useBoost == true) {
            File rabbitFile = new File(System.getProperty("user.dir") + "/lib/rabbit/jars/rabbit4.jar");
            if (!rabbitFile.exists()) {
                Logger.getLogger(App.class.getName()).log(Level.SEVERE, "Can not use rabbit, not found in {0}", rabbitFile.toString());
            } else {
                ProcessBuilder pb = new ProcessBuilder("java", "-jar", rabbitFile.toString());
                pb.directory(new File(System.getProperty("user.dir") + "/lib/rabbit/"));
                pb.start();
                Process proc = Runtime.getRuntime().exec("java -jar " + rabbitFile.toString());
                System.getProperties().put("http.proxySet", "true");
                System.setProperty("http.proxyHost", "127.0.0.1");
                System.setProperty("https.proxyHost", "127.0.0.1");
                System.setProperty("http.proxyPort", "9666");
                System.setProperty("https.proxyPort", "9666");
                System.getProperties().put("http.nonProxyHosts", "localhost|127.0.0.1|testingbot.com|api.testingbot.com|hub.testingbot.com|europe.testingbot.com|api-eu.testingbot.com");
            }
        }
        
        api = new Api(this);
        JSONObject tunnelData = api.createTunnel();
        
        if (tunnelData.has("error")) {
            System.err.println("An error ocurred: " + tunnelData.getString("error"));
            return;
        }
        
        if (tunnelData.has("id")) {
            this.tunnelID = Integer.parseInt(tunnelData.getString("id"));
            api.setTunnelID(tunnelID);
        }
        
        if (!tunnelData.getString("version").equals(App.VERSION)) {
            System.err.println("A new version (" + tunnelData.getString("version") + ") is available for download at http://testingbot.com\nYou have version " + App.VERSION);
        }
        
        if (tunnelData.getString("state").equals("READY")) {
            this.tunnelReady(tunnelData);
        } else {
            Logger.getLogger(App.class.getName()).log(Level.INFO, "Please wait while your personal Tunnel Server is being setup. Shouldn't take more than a minute.\nWhen the tunnel is ready you will see a message \"You may start your tests.\"");
            TunnelPoller poller = new TunnelPoller(this, tunnelData.getString("id"));
        }
    }

    public void stop() {
      if (tunnel != null) {
         tunnel.stop(true);
      }

      try {
          System.out.println("Shutting down your personal Tunnel Server.");
          api.destroyTunnel();
      } catch (Exception ex) {
          Logger.getLogger(App.class.getName()).log(Level.SEVERE, null, ex);
      }
    }
    
    public void tunnelReady(JSONObject apiResponse) {
        // server is booted, make the connection
        try {
            String _serverIP = apiResponse.getString("ip");
            tunnel = new SSHTunnel(this, _serverIP);
            if (tunnel.isAuthenticated() == true) {
                this.serverIP = _serverIP;
                Logger.getLogger(App.class.getName()).log(Level.INFO, "Successfully authenticated, setting up forwarding.");
                tunnel.createPortForwarding();
                this.startProxies();
                if (useBrowserMob == true) {
                    try {
                        Thread.sleep(10000);
                    } catch(InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    }
                    api.setupBrowserMob(apiResponse);
                }
                Logger.getLogger(App.class.getName()).log(Level.INFO, "The Tunnel is ready, ip: {0}\nYou may start your tests.", _serverIP);
                this.saveUserData();
            }
        } catch (Exception ex) {
            Logger.getLogger(App.class.getName()).log(Level.INFO, "Something went wrong while setting up the Tunnel.");
            Logger.getLogger(App.class.getName()).log(Level.SEVERE, null, ex);
        }
    }
    
    private void startProxies() {
       HttpForwarder httpForwarder = new HttpForwarder(this);
       
       if (httpForwarder.testForwarding() == false) {
           Logger.getLogger(App.class.getName()).log(Level.SEVERE, "!! Forwarder testing failed, localhost port {0} does not seem to be able to reach our hub (hub.testingbot.com)", getSeleniumPort());
       }
      
       if (! this.noProxy) {
           HttpProxy httpProxy = new HttpProxy(this);
           if (httpProxy.testProxy() == false) {
               Logger.getLogger(App.class.getName()).log(Level.SEVERE, "!! Tunnel might not work properly, test failed");
            }
       }
       
       if (this.readyFile != null) {
           File f = new File(this.readyFile);
           if (f.exists()) {
               f.setLastModified(System.currentTimeMillis());
           } else {
                try {
                    f.createNewFile();
                } catch (IOException ex) {
                    Logger.getLogger(App.class.getName()).log(Level.SEVERE, null, ex);
                }
           }
           
           Thread cleanupThread = new Thread() {
              @Override
              public void run() {
                  File f = new File(readyFile);
                  if (f.exists()) {
                        f.delete();
                  }
              }
            };

            Runtime.getRuntime().addShutdownHook(cleanupThread);
       }
    }
    
    public int getTunnelID() {
        return tunnelID;
    }
    
    public Api getApi() {
        return api;
    }
    
    public int getHubPort() {
        return hubPort;
    }

    /**
     * @return the clientKey
     */
    public String getClientKey() {
        return clientKey;
    }

    /**
     * @return the clientSecret
     */
    public String getClientSecret() {
        return clientSecret;
    }

    /**
     * @return the fastFail
     */
    public String[] getFastFail() {
        return fastFail;
    }
    
    public boolean getUseBrowserMob() {
        return useBrowserMob;
    }
    
    public Map<String, String> getCustomHeaders() {
        return customHeaders;
    }
    
    public void addCustomHeader(String key, String value) {
        customHeaders.put(key, value);
    }
    
    public boolean getUseBoost() {
        return useBoost;
    }

   /**
    * @return the seleniumPort
    */
    public String getSeleniumPort() {
      return seleniumPort;
    }
    
    public String getServerIP() {
        return serverIP;
    }
    
    public boolean isBypassingSquid() {
        return bypassSquid;
    }
}
