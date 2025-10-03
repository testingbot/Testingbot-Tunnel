package com.testingbot.tunnel;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for proxy configuration validation
 */
class ProxyValidationTest {

    private App app;

    @BeforeEach
    void setUp() {
        app = new App();
        app.setClientKey("test");
        app.setClientSecret("test");
    }

    @Test
    void setProxy_withValidHostAndPort_shouldSucceed() {
        // Given: Valid proxy configuration
        // When: Setting proxy
        app.setProxy("proxy.example.com:8080");

        // Then: Should be set
        assertThat(app.getProxy()).isEqualTo("proxy.example.com:8080");
    }

    @Test
    void setProxy_withValidHostOnly_shouldSucceed() {
        // Given: Valid proxy hostname without port
        // When: Setting proxy
        app.setProxy("proxy.example.com");

        // Then: Should be set
        assertThat(app.getProxy()).isEqualTo("proxy.example.com");
    }

    @Test
    void setProxy_withIPAddress_shouldSucceed() {
        // Given: Valid IP address
        // When: Setting proxy
        app.setProxy("192.168.1.1:3128");

        // Then: Should be set
        assertThat(app.getProxy()).isEqualTo("192.168.1.1:3128");
    }

    @Test
    void setProxy_withInvalidPort_shouldThrowException() {
        // Given: Invalid port number
        // When/Then: Setting proxy should throw exception
        assertThatThrownBy(() -> app.setProxy("proxy.example.com:99999"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid proxy port");
    }

    @Test
    void setProxy_withNegativePort_shouldThrowException() {
        // Given: Negative port number
        // When/Then: Setting proxy should throw exception
        assertThatThrownBy(() -> app.setProxy("proxy.example.com:-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid proxy port");
    }

    @Test
    void setProxy_withNonNumericPort_shouldThrowException() {
        // Given: Non-numeric port
        // When/Then: Setting proxy should throw exception
        assertThatThrownBy(() -> app.setProxy("proxy.example.com:abc"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid proxy port");
    }

    @Test
    void setProxy_withEmptyHost_shouldThrowException() {
        // Given: Empty hostname
        // When/Then: Setting proxy should throw exception
        assertThatThrownBy(() -> app.setProxy(":8080"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid proxy format");
    }

    @Test
    void setProxy_withWhitespace_shouldTrim() {
        // Given: Proxy with whitespace
        // When: Setting proxy
        app.setProxy("  proxy.example.com:8080  ");

        // Then: Should be trimmed
        assertThat(app.getProxy()).isEqualTo("proxy.example.com:8080");
    }

    @Test
    void setProxyAuth_withValidCredentials_shouldSucceed() {
        // Given: Valid credentials
        // When: Setting proxy auth
        app.setProxyAuth("username:password");

        // Then: Should be set
        assertThat(app.getProxyAuth()).isEqualTo("username:password");
    }

    @Test
    void setProxyAuth_withPasswordContainingColon_shouldSucceed() {
        // Given: Password containing colon
        // When: Setting proxy auth
        app.setProxyAuth("user:pass:word");

        // Then: Should be set (split on first colon only)
        assertThat(app.getProxyAuth()).isEqualTo("user:pass:word");
    }

    @Test
    void setProxyAuth_withoutColon_shouldThrowException() {
        // Given: Credentials without colon
        // When/Then: Setting proxy auth should throw exception
        assertThatThrownBy(() -> app.setProxyAuth("usernamepassword"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid proxy auth format");
    }

    @Test
    void setProxyAuth_withEmptyUsername_shouldThrowException() {
        // Given: Empty username
        // When/Then: Setting proxy auth should throw exception
        assertThatThrownBy(() -> app.setProxyAuth(":password"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Username cannot be empty");
    }

    @Test
    void setProxyAuth_withWhitespace_shouldTrim() {
        // Given: Credentials with whitespace
        // When: Setting proxy auth
        app.setProxyAuth("  user:pass  ");

        // Then: Should be trimmed
        assertThat(app.getProxyAuth()).isEqualTo("user:pass");
    }

    @Test
    void setProxyAuth_withNull_shouldAcceptNull() {
        // Given: Null credentials
        // When: Setting null
        app.setProxyAuth(null);

        // Then: Should be null
        assertThat(app.getProxyAuth()).isNull();
    }

    @Test
    void setProxy_withNull_shouldAcceptNull() {
        // Given: Null proxy
        // When: Setting null
        app.setProxy(null);

        // Then: Should be null
        assertThat(app.getProxy()).isNull();
    }
}
