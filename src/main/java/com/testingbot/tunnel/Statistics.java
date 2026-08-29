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
        return supplier != null ? supplier.getAsLong() : bytesTransferred.sum();
    }

    /** Wired by {@link HttpProxy} so byte totals include CONNECT and WebSocket traffic. */
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
