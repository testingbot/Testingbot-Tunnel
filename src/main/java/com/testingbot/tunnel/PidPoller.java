package com.testingbot.tunnel;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;
import ssh.Scheduler;

/**
 * Watches a pid file so an external supervisor can stop the tunnel by deleting it.
 *
 * <p>The shutdown action and the scheduler are injectable. Without that the only observable
 * behaviour was {@code System.exit(0)} on a five-second timer, which is untestable in-process --
 * so the file naming, the "already exists" path and the removal detection all went unverified,
 * even though a wrong file name means a supervisor silently cannot stop the tunnel at all.
 *
 * @author testingbot
 */
public class PidPoller {

    private static final Logger LOG = Logger.getLogger(PidPoller.class.getName());

    static final long POLL_INTERVAL_MS = 5000;

    private final File pidFile;
    private final Scheduler scheduler;
    private final Runnable onRemoved;
    private final Thread cleanupThread;
    private boolean started;

    public PidPoller(App app) {
        this(new File(pidFileName(app.getTunnelIdentifier())), Scheduler.timerBased(),
                () -> System.exit(0), true);
    }

    /**
     * @param registerShutdownHook false in tests, where a hook per instance would accumulate and
     *                             delete files belonging to the surrounding process
     */
    PidPoller(File pidFile, Scheduler scheduler, Runnable onRemoved, boolean registerShutdownHook) {
        this.pidFile = pidFile;
        this.scheduler = scheduler;
        this.onRemoved = onRemoved;
        this.cleanupThread = new Thread(() -> deleteQuietly(pidFile));

        if (!createIfAbsent()) {
            return;
        }
        if (registerShutdownHook) {
            Runtime.getRuntime().addShutdownHook(cleanupThread);
        }
        scheduler.scheduleRepeating("PidPoller", this::poll, POLL_INTERVAL_MS, POLL_INTERVAL_MS);
        started = true;
    }

    /** {@code testingbot-tunnel.pid}, or one per tunnel when an identifier is set. */
    static String pidFileName(String tunnelIdentifier) {
        if (tunnelIdentifier == null || tunnelIdentifier.isEmpty()) {
            return "testingbot-tunnel.pid";
        }
        // Without this, two tunnels on one machine share a pid file and deleting it stops
        // whichever happens to notice first.
        return "testingbot-tunnel-" + tunnelIdentifier + ".pid";
    }

    /** @return false when the file could not be created, in which case polling never starts */
    private boolean createIfAbsent() {
        if (pidFile.exists()) {
            return true;
        }
        try (FileWriter fw = new FileWriter(pidFile.getAbsoluteFile());
             BufferedWriter bw = new BufferedWriter(fw)) {
            bw.write("TestingBot Tunnel, Remove this file to shutdown the tunnel");
            LOG.log(Level.INFO, "Pid file: {0}", pidFile.getAbsoluteFile().toString());
            return true;
        } catch (IOException ex) {
            LOG.log(Level.SEVERE, "Can't create testingbot pidfile in current directory", ex);
            return false;
        }
    }

    /** One poll. Package-private so a test can run it without waiting for the interval. */
    void poll() {
        if (!pidFile.exists()) {
            LOG.log(Level.INFO, "{0} pidFile was removed, shutting down Tunnel", pidFile.toString());
            scheduler.cancel();
            onRemoved.run();
        }
    }

    public void cancel() {
        if (started) {
            try {
                Runtime.getRuntime().removeShutdownHook(cleanupThread);
            } catch (IllegalStateException alreadyShuttingDown) {
                // The JVM is on its way down and will run the hook itself.
            }
        }
        scheduler.cancel();
    }

    boolean isStarted() {
        return started;
    }

    private static void deleteQuietly(File file) {
        if (file.exists() && !file.delete()) {
            LOG.log(Level.WARNING, "Could not delete pid file: {0}", file.toString());
        }
    }
}
