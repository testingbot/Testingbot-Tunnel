package ssh;

/**
 * The part of {@link SSHTunnel} that {@link CustomConnectionMonitor} drives when a connection
 * drops.
 *
 * <p>Exists so the reconnect logic can be tested against a stand-in rather than a real SSH
 * server. What the monitor decides -- how many times to retry, when to give up and rebuild the
 * tunnel from scratch, what to do on success -- is behaviour a customer feels directly on a
 * flaky network, and it was previously reachable only by actually breaking a live connection.
 */
public interface ReconnectableTunnel {

    String getConnectionId();

    /** True once a deliberate shutdown has begun, so a drop should not trigger a reconnect. */
    boolean isShuttingDown();

    void stop();

    void connect() throws Exception;

    boolean isAuthenticated();

    void createPortForwarding();
}
