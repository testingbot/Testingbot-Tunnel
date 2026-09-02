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
    public static int free() {
        for (int attempt = 0; attempt < 200; attempt++) {
            int candidate;
            try (ServerSocket probe = new ServerSocket(0)) {
                candidate = probe.getLocalPort();
            } catch (IOException cannotProbe) {
                continue;
            }
            if (!HANDED_OUT.add(candidate)) {
                continue;                       // already promised to another test
            }
            if (inUse(candidate)) {
                continue;                       // kept reserved: something still holds it
            }
            return candidate;
        }
        throw new UncheckedIOException(
                new IOException("No free port available after 200 attempts"));
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
