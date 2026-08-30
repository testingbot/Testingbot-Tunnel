package com.testingbot.tunnel;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;

/**
 * Lets every command-line option also be given as a {@code TESTINGBOT_*} environment variable.
 *
 * <p>Containers and CI systems configure processes with environment variables, not argv. Until
 * now only a handful of settings had an environment equivalent, so a Docker or CI user who
 * needed any other flag had to build a custom command line -- the one part of a compose file or
 * pipeline definition that cannot be set from the surrounding configuration.
 *
 * <p>The name is derived from the long option: {@code --se-port} reads {@code TESTINGBOT_SE_PORT}.
 * That scheme reproduces the names of the variables that already existed
 * ({@code TESTINGBOT_AUTH}, {@code TESTINGBOT_METRICS_AUTH},
 * {@code TESTINGBOT_PROXY_USERPWD}), so nothing users already set changes meaning.
 *
 * <p>Precedence is command line, then config file, then environment. The environment is the most
 * ambient of the three and the least likely to be what someone means when they also typed a flag
 * or wrote a config file. This matches how the pre-existing variables behaved, which were only
 * consulted when the corresponding flag was absent.
 */
public final class EnvOptions {

    static final String PREFIX = "TESTINGBOT_";

    /**
     * Options whose environment variables are read elsewhere, with semantics this generic
     * expansion would change.
     *
     * <p>{@code TESTINGBOT_AUTH} is comma-separated into several {@code --auth} values, and the
     * credentials are positional rather than flags. Expanding them here would turn a
     * comma-separated list into one malformed value.
     */
    private static final Set<String> HANDLED_ELSEWHERE = Set.of("auth");

    /** Options that would make no sense to set for every run in an environment. */
    private static final Set<String> NEVER = Set.of("help", "version", "doctor", "ready", "config");

    private EnvOptions() {
    }

    /** Convenience overload reading the real environment. */
    public static String[] expand(String[] args, Options options) {
        return expand(args, options, System.getenv());
    }

    /**
     * Appends flags for any option set in {@code environment} but absent from {@code args}.
     *
     * @param args the command line, already expanded from any {@code --config} file
     */
    static String[] expand(String[] args, Options options, Map<String, String> environment) {
        if (options == null || environment.isEmpty()) {
            return args;
        }

        List<String> out = new ArrayList<>(List.of(args));
        for (Option option : options.getOptions()) {
            String longOpt = option.getLongOpt();
            if (longOpt == null || NEVER.contains(longOpt) || HANDLED_ELSEWHERE.contains(longOpt)) {
                continue;
            }
            if (ConfigFile.isPresent(args, longOpt, options)) {
                // Explicitly given on the command line or via --config; those win.
                continue;
            }

            String value = environment.get(variableName(longOpt));
            if (value == null || value.trim().isEmpty()) {
                continue;
            }
            if (option.hasArg()) {
                out.add("--" + longOpt);
                out.add(value.trim());
            } else if (ConfigFile.isTrue(value)) {
                out.add("--" + longOpt);
            }
        }
        return out.toArray(new String[0]);
    }

    /** {@code se-port} to {@code TESTINGBOT_SE_PORT}. */
    static String variableName(String longOpt) {
        return PREFIX + longOpt.replace('-', '_').toUpperCase(Locale.ROOT);
    }
}
