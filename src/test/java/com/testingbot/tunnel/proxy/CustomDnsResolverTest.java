package com.testingbot.tunnel.proxy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import org.xbill.DNS.ARecord;
import org.xbill.DNS.DClass;
import org.xbill.DNS.Flags;
import org.xbill.DNS.Message;
import org.xbill.DNS.Record;
import org.xbill.DNS.Section;
import org.xbill.DNS.Type;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.UnknownHostException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises the resolver against a hand-written UDP DNS server, so the wire behaviour is
 * actually verified rather than assumed.
 *
 * <p>The point of --dns is to answer differently from the platform resolver, so the fake
 * server returns an address that could not come from anywhere else.
 */
class CustomDnsResolverTest {

    private DatagramSocket server;
    private Thread serverThread;

    @AfterEach
    void tearDown() {
        if (serverThread != null) {
            serverThread.interrupt();
        }
        if (server != null && !server.isClosed()) {
            server.close();
        }
    }

    /**
     * Answers every A query with {@code answer}.
     *
     * <p>The response is assembled with dnsjava's own Message API rather than hand-written
     * bytes: building DNS wire format by hand is easy to get subtly wrong, and a malformed
     * reply here would look exactly like a resolver bug.
     *
     * @return the port it listens on
     */
    private int startServer(String answer) throws Exception {
        server = new DatagramSocket(0, InetAddress.getLoopbackAddress());
        int port = server.getLocalPort();

        serverThread = new Thread(() -> {
            byte[] buf = new byte[512];
            while (!server.isClosed()) {
                try {
                    DatagramPacket request = new DatagramPacket(buf, buf.length);
                    server.receive(request);

                    Message query = new Message(java.util.Arrays.copyOf(request.getData(), request.getLength()));
                    Record question = query.getQuestion();

                    Message response = new Message(query.getHeader().getID());
                    response.getHeader().setFlag(Flags.QR);
                    response.getHeader().setFlag(Flags.AA);
                    response.addRecord(question, Section.QUESTION);
                    if (question.getType() == Type.A) {
                        response.addRecord(new ARecord(question.getName(), DClass.IN, 60,
                                InetAddress.getByName(answer)), Section.ANSWER);
                    }

                    byte[] wire = response.toWire();
                    server.send(new DatagramPacket(wire, wire.length,
                            request.getAddress(), request.getPort()));
                } catch (Exception e) {
                    return;
                }
            }
        });
        serverThread.setDaemon(true);
        serverThread.start();
        return port;
    }

    @Test
    void resolvesThroughTheConfiguredServer() throws Exception {
        // 203.0.113.7 is TEST-NET-3: it cannot be the answer from any real resolver,
        // which is what makes this prove the custom server was actually consulted.
        int port = startServer("203.0.113.7");
        CustomDnsResolver resolver = CustomDnsResolver.create("127.0.0.1:" + port);

        assertThat(resolver).isNotNull();
        InetAddress[] addresses = resolver.resolve("anything.internal");

        assertThat(addresses).isNotEmpty();
        assertThat(addresses[0].getHostAddress()).isEqualTo("203.0.113.7");
    }


    @Test
    void blankServer_yieldsNoResolver() {
        // Null means "use the platform resolver"; the option was simply not given.
        assertThat(CustomDnsResolver.create(null)).isNull();
        assertThat(CustomDnsResolver.create("")).isNull();
        assertThat(CustomDnsResolver.create("   ")).isNull();
    }

    @Test
    void unusableServerName_degradesToNoResolver() {
        // A misconfigured --dns must not take the tunnel down at startup.
        assertThat(CustomDnsResolver.create("no-such-host.invalid.")).isNull();
    }

    @Test
    void unreachableServer_fallsBackToTheSystemResolver() throws Exception {
        // Discard port on loopback: nothing answers, so the lookup times out.
        CustomDnsResolver resolver = CustomDnsResolver.create("127.0.0.1:9");
        assertThat(resolver).isNotNull();

        // localhost resolves via the platform resolver even though the DNS server is dead.
        assertThat(resolver.resolve("localhost")).isNotEmpty();
    }

    @Test
    void emptyHost_isRejected() {
        CustomDnsResolver resolver = CustomDnsResolver.create("127.0.0.1:53");
        assertThatThrownBy(() -> resolver.resolve(""))
                .isInstanceOf(UnknownHostException.class);
    }

    @Test
    void isIpLiteral_distinguishesNamesFromAddresses() {
        assertThat(CustomDnsResolver.isIpLiteral("192.168.1.1")).isTrue();
        assertThat(CustomDnsResolver.isIpLiteral("::1")).isTrue();
        assertThat(CustomDnsResolver.isIpLiteral("2001:db8::1")).isTrue();
        assertThat(CustomDnsResolver.isIpLiteral("example.com")).isFalse();
        assertThat(CustomDnsResolver.isIpLiteral("1.2.3")).isFalse();
        assertThat(CustomDnsResolver.isIpLiteral("staging.internal")).isFalse();
    }
}
