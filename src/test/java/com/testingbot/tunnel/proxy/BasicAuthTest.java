package com.testingbot.tunnel.proxy;

import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletContext;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.Authentication;
import org.eclipse.jetty.client.api.AuthenticationStore;
import org.eclipse.jetty.client.util.BasicAuthentication;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * --basic-auth uses host:port:user:password. The password is the rest of the
 * string after the third colon and must be allowed to contain colons itself.
 */
class BasicAuthTest {

    private static class StubbedServlet extends TunnelProxyServlet {
        private final ServletConfig cfg;

        StubbedServlet(ServletConfig cfg) {
            this.cfg = cfg;
        }

        @Override
        public ServletConfig getServletConfig() {
            return cfg;
        }

        HttpClient buildHttpClient() {
            return newHttpClient();
        }
    }

    private static ServletConfig mockServletConfig(String basicAuth) {
        ServletConfig cfg = mock(ServletConfig.class);
        ServletContext ctx = mock(ServletContext.class);
        when(cfg.getServletContext()).thenReturn(ctx);
        when(cfg.getInitParameter("basicAuth")).thenReturn(basicAuth);
        when(cfg.getInitParameter("proxy")).thenReturn(null);
        when(cfg.getInitParameter("proxyAuth")).thenReturn(null);
        when(cfg.getInitParameter("blackList")).thenReturn(null);
        return cfg;
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
        ServletConfig cfg = mockServletConfig("backend.example:8080:" + user + ":" + password);

        StubbedServlet servlet = new StubbedServlet(cfg);
        HttpClient client = servlet.buildHttpClient();
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
        ServletConfig cfg = mockServletConfig(
                "host-a:80:user-a:pwd:with:colons,host-b:443:user-b:plain");

        StubbedServlet servlet = new StubbedServlet(cfg);
        HttpClient client = servlet.buildHttpClient();
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
        ServletConfig cfg = mockServletConfig("only:three:fields");

        StubbedServlet servlet = new StubbedServlet(cfg);
        HttpClient client = servlet.buildHttpClient();
        try {
            Authentication found = client.getAuthenticationStore()
                    .findAuthentication("Basic", URI.create("http://only:80/"), Authentication.ANY_REALM);
            assertThat(found).isNull();
        } finally {
            client.destroy();
        }
    }
}
