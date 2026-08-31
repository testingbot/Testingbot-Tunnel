package com.testingbot.tunnel.pac;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Language semantics the routing tests do not reach: arithmetic, coercions, the String methods
 * and the error paths.
 *
 * <p>These matter because the interpreter is hand-written. A wrong {@code %} or a wrong
 * number-to-string conversion would not throw -- it would quietly produce a different proxy
 * host, which is the failure mode this whole component is built to avoid.
 */
class PacInterpreterSemanticsTest {

    private static PacInterpreter.PacEnvironment recording(List<String> calls) {
        return new PacInterpreter.PacEnvironment() {
            @Override
            public String resolve(String host) {
                return host.equals("known") ? "10.1.2.3" : null;
            }

            @Override
            public String myIpAddress() {
                return "10.0.0.5";
            }

            @Override
            public boolean weekdayRange(List<String> args) {
                calls.add("weekdayRange" + args);
                return true;
            }

            @Override
            public boolean dateRange(List<String> args) {
                calls.add("dateRange" + args);
                return true;
            }

            @Override
            public boolean timeRange(List<String> args) {
                calls.add("timeRange" + args);
                return true;
            }
        };
    }

    /** Evaluates a body and returns the directive string. */
    private static String run(String body) {
        return run(body, new ArrayList<>());
    }

    private static String run(String body, List<String> calls) {
        return new PacInterpreter(
                "function FindProxyForURL(url, host) {" + body + "}", recording(calls))
                .findProxyForUrl("http://example.com/path", "example.com");
    }

    /* ------------------------------------------------------------------- arithmetic */

    @Test
    void arithmeticOperators() {
        assertThat(run("return '' + (7 - 2);")).isEqualTo("5");
        assertThat(run("return '' + (7 * 3);")).isEqualTo("21");
        assertThat(run("return '' + (8 / 2);")).isEqualTo("4");
        assertThat(run("return '' + (7 % 3);")).isEqualTo("1");
    }

    @Test
    void unaryOperators() {
        assertThat(run("return '' + (-5);")).isEqualTo("-5");
        assertThat(run("return '' + (+'42');")).isEqualTo("42");
        assertThat(run("return !false ? 'yes' : 'no';")).isEqualTo("yes");
    }

    @Test
    void typeofReportsTheSubsetTypes() {
        assertThat(run("return typeof 'a';")).isEqualTo("string");
        assertThat(run("return typeof 1;")).isEqualTo("number");
        assertThat(run("return typeof true;")).isEqualTo("boolean");
        assertThat(run("return typeof null;")).isEqualTo("undefined");
        assertThat(run("return typeof [1];")).isEqualTo("object");
    }

    @Test
    void compoundAssignment() {
        assertThat(run("var n = 10; n -= 3; n *= 2; return '' + n;")).isEqualTo("14");
        assertThat(run("var s = 'a'; s += 'b'; return s;")).isEqualTo("ab");
    }

    @Test
    void nonIntegralNumbersKeepTheirDecimal() {
        // Integral doubles print without a decimal point so ports concatenate correctly; a
        // genuinely fractional value must not silently truncate.
        assertThat(run("return '' + (7 / 2);")).isEqualTo("3.5");
    }

    @Test
    void comparisonOnStringsIsLexicographic() {
        assertThat(run("return 'a' < 'b' ? 'yes' : 'no';")).isEqualTo("yes");
        assertThat(run("return 'b' <= 'a' ? 'yes' : 'no';")).isEqualTo("no");
        assertThat(run("return 2 >= 2 ? 'yes' : 'no';")).isEqualTo("yes");
    }

    @Test
    void looseEqualityAcrossTypes() {
        assertThat(run("return 1 == '1' ? 'yes' : 'no';")).isEqualTo("yes");
        assertThat(run("return 0 == false ? 'yes' : 'no';")).isEqualTo("yes");
        assertThat(run("return null == null ? 'yes' : 'no';")).isEqualTo("yes");
        assertThat(run("return 'a' != 'b' ? 'yes' : 'no';")).isEqualTo("yes");
    }

    @Test
    void arrayCoercesToACommaSeparatedString() {
        assertThat(run("return '' + [1, 'a', true];")).isEqualTo("1,a,true");
    }

    /* --------------------------------------------------------------- string methods */

