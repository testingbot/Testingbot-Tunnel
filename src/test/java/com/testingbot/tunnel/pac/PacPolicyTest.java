package com.testingbot.tunnel.pac;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Loading, caching and failure behaviour around the interpreter.
 *
 * <p>The interpreter is tested separately; what matters here is the wrapper a running tunnel
 * actually depends on -- that a file can be fetched at all, that the answer is cheap enough to
 * ask per request, and above all that a broken PAC file degrades to DIRECT instead of taking
 * the tunnel down.
 */
class PacPolicyTest {

    /**
     * A well-formed pin that no document will match. These tests are about fetch failures --
     * 404, unreachable, oversized -- which all happen before any digest is compared, so the pin
     * only has to get past the refusal of unpinned http:// URLs.
     */
    private static final String ANY_PIN = "0".repeat(64);

    private static final String SIMPLE = """
            function FindProxyForURL(url, host) {
                if (dnsDomainIs(host, ".corp")) return "DIRECT";
                return "PROXY p.corp:8080";
            }
            """;

    @Test
    void loadsFromAFile(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("corp.pac");
        Files.writeString(file, SIMPLE);

        PacPolicy policy = PacPolicy.load(file.toString());

        assertThat(policy.resolve("http://a.corp/", "a.corp").isDirect()).isTrue();
        assertThat(policy.resolve("http://a.com/", "a.com").first().getHost()).isEqualTo("p.corp");
        assertThat(policy.getSource()).isEqualTo(file.toString());
    }

    @Test
    void loadsFromAUrl() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/proxy.pac", exchange -> {
            byte[] body = SIMPLE.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            // Pinned, because a plain http:// PAC URL is refused without one. The digest is
            // computed from the same bytes the server serves, so this still tests the fetch
            // rather than the pin -- the pin has its own tests below.
            PacPolicy policy = PacPolicy.load(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/proxy.pac",
                    PacPolicy.sha256Hex(SIMPLE.getBytes(StandardCharsets.UTF_8)));

            assertThat(policy.resolve("http://a.corp/", "a.corp").isDirect()).isTrue();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void aNon200ResponseIsReportedWithItsStatus() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/missing.pac", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/missing.pac";

            assertThatThrownBy(() -> PacPolicy.load(url, ANY_PIN))
                    .isInstanceOf(PacException.class)
                    .hasMessageContaining("404");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void anUnreachableUrlIsReportedWithoutAStackTrace(@TempDir Path tmp) throws Exception {
        // Nothing listening on this port.
        try (java.net.ServerSocket probe = new java.net.ServerSocket(0)) {
            int free = probe.getLocalPort();
            probe.close();

            assertThatThrownBy(() -> PacPolicy.load("http://127.0.0.1:" + free + "/proxy.pac", ANY_PIN))
                    .isInstanceOf(PacException.class)
                    .hasMessageContaining("Could not fetch PAC file");
        }
    }

    @Test
    void anAbsurdlyLargePacFileIsRefusedRatherThanBuffered() throws Exception {
        // The URL comes from the user, and a corporate endpoint having a bad day can answer with
        // anything. A PAC file is a few kilobytes; reading an unbounded body into memory is not
        // a reasonable thing to do on startup.
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/huge.pac", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            byte[] chunk = new byte[64 * 1024];
            java.util.Arrays.fill(chunk, (byte) 'x');
            try {
                for (int i = 0; i < (PacPolicy.MAX_PAC_BYTES / chunk.length) + 4; i++) {
                    exchange.getResponseBody().write(chunk);
                }
            } catch (Exception clientGaveUp) {
                // expected once the read is cut short
            }
            exchange.close();
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/huge.pac";

            assertThatThrownBy(() -> PacPolicy.load(url, ANY_PIN))
                    .isInstanceOf(PacException.class)
                    .hasMessageContaining("larger than");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void aMissingFileIsNamed(@TempDir Path tmp) {
        assertThatThrownBy(() -> PacPolicy.load(tmp.resolve("absent.pac").toString()))
                .isInstanceOf(PacException.class)
                .hasMessageContaining("PAC file not found");
    }

    @Test
    void anUnparseableFileIsRejectedAtLoadTimeWithTheLine(@TempDir Path tmp) throws Exception {
        // Better to refuse at startup than to discover it on the first request.
        Path file = tmp.resolve("broken.pac");
        Files.writeString(file, "function FindProxyForURL(url, host) {\n var o = {a: 1};\n}");

        assertThatThrownBy(() -> PacPolicy.load(file.toString()))
                .isInstanceOf(PacException.class)
                .hasMessageContaining("not usable")
                .hasMessageContaining("line 2");
    }

    @Test
    void theLineNumberIsNotRepeated(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("broken.pac");
        Files.writeString(file, "function FindProxyForURL(url, host) {\n var o = {a: 1};\n}");

        String message = catchMessage(() -> PacPolicy.load(file.toString()));

        assertThat(message.split(java.util.regex.Pattern.quote("(line 2)"), -1).length - 1)
                .as("'(line 2)' should appear exactly once in: %s", message)
                .isEqualTo(1);
    }

    @Test
    void repeatedIdenticalLookupsAreCached() {
        // The reason the cache exists: every request through the tunnel asks again.
        PacPolicy policy = PacPolicy.of(SIMPLE, "inline");

        policy.resolve("http://a.corp/", "a.corp");
        policy.resolve("http://a.corp/", "a.corp");
        policy.resolve("http://b.com/", "b.com");

        assertThat(policy.cacheSize()).isEqualTo(2);
    }

    /**
     * The cache is keyed by what the interpreter was asked, not by host alone.
     *
     * <p>FindProxyForURL receives url as well as host and may branch on it. Keyed by host, the
     * first decision for a host stood in for every later one, so whichever request arrived first
     * chose the route for the rest -- a CONNECT to host:443 and a ws:// upgrade to host:80 are
     * different questions and used to share one answer.
     */
    @Test
    void aDecisionForOneSchemeDoesNotStandInForAnother() {
        String byScheme = """
                function FindProxyForURL(url, host) {
                    if (shExpMatch(url, "https:*")) return "PROXY secure:8443";
                    return "PROXY plain:8080";
                }
                """;
        PacPolicy policy = PacPolicy.of(byScheme, "inline");

        // The https answer first, so a host-keyed cache would serve it to the http question too.
        assertThat(policy.resolve("https://x.com:443/", "x.com").first().toProxySpec())
                .contains("secure");
        assertThat(policy.resolve("http://x.com:80/", "x.com").first().toProxySpec())
                .contains("plain");
        // And the other way round, so neither order hides the bug.
        assertThat(policy.resolve("https://x.com:443/", "x.com").first().toProxySpec())
                .contains("secure");
    }

    @Test
    void theCacheIsBoundedSoAWideRunCannotLeak() {
        // A tunnel can be pointed at a very large number of hosts over a long session.
        PacPolicy policy = PacPolicy.of(SIMPLE, "inline");

        for (int i = 0; i < PacPolicy.MAX_CACHE_ENTRIES + 50; i++) {
            policy.resolve("http://h" + i + "/", "h" + i + ".com");
        }

        assertThat(policy.cacheSize()).isLessThanOrEqualTo(PacPolicy.MAX_CACHE_ENTRIES);
    }

    @Test
    void aRuntimeFailureDegradesToDirectRatherThanBreakingTheTunnel() {
        // Reaching an unknown variable only on some branch is exactly the kind of latent bug a
        // real PAC file has. It must not make every request fail.
        String risky = """
                function FindProxyForURL(url, host) {
                    if (host == "boom.com") return notDeclaredAnywhere;
                    return "PROXY p:8080";
                }
                """;
        PacPolicy policy = PacPolicy.of(risky, "inline");

        assertThat(policy.resolve("http://boom.com/", "boom.com").isDirect()).isTrue();
        assertThat(policy.resolve("http://fine.com/", "fine.com").first().getHost())
                .isEqualTo("p");
    }

    @Test
    void aRunawayFileDegradesToDirectRatherThanHangingTheRequest() {
        String looping = """
                function FindProxyForURL(url, host) {
                    while (true) { var x = 1; }
                    return "DIRECT";
                }
                """;
        PacPolicy policy = PacPolicy.of(looping, "inline");

        assertThat(policy.resolve("http://any.com/", "any.com").isDirect()).isTrue();
    }

    @Test
    void aTimeDependentDecisionIsNotFrozenForTheLifeOfTheTunnel() {
        // PAC files route by time of day, and tunnels run for hours. Caching without expiry
        // meant a session started before 09:00 kept the out-of-hours route all day.
        long[] now = {0L};
        boolean[] businessHours = {false};
        String script = """
                function FindProxyForURL(url, host) {
                    if (timeRange(9, 17)) return "PROXY office:8080";
                    return "DIRECT";
                }
                """;
        PacInterpreter interpreter = new PacInterpreter(script, new PacInterpreter.PacEnvironment() {
            @Override
            public String resolve(String host) {
                return null;
            }

            @Override
            public String myIpAddress() {
                return "10.0.0.1";
            }

            @Override
            public boolean weekdayRange(java.util.List<String> args) {
                return false;
            }

            @Override
            public boolean dateRange(java.util.List<String> args) {
                return false;
            }

            @Override
            public boolean timeRange(java.util.List<String> args) {
                return businessHours[0];
            }
        });
        PacPolicy policy = new PacPolicy(interpreter, "inline", () -> now[0]);

        assertThat(policy.resolve("http://a.com/", "a.com").isDirect()).isTrue();

        // Business hours start; within the TTL the old answer still stands.
        businessHours[0] = true;
        now[0] += PacPolicy.CACHE_TTL_MS / 2;
        assertThat(policy.resolve("http://a.com/", "a.com").isDirect()).isTrue();

        // Past the TTL it is re-evaluated.
        now[0] += PacPolicy.CACHE_TTL_MS;
        assertThat(policy.resolve("http://a.com/", "a.com").first().getHost()).isEqualTo("office");
    }

    @Test
    void withinTheTtlARepeatedHostIsAnsweredFromTheCache() {
        // The TTL must not turn every request into a fresh evaluation.
        long[] now = {0L};
        PacPolicy policy = new PacPolicy(new PacInterpreter(SIMPLE), "inline", () -> now[0]);

        assertThat(policy.resolve("http://a.corp/", "a.corp").isDirect()).isTrue();
        now[0] += PacPolicy.CACHE_TTL_MS / 2;
        assertThat(policy.resolve("http://a.corp/", "a.corp").isDirect()).isTrue();

        assertThat(policy.cacheSize()).isEqualTo(1);
    }

    @Test
    void aNullHostIsDirectAndNotAnError() {
        assertThat(PacPolicy.of(SIMPLE, "inline").resolve("http://x/", null).isDirect()).isTrue();
    }

    @Test
    void evaluateUncachedDoesNotPopulateTheCache() {
        // --pac-test must not warm a cache the running tunnel would then reuse.
        PacPolicy policy = PacPolicy.of(SIMPLE, "inline");

        policy.evaluateUncached("http://a.corp/", "a.corp");

        assertThat(policy.cacheSize()).isZero();
    }

    @Test
    void evaluateUncachedPropagatesFailuresInsteadOfHidingThem() {
        // The opposite of resolve(): --pac-test exists to surface problems.
        PacPolicy policy = PacPolicy.of("""
                function FindProxyForURL(url, host) { return nope; }
                """, "inline");

        assertThatThrownBy(() -> policy.evaluateUncached("http://x/", "x"))
                .isInstanceOf(PacException.class);
    }

    private static String catchMessage(Runnable action) {
        try {
            action.run();
            throw new AssertionError("expected a failure");
        } catch (RuntimeException expected) {
            return expected.getMessage();
        }
    }

    // ------------------------------------------------------- TB-388 / F4: fetch integrity

    /**
     * The document decides where every byte of egress goes, and on the CONNECT path who receives
     * the --proxy-userpwd credential. Fetched in cleartext it is whatever the network says it is,
     * and owning the WPAD name on a corporate LAN -- the classic position for that -- is exactly
     * where --pac-local gets used.
     */
    @Test
    void aPlainHttpUrlIsRefusedWhenNotPinned() {
        assertThatThrownBy(() -> PacPolicy.load("http://wpad.corp/proxy.pac"))
                .isInstanceOf(PacException.class)
                .hasMessageContaining("plain HTTP")
                .hasMessageContaining("--pac-local-sha256");
    }

    /** The refusal is about authenticity, so it fires before anything is fetched. */
    @Test
    void thePlainHttpRefusalDoesNotEvenConnect() throws Exception {
        java.net.ServerSocket probe = new java.net.ServerSocket(0);
        int free = probe.getLocalPort();
        probe.close();

        // Nothing is listening, yet the message is about http, not about a failed connection.
        assertThatThrownBy(() -> PacPolicy.load("http://127.0.0.1:" + free + "/proxy.pac"))
                .isInstanceOf(PacException.class)
                .hasMessageContaining("plain HTTP");
    }

    @Test
    void aPinnedDocumentThatMatchesIsAccepted(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("corp.pac");
        Files.writeString(file, SIMPLE);
        String digest = PacPolicy.sha256Hex(Files.readAllBytes(file));

        PacPolicy policy = PacPolicy.load(file.toString(), digest);

        assertThat(policy.resolve("http://a.corp/", "a.corp").isDirect()).isTrue();
    }

    @Test
    void aPinnedDocumentThatDoesNotMatchIsRefused(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("corp.pac");
        Files.writeString(file, SIMPLE);

        assertThatThrownBy(() -> PacPolicy.load(file.toString(), ANY_PIN))
                .isInstanceOf(PacException.class)
                .hasMessageContaining("does not match");
    }

    /** A pin that could never match is a pin that is not doing anything; say so at startup. */
    @Test
    void aMalformedPinIsRefusedRatherThanNeverMatching(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("corp.pac");
        Files.writeString(file, SIMPLE);

        assertThatThrownBy(() -> PacPolicy.load(file.toString(), "not-a-digest"))
                .isInstanceOf(PacException.class)
                .hasMessageContaining("64 hex characters");
    }

    @Test
    void theDigestIsCaseAndWhitespaceInsensitive(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("corp.pac");
        Files.writeString(file, SIMPLE);
        String digest = PacPolicy.sha256Hex(Files.readAllBytes(file));

        assertThatCode(() -> PacPolicy.load(file.toString(),
                "  " + digest.toUpperCase(java.util.Locale.ROOT) + "  "))
                .doesNotThrowAnyException();
    }

    /**
     * A redirect means the document came from somewhere other than the place that was
     * configured, which is the thing being established.
     */
    @Test
    void aRedirectIsRefusedRatherThanFollowed() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/proxy.pac", exchange -> {
            exchange.getResponseHeaders().add("Location", "http://elsewhere.invalid/other.pac");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/proxy.pac";

            assertThatThrownBy(() -> PacPolicy.load(url, ANY_PIN))
                    .isInstanceOf(PacException.class)
                    .hasMessageContaining("redirect");
        } finally {
            server.stop(0);
        }
    }
}
