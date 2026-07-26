package io.vantage.agentcore.router;

import java.util.Optional;

/**
 * Decides which toolgroup a given request (or reasoning step) should use.
 *
 * <p>This exists as its own abstraction — separate from {@link
 * io.vantage.agentcore.mcp.McpClientRegistry} — because the routing
 * decision needs to be cheap and deterministic where possible (see the
 * log-analyzer/testcase-generator design discussions: rule-based routing for
 * the initial toolgroup pick, with the LLM free to re-route mid-investigation
 * once results from one toolgroup point toward another).
 *
 * <p>Implementations should return {@link Optional#empty()} when no
 * toolgroup confidently matches, rather than guessing — callers can fall
 * back to asking the LLM directly, or to a default toolgroup, depending on
 * the service's own policy.
 */
public interface ToolgroupRouter {

    /**
     * @param query          the user's request or the current reasoning step's
     *                       stated need
     * @param availableGroups toolgroup names currently registered and usable
     * @return the chosen toolgroup name, if one confidently matches
     */
    Optional<String> route(String query, Iterable<String> availableGroups);
}
