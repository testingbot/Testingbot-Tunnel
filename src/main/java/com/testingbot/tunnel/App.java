package com.testingbot.tunnel;

import com.testingbot.tunnel.proxy.ProxyAuth;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.Authenticator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.logging.ConsoleHandler;
import java.util.logging.FileHandler;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.sf.json.JSONObject;
import org.apache.commons.cli.*;
import ssh.SSHTunnel;
import ssh.TunnelPoller;

public class App {
    public static final Float VERSION = 2.5f;
    private Api api;
    private String clientKey;
    private String clientSecret;
    private String readyFile;
    private int seleniumPort = 4445;
    private String[] fastFail;
    private SSHTunnel tunnel;
    private String tunnelIdentifier;
    private String serverIP;
    private Map<String, String> customHeaders = new HashMap<String, String>();
    private int hubPort = 4444;
    private int tunnelID = 0;
    private int jettyPort = 8087;
    private boolean noProxy = false;
    private boolean bypassSquid = false;
    private boolean debugMode = false;
    private HttpProxy httpProxy;
    private String proxy;
    private String proxyAuth;
    private String[] basicAuth;
    private String pac = null;
    private int metricsPort = 8003;

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

        Option metrics = OptionBuilder.withLongOpt("metrics-port").hasArg().withValueSeparator().withDescription("Use the specified port to access metrics. Default port 8003").create();
        options.addOption(metrics);

        Option proxy = new Option("Y", "proxy", true, "Specify an upstream proxy.");
        proxy.setArgName("PROXYHOST:PROXYPORT");
        options.addOption(proxy);
        
        Option basicAuth = new Option("a", "auth", true, "Performs Basic Authentication for specific hosts.");
        basicAuth.setArgs(Option.UNLIMITED_VALUES);
        basicAuth.setArgName("host:port:user:passwd");
        options.addOption(basicAuth);

        Option pac = OptionBuilder.withLongOpt("pac").hasArg().withDescription("Proxy autoconfiguration. Should be a http(s) URL").create();
        options.addOption(pac);

        Option proxyAuth = new Option("z", "proxy-userpwd", true, "Username and password required to access the proxy configured with --proxy.");
        proxyAuth.setArgName("user:pwd");
        options.addOption(proxyAuth);

        Option logfile = new Option("l", "logfile", true, "Write logging to a file.");
        logfile.setArgName("FILE");
        options.addOption(logfile);

        Option identifier = new Option("i", "tunnel-identifier", true, "Add an identifier to this tunnel connection.\n In case of multiple tunnels, specify this identifier in your desired capabilities to use this specific tunnel connection.");
        identifier.setArgName("id");
        options.addOption(identifier);

        Option hubPort = new Option("p", "hubport", true, "Use this if you want to connect to port 80 on our hub instead of the default port 4444");
        hubPort.setArgName("HUBPORT");
        options.addOption(hubPort);

        Option extraHeaders = new Option(null, "extra-headers", true, "Inject extra headers in the requests the tunnel makes.");
        extraHeaders.setArgName("JSON Map with Header Key and Value");
        options.addOption(extraHeaders);

        Option dns = new Option("dns", "dns", true, "Use a custom DNS server. For example: 8.8.8.8");
        dns.setArgName("server");
        options.addOption(dns);

        Option localweb = new Option("w", "web", true, "Point to a directory for testing. Creates a local webserver.");
        localweb.setArgName("directory");
        options.addOption(localweb);

        options.addOption("x", "noproxy", false, "Do not start a local proxy (requires user provided proxy server on port 8087)");
        options.addOption("q", "nocache", false, "Bypass our Caching Proxy running on our tunnel VM.");
        options.addOption("j", "jettyport", true, "The port to launch the local proxy on (default 8087)");
        options.addOption(null, "doctor", false, "Perform checks to detect possible misconfiguration or problems.");
        options.addOption("v", "version", false, "Displays the current version of this program");

        System.setProperty("org.eclipse.jetty.util.log.class", "org.eclipse.jetty.util.log.StdErrLog");
        System.setProperty("org.eclipse.jetty.LEVEL", "OFF");

        Statistics.setStartTime(System.currentTimeMillis());

