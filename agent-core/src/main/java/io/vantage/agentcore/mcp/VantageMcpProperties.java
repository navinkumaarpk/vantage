package io.vantage.agentcore.mcp;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds {@code vantage.mcp.toolgroups} from each service's own
 * application.yml. Toolgroup names and URLs are entirely per-service
 * configuration — agent-core has no opinion about what toolgroups exist,
 * only how to turn a configured entry into a registered client.
 *
 * <pre>{@code
 * vantage:
 *   mcp:
 *     toolgroups:
 *       logs:
 *         url: http://server2:8081/mcp
 *       jira_rag:
 *         url: http://server2:8083/mcp
 * }</pre>
 */
@ConfigurationProperties(prefix = "vantage.mcp")
public class VantageMcpProperties {

    private Map<String, ToolgroupConnection> toolgroups = new LinkedHashMap<>();

    public Map<String, ToolgroupConnection> getToolgroups() {
        return toolgroups;
    }

    public void setToolgroups(Map<String, ToolgroupConnection> toolgroups) {
        this.toolgroups = toolgroups;
    }

    public static class ToolgroupConnection {

        /** Base URL of the MCP server for this toolgroup. */
        private String url;

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }
    }
}
