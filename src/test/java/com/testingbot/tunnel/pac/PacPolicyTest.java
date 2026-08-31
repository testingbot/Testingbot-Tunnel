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
            PacPolicy policy = PacPolicy.load(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/proxy.pac");

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

            assertThatThrownBy(() -> PacPolicy.load(url))
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

            assertThatThrownBy(() -> PacPolicy.load("http://127.0.0.1:" + free + "/proxy.pac"))
                    .isInstanceOf(PacException.class)
                    .hasMessageContaining("Could not fetch PAC file");
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
    void repeatedLookupsForTheSameHostAreCached() {
        PacPolicy policy = PacPolicy.of(SIMPLE, "inline");

        policy.resolve("http://a.corp/", "a.corp");
        policy.resolve("http://a.corp/other", "a.corp");
        policy.resolve("http://b.com/", "b.com");

        assertThat(policy.cacheSize()).isEqualTo(2);
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
}
