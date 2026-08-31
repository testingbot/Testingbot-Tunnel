package ssh;

import com.testingbot.tunnel.App;

/**
 * The part of {@link App} that {@link CustomConnectionMonitor} needs: the local proxy has to be
 * stopped while the SSH connection is down and started again once it returns, and after enough
 * failed attempts the whole tunnel is rebuilt against a different server.
 */
public interface ReconnectHost {

    /** Stops the local proxy, if one is running. */
    void stopLocalProxy();

    /** Starts the local proxy again after a successful reconnect. */
    void startLocalProxy();

    /** Tears the tunnel down and builds a new one, typically against a different server. */
    void rebuildTunnel() throws Exception;

    /** The production adapter. */
    static ReconnectHost of(App app) {
        return new ReconnectHost() {
            @Override
            public void stopLocalProxy() {
                if (app.getHttpProxy() != null) {
                    app.getHttpProxy().stop();
                }
            }

            @Override
            public void startLocalProxy() {
                if (app.getHttpProxy() != null) {
                    app.getHttpProxy().start();
                }
            }

            @Override
            public void rebuildTunnel() throws Exception {
                app.stop();
                app.boot();
            }
        };
    }
}
