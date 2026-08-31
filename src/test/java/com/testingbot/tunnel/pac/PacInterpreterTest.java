package com.testingbot.tunnel.pac;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The PAC subset interpreter, exercised the way real proxy auto-config files are written.
 *
 * <p>A misread PAC file silently routes a customer's traffic to the wrong place, so the cases
 * here lean on what actually appears in the wild -- shExpMatch chains, isInNet against
 * dnsResolve, plain-host checks -- rather than on synthetic language features.
 */
class PacInterpreterTest {

    /** A fixed world, so DNS and the clock cannot make these tests flaky. */
    private static PacInterpreter.PacEnvironment env(Map<String, String> dns, String myIp) {
        return new PacInterpreter.PacEnvironment() {
            @Override
            public String resolve(String host) {
                return dns.get(host);
            }

            @Override
            public String myIpAddress() {
                return myIp;
            }

            @Override
            public boolean weekdayRange(List<String> args) {
                return false;
            }

            @Override
            public boolean dateRange(List<String> args) {
                return false;
            }

            @Override
            public boolean timeRange(List<String> args) {
                return false;
            }
        };
    }

    private static PacInterpreter of(String source) {
        return new PacInterpreter(source, env(Map.of(), "10.0.0.5"));
    }

    private static String eval(String body, String url, String host) {
        return of("function FindProxyForURL(url, host) {" + body + "}").findProxyForUrl(url, host);
    }

    @Test
    void simplestPossibleFile() {
        assertThat(eval("return 'DIRECT';", "http://example.com/", "example.com"))
                .isEqualTo("DIRECT");
    }

    @Test
    void ifElseChainOnHost() {
        String body = """
                if (host == "intranet.corp") { return "DIRECT"; }
                else if (host == "build.corp") { return "PROXY build-proxy:3128"; }
                return "PROXY default:8080";
                """;
        assertThat(eval(body, "http://intranet.corp/", "intranet.corp")).isEqualTo("DIRECT");
        assertThat(eval(body, "http://build.corp/", "build.corp")).isEqualTo("PROXY build-proxy:3128");
        assertThat(eval(body, "http://other/", "other")).isEqualTo("PROXY default:8080");
    }

    @Test
    void isPlainHostName_distinguishesBareNames() {
        String body = "return isPlainHostName(host) ? 'DIRECT' : 'PROXY p:8080';";
        assertThat(eval(body, "http://intranet/", "intranet")).isEqualTo("DIRECT");
        assertThat(eval(body, "http://a.example.com/", "a.example.com")).isEqualTo("PROXY p:8080");
    }

    @Test
    void dnsDomainIs_matchesSuffix() {
        String body = "if (dnsDomainIs(host, '.example.com')) return 'DIRECT'; return 'PROXY p:8080';";
        assertThat(eval(body, "http://a.example.com/", "a.example.com")).isEqualTo("DIRECT");
        assertThat(eval(body, "http://example.org/", "example.org")).isEqualTo("PROXY p:8080");
    }

    @Test
    void localHostOrDomainIs_matchesBareAndFullyQualified() {
        String body = "return localHostOrDomainIs(host, 'www.example.com') ? 'DIRECT' : 'PROXY p:8080';";
        assertThat(eval(body, "http://www/", "www")).isEqualTo("DIRECT");
        assertThat(eval(body, "http://www.example.com/", "www.example.com")).isEqualTo("DIRECT");
        assertThat(eval(body, "http://other.example.com/", "other.example.com"))
                .isEqualTo("PROXY p:8080");
    }

    @Test
    void shExpMatch_handlesGlobsOnUrlAndHost() {
        String body = """
                if (shExpMatch(url, "http://*.internal/*")) return "DIRECT";
                if (shExpMatch(host, "*.example.??")) return "PROXY cc:8080";
                return "PROXY p:8080";
                """;
        assertThat(eval(body, "http://wiki.internal/page", "wiki.internal")).isEqualTo("DIRECT");
        assertThat(eval(body, "http://a.example.de/", "a.example.de")).isEqualTo("PROXY cc:8080");
        assertThat(eval(body, "http://a.example.com/", "a.example.com")).isEqualTo("PROXY p:8080");
    }

