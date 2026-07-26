package io.vantage.agentcore.router;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Simple keyword-based {@link ToolgroupRouter}. Deliberately dumb by design —
 * this is the cheap, deterministic first pass described in the architecture
 * discussion, not meant to handle every case. Each consuming service supplies
 * its own keyword-to-toolgroup mapping at construction time, since agent-core
 * has no opinion about what toolgroups a given product needs (log-analyzer's
 * "logs"/"code"/"jira_rag"/"report" set is entirely different from
 * testcase-generator's "code"/"docs_rag"/"jira_rag"/"report" set).
 *
 * <p>Matching is first-match-wins over the supplied keyword list order —
 * callers should order their rules from most to least specific.
 *
 * <p>Example construction:
 * <pre>{@code
 * new RuleBasedToolgroupRouter(Map.of(
 *     "logs",     List.of("reconnect", "log", "socket", "error"),
 *     "code",     List.of("class:line", "method", "source", "callers"),
 *     "jira_rag", List.of("jira", "ticket", "seen before", "similar")
 * ));
 * }</pre>
 */
public class RuleBasedToolgroupRouter implements ToolgroupRouter {

    private final Map<String, List<String>> keywordsByToolgroup;

    public RuleBasedToolgroupRouter(Map<String, List<String>> keywordsByToolgroup) {
        this.keywordsByToolgroup = keywordsByToolgroup;
    }

    @Override
    public Optional<String> route(String query, Iterable<String> availableGroups) {
        if (query == null || query.isBlank()) {
            return Optional.empty();
        }
        String normalized = query.toLowerCase(Locale.ROOT);

        for (String toolgroup : availableGroups) {
            List<String> keywords = keywordsByToolgroup.get(toolgroup);
            if (keywords == null) {
                continue;
            }
            for (String keyword : keywords) {
                if (normalized.contains(keyword.toLowerCase(Locale.ROOT))) {
                    return Optional.of(toolgroup);
                }
            }
        }
        return Optional.empty();
    }
}
