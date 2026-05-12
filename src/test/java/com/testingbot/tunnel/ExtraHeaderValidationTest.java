package com.testingbot.tunnel;

import org.apache.commons.cli.ParseException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExtraHeaderValidationTest {

    @Test
    void validHeader_passes() {
        assertThatCode(() -> App.validateHeader("X-Tenant", "abc123"))
            .doesNotThrowAnyException();
        assertThatCode(() -> App.validateHeader("Authorization", "Bearer xyz"))
            .doesNotThrowAnyException();
    }

    @Test
    void rejectsHeaderNameWithSpace() {
        assertThatThrownBy(() -> App.validateHeader("X Tenant", "abc"))
            .isInstanceOf(ParseException.class)
            .hasMessageContaining("Invalid header name");
    }

    @Test
    void rejectsEmptyHeaderName() {
        assertThatThrownBy(() -> App.validateHeader("", "abc"))
            .isInstanceOf(ParseException.class);
    }

    @Test
    void rejectsHeaderNameWithColon() {
        assertThatThrownBy(() -> App.validateHeader("X:Tenant", "abc"))
            .isInstanceOf(ParseException.class);
    }

    @Test
    void rejectsCRInValue() {
        assertThatThrownBy(() -> App.validateHeader("X-Tenant", "abc\rInjected: yes"))
            .isInstanceOf(ParseException.class)
            .hasMessageContaining("CR or LF");
    }

    @Test
    void rejectsLFInValue() {
        assertThatThrownBy(() -> App.validateHeader("X-Tenant", "abc\nX-Injected: yes"))
            .isInstanceOf(ParseException.class)
            .hasMessageContaining("CR or LF");
    }

    @Test
    void rejectsCRLFInValue() {
        assertThatThrownBy(() -> App.validateHeader("X-Tenant", "abc\r\nX-Injected: yes"))
            .isInstanceOf(ParseException.class);
    }
}
