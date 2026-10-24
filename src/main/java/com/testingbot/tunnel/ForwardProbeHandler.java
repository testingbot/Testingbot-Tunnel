package com.testingbot.tunnel;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;

/**
 * Answers the reverse-forward probe ({@code SSHTunnel.probeReverseForward}) from the local proxy
 * itself, before anything is proxied or logged.
 *
 * <p>The probe enters port 2010 on the tunnel server and must come back out here; echoing its
 * nonce proves the round trip went through this client's forward and not through a listener some
 * other, dead session left behind. The host is in {@code .invalid}, which never resolves, so no
 * real request can match it.
 */
public class ForwardProbeHandler extends Handler.Wrapper {
    public static final String PROBE_HOST = "reverse-forward-probe.testingbot.invalid";
    public static final String PROBE_HEADER = "X-TestingBot-Probe";

    @Override
    public boolean handle(Request request, Response response, Callback callback) throws Exception {
        if (!PROBE_HOST.equalsIgnoreCase(Request.getServerName(request))) {
            return super.handle(request, response, callback);
        }
        String nonce = request.getHeaders().get(PROBE_HEADER);
        response.setStatus(HttpStatus.NO_CONTENT_204);
        if (nonce != null) {
            response.getHeaders().put(PROBE_HEADER, nonce);
        }
        response.getHeaders().put(HttpHeader.CONNECTION, "close");
        callback.succeeded();
        return true;
    }
}
