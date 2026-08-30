package com.testingbot.tunnel.proxy;

import org.eclipse.jetty.http.HttpFields;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The {@code --header} / {@code --response-header} grammar. */
class HeaderRulesTest {

    private static HeaderRules of(String... rules) {
        return HeaderRules.parse(rules);
    }

    private static HttpFields.Mutable fields(String... nameValuePairs) {
        HttpFields.Mutable f = HttpFields.build();
        for (int i = 0; i < nameValuePairs.length; i += 2) {
            f.add(nameValuePairs[i], nameValuePairs[i + 1]);
        }
        return f;
    }

    @Test
    void setRule_addsWhenAbsent() {
        HttpFields.Mutable f = fields();
        of("X-Test: hello").applyTo(f);

        assertThat(f.get("X-Test")).isEqualTo("hello");
    }

    @Test
    void setRule_replacesRatherThanAppending() {
        HttpFields.Mutable f = fields("X-Test", "original");
        of("X-Test: replaced").applyTo(f);

        assertThat(f.getValuesList("X-Test")).containsExactly("replaced");
    }

    @Test
    void valuesMayContainColonsAndCommas() {
        // CSP and Date values both do; splitting on every colon would mangle them.
        HttpFields.Mutable f = fields();
        of("Content-Security-Policy: default-src 'self'; img-src https://a.example, https://b.example")
                .applyTo(f);

        assertThat(f.get("Content-Security-Policy"))
                .isEqualTo("default-src 'self'; img-src https://a.example, https://b.example");
    }

    @Test
    void semicolonRule_setsAnEmptyValue() {
        HttpFields.Mutable f = fields("X-Test", "original");
        of("X-Test;").applyTo(f);

        assertThat(f.get("X-Test")).isEmpty();
    }

    @Test
    void dashRule_removes() {
        HttpFields.Mutable f = fields("X-Drop", "v", "X-Keep", "v");
        of("-X-Drop").applyTo(f);

        assertThat(f.get("X-Drop")).isNull();
        assertThat(f.get("X-Keep")).isEqualTo("v");
    }

    @Test
    void starRule_removesByPrefix() {
        HttpFields.Mutable f = fields("X-Internal-A", "1", "X-Internal-B", "2", "X-Other", "3");
        of("-X-Internal-*").applyTo(f);

        assertThat(f.get("X-Internal-A")).isNull();
        assertThat(f.get("X-Internal-B")).isNull();
        assertThat(f.get("X-Other")).isEqualTo("3");
    }

    @Test
    void matchingIsCaseInsensitive() {
        HttpFields.Mutable f = fields("x-drop", "v", "X-Set", "old");
        of("-X-DROP", "x-set: new").applyTo(f);

        assertThat(f.get("x-drop")).isNull();
        assertThat(f.getValuesList("X-Set")).containsExactly("new");
    }

    @Test
    void removalsApplyBeforeSets_soOrderOfArgumentsDoesNotMatter() {
        HttpFields.Mutable a = fields("X-Test", "origin");
        of("-X-Test", "X-Test: final").applyTo(a);
        assertThat(a.getValuesList("X-Test")).containsExactly("final");

        HttpFields.Mutable b = fields("X-Test", "origin");
        of("X-Test: final", "-X-Test").applyTo(b);
        assertThat(b.getValuesList("X-Test")).containsExactly("final");
    }

    @Test
    void drops_reportsWhatTheResponsePathMustNotCopy() {
        HeaderRules rules = of("-X-Gone", "-X-Pre-*", "X-Override: v");

        assertThat(rules.drops("X-Gone")).isTrue();
        assertThat(rules.drops("X-Pre-Anything")).isTrue();
        // A header we set replaces the origin's, so its copy must be dropped too.
        assertThat(rules.drops("x-override")).isTrue();
        assertThat(rules.drops("X-Untouched")).isFalse();
        assertThat(rules.drops(null)).isFalse();
    }

    @Test
    void emptyInputs_produceANoOp() {
        assertThat(HeaderRules.none().isEmpty()).isTrue();
        assertThat(of().isEmpty()).isTrue();
        assertThat(of("", "  ", null).isEmpty()).isTrue();

        HttpFields.Mutable f = fields("X-Test", "v");
        HeaderRules.none().applyTo(f);
        assertThat(f.get("X-Test")).isEqualTo("v");
    }

    @Test
    void malformedRules_areRejectedAtParseTime() {
        assertThatThrownBy(() -> of("no separator"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid header rule");
        assertThatThrownBy(() -> of(": novalue")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> of("-")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> of("bad name: v")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void valuesCarryingCrLf_areRejected() {
        // Otherwise a rule could inject further headers into every request the tunnel makes.
        assertThatThrownBy(() -> of("X-Test: a\r\nX-Injected: b"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CR or LF");
        assertThatThrownBy(() -> of("X-Test: a\nb"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
