package com.testingbot.tunnel;

import com.testingbot.tunnel.proxy.ProxyAuth;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.net.Authenticator;
import java.net.ServerSocket;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.logging.ConsoleHandler;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.cli.*;
import ch.qos.logback.classic.LoggerContext;
import org.slf4j.LoggerFactory;
import ssh.SSHTunnel;
import ssh.TunnelPoller;

public class App {
    public static final Float VERSION = getVersionFromProperties();
    private Api api;
    private String clientKey;
    private String clientSecret;
    private String readyFile;
    private int seleniumPort = 4445;
    private String[] fastFail;
    private String[] connectTo;
    private String localhostPolicy;
    private String pacLocal;
    private com.testingbot.tunnel.pac.PacPolicy pacPolicy;
    private String proxyAuthScheme;
    private String proxySpn;
    private String krb5KeyTab;
    private String krb5Principal;
    private String logHttp;
    private String requestIdHeader;
    private String[] headerRules;
    private String[] responseHeaderRules;
    private SSHTunnel tunnel;
    private String tunnelIdentifier;
    private String serverIP;
    private final Map<String, String> customHeaders = new HashMap<>();
    private int hubPort = 80;
    private int tunnelID = 0;
    private int jettyPort = 0;
    private boolean noProxy = false;
    private boolean bypassSquid = false;
    private boolean noBump = false;
    private boolean debugMode = false;
    private HttpProxy httpProxy;
    private String proxy;
    private String proxyAuth;
    private String[] basicAuth;
    private String pac = null;
    private String dnsServer = null;
    static final int DEFAULT_METRICS_PORT = 8003;
    private int metricsPort = DEFAULT_METRICS_PORT;
    private String metricsAuth;
    private int sshPort = 0;
    private boolean shared = false;

    private static Float getVersionFromProperties() {
        try (InputStream input = App.class.getClassLoader().getResourceAsStream("version.properties")) {
            if (input == null) {
                return 0.0f;
            }
            Properties prop = new Properties();
            prop.load(input);
            String version = prop.getProperty("version");
            if (version != null) {
                String numericVersion = version.replaceAll("-SNAPSHOT", "").replaceAll("[^0-9.]", "");
                return Float.parseFloat(numericVersion);
            }
        } catch (IOException | NumberFormatException ex) {
            Logger.getLogger(App.class.getName()).log(Level.WARNING, "Could not read version from properties, using fallback", ex);
        }
        return 0.0f;
    }

    /** Matches maven.compiler.release; the jar's class files cannot load below this. */
    private static final int MINIMUM_JAVA_VERSION = 17;

    static boolean checkJavaVersion() {
        int major = getMajorJavaVersion();
        if (major < MINIMUM_JAVA_VERSION) {
            System.err.println("Error: TestingBot Tunnel requires Java " + MINIMUM_JAVA_VERSION + " or higher.");
            System.err.println("Current version: " + Runtime.version());
            System.err.println("Please upgrade your Java installation and try again.");
            return false;
        }
        return true;
    }

    static int getMajorJavaVersion() {
        return Runtime.version().feature();
    }

    // RFC 7230 token: 1*tchar
    private static final java.util.regex.Pattern HEADER_NAME = java.util.regex.Pattern.compile("[!#$%&'*+\\-.^_`|~0-9A-Za-z]+");

    private static void validateHeaderRules(String flag, String[] rules) throws ParseException {
        try {
            com.testingbot.tunnel.proxy.HeaderRules.parse(rules);
        } catch (IllegalArgumentException invalid) {
            throw new ParseException(flag + ": " + invalid.getMessage());
        }
    }

    static void validateHeader(String name, String value) throws ParseException {
        if (name == null || name.isEmpty() || !HEADER_NAME.matcher(name).matches()) {
            throw new ParseException("Invalid header name: '" + name + "' (must be an RFC 7230 token)");
        }
        if (value == null) {
            throw new ParseException("Header value for '" + name + "' is null");
        }
        if (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw new ParseException("Header value for '" + name + "' must not contain CR or LF");
        }
    }

    public static void main(String... args) throws Exception {
        if (!checkJavaVersion()) {
            System.exit(1);
        }

        final CommandLineParser cmdLinePosixParser = new PosixParser();
        final Options options = new Options();

        options.addOption("h", "help", false, "Displays help text");
        options.addOption("d", "debug", false, "Enables debug messages (alias for --log-level debug)");

        Option logLevel = new Option(null, "log-level", true, "Set log verbosity. One of: error, warn, info (default), debug, trace. Overrides --debug if both are given.");
        logLevel.setArgName("LEVEL");
        options.addOption(logLevel);

        Option readyfile = new Option("f", "readyfile", true, "This file will be touched when the tunnel is ready for usage");
        readyfile.setArgName("FILE");
        options.addOption(readyfile);

        Option seleniumPort = new Option("P", "se-port", true, "The local port your Selenium test should connect to.");
        seleniumPort.setArgName("PORT");
        options.addOption(seleniumPort);

        // Distinct from --pac, which forwards a PAC URL to the cloud browser. This one is
        // evaluated here, by this process, to pick the tunnel's own egress.
        Option pacLocal = new Option(null, "pac-local", true,
            "Choose this tunnel's egress per destination from a proxy auto-config file. Accepts "
            + "a path or an http(s) URL. Evaluated locally by a restricted interpreter -- no "
            + "JavaScript engine is embedded -- so unsupported syntax is reported rather than "
            + "guessed at. Overrides --proxy for hosts the file routes. Unrelated to --pac, "
            + "which tells the remote browser which PAC to use.");
        pacLocal.setArgName("FILE|URL");
        options.addOption(pacLocal);

        Option pacTest = new Option(null, "pac-test", true,
            "Evaluate --pac-local against a URL and print the result, then exit. "
            + "For checking a file before deploying it.");
        pacTest.setArgName("URL");
        options.addOption(pacTest);

        Option proxyAuthScheme = new Option(null, "proxy-auth-scheme", true,
            "Authentication scheme for the upstream --proxy: basic (default) or negotiate "
            + "(SPNEGO/Kerberos, for enterprise proxies). Applies to the upstream proxy only, "
            + "not to --auth. Use --doctor to check a Negotiate setup.");
        proxyAuthScheme.setArgName("basic|negotiate");
        options.addOption(proxyAuthScheme);

        Option proxySpn = new Option(null, "proxy-spn", true,
            "Service principal for --proxy-auth-scheme negotiate. Defaults to HTTP/<proxy-host>. "
            + "Accepts HTTP/host or HTTP@host.");
        proxySpn.setArgName("SPN");
        options.addOption(proxySpn);

        Option krbKeytab = new Option(null, "krb5-keytab", true,
            "Keytab to obtain Kerberos credentials from, for unattended use where nobody has run "
            + "kinit. Requires --krb5-principal. Without it the ambient ticket cache is used.");
        krbKeytab.setArgName("FILE");
        options.addOption(krbKeytab);

        Option krbPrincipal = new Option(null, "krb5-principal", true,
            "Principal to log in as with --krb5-keytab, e.g. user@REALM.");
        krbPrincipal.setArgName("PRINCIPAL");
        options.addOption(krbPrincipal);

        Option logHttp = new Option(null, "log-http", true,
            "How much HTTP traffic detail to log: none, url, headers, or errors (default). "
            + "'errors' logs the request line and headers only for failed or 5xx responses: "
            + "quiet in normal use, self-diagnosing when tests fail. Header values that carry "
            + "credentials are redacted.");
        logHttp.setArgName("MODE");
        options.addOption(logHttp);

        Option requestIdHeader = new Option(null, "request-id-header", true,
            "Header carrying the correlation id used in HTTP logs and passed to the target. "
            + "Reused when the incoming request already has one. Default X-Request-Id.");
        requestIdHeader.setArgName("NAME");
        options.addOption(requestIdHeader);

        // Single-arg and repeatable rather than UNLIMITED_VALUES: a removal rule starts with
        // "-", and a greedy option would be at risk of reading the next flag as a rule.
        Option header = new Option(null, "header", true,
            "Edit a request header sent to the target. Repeatable. "
            + "'name: value' sets, 'name;' sets empty, '-name' removes, '-name*' removes by prefix. "
            + "Plain HTTP only; HTTPS is tunnelled via CONNECT and opaque here.");
        header.setArgName("RULE");
        options.addOption(header);

        Option responseHeader = new Option(null, "response-header", true,
            "Edit a response header returned to the browser, same grammar as --header. "
            + "Useful for dropping a Content-Security-Policy or HSTS that blocks a test page.");
        responseHeader.setArgName("RULE");
        options.addOption(responseHeader);

        Option localhostPolicy = new Option(null, "localhost-policy", true,
            "Whether requests coming through the tunnel may reach this machine's loopback "
            + "interface: allow (default) or deny. Denying is for tunnels meant to reach a "
            + "staging network and nothing running locally. Does not affect the Selenium relay.");
        localhostPolicy.setArgName("allow|deny");
        options.addOption(localhostPolicy);

        Option connectTo = new Option(null, "connect-to", true,
            "Dial a different host/port than the request asks for, without changing the URL, "
            + "Host header or TLS SNI. Format HOST1:PORT1:HOST2:PORT2, comma separated. "
            + "Empty fields match anything / leave that half unchanged.");
        connectTo.setArgName("HOST1:PORT1:HOST2:PORT2");
        options.addOption(connectTo);

        Option fastFail = new Option("F", "fast-fail-regexps", true,
            "Specify domains you don't want to proxy, comma separated. "
            + "Prefix an entry with ! to make it an exception, so '.*,!ok\\.com' blocks "
            + "everything except ok.com.");
        fastFail.setArgName("OPTIONS");
        options.addOption(fastFail);

        Option metrics = Option.builder().longOpt("metrics-port").hasArg().valueSeparator().desc("Use the specified port to access metrics. Default port 8003").build();
        options.addOption(metrics);

        options.addOption(null, "ready", false,
            "Ask a running tunnel whether it is ready, then exit 0 (ready) or 1 (not ready). "
            + "Queries /readyz on --metrics-port; intended for Docker HEALTHCHECK and k8s probes.");

        Option metricsAuthOpt = Option.builder().longOpt("metrics-auth").hasArg().argName("user:password")
                .desc("Require HTTP Basic auth on the /metrics (Prometheus) endpoint. Format: user:password. Off by default. Env: TESTINGBOT_METRICS_AUTH.").build();
        options.addOption(metricsAuthOpt);

        Option proxy = new Option("Y", "proxy", true,
            "Specify an upstream proxy. Accepts host:port (HTTP), http://host:port or socks5://host:port.");
        proxy.setArgName("[SCHEME://]PROXYHOST:PROXYPORT");
        options.addOption(proxy);

        Option basicAuth = new Option("a", "auth", true, "Performs Basic Authentication for specific hosts. Env: TESTINGBOT_AUTH (comma-separated for multiple entries).");
        basicAuth.setArgs(Option.UNLIMITED_VALUES);
        basicAuth.setArgName("host:port:user:passwd");
        options.addOption(basicAuth);

        Option pacOption = Option.builder().longOpt("pac").hasArg().desc("Proxy autoconfiguration. Should be a http(s) URL").build();
        options.addOption(pacOption);

        Option proxyAuth = new Option("z", "proxy-userpwd", true, "Username and password required to access the proxy configured with --proxy. Env: TESTINGBOT_PROXY_USERPWD.");
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

        Option dns = new Option("dns", "dns", true,
            "Resolve hostnames with a specific DNS server instead of the system resolver."
            + " Accepts host or host:port, e.g. 8.8.8.8 or 10.0.0.53:5353."
            + " Falls back to the system resolver if the server cannot answer.");
        dns.setArgName("server");
        options.addOption(dns);

        Option localweb = new Option("w", "web", true, "Point to a directory for testing. Creates a local webserver.");
        localweb.setArgName("directory");
        options.addOption(localweb);

        options.addOption("x", "noproxy", false, "Do not start a local proxy (requires user provided proxy server on port 8087)");
        options.addOption("q", "nocache", false, "Bypass our Caching Proxy running on our tunnel VM.");
        options.addOption("b", "nobump", false, "Do not perform SSL bumping.");
        options.addOption("j", "localproxy", true, "The port to launch the local proxy on (default 8087)");

        options.addOption("s", "shared", false, "Share this tunnel among team members.");
        options.addOption(null, "doctor", false, "Perform checks to detect possible misconfiguration or problems.");
        options.addOption("v", "version", false, "Displays the current version of this program");
        Option configOption = new Option(null, "config", true,
            "Read settings from a properties file. Keys are long option names without dashes"
            + " (for example: se-port = 4445). Explicit command-line flags override the file.");
        configOption.setArgName("FILE");
        options.addOption(configOption);

        Statistics.setStartTime(System.currentTimeMillis());

        CommandLine commandLine;
        try {
            // Config entries are expanded into the argument list, so everything below sees
            // them exactly as if they had been typed on the command line.
            // Config entries first, then the environment for anything still unset, so the
            // parser below sees all three sources as if they had been typed on the command line.
            commandLine = cmdLinePosixParser.parse(options,
                    EnvOptions.expand(ConfigFile.expand(args, options), options));
            if (commandLine.hasOption("help")) {
                HelpFormatter help = new HelpFormatter();
                help.setWidth(180);
                help.printHelp("java -jar testingbot-tunnel.jar API_KEY API_SECRET [OPTIONS]", options);
                System.exit(0);
            } else if (commandLine.hasOption("version")) {
                System.out.println("Version: testingbot-tunnel.jar " + App.VERSION);
                System.exit(0);
            } else if (commandLine.hasOption("pac-test")) {
                if (!commandLine.hasOption("pac-local")) {
                    throw new ParseException("--pac-test also needs --pac-local.");
                }
                System.exit(pacTest(commandLine.getOptionValue("pac-local").trim(),
                        commandLine.getOptionValue("pac-test").trim()));
            } else if (commandLine.hasOption("ready")) {
                // Queries a tunnel running in another process, so it needs no credentials and
                // must not perform any of the local setup below -- binding a proxy port here
                // would be a side effect on a machine whose tunnel is already running.
                System.exit(ReadinessProbe.probe("127.0.0.1", readinessPort(commandLine),
                        ReadinessProbe.DEFAULT_TIMEOUT_MS));
            }


            Logger logger = Logger.getLogger(App.class.getName());
            logger.setUseParentHandlers(false);
            ConsoleHandler handler = new ConsoleHandler();
            handler.setFormatter(new LogFormatter());
            logger.addHandler(handler);

            App app = new App();

            String levelArg = commandLine.getOptionValue("log-level");
            if (levelArg == null && commandLine.hasOption("debug")) {
                levelArg = "debug";
            }
            if (levelArg == null) {
                levelArg = "info";
            }

            Level julLevel;
            ch.qos.logback.classic.Level logbackLevel;
            String jettyLevel;
            switch (levelArg.toLowerCase(java.util.Locale.ROOT)) {
                case "error":
                    julLevel = Level.SEVERE;
                    logbackLevel = ch.qos.logback.classic.Level.ERROR;
                    jettyLevel = "ERROR";
                    break;
                case "warn":
                case "warning":
                    julLevel = Level.WARNING;
                    logbackLevel = ch.qos.logback.classic.Level.WARN;
                    jettyLevel = "WARN";
                    break;
                case "info":
                    julLevel = Level.INFO;
                    logbackLevel = ch.qos.logback.classic.Level.INFO;
                    jettyLevel = "INFO";
                    break;
                case "debug":
                    julLevel = Level.ALL;
                    logbackLevel = ch.qos.logback.classic.Level.DEBUG;
                    jettyLevel = "DEBUG";
                    break;
                case "trace":
                    julLevel = Level.ALL;
                    logbackLevel = ch.qos.logback.classic.Level.TRACE;
                    jettyLevel = "TRACE";
                    break;
                default:
                    throw new ParseException("Invalid --log-level '" + levelArg + "'. Use one of: error, warn, info, debug, trace.");
            }

            logger.setLevel(julLevel);
            System.setProperty("org.eclipse.jetty.LEVEL", jettyLevel);

            LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
            loggerContext.getLogger("ROOT").setLevel(logbackLevel);
            loggerContext.getLogger("org.apache.hc").setLevel(logbackLevel);
            // Never enable HttpClient wire/header logging: it dumps request bodies
            // (client_key/client_secret form fields) and Authorization headers in cleartext.
            loggerContext.getLogger("org.apache.hc.client5.http.wire").setLevel(ch.qos.logback.classic.Level.ERROR);
            loggerContext.getLogger("org.apache.hc.client5.http.headers").setLevel(ch.qos.logback.classic.Level.ERROR);

            boolean debugLike = (logbackLevel.toInt() <= ch.qos.logback.classic.Level.DEBUG.toInt());
            if (debugLike) {
                loggerContext.getLogger("org.eclipse.jetty").setLevel(logbackLevel);
                loggerContext.getLogger("com.testingbot.tunnel.proxy").setLevel(logbackLevel);
                logger.log(Level.INFO, "Running in debug-mode");
                app.setDebugMode(true);
            }

            if (commandLine.hasOption("logfile")) {
                String logfilePath = commandLine.getOptionValue("logfile");
                try {
                    FileHandler handlerFile = new FileHandler(logfilePath, true);
                    handlerFile.setFormatter(new LogFormatter());
                    handlerFile.setLevel(Level.ALL);
                    // Attach to App's logger (useParentHandlers=false above) AND to the JUL root
                    // so messages from sibling loggers (HttpProxy, SSHTunnel, Doctor, ...) land in the file too.
                    logger.addHandler(handlerFile);
                    Logger.getLogger("").addHandler(handlerFile);

                    ch.qos.logback.classic.encoder.PatternLayoutEncoder encoder = new ch.qos.logback.classic.encoder.PatternLayoutEncoder();
                    encoder.setContext(loggerContext);
                    encoder.setPattern("%d{ISO8601} [%thread] %-5level %logger{36} - %msg%n");
                    encoder.start();
                    ch.qos.logback.core.FileAppender<ch.qos.logback.classic.spi.ILoggingEvent> fileAppender = new ch.qos.logback.core.FileAppender<>();
                    fileAppender.setContext(loggerContext);
                    fileAppender.setName("FILE");
                    fileAppender.setFile(logfilePath);
                    fileAppender.setAppend(true);
                    fileAppender.setEncoder(encoder);
                    fileAppender.start();
                    loggerContext.getLogger(ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME).addAppender(fileAppender);

                    logger.log(Level.INFO, "Logging to file {0}", logfilePath);
                } catch (IOException | SecurityException e) {
                    System.err.println("Cannot write logfile to " + logfilePath + ".\nMake sure the directory exists and we have permission to write there.");
                }
            }

            String clientKey = null;
            String clientSecret = null;

            if (commandLine.hasOption("se-port")) {
                app.seleniumPort = Integer.parseInt(commandLine.getOptionValue("se-port"));
            }

            if (commandLine.hasOption("localproxy")) {
                app.setJettyPort(Integer.parseInt(commandLine.getOptionValue("localproxy")));
            } else {
                app.setFreeJettyPort();
            }

            if (commandLine.hasOption("doctor")) {
                applyUpstreamProxyOptions(app, commandLine);
                app.doctor();
                return;
            }

            System.out.println("----------------------------------------------------------------");
            System.out.println("  TestingBot Tunnel v" + App.VERSION + "                        ");
            System.out.println("  Questions or suggestions, please visit https://testingbot.com ");
            System.out.println("----------------------------------------------------------------");

            if (commandLine.getArgs().length < 2) {
                String[] userdata = app.getUserData();
                if (userdata.length == 2) {
                    clientKey = userdata[0];
                    clientSecret = userdata[1];
                }
            }

            if ((commandLine.getArgs().length == 0) && (clientKey == null)) {
                throw new ParseException("Missing required arguments API_KEY API_SECRET\nYou can get these two values from https://testingbot.com/members/user/edit");
            }

            if ((commandLine.getArgs().length == 1) && (clientKey == null)) {
                throw new ParseException("Missing required argument API_SECRET\nYou can get this from https://testingbot.com/members/user/edit");
            }

            if ((clientKey != null) && (clientSecret != null)) {
                app.clientKey = clientKey;
                app.clientSecret = clientSecret;
            } else {
                app.clientKey = commandLine.getArgs()[0].trim();
                app.clientSecret = commandLine.getArgs()[1].trim();
            }

            // Parsed here purely to validate: a typo should be a startup error with the usual
            // CLI wording, not an IllegalArgumentException from inside proxy construction.
            if (commandLine.hasOption("log-http")) {
                String value = commandLine.getOptionValue("log-http").trim();
                try {
                    com.testingbot.tunnel.proxy.HttpLogHandler.Mode.parse(value);
                } catch (IllegalArgumentException unknown) {
                    throw new ParseException("Invalid --log-http value: " + value
                            + ". Expected none, url, headers or errors.");
                }
                app.setLogHttp(value);
            }
            if (commandLine.hasOption("request-id-header")) {
                String value = commandLine.getOptionValue("request-id-header").trim();
                validateHeader(value, "");
                app.setRequestIdHeader(value);
            }

            if (commandLine.hasOption("header")) {
                String[] rules = commandLine.getOptionValues("header");
                validateHeaderRules("--header", rules);
                app.setHeaderRules(rules);
            }
            if (commandLine.hasOption("response-header")) {
                String[] rules = commandLine.getOptionValues("response-header");
                validateHeaderRules("--response-header", rules);
                app.setResponseHeaderRules(rules);
            }

            if (commandLine.hasOption("localhost-policy")) {
                String value = commandLine.getOptionValue("localhost-policy").trim();
                if (!value.equalsIgnoreCase("allow") && !value.equalsIgnoreCase("deny")) {
                    throw new ParseException("Invalid --localhost-policy value: " + value
                            + ". Expected allow or deny.");
                }
                app.setLocalhostPolicy(value);
            }

            if (commandLine.hasOption("connect-to")) {
                app.setConnectTo(commandLine.getOptionValue("connect-to").split(","));
            }

            if (commandLine.hasOption("fast-fail-regexps")) {
                String line = commandLine.getOptionValue("fast-fail-regexps");
                app.fastFail = line.split(",");
                Logger.getLogger(App.class.getName()).log(Level.INFO, "Fast-fail mode set for {0}", line);
            }

            applyUpstreamProxyOptions(app, commandLine);

            if (commandLine.hasOption("extra-headers")) {
                String extraHeadersValue = commandLine.getOptionValue("extra-headers");
                ObjectMapper mapper = new ObjectMapper();
                JsonNode obj = mapper.readTree(extraHeadersValue);

                Iterator<String> keyIterator = obj.fieldNames();
                while (keyIterator.hasNext()) {
                    String key = keyIterator.next();
                    String value = obj.get(key).asText();
                    validateHeader(key, value);
                    app.addCustomHeader(key, value);
                }
            }

            if (commandLine.hasOption("metrics-port")) {
                String line = commandLine.getOptionValue("metrics-port");
                app.setMetricsPort(Integer.parseInt(line));
            }

            String metricsAuthValue = commandLine.hasOption("metrics-auth")
                    ? commandLine.getOptionValue("metrics-auth")
                    : System.getenv("TESTINGBOT_METRICS_AUTH");
            if (metricsAuthValue != null && !metricsAuthValue.isEmpty()) {
                if (!metricsAuthValue.contains(":") || metricsAuthValue.startsWith(":")) {
                    throw new ParseException("--metrics-auth / TESTINGBOT_METRICS_AUTH must be in the form user:password");
                }
                app.setMetricsAuth(metricsAuthValue);
            }

            if (commandLine.hasOption("tunnel-identifier")) {
                String identifierValue = commandLine.getOptionValue("tunnel-identifier");
                app.setTunnelIdentifier(identifierValue.substring(0, Math.min(identifierValue.length(), 50)));
            }

            String[] authValues = null;
            if (commandLine.hasOption("auth")) {
                authValues = commandLine.getOptionValues("auth");
            } else {
                String envAuth = System.getenv("TESTINGBOT_AUTH");
                if (envAuth != null && !envAuth.isEmpty()) {
                    authValues = envAuth.split(",");
                }
            }
            if (authValues != null) {
                for (String optionValue : authValues) {
                    if (optionValue.split(":").length < 4) {
                        throw new ParseException("ERROR: Auth value must contain host:port:user:password value: " + optionValue);
                    }
                }
                app.setBasicAuth(authValues);
            }

            String proxyAuthValue = commandLine.hasOption("proxy-userpwd")
                    ? commandLine.getOptionValue("proxy-userpwd")
                    : System.getenv("TESTINGBOT_PROXY_USERPWD");
            if (proxyAuthValue != null && !proxyAuthValue.isEmpty()) {
                app.setProxyAuth(proxyAuthValue);
            }

            if (commandLine.hasOption("noproxy")) {
                app.noProxy = true;
            }

            if (commandLine.hasOption("pac")) {
                app.pac = commandLine.getOptionValue("pac");
            }

            if (commandLine.hasOption("readyfile")) {
                app.readyFile = commandLine.getOptionValue("readyfile").trim();
            }

            if (commandLine.hasOption("nocache")) {
                Logger.getLogger(App.class.getName()).log(Level.INFO, "Disable Caching. All requests will go through the tunnel.");
                app.bypassSquid = true;
            }

            if (commandLine.hasOption("nobump")) {
                Logger.getLogger(App.class.getName()).log(Level.INFO, "Disable SSL bumping. SSL certificates will not be rewritten.");
                app.noBump = true;
            }

            if (commandLine.hasOption("shared")) {
                Logger.getLogger(App.class.getName()).log(Level.INFO, "Tunnel will be shared among team members.");
                app.shared = true;
            }

            if (commandLine.hasOption("hubport")) {
                app.hubPort = Integer.parseInt(commandLine.getOptionValue("hubport"));
                if ((app.hubPort != 80) && (app.hubPort != 4444)) {
                    throw new ParseException("The hub port must either be 80 or 4444");
                }
            }

            if (commandLine.hasOption("dns")) {
                // Note: this used to set sun.net.spi.nameservice.*, the JDK 8 pluggable
                // nameservice SPI. That SPI was removed in JDK 9, so the option silently did
                // nothing for years. Resolution now goes through CustomDnsResolver, which the
                // proxy and CONNECT paths consult when dialling.
                app.setDnsServer(commandLine.getOptionValue("dns"));
            }

            if (commandLine.hasOption("web")) {
                new LocalWebServer(commandLine.getOptionValue("web"));
            }

            app.init();
            app.boot();
            // The pid file lets an external supervisor stop this process; it is
            // only meaningful when running as a command line client.
            app.trackPid();
        } catch (ParseException parseException) {
            System.err.println(parseException.getMessage());
            System.exit(2);
        } catch (TunnelFailedException tunnelFailedException) {
            System.err.println(tunnelFailedException.getMessage());
            System.exit(tunnelFailedException.getExitCode());
        }
    }
    private PidPoller pidPoller;
    private TunnelPoller poller;
    private HttpForwarder httpForwarder;
    private Thread cleanupThread;

