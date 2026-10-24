package com.testingbot.tunnel;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies that App can be embedded in a host process: a failing tunnel must
 * report the failure to the caller instead of terminating the JVM.
 *
 * These tests would not merely fail but crash the surefire JVM if App went back
 * to calling System.exit on the boot path.
 */
class AppEmbeddedTest {

    private WireMockServer wireMockServer;
    private App app;

    @BeforeEach
    void setUp() {
        wireMockServer = new WireMockServer(options().dynamicPort());
        wireMockServer.start();
        WireMock.configureFor("localhost", wireMockServer.port());

        app = new App() {
            @Override
            Api createApi() {
                Api api = new Api(this);
                api.setApiScheme("http");
                api.setApiHost("localhost:" + wireMockServer.port());
                return api;
            }
        };
        app.setClientKey("test_key");
        app.setClientSecret("test_secret");
    }

    @AfterEach
    void tearDown() {
        if (wireMockServer != null) {
            wireMockServer.stop();
        }
    }

    @Test
    void boot_whenCredentialsAreRejected_shouldThrowInsteadOfExiting() {
        // Given: the API reports an authentication failure
        wireMockServer.stubFor(post(urlPathEqualTo("/v1/tunnel/create"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"error\":\"401 Unauthorized\"}")));

        // When & Then: the caller gets an exception, the JVM survives
        assertThatThrownBy(() -> app.boot())
            .isInstanceOf(TunnelFailedException.class)
            .hasMessageContaining("401");
    }

    @Test
    void boot_whenTunnelCreationFails_shouldThrowInsteadOfExiting() {
        // Given: the API is unreachable / returns something unusable
        wireMockServer.stubFor(post(urlPathEqualTo("/v1/tunnel/create"))
            .willReturn(aResponse().withStatus(500).withBody("nope")));

        // When & Then
        assertThatThrownBy(() -> app.boot())
            .isInstanceOf(TunnelFailedException.class);
    }

    private void createAnswers(String body) {
        wireMockServer.stubFor(post(urlPathEqualTo("/v1/tunnel/create"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(body)));
    }

    @Test
    void boot_whenTheResponseHasNoState_shouldNameTheApiNotThrowANullPointer() {
        // Given: an answer without "state", which used to be a NullPointerException
        createAnswers("{\"id\":\"42\",\"version\":\"5.0\"}");

        // When & Then
        assertThatThrownBy(() -> app.boot())
            .isInstanceOf(TunnelFailedException.class)
            .hasMessageContaining("Unexpected response from the TestingBot API: no tunnel \"state\"")
            .hasMessageContaining("[id, version]");

        // The id was kept, so the cleanup on the way out can still delete the tunnel
        assertThat(app.getTunnelID()).isEqualTo(42);
    }

    @Test
    void boot_whenTheResponseHasNoId_shouldFailWithAMessage() {
        createAnswers("{\"state\":\"BOOTING\"}");

        assertThatThrownBy(() -> app.boot())
            .isInstanceOf(TunnelFailedException.class)
            .hasMessageContaining("Unexpected response from the TestingBot API: no tunnel \"id\"");
    }

    @Test
    void boot_whenTheIdIsNotAnInteger_shouldFailWithAMessage() {
        // Used to be a NumberFormatException stack trace
        createAnswers("{\"id\":\"abc\",\"state\":\"BOOTING\"}");

        assertThatThrownBy(() -> app.boot())
            .isInstanceOf(TunnelFailedException.class)
            .hasMessageContaining("Unexpected response from the TestingBot API: tunnel \"id\" is not an integer");

        createAnswers("{\"id\":1.5,\"state\":\"BOOTING\"}");

        assertThatThrownBy(() -> app.boot())
            .isInstanceOf(TunnelFailedException.class)
            .hasMessageContaining("is not an integer");
    }

    @Test
    void boot_whenTheResponseIsNotAnObject_shouldFailWithAMessage() {
        createAnswers("[]");

        assertThatThrownBy(() -> app.boot())
            .isInstanceOf(TunnelFailedException.class)
            .hasMessageContaining("Unexpected response from the TestingBot API: not a JSON object");
    }

    @Test
    void boot_shouldNotStartPidPolling() throws Exception {
        // Given: pid tracking is a command line concern; a background timer that
        // calls System.exit has no business running inside a host process
        wireMockServer.stubFor(post(urlPathEqualTo("/v1/tunnel/create"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"error\":\"401 Unauthorized\"}")));

        // When
        assertThatThrownBy(() -> app.boot()).isInstanceOf(TunnelFailedException.class);

        // Then
        assertThat(readField(app, "pidPoller")).isNull();
    }

    @Test
    void stop_shouldUnregisterTheShutdownHook() throws Exception {
        // Given
        app.init();
        assertThat(readField(app, "cleanupThread")).isNotNull();

        // When
        app.stop();

        // Then: an embedder starting a tunnel per job must not leak a hook each time
        assertThat(readField(app, "cleanupThread")).isNull();
    }

    @Test
    void stop_shouldBeSafeToCallRepeatedly() {
        // Given
        app.init();

        // When & Then
        assertThatCode(() -> {
            app.stop();
            app.stop();
        }).doesNotThrowAnyException();
    }

    private Object readField(App target, String name) throws Exception {
        Field field = App.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }
}
