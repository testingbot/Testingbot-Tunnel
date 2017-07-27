package com.testingbot.tunnel;

public class Statistics {
    private static long numberOfRequests = 0;
    private static long startTime = 0;
    private static long bytesTransfered = 0;

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
     * @return the bytesTransfered
     */
    public static long getBytesTransfered() {
        return bytesTransfered;
    }

    /**
     * @param aBytesTransfered the bytesTransfered to set
     */
    public static void setBytesTransfered(long aBytesTransfered) {
        bytesTransfered = aBytesTransfered;
    }
    
    public static void addBytesTransfered(long aBytesTransfered) {
        bytesTransfered += aBytesTransfered;
    }
    
    public static void addRequest() {
        numberOfRequests += 1;
    }
}
