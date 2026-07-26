package io.vantage.agentcore.mcp;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;

/**
 * Builds one {@link McpSyncClient} per entry in {@link VantageMcpProperties}
 * and registers it into {@link McpClientRegistry}. Runs once at startup (see
 * {@link VantageMcpAutoConfiguration}) — this is intentionally not lazy,
 * since a toolgroup that fails to connect should surface as a startup
 * failure, not a confusing runtime error the first time a request tries to
 * route to it.
 *
 * <p><strong>Bug found and fixed (2026-07-26):</strong> previously used
 * {@code HttpClientSseClientTransport} (the legacy SSE-specific transport).
 * This worked by accident early on because log-mcp-server had no explicit
 * {@code spring.ai.mcp.server.protocol} set and was defaulting to the
 * deprecated SSE protocol, which happened to match. Once the server was
 * correctly configured with {@code protocol: STREAMABLE} (per Spring AI's
 * official docs), this client transport became the mismatched one — it kept
 * timing out on {@code initialize()} since it was sending SSE-style requests
 * against a server that no longer speaks that protocol. Fixed by switching
 * to {@link HttpClientStreamableHttpTransport}, confirmed via Spring's own
 * MCP Security reference docs and the MCP Java SDK client docs, both of
 * which show the same builder shape independently. The configured URL
 * ({@code vantage.mcp.toolgroups.*.url}) already includes the {@code /mcp}
 * path segment, so the builder is called with just the URL — not paired
 * with an explicit {@code .endpoint("/mcp")} call, which would double the
 * path.
 */
public class McpClientRegistrar {

    private final VantageMcpProperties properties;
    private final McpClientRegistry registry;

    public McpClientRegistrar(VantageMcpProperties properties, McpClientRegistry registry) {
        this.properties = properties;
        this.registry = registry;
    }

    public void registerAll() {
        properties.getToolgroups().forEach((toolgroupName, connection) -> {
            var transport = HttpClientStreamableHttpTransport.builder(connection.getUrl()).build();
            McpSyncClient client = McpClient.sync(transport).build();
            client.initialize();
            registry.register(toolgroupName, client);
        });
    }
}