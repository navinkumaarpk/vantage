package io.vantage.logmcp;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import java.util.List;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * <strong>Bug found and fixed (2026-07-26):</strong> this method previously
 * used {@code @Tool}, which is for exposing a method directly to an
 * in-process ChatClient — not for external MCP server exposure. Confirmed via
 * a live debug endpoint that log-mcp-server's auto-configuration scans for
 * {@code @McpTool} specifically (per VISTA's prior design, which used
 * {@code @McpTool} for external MCP exposure and {@code @Tool} only when
 * also calling a method directly from an in-process ChatClient with no
 * network hop). First attempt guessed the wrong package
 * ({@code org.springframework.ai.tool.annotation.McpTool} — doesn't exist);
 * correct package confirmed via Spring AI's official MCP reference docs:
 * {@code org.springframework.ai.mcp.annotation.McpTool}, alongside
 * {@code @McpToolParam} for per-parameter schema descriptions.
 */
@Component
public class LogSearchTools {

    // Wildcard so both the original hand-seeded oltmgr-logs-sample and any
    // real uploaded logs (oltmgr-logs-uploaded) are searchable through the
    // same tool — uploaded data would otherwise sit in ES unused by chat.
    private static final String INDEX = "oltmgr-logs*";

    private final ElasticsearchClient esClient;

    public LogSearchTools(ElasticsearchClient esClient) {
        this.esClient = esClient;
    }

    @McpTool(name = "search_logs", description = "Full-text and filtered search over OltMgr log lines. "
            + "Returns matching log entries, newest first, up to 50 results.")
    public List<LogEntry> searchLogs(
            @McpToolParam(description = "Free-text match against the log message", required = false) String query,
            @McpToolParam(description = "Exact R-OLT MAC address to filter by", required = false) String roltMac) {
        try {
            SearchResponse<LogDocument> response = esClient.search(s -> s
                    .index(INDEX)
                    .size(50)
                    .sort(sort -> sort.field(f -> f.field("timestamp").order(co.elastic.clients.elasticsearch._types.SortOrder.Desc)))
                    .query(q -> q.bool(b -> {
                        if (query != null && !query.isBlank()) {
                            b.must(m -> m.match(mt -> mt.field("message").query(query)));
                        }
                        if (roltMac != null && !roltMac.isBlank()) {
                            b.filter(f -> f.term(t -> t.field("rolt_mac").value(roltMac)));
                        }
                        return b;
                    })),
                    LogDocument.class
            );

            return response.hits().hits().stream()
                    .map(Hit::source)
                    .filter(java.util.Objects::nonNull)
                    .map(doc -> new LogEntry(doc.timestamp, doc.roltMac, doc.className, doc.line, doc.event, doc.message))
                    .toList();
        } catch (Exception e) {
            throw new RuntimeException("search_logs failed: " + e.getMessage(), e);
        }
    }
}
