package com.testingbot.tunnel;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.cli.PosixParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code --http-dial-timeout} and {@code --http-idle-timeout}.
 *
 * <p>Both default to null rather than to a number, because the built-in values differ by path on
 * purpose: the API should give up quickly, a CONNECT tunnel should not. One value for all of
 * them would be worse than either, so an absent flag has to leave every path alone -- which is
 * the first thing these tests check.
 */
class HttpTimeoutOptionsTest {

    private static CommandLine parse(String... args) throws ParseException {
        return new PosixParser().parse(App.buildOptions(), args);
    }

    private static App configured(String... args) throws Exception {
        App app = new App();
        App.applyOptions(app, parse(args));
        return app;
    }

    @Test
    void absentOptionsLeaveEveryPathOnItsOwnDefault() throws Exception {
        App app = configured();

        assertThat(app.getHttpDialTimeoutSeconds()).isNull();
        assertThat(app.getHttpIdleTimeoutSeconds()).isNull();
    }

    @Test
    void bothAreReadAsSeconds() throws Exception {
        App app = configured("--http-dial-timeout", "3", "--http-idle-timeout", "600");

        assertThat(app.getHttpDialTimeoutSeconds()).isEqualTo(3);
        assertThat(app.getHttpIdleTimeoutSeconds()).isEqualTo(600);
    }

    @Test
    void zeroAndNegativeAreRefused() {
        // Zero means "no timeout" in some libraries and "immediately" in others. Refusing it is
        // clearer than picking one of those meanings on the user's behalf.
        assertThatThrownBy(() -> configured("--http-dial-timeout", "0"))
                .isInstanceOf(ParseException.class)
                .hasMessageContaining("--http-dial-timeout");
        assertThatThrownBy(() -> configured("--http-idle-timeout", "-1"))
                .isInstanceOf(ParseException.class)
                .hasMessageContaining("greater than zero");
    }

    @Test
    void aNonNumberIsRefusedByName() throws Exception {
        assertThatThrownBy(() -> configured("--http-dial-timeout", "30s"))
                .isInstanceOf(ParseException.class)
                .hasMessageContaining("--http-dial-timeout");
    }

    @Test
    void theApiDialTimeoutFollowsTheOption() throws Exception {
        // Read off the Api, not the App: asserting the getter would only re-test the setter,
        // and the wiring between them is the part that breaks.
        Api api = new Api(configured("--http-dial-timeout", "2"));

        java.lang.reflect.Field field = Api.class.getDeclaredField("connectTimeout");
        field.setAccessible(true);
        assertThat(field.get(api).toString()).isEqualTo("2 SECONDS");
    }

    @Test
    void theApiKeepsItsOwnDefaultWhenTheOptionIsAbsent() throws Exception {
        Api api = new Api(configured());

        java.lang.reflect.Field field = Api.class.getDeclaredField("connectTimeout");
        field.setAccessible(true);
        assertThat(field.get(api)).isEqualTo(Api.DEFAULT_CONNECT_TIMEOUT);
    }

    /** The CONNECT handler inside a running proxy, wherever it sits in the handler chain. */
    private static org.eclipse.jetty.server.handler.ConnectHandler findConnectHandler(
            HttpProxy proxy) throws Exception {
        java.lang.reflect.Field field = HttpProxy.class.getDeclaredField("httpProxy");
        field.setAccessible(true);
        org.eclipse.jetty.server.Server server =
                (org.eclipse.jetty.server.Server) field.get(proxy);
        return server.getDescendant(org.eclipse.jetty.server.handler.ConnectHandler.class);
    }

    private static int findFreePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    @Test
    void theOptionsSurviveIntoARunningProxy() throws Exception {
        // End to end through HttpProxy, which is what actually applies them to the handlers.
        App app = configured("--http-idle-timeout", "37");
        app.setJettyPort(findFreePort());
        app.setClientKey("test_key");
        app.setClientSecret("test_secret");

        HttpProxy proxy = new HttpProxy(app);
        try {
            for (int i = 0; i < 100; i++) {
                try (Socket s = new Socket("127.0.0.1", app.getJettyPort())) {
                    break;
                } catch (IOException retry) {
                    Thread.sleep(50);
                }
            }
            // The value has to have reached the CONNECT handler, which is what applies it to
            // tunnelled connections. Anything less proves only that the flag parsed.
            org.eclipse.jetty.server.handler.ConnectHandler connect = findConnectHandler(proxy);
            assertThat(connect).isNotNull();
            assertThat(connect.getIdleTimeout()).isEqualTo(37_000L);
        } finally {
            proxy.stop();
        }
    }
}
