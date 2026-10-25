package com.testingbot.tunnel;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.ParseException;
import org.apache.commons.cli.PosixParser;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** {@code --ssh-connections}: how many SSH connections the tunnel opens. */
class SshConnectionsOptionTest {

    private static CommandLine parse(String... args) throws ParseException {
        return new PosixParser().parse(App.buildOptions(), args);
    }

    private static App configured(String... args) throws Exception {
        App app = new App();
        App.applyOptions(app, parse(args));
        return app;
    }

    @Test
    void defaultsToFour() throws Exception {
        assertThat(configured().getSshConnections()).isEqualTo(App.DEFAULT_SSH_CONNECTIONS).isEqualTo(4);
    }

    @Test
    void acceptsOneToEight() throws Exception {
        assertThat(configured("--ssh-connections", "1").getSshConnections()).isEqualTo(1);
        assertThat(configured("--ssh-connections", "8").getSshConnections()).isEqualTo(8);
    }

    @Test
    void rejectsAnythingElse() {
        for (String bad : new String[]{"0", "9", "-1", "two", "2.5"}) {
            assertThatThrownBy(() -> configured("--ssh-connections", bad))
                    .as(bad)
                    .isInstanceOf(ParseException.class)
                    .hasMessageContaining("--ssh-connections");
        }
    }
}
