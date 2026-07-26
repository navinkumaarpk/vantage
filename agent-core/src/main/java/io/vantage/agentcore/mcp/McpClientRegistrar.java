package io.vantage.agentcore.mcp;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;

/**
 * Builds one {@link McpSyncClient} per entry in {@link VantageMcpProperties}
 * and registers it into {@link McpClientRegistry}. Runs once at startup (see
 * {@link VantageMcpAutoConfiguration}) — this is intentionally not lazy,
 * since a toolgroup that fails to connect should surface as a startup
 * failure, not a confusing runtime error the first time a request tries to
 * route to it.
 *
 * <p><strong>Unverified against the real dependency:</strong> the transport
 * class name/builder shape here ({@code HttpClientSseClientTransport}) is
 * best-effort based on the MCP Java SDK's typical API shape — this sandbox
 * can't reach Maven Central to confirm it compiles against the actual
 * spring-ai-starter-mcp-client version. Expect to adjust this one class if
 * the real SDK's transport builder differs; nothing else in agent-core
 * depends on these specifics.
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
            var transport = HttpClientSseClientTransport.builder(connection.getUrl()).build();
            McpSyncClient client = McpClient.sync(transport).build();
            client.initialize();
            registry.register(toolgroupName, client);
        });
    }
}
