package com.testingbot.tunnel;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code --web} directory server.
 *
 * <p>Had no tests at all, and could not have had useful ones: the {@code Server} lived in a
 * constructor-local, so nothing could stop it or ask what it was doing. It served an
 * operator-chosen directory, with listing enabled, for the life of the JVM -- outliving the
 * tunnel it accompanied, leaking a Jetty server per App for an embedder, and holding port 8080
 * so the next run could not bind one.
 */
class LocalWebServerTest {

    @TempDir
    Path tempDir;

    private static int freePort() throws IOException {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static String get(String url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(5000);
        try (java.io.InputStream in = connection.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } finally {
            connection.disconnect();
        }
    }

    @Test
    void itServesTheDirectoryItWasGiven() throws Exception {
        Files.writeString(tempDir.resolve("hello.txt"), "from the local web server");
        LocalWebServer server = new LocalWebServer(tempDir.toString(), "127.0.0.1", freePort());
        try {
            assertThat(server.isRunning()).isTrue();
            assertThat(get("http://127.0.0.1:" + server.getPort() + "/hello.txt"))
                    .contains("from the local web server");
        } finally {
            server.stop();
        }
    }

    @Test
    void itCanBeStoppedAndReleasesItsPort() throws Exception {
        int port = freePort();
        LocalWebServer server = new LocalWebServer(tempDir.toString(), "127.0.0.1", port);
        assertThat(server.isRunning()).isTrue();

        server.stop();

        assertThat(server.isRunning()).isFalse();
        // The port has to come back, or the next run cannot start one -- the reason this
        // mattered beyond tidiness.
        try (java.net.ServerSocket rebind = new java.net.ServerSocket(port)) {
            assertThat(rebind.isBound()).isTrue();
        }
    }

    @Test
    void stoppingTwiceIsHarmless() throws Exception {
        LocalWebServer server = new LocalWebServer(tempDir.toString(), "127.0.0.1", freePort());
        server.stop();
        server.stop();
        assertThat(server.isRunning()).isFalse();
    }

}
