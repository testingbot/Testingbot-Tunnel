package com.testingbot.tunnel.proxy;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Parsing for {@code --log-http}. */
class HttpLogHandlerModeTest {

    @Test
    void defaultsToErrors() {
        // Quiet in normal use, self-diagnosing when a test fails.
        assertThat(HttpLogHandler.Mode.parse(null)).isEqualTo(HttpLogHandler.Mode.ERRORS);
        assertThat(HttpLogHandler.Mode.parse("")).isEqualTo(HttpLogHandler.Mode.ERRORS);
        assertThat(HttpLogHandler.Mode.parse("   ")).isEqualTo(HttpLogHandler.Mode.ERRORS);
    }

    @Test
    void parsesEveryModeCaseInsensitively() {
        assertThat(HttpLogHandler.Mode.parse("none")).isEqualTo(HttpLogHandler.Mode.NONE);
        assertThat(HttpLogHandler.Mode.parse("URL")).isEqualTo(HttpLogHandler.Mode.URL);
        assertThat(HttpLogHandler.Mode.parse(" Headers ")).isEqualTo(HttpLogHandler.Mode.HEADERS);
        assertThat(HttpLogHandler.Mode.parse("errors")).isEqualTo(HttpLogHandler.Mode.ERRORS);
    }

    @Test
    void unknownModeIsRejected() {
        assertThatThrownBy(() -> HttpLogHandler.Mode.parse("verbose"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void requestIdHeaderDefaults() {
        assertThat(new HttpLogHandler(HttpLogHandler.Mode.URL, null).getRequestIdHeader())
                .isEqualTo("X-Request-Id");
        assertThat(new HttpLogHandler(HttpLogHandler.Mode.URL, "").getRequestIdHeader())
                .isEqualTo("X-Request-Id");
        assertThat(new HttpLogHandler(HttpLogHandler.Mode.URL, "X-Trace").getRequestIdHeader())
                .isEqualTo("X-Trace");
    }
}
