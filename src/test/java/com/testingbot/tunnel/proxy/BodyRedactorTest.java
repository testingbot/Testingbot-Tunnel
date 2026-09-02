package com.testingbot.tunnel.proxy;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a body log is allowed to contain.
 *
 * <p>The Selenium relay carries WebDriver capabilities, and it is normal for those to hold the
 * customer's access key. Every test here is ultimately the same question: can a credential get
 * into a log file. The realistic capabilities payload is the one that matters most.
 */
class BodyRedactorTest {

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void aRealCapabilitiesPayloadLosesItsCredentials() {
        String body = "{\"capabilities\":{\"alwaysMatch\":{\"browserName\":\"chrome\","
                + "\"tb:options\":{\"key\":\"abc123\",\"secret\":\"s3cr3t\","
                + "\"name\":\"my test\"}}}}";

        String rendered = BodyRedactor.render("application/json", bytes(body));

        assertThat(rendered).doesNotContain("abc123").doesNotContain("s3cr3t");
        assertThat(rendered).contains("<redacted>");
        // What is left has to be worth logging, or the feature is pointless.
        assertThat(rendered).contains("chrome").contains("my test");
    }

    @Test
    void credentialsAreRedactedAtAnyDepthAndInsideArrays() {
        String body = "{\"a\":[{\"b\":{\"accessKey\":\"deep\"}}],\"list\":[{\"password\":\"p\"}]}";

        String rendered = BodyRedactor.render("application/json", bytes(body));

        assertThat(rendered).doesNotContain("deep").doesNotContain("\"p\"");
    }

    @Test
    void keyNamingVariationsAreAllCaught() {
        // access_key, ACCESS-KEY and accessKey are one thing as far as a leak is concerned.
        String body = "{\"access_key\":\"1\",\"ACCESS-KEY\":\"2\",\"accessKey\":\"3\","
                + "\"apiToken\":\"4\",\"X-Auth\":\"5\",\"sessionId\":\"6\"}";

        String rendered = BodyRedactor.render("application/json", bytes(body));

        for (String secret : new String[]{"\"1\"", "\"2\"", "\"3\"", "\"4\"", "\"5\"", "\"6\""}) {
            assertThat(rendered).doesNotContain(secret);
        }
    }

    @Test
    void anUnparseableTypeIsDescribedNotPrinted() {
        // The central rule: a structure that cannot be parsed cannot be redacted, so it is
        // never shown. Pattern-matching a blob for secrets would fail open.
        byte[] body = bytes("username=admin&password=hunter2 and some prose");

        String rendered = BodyRedactor.render("text/html", body);

        assertThat(rendered).doesNotContain("hunter2").doesNotContain("admin");
        assertThat(rendered).contains("text/html").contains("not shown");
    }

    @Test
    void jsonThatDoesNotParseIsDescribedRatherThanShown() {
        byte[] body = bytes("{\"key\":\"leaked\", this is not json");

        String rendered = BodyRedactor.render("application/json", body);

        assertThat(rendered).doesNotContain("leaked");
        assertThat(rendered).contains("could not be parsed");
    }

    @Test
    void anOversizedBodyIsDescribedRatherThanTruncated() {
        // Truncating first would mean printing raw bytes exactly when there are most of them,
        // and half a document does not parse so could not be redacted.
        String big = "{\"secret\":\"x\",\"pad\":\"" + "A".repeat(BodyRedactor.MAX_BODY_BYTES) + "\"}";

        String rendered = BodyRedactor.render("application/json", bytes(big));

        assertThat(rendered).doesNotContain("AAAA");
        assertThat(rendered).contains("cannot be redacted");
    }

    @Test
    void formEncodedBodiesAreRedactedByKey() {
        byte[] body = bytes("user=alice&password=hunter2&remember=1");

        String rendered = BodyRedactor.render("application/x-www-form-urlencoded", body);

        assertThat(rendered).doesNotContain("hunter2");
        assertThat(rendered).contains("alice").contains("remember=1");
    }

    @Test
    void anEmptyOrAbsentBodyIsHarmless() {
        assertThat(BodyRedactor.render("application/json", null)).isEqualTo("<empty>");
        assertThat(BodyRedactor.render("application/json", new byte[0])).isEqualTo("<empty>");
    }

    @Test
    void aBodyWithNoContentTypeIsNotShown() {
        assertThat(BodyRedactor.render(null, bytes("{\"key\":\"leaked\"}")))
                .doesNotContain("leaked");
    }

    @Test
    void ordinaryFieldsSurviveSoTheLogIsStillUseful() {
        String body = "{\"browserName\":\"firefox\",\"platformName\":\"LINUX\",\"monkey\":\"ok\"}";

        assertThat(BodyRedactor.render("application/json", bytes(body)))
                .contains("firefox").contains("LINUX").contains("ok");
    }

    @Test
    void aBodyMislabelledAsFormEncodingIsNotPrinted() {
        // The leak this class existed to prevent, and did not. form() trusted the declared
        // content type: a JSON body with no '&' is one segment and with no '=' the whole body
        // became the "name", which was appended before anything judged it sensitive. That is
        // what `curl -d @caps.json` sends by default.
        byte[] caps = bytes("{\"tb:options\":{\"key\":\"REAL-KEY\",\"secret\":\"REAL-SECRET\"}}");

        String rendered = BodyRedactor.render("application/x-www-form-urlencoded", caps);

        assertThat(rendered).doesNotContain("REAL-KEY").doesNotContain("REAL-SECRET");
        assertThat(rendered).contains("not name=value pairs");
    }

    @Test
    void aSegmentWithNoNameIsNotPrintedEither() {
        assertThat(BodyRedactor.render("application/x-www-form-urlencoded", bytes("=justavalue")))
                .doesNotContain("justavalue");
        assertThat(BodyRedactor.render("application/x-www-form-urlencoded", bytes("a=1&leftover")))
                .doesNotContain("leftover");
    }

    @Test
    void aSensitiveFormFieldLosesItsNameAsWellAsItsValue() {
        // A name long enough to hold a credential frequently is one.
        String rendered = BodyRedactor.render("application/x-www-form-urlencoded",
                bytes("user=alice&password=hunter2"));

        assertThat(rendered).doesNotContain("hunter2").contains("alice");
    }

    @Test
    void theProjectsOwnNameForTheCredentialIsRedacted() {
        // HttpProxy sends the credential as client_key / client_secret. Whole-key matching
        // caught client_secret through the "secret" fragment and missed client_key entirely,
        // so the secret was hidden and the key beside it was printed.
        assertThat(BodyRedactor.isSensitiveKey("client_key")).isTrue();
        assertThat(BodyRedactor.isSensitiveKey("clientKey")).isTrue();
        assertThat(BodyRedactor.isSensitiveKey("privateKey")).isTrue();
        assertThat(BodyRedactor.isSensitiveKey("passphrase")).isTrue();
    }

    @Test
    void aWordThatMerelyContainsASensitiveWordIsNotRedacted() {
        // Splitting on camelCase and separators rather than substring-matching keeps "monkey"
        // from being read as "key"; over-redaction costs debugging.
        assertThat(BodyRedactor.isSensitiveKey("monkey")).isFalse();
        assertThat(BodyRedactor.isSensitiveKey("browserName")).isFalse();
        assertThat(BodyRedactor.isSensitiveKey("passenger")).isFalse();
    }
}
