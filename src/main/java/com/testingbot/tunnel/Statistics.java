package com.testingbot.tunnel;

import java.util.concurrent.atomic.LongAdder;
import java.util.function.LongSupplier;

/**
 * Process-wide tunnel counters, read by the status and metrics endpoints.
 *
 * <p>Counters are {@link LongAdder}s: every proxied request updates them, concurrently and
 * from many threads, so plain {@code long += } lost updates.
 */
public class Statistics {
    private static final LongAdder numberOfRequests = new LongAdder();
    private static final LongAdder bytesTransferred = new LongAdder();
    private static volatile long startTime = 0;

    /**
     * When the local proxy is running it supplies byte totals from the connector, which
     * counts tunnelled traffic too. Until then (and in tests) the manually accumulated
     * counter is used.
     */
    private static volatile LongSupplier bytesTransferredSupplier;

    /**
     * @return the numberOfRequests
     */
    public static long getNumberOfRequests() {
        return numberOfRequests.sum();
    }

    /**
     * @return the startTime
     */
    public static long getStartTime() {
        return startTime;
    }

    /**
     * @param aStartTime the startTime to set
     */
    public static void setStartTime(long aStartTime) {
        startTime = aStartTime;
    }

    /**
     * @return the bytesTransferred
     */
    public static long getBytesTransferred() {
        LongSupplier supplier = bytesTransferredSupplier;
        return bytesTransferred.sum() + (supplier != null ? supplier.getAsLong() : 0L);
    }

    /**
     * Folds the live connector total into the accumulated figure and drops the supplier.
     *
     * <p>Called when the local proxy stops. Jetty's ConnectionStatistics belongs to the
     * connector and resets with it, so on an SSH reconnect the reported byte total would
     * otherwise fall back to zero.
     */
    public static void carryBytesTransferred() {
        LongSupplier supplier = bytesTransferredSupplier;
        if (supplier != null) {
            bytesTransferred.add(supplier.getAsLong());
            bytesTransferredSupplier = null;
        }
    }

    /**
     * Wired by {@link HttpProxy} so byte totals include CONNECT and WebSocket traffic.
     *
     * <p>Note the connector records a connection's bytes when it closes, so long-lived tunnels
     * contribute only once finished; the figure trails live traffic rather than tracking it.
     */
    public static void setBytesTransferredSupplier(LongSupplier supplier) {
        bytesTransferredSupplier = supplier;
    }

    public static void addBytesTransferred(long aBytesTransferred) {
        bytesTransferred.add(aBytesTransferred);
    }

    public static void addRequest() {
        numberOfRequests.increment();
    }

    /** Clears all counters. Intended for tests, which would otherwise see leakage between cases. */
    public static void reset() {
        numberOfRequests.reset();
        bytesTransferred.reset();
        startTime = 0;
        bytesTransferredSupplier = null;
    }
}
