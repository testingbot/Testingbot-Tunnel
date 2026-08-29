package com.testingbot.tunnel;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.cli.PosixParser;

/**
 * Loads tunnel settings from a {@code key = value} properties file.
 *
 * <p>Keys are the long option names without dashes, so the file mirrors the command line
 * one-for-one:
 *
 * <pre>
 * # testingbot.conf
 * se-port      = 4445
 * localproxy   = 8087
 * proxy        = socks5://corp-proxy:1080
 * nobump       = true
 * fast-fail-regexps = ads\\.example\\.com,tracker\\..*
 * </pre>
 *
 * <p>Rather than being consulted at each lookup, entries are expanded into the argument
 * list before parsing. That keeps one rule for users -- a config entry behaves exactly as
 * if the flag had been typed -- and means an explicit flag always wins, because the
 * command line is parsed after.
 */
public final class ConfigFile {

    /** Keys that are not CLI options but are accepted for convenience. */
    static final String KEY_CLIENT_KEY = "client-key";
    static final String KEY_CLIENT_SECRET = "client-secret";

    private ConfigFile() {
    }

    /**
     * Expands {@code --config <file>} into the arguments it represents.
     *
     * @return the arguments with config entries appended, or {@code args} unchanged when no
     *         config file was requested
     * @throws ParseException if the file is missing, unreadable, or contains unknown keys
     */
    public static String[] expand(String[] args, Options options) throws ParseException {
        String path = findValue(args, "config");
        if (path == null) {
            return args;
        }

        Properties properties = read(Path.of(path));
        List<String> merged = new ArrayList<>(List.of(args));
        List<String> trailing = new ArrayList<>();

        for (Map.Entry<String, String> entry : ordered(properties).entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();

            if (KEY_CLIENT_KEY.equals(key) || KEY_CLIENT_SECRET.equals(key)) {
                // Credentials are positional arguments, not flags.
                trailing.add(value);
                continue;
            }

            Option option = options.getOption(key);
            if (option == null) {
                throw new ParseException("Unknown setting '" + key + "' in config file " + path);
            }
            if (isPresent(args, key, options)) {
                // An explicit command-line flag beats the file.
                continue;
            }
            if (option.hasArg()) {
                merged.add("--" + key);
                merged.add(value);
            } else if (isTrue(value)) {
                merged.add("--" + key);
            }
        }

        // Positional credentials go at the very FRONT.
        //
        // Appending them last lets any preceding option declared with UNLIMITED_VALUES (--auth)
        // swallow them, whether that option came from the config file or the command line, and
        // the tunnel then starts with no key -- reporting it in a message that echoes the API
        // key. A "--" terminator does not help: PosixParser, which App uses, hands "--" itself
        // to a multi-valued option when it directly follows one. Leading positional arguments
        // are the one arrangement every commons-cli parser reads the same way.
        List<String> out = new ArrayList<>();
        if (!commandLineSuppliesCredentials(args, options)) {
            out.addAll(trailing);
        }
        out.addAll(List.of(args));
        out.addAll(merged.subList(args.length, merged.size()));
        return out.toArray(new String[0]);
    }

    /**
     * True when the command line already carries the key and secret positionally.
     *
     * <p>The command line beats the config file everywhere else, so it must here too --
     * otherwise both pairs end up positional and the parser reads the key as the secret.
     */
    private static boolean commandLineSuppliesCredentials(String[] args, Options options) {
        try {
            return new PosixParser().parse(options, args).getArgs().length >= 2;
        } catch (ParseException malformed) {
            // Not our error to report: the real parse in App will raise it with full context.
            return false;
        }
    }

    /** Preserves client-key before client-secret, since they are positional. */
    private static Map<String, String> ordered(Properties properties) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String key : new String[]{KEY_CLIENT_KEY, KEY_CLIENT_SECRET}) {
            if (properties.containsKey(key)) {
                out.put(key, properties.getProperty(key).trim());
            }
        }
        for (String name : properties.stringPropertyNames()) {
            if (!out.containsKey(name)) {
                out.put(name.trim(), properties.getProperty(name).trim());
            }
        }
        return out;
    }

    static Properties read(Path path) throws ParseException {
        if (!Files.isReadable(path)) {
            throw new ParseException("Config file not found or not readable: " + path);
        }
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(path)) {
            properties.load(in);
        } catch (IOException ex) {
            throw new ParseException("Could not read config file " + path + ": " + ex.getMessage());
        }
        return properties;
    }

    static boolean isTrue(String value) {
        if (value == null) {
            return false;
        }
        String v = value.trim();
        return v.equalsIgnoreCase("true") || v.equals("1") || v.equalsIgnoreCase("yes")
                || v.equalsIgnoreCase("on") || v.isEmpty();
    }

    /**
     * True when the option already appears on the command line, in long or short form.
     *
     * <p>Short aliases matter: nearly every option has one ({@code -P}, {@code -a}, {@code -Y}),
     * and missing them meant a config entry was not suppressed, so both ended up parsed and the
     * documented "command line wins" rule quietly did not hold.
     */
    static boolean isPresent(String[] args, String key, Options options) {
        String shortOpt = null;
        if (options != null) {
            Option option = options.getOption(key);
            if (option != null) {
                shortOpt = option.getOpt();
            }
        }
        for (String arg : args) {
            if (arg == null) {
                continue;
            }
            if (arg.equals("--" + key) || arg.equals("-" + key)
                    || arg.startsWith("--" + key + "=")) {
                return true;
            }
            if (shortOpt != null && !shortOpt.equals(key)
                    && (arg.equals("-" + shortOpt) || arg.startsWith("-" + shortOpt + "="))) {
                return true;
            }
        }
        return false;
    }

    static String findValue(String[] args, String key) {
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg == null) {
                continue;
            }
            if ((arg.equals("--" + key) || arg.equals("-" + key)) && i + 1 < args.length) {
                return args[i + 1];
            }
            if (arg.startsWith("--" + key + "=")) {
                return arg.substring(key.length() + 3);
            }
        }
        return null;
    }
}
