package com.testingbot.tunnel.proxy;

import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLHandshakeException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.channels.UnresolvedAddressException;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

class ProxyErrorsTest {

    @Test
    void dnsFailures_areClassified() {
        assertThat(ProxyErrors.classify(new UnknownHostException("nope.invalid")))
                .isEqualTo(ProxyErrors.Reason.DNS_ERROR);
        assertThat(ProxyErrors.classify(new UnresolvedAddressException()))
                .isEqualTo(ProxyErrors.Reason.DNS_ERROR);
    }

    @Test
    void refusedAndTimedOutConnections_areDistinguished() {
        // ConnectException covers both; only the message separates them, and conflating them
        // sends people to debug the wrong thing (firewall drop vs nothing listening).
        assertThat(ProxyErrors.classify(new ConnectException("Connection refused")))
                .isEqualTo(ProxyErrors.Reason.CONNECTION_REFUSED);
        assertThat(ProxyErrors.classify(new ConnectException("Operation timed out")))
                .isEqualTo(ProxyErrors.Reason.CONNECTION_TIMEOUT);
    }

    @Test
    void timeoutsAndRoutingFailures_areClassified() {
        assertThat(ProxyErrors.classify(new SocketTimeoutException()))
                .isEqualTo(ProxyErrors.Reason.CONNECTION_TIMEOUT);
        assertThat(ProxyErrors.classify(new TimeoutException()))
                .isEqualTo(ProxyErrors.Reason.CONNECTION_TIMEOUT);
        assertThat(ProxyErrors.classify(new NoRouteToHostException()))
                .isEqualTo(ProxyErrors.Reason.HOST_UNREACHABLE);
    }

    @Test
    void tlsAndMalformedUri_areClassified() {
        assertThat(ProxyErrors.classify(new SSLHandshakeException("bad cert")))
                .isEqualTo(ProxyErrors.Reason.TLS_ERROR);
        assertThat(ProxyErrors.classify(new IllegalArgumentException("Bad URI % encoding")))
                .isEqualTo(ProxyErrors.Reason.MALFORMED_REQUEST_URI);
    }

    @Test
    void upstreamProxyFailures_areClassified() {
        // These come from our own handshake code, which reports via IOException messages.
        assertThat(ProxyErrors.classify(
                new IOException("Upstream SOCKS proxy rejected the supplied credentials")))
                .isEqualTo(ProxyErrors.Reason.UPSTREAM_PROXY_AUTH_FAILED);
        assertThat(ProxyErrors.classify(
                new IOException("Timed out waiting for upstream proxy 10.0.0.1:8080")))
                .isEqualTo(ProxyErrors.Reason.UPSTREAM_PROXY_UNREACHABLE);
    }

    @Test
    void wrappedCauses_areUnwrapped() {
        // By the time a failure reaches a handler it is usually wrapped at least once.
        Throwable wrapped = new RuntimeException("proxying failed",
                new IllegalStateException("layer", new UnknownHostException("host.invalid")));

        assertThat(ProxyErrors.classify(wrapped)).isEqualTo(ProxyErrors.Reason.DNS_ERROR);
    }

    @Test
    void selfReferencingCause_doesNotLoop() {
        // A cause chain that points at itself would otherwise spin forever.
        Exception loop = new Exception("loop") {
            @Override
            public synchronized Throwable getCause() {
                return this;
            }
        };

        assertThat(ProxyErrors.classify(loop)).isEqualTo(ProxyErrors.Reason.TUNNEL_ERROR);
    }

    @Test
    void unknownFailures_fallBackToTunnelError() {
        assertThat(ProxyErrors.classify(new RuntimeException("something odd")))
                .isEqualTo(ProxyErrors.Reason.TUNNEL_ERROR);
        assertThat(ProxyErrors.classify(null)).isEqualTo(ProxyErrors.Reason.TUNNEL_ERROR);
    }

    @Test
    void statusesMatchTheKindOfFailure() {
        // The header carries the detail; the status still has to be conventional enough for
        // clients and proxies in between to behave sensibly.
        assertThat(ProxyErrors.Reason.DENIED_BY_FAST_FAIL.status()).isEqualTo(403);
        assertThat(ProxyErrors.Reason.MALFORMED_REQUEST_URI.status()).isEqualTo(400);
        assertThat(ProxyErrors.Reason.CONNECTION_TIMEOUT.status()).isEqualTo(504);
        assertThat(ProxyErrors.Reason.DNS_ERROR.status()).isEqualTo(502);
    }

    @Test
    void reasonStringsAreStableAndMachineReadable() {
        // These appear in headers, metric labels and support tickets, so they must not drift
        // into prose. Lowercase and hyphenated, no spaces.
        for (ProxyErrors.Reason reason : ProxyErrors.Reason.values()) {
            assertThat(reason.reason())
                    .as("reason string for %s", reason)
                    .matches("[a-z0-9]+(-[a-z0-9]+)*");
            assertThat(reason.explanation()).isNotBlank();
        }
    }
}
