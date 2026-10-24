package com.testingbot.tunnel.proxy;

import com.testingbot.tunnel.pac.PacResult;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.channels.UnresolvedAddressException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The egress for one request: a PAC answer's failover list, walked one dial at a time.
 *
 * <p>{@code "PROXY a:8080; PROXY b:8080; DIRECT"} means "a, and if a cannot be reached, b, and
 * failing that go direct". Only the first entry used to be dialled, so a PAC file whose primary
 * proxy was down answered every request 502 where a browser given the same file carried on
 * through the next entry.
 *
 * <p>Moving on is for a proxy that could not be reached, never for an answer: a proxy that
 * refused the destination or the credentials has spoken, and quietly trying another egress
 * would route around a decision the network made. A failed DIRECT dial is final for the same
 * reason browsers treat it so -- the destination itself is what failed.
 *
 * <p>One instance per request, kept as a request attribute, so the PAC file is evaluated once
 * per request and every attempt walks the same answer.
 */
final class PacRoute {

    /** In failover order; a null element is DIRECT. */
    private final List<ProxySpec> entries;
    private int index;

    private PacRoute(List<ProxySpec> entries) {
        this.entries = entries;
    }

    /** A route with no alternatives: the static {@code --proxy}, or DIRECT for null. */
    static PacRoute only(ProxySpec upstream) {
        return new PacRoute(Collections.singletonList(upstream));
    }

    static PacRoute of(PacResult result) {
        List<ProxySpec> entries = new ArrayList<>(result.getEntries().size());
        for (PacResult.Entry entry : result.getEntries()) {
            if (entry.isDirect()) {
                entries.add(null);
                continue;
            }
            ProxySpec spec = ProxySpec.parse(entry.toProxySpec());
            if (spec != null) {
                entries.add(spec);
            }
        }
        if (entries.isEmpty()) {
            // Not reachable from a PacResult, which validates its entries, but an empty list
            // here would otherwise throw on the request path.
            entries.add(null);
        }
        return new PacRoute(entries);
    }

    /** The entry to dial now; null means DIRECT. */
    synchronized ProxySpec current() {
        return entries.get(index);
    }

    /**
     * Moves to the next entry when {@code failure} means the current proxy could not be reached.
     *
     * @return true when there is a next entry to try, false when this failure is the answer
     */
    synchronized boolean failOver(Throwable failure) {
        if (entries.get(index) == null || index + 1 >= entries.size() || !isDialFailure(failure)) {
            return false;
        }
        index++;
        return true;
    }

    /** "PROXY host:port", "SOCKS host:port" or "DIRECT", for log lines. */
    static String describe(ProxySpec upstream) {
        if (upstream == null) {
            return "DIRECT";
        }
        return (upstream.isSocks5() ? "SOCKS " : "PROXY ") + upstream.getHost() + ":"
                + upstream.getPort();
    }

    /**
     * True when the connection itself could not be made: refused, timed out, no route, or a
     * name that does not resolve. Anything the far end said after accepting the connection --
     * a CONNECT answered 403 or 407, a SOCKS refusal -- is not, and neither is an idle timeout
     * (a {@code TimeoutException} in Jetty, where a connect timeout is a
     * {@code SocketTimeoutException}): by then the request may already have been sent, and
     * sending it again through another egress is a replay.
     */
    static boolean isDialFailure(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof UpstreamProxyRejection || t instanceof LocalhostPolicy.Denied) {
                return false;
            }
            if (t instanceof ConnectException
                    || t instanceof NoRouteToHostException
                    || t instanceof SocketTimeoutException
                    || t instanceof UnknownHostException
                    || t instanceof UnresolvedAddressException) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }
}
