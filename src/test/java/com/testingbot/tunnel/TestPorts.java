package com.testingbot.tunnel;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ports for tests to bind, allocated so that two tests cannot be handed the same one.
 *
 * <p>Every test file had its own copy of
 *
 * <pre>
 * try (ServerSocket s = new ServerSocket(0)) { return s.getLocalPort(); }
 * </pre>
 *
 * which opens a port, closes it, and returns the number -- leaving a window between the
 * allocation and the bind that follows. At 209 tests that was tolerable. At 780, with most of
 * the new ones standing up real servers, a full run failed roughly once in three: a 403 from a
 * proxy in one run, a BindException in another, each passing in isolation and neither
 * reproducible.
 *
 * <p>Two things close the window. A port already handed out in this JVM is never handed out
 * again, even after whoever had it has finished with it; and a port something is still listening
 * on is skipped, which covers a server from an earlier test that has not finished shutting down.
 */
public final class TestPorts {

    private static final Set<Integer> HANDED_OUT = ConcurrentHashMap.newKeySet();

    private TestPorts() {
    }

    /**
     * @return a port no other test in this JVM has been given and nothing is listening on
     * @throws UncheckedIOException if no such port can be found, which would mean something is
     *         badly wrong rather than merely busy
     */
    /**
     * Listen ports are taken from below the ephemeral range rather than from it.
     *
     * <p>{@code new ServerSocket(0)} allocates an ephemeral port -- the same pool the operating
     * system draws from for outbound connections. This suite makes thousands of those, so a port
     * that was free when probed could be taken by an outgoing socket before the server bound it,
     * which no amount of bookkeeping inside this class can prevent. Linux hands out 32768 and
     * above by default and macOS 49152 and above, so this range collides with neither.
     */
    private static final int RANGE_START = 20_000;
    private static final int RANGE_END = 31_999;

    private static final java.util.Random RANDOM = new java.util.Random();

    /**
     * @return a port no other test in this JVM has been given and nothing is listening on
     * @throws UncheckedIOException if no such port can be found, which would mean something is
     *         badly wrong rather than merely busy
     */
    public static int free() {
        for (int attempt = 0; attempt < 500; attempt++) {
            int candidate = RANGE_START + RANDOM.nextInt(RANGE_END - RANGE_START + 1);
            if (!HANDED_OUT.add(candidate)) {
                continue;                       // already promised to another test
            }
            // Binding it explicitly is the check: if this succeeds the port is genuinely
            // available, which probing with port 0 and hoping never established.
            try (ServerSocket probe = new ServerSocket(candidate, 1,
                    InetAddress.getLoopbackAddress())) {
                probe.getLocalPort();
            } catch (IOException taken) {
                continue;                       // stays reserved; something else holds it
            }
            if (inUse(candidate)) {
                continue;
            }
            return candidate;
        }
        throw new UncheckedIOException(
                new IOException("No free port available after 500 attempts"));
    }

    /** True when something accepts a connection here, so the port is not really free. */
    private static boolean inUse(int port) {
        try (Socket probe = new Socket()) {
            probe.connect(new java.net.InetSocketAddress(
                    InetAddress.getLoopbackAddress(), port), 100);
            return true;
        } catch (IOException nothingListening) {
            return false;
        }
    }
}
