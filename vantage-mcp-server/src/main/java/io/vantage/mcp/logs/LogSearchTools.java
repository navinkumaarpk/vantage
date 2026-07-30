package io.vantage.mcp.logs;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.json.JsonData;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
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

    @McpTool(name = "search_logs", description = """
            Find log entries matching a query or filters. Returns newest first.

            This is the ANCHOR step: use it to locate where something happened, then call
            get_log_context with the returned timestamp AND thread to see the surrounding causal
            chain. A single device often appears under several different identifiers across log
            lines, so entries found here are usually only part of the story -- expand with
            get_log_context before concluding what went wrong.

            Most entries are DEBUG noise, so pass level=ERROR when investigating a failure. For a
            specific moment pass a narrow from/to window rather than an exact timestamp, since
            stored timestamps include milliseconds.""")
    public List<LogEntry> searchLogs(
            @McpToolParam(description = "Free-text match against the message body", required = false) String query,
            @McpToolParam(description = "Exact R-OLT MAC address", required = false) String roltMac,
            @McpToolParam(description = "Exact case name a log file was tagged with. Use list_log_cases to discover valid values.", required = false) String caseName,
            @McpToolParam(description = "Log level: ERROR, WARN, INFO or DEBUG", required = false) String level,
            @McpToolParam(description = "Window start, ISO-8601 e.g. 2026-07-27T13:36:00", required = false) String from,
            @McpToolParam(description = "Window end, ISO-8601 e.g. 2026-07-27T13:37:00", required = false) String to,
            @McpToolParam(description = "Max results (default 25, max 100)", required = false) Integer limit) {

        int size = (limit == null) ? 25 : Math.min(Math.max(limit, 1), 100);
        try {
            SearchResponse<LogDocument> response = esClient.search(s -> s
                    .index(INDEX)
                    .size(size)
                    .sort(so -> so.field(f -> f.field("timestamp").order(SortOrder.Desc)))
                    .query(q -> q.bool(b -> {
                        if (notBlank(query)) {
                            b.must(m -> m.match(mt -> mt.field("message").query(query)));
                        }
                        termIf(b, "rolt_mac", roltMac);
                        termIf(b, "case_name", caseName);
                        termIf(b, "level", level == null ? null : level.toUpperCase());
                        addRange(b, from, to);
                        return b;
                    })),
                    LogDocument.class);
            return toEntries(response);
        } catch (Exception e) {
            throw new RuntimeException("search_logs failed: " + e.getClass().getSimpleName() + ": " + e.getMessage(), e);
        }
    }

    @McpTool(name = "get_log_context", description = """
            Reconstruct what happened around a specific moment by returning surrounding log entries
            in chronological order. This is the EXPAND step after search_logs.

            Strongly prefer passing the thread from the anchor entry. One worker thread handling one
            device is a single causal story, so thread-scoped context cuts unrelated interleaved
            activity dramatically while still capturing entries that refer to the same device under
            a DIFFERENT identifier -- which text search on one identifier misses entirely.

            Use this to answer "what went wrong" / "what happened here".""")
    public List<LogEntry> getLogContext(
            @McpToolParam(description = "Anchor timestamp, ISO-8601 e.g. 2026-07-27T13:36:59", required = true) String timestamp,
            @McpToolParam(description = "Thread name from the anchor entry, e.g. 'ROLT Manager ONT Queue-5'. Highly recommended.", required = false) String thread,
            @McpToolParam(description = "Seconds before the anchor (default 5)", required = false) Integer secondsBefore,
            @McpToolParam(description = "Seconds after the anchor (default 5)", required = false) Integer secondsAfter,
            @McpToolParam(description = "Max entries (default 60, max 200)", required = false) Integer limit) {

        int before = (secondsBefore == null) ? 5 : Math.max(secondsBefore, 0);
        int after = (secondsAfter == null) ? 5 : Math.max(secondsAfter, 0);
        int size = (limit == null) ? 60 : Math.min(Math.max(limit, 1), 200);
        try {
            Instant anchor = parseFlexible(timestamp);
            String lo = anchor.minusSeconds(before).toString();
            String hi = anchor.plusSeconds(after).toString();
            SearchResponse<LogDocument> response = esClient.search(s -> s
                    .index(INDEX)
                    .size(size)
                    // Ascending: chronological order is what makes a causal chain readable.
                    .sort(so -> so.field(f -> f.field("timestamp").order(SortOrder.Asc)))
                    .query(q -> q.bool(b -> {
                        termIf(b, "thread", thread);
                        addRange(b, lo, hi);
                        return b;
                    })),
                    LogDocument.class);
            return toEntries(response);
        } catch (Exception e) {
            throw new RuntimeException("get_log_context failed: " + e.getClass().getSimpleName() + ": " + e.getMessage(), e);
        }
    }

    @McpTool(name = "summarize_logs", description = """
            Aggregate counts without pulling individual lines: top source file:line call sites,
            totals by level, busiest threads.

            Call this FIRST on an unfamiliar log set. In Java logs every statement lives at exactly
            one source file:line, so grouping by source_file is free and exact template detection --
            a real 4100-line file collapsed to 6 distinct ERROR call sites. Those call sites are
            directly usable with get_source_context to read the code that produced them.""")
    public Map<String, Object> summarizeLogs(
            @McpToolParam(description = "Scope to this case name", required = false) String caseName,
            @McpToolParam(description = "Scope to this level, e.g. ERROR", required = false) String level,
            @McpToolParam(description = "Window start, ISO-8601", required = false) String from,
            @McpToolParam(description = "Window end, ISO-8601", required = false) String to) {
        try {
            SearchResponse<LogDocument> response = esClient.search(s -> s
                    .index(INDEX)
                    .size(0)
                    .query(q -> q.bool(b -> {
                        termIf(b, "case_name", caseName);
                        termIf(b, "level", level == null ? null : level.toUpperCase());
                        addRange(b, from, to);
                        return b;
                    }))
                    .aggregations("by_source_file", a -> a.terms(t -> t.field("source_file").size(15)))
                    .aggregations("by_level", a -> a.terms(t -> t.field("level").size(10)))
                    .aggregations("by_thread", a -> a.terms(t -> t.field("thread").size(15))),
                    LogDocument.class);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("totalMatching", response.hits().total() != null ? response.hits().total().value() : 0);
            out.put("bySourceFile", buckets(response, "by_source_file"));
            out.put("byLevel", buckets(response, "by_level"));
            out.put("byThread", buckets(response, "by_thread"));
            return out;
        } catch (Exception e) {
            throw new RuntimeException("summarize_logs failed: " + e.getClass().getSimpleName() + ": " + e.getMessage(), e);
        }
    }

    @McpTool(name = "list_log_cases", description = "List case names, Jira tickets and file names that uploaded "
            + "logs were tagged with. Use before filtering by caseName, since that match is exact.")
    public Map<String, Object> listLogCases() {
        try {
            SearchResponse<LogDocument> response = esClient.search(s -> s
                    .index(INDEX)
                    .size(0)
                    .aggregations("cases", a -> a.terms(t -> t.field("case_name").size(50)))
                    .aggregations("tickets", a -> a.terms(t -> t.field("jira_ticket").size(50)))
                    .aggregations("files", a -> a.terms(t -> t.field("source_file_name").size(50))),
                    LogDocument.class);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("caseNames", buckets(response, "cases"));
            out.put("jiraTickets", buckets(response, "tickets"));
            out.put("sourceFiles", buckets(response, "files"));
            return out;
        } catch (Exception e) {
            throw new RuntimeException("list_log_cases failed: " + e.getClass().getSimpleName() + ": " + e.getMessage(), e);
        }
    }

    /* ---------- helpers ---------- */

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static void termIf(BoolQuery.Builder b, String field, String value) {
        if (notBlank(value)) {
            b.filter(f -> f.term(t -> t.field(field).value(value)));
        }
    }

    /**
     * Range filter on timestamp using the untyped RangeQuery variant with
     * JsonData. NOTE: not fully verified against the pinned 9.4.4 client --
     * ES Java client 9.x split RangeQuery into typed variants
     * (untyped/date/number/term). If this fails to compile, the alternative is
     *   b.filter(f -> f.range(r -> r.date(d -> d.field("timestamp").gte(from).lte(to))))
     * with plain strings instead of JsonData.
     */
    private static void addRange(BoolQuery.Builder b, String from, String to) {
        if (!notBlank(from) && !notBlank(to)) {
            return;
        }
        b.filter(f -> f.range(r -> r.untyped(u -> {
            u.field("timestamp");
            if (notBlank(from)) {
                u.gte(JsonData.of(from));
            }
            if (notBlank(to)) {
                u.lte(JsonData.of(to));
            }
            return u;
        })));
    }

    /** Accepts "2026-07-27T13:36:59Z", "2026-07-27T13:36:59", "2026-07-27 13:36:59,318". */
    private static Instant parseFlexible(String ts) {
        try {
            return Instant.parse(ts);
        } catch (Exception ignored) {
            return LocalDateTime.parse(ts.trim().replace(' ', 'T').replace(',', '.'))
                    .atZone(ZoneId.systemDefault()).toInstant();
        }
    }

    private List<LogEntry> toEntries(SearchResponse<LogDocument> response) {
        return response.hits().hits().stream()
                .map(Hit::source)
                .filter(Objects::nonNull)
                .map(d -> new LogEntry(d.timestamp, d.message, d.thread, d.roltMac, d.className, d.line, d.event,
                        d.level, d.logger, d.sourceFile, d.sourceLine, d.caseName, d.jiraTicket, d.sourceFileName))
                .toList();
    }

    private List<Map<String, Object>> buckets(SearchResponse<LogDocument> response, String aggName) {
        List<Map<String, Object>> out = new ArrayList<>();
        var agg = response.aggregations().get(aggName);
        if (agg == null) {
            return out;
        }
        agg.sterms().buckets().array().forEach(bk -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("value", bk.key().stringValue());
            m.put("count", bk.docCount());
            out.add(m);
        });
        return out;
    }
}
