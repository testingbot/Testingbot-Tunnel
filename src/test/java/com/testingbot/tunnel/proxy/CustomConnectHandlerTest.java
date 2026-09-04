package com.testingbot.tunnel.proxy;

import com.testingbot.tunnel.App;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class CustomConnectHandlerTest {

    private App app;
    private CustomConnectHandler handler;

    @BeforeEach
    void setUp() {
        app = new App();
        app.setClientKey("test_key");
        app.setClientSecret("test_secret");
    }






    @Test
    void isSuccessfulConnect_acceptsHttp200() {
        assertThat(CustomConnectHandler.isSuccessfulConnect("HTTP/1.1 200 Connection Established")).isTrue();
    }

    @Test
    void isSuccessfulConnect_acceptsHttp10() {
        assertThat(CustomConnectHandler.isSuccessfulConnect("HTTP/1.0 200 OK")).isTrue();
    }

    @Test
    void isSuccessfulConnect_acceptsAny2xx() {
        assertThat(CustomConnectHandler.isSuccessfulConnect("HTTP/1.1 201 Created")).isTrue();
        assertThat(CustomConnectHandler.isSuccessfulConnect("HTTP/1.1 299 Custom")).isTrue();
    }

    @Test
    void isSuccessfulConnect_rejectsHttp407() {
        assertThat(CustomConnectHandler.isSuccessfulConnect("HTTP/1.1 407 Proxy Authentication Required")).isFalse();
    }

    @Test
    void isSuccessfulConnect_rejectsHttp502() {
        assertThat(CustomConnectHandler.isSuccessfulConnect("HTTP/1.1 502 Bad Gateway")).isFalse();
    }

    @Test
    void isSuccessfulConnect_rejectsBodyLineWith200Substring() {
        // The old buggy check would have accepted this because "200" is a substring.
        assertThat(CustomConnectHandler.isSuccessfulConnect("Content-Length: 200")).isFalse();
    }

    @Test
    void isSuccessfulConnect_rejectsMalformedStatusLine() {
        assertThat(CustomConnectHandler.isSuccessfulConnect("garbage")).isFalse();
        assertThat(CustomConnectHandler.isSuccessfulConnect("")).isFalse();
        assertThat(CustomConnectHandler.isSuccessfulConnect(null)).isFalse();
        assertThat(CustomConnectHandler.isSuccessfulConnect("HTTP/1.1 notanumber OK")).isFalse();
    }







    @Test
    void setBlackList_silentlyIgnoresInvalidRegex() {
        handler = new CustomConnectHandler(app);
        handler.setBlackList(new String[]{"valid\\.com", "(unclosed"});

        // Not just "did not throw", which held if the bad entry took the good one down with it
        // or if setBlackList did nothing at all. The valid pattern must survive its neighbour.
        assertThat(handler.validateDestination("valid.com", 443)).isFalse();
        assertThat(handler.validateDestination("elsewhere.com", 443)).isTrue();
    }

    @Test
    void setBlackList_handlesNullAndEmpty() {
        handler = new CustomConnectHandler(app);

        // Each of these must leave the handler permitting everything, rather than merely not
        // throwing -- a blank entry compiled into a pattern would match every host and refuse
        // the lot, which "did not throw" could never have detected.
        handler.setBlackList(null);
        assertThat(handler.validateDestination("anything.com", 443)).isTrue();

        handler.setBlackList(new String[]{});
        assertThat(handler.validateDestination("anything.com", 443)).isTrue();

        handler.setBlackList(new String[]{"", null, "  "});
        assertThat(handler.validateDestination("anything.com", 443)).isTrue();
    }

    @Test
    void validateDestination_rejectsBlacklistedHost() {
        // ConnectHandler consults validateDestination() before dialling out and answers 403
        // itself, so this is where the fast-fail policy has to bite.
        CustomConnectHandler handler = new CustomConnectHandler(new App());
        handler.setBlackList(new String[]{"blocked\\.example\\.com"});

        assertThat(handler.validateDestination("blocked.example.com", 443)).isFalse();
    }

    @Test
    void validateDestination_allowsOtherHosts() {
        CustomConnectHandler handler = new CustomConnectHandler(new App());
        handler.setBlackList(new String[]{"blocked\\.example\\.com"});

        assertThat(handler.validateDestination("allowed.example.com", 443)).isTrue();
    }

    @Test
    void validateDestination_allowsEverythingWhenNoBlacklist() {
        CustomConnectHandler handler = new CustomConnectHandler(new App());

        assertThat(handler.validateDestination("anything.example.com", 443)).isTrue();
    }


}