    @Test
    void shExpMatch_treatsDotsAsLiteralsNotRegex() {
        // A naive regex translation would let "aXexample.com" match "a.example.com".
        assertThat(PacInterpreter.shExpMatch("aXexample.com", "a.example.com")).isFalse();
        assertThat(PacInterpreter.shExpMatch("a.example.com", "a.example.com")).isTrue();
        assertThat(PacInterpreter.shExpMatch("a+b", "a+b")).isTrue();
    }

    @Test
    void isInNet_againstResolvedAddress() {
        String source = """
                function FindProxyForURL(url, host) {
                    if (isInNet(dnsResolve(host), "10.0.0.0", "255.0.0.0")) return "DIRECT";
                    return "PROXY p:8080";
                }
                """;
        PacInterpreter pac = new PacInterpreter(source,
                env(Map.of("inside.corp", "10.1.2.3", "outside.com", "93.184.216.34"), "10.0.0.5"));

        assertThat(pac.findProxyForUrl("http://inside.corp/", "inside.corp")).isEqualTo("DIRECT");
        assertThat(pac.findProxyForUrl("http://outside.com/", "outside.com"))
                .isEqualTo("PROXY p:8080");
    }

    @Test
    void isInNet_withUnresolvableHostIsFalseNotAnError() {
        // dnsResolve returns null; the file must still produce a directive.
        String source = """
                function FindProxyForURL(url, host) {
                    if (isInNet(dnsResolve(host), "10.0.0.0", "255.0.0.0")) return "DIRECT";
                    return "PROXY p:8080";
                }
                """;
        PacInterpreter pac = new PacInterpreter(source, env(Map.of(), "10.0.0.5"));

        assertThat(pac.findProxyForUrl("http://nope.invalid/", "nope.invalid"))
                .isEqualTo("PROXY p:8080");
    }

    @Test
    void isResolvableAndMyIpAddress() {
        String source = """
                function FindProxyForURL(url, host) {
                    if (!isResolvable(host)) return "DIRECT";
                    if (isInNet(myIpAddress(), "10.0.0.0", "255.0.0.0")) return "PROXY corp:8080";
                    return "PROXY home:8080";
                }
                """;
        PacInterpreter pac = new PacInterpreter(source,
                env(Map.of("known.com", "1.2.3.4"), "10.0.0.5"));

        assertThat(pac.findProxyForUrl("http://known.com/", "known.com")).isEqualTo("PROXY corp:8080");
        assertThat(pac.findProxyForUrl("http://gone.invalid/", "gone.invalid")).isEqualTo("DIRECT");
    }

    @Test
    void dnsDomainLevels_countsDots() {
        String body = "return dnsDomainLevels(host) < 2 ? 'DIRECT' : 'PROXY p:8080';";
        assertThat(eval(body, "http://a.com/", "a.com")).isEqualTo("DIRECT");
        assertThat(eval(body, "http://a.b.com/", "a.b.com")).isEqualTo("PROXY p:8080");
    }

    @Test
    void stringMethodsUsedByRealFiles() {
        String body = """
                var h = host.toLowerCase();
                if (h.indexOf("test") == 0) return "DIRECT";
                if (h.substring(0, 3) == "abc") return "PROXY abc:1";
                if (h.length > 20) return "PROXY long:1";
                return "PROXY p:8080";
                """;
        assertThat(eval(body, "u", "TEST.example.com")).isEqualTo("DIRECT");
        assertThat(eval(body, "u", "abc.example.com")).isEqualTo("PROXY abc:1");
        assertThat(eval(body, "u", "a-very-long-hostname.example.com")).isEqualTo("PROXY long:1");
    }

    @Test
    void arraysAndLoops() {
        String body = """
                var domains = ["one.com", "two.com", "three.com"];
                for (var i = 0; i < domains.length; i++) {
                    if (dnsDomainIs(host, domains[i])) return "DIRECT";
                }
                return "PROXY p:8080";
                """;
        assertThat(eval(body, "u", "www.two.com")).isEqualTo("DIRECT");
        assertThat(eval(body, "u", "www.four.com")).isEqualTo("PROXY p:8080");
    }