    private String[] getUserData() {
        if (System.getenv("TESTINGBOT_KEY") != null && System.getenv("TESTINGBOT_SECRET") != null) {
          return new String[] { System.getenv("TESTINGBOT_KEY"), System.getenv("TESTINGBOT_SECRET") };
        }
        Path dataFile = Path.of(System.getProperty("user.home"), ".testingbot");
        if (Files.exists(dataFile)) {
            try (BufferedReader br = Files.newBufferedReader(dataFile)) {
                String strLine = br.readLine();
                if (strLine != null) {
                    return strLine.split(":", 2);
                }
            } catch (IOException e) {
                Logger.getLogger(App.class.getName()).log(Level.WARNING, "Could not read credentials from .testingbot file", e);
            }
        }

        return new String[]{""};
    }

    public void init() {
        cleanupThread = new Thread() {
            @Override
            public void run() {
                if (readyFile != null) {
                    File f = new File(readyFile);
                    if (f.exists() && !f.delete()) {
                        Logger.getLogger(App.class.getName()).log(Level.WARNING, "Could not delete ready file: " + readyFile);
                    }
                }

                if (tunnel != null) {
                    tunnel.stop();
                }
                TunnelMetrics.setTunnelUp(false);
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

    Api createApi() {
        return new Api(this);
    }

    public void boot() throws Exception {
        api = createApi();
        JsonNode tunnelData = null;

        try {
            tunnelData = api.createTunnel();
        } catch (Exception e) {
            throw new TunnelFailedException("Creating a new tunnel failed, please make sure you're supplying correct credentials and that you can connect to the TestingBot network.\nUse --doctor to verify if everything is set up correctly.\n" + e.getMessage(), 1, e);
        }

        if (tunnelData.has("error")) {
            String error = "An error ocurred: " + tunnelData.get("error").asText();
            if (tunnelData.get("error").asText().contains("401")) {
                error += "\nMissing required arguments API_KEY API_SECRET\nYou can get these two values from https://testingbot.com/members/user/edit";
            }
            throw new TunnelFailedException(error, 1);
        }

        startInsightServer();

        if (tunnelData.has("id")) {
            this.tunnelID = Integer.parseInt(tunnelData.get("id").asText());
            api.setTunnelID(tunnelID);
        }

        TunnelMetrics.setTunnelInfo(App.VERSION, this.tunnelID, this.tunnelIdentifier);

        if (Float.parseFloat(tunnelData.get("version").asText()) > App.VERSION) {
            System.err.println("A new version (" + tunnelData.get("version").asText() + ") is available for download at https://testingbot.com\nYou have version " + App.VERSION);
        }

        Logger.getLogger(App.class.getName()).log(Level.INFO, "Please wait while your personal Tunnel Server is being setup. Shouldn't take more than a minute.\nWhen the tunnel is ready you will see a message \"You may start your tests.\"");

        if (tunnelData.get("state").asText().equals("READY")) {
            this.tunnelReady(tunnelData);
        } else {
            poller = new TunnelPoller(this, tunnelData.get("id").asText());
        }
    }

    public void startInsightServer() {
        InsightServer insight = new InsightServer(this);
    }

    public void trackPid() {
        pidPoller = new PidPoller(this);
    }

    public void stop() {
        TunnelMetrics.setTunnelUp(false);

        if (tunnel != null) {
            tunnel.stop(true);
        }

        if (httpForwarder != null) {
            httpForwarder.stop();
        }

        if (poller != null) {
            poller.cancel();
        }

        if (pidPoller != null) {
            pidPoller.cancel();
            pidPoller = null;
        }

        // Without this, an embedder that starts a tunnel per job leaks one
        // shutdown hook per App instance for the lifetime of the JVM.
        if (cleanupThread != null) {
            try {
                Runtime.getRuntime().removeShutdownHook(cleanupThread);
            } catch (IllegalStateException alreadyShuttingDown) {
                // the JVM is on its way down and will run the hook itself
            }
            cleanupThread = null;
        }

        // api is null when stop() is called after boot() failed, which is the
        // normal path for an embedder cleaning up in a finally block.
        if (api != null) {
            try {
                System.out.println("Shutting down your personal Tunnel Server.");
                api.destroyTunnel();
            } catch (Exception ex) {
                Logger.getLogger(App.class.getName()).log(Level.SEVERE, null, ex);
            }
        }
    }

    public void tunnelReady(JsonNode apiResponse) {
        // server is booted, make the connection
        try {
            String _serverIP = apiResponse.get("ip").asText();
            tunnel = new SSHTunnel(this, _serverIP);
            if (tunnel.isAuthenticated()) {
                this.serverIP = _serverIP;
                Logger.getLogger(App.class.getName()).log(Level.INFO, "Successfully authenticated, setting up forwarding.");
                tunnel.createPortForwarding();
                boolean healthy = this.startProxies();
                TunnelMetrics.setTunnelUp(true);
                if (healthy) {
                    Logger.getLogger(App.class.getName()).log(Level.INFO, "The Tunnel is ready, ip: {0}\nYou may start your tests.", _serverIP);
                } else {
                    Logger.getLogger(App.class.getName()).log(Level.SEVERE, "The Tunnel is up (ip: {0}) but its self-test failed; tests may not work until this is resolved.", _serverIP);
                }
                Logger.getLogger(App.class.getName()).log(Level.INFO, "To stop the tunnel, press CTRL+C");
            }
        } catch (TunnelFailedException tunnelFailedException) {
            // fatal: let it reach the caller of boot() so the command line
            // client can exit and an embedder can handle it
            throw tunnelFailedException;
        } catch (Exception ex) {
            Logger.getLogger(App.class.getName()).log(Level.INFO, "Something went wrong while setting up the Tunnel.");
            Logger.getLogger(App.class.getName()).log(Level.SEVERE, null, ex);
        }
    }

    private boolean startProxies() {
        boolean healthy = true;
        httpForwarder = new HttpForwarder(this);

        if (!httpForwarder.testForwarding()) {
            Logger.getLogger(App.class.getName()).log(Level.SEVERE, "! Forwarder testing failed, localhost port {0} does not seem to be able to reach our hub (hub.testingbot.com)", Integer.toString(getSeleniumPort()));
            healthy = false;
        }

        if (!this.noProxy) {
            try {
                this.httpProxy = new HttpProxy(this);
            } catch (HttpProxy.HttpProxyStartException ex) {
                throw new TunnelFailedException(ex.getMessage(), 1, ex);
            }
            if (tunnel != null && !tunnel.verifyReverseForwardDelivery()) {
                Logger.getLogger(App.class.getName()).log(Level.SEVERE, "! Reverse port forwarding cannot reach the local proxy on port {0}, traffic through the tunnel will fail", Integer.toString(getJettyPort()));
                healthy = false;
            }
            if (this.getProxy() == null && !this.httpProxy.testProxy()) {
                Logger.getLogger(App.class.getName()).log(Level.SEVERE, "! Tunnel might not work properly, test failed");
                healthy = false;
            }
        }

        if (this.readyFile != null) {
            File f = new File(this.readyFile);
            if (f.exists()) {
                f.setLastModified(System.currentTimeMillis());
            } else {
                try (FileWriter fw = new FileWriter(f.getAbsoluteFile());
                     BufferedWriter bw = new BufferedWriter(fw)) {
                    bw.write("TestingBot Tunnel Ready");
                } catch (IOException ex) {
                    Logger.getLogger(App.class.getName()).log(Level.SEVERE, "Could not create readyfile. Please make sure the directory exists and we have permission write to this directory." , ex);
                }
            }
        }

        return healthy;
    }

    /**
     * Evaluates a PAC file against one URL and prints the outcome.
     *
     * <p>A PAC file decides where a customer's traffic goes, and the usual way to discover it is
     * wrong is that some requests quietly take the wrong route. Being able to ask directly turns
     * that into a one-line check.
     */
    private static int pacTest(String location, String url) {
        try {
            com.testingbot.tunnel.pac.PacPolicy policy =
                    com.testingbot.tunnel.pac.PacPolicy.load(location);
            String host = java.net.URI.create(url).getHost();
            if (host == null) {
                System.err.println("Could not read a host from " + url
                        + "; expected something like https://example.com/path");
                return 1;
            }
            com.testingbot.tunnel.pac.PacResult result = policy.evaluateUncached(url, host);
            System.out.println("PAC file : " + location);
            System.out.println("URL      : " + url);
            System.out.println("Host     : " + host);
            System.out.println("Result   : " + result);
            System.out.println("Egress   : " + (result.first().isDirect()
                    ? "direct connection"
                    : "via " + result.first().toProxySpec()));
            return 0;
        } catch (RuntimeException failure) {
            System.err.println(failure.getMessage());
            return 1;
        }
    }

    /** The metrics port --ready should query: the flag if given, otherwise the default. */
    /**
     * Applies the upstream-proxy options.
     *
     * <p>Called from the --doctor branch as well as the normal path: the Kerberos diagnostics
     * are useless if doctor runs before --proxy and --proxy-auth-scheme have been read, which is
     * where they sit in the argument handling below.
     */
    private static void applyUpstreamProxyOptions(App app, CommandLine commandLine) throws ParseException {
        if (commandLine.hasOption("proxy")) {
            app.setProxy(commandLine.getOptionValue("proxy"));
        }
        if (commandLine.hasOption("pac-local")) {
            app.setPacLocal(commandLine.getOptionValue("pac-local").trim());
        }
            if (commandLine.hasOption("proxy-auth-scheme")) {
                String value = commandLine.getOptionValue("proxy-auth-scheme").trim();
                try {
                    com.testingbot.tunnel.proxy.ProxyAuthenticator.Scheme.parse(value);
                } catch (IllegalArgumentException unknown) {
                    throw new ParseException("Invalid --proxy-auth-scheme value: " + value
                            + ". Expected basic or negotiate.");
                }
                app.setProxyAuthScheme(value);
            }
            if (commandLine.hasOption("proxy-spn")) {
                app.setProxySpn(commandLine.getOptionValue("proxy-spn").trim());
            }
            if (commandLine.hasOption("krb5-keytab")) {
                app.setKrb5KeyTab(commandLine.getOptionValue("krb5-keytab").trim());
            }
            if (commandLine.hasOption("krb5-principal")) {
                app.setKrb5Principal(commandLine.getOptionValue("krb5-principal").trim());
            }
            if (app.getKrb5KeyTab() != null && app.getKrb5Principal() == null) {
                throw new ParseException("--krb5-keytab also needs --krb5-principal.");
            }

    }

    private static int readinessPort(CommandLine commandLine) throws ParseException {
        String value = commandLine.getOptionValue("metrics-port");
        if (value == null) {
            return DEFAULT_METRICS_PORT;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException notANumber) {
            throw new ParseException("Invalid --metrics-port value: " + value);
        }
    }

    public void doctor() {
        Doctor doctor = new Doctor(this);
        if (doctor.hasFailures()) {
            throw new TunnelFailedException("Doctor detected one or more problems, see the output above.", 1);
        }
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

    public void setFreeJettyPort() {
        try (ServerSocket serverSocket = _findAvailableSocket()) {
            if (serverSocket == null) {
                Logger.getLogger(App.class.getName()).log(Level.WARNING, "Could not find available port for Jetty, using default 8087");
                setJettyPort(8087);
                return;
            }
            int port = serverSocket.getLocalPort();
            setJettyPort(port);
        } catch (IOException e) {
            Logger.getLogger(App.class.getName()).log(Level.WARNING, "Error finding available port: " + e.getMessage());
            setJettyPort(8087);
        }
    }

    private ServerSocket _findAvailableSocket() {
        try {
            return new ServerSocket(0);
        } catch (IOException ex) {
            return null;
        }
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

    /**
     * Sets the upstream proxy server address.
     *
     * @param p proxy address in format "host:port" or "host" (defaults to port 80)
     * @throws IllegalArgumentException if proxy format is invalid
     */
    public void setProxy(String p) {
        if (p == null || p.trim().isEmpty()) {
            proxy = p;
            return;
        }
        String trimmed = p.trim();
        // Validation lives in ProxySpec so the CLI accepts exactly the forms the proxy layer
        // understands. Parsing it here separately is what let "--proxy socks5://host:port" --
        // a form this option advertises -- die with NumberFormatException before the tunnel
        // even started.
        if (com.testingbot.tunnel.proxy.ProxySpec.parse(trimmed) == null) {
            throw new IllegalArgumentException(
                "Invalid --proxy value: " + p
                + ". Expected host:port, http://host:port or socks5://host:port.");
        }
        proxy = trimmed;
    }

    public String getProxy() {
        return proxy;
    }

    /**
     * @return the fastFail
     */
    public String getPacLocal() {
        return pacLocal;
    }

    public void setPacLocal(String pacLocal) {
        this.pacLocal = pacLocal;
        this.pacPolicy = null;
    }

    /** Loaded once and shared; null when --pac-local was not given. */
    public synchronized com.testingbot.tunnel.pac.PacPolicy getPacPolicy() {
        if (pacPolicy == null && pacLocal != null) {
            pacPolicy = com.testingbot.tunnel.pac.PacPolicy.load(pacLocal);
        }
        return pacPolicy;
    }

    public String getProxyAuthScheme() {
        return proxyAuthScheme;
    }

    public void setProxyAuthScheme(String proxyAuthScheme) {
        this.proxyAuthScheme = proxyAuthScheme;
    }

    public String getProxySpn() {
        return proxySpn;
    }

    public void setProxySpn(String proxySpn) {
        this.proxySpn = proxySpn;
    }

    public String getKrb5KeyTab() {
        return krb5KeyTab;
    }

    public void setKrb5KeyTab(String krb5KeyTab) {
        this.krb5KeyTab = krb5KeyTab;
    }

    public String getKrb5Principal() {
        return krb5Principal;
    }

    public void setKrb5Principal(String krb5Principal) {
        this.krb5Principal = krb5Principal;
    }

    /** The authenticator every egress path uses for the upstream proxy. */
    public com.testingbot.tunnel.proxy.ProxyAuthenticator proxyAuthenticator() {
        return com.testingbot.tunnel.proxy.ProxyAuthenticator.create(
                com.testingbot.tunnel.proxy.ProxyAuthenticator.Scheme.parse(proxyAuthScheme),
                getProxyAuth(), proxySpn,
                krb5KeyTab == null ? null : java.nio.file.Path.of(krb5KeyTab),
                krb5Principal);
    }

    public String getLogHttp() {
        return logHttp;
    }

    public void setLogHttp(String logHttp) {
        this.logHttp = logHttp;
    }

    public String getRequestIdHeader() {
        return requestIdHeader;
    }

    public void setRequestIdHeader(String requestIdHeader) {
        this.requestIdHeader = requestIdHeader;
    }

    public String[] getHeaderRules() {
        return headerRules;
    }

    public void setHeaderRules(String[] headerRules) {
        this.headerRules = headerRules;
    }

    public String[] getResponseHeaderRules() {
        return responseHeaderRules;
    }

    public void setResponseHeaderRules(String[] responseHeaderRules) {
        this.responseHeaderRules = responseHeaderRules;
    }

    public String getLocalhostPolicy() {
        return localhostPolicy;
    }

    public void setLocalhostPolicy(String localhostPolicy) {
        this.localhostPolicy = localhostPolicy;
    }

    public String[] getConnectTo() {
        return connectTo;
    }

    public void setConnectTo(String[] connectTo) {
        this.connectTo = connectTo;
    }

    public void setFastFail(String[] fastFail) {
        this.fastFail = fastFail;
    }

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

    public boolean isNoBump() {
        return noBump;
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

    /**
     * Sets the proxy authentication credentials.
     *
     * @param proxyAuth credentials in format "username:password"
     * @throws IllegalArgumentException if format is invalid
     */
    public void setProxyAuth(String proxyAuth) {
        if (proxyAuth != null && !proxyAuth.trim().isEmpty()) {
            String trimmed = proxyAuth.trim();
            if (!trimmed.contains(":")) {
                throw new IllegalArgumentException("Invalid proxy auth format. Expected 'username:password' but got: " + proxyAuth);
            }
            String[] splitted = trimmed.split(":", 2);
            if (splitted.length != 2 || splitted[0].isEmpty()) {
                throw new IllegalArgumentException("Invalid proxy auth format. Username cannot be empty");
            }
            this.proxyAuth = trimmed;
            Authenticator.setDefault(new ProxyAuth(splitted[0], splitted[1]));
        } else {
            this.proxyAuth = proxyAuth;
        }
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
    /** DNS server for the "dns" option, or null to use the platform resolver. */
    public String getDnsServer() {
        return dnsServer;
    }

    public void setDnsServer(String dnsServer) {
        this.dnsServer = dnsServer;
    }

    public String getPac() {
        return pac;
    }

    /**
     * @param jettyPort the jettyPort to set
     */
    public void setJettyPort(int jettyPort) {
        Logger.getLogger(App.class.getName()).log(Level.INFO, "Setting up Local Proxy Port {0}", Integer.toString(jettyPort));
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
    /** Companion to {@link #setJettyPort} and {@link #setMetricsPort}; --se-port sets this. */
    public void setSeleniumPort(int seleniumPort) {
        this.seleniumPort = seleniumPort;
    }

    public void setMetricsPort(int metricsPort) {
        this.metricsPort = metricsPort;
    }

    public String getMetricsAuth() {
        return metricsAuth;
    }

    public void setMetricsAuth(String metricsAuth) {
        this.metricsAuth = metricsAuth;
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

    public int getSSHPort() {
        if (sshPort == 0) {
            // find available port
            try (ServerSocket serverSocket = _findAvailableSocket()) {
                if (serverSocket == null) {
                    sshPort = 4446;
                    return sshPort;
                }
                sshPort = serverSocket.getLocalPort();
            } catch (IOException e) {
                Logger.getLogger(App.class.getName()).log(Level.WARNING, "Error finding available SSH port: " + e.getMessage());
                sshPort = 4446;
            }
        }
        return sshPort;
    }

    /**
     * @return whether the tunnel is shared among team members
     */
    public boolean isShared() {
        return shared;
    }

    /**
     * @param shared whether the tunnel should be shared among team members
     */
    public void setShared(boolean shared) {
        this.shared = shared;
    }

}
