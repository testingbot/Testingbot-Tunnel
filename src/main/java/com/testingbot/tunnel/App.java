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
    /** How ws:// traverses an upstream proxy; see --ws-proxy-mode. */
    private String wsProxyMode = "connect";
    private InsightServer insightServer;
    private String pacLocal;
    private com.testingbot.tunnel.pac.PacPolicy pacPolicy;
    private String proxyAuthScheme;
    private String proxySpn;
    private String krb5KeyTab;
    private String krb5Principal;
    private String logHttp;
    private String logFormat = "text";
    private com.testingbot.tunnel.proxy.LogHttpPolicy logHttpPolicy =
            com.testingbot.tunnel.proxy.LogHttpPolicy.defaults();
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
    private com.testingbot.tunnel.proxy.BumpPolicy bumpPolicy =
            com.testingbot.tunnel.proxy.BumpPolicy.parse(false, null);
    private boolean debugMode = false;
    private HttpProxy httpProxy;
    private String proxy;
    private String proxyAuth;
    private String[] basicAuth;
    private String pac = null;
    private com.testingbot.tunnel.proxy.CaCertificates caCertificates = null;
    private com.testingbot.tunnel.proxy.AllowedHosts allowedHosts =
            com.testingbot.tunnel.proxy.AllowedHosts.unrestricted();
    private com.testingbot.tunnel.proxy.NegotiateHosts negotiateHosts =
            com.testingbot.tunnel.proxy.NegotiateHosts.none();
    /** Null means "keep the built-in default", so an absent flag changes nothing. */
    private Integer httpDialTimeoutSeconds = null;
    private Integer httpIdleTimeoutSeconds = null;
    private String controlProxy = null;
    private String controlProxyAuth = null;
    private String dnsServer = null;
    private java.time.Duration dnsTimeout =
            com.testingbot.tunnel.proxy.CustomDnsResolver.DEFAULT_QUERY_TIMEOUT;
    private boolean dnsRoundRobin = false;
    static final int DEFAULT_METRICS_PORT = 8003;
    private int metricsPort = DEFAULT_METRICS_PORT;
    private String metricsAuth;
    /**
     * Loopback, because every listener this program opens is meant for a client on this
     * machine: the Selenium relay and the local proxy are reached by the tests and by the
     * reverse SSH forward, which delivers to 127.0.0.1. Binding the wildcard address instead
     * offered the relay -- which stamps the account key and secret onto every request it
     * forwards -- and the proxy -- which is an open CONNECT relay into this host's network
     * and loopback -- to anyone who could route here.
     */
    static final String DEFAULT_BIND_ADDRESS = "127.0.0.1";
    private String bindAddress = DEFAULT_BIND_ADDRESS;
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
    /** Package-visible so LauncherTest can check the launcher’s copy has not drifted. */
    static final int MINIMUM_JAVA_VERSION = 17;

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

    static void validateHeaderRules(String flag, String[] rules) throws ParseException {
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

    /**
     * The full option set.
     *
     * <p>Extracted from main so the options and their validation can be exercised without
     * starting a tunnel; previously the only way to reach any of this was to run the
     * process.
     */
    static Options buildOptions() {
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

        Option logFormat = new Option(null, "log-format", true,
            "How log lines are written: text (default) or json. JSON emits one object per "
            + "record, for a collector that would otherwise have to guess where a multi-line "
            + "message or a stack trace ends.");
        logFormat.setArgName("text|json");
        options.addOption(logFormat);

        Option logHttp = new Option(null, "log-http", true,
            "How much HTTP traffic detail to log: none, url, headers, or errors (default). "
            + "'errors' logs the request line and headers only for failed or 5xx responses: "
            + "quiet in normal use, self-diagnosing when tests fail. Header values that carry "
            + "credentials are redacted. Can be set per module as 'proxy:LEVEL' or "
            + "'forwarder:LEVEL', comma separated, so browser traffic and the Selenium relay "
            + "can be turned up independently: --log-http proxy:none,forwarder:headers.");
        logHttp.setArgName("MODE|MODULE:MODE");
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

        Option wsProxyMode = new Option(null, "ws-proxy-mode", true,
            "How a ws:// upgrade traverses --proxy: connect (default) asks the proxy to open a "
            + "tunnel with CONNECT and upgrades inside it, which is what RFC 6455 specifies and "
            + "what browsers do; get sends the upgrade to the proxy as an ordinary absolute-URI "
            + "request, for proxies that forward Upgrade but only allow CONNECT to 443. "
            + "Does not affect wss://, which is always a CONNECT.");
        wsProxyMode.setArgName("connect|get");
        options.addOption(wsProxyMode);

        Option connectTo = new Option(null, "connect-to", true,
            "Dial a different host/port than the request asks for, without changing the URL, "
            + "Host header or TLS SNI. Format HOST1:PORT1:HOST2:PORT2, comma separated. "
            + "Empty fields match anything / leave that half unchanged.");
        connectTo.setArgName("HOST1:PORT1:HOST2:PORT2");
        options.addOption(connectTo);

        Option allowHosts = new Option(null, "allow-hosts", true,
            "Only allow the tunnel to reach these hosts, comma separated. An entry is an exact "
            + "host or *.suffix for subdomains. Everything else is refused with 403. The "
            + "opposite of --fast-fail-regexps, which denies a named few and permits the rest; "
            + "this permits a named few and denies the rest. Omit it to permit any host.");
        allowHosts.setArgName("HOST,*.HOST");
        options.addOption(allowHosts);

        Option fastFail = new Option("F", "fast-fail-regexps", true,
            "Specify domains you don't want to proxy, comma separated. "
            + "Prefix an entry with ! to make it an exception, so '.*,!ok\\.com' blocks "
            + "everything except ok.com.");
        fastFail.setArgName("OPTIONS");
        options.addOption(fastFail);

        Option metrics = Option.builder().longOpt("metrics-port").hasArg().valueSeparator().desc("Use the specified port to access metrics. Default port 8003").build();
        options.addOption(metrics);

        Option bindAddressOpt = Option.builder().longOpt("bind-address").hasArg().argName("ADDRESS")
                .desc("Interface for every local listener -- the Selenium relay (--se-port), the "
                    + "local proxy (--localproxy), the insight endpoints (--metrics-port) and "
                    + "--web. Default 127.0.0.1. Use 0.0.0.0 to accept connections from other "
                    + "hosts, which exposes the relay's TestingBot credentials and an open "
                    + "forward proxy to everyone who can reach this machine. "
                    + "Env: TESTINGBOT_BIND_ADDRESS.").build();
        options.addOption(bindAddressOpt);

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

        Option krb5Hosts = new Option(null, "krb5-hosts", true,
            "Send SPNEGO/Negotiate credentials to these hosts, comma separated, as well as to "
            + "the upstream proxy. For intranet sites that authenticate with Kerberos. Empty by "
            + "default and never wildcarded: a service ticket names the user, so every host that "
            + "may receive one has to be written down. Plain HTTP only -- HTTPS reaches the "
            + "target as an opaque CONNECT tunnel.");
        krb5Hosts.setArgName("HOST,HOST");
        options.addOption(krb5Hosts);

        Option dialTimeout = new Option(null, "http-dial-timeout", true,
            "Seconds to wait for a TCP connection to a target or upstream proxy before giving "
            + "up (default 5 for the API, 15 for tunnelled connections). Lower it on a network "
            + "where unreachable hosts should fail fast.");
        dialTimeout.setArgName("seconds");
        options.addOption(dialTimeout);

        Option idleTimeout = new Option(null, "http-idle-timeout", true,
            "Seconds a connection may sit with no traffic before it is closed (default 120 for "
            + "proxied HTTP, 300 for CONNECT tunnels). Raise it for long-lived idle "
            + "connections; this is what bounds a response that trickles forever.");
        idleTimeout.setArgName("seconds");
        options.addOption(idleTimeout);

        Option caCert = new Option(null, "cacert-file", true,
            "Trust an additional certificate authority, as a PEM file. Repeatable. For networks "
            + "where a proxy intercepts TLS and re-signs it with an internal CA, which the JVM "
            + "would otherwise reject. Added to the platform's trust store, not replacing it.");
        caCert.setArgName("FILE");
        // Not UNLIMITED_VALUES: that made "--cacert-file ca.pem KEY SECRET" swallow the
        // positional credentials as extra file names, and the error then quoted the
        // customer's API key back at them -- the line that gets pasted into a support
        // ticket. Repeating the option already yields every occurrence.
        options.addOption(caCert);

        Option controlProxy = new Option(null, "proxy-testingbot", true,
            "Upstream proxy for reaching TestingBot itself -- the API and the SSH control "
            + "connection -- when it differs from the one test traffic uses. Same forms as "
            + "--proxy. Defaults to --proxy when not given.");
        controlProxy.setArgName("host:port");
        options.addOption(controlProxy);

        Option controlProxyAuth = new Option(null, "proxy-testingbot-userpwd", true,
            "Credentials for --proxy-testingbot. Not inherited from --proxy-userpwd: those "
            + "belong to a different proxy and must not be sent to this one.");
        controlProxyAuth.setArgName("user:pwd");
        options.addOption(controlProxyAuth);

        Option dns = new Option("dns", "dns", true,
            "Resolve hostnames with specific DNS servers instead of the system resolver."
            + " Accepts host or host:port, comma separated, e.g. 8.8.8.8 or"
            + " 10.0.0.53:5353,10.0.0.54. The first is primary and the rest are tried in order"
            + " when it does not answer. Falls back to the system resolver if none can.");
        dns.setArgName("server[,server]");
        options.addOption(dns);

        Option dnsTimeout = new Option(null, "dns-timeout", true,
            "How long to wait for each DNS server, in seconds (default 5). Only applies to"
            + " servers given with --dns.");
        dnsTimeout.setArgName("seconds");
        options.addOption(dnsTimeout);

        options.addOption(null, "dns-round-robin", false,
            "Spread queries across the --dns servers instead of treating the first as primary."
            + " For a pool whose members are equal, so one being slow does not slow everything.");

        Option localweb = new Option("w", "web", true, "Point to a directory for testing. Creates a local webserver.");
        localweb.setArgName("directory");
        options.addOption(localweb);

        options.addOption("x", "noproxy", false, "Do not start a local proxy (requires user provided proxy server on port 8087)");
        options.addOption("q", "nocache", false, "Bypass our Caching Proxy running on our tunnel VM.");
        options.addOption("b", "nobump", false, "Do not perform SSL bumping.");

        Option noBumpDomains = new Option(null, "nobump-domains", true,
            "Do not perform SSL bumping for these hosts, comma separated. Use when one "
            + "environment behind the tunnel presents a certificate that cannot be re-signed, "
            + "without turning bumping off everywhere. Ignored when --nobump is given, which "
            + "already covers every host.");
        noBumpDomains.setArgName("HOST,HOST");
        options.addOption(noBumpDomains);
        options.addOption("j", "localproxy", true, "The port to launch the local proxy on (default 8087)");

        options.addOption("s", "shared", false, "Share this tunnel among team members.");
        options.addOption(null, "doctor", false, "Perform checks to detect possible misconfiguration or problems.");
        options.addOption("v", "version", false, "Displays the current version of this program");
        Option configOption = new Option(null, "config", true,
            "Read settings from a properties file. Keys are long option names without dashes"
            + " (for example: se-port = 4445). Explicit command-line flags override the file.");
        configOption.setArgName("FILE");
        options.addOption(configOption);

        return options;
    }

    /**
     * Applies every option that configures the tunnel, and validates as it goes.
     *
     * <p>Extracted from main so the validation can be exercised without starting a tunnel.
     * These are the messages a user meets when a flag is slightly wrong, and previously the
     * only way to reach any of them was to run the process and read stderr.
     */
    /**
     * Resolves the API credentials from the positional arguments, falling back to the dotfile.
     *
     * <p>Separated because the three failure messages here are the first thing a new user sees
     * when they get it wrong, and they were previously unreachable without launching the process.
     */
    static void applyCredentials(App app, CommandLine commandLine) throws ParseException {
        String clientKey = null;
        String clientSecret = null;

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
    }

    /** The three logging levels a --log-level value maps to. */
    record LogLevels(Level jul, ch.qos.logback.classic.Level logback, String jetty) {
        boolean isDebugLike() {
            return logback.toInt() <= ch.qos.logback.classic.Level.DEBUG.toInt();
        }
    }

    /**
     * Maps {@code --log-level} (or {@code --debug}) onto the three logging systems in play.
     *
     * <p>Pure, so the mapping and its rejection message can be tested without reconfiguring the
     * JVM's loggers, which a test cannot undo.
     */
    static LogLevels resolveLogLevels(String levelArg) throws ParseException {
        switch (levelArg.toLowerCase(java.util.Locale.ROOT)) {
            case "error":
                return new LogLevels(Level.SEVERE, ch.qos.logback.classic.Level.ERROR, "ERROR");
            case "warn":
            case "warning":
                return new LogLevels(Level.WARNING, ch.qos.logback.classic.Level.WARN, "WARN");
            case "info":
                return new LogLevels(Level.INFO, ch.qos.logback.classic.Level.INFO, "INFO");
            case "debug":
                return new LogLevels(Level.ALL, ch.qos.logback.classic.Level.DEBUG, "DEBUG");
            case "trace":
                return new LogLevels(Level.ALL, ch.qos.logback.classic.Level.TRACE, "TRACE");
            default:
                throw new ParseException("Invalid --log-level '" + levelArg
                        + "'. Use one of: error, warn, info, debug, trace.");
        }
    }

    /** The effective level: --log-level, else --debug, else info. */
    /**
     * {@code --log-format} straight off the command line, for use before an App exists.
     *
     * <p>Validated here as well as in applyOptions, because --doctor never reaches applyOptions
     * and a typo would otherwise fall back to text without a word.
     */
    static String requestedLogFormat(CommandLine commandLine) throws ParseException {
        String value = commandLine.getOptionValue("log-format");
        if (value == null) {
            return "text";
        }
        String normalised = value.trim().toLowerCase(java.util.Locale.ROOT);
        if (!normalised.equals("text") && !normalised.equals("json")) {
            throw new ParseException("Invalid --log-format '" + normalised
                    + "'. Expected text or json.");
        }
        return normalised;
    }

    static String requestedLogLevel(CommandLine commandLine) {
        String levelArg = commandLine.getOptionValue("log-level");
        if (levelArg == null && commandLine.hasOption("debug")) {
            levelArg = "debug";
        }
        return levelArg == null ? "info" : levelArg;
    }

    static void applyOptions(App app, CommandLine commandLine) throws Exception {
        // Parsed here purely to validate: a typo should be a startup error with the usual
        // CLI wording, not an IllegalArgumentException from inside proxy construction.
        if (commandLine.hasOption("log-format")) {
            String value = commandLine.getOptionValue("log-format").trim().toLowerCase(java.util.Locale.ROOT);
            if (!value.equals("text") && !value.equals("json")) {
                throw new ParseException("Invalid --log-format '" + value
                        + "'. Expected text or json.");
            }
            app.setLogFormat(value);
        }

        if (commandLine.hasOption("log-http")) {
            String value = commandLine.getOptionValue("log-http").trim();
            try {
                com.testingbot.tunnel.proxy.LogHttpPolicy.parse(value);
            } catch (IllegalArgumentException unknown) {
                throw new ParseException(unknown.getMessage());
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

        if (commandLine.hasOption("ws-proxy-mode")) {
            String value = commandLine.getOptionValue("ws-proxy-mode").trim();
            if (!value.equalsIgnoreCase("connect") && !value.equalsIgnoreCase("get")) {
                throw new ParseException("Invalid --ws-proxy-mode value: " + value
                        + ". Expected connect or get.");
            }
            app.setWsProxyMode(value.toLowerCase(java.util.Locale.ROOT));
        }

        if (commandLine.hasOption("connect-to")) {
            app.setConnectTo(commandLine.getOptionValue("connect-to").split(","));
        }

        if (commandLine.hasOption("allow-hosts")) {
            try {
                app.allowedHosts = com.testingbot.tunnel.proxy.AllowedHosts.parse(
                        commandLine.getOptionValue("allow-hosts"));
            } catch (IllegalArgumentException invalid) {
                throw new ParseException(invalid.getMessage());
            }
            Logger.getLogger(App.class.getName()).log(Level.INFO,
                    "Tunnel may only reach: {0}", app.allowedHosts);
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
            JsonNode obj;
            try {
                obj = mapper.readTree(extraHeadersValue);
            } catch (com.fasterxml.jackson.core.JacksonException malformed) {
                throw new ParseException("Invalid --extra-headers: expected a JSON object such as"
                        + " '{\"X-Header\":\"value\"}'. " + malformed.getOriginalMessage());
            }
            if (obj == null || !obj.isObject()) {
                throw new ParseException("Invalid --extra-headers: expected a JSON object such as"
                        + " '{\"X-Header\":\"value\"}'.");
            }

            Iterator<String> keyIterator = obj.fieldNames();
            while (keyIterator.hasNext()) {
                String key = keyIterator.next();
                String value = obj.get(key).asText();
                validateHeader(key, value);
                app.addCustomHeader(key, value);
            }
        }

        if (commandLine.hasOption("metrics-port")) {
            app.setMetricsPort(port(commandLine, "metrics-port"));
        }

        if (commandLine.hasOption("bind-address")) {
            app.setBindAddress(commandLine.getOptionValue("bind-address"));
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
            try {
                app.setProxyAuth(proxyAuthValue);
            } catch (IllegalArgumentException invalid) {
                // IllegalArgumentException is not caught by main, so a mistyped
                // value produced a stack trace where every other option manages a
                // one-line message.
                throw new ParseException(invalid.getMessage());
            }
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

        // Parsed together so the two options cannot disagree about what was asked for.
        try {
            app.bumpPolicy = com.testingbot.tunnel.proxy.BumpPolicy.parse(
                    app.noBump, commandLine.getOptionValue("nobump-domains"));
        } catch (IllegalArgumentException invalid) {
            // The convention here is a readable message, not a stack trace out of a
            // constructor: this is a path users reach by mistyping a flag.
            throw new ParseException(invalid.getMessage());
        }
        if (app.noBump && commandLine.hasOption("nobump-domains")) {
            Logger.getLogger(App.class.getName()).log(Level.WARNING,
                    "--nobump-domains is ignored because --nobump already disables SSL bumping "
                    + "for every host.");
        } else if (!app.bumpPolicy.domains().isEmpty()) {
            Logger.getLogger(App.class.getName()).log(Level.INFO,
                    "SSL bumping disabled for: {0}", app.bumpPolicy.toString());
        }

        if (commandLine.hasOption("shared")) {
            Logger.getLogger(App.class.getName()).log(Level.INFO, "Tunnel will be shared among team members.");
            app.shared = true;
        }

        if (commandLine.hasOption("hubport")) {
            app.hubPort = port(commandLine, "hubport");
            if ((app.hubPort != 80) && (app.hubPort != 4444)) {
                throw new ParseException("The hub port must either be 80 or 4444");
            }
        }

        app.httpDialTimeoutSeconds = positiveSeconds(commandLine, "http-dial-timeout");
        app.httpIdleTimeoutSeconds = positiveSeconds(commandLine, "http-idle-timeout");

        if (commandLine.hasOption("dns")) {
            // Note: this used to set sun.net.spi.nameservice.*, the JDK 8 pluggable
            // nameservice SPI. That SPI was removed in JDK 9, so the option silently did
            // nothing for years. Resolution now goes through CustomDnsResolver, which the
            // proxy and CONNECT paths consult when dialling.
            app.setDnsServer(commandLine.getOptionValue("dns"));
        }

        if (commandLine.hasOption("dns-timeout")) {
            String value = commandLine.getOptionValue("dns-timeout").trim();
            try {
                long seconds = Long.parseLong(value);
                if (seconds <= 0) {
                    throw new NumberFormatException(value);
                }
                app.setDnsTimeout(java.time.Duration.ofSeconds(seconds));
            } catch (NumberFormatException invalid) {
                throw new ParseException("Invalid --dns-timeout '" + value
                        + "': give a whole number of seconds greater than zero.");
            }
        }
        app.setDnsRoundRobin(commandLine.hasOption("dns-round-robin"));
        if (app.isDnsRoundRobin() && app.getDnsServer() == null) {
            Logger.getLogger(App.class.getName()).log(Level.WARNING,
                    "--dns-round-robin has no effect without --dns.");
        }

    }

    public static void main(String... args) throws Exception {
        if (!checkJavaVersion()) {
            System.exit(1);
        }

        final CommandLineParser cmdLinePosixParser = new PosixParser();
        final Options options = buildOptions();

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
            // Read from the command line rather than the App, which does not exist yet: the
            // console handler is installed before anything is parsed into an App.
            handler.setFormatter(logFormatterFor(requestedLogFormat(commandLine)));
            logger.addHandler(handler);
            if ("json".equalsIgnoreCase(requestedLogFormat(commandLine))) {
                // Sibling JUL loggers (HttpProxy, Doctor, SSHTunnel, the handlers) publish
                // through the root handlers. Reformatting only App's left stdout interleaving
                // JSON objects with SimpleFormatter's two-line text, which a one-object-per-line
                // collector mis-parses for the majority of records.
                for (java.util.logging.Handler rootHandler
                        : Logger.getLogger("").getHandlers()) {
                    rootHandler.setFormatter(new JsonLogFormatter());
                }
            }

            App app = new App();

            LogLevels levels = resolveLogLevels(requestedLogLevel(commandLine));
            Level julLevel = levels.jul();
            ch.qos.logback.classic.Level logbackLevel = levels.logback();
            String jettyLevel = levels.jetty();

            logger.setLevel(julLevel);
            System.setProperty("org.eclipse.jetty.LEVEL", jettyLevel);

            LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
            loggerContext.getLogger("ROOT").setLevel(logbackLevel);
            loggerContext.getLogger("org.apache.hc").setLevel(logbackLevel);
            // Never enable HttpClient wire/header logging: it dumps request bodies
            // (client_key/client_secret form fields) and Authorization headers in cleartext.
            loggerContext.getLogger("org.apache.hc.client5.http.wire").setLevel(ch.qos.logback.classic.Level.ERROR);
            loggerContext.getLogger("org.apache.hc.client5.http.headers").setLevel(ch.qos.logback.classic.Level.ERROR);

            boolean debugLike = levels.isDebugLike();
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
                    handlerFile.setFormatter(logFormatterFor(requestedLogFormat(commandLine)));
                    handlerFile.setLevel(Level.ALL);
                    // Attach to App's logger (useParentHandlers=false above) AND to the JUL root
                    // so messages from sibling loggers (HttpProxy, SSHTunnel, Doctor, ...) land in the file too.
                    logger.addHandler(handlerFile);
                    Logger.getLogger("").addHandler(handlerFile);

                    // The same file receives records from both logging stacks, so a json run
                    // must not leave logback emitting text into it. A real encoder rather than
                    // a pattern: escaping quotes with %replace silently did not work.
                    ch.qos.logback.core.encoder.Encoder<ch.qos.logback.classic.spi.ILoggingEvent>
                            encoder;
                    if ("json".equalsIgnoreCase(requestedLogFormat(commandLine))) {
                        JsonLogbackEncoder json = new JsonLogbackEncoder();
                        json.setContext(loggerContext);
                        encoder = json;
                    } else {
                        ch.qos.logback.classic.encoder.PatternLayoutEncoder pattern =
                                new ch.qos.logback.classic.encoder.PatternLayoutEncoder();
                        pattern.setContext(loggerContext);
                        pattern.setPattern("%d{ISO8601} [%thread] %-5level %logger{36} - %msg%n");
                        encoder = pattern;
                    }
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

            if (commandLine.hasOption("se-port")) {
                app.seleniumPort = port(commandLine, "se-port");
            }

            if (commandLine.hasOption("localproxy")) {
                app.setJettyPort(port(commandLine, "localproxy"));
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

            applyCredentials(app, commandLine);

            applyOptions(app, commandLine);

            if (commandLine.hasOption("web")) {
                new LocalWebServer(commandLine.getOptionValue("web"), app.getBindAddress());
            }

            app.init();
            app.boot();
            // The pid file lets an external supervisor stop this process; it is
            // only meaningful when running as a command line client.
            app.trackPid();
        } catch (org.apache.commons.cli.MissingArgumentException missingArgument) {
            // Commons CLI names the *short* option here, so "--proxy" with no value reported
            // "Missing argument for option: Y" -- a letter the user never typed.
            org.apache.commons.cli.Option option = missingArgument.getOption();
            String named = option != null && option.getLongOpt() != null
                    ? "--" + option.getLongOpt()
                    : (option != null ? "-" + option.getOpt() : "that option");
            System.err.println(named + " needs a value"
                    + (option != null && option.getArgName() != null
                        ? " (" + option.getArgName() + ")" : "") + ".");
            System.exit(2);
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

    /** Package-private so a test can stand in for the ~/.testingbot dotfile. */
    String[] getUserData() {
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
        // Set here, not only in main(): an embedder constructs App directly, leaving startTime at
        // zero, so uptime was reported as seconds since the epoch -- a number that keeps climbing
        // and so never looks obviously wrong. main() sets it earlier to cover startup too.
        if (Statistics.getStartTime() == 0) {
            Statistics.setStartTime(System.currentTimeMillis());
        }

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
        // Replace rather than stack: boot() runs again on a tunnel rebuild, and a second server
        // on the same port simply fails to bind.
        stopInsightServer();
        insightServer = new InsightServer(this);
    }

    private void stopInsightServer() {
        if (insightServer != null) {
            insightServer.stop();
            insightServer = null;
        }
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

        // The local proxy has to go too, and after the tunnel that feeds it. Leaving it running
        // kept its port bound, so the reconnect monitor's last resort -- stop() then boot() after
        // the retry limit -- could not rebind and the tunnel died instead of recovering. It also
        // leaked a Jetty server per App for anything embedding this.
        if (httpProxy != null) {
            httpProxy.stop();
            httpProxy = null;
        }

        stopInsightServer();

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
    static int pacTest(String location, String url) {
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
    static void applyUpstreamProxyOptions(App app, CommandLine commandLine) throws ParseException {
        // Parsed here rather than with the rest of the options because --doctor calls this
        // method directly and nothing else: a diagnostic that could not see the control proxy
        // or the extra authorities would report on a configuration the user is not running.
        if (commandLine.hasOption("krb5-hosts")) {
            try {
                app.negotiateHosts = com.testingbot.tunnel.proxy.NegotiateHosts.parse(
                        commandLine.getOptionValue("krb5-hosts"));
            } catch (IllegalArgumentException invalid) {
                throw new ParseException(invalid.getMessage());
            }
            if (!app.negotiateHosts.isEmpty()) {
                Logger.getLogger(App.class.getName()).log(Level.INFO,
                        "Kerberos credentials will be sent to: {0}", app.negotiateHosts);
                if (app.getKrb5KeyTab() == null) {
                    Logger.getLogger(App.class.getName()).log(Level.INFO,
                            "No --krb5-keytab given, so the ambient ticket cache will be used.");
                }
            }
        }

        if (commandLine.hasOption("cacert-file")) {
            try {
                app.setCaCertificates(com.testingbot.tunnel.proxy.CaCertificates.load(
                        commandLine.getOptionValues("cacert-file")));
            } catch (IllegalArgumentException invalid) {
                throw new ParseException(invalid.getMessage());
            }
            if (app.getCaCertificates() != null) {
                Logger.getLogger(App.class.getName()).log(Level.INFO,
                        "Trusting {0} additional certificate authority/authorities",
                        app.getCaCertificates().size());
            }
        }

        if (commandLine.hasOption("proxy-testingbot")) {
            String controlValue = commandLine.getOptionValue("proxy-testingbot").trim();
            if (com.testingbot.tunnel.proxy.ProxySpec.parse(controlValue) == null) {
                throw new ParseException("Invalid --proxy-testingbot '" + controlValue
                        + "'; expected host:port, http://host:port or socks5://host:port");
            }
            app.setControlProxy(controlValue);
            Logger.getLogger(App.class.getName()).log(Level.INFO,
                    "TestingBot API and SSH will use the upstream proxy {0}", controlValue);
        }
        if (commandLine.hasOption("proxy-testingbot-userpwd")) {
            String controlAuth = commandLine.getOptionValue("proxy-testingbot-userpwd");
            if (controlAuth == null || !controlAuth.contains(":")) {
                throw new ParseException(
                        "--proxy-testingbot-userpwd must be in the form user:password");
            }
            app.setControlProxyAuth(controlAuth);
        }
        if (!commandLine.hasOption("proxy-testingbot")
                && commandLine.hasOption("proxy-testingbot-userpwd")) {
            Logger.getLogger(App.class.getName()).log(Level.WARNING,
                    "--proxy-testingbot-userpwd has no effect without --proxy-testingbot.");
        }

        if (commandLine.hasOption("proxy")) {
                try {
                    app.setProxy(commandLine.getOptionValue("proxy"));
                } catch (IllegalArgumentException invalid) {
                    throw new ParseException(invalid.getMessage());
                }
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

    static int readinessPort(CommandLine commandLine) throws ParseException {
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

    /**
     * Public because the reconnect adapter's test lives in the ssh package. startProxies() owns
     * this in production; nothing else should be assigning it.
     */
    public void setHttpProxy(HttpProxy httpProxy) {
        this.httpProxy = httpProxy;
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

    /** {@code text} or {@code json}; see {@link JsonLogFormatter}. */
    public String getLogFormat() {
        return logFormat;
    }

    public void setLogFormat(String logFormat) {
        this.logFormat = logFormat == null ? "text" : logFormat;
    }

    /** The formatter for {@code --log-format}, shared by the console and the log file. */
    static java.util.logging.Formatter logFormatterFor(String logFormat) {
        return "json".equalsIgnoreCase(logFormat) ? new JsonLogFormatter() : new LogFormatter();
    }

    public void setLogHttp(String logHttp) {
        this.logHttp = logHttp;
        this.logHttpPolicy = com.testingbot.tunnel.proxy.LogHttpPolicy.parse(logHttp);
    }

    /**
     * {@code --log-http} resolved per module.
     *
     * <p>Held rather than reparsed per request: this is consulted on the request path, and the
     * value cannot change once the process is running.
     */
    public com.testingbot.tunnel.proxy.LogHttpPolicy getLogHttpPolicy() {
        return logHttpPolicy;
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

    public String getWsProxyMode() {
        return wsProxyMode;
    }

    public void setWsProxyMode(String wsProxyMode) {
        this.wsProxyMode = wsProxyMode == null || wsProxyMode.isBlank() ? "connect" : wsProxyMode;
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

    /** Which hosts should not be bumped; see {@link com.testingbot.tunnel.proxy.BumpPolicy}. */
    public com.testingbot.tunnel.proxy.BumpPolicy getBumpPolicy() {
        return bumpPolicy;
    }

    /** For embedding and tests: set the policy without going through the command line. */
    public void setBumpPolicy(com.testingbot.tunnel.proxy.BumpPolicy bumpPolicy) {
        this.bumpPolicy = bumpPolicy == null
                ? com.testingbot.tunnel.proxy.BumpPolicy.parse(false, null) : bumpPolicy;
        this.noBump = this.bumpPolicy.isAllDomains();
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
            // Scoped to the proxy when it is known. Before this the authenticator answered
            // every request in the JVM with these credentials, whoever was asking.
            com.testingbot.tunnel.proxy.ProxySpec spec =
                    com.testingbot.tunnel.proxy.ProxySpec.parse(this.proxy);
            Authenticator previousDefault = Authenticator.getDefault();
            Authenticator.setDefault(spec == null
                    ? new ProxyAuth(splitted[0], splitted[1], null, -1, previousDefault)
                    : new ProxyAuth(splitted[0], splitted[1], spec.getHost(), spec.getPort(),
                                    previousDefault));
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

    /**
     * A TCP port for {@code option}, validated.
     *
     * <p>These used to be a bare {@code Integer.parseInt}, so a typo produced a raw
     * NumberFormatException stack trace, and a number outside the port range was accepted and
     * failed much later as a bind error that named nothing the user had typed.
     */
    static int port(CommandLine commandLine, String option) throws ParseException {
        String value = commandLine.getOptionValue(option).trim();
        int parsed;
        try {
            parsed = Integer.parseInt(value);
        } catch (NumberFormatException notANumber) {
            throw new ParseException("Invalid --" + option + " '" + value
                    + "': expected a port number between 1 and 65535.");
        }
        if (parsed < 1 || parsed > 65535) {
            throw new ParseException("Invalid --" + option + " '" + value
                    + "': a port must be between 1 and 65535.");
        }
        return parsed;
    }

    /** A whole number of seconds greater than zero, or null when the option is absent. */
    static Integer positiveSeconds(CommandLine commandLine, String option) throws ParseException {
        if (!commandLine.hasOption(option)) {
            return null;
        }
        String value = commandLine.getOptionValue(option).trim();
        try {
            int seconds = Integer.parseInt(value);
            if (seconds <= 0) {
                throw new NumberFormatException(value);
            }
            return seconds;
        } catch (NumberFormatException invalid) {
            throw new ParseException("Invalid --" + option + " '" + value
                    + "': give a whole number of seconds greater than zero.");
        }
    }

    /**
     * {@code --http-dial-timeout}, or null to keep each path's own default.
     *
     * <p>Null rather than a value: the defaults differ by path on purpose -- the API should give
     * up quickly, a tunnelled connection should not -- and one number for both would be worse
     * than either.
     */
    public Integer getHttpDialTimeoutSeconds() {
        return httpDialTimeoutSeconds;
    }

    public void setHttpDialTimeoutSeconds(Integer seconds) {
        this.httpDialTimeoutSeconds = seconds;
    }

    /** {@code --http-idle-timeout}, or null to keep each path's own default. */
    public Integer getHttpIdleTimeoutSeconds() {
        return httpIdleTimeoutSeconds;
    }

    public void setHttpIdleTimeoutSeconds(Integer seconds) {
        this.httpIdleTimeoutSeconds = seconds;
    }

    /** The hosts this tunnel may reach; unrestricted unless {@code --allow-hosts} was given. */
    public com.testingbot.tunnel.proxy.AllowedHosts getAllowedHosts() {
        return allowedHosts;
    }

    public void setAllowedHosts(com.testingbot.tunnel.proxy.AllowedHosts allowedHosts) {
        this.allowedHosts = allowedHosts == null
                ? com.testingbot.tunnel.proxy.AllowedHosts.unrestricted() : allowedHosts;
    }

    /** Hosts that may receive SPNEGO credentials; empty unless {@code --krb5-hosts} was given. */
    public com.testingbot.tunnel.proxy.NegotiateHosts getNegotiateHosts() {
        return negotiateHosts;
    }

    public void setNegotiateHosts(com.testingbot.tunnel.proxy.NegotiateHosts negotiateHosts) {
        this.negotiateHosts = negotiateHosts == null
                ? com.testingbot.tunnel.proxy.NegotiateHosts.none() : negotiateHosts;
    }

    /** Extra certificate authorities from {@code --cacert-file}, or null when none. */
    public com.testingbot.tunnel.proxy.CaCertificates getCaCertificates() {
        return caCertificates;
    }

    /** For embedding and tests. */
    public void setCaCertificates(com.testingbot.tunnel.proxy.CaCertificates caCertificates) {
        this.caCertificates = caCertificates;
    }

    /**
     * The upstream proxy for reaching TestingBot: the API and the SSH control connection.
     *
     * <p>Falls back to {@code --proxy} so that the common case -- one proxy for everything --
     * needs no extra flag, and so this does not change behaviour for anyone already using it.
     *
     * <p>Splitting the two matters on networks where the proxy that may reach the public
     * internet is not the proxy that reaches internal test targets, which is a normal shape
     * once egress is filtered by destination.
     */
    public String getControlProxy() {
        return controlProxy != null ? controlProxy : proxy;
    }

    public void setControlProxy(String controlProxy) {
        this.controlProxy = controlProxy;
    }

    /**
     * Credentials for {@link #getControlProxy()}.
     *
     * <p>Deliberately not inherited from {@code --proxy-userpwd} when a separate control proxy
     * is configured: those credentials were issued for a different host, and sending them on
     * would hand one proxy operator the credentials for another.
     */
    public String getControlProxyAuth() {
        return controlProxy != null ? controlProxyAuth : getProxyAuth();
    }

    public void setControlProxyAuth(String controlProxyAuth) {
        this.controlProxyAuth = controlProxyAuth;
    }

    /** True when control traffic and test traffic use different proxies. */
    public boolean hasSeparateControlProxy() {
        return controlProxy != null;
    }

    /** The authenticator for the control proxy, which may differ from the test-traffic one. */
    public com.testingbot.tunnel.proxy.ProxyAuthenticator controlProxyAuthenticator() {
        if (controlProxy == null) {
            return proxyAuthenticator();
        }
        return com.testingbot.tunnel.proxy.ProxyAuthenticator.create(
                com.testingbot.tunnel.proxy.ProxyAuthenticator.Scheme.parse(proxyAuthScheme),
                getControlProxyAuth(), proxySpn,
                krb5KeyTab == null ? null : java.nio.file.Path.of(krb5KeyTab),
                krb5Principal);
    }

    /** Per-query timeout for the {@code --dns} servers. */
    public java.time.Duration getDnsTimeout() {
        return dnsTimeout;
    }

    public void setDnsTimeout(java.time.Duration dnsTimeout) {
        this.dnsTimeout = dnsTimeout == null
                ? com.testingbot.tunnel.proxy.CustomDnsResolver.DEFAULT_QUERY_TIMEOUT : dnsTimeout;
    }

    /** True when queries should be spread across the {@code --dns} servers. */
    public boolean isDnsRoundRobin() {
        return dnsRoundRobin;
    }

    public void setDnsRoundRobin(boolean dnsRoundRobin) {
        this.dnsRoundRobin = dnsRoundRobin;
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
     * @return the interface every local listener binds to, never null
     */
    public String getBindAddress() {
        return bindAddress;
    }

    /**
     * @param bindAddress the interface for every local listener; blank restores the default
     */
    public void setBindAddress(String bindAddress) {
        if (bindAddress == null || bindAddress.trim().isEmpty()) {
            this.bindAddress = DEFAULT_BIND_ADDRESS;
            return;
        }
        this.bindAddress = bindAddress.trim();
        if (!isLoopbackBind(this.bindAddress)) {
            // Not refused: reaching the tunnel from another host is a legitimate setup. But it
            // is the one that hands an unauthenticated relay and forward proxy to the network,
            // so it is never entered silently.
            Logger.getLogger(App.class.getName()).log(Level.WARNING,
                "Listeners will bind {0}, so other hosts can reach the Selenium relay and the "
                    + "local proxy. Both are unauthenticated: the relay forwards requests with "
                    + "your TestingBot key and secret attached, and the proxy will connect "
                    + "anywhere this machine can, including its own loopback. Restrict access "
                    + "to this port or bind 127.0.0.1 instead.",
                this.bindAddress);
        }
    }

    /** True when {@code address} names this machine's loopback interface. */
    private static boolean isLoopbackBind(String address) {
        try {
            return java.net.InetAddress.getByName(address).isLoopbackAddress();
        } catch (java.net.UnknownHostException ex) {
            // Unresolvable here means the bind will fail with a clearer message than anything
            // this check could produce, so let it get that far rather than guessing.
            return false;
        }
    }

    /**
     * @param metricsPort the metricsPort to set
     */
    /** Companion to {@link #setJettyPort}; --hubport sets this. */
    public void setHubPort(int hubPort) {
        this.hubPort = hubPort;
    }

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
