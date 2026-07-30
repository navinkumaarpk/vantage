package io.vantage.mcp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Single MCP server hosting every Vantage toolgroup.
 *
 * <p><strong>Consolidated 2026-07-27</strong> from three separate modules
 * (log-mcp-server, code-mcp-server, jira-rag-mcp-server). The original split
 * followed an "MCP server lives with its data" rule -- but all three data
 * sources turned out to live on the same box (Elasticsearch, OpenGrok and
 * Postgres are all localhost from Server2), so the rule was satisfied by any
 * single process there and never actually required three. The other original
 * justification, keeping each toolgroup's schemas out of a small model's
 * context via the router, disappeared when the router was removed in favour
 * of exposing all tools.
 *
 * <p>What the split was costing: three processes to start in the right order,
 * three ports, three configs, and three chances for one to be silently down
 * -- which bit us repeatedly during development.
 *
 * <p><strong>Kept split-friendly on purpose.</strong> Tools stay in one
 * package per domain ({@code .logs}, {@code .code}, {@code .jira}), each with
 * its own dependencies and no cross-domain imports. Splitting one back out
 * later is: create a module, move that package, point config at the new URL.
 */
@SpringBootApplication
public class VantageMcpServerApplication {
    public static void main(String[] args) {
        SpringApplication.run(VantageMcpServerApplication.class, args);
    }
}
