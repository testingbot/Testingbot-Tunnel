package com.testingbot.tunnel.proxy;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.Authentication;
import org.eclipse.jetty.client.AuthenticationStore;
import org.eclipse.jetty.client.BasicAuthentication;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * --basic-auth uses host:port:user:password. The password is the rest of the
 * string after the third colon and must be allowed to contain colons itself.
 */
class BasicAuthTest {

    /** Applies --basic-auth to a client exactly as the handler does at startup. */
    private static HttpClient buildHttpClient(String basicAuth) {
        TunnelProxyHandler handler = new TunnelProxyHandler();
        handler.setBasicAuth(basicAuth == null ? null : basicAuth.split(","));
        HttpClient client = new HttpClient();
        handler.configureHttpClient(client);
        return client;
    }

    private static BasicAuthentication findBasicAuth(HttpClient client, URI uri) {
        AuthenticationStore store = client.getAuthenticationStore();
        Authentication found = store.findAuthentication("Basic", uri, Authentication.ANY_REALM);
        assertThat(found)
                .as("AuthenticationStore should contain a Basic credential for %s", uri)
                .isInstanceOf(BasicAuthentication.class);
        return (BasicAuthentication) found;
    }

    private static String readPrivate(Object target, String field) throws Exception {
        Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        return (String) f.get(target);
    }

    @Test
    void passwordContainingColons_isNotTruncated() throws Exception {
        String user = "alice";
        String password = "pa:ss:word";
        HttpClient client = buildHttpClient("backend.example:8080:" + user + ":" + password);
        try {
            BasicAuthentication auth = findBasicAuth(client, URI.create("http://backend.example:8080/anything"));
            assertThat(readPrivate(auth, "user")).isEqualTo(user);
            assertThat(readPrivate(auth, "password")).isEqualTo(password);
        } finally {
            client.destroy();
        }
    }

    @Test
    void multipleEntries_eachKeepsItsOwnColons() throws Exception {
        HttpClient client = buildHttpClient("host-a:80:user-a:pwd:with:colons,host-b:443:user-b:plain");
        try {
            BasicAuthentication a = findBasicAuth(client, URI.create("http://host-a:80/x"));
            assertThat(readPrivate(a, "password")).isEqualTo("pwd:with:colons");

            BasicAuthentication b = findBasicAuth(client, URI.create("http://host-b:443/x"));
            assertThat(readPrivate(b, "password")).isEqualTo("plain");
        } finally {
            client.destroy();
        }
    }

    @Test
    void underSpecifiedEntry_isSkipped() {
        HttpClient client = buildHttpClient("only:three:fields");
        try {
            Authentication found = client.getAuthenticationStore()
                    .findAuthentication("Basic", URI.create("http://only:80/"), Authentication.ANY_REALM);
            assertThat(found).isNull();
        } finally {
            client.destroy();
        }
    }
}
