package com.testingbot.tunnel;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;

/**
 * Queries a running tunnel's {@code /readyz} endpoint on behalf of {@code --ready}.
 *
 * <p>Docker's HEALTHCHECK and Kubernetes' exec probes want a command with an exit code. The
 * {@code --readyfile} we have offered until now cannot express "was ready, has since dropped":
 * the file is created once and never removed while the process lives, so a tunnel that lost its
 * SSH connection still looks ready on disk. Asking the process itself answers the current state.
 *
 * <p>Runs in its own short-lived JVM, so it deliberately depends on nothing but the JDK.
 */
public final class ReadinessProbe {

    /** Long enough to cover a loaded machine, short enough for a probe interval. */
    static final int DEFAULT_TIMEOUT_MS = 5_000;

    private ReadinessProbe() {
    }

    /**
     * @return {@code 0} when the tunnel is ready, {@code 1} otherwise -- including when nothing
     *         is listening, which is the normal answer before the tunnel has started.
     */
    public static int probe(String host, int port, int timeoutMs) {
        HttpURLConnection connection = null;
        try {
            URI uri = URI.create("http://" + host + ":" + port + "/readyz");
            connection = (HttpURLConnection) uri.toURL().openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(timeoutMs);
            connection.setReadTimeout(timeoutMs);
            int status = connection.getResponseCode();
            if (status == 200) {
                return 0;
            }
            System.err.println(status == 503
                    ? "Tunnel is not ready yet."
                    : "Unexpected status " + status + " from /readyz on port " + port + ".");
            return 1;
        } catch (IOException unreachable) {
            System.err.println("Could not reach the tunnel's metrics port " + port + " on " + host
                    + ": " + unreachable.getMessage()
                    + "\nIs the tunnel running, and does --metrics-port match?");
            return 1;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }
}
