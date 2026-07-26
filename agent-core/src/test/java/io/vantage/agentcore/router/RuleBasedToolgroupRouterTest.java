package io.vantage.agentcore.router;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RuleBasedToolgroupRouterTest {

    private final RuleBasedToolgroupRouter router = new RuleBasedToolgroupRouter(Map.of(
            "logs", List.of("reconnect", "socket", "log"),
            "code", List.of("class:line", "method", "source"),
            "jira_rag", List.of("jira", "ticket", "seen before")
    ));

    private final List<String> availableGroups = List.of("logs", "code", "jira_rag");

    @Test
    void routesToLogsOnReconnectKeyword() {
        Optional<String> result = router.route("check R-OLT 84bb69544680 for reconnect issues", availableGroups);
        assertThat(result).contains("logs");
    }

    @Test
    void routesToJiraRagOnSeenBeforePhrase() {
        Optional<String> result = router.route("has this been seen before in past tickets?", availableGroups);
        assertThat(result).contains("jira_rag");
    }

    @Test
    void returnsEmptyWhenNoKeywordMatches() {
        Optional<String> result = router.route("what's the weather like", availableGroups);
        assertThat(result).isEmpty();
    }

    @Test
    void returnsEmptyOnBlankQuery() {
        assertThat(router.route("", availableGroups)).isEmpty();
        assertThat(router.route(null, availableGroups)).isEmpty();
    }
}
