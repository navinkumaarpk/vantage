package io.vantage.loganalyzer;

import io.modelcontextprotocol.client.McpSyncClient;
import io.vantage.agentcore.mcp.McpClientRegistry;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Not a real feature — exists to give an easy way to confirm agent-core's
 * VantageMcpAutoConfiguration actually wired McpClientRegistry as an
 * injectable bean. Expect an empty toolgroups list until real MCP servers
 * are configured in application.yml.
 */
@RestController
public class StatusController {

    private final McpClientRegistry mcpClientRegistry;

    public StatusController(McpClientRegistry mcpClientRegistry) {
        this.mcpClientRegistry = mcpClientRegistry;
    }

    @GetMapping("/api/status")
    public Map<String, Object> status() {
        return Map.of(
                "service", "log-analyzer-service",
                "registeredToolgroups", mcpClientRegistry.all().keySet()
        );
    }

    /**
     * Debugging the "no tool call ever reached log-mcp-server" issue —
     * calls listTools() directly on the "logs" toolgroup's MCP client,
     * bypassing ChatClient/the LLM entirely. If this comes back empty, the
     * problem is server-side (LogSearchTools never got registered as a real
     * MCP tool by log-mcp-server's auto-configuration). If it comes back
     * with search_logs listed, the problem is client-side (something in how
     * ChatController attaches tools to the ChatClient call).
     */
    @GetMapping("/api/debug/logs-tools")
    public Map<String, Object> debugLogsTools() {
        McpSyncClient client = mcpClientRegistry.get("logs")
                .orElseThrow(() -> new IllegalStateException("logs toolgroup not registered"));

        List<String> toolNames = client.listTools().tools().stream()
                .map(tool -> tool.name())
                .toList();

        return Map.of("toolsSeenByClient", toolNames);
    }
}

