package ssh;

import com.jcraft.jsch.HostKey;
import com.jcraft.jsch.HostKeyRepository;
import com.jcraft.jsch.UserInfo;

/**
 * A {@link HostKeyRepository} that accepts exactly the pinned keys and nothing else.
 *
 * <p>Deliberately not a known-hosts file. A known-hosts repository learns: JSch's own
 * implementation adds a key on first sight when strict checking is off, which is what made the
 * original {@code StrictHostKeyChecking=no} acceptable-looking. Here {@link #add} is a no-op, so
 * a key that was not configured can never become trusted by having been offered once.
 */
final class PinnedHostKeyRepository implements HostKeyRepository {

    private final HostKeyPins pins;

    PinnedHostKeyRepository(HostKeyPins pins) {
        this.pins = pins;
    }

    @Override
    public int check(String host, byte[] key) {
        if (pins.matches(key)) {
            return OK;
        }
        // CHANGED rather than NOT_INCLUDED: pins are configured, so a key that is not among them
        // is a wrong key, not an unknown one. JSch's message for CHANGED says the host identity
        // has changed, which is what actually happened from this client's point of view.
        return CHANGED;
    }

    /** Never learns. A key is trusted because it was configured, not because it was offered. */
    @Override
    public void add(HostKey hostKey, UserInfo ui) {
        // no-op
    }

    @Override
    public void remove(String host, String type) {
        // no-op
    }

    @Override
    public void remove(String host, String type, byte[] key) {
        // no-op
    }

    @Override
    public String getKnownHostsRepositoryID() {
        return "testingbot-pinned-host-keys";
    }

    @Override
    public HostKey[] getHostKey() {
        return new HostKey[0];
    }

    @Override
    public HostKey[] getHostKey(String host, String type) {
        return new HostKey[0];
    }
}
