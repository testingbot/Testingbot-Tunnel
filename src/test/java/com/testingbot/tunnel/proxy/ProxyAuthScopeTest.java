package com.testingbot.tunnel.proxy;

import org.junit.jupiter.api.Test;

import java.net.Authenticator;
import java.net.PasswordAuthentication;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Who is allowed to receive the {@code --proxy-userpwd} credentials.
 *
 * <p>This is installed as the JVM-wide default Authenticator, and it used to return the password
 * to every caller unconditionally: no requestor type, no host, no port, no protocol. Anything in
 * the process that consulted the default authenticator got the customer's proxy password, and in
 * an embedded deployment that includes the host application's own traffic.
 */
class ProxyAuthScopeTest {

    /** Asks an authenticator directly, the way the JDK would. */
    private static PasswordAuthentication ask(Authenticator authenticator, String host, int port,
                                              String protocol, Authenticator.RequestorType type) {
        return Authenticator.requestPasswordAuthentication(
                authenticator, host, null, port, protocol, "", null, null, type);
    }

    @Test
    void theConfiguredProxyGetsTheCredentials() {
        ProxyAuth auth = new ProxyAuth("user", "secret", "proxy.example", 8080);

        PasswordAuthentication answer =
                ask(auth, "proxy.example", 8080, "http", Authenticator.RequestorType.PROXY);

        assertThat(answer).isNotNull();
        assertThat(answer.getUserName()).isEqualTo("user");
        assertThat(new String(answer.getPassword())).isEqualTo("secret");
    }

    @Test
    void anotherHostGetsNothing() {
        // The leak: this returned the password before.
        ProxyAuth auth = new ProxyAuth("user", "secret", "proxy.example", 8080);

        assertThat(ask(auth, "evil.example", 8080, "http", Authenticator.RequestorType.PROXY))
                .isNull();
    }

    @Test
    void anotherPortOnTheSameHostGetsNothing() {
        ProxyAuth auth = new ProxyAuth("user", "secret", "proxy.example", 8080);

        assertThat(ask(auth, "proxy.example", 3128, "http", Authenticator.RequestorType.PROXY))
                .isNull();
    }

    @Test
    void anOrdinaryServerChallengeGetsNothing() {
        // A site asking for credentials is not a proxy asking, and the difference is the whole
        // point: proxy credentials must not travel to origins the tests happen to visit.
        ProxyAuth auth = new ProxyAuth("user", "secret", "proxy.example", 8080);

        assertThat(ask(auth, "proxy.example", 8080, "http", Authenticator.RequestorType.SERVER))
                .isNull();
    }

    @Test
    void withoutAKnownProxyOnlyProxyChallengesAreAnswered() {
        // The host is not always known when the credentials are parsed. Answering any proxy
        // challenge is still far narrower than answering everything.
        ProxyAuth auth = new ProxyAuth("user", "secret");

        assertThat(ask(auth, "anything.example", 8080, "http",
                Authenticator.RequestorType.PROXY)).isNotNull();
        assertThat(ask(auth, "anything.example", 8080, "http",
                Authenticator.RequestorType.SERVER)).isNull();
    }

    @Test
    void anEmptyPasswordIsStillHandledWithoutFailing() {
        ProxyAuth auth = new ProxyAuth("user", null, "proxy.example", 8080);

        PasswordAuthentication answer =
                ask(auth, "proxy.example", 8080, "http", Authenticator.RequestorType.PROXY);
        assertThat(answer).isNotNull();
        assertThat(answer.getPassword()).isEmpty();
    }
}
