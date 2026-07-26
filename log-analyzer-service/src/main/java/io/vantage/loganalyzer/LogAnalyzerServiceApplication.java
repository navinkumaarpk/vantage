package io.vantage.loganalyzer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Deliberately minimal for now — the point of this first pass is to confirm
 * the app boots and agent-core's MCP auto-configuration wires cleanly
 * (McpClientRegistry, VantageMcpProperties) even with zero toolgroups
 * configured yet. Real toolgroup config (logs, code, jira_rag, report) gets
 * added to application.yml once the corresponding MCP tool servers exist on
 * Server2/Server3 to point at.
 */
@SpringBootApplication
public class LogAnalyzerServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(LogAnalyzerServiceApplication.class, args);
    }
}
