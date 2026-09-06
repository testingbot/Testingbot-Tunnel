package com.testingbot.tunnel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two shipped Grafana dashboards have to stay the same dashboard.
 *
 * <p>They are the same document in two places -- one for a user importing it by hand, one
 * provisioned by the docker-compose example -- and they had silently drifted: the provisioned
 * copy was five panels behind, missing everything added for connection, dial and proxy-error
 * observability. Someone running the compose example to look at those metrics would have found
 * a dashboard that simply did not show them, with nothing to indicate it was stale.
 *
 * <p>Panels are compared by title rather than byte-for-byte on the whole file, so the failure
 * message names what is missing instead of saying the files differ.
 */
class GrafanaDashboardsTest {

    private static final Path CANONICAL =
            Path.of("examples/grafana-dashboard/testingbot-tunnel.json");
    private static final Path PROVISIONED = Path.of(
            "examples/docker-compose-prometheus-grafana/grafana/provisioning/dashboards",
            "testingbot_tunnel.json");

    private static JsonNode read(Path path) throws Exception {
        assertThat(Files.exists(path)).as("%s should exist", path).isTrue();
        return new ObjectMapper().readTree(Files.readString(path));
    }

    private static List<String> panelTitles(JsonNode dashboard) {
        List<String> titles = new ArrayList<>();
        for (JsonNode panel : dashboard.path("panels")) {
            titles.add(panel.path("title").asText());
        }
        return titles;
    }

    @Test
    void bothDashboardsShowTheSamePanels() throws Exception {
        List<String> canonical = panelTitles(read(CANONICAL));
        List<String> provisioned = panelTitles(read(PROVISIONED));

        assertThat(canonical).as("the canonical dashboard should have panels").isNotEmpty();
        assertThat(provisioned)
                .as("the docker-compose dashboard must not fall behind the canonical one")
                .containsExactlyElementsOf(canonical);
    }

    @Test
    void bothDashboardsAreOtherwiseIdentical() throws Exception {
        // Beyond the panels: the datasource, uid, refresh interval and templating all decide
        // whether the thing works when provisioned.
        assertThat(read(PROVISIONED))
                .as("the two files are one document; keep them in step")
                .isEqualTo(read(CANONICAL));
    }

    @Test
    void everyPanelQueriesAMetricThisProcessExports() throws Exception {
        // A panel naming a metric that no longer exists renders empty, which reads as "the
        // tunnel is idle" rather than "this panel is wrong".
        String json = Files.readString(CANONICAL);
        assertThat(json)
                .as("the dashboards are built around the testingbot_ metric namespace")
                .contains("testingbot_");
    }
}