    @Test
    void stringMethods() {
        assertThat(run("return '' + 'abcdef'.length;")).isEqualTo("6");
        assertThat(run("return 'abc'.toUpperCase();")).isEqualTo("ABC");
        assertThat(run("return '' + 'abcabc'.lastIndexOf('b');")).isEqualTo("4");
        assertThat(run("return 'abc'.charAt(1);")).isEqualTo("b");
        assertThat(run("return 'abcdef'.substr(2, 3);")).isEqualTo("cde");
        // JavaScript replaces the first match only, unlike Java's String.replace.
        assertThat(run("return 'a-b-c'.replace('-', '+');")).isEqualTo("a+b-c");
        assertThat(run("return 'abc'.replace('z', '+');")).isEqualTo("abc");
        assertThat(run("return '  x  '.trim();")).isEqualTo("x");
        assertThat(run("return 'abc'.startsWith('ab') ? 'yes' : 'no';")).isEqualTo("yes");
        assertThat(run("return 'abc'.endsWith('bc') ? 'yes' : 'no';")).isEqualTo("yes");
        assertThat(run("return 'abc'.toString();")).isEqualTo("abc");
    }

    @Test
    void splitProducesAnIndexableArray() {
        assertThat(run("var p = 'a.b.c'.split('.'); return p[1];")).isEqualTo("b");
        assertThat(run("var p = 'a.b.c'.split('.'); return '' + p.length;")).isEqualTo("3");
    }

    @Test
    void charAtAndIndexOutOfRangeAreEmptyRatherThanErrors() {
        assertThat(run("return 'abc'.charAt(99) == '' ? 'empty' : 'other';")).isEqualTo("empty");
        assertThat(run("return 'abc'[99] == null ? 'null' : 'other';")).isEqualTo("null");
    }

    @Test
    void stringsAreIndexable() {
        assertThat(run("return 'abc'[1];")).isEqualTo("b");
    }

    @Test
    void arrayIndexOutOfRangeIsNullRatherThanAnError() {
        assertThat(run("var a = [1]; return a[9] == null ? 'null' : 'other';")).isEqualTo("null");
    }

    @Test
    void unsupportedStringMethodIsRefusedByName() {
        assertThatThrownBy(() -> run("return 'abc'.padStart(5);"))
                .isInstanceOf(PacException.class)
                .hasMessageContaining("padStart");
    }

    @Test
    void arrayPropertiesOtherThanLengthAreRefused() {
        assertThatThrownBy(() -> run("var a = [1]; return a.join(',');"))
                .isInstanceOf(PacException.class)
                .hasMessageContaining("only .length");
    }

    @Test
    void propertyAccessOnANonObjectIsRefused() {
        assertThatThrownBy(() -> run("var n = 1; return n.length;"))
                .isInstanceOf(PacException.class)
                .hasMessageContaining("Cannot read a property");
    }

    @Test
    void callingSomethingUncallableIsRefused() {
        // A bare name is looked up as a function, so this reports the more useful "unknown
        // function" rather than a type error.
        assertThatThrownBy(() -> run("var n = 1; return n();"))
                .isInstanceOf(PacException.class)
                .hasMessageContaining("Unknown function 'n'");

        // Calling the result of an expression reaches the type error.
        assertThatThrownBy(() -> run("var a = [1]; return a[0]();"))
                .isInstanceOf(PacException.class)
                .hasMessageContaining("Cannot call");
    }

    @Test
    void nanComparisonsAreFalseInEveryDirection() {
        // JavaScript semantics, and the reason a broken .length used to look "greater than".
        assertThat(run("return ('abc' * 1) > 0 ? 'yes' : 'no';")).isEqualTo("no");
        assertThat(run("return ('abc' * 1) < 0 ? 'yes' : 'no';")).isEqualTo("no");
        assertThat(run("return ('abc' * 1) >= 0 ? 'yes' : 'no';")).isEqualTo("no");
        assertThat(run("return ('abc' * 1) <= 0 ? 'yes' : 'no';")).isEqualTo("no");
    }

    @Test
    void stringLengthIsANumberNotAMethodHandle() {
        assertThat(run("return 'abcdef'.length > 3 ? 'long' : 'short';")).isEqualTo("long");
        assertThat(run("return 'ab'.length > 3 ? 'long' : 'short';")).isEqualTo("short");
        assertThat(run("return '' + 'abc'.length;")).isEqualTo("3");
    }

    @Test
    void compoundAssignmentToAnUnknownVariableIsRefused() {
        assertThatThrownBy(() -> run("nope += 1; return 'DIRECT';"))
                .isInstanceOf(PacException.class)
                .hasMessageContaining("nope");
    }

    /* ------------------------------------------------------------- time predicates */

    @Test
    void timePredicatesAreDelegatedWithTheirArgumentsIntact() {
        List<String> calls = new ArrayList<>();

        run("if (weekdayRange('MON', 'FRI') && dateRange('JAN') && timeRange(9, 17)) "
                + "return 'DIRECT'; return 'PROXY p:1';", calls);

        assertThat(calls).containsExactly(
                "weekdayRange[MON, FRI]", "dateRange[JAN]", "timeRange[9, 17]");
    }

    @Test
    void alertIsAcceptedAndIgnored() {
        // Browsers log it; a PAC file containing one must still evaluate.
        assertThat(run("alert('debugging'); return 'DIRECT';")).isEqualTo("DIRECT");
    }

