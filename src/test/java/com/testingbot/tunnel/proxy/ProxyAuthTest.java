package com.testingbot.tunnel.proxy;

import org.junit.jupiter.api.Test;

import java.net.PasswordAuthentication;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for ProxyAuth authentication
 */
class ProxyAuthTest {

    @Test
    void constructor_withValidCredentials_shouldCreateAuth() {
        // Given: Valid credentials
        String username = "testuser";
        String password = "testpass";

        // When: Creating ProxyAuth
        ProxyAuth auth = new ProxyAuth(username, password);

        // Then: Should be created successfully
        assertThat(auth).isNotNull();
    }

    @Test
    void getPasswordAuthentication_shouldReturnCredentials() {
        // Given: ProxyAuth with credentials
        String username = "myuser";
        String password = "mypassword";
        ProxyAuth auth = new ProxyAuth(username, password);

        // When: Getting password authentication
        PasswordAuthentication passwordAuth = auth.getPasswordAuthentication();

        // Then: Should return correct username
        assertThat(passwordAuth.getUserName()).isEqualTo(username);

        // And: Password should match
        assertThat(new String(passwordAuth.getPassword())).isEqualTo(password);
    }

    @Test
    void constructor_withNullPassword_shouldHandleGracefully() {
        // Given: Username with null password
        String username = "user";

        // When: Creating ProxyAuth with null password
        ProxyAuth auth = new ProxyAuth(username, null);

        // Then: Should create successfully
        assertThat(auth).isNotNull();

        // And: Should return empty password
        PasswordAuthentication passwordAuth = auth.getPasswordAuthentication();
        assertThat(passwordAuth.getUserName()).isEqualTo(username);
        assertThat(passwordAuth.getPassword()).isEmpty();
    }

    @Test
    void constructor_withEmptyPassword_shouldStoreEmpty() {
        // Given: Username with empty password
        String username = "user";
        String password = "";

        // When: Creating ProxyAuth
        ProxyAuth auth = new ProxyAuth(username, password);

        // Then: Should store empty password
        PasswordAuthentication passwordAuth = auth.getPasswordAuthentication();
        assertThat(passwordAuth.getUserName()).isEqualTo(username);
        assertThat(new String(passwordAuth.getPassword())).isEmpty();
    }

    @Test
    void getPasswordAuthentication_withSpecialCharacters_shouldHandle() {
        // Given: Credentials with special characters
        String username = "user@domain.com";
        String password = "p@ss:w0rd!#$%";

        // When: Creating ProxyAuth
        ProxyAuth auth = new ProxyAuth(username, password);

        // Then: Should handle special characters
        PasswordAuthentication passwordAuth = auth.getPasswordAuthentication();
        assertThat(passwordAuth.getUserName()).isEqualTo(username);
        assertThat(new String(passwordAuth.getPassword())).isEqualTo(password);
    }

    @Test
    void getPasswordAuthentication_withLongPassword_shouldHandle() {
        // Given: Very long password
        String username = "user";
        String password = "a".repeat(1000);

        // When: Creating ProxyAuth
        ProxyAuth auth = new ProxyAuth(username, password);

        // Then: Should handle long password
        PasswordAuthentication passwordAuth = auth.getPasswordAuthentication();
        assertThat(passwordAuth.getUserName()).isEqualTo(username);
        assertThat(new String(passwordAuth.getPassword())).hasSize(1000);
    }

    @Test
    void multipleInvocations_shouldReturnSameCredentials() {
        // Given: ProxyAuth instance
        String username = "testuser";
        String password = "testpass";
        ProxyAuth auth = new ProxyAuth(username, password);

        // When: Calling getPasswordAuthentication multiple times
        PasswordAuthentication auth1 = auth.getPasswordAuthentication();
        PasswordAuthentication auth2 = auth.getPasswordAuthentication();

        // Then: Should return same credentials
        assertThat(auth1.getUserName()).isEqualTo(auth2.getUserName());
        assertThat(new String(auth1.getPassword())).isEqualTo(new String(auth2.getPassword()));
    }

    @Test
    void constructor_withUnicodePassword_shouldHandle() {
        // Given: Unicode password
        String username = "user";
        String password = "пароль密码🔐";

        // When: Creating ProxyAuth
        ProxyAuth auth = new ProxyAuth(username, password);

        // Then: Should handle Unicode correctly
        PasswordAuthentication passwordAuth = auth.getPasswordAuthentication();
        assertThat(new String(passwordAuth.getPassword())).isEqualTo(password);
    }
}
