package com.testingbot.tunnel.proxy;

import com.testingbot.tunnel.pac.PacResult;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.channels.UnresolvedAddressException;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

/** Which failures move a request down its PAC failover list, and in what order. */
class PacRouteTest {

    @Test
    void walksTheEntriesInOrderAndStopsAtTheLast() {
        PacRoute route = PacRoute.of(PacResult.parse("PROXY a:1; SOCKS b:2; DIRECT"));
        ConnectException refused = new ConnectException("Connection refused");

        assertThat(route.current().getHost()).isEqualTo("a");
        assertThat(route.failOver(refused)).isTrue();
        assertThat(route.current().getHost()).isEqualTo("b");
        assertThat(route.current().isSocks5()).isTrue();
        assertThat(route.failOver(refused)).isTrue();
        assertThat(route.current()).as("DIRECT").isNull();
        assertThat(route.failOver(refused)).as("nothing after the last entry").isFalse();
    }

    @Test
    void aFailedDirectDialIsFinal() {
        // The destination itself failed; browsers do not fall back from DIRECT either.
        PacRoute route = PacRoute.of(PacResult.parse("DIRECT; PROXY a:1"));

        assertThat(route.failOver(new ConnectException("Connection refused"))).isFalse();
        assertThat(route.current()).isNull();
    }

    @Test
    void aSingleProxyHasNothingToFailOverTo() {
        assertThat(PacRoute.only(ProxySpec.parse("p:3128"))
                .failOver(new ConnectException("refused"))).isFalse();
    }

    @Test
    void onlyAProxyThatCouldNotBeReachedCounts() {
        assertThat(PacRoute.isDialFailure(new ConnectException("refused"))).isTrue();
        assertThat(PacRoute.isDialFailure(new SocketTimeoutException("Connect Timeout"))).isTrue();
        assertThat(PacRoute.isDialFailure(new UnknownHostException("proxy.corp"))).isTrue();
        assertThat(PacRoute.isDialFailure(new UnresolvedAddressException())).isTrue();
        assertThat(PacRoute.isDialFailure(new IOException("wrapped", new ConnectException("x"))))
                .as("found through the cause chain").isTrue();

        // The proxy answered: its decision, not a reason to try another egress.
        assertThat(PacRoute.isDialFailure(new UpstreamProxyRejection("rejected",
                "HTTP/1.1 403 Forbidden"))).isFalse();
        assertThat(PacRoute.isDialFailure(new LocalhostPolicy.Denied("localhost",
                InetAddress.getLoopbackAddress()))).isFalse();
        // An idle timeout may come after the request was sent; retrying it is a replay.
        assertThat(PacRoute.isDialFailure(new TimeoutException("Idle timeout expired"))).isFalse();
        assertThat(PacRoute.isDialFailure(new IOException("Connection reset"))).isFalse();
        assertThat(PacRoute.isDialFailure(null)).isFalse();
    }

    @Test
    void describesEntriesTheWayAPacFileWritesThem() {
        assertThat(PacRoute.describe(null)).isEqualTo("DIRECT");
        assertThat(PacRoute.describe(ProxySpec.parse("p:3128"))).isEqualTo("PROXY p:3128");
        assertThat(PacRoute.describe(ProxySpec.parse("socks5://s:1080"))).isEqualTo("SOCKS s:1080");
    }
}