    /* ----------------------------------------------------------------- helper units */

    @Test
    void ipv4ToLongRejectsAnythingThatIsNotDottedQuad() {
        assertThat(PacInterpreter.ipv4ToLong("10.0.0.1")).isEqualTo(167772161L);
        assertThat(PacInterpreter.ipv4ToLong(null)).isEqualTo(-1);
        assertThat(PacInterpreter.ipv4ToLong("10.0.0")).isEqualTo(-1);
        assertThat(PacInterpreter.ipv4ToLong("10.0.0.256")).isEqualTo(-1);
        assertThat(PacInterpreter.ipv4ToLong("10.0.0.-1")).isEqualTo(-1);
        assertThat(PacInterpreter.ipv4ToLong("10.0.0.x")).isEqualTo(-1);
        assertThat(PacInterpreter.ipv4ToLong("::1")).isEqualTo(-1);
    }

    @Test
    void isInNetIsFalseWhenAnyArgumentIsNotAnIpv4Address() {
        // dnsResolve returning null is the common case and must not throw.
        assertThat(PacInterpreter.isInNet(null, "10.0.0.0", "255.0.0.0")).isFalse();
        assertThat(PacInterpreter.isInNet("10.0.0.1", "garbage", "255.0.0.0")).isFalse();
        assertThat(PacInterpreter.isInNet("10.0.0.1", "10.0.0.0", "garbage")).isFalse();
        assertThat(PacInterpreter.isInNet("10.0.0.1", "10.0.0.0", "255.0.0.0")).isTrue();
    }

    @Test
    void shExpMatchAnchorsTheWholeValue() {
        // A pattern must match end to end, or "evil.com.attacker.net" would match "*.com".
        assertThat(PacInterpreter.shExpMatch("a.example.com", "*.example.com")).isTrue();
        assertThat(PacInterpreter.shExpMatch("a.example.com.evil.net", "*.example.com")).isFalse();
        assertThat(PacInterpreter.shExpMatch("abc", "a?c")).isTrue();
        assertThat(PacInterpreter.shExpMatch("ac", "a?c")).isFalse();
        assertThat(PacInterpreter.shExpMatch("anything", "*")).isTrue();
    }

    /* ------------------------------------------------------- the real environment */

    @Test
    void systemEnvironmentResolvesAndReportsAnAddress() {
        PacInterpreter.PacEnvironment system = PacInterpreter.PacEnvironment.system();

        assertThat(system.resolve("localhost")).isEqualTo("127.0.0.1");
        assertThat(system.resolve("a-host-that-does-not-exist.invalid")).isNull();
        assertThat(system.myIpAddress()).matches("\\d+\\.\\d+\\.\\d+\\.\\d+");
    }

    @Test
    void systemEnvironmentAnswersTheTimePredicates() {
        PacInterpreter.PacEnvironment system = PacInterpreter.PacEnvironment.system();

        // Every day and every hour, so the answer cannot depend on when the suite runs.
        assertThat(system.weekdayRange(List.of("SUN", "SAT"))).isTrue();
        assertThat(system.timeRange(List.of("0", "24"))).isTrue();
        assertThat(system.dateRange(List.of("1", "31"))).isTrue();
    }

    @Test
    void aPacFileCanBeBuiltAgainstTheRealEnvironment() {
        // The single-argument constructor is what production uses.
        PacInterpreter pac = new PacInterpreter(
                "function FindProxyForURL(url, host) { return 'DIRECT'; }");

        assertThat(pac.findProxyForUrl("http://x/", "x")).isEqualTo("DIRECT");
    }

    @Test
    void stepLimitAppliesToForLoopsToo() {
        assertThatThrownBy(() -> run("for (var i = 0; i < 999999999; i += 1) { var x = i; } "
                + "return 'DIRECT';"))
                .isInstanceOf(PacException.class)
                .hasMessageContaining("exceeded");
    }

    @Test
    void breakAndContinueWorkInsideForLoops() {
        String body = """
                var seen = 0;
                for (var i = 0; i < 10; i += 1) {
                    if (i < 3) continue;
                    if (i > 6) break;
                    seen += 1;
                }
                return '' + seen;
                """;
        assertThat(run(body)).isEqualTo("4");
    }

    @Test
    void aFunctionCanReturnWithNoValueAndTheCallerSeesNull() {
        String source = """
                function helper(x) { if (x) { return; } return "set"; }
                function FindProxyForURL(url, host) {
                    return helper(true) == null ? "DIRECT" : "PROXY p:1";
                }
                """;
        assertThat(new PacInterpreter(source, recording(new ArrayList<>()))
                .findProxyForUrl("http://x/", "x")).isEqualTo("DIRECT");
    }
}
