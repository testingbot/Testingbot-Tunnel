package com.testingbot.tunnel;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * An unusable {@code --pac-local} fails before a tunnel is asked for.
 *
 * <p>The file was first read when the local proxy started, which is after
 * {@code /v1/tunnel/create}: a tunnel server was provisioned, the user waited for it, and only
 * then heard that the PAC file was refused. The API stand-in here counts the create calls, so a
 * check that moved back behind the create would fail.
 */
class PacLocalPreflightTest {

    private static final String SCRIPT =
            "function FindProxyForURL(url, host) { return \"DIRECT\"; }";

    private WireMockServer wireMockServer;
    private App app;

    @BeforeEach
    void setUp() {
        wireMockServer = new WireMockServer(options().dynamicPort());
        wireMockServer.start();
        // Stops boot() right after the create call, which is all these tests need to observe.
        wireMockServer.stubFor(post(urlPathEqualTo("/v1/tunnel/create"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"error\":\"401 Unauthorized\"}")));
        wireMockServer.stubFor(get(urlPathEqualTo("/proxy.pac"))
            .willReturn(aResponse().withStatus(200).withBody(SCRIPT)));

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
        wireMockServer.stop();
    }

    private int createCalls() {
        return wireMockServer.findAll(postRequestedFor(urlPathEqualTo("/v1/tunnel/create"))).size();
    }

    @Test
    void aPlainHttpPacUrlWithoutAPinIsRefusedBeforeTheTunnelIsCreated() {
        app.setPacLocal("http://localhost:" + wireMockServer.port() + "/proxy.pac");

        assertThatThrownBy(() -> app.boot())
            .isInstanceOf(TunnelFailedException.class)
            .hasMessageContaining("Refusing to fetch PAC file")
            .hasMessageContaining("over plain HTTP");

        assertThat(createCalls()).isZero();
    }

    @Test
    void aDigestMismatchIsRefusedBeforeTheTunnelIsCreated(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("p.pac");
        Files.writeString(file, SCRIPT, UTF_8);
        app.setPacLocal(file.toString());
        app.setPacLocalSha256("0".repeat(64));

        assertThatThrownBy(() -> app.boot())
            .isInstanceOf(TunnelFailedException.class)
            .hasMessageContaining("does not match --pac-local-sha256");

        assertThat(createCalls()).isZero();
    }

    @Test
    void aFileThatDoesNotParseIsRefusedBeforeTheTunnelIsCreated(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("broken.pac");
        Files.writeString(file, "function FindProxyForURL(url, host) { return ", UTF_8);
        app.setPacLocal(file.toString());

        assertThatThrownBy(() -> app.boot())
            .isInstanceOf(TunnelFailedException.class)
            .hasMessageContaining("is not usable");

        assertThat(createCalls()).isZero();
    }

    @Test
    void aUsablePacFileIsLoadedOnceAndTheTunnelIsThenCreated(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("p.pac");
        Files.writeString(file, SCRIPT, UTF_8);
        app.setPacLocal(file.toString());

        // Fails on the stubbed 401, i.e. after the create call, not on the PAC file.
        assertThatThrownBy(() -> app.boot())
            .isInstanceOf(TunnelFailedException.class)
            .hasMessageContaining("401");

        assertThat(createCalls()).isEqualTo(1);
        // The proxy reuses this load instead of reading the file a second time: with the file
        // gone, a second read could only fail.
        Files.delete(file);
        assertThat(app.getPacPolicy()).isNotNull();
    }

    @Test
    void underNoproxyThePacFileIsNotRead() {
        // No local proxy routes anything, so the file was never consulted there; a refusal now
        // would break a configuration that worked.
        app.setNoProxy(true);
        app.setPacLocal("http://localhost:" + wireMockServer.port() + "/proxy.pac");

        assertThatThrownBy(() -> app.boot())
            .isInstanceOf(TunnelFailedException.class)
            .hasMessageContaining("401");

        assertThat(createCalls()).isEqualTo(1);
    }
}
