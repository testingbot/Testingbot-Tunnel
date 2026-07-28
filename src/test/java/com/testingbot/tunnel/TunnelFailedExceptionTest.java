package com.testingbot.tunnel;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TunnelFailedExceptionTest {

    @Test
    void defaultExitCode_shouldBeOne() {
        // Given & When
        TunnelFailedException exception = new TunnelFailedException("boom");

        // Then
        assertThat(exception.getMessage()).isEqualTo("boom");
        assertThat(exception.getExitCode()).isEqualTo(1);
    }

    @Test
    void exitCode_shouldBePreserved() {
        // Given & When
        TunnelFailedException exception = new TunnelFailedException("boom", 2);

        // Then
        assertThat(exception.getExitCode()).isEqualTo(2);
    }

    @Test
    void cause_shouldBePreserved() {
        // Given
        Exception cause = new IllegalStateException("underlying");

        // When
        TunnelFailedException exception = new TunnelFailedException("boom", 1, cause);

        // Then
        assertThat(exception).hasCause(cause);
        assertThat(exception.getExitCode()).isEqualTo(1);
    }
}
