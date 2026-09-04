package com.testingbot.tunnel.proxy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@code --dns} with more than one server, plus {@code --dns-timeout} and
 * {@code --dns-round-robin}.
 *
 * <p>Driven against real UDP servers rather than a mocked Resolver: the behaviour that matters
 * is what happens when a server does not answer, and a stub that returns null on demand would
 * be testing the stub. These answer, refuse, or stay silent as the case requires.
 */
class CustomDnsResolverMultiServerTest {

    /** A minimal authoritative server: answers for one name, NXDOMAIN otherwise. */
    private static final class FakeDns implements AutoCloseable {
        private final DatagramSocket socket;
        private final Thread thread;
        private final AtomicInteger queries = new AtomicInteger();
        private final AtomicBoolean silent = new AtomicBoolean();
        private final String name;
        private final byte[] address;

        FakeDns(String name, String address, boolean silent) throws IOException {
            this.name = name.toLowerCase();
            this.address = InetAddress.getByName(address).getAddress();
            this.silent.set(silent);
            this.socket = new DatagramSocket(0, InetAddress.getLoopbackAddress());
            this.thread = new Thread(this::serve);
            this.thread.setDaemon(true);
            this.thread.start();
        }

        int port() {
            return socket.getLocalPort();
        }

        int queryCount() {
            return queries.get();
        }

        private void serve() {
            byte[] buffer = new byte[512];
            while (!socket.isClosed()) {
                try {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    socket.receive(packet);
                    queries.incrementAndGet();
                    if (silent.get()) {
                        continue;                       // accept and never answer
                    }
                    byte[] response = respond(packet.getData(), packet.getLength());
                    if (response != null) {
                        socket.send(new DatagramPacket(response, response.length,
                                packet.getAddress(), packet.getPort()));
                    }
                } catch (IOException closed) {
                    return;
                }
            }
        }

        /** Parses the question and answers an A record for the one name we serve. */
        private byte[] respond(byte[] query, int length) {
            int offset = 12;
            StringBuilder queried = new StringBuilder();
            while (offset < length) {
                int labelLength = query[offset] & 0xFF;
                offset++;
                if (labelLength == 0) {
                    break;
                }
                if (queried.length() > 0) {
                    queried.append('.');
                }
                queried.append(new String(query, offset, labelLength,
                        java.nio.charset.StandardCharsets.US_ASCII));
                offset += labelLength;
            }
            if (offset + 4 > length) {
                return null;
            }
            int qtype = ((query[offset] & 0xFF) << 8) | (query[offset + 1] & 0xFF);
            int questionEnd = offset + 4;

            List<Byte> out = new ArrayList<>();
            // Header: id echoed, response + authoritative, one question.
            out.add(query[0]);
            out.add(query[1]);
            boolean answering = qtype == 1 && queried.toString().equalsIgnoreCase(name);
            out.add((byte) 0x84);
            out.add((byte) (answering ? 0x00 : 0x03));   // NXDOMAIN when we do not serve it
            addShort(out, 1);
            addShort(out, answering ? 1 : 0);
            addShort(out, 0);
            addShort(out, 0);
            for (int i = 12; i < questionEnd; i++) {
                out.add(query[i]);
            }
            if (answering) {
                out.add((byte) 0xC0);
                out.add((byte) 0x0C);                    // pointer to the question's name
                addShort(out, 1);                        // A
                addShort(out, 1);                        // IN
                addShort(out, 0);
                addShort(out, 60);                       // TTL
                addShort(out, address.length);
                for (byte b : address) {
                    out.add(b);
                }
            }
            byte[] response = new byte[out.size()];
            for (int i = 0; i < response.length; i++) {
                response[i] = out.get(i);
            }
            return response;
        }

        private static void addShort(List<Byte> out, int value) {
            out.add((byte) ((value >> 8) & 0xFF));
            out.add((byte) (value & 0xFF));
        }

        @Override
        public void close() {
            socket.close();
            thread.interrupt();
        }
    }

    private final List<FakeDns> servers = new CopyOnWriteArrayList<>();

    private FakeDns server(String name, String address) throws IOException {
        FakeDns dns = new FakeDns(name, address, false);
        servers.add(dns);
        return dns;
    }

    private FakeDns silentServer() throws IOException {
        FakeDns dns = new FakeDns("unused.invalid", "127.0.0.1", true);
        servers.add(dns);
        return dns;
    }

    @AfterEach
    void tearDown() {
        servers.forEach(FakeDns::close);
        servers.clear();
    }

    private static String spec(FakeDns... dns) {
        StringBuilder out = new StringBuilder();
        for (FakeDns server : dns) {
            if (out.length() > 0) {
                out.append(',');
            }
            out.append("127.0.0.1:").append(server.port());
        }
        return out.toString();
    }

    @Test
    void aSingleServerStillWorksAsBefore() throws Exception {
        FakeDns only = server("internal.example", "10.1.2.3");

        CustomDnsResolver resolver = CustomDnsResolver.create(spec(only));

        assertThat(resolver.serverCount()).isEqualTo(1);
        assertThat(resolver.resolve("internal.example")[0].getHostAddress())
                .isEqualTo("10.1.2.3");
    }

