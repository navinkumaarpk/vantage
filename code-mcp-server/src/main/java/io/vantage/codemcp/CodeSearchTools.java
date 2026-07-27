package io.vantage.codemcp;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/**
 * <strong>Query format confirmed empirically (2026-07-26)</strong> against
 * the real oltmgr/dci OpenGrok index — not from docs, which didn't clearly
 * expose the literal parameter values. Two things had to be verified via
 * direct curl testing before writing this class:
 *
 * <ol>
 *   <li>The real full-text parameter is {@code full}, not {@code q} (docs
 *       only exposed the Java constant name {@code FULL_SEARCH_PARAM}, not
 *       its string value).</li>
 *   <li>{@code defs}/{@code refs} searches 400 unless a non-empty
 *       {@code full} parameter is <em>also</em> present. Confirmed the
 *       combination isn't just validation theater — an unrelated {@code
 *       full} value ({@code full=the}) passed validation but returned
 *       unfiltered noise, while mirroring the same term into both {@code
 *       full} and {@code refs} returned genuinely scoped results. So every
 *       symbol search here sends the same term in both fields.</li>
 * </ol>
 *
 * <p>{@code find_definition}'s combined-with-full requirement is extrapolated
 * from the confirmed {@code refs} behavior plus one working browser example
 * ({@code full=ssl&defs=vcm_vlan}) — not independently curl-tested the same
 * rigorous way {@code refs} was. Worth confirming directly if it misbehaves.
 */
@Component
public class CodeSearchTools {

    private static final Logger log = LoggerFactory.getLogger(CodeSearchTools.class);

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    // ^ Bug found and fixed (2026-07-26): OpenGrokSearchResponse only
    // declares resultCount/results, but the real response also includes
    // time/startDocument/endDocument. Jackson's default strict mode threw
    // UnrecognizedPropertyException on "time" specifically, which the LLM
    // then surfaced almost verbatim in its answer — that's what led to
    // finding this. Ignoring unknown properties is simpler and more
    // resilient than exhaustively declaring every field OpenGrok might add.

    @Value("${vantage.opengrok.search-url}")
    private String searchUrl;

    @Value("${vantage.opengrok.source-root}")
    private String sourceRoot;

    @McpTool(name = "search_by_symptom", description = "Free-text search across the oltmgr/dci codebase for a "
            + "keyword or phrase. Use when no explicit class:line reference exists in the log line being "
            + "investigated. Returns matching lines with file paths and line numbers.")
    public List<CodeMatch> searchBySymptom(
            @McpToolParam(description = "Keyword or phrase to search for", required = true) String query) {
        return search(Map.of("full", query));
    }

    @McpTool(name = "find_definition", description = "Find where a symbol (class, method, or variable) is "
            + "defined in the oltmgr/dci codebase.")
    public List<CodeMatch> findDefinition(
            @McpToolParam(description = "Symbol name to find the definition of", required = true) String symbol) {
        return search(Map.of("full", symbol, "defs", symbol));
    }

    @McpTool(name = "find_callers", description = "Find all call sites and references of a symbol in the "
            + "oltmgr/dci codebase — use to assess blast radius before recommending a change.")
    public List<CodeMatch> findCallers(
            @McpToolParam(description = "Symbol name to find references of", required = true) String symbol) {
        return search(Map.of("full", symbol, "refs", symbol));
    }

    @McpTool(name = "get_source_context", description = "Read the source code surrounding a specific file and "
            + "line number. file must be the OpenGrok-relative path exactly as returned by the other tools here "
            + "(e.g. /oltmgr/develop/dcijavagit/dml/src/com/example/Foo.java), not an absolute filesystem path.")
    public String getSourceContext(
            @McpToolParam(description = "OpenGrok-relative file path, as returned by other tools", required = true) String file,
            @McpToolParam(description = "1-indexed line number to center the context window on", required = true) int line) {

        if (file == null || file.isBlank()) {
            throw new IllegalArgumentException("get_source_context: 'file' must not be blank");
        }
        if (line < 1) {
            throw new IllegalArgumentException("get_source_context: 'line' must be a positive integer, got " + line);
        }

        try {
            // Bug found and fixed (2026-07-26): Path.of(sourceRoot, file) silently
            // discards sourceRoot whenever file is absolute (starts with "/"), which
            // it always does - OpenGrok's returned paths are always of the form
            // "/oltmgr/...". Java/Unix path resolution treats an absolute second
            // component as replacing the first entirely, not joining onto it. This
            // meant every call looked up "/oltmgr/..." directly on disk instead of
            // "/opengrok/src/oltmgr/...", which never exists. Strip the leading
            // slash before joining so it resolves as a genuinely relative path.
            String relativeFile = file.startsWith("/") ? file.substring(1) : file;
            Path path = Path.of(sourceRoot, relativeFile);

            List<String> lines = Files.readAllLines(path);
            int contextLines = 25;
            int start = Math.max(0, line - 1 - contextLines);
            int end = Math.min(lines.size(), line - 1 + contextLines + 1);
            return String.join("\n", lines.subList(start, end));
        } catch (Exception e) {
            log.error("get_source_context failed for file='{}' line={}", file, line, e);
            throw new RuntimeException("get_source_context failed for " + file + ":" + line
                    + " - " + e.getClass().getSimpleName() + ": " + e.getMessage(), e);
        }
    }

    private List<CodeMatch> search(Map<String, String> params) {
        try {
            StringBuilder query = new StringBuilder(searchUrl).append("?");
            params.forEach((key, value) ->
                    query.append(key).append("=").append(URLEncoder.encode(value, StandardCharsets.UTF_8)).append("&"));

            String responseBody = restTemplate.getForObject(URI.create(query.toString()), String.class);
            OpenGrokSearchResponse response = objectMapper.readValue(responseBody, OpenGrokSearchResponse.class);

            List<CodeMatch> matches = new ArrayList<>();
            if (response.results != null) {
                response.results.forEach((filePath, hits) -> hits.forEach(hit -> matches.add(new CodeMatch(
                        filePath,
                        parseLineNumber(hit.lineNumber),
                        stripHtml(hit.line),
                        hit.tag
                ))));
            }
            return matches;
        } catch (Exception e) {
            throw new RuntimeException("OpenGrok search failed: " + e.getMessage(), e);
        }
    }

    private int parseLineNumber(String raw) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (Exception e) {
            return -1;
        }
    }

    private String stripHtml(String raw) {
        return raw == null ? "" : raw.replaceAll("<[^>]+>", "");
    }
}
