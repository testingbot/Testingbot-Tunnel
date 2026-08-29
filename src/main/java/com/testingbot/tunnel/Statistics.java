package com.testingbot.tunnel;

public class Statistics {
    private static long numberOfRequests = 0;
    private static long startTime = 0;
    private static long bytesTransferred = 0;

    /**
     * When the local proxy is running it supplies byte totals from the connector, which
     * counts tunnelled traffic too. Until then (and in tests) the manually accumulated
     * counter is used.
     */
    private static volatile java.util.function.LongSupplier bytesTransferredSupplier;

    /**
     * @return the numberOfRequests
     */
    public static long getNumberOfRequests() {
        return numberOfRequests;
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
        java.util.function.LongSupplier supplier = bytesTransferredSupplier;
        return supplier != null ? supplier.getAsLong() : bytesTransferred;
    }

    /** Wired by {@link HttpProxy} so byte totals include CONNECT and WebSocket traffic. */
    public static void setBytesTransferredSupplier(java.util.function.LongSupplier supplier) {
        bytesTransferredSupplier = supplier;
    }

    public static void addBytesTransferred(long aBytesTransferred) {
        bytesTransferred += aBytesTransferred;
    }

    public static void addRequest() {
        numberOfRequests += 1;
    }
}