        CommandLine commandLine;
        try {
            commandLine = cmdLinePosixParser.parse(options, args);
            if (commandLine.hasOption("help")) {
                HelpFormatter help = new HelpFormatter();
                help.setWidth(180);
                help.printHelp("java -jar testingbot-tunnel.jar API_KEY API_SECRET [OPTIONS]", options);
                System.exit(0);
            } else if (commandLine.hasOption("version")) {
                System.out.println("Version: testingbot-tunnel.jar " + App.VERSION);
                System.exit(0);
            }


            Logger logger = Logger.getLogger(App.class.getName());
            logger.setUseParentHandlers(false);
            ConsoleHandler handler = new ConsoleHandler();
            handler.setFormatter(new LogFormatter());
            logger.addHandler(handler);

            App app = new App();
            if (commandLine.hasOption("debug")) {
                Logger.getLogger(App.class.getName()).log(Level.INFO, "Running in debug-mode");
                Logger.getLogger(App.class.getName()).setLevel(Level.ALL);
                app.setDebugMode(true);
            } else {
                Logger.getLogger(App.class.getName()).setLevel(Level.INFO);
            }

            if (commandLine.hasOption("logfile")) {
                try {
                    Handler handlerFile = new FileHandler(commandLine.getOptionValue("logfile"));
                    handlerFile.setFormatter(new LogFormatter());
                    handlerFile.setLevel(Level.ALL);
                    Logger.getLogger(App.class.getName()).addHandler(handlerFile);
                    Logger.getLogger(App.class.getName()).log(Level.INFO, "Logging to file " + commandLine.getOptionValue("logfile"));
                } catch (Exception e) {
                    System.err.println("Cannot write logfile to " + commandLine.getOptionValue("logfile") + ".\nMake sure the directory exists and that we have the proper rights to write to this directory.");
                }
            }

            String clientKey = null;
            String clientSecret = null;

            if (commandLine.hasOption("doctor")) {
                app.doctor();
                return;
            }

            System.out.println("----------------------------------------------------------------");
            System.out.println("  TestingBot Tunnel v" + App.VERSION + "                        ");
            System.out.println("  Questions or suggestions, please visit https://testingbot.com ");
            System.out.println("----------------------------------------------------------------");

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
                Logger.getLogger(App.class.getName()).log(Level.INFO, "Fast-fail mode set for {0}", line);
            }

            if (commandLine.hasOption("proxy")) {
                String line = commandLine.getOptionValue("proxy");
                app.setProxy(line);
            }

            if (commandLine.hasOption("extra-headers")) {
                String extraHeadersValue = commandLine.getOptionValue("extra-headers");
                JSONObject obj = JSONObject.fromObject(extraHeadersValue);

                Iterator<String> keyIterator = obj.keys();
                while (keyIterator.hasNext()) {
                    String key = keyIterator.next();
                    String value = obj.getString(key);
                    app.addCustomHeader(key, value);
                }
            }

            if (commandLine.hasOption("metrics-port")) {
                String line = commandLine.getOptionValue("metrics-port");
                app.setMetricsPort(Integer.parseInt(line));
            }

            if (commandLine.hasOption("tunnel-identifier")) {
                String identifierValue = commandLine.getOptionValue("tunnel-identifier");
                app.setTunnelIdentifier(identifierValue.substring(0, Math.min(identifierValue.length(), 50)));
            }
            
            if (commandLine.hasOption("auth")) {
                app.setBasicAuth(commandLine.getOptionValues("auth"));
            }

            if (commandLine.hasOption("proxy-userpwd")) {
                String line = commandLine.getOptionValue("proxy-userpwd");
                app.setProxyAuth(line);
            }
            if (commandLine.hasOption("noproxy")) {
                app.noProxy = true;
            }

            if (commandLine.hasOption("pac")) {
                app.pac = commandLine.getOptionValue("pac");
            }

            if (commandLine.hasOption("jettyport")) {
                app.setJettyPort(Integer.parseInt(commandLine.getOptionValue("jettyport")));
            }

            if (commandLine.hasOption("readyfile")) {
                app.readyFile = commandLine.getOptionValue("readyfile").trim();
            }

            if (commandLine.hasOption("nocache")) {
                Logger.getLogger(App.class.getName()).log(Level.INFO, "Disable Caching. All requests will go through the tunnel.");
                app.bypassSquid = true;
            }

            if (commandLine.hasOption("hubport")) {
                app.hubPort = Integer.parseInt(commandLine.getOptionValue("hubport"));
                if ((app.hubPort != 80) && (app.hubPort != 4444)) {
                    throw new ParseException("The hub port must either be 80 or 4444");
                }
            }

            if (commandLine.hasOption("dns")) {
                System.setProperty("sun.net.spi.nameservice.nameservers", commandLine.getOptionValue("dns"));
                System.setProperty("sun.net.spi.nameservice.provider.1", "dns,sun");
            }

