package com.testingbot.tunnel;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;

import static org.assertj.core.api.Assertions.assertThatCode;

class DoctorTest {

    private App app;

    @BeforeEach
    void setUp() {
        app = new App();
        app.setClientKey("test_key");
        app.setClientSecret("test_secret");
        app.setJettyPort(findFreePort());
    }

    @Test
    void constructor_shouldInitializeAndPerformChecks() {
        assertThatCode(() -> {
            Doctor doctor = new Doctor(app);
        }).doesNotThrowAnyException();
    }

    private int findFreePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (Exception e) {
            return 8087; // fallback to default
        }
    }
}
