package io.vantage.loganalyzer;

import io.vantage.agentcore.router.RuleBasedToolgroupRouter;
import io.vantage.agentcore.router.ToolgroupRouter;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * log-analyzer-service's own toolgroup keyword set — agent-core has no
 * opinion about these, per the original router design. "jira_rag" and
 * "report" still don't have real MCP servers to point at yet.
 */
@Configuration
public class RouterConfig {

    @Bean
    public ToolgroupRouter toolgroupRouter() {
        return new RuleBasedToolgroupRouter(Map.of(
                "logs", List.of("reconnect", "socket", "log", "error", "device", "rolt", "mac", "duplicate conn", "handshake"),
                "code", List.of("class:line", "method", "source", "callers", "definition", "symbol", "function", "code")
        ));
    }
}
