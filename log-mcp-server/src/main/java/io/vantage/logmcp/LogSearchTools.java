package io.vantage.logmcp;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import java.util.List;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

/**
 * <strong>Not verified against real dependencies</strong> — the {@code @Tool}
 * annotation and how it gets auto-discovered as an MCP server tool is the
 * least-confident part of this module (same class of guess as agent-core's
 * original McpSyncClient wiring, which did turn out to need correcting once,
 * then compiled clean after the fix). The Elasticsearch query logic itself
 * follows the client's documented lambda DSL and should be solid regardless
 * of whether the tool-registration mechanism needs adjusting.
 *
 * <p>Hardcoded to the {@code oltmgr-logs-sample} test index for now — swap
 * for a real index pattern (and drop the "sample" naming) once the actual
 * log ingestion pipeline exists.
 */
@Component
public class LogSearchTools {

    private static final String INDEX = "oltmgr-logs-sample";

    private final ElasticsearchClient esClient;

    public LogSearchTools(ElasticsearchClient esClient) {
        this.esClient = esClient;
    }

    @Tool(description = "Full-text and filtered search over OltMgr log lines. "
            + "query: free-text match against the log message (optional). "
            + "roltMac: exact R-OLT MAC address to filter by (optional). "
            + "Returns matching log entries, newest first, up to 50 results.")
    public List<LogEntry> searchLogs(String query, String roltMac) {
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
