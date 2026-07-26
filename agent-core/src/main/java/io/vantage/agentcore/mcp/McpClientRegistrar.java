package io.vantage.agentcore.mcp;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds one {@link McpSyncClient} per entry in {@link VantageMcpProperties}
 * and registers it into {@link McpClientRegistry}. Runs once at startup (see
 * {@link VantageMcpAutoConfiguration}).
 *
 * <p><strong>Design reversed 2026-07-26:</strong> this class previously
 * failed the whole application startup if any single toolgroup couldn't
 * connect — the javadoc here used to argue that was the right call, so a
 * broken toolgroup would "surface as a startup failure, not a confusing
 * runtime error." In practice this meant one flaky/misconfigured MCP server
 * (a hostname typo, a dependency not up yet) took down every toolgroup,
 * including ones that were perfectly healthy — confirmed directly when a
 * transient jira-rag-mcp-server startup race killed the entire service,
 * including the already-working logs/code toolgroups. Each toolgroup's
 * connection attempt is now isolated: failures are logged clearly (name,
 * URL, cause) and skipped rather than propagated, so the app starts with
 * whatever toolgroups actually connected. {@link McpClientRegistry#all()}
 * reflects only genuinely live toolgroups — callers must not assume every
 * configured toolgroup is actually registered (see ChatController's
 * matching fix for the request-time half of this).
 *
 * <p><strong>Known remaining gap, not fixed here:</strong> this only
 * handles connection failures at startup. If a toolgroup connects
 * successfully but its session later dies (the MCP server restarts,
 * crashes, or the network drops), there's no reconnection logic — that
 * client will keep failing on every subsequent call until the whole service
 * is restarted. Worth a follow-up (periodic health check + reconnect, or
 * lazy reconnect on failure) if this proves to matter in practice.
 */
public class McpClientRegistrar {

    private static final Logger log = LoggerFactory.getLogger(McpClientRegistrar.class);

    private final VantageMcpProperties properties;
    private final McpClientRegistry registry;

    public McpClientRegistrar(VantageMcpProperties properties, McpClientRegistry registry) {
        this.properties = properties;
        this.registry = registry;
    }

    public void registerAll() {
        properties.getToolgroups().forEach((toolgroupName, connection) -> {
            try {
                var transport = HttpClientStreamableHttpTransport.builder(connection.getUrl()).build();
                McpSyncClient client = McpClient.sync(transport).build();
                client.initialize();
                registry.register(toolgroupName, client);
                log.info("Connected MCP toolgroup '{}' at {}", toolgroupName, connection.getUrl());
            } catch (Exception e) {
                log.error("Failed to connect MCP toolgroup '{}' at {} — this toolgroup will be unavailable "
                        + "until the app is restarted (no auto-reconnect yet). Cause: {}",
                        toolgroupName, connection.getUrl(), e.getMessage());
            }
        });
    }
}