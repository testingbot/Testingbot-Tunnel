package ssh;

import com.testingbot.tunnel.App;

/**
 * Test-only access to the SSHTunnel constructor that takes a port and hub host.
 *
 * <p>Those two are fixed in production, so the constructor carrying them is package-private.
 * Tests outside this package -- anything that boots a whole {@link App} against an in-process SSH
 * server -- need it without widening the production API.
 */
public final class TestTunnels {

    private TestTunnels() {
    }

    public static SSHTunnel connect(App app, String server, int sshPort, String hubHost)
            throws Exception {
        return new SSHTunnel(app, server, sshPort, hubHost);
    }

    /**
     * @param remoteProxyPort where the tunnel server listens for browser traffic; two tunnel
     *                        servers in one JVM cannot both bind the production 2010
     */
    public static SSHTunnel connect(App app, String server, int sshPort, String hubHost,
                                    int remoteProxyPort) throws Exception {
        return new SSHTunnel(app, server, sshPort, hubHost, remoteProxyPort);
    }
}
