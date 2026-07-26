package io.vantage.loganalyzer;

import io.vantage.agentcore.mcp.McpClientRegistry;
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
}
