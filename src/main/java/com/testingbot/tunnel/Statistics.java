package com.testingbot.tunnel;

public class Statistics {
    private static long numberOfRequests = 0;
    private static long startTime = 0;
    private static long bytesTransferred = 0;

    /**
     * @return the numberOfRequests
     */
    public static long getNumberOfRequests() {
        return numberOfRequests;
    }

    /**
     * @param aNumberOfRequests the numberOfRequests to set
     */
    public static void setNumberOfRequests(long aNumberOfRequests) {
        numberOfRequests = aNumberOfRequests;
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
        return bytesTransferred;
    }

    /**
     * @param aBytesTransferred the bytesTransferred to set
     */
    public static void setBytesTransferred(long aBytesTransferred) {
        bytesTransferred = aBytesTransferred;
    }

    public static void addBytesTransferred(long aBytesTransferred) {
        bytesTransferred += aBytesTransferred;
    }

    public static void addRequest() {
        numberOfRequests += 1;
    }
}
