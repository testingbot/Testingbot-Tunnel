package com.testingbot.tunnel.pac;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What a PAC file that fails at runtime means, and what a local file is allowed to be.
 *
 * <p>Evaluation failure used to become {@link PacResult#direct()}. That is not a neutral
 * default: on a network whose only sanctioned egress is a proxy it takes traffic the operator
 * routed deliberately and sends it straight out, past a configured {@code --proxy} as well. The
 * component whose entire job is deciding where traffic goes was failing open, and the one word
 * in the log was "direct".
 */
class PacFailureFallbackTest {

    @TempDir
    Path tempDir;

    /** A file that parses but throws when evaluated -- an undefined function is the easy way. */
    private static PacPolicy throwingPolicy() {
        return PacPolicy.of(
                "function FindProxyForURL(url, host) { return noSuchFunction(host); }",
                "throwing.pac");
    }

    @Test
    void aFailedEvaluationIsReportedAsUnknownRatherThanDirect() {
        PacPolicy policy = throwingPolicy();

        assertThat(policy.resolveOrNull("http://example.com/", "example.com"))
                .as("null means 'could not decide', which the caller answers with --proxy; "
                        + "DIRECT would mean 'decided: send it straight out'")
                .isNull();
    }

    @Test
    void theLegacyResolveStillAnswersDirectSoNothingElseChangesShape() {
        // resolve() keeps its old signature and its old answer for callers that have no
        // fallback to offer; resolveOrNull is what lets the proxy handlers do better.
        assertThat(throwingPolicy().resolve("http://example.com/", "example.com").isDirect())
                .isTrue();
    }

    @Test
    void aFailureIsNotCached() {
        // A failure is usually about this evaluation -- a dnsResolve that timed out, say -- and
        // caching it would hold the fallback route in place for the full cache TTL after the
        // condition had passed.
        PacPolicy policy = throwingPolicy();

        assertThat(policy.resolveOrNull("http://example.com/", "example.com")).isNull();
        assertThat(policy.resolveOrNull("http://example.com/", "example.com")).isNull();
    }

    @Test
    void aWorkingFileIsUnaffected() {
        PacPolicy policy = PacPolicy.of(
                "function FindProxyForURL(url, host) { return \"PROXY p.example:3128\"; }",
                "ok.pac");

        PacResult result = policy.resolveOrNull("http://example.com/", "example.com");

        assertThat(result).isNotNull();
        assertThat(result.first().toProxySpec()).isEqualTo("p.example:3128");
    }

    @Test
    void aDeliberateDirectIsStillDirect() {
        // The distinction that matters: a file that says DIRECT decided that, and must not be
        // rerouted through --proxy just because failures now fall back there.
        PacPolicy policy = PacPolicy.of(
                "function FindProxyForURL(url, host) { return \"DIRECT\"; }", "direct.pac");

        PacResult result = policy.resolveOrNull("http://example.com/", "example.com");

        assertThat(result).isNotNull();
        assertThat(result.isDirect()).isTrue();
    }

    @Test
    void anOversizedLocalFileIsRefusedLikeAnOversizedRemoteOne() throws Exception {
        // readAllBytes() had no limit, so the cap depended on where the bytes came from. A path
        // that is not the small script it was meant to be -- a log, the wrong file entirely --
        // was read into memory in full before anything looked at it.
        Path oversized = tempDir.resolve("huge.pac");
        byte[] filler = new byte[PacPolicy.MAX_PAC_BYTES + 1024];
        java.util.Arrays.fill(filler, (byte) ' ');
        Files.write(oversized, filler);

        assertThatThrownBy(() -> PacPolicy.load(oversized.toString()))
                .isInstanceOf(PacException.class)
                .hasMessageContaining("larger than");
    }

    @Test
    void aNormalLocalFileStillLoads() throws Exception {
        Path file = tempDir.resolve("ok.pac");
        Files.write(file, "function FindProxyForURL(url, host) { return \"DIRECT\"; }"
                .getBytes(StandardCharsets.UTF_8));

        PacPolicy policy = PacPolicy.load(file.toString());

        assertThat(policy.resolveOrNull("http://example.com/", "example.com").isDirect()).isTrue();
    }
}