    @Test
    void theSecondServerAnswersWhenTheFirstDoesNot() throws Exception {
        // The point of a list: an internal resolver with a standby behind it.
        FakeDns primary = silentServer();
        FakeDns standby = server("internal.example", "10.9.9.9");

        CustomDnsResolver resolver = CustomDnsResolver.create(
                spec(primary, standby), Duration.ofMillis(400), false);

        assertThat(resolver.resolve("internal.example")[0].getHostAddress())
                .isEqualTo("10.9.9.9");
        assertThat(primary.queryCount())
                .as("the primary should have been tried first")
                .isGreaterThan(0);
    }

    @Test
    void theFirstServerIsPreferredWhenItAnswers() throws Exception {
        FakeDns primary = server("internal.example", "10.1.1.1");
        FakeDns standby = server("internal.example", "10.2.2.2");

        CustomDnsResolver resolver = CustomDnsResolver.create(spec(primary, standby));

        for (int i = 0; i < 4; i++) {
            assertThat(resolver.resolve("internal.example")[0].getHostAddress())
                    .isEqualTo("10.1.1.1");
        }
        assertThat(standby.queryCount())
                .as("without round robin the standby should not be consulted at all")
                .isZero();
    }

    @Test
    void roundRobinSpreadsQueriesAcrossTheServers() throws Exception {
        FakeDns first = server("internal.example", "10.1.1.1");
        FakeDns second = server("internal.example", "10.2.2.2");

        CustomDnsResolver resolver = CustomDnsResolver.create(
                spec(first, second), CustomDnsResolver.DEFAULT_QUERY_TIMEOUT, true);

        for (int i = 0; i < 6; i++) {
            resolver.resolve("internal.example");
        }

        assertThat(first.queryCount()).isPositive();
        assertThat(second.queryCount())
                .as("both members of an equal pool should be used")
                .isPositive();
    }

    @Test
    void roundRobinStillFallsThroughToAWorkingServer() throws Exception {
        // Spreading load must not mean a query dies on whichever server it landed on.
        FakeDns broken = silentServer();
        FakeDns working = server("internal.example", "10.3.3.3");

        CustomDnsResolver resolver = CustomDnsResolver.create(
                spec(broken, working), Duration.ofMillis(400), true);

        for (int i = 0; i < 4; i++) {
            assertThat(resolver.resolve("internal.example")[0].getHostAddress())
                    .isEqualTo("10.3.3.3");
        }
    }

    @Test
    void anUnusableEntryIsDroppedAndTheRestAreKept() throws Exception {
        // One bad entry in a list should not cost the user the servers that do work.
        FakeDns working = server("internal.example", "10.4.4.4");

        CustomDnsResolver resolver = CustomDnsResolver.create(
                "no-such-host.invalid," + spec(working), Duration.ofSeconds(2), false);

        assertThat(resolver.serverCount()).isEqualTo(1);
        assertThat(resolver.resolve("internal.example")[0].getHostAddress())
                .isEqualTo("10.4.4.4");
    }

    @Test
    void nothingUsableFallsBackToThePlatformResolver() {
        // Null means "keep the system resolver", which is better than failing every lookup.
        assertThat(CustomDnsResolver.create("no-such-host.invalid", Duration.ofSeconds(1), false))
                .isNull();
        assertThat(CustomDnsResolver.create("  ")).isNull();
        assertThat(CustomDnsResolver.create(null)).isNull();
    }

    @Test
    void aNameNoServerKnowsFallsBackToThePlatformResolver() throws Exception {
        FakeDns only = server("internal.example", "10.5.5.5");
        CustomDnsResolver resolver = CustomDnsResolver.create(spec(only));

        // localhost is not served here, so answering it at all proves the fallback ran.
        assertThat(resolver.resolve("localhost")).isNotEmpty();
    }

    @Test
    void aNameNothingCanResolveStillFails() throws Exception {
        FakeDns only = server("internal.example", "10.6.6.6");
        CustomDnsResolver resolver = CustomDnsResolver.create(spec(only));

        // Some networks answer everything, so this only means anything where the platform itself
        // says the name does not exist.
        String bogus = "definitely-not-a-real-host.invalid";
        assumeTrue(platformRefuses(bogus), "this network resolves names that do not exist");

        // Failure has to be a thrown UnknownHostException, not an empty array and not null: a
        // caller that gets either of those back dials nothing and reports nothing. The previous
        // shape -- a try/catch whose catch asserted the caught exception was of the type it was
        // declared as -- was satisfied by all three.
        assertThatThrownBy(() -> resolver.resolve(bogus))
                .isInstanceOf(UnknownHostException.class);
    }

    private static boolean platformRefuses(String host) {
        try {
            InetAddress.getAllByName(host);
            return false;
        } catch (UnknownHostException nxdomain) {
            return true;
        }
    }

    /**
     * An IP literal must not be sent to a DNS server at all.
     *
     * <p>Asserted by counting queries. The version of this test that lived in
     * CustomDnsResolverTest pointed at a dead port and asserted the literal came back, which
     * held with the shortcut deleted too: the query simply failed and the platform fallback
     * returned the literal anyway. Verified by removing the shortcut -- that version stayed
     * green, this one does not.
     */
    @Test
    void ipLiteralsAreNeverQueried() throws Exception {
        try (FakeDns dns = new FakeDns("irrelevant.example", "203.0.113.9", false)) {
            CustomDnsResolver resolver = CustomDnsResolver.create("127.0.0.1:" + dns.port());

            assertThat(resolver.resolve("198.51.100.4")[0].getHostAddress())
                    .isEqualTo("198.51.100.4");
            assertThat(dns.queryCount())
                    .as("an IP literal needs no resolution")
                    .isZero();
        }
    }
}
