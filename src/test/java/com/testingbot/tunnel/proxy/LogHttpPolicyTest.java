package com.testingbot.tunnel.proxy;

import com.testingbot.tunnel.proxy.HttpLogHandler.Mode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Per-module {@code --log-http}.
 *
 * <p>The tunnel logs two unrelated streams -- browser traffic and the Selenium relay -- and
 * debugging one used to mean drowning in the other. The first thing checked here is that the
 * old spelling still means exactly what it did, since every existing user has one.
 */
class LogHttpPolicyTest {

    @Test
    void aBareLevelSetsEveryModule() {
        LogHttpPolicy policy = LogHttpPolicy.parse("url");

        assertThat(policy.modeFor(LogHttpPolicy.PROXY)).isEqualTo(Mode.URL);
        assertThat(policy.modeFor(LogHttpPolicy.FORWARDER)).isEqualTo(Mode.URL);
    }

    @Test
    void nothingConfiguredIsErrorsEverywhere() {
        for (String value : new String[]{null, "", "   "}) {
            LogHttpPolicy policy = LogHttpPolicy.parse(value);
            assertThat(policy.modeFor(LogHttpPolicy.PROXY)).isEqualTo(Mode.ERRORS);
            assertThat(policy.modeFor(LogHttpPolicy.FORWARDER)).isEqualTo(Mode.ERRORS);
        }
        assertThat(LogHttpPolicy.defaults().modeFor(LogHttpPolicy.PROXY)).isEqualTo(Mode.ERRORS);
    }

    @Test
    void modulesCanBeSetIndependently() {
        LogHttpPolicy policy = LogHttpPolicy.parse("proxy:none,forwarder:headers");

        assertThat(policy.modeFor(LogHttpPolicy.PROXY)).isEqualTo(Mode.NONE);
        assertThat(policy.modeFor(LogHttpPolicy.FORWARDER)).isEqualTo(Mode.HEADERS);
    }

    @Test
    void aNamedModuleOverridesTheBareLevelForItself() {
        // "everything quiet except the relay", which is the case this exists for.
        LogHttpPolicy policy = LogHttpPolicy.parse("none,forwarder:url");

        assertThat(policy.modeFor(LogHttpPolicy.PROXY)).isEqualTo(Mode.NONE);
        assertThat(policy.modeFor(LogHttpPolicy.FORWARDER)).isEqualTo(Mode.URL);
    }

    @Test
    void anUnnamedModuleKeepsTheDefaultWhenOnlyOneIsSet() {
        LogHttpPolicy policy = LogHttpPolicy.parse("forwarder:none");

        assertThat(policy.modeFor(LogHttpPolicy.FORWARDER)).isEqualTo(Mode.NONE);
        assertThat(policy.modeFor(LogHttpPolicy.PROXY))
                .as("a module nobody mentioned keeps the default")
                .isEqualTo(Mode.ERRORS);
    }

    @Test
    void caseAndSpacingDoNotMatter() {
        LogHttpPolicy policy = LogHttpPolicy.parse(" PROXY : Headers , forwarder:NONE ");

        assertThat(policy.modeFor(LogHttpPolicy.PROXY)).isEqualTo(Mode.HEADERS);
        assertThat(policy.modeFor(LogHttpPolicy.FORWARDER)).isEqualTo(Mode.NONE);
    }

    @Test
    void anUnknownModuleIsRefusedAndTheValidOnesListed() {
        // A typo would otherwise be accepted and quietly do nothing, which is the worst
        // outcome for a flag whose only job is to change what you see.
        assertThatThrownBy(() -> LogHttpPolicy.parse("proxxy:url"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("proxxy")
                .hasMessageContaining("proxy, forwarder");
    }

    @Test
    void anUnknownLevelIsRefused() {
        assertThatThrownBy(() -> LogHttpPolicy.parse("proxy:verbose"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("none, url, headers or errors");
        assertThatThrownBy(() -> LogHttpPolicy.parse("verbose"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("none, url, headers or errors");
    }
}