    @Test
    void whileLoopWithBreakAndContinue() {
        String body = """
                var i = 0;
                var found = false;
                while (i < 10) {
                    i += 1;
                    if (i < 5) continue;
                    found = true;
                    break;
                }
                return found ? "DIRECT" : "PROXY p:8080";
                """;
        assertThat(eval(body, "u", "h")).isEqualTo("DIRECT");
    }

    @Test
    void helperFunctionsDeclaredAlongsideFindProxyForURL() {
        String source = """
                function isCorp(host) {
                    return dnsDomainIs(host, ".corp");
                }
                function FindProxyForURL(url, host) {
                    if (isCorp(host)) return "DIRECT";
                    return "PROXY p:8080";
                }
                """;
        PacInterpreter pac = of(source);

        assertThat(pac.findProxyForUrl("http://a.corp/", "a.corp")).isEqualTo("DIRECT");
        assertThat(pac.findProxyForUrl("http://a.com/", "a.com")).isEqualTo("PROXY p:8080");
    }

    @Test
    void numbersConcatenateWithoutADecimalPoint() {
        // "PROXY host:8080.0" would be a real routing bug.
        assertThat(eval("var port = 8080; return 'PROXY host:' + port;", "u", "h"))
                .isEqualTo("PROXY host:8080");
    }

    @Test
    void commentsAreIgnored() {
        String body = """
                // line comment
                /* block
                   comment */
                return "DIRECT"; // trailing
                """;
        assertThat(eval(body, "u", "h")).isEqualTo("DIRECT");
    }

    @Test
    void multipleDirectivesArePassedThroughVerbatim() {
        // Failover lists are the caller's problem to interpret, not the interpreter's.
        assertThat(eval("return 'PROXY a:1; PROXY b:2; DIRECT';", "u", "h"))
                .isEqualTo("PROXY a:1; PROXY b:2; DIRECT");
    }

    /* ------------------------------------------------------- refusals, not guesses */

    @Test
    void missingEntryPointIsRejected() {
        assertThatThrownBy(() -> of("function other() { return 'DIRECT'; }"))
                .isInstanceOf(PacException.class)
                .hasMessageContaining("FindProxyForURL");
    }

    @Test
    void unsupportedConstructsAreRefusedWithTheLine() {
        // Silently ignoring these would route traffic on a misread file.
        assertThatThrownBy(() -> of("function FindProxyForURL(url, host) {\n var o = {a: 1};\n}"))
                .isInstanceOf(PacException.class)
                .hasMessageContaining("Object literals")
                .hasMessageContaining("line 2");

        assertThatThrownBy(() -> of("function FindProxyForURL(url, host) {\n"
                + " if (/x/.test(host)) return 'DIRECT';\n}"))
                .isInstanceOf(PacException.class)
                .hasMessageContaining("Regular expressions");

        assertThatThrownBy(() -> of("function FindProxyForURL(url, host) {\n"
                + " var d = new Date();\n}"))
                .isInstanceOf(PacException.class)
                .hasMessageContaining("'new'");
    }

    @Test
    void unknownFunctionIsRefused() {
        assertThatThrownBy(() -> eval("return madeUpHelper(host);", "u", "h"))
                .isInstanceOf(PacException.class)
                .hasMessageContaining("madeUpHelper");
    }

    @Test
    void unknownVariableIsRefused() {
        assertThatThrownBy(() -> eval("return somethingUndeclared;", "u", "h"))
                .isInstanceOf(PacException.class)
                .hasMessageContaining("somethingUndeclared");
    }

    @Test
    void unterminatedConstructsReportTheLine() {
        assertThatThrownBy(() -> of("function FindProxyForURL(url, host) {\n return 'unclosed;\n}"))
                .isInstanceOf(PacException.class)
                .hasMessageContaining("Unterminated string");
    }

    @Test
    void runawayLoopIsStoppedRatherThanHangingTheRequest() {
        assertThatThrownBy(() -> eval("while (true) { var x = 1; } return 'DIRECT';", "u", "h"))
                .isInstanceOf(PacException.class)
                .hasMessageContaining("exceeded");
    }

    @Test
    void functionThatReturnsNothingIsAnError() {
        // Better than quietly treating it as DIRECT.
        assertThatThrownBy(() -> eval("var x = 1;", "u", "h"))
                .isInstanceOf(PacException.class)
                .hasMessageContaining("returned no value");
    }
}
