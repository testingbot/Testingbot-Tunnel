package ssh;

import com.testingbot.tunnel.App;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SSHTunnelTest {

    private App app;
    
    @BeforeEach
    void setUp() {
        app = new App();
        app.setClientKey("test_key");
        app.setClientSecret("test_secret");
    }
    
    @Test
    void constructor_withInvalidServer_shouldThrowException() {
        // Given
        String invalidServer = "invalid.server.com";
        
        // When & Then
        assertThatThrownBy(() -> new SSHTunnel(app, invalidServer))
            .isInstanceOf(Exception.class)
            .hasMessageContaining("Connection failed");
    }
    
    @Test
    void constructor_withValidMockData_shouldFailOnConnectionAttempt() {
        // Given
        String server = "test.server.com";
        
        // When & Then - Should fail to connect in test environment
        assertThatThrownBy(() -> new SSHTunnel(app, server))
            .isInstanceOf(Exception.class)
            .hasMessageContaining("Connection failed");
    }

    @Test
    void reverseForwardHost_mustBeConnectableLoopback() {
        // TB-253: "0.0.0.0" is not a connectable address on macOS, and JSch uses
        // this value as the delivery target for every forwarded connection.
        // "localhost" is also unsafe: it may resolve to ::1 while Jetty binds IPv4.
        assertThat(SSHTunnel.REVERSE_FORWARD_HOST).isEqualTo("127.0.0.1");
    }
}