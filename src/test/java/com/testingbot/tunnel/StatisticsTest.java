package com.testingbot.tunnel;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for Statistics tracking
 */
class StatisticsTest {

    @BeforeEach
    void setUp() throws Exception {
        // Reset static fields before each test
        resetStatistics();
    }

    @Test
    void addRequest_shouldIncrementCounter() {
        // Given: Initial state
        assertThat(Statistics.getNumberOfRequests()).isEqualTo(0);

        // When: Adding requests
        Statistics.addRequest();
        Statistics.addRequest();
        Statistics.addRequest();

        // Then: Counter should be incremented
        assertThat(Statistics.getNumberOfRequests()).isEqualTo(3);
    }

    @Test
    void addBytesTransferred_shouldAccumulateBytes() {
        // Given: Initial state
        assertThat(Statistics.getBytesTransferred()).isEqualTo(0);

        // When: Adding bytes
        Statistics.addBytesTransferred(1024);
        Statistics.addBytesTransferred(2048);
        Statistics.addBytesTransferred(512);

        // Then: Bytes should be accumulated
        assertThat(Statistics.getBytesTransferred()).isEqualTo(3584);
    }

    @Test
    void setStartTime_shouldStoreStartTime() {
        // Given: A timestamp
        long timestamp = System.currentTimeMillis();

        // When: Setting start time
        Statistics.setStartTime(timestamp);

        // Then: Should be retrievable
        assertThat(Statistics.getStartTime()).isEqualTo(timestamp);
    }

    @Test
    void getStartTime_shouldReturnZeroInitially() {
        // Given: Fresh statistics
        // When: Getting start time
        long startTime = Statistics.getStartTime();

        // Then: Should be zero
        assertThat(startTime).isEqualTo(0);
    }

    @Test
    void multipleOperations_shouldTrackCorrectly() {
        // Given: Set start time
        long startTime = System.currentTimeMillis();
        Statistics.setStartTime(startTime);

        // When: Performing multiple operations
        for (int i = 0; i < 10; i++) {
            Statistics.addRequest();
            Statistics.addBytesTransferred(1000);
        }

        // Then: All statistics should be correct
        assertThat(Statistics.getNumberOfRequests()).isEqualTo(10);
        assertThat(Statistics.getBytesTransferred()).isEqualTo(10000);
        assertThat(Statistics.getStartTime()).isEqualTo(startTime);
    }

    @Test
    void addBytesTransferred_withZero_shouldNotChange() {
        // Given: Some initial bytes
        Statistics.addBytesTransferred(100);

        // When: Adding zero bytes
        Statistics.addBytesTransferred(0);

        // Then: Should remain the same
        assertThat(Statistics.getBytesTransferred()).isEqualTo(100);
    }

    @Test
    void addBytesTransferred_withLargeValues_shouldHandle() {
        // Given: Large byte transfer
        long largeValue = 1024L * 1024L * 1024L; // 1GB

        // When: Adding large values
        Statistics.addBytesTransferred(largeValue);
        Statistics.addBytesTransferred(largeValue);

        // Then: Should handle correctly
        assertThat(Statistics.getBytesTransferred()).isEqualTo(2L * 1024L * 1024L * 1024L);
    }

    /**
     * Reset static fields using reflection
     */
    private void resetStatistics() throws Exception {
        Field requestsField = Statistics.class.getDeclaredField("numberOfRequests");
        requestsField.setAccessible(true);
        requestsField.setLong(null, 0);

        Field bytesField = Statistics.class.getDeclaredField("bytesTransferred");
        bytesField.setAccessible(true);
        bytesField.setLong(null, 0);

        Field startTimeField = Statistics.class.getDeclaredField("startTime");
        startTimeField.setAccessible(true);
        startTimeField.setLong(null, 0);
    }
}
