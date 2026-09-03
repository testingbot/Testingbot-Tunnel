package com.testingbot.tunnel;

import java.nio.charset.StandardCharsets;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import ssh.HostKeyPins;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Adoption of the tunnel server's host key fingerprint from the API response.
 *
 * <p>The API call is HTTPS and already authenticated, so a fingerprint arriving on it comes from
 * TestingBot rather than from whoever is on the network path -- which makes it a usable channel
 * for the one value the SSH connection cannot establish for itself. The field is optional and
 * not yet sent, so these fix the contract ahead of the service: when it starts sending one,
 * clients on this version verify with no flag and no upgrade.
 */
class ApiHostKeyFingerprintTest {

    private static JsonNode node(String field, String value) {
        return new ObjectMapper().createObjectNode().put(field, value);
    }

    private static String someFingerprint(String seed) {
        return HostKeyPins.displayFingerprint(seed.getBytes(StandardCharsets.UTF_8));
    }

    private static App app() {
        App app = new App();
        app.setClientKey("test_key");
        app.setClientSecret("test_secret");
        return app;
    }

    @Test
    void adoptsTheFingerprintWhenNoPinWasConfigured() {
        App app = app();
        String fingerprint = someFingerprint("server-key");

        app.adoptApiHostKeyFingerprint(node("ssh_fingerprint", fingerprint));

        assertThat(app.getSshHostKeyPins().displayValues()).containsExactly(fingerprint);
    }

    @Test
    void acceptsTheAlternativeFieldName() {
        App app = app();
        String fingerprint = someFingerprint("server-key");

        app.adoptApiHostKeyFingerprint(node("ssh_host_key_fingerprint", fingerprint));

        assertThat(app.getSshHostKeyPins().displayValues()).containsExactly(fingerprint);
    }

    @Test
    void doesNotOverrideAPinAlreadySet() {
        // The recourse where a TLS-intercepting proxy could rewrite this very response is a pin
        // established out of band. The far side does not get to replace it.
        App app = app();
        String configured = someFingerprint("the-key-i-configured");
        app.setSshHostKeyPins(HostKeyPins.parse(configured));

        app.adoptApiHostKeyFingerprint(node("ssh_fingerprint", someFingerprint("some-other-key")));

        assertThat(app.getSshHostKeyPins().displayValues()).containsExactly(configured);
    }

    @Test
    void unusableValueLeavesTheTunnelUnverifiedRatherThanFailing() {
        App app = app();

        app.adoptApiHostKeyFingerprint(node("ssh_fingerprint", "not-a-fingerprint"));

        assertThat(app.getSshHostKeyPins().isEmpty()).isTrue();
    }

    @Test
    void md5FromTheApiIsRefused() {
        App app = app();

        app.adoptApiHostKeyFingerprint(
            node("ssh_fingerprint", "MD5:00:11:22:33:44:55:66:77:88:99:aa:bb:cc:dd:ee:ff"));

        assertThat(app.getSshHostKeyPins().isEmpty()).isTrue();
    }

    @Test
    void absentFieldChangesNothing() {
        App app = app();

        app.adoptApiHostKeyFingerprint(node("id", "12345"));

        assertThat(app.getSshHostKeyPins().isEmpty()).isTrue();
    }

    @Test
    void nullResponseChangesNothing() {
        App app = app();

        app.adoptApiHostKeyFingerprint(null);

        assertThat(app.getSshHostKeyPins().isEmpty()).isTrue();
    }
}
