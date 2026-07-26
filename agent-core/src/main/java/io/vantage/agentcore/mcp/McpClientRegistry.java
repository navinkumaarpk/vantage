package io.vantage.agentcore.mcp;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import io.modelcontextprotocol.client.McpSyncClient;

/**
 * Holds one {@link McpSyncClient} per named toolgroup (e.g. "logs", "code",
 * "report", "jira_rag", "docs_rag" — the actual names are defined by each
 * consuming service, agent-core doesn't hardcode a fixed set).
 *
 * <p>This is deliberately a flat registry, not a router — see {@link
 * io.vantage.agentcore.router.ToolgroupRouter} for the logic that decides
 * <em>which</em> toolgroup a given request should use. The registry only
 * answers "give me the client for toolgroup X"; it has no opinion about how
 * X gets chosen.
 *
 * <p>The whole point of keeping toolgroups as separate MCP client connections
 * (rather than one client with every tool attached) is context control: a
 * consuming service should build its {@code ChatClient} tool list from a
 * single toolgroup's client per call, not from every registered client at
 * once, or the context-bloat problem this design was meant to solve comes
 * right back.
 */
public class McpClientRegistry {

    private final Map<String, McpSyncClient> clients = new ConcurrentHashMap<>();

    public void register(String toolgroupName, McpSyncClient client) {
        clients.put(toolgroupName, client);
    }

    public Optional<McpSyncClient> get(String toolgroupName) {
        return Optional.ofNullable(clients.get(toolgroupName));
    }

    public Map<String, McpSyncClient> all() {
        return Collections.unmodifiableMap(clients);
    }

    public boolean isRegistered(String toolgroupName) {
        return clients.containsKey(toolgroupName);
    }
}