            if (commandLine.hasOption("web")) {
                LocalWebServer local = new LocalWebServer(commandLine.getOptionValue("web"));
            }

            if (commandLine.hasOption("se-port")) {
                app.seleniumPort = Integer.parseInt(commandLine.getOptionValue("se-port"));
            }

            app.init();
            app.boot();
        } catch (ParseException parseException) {
            System.err.println(parseException.getMessage());
            HelpFormatter help = new HelpFormatter();
            help.printHelp("java -jar testingbot-tunnel.jar API_KEY API_SECRET [OPTIONS]", options);
            System.exit(0);
        }
    }
    private PidPoller pidPoller;
    private TunnelPoller poller;
    private HttpForwarder httpForwarder;

    private String[] getUserData() {
        if (System.getenv("TESTINGBOT_KEY") != null && System.getenv("TESTINGBOT_SECRET") != null) {
          return new String[] { System.getenv("TESTINGBOT_KEY"), System.getenv("TESTINGBOT_SECRET") };
        }
        File dataFile = new File(System.getProperty("user.home") + File.separator + ".testingbot");
        if (dataFile.exists()) {
            try {
                FileInputStream fstream = new FileInputStream(dataFile.getAbsolutePath());
                // Get the object of DataInputStream
                DataInputStream in = new DataInputStream(fstream);
                BufferedReader br = new BufferedReader(new InputStreamReader(in));
                String strLine = br.readLine();
                return strLine.split(":");
            } catch (Exception e) {
            }
        }

        String[] empty = {""};

        return empty;
    }

    private void saveUserData() {
        File dataFile = new File(System.getProperty("user.home") + File.separator + ".testingbot");
        if (!dataFile.exists()) {
            try {
                FileWriter fstream = new FileWriter(dataFile.getAbsolutePath());
                BufferedWriter out = new BufferedWriter(fstream);
                out.write(this.clientKey + ":" + this.clientSecret);
                out.close();
            } catch (Exception e) {
            }
        }
    }

    public void init() {
        Thread cleanupThread = new Thread() {
            @Override
            public void run() {
                if (readyFile != null) {
                    File f = new File(readyFile);
                    if (f.exists()) {
                        f.delete();
                    }
                }

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
    }

    public void boot() throws Exception {
        api = new Api(this);
        JSONObject tunnelData = new JSONObject();

        try {
            tunnelData = api.createTunnel();
        } catch (Exception e) {
            System.err.println("Creating a new tunnel failed, please make sure you're supplying correct credentials and that you can connect to the TestingBot network.\nUse --doctor to verify if everything is set up correctly.");
            System.err.println(e.getMessage());
            System.exit(1);
        }

        if (tunnelData.has("error")) {
            System.err.println("An error ocurred: " + tunnelData.getString("error"));
            System.exit(1);
        }

        trackPid();

        startInsightServer();

        if (tunnelData.has("id")) {
            this.tunnelID = Integer.parseInt(tunnelData.getString("id"));
            api.setTunnelID(tunnelID);
        }

        if (Float.parseFloat(tunnelData.getString("version")) > App.VERSION) {
            System.err.println("A new version (" + tunnelData.getString("version") + ") is available for download at https://testingbot.com\nYou have version " + App.VERSION);
        }

        Logger.getLogger(App.class.getName()).log(Level.INFO, "Please wait while your personal Tunnel Server is being setup. Shouldn't take more than a minute.\nWhen the tunnel is ready you will see a message \"You may start your tests.\"");

        if (tunnelData.getString("state").equals("READY")) {
            this.tunnelReady(tunnelData);
        } else {
            poller = new TunnelPoller(this, tunnelData.getString("id"));
        }
    }

    public void startInsightServer() {
        InsightServer insight = new InsightServer(this);
    }

    public void trackPid() {
        pidPoller = new PidPoller(this);
    }

    public void stop() {
        if (tunnel != null) {
            tunnel.stop(true);
        }

        if (httpForwarder != null) {
            httpForwarder.stop();
        }
        
        if (poller != null) {
            poller.cancel();
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
                Logger.getLogger(App.class.getName()).log(Level.INFO, "The Tunnel is ready, ip: {0}\nYou may start your tests.", _serverIP);
                Logger.getLogger(App.class.getName()).log(Level.INFO, "To stop the tunnel, press CTRL+C");

                this.saveUserData();
            }
        } catch (Exception ex) {
            Logger.getLogger(App.class.getName()).log(Level.INFO, "Something went wrong while setting up the Tunnel.");
            Logger.getLogger(App.class.getName()).log(Level.SEVERE, null, ex);
        }
    }

    private void startProxies() {
        httpForwarder = new HttpForwarder(this);

        if (httpForwarder.testForwarding() == false) {
            Logger.getLogger(App.class.getName()).log(Level.SEVERE, "! Forwarder testing failed, localhost port {0} does not seem to be able to reach our hub (hub.testingbot.com)", Integer.toString(getSeleniumPort()));
        }

        if (!this.noProxy) {
            this.httpProxy = new HttpProxy(this);
            if (this.httpProxy.testProxy() == false) {
                Logger.getLogger(App.class.getName()).log(Level.SEVERE, "! Tunnel might not work properly, test failed");
            }
        }

        if (this.readyFile != null) {
            File f = new File(this.readyFile);
            if (f.exists()) {
                f.setLastModified(System.currentTimeMillis());
            } else {
                try {
                    FileWriter fw = new FileWriter(f.getAbsoluteFile());
                    BufferedWriter bw = new BufferedWriter(fw);
                    bw.write("TestingBot Tunnel Ready");
                    bw.close();
                } catch (IOException ex) {
                    Logger.getLogger(App.class.getName()).log(Level.SEVERE, "Could not create readyfile. Please make sure the director exists and we can write to this directory." , ex);
                }
            }
        }
    }

    public void doctor() {
        Doctor doctor = new Doctor(this);
    }

    public HttpProxy getHttpProxy() {
        return httpProxy;
    }

    public int getTunnelID() {
        return tunnelID;
    }

    public Api getApi() {
        return api;
    }

    public int getJettyPort() {
        return jettyPort;
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

    public void setClientKey(String key) {
        clientKey = key;
    }

    public void setClientSecret(String secret) {
        clientSecret = secret;
    }

    public void setProxy(String p) {
        proxy = p;
        String[] splitted = proxy.split(":");
        System.getProperties().put("http.proxySet", "true");
        System.setProperty("http.proxyHost", splitted[0]);
        System.setProperty("https.proxyHost", splitted[0]);
        System.setProperty("http.proxyPort", splitted[1]);
        System.setProperty("https.proxyPort", splitted[1]);
    }

    public String getProxy() {
        return proxy;
    }

    /**
     * @return the fastFail
     */
    public String[] getFastFail() {
        return fastFail;
    }

    public Map<String, String> getCustomHeaders() {
        return customHeaders;
    }

    public void addCustomHeader(String key, String value) {
        customHeaders.put(key, value);
    }

    /**
     * @return the seleniumPort
     */
    public int getSeleniumPort() {
        return seleniumPort;
    }

    public String getServerIP() {
        return serverIP;
    }

    public boolean isBypassingSquid() {
        return bypassSquid;
    }

    /**
     * @return the debugMode
     */
    public boolean isDebugMode() {
        return debugMode;
    }

    /**
     * @param debugMode the debugMode to set
     */
    public void setDebugMode(boolean debugMode) {
        this.debugMode = debugMode;
    }

    public String getProxyAuth() {
        return proxyAuth;
    }

    public void setProxyAuth(String proxyAuth) {
        this.proxyAuth = proxyAuth;
        String[] splitted = proxyAuth.split(":");
        Authenticator.setDefault(new ProxyAuth(splitted[0], splitted[1]));
    }

    /**
     * @return the tunnelIdentifier
     */
    public String getTunnelIdentifier() {
        return tunnelIdentifier;
    }

    /**
     * @param tunnelIdentifier the tunnelIdentifier to set
     */
    public void setTunnelIdentifier(String tunnelIdentifier) {
        this.tunnelIdentifier = tunnelIdentifier;
    }

    /**
     * @return the pac
     */
    public String getPac() {
        return pac;
    }

    /**
     * @param jettyPort the jettyPort to set
     */
    public void setJettyPort(int jettyPort) {
        this.jettyPort = jettyPort;
    }
    
    /**
     * @return the metricsPort
     */
    public int getMetricsPort() {
        return metricsPort;
    }

    /**
     * @param metricsPort the metricsPort to set
     */
    public void setMetricsPort(int metricsPort) {
        this.metricsPort = metricsPort;
    }

    /**
     * @return the basicAuth
     */
    public String[] getBasicAuth() {
        return basicAuth;
    }

    /**
     * @param basicAuth the basicAuth to set
     */
    public void setBasicAuth(String[] basicAuth) {
        this.basicAuth = basicAuth;
    }

}
