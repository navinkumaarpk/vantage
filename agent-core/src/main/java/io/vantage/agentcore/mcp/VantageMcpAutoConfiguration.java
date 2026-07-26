package io.vantage.agentcore.mcp;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Auto-activates for any service that depends on agent-core — no explicit
 * {@code @Import} needed, per Spring Boot's standard auto-configuration
 * discovery (see {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}).
 *
 * <p>Registers clients eagerly at startup via {@link McpClientRegistrar}, so
 * a misconfigured or unreachable MCP server fails the service's startup
 * rather than failing silently on first use.
 */
@AutoConfiguration
@EnableConfigurationProperties(VantageMcpProperties.class)
public class VantageMcpAutoConfiguration {

    @Bean
    public McpClientRegistry mcpClientRegistry() {
        return new McpClientRegistry();
    }

    @Bean
    public McpClientRegistrar mcpClientRegistrar(VantageMcpProperties properties, McpClientRegistry registry) {
        McpClientRegistrar registrar = new McpClientRegistrar(properties, registry);
        registrar.registerAll();
        return registrar;
    }
}
