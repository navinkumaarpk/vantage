package io.vantage.logmcp;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Direct multipart upload -> parse -> bulk-index into Elasticsearch.
 * Deliberately NOT an MCP tool — this is a direct user action from the UI,
 * not something the LLM should decide to invoke on its own, matching the
 * same distinction InvestigationController/StatusController already draw
 * in log-analyzer-service between plain REST endpoints and MCP tool
 * exposure.
 *
 * <p>Chose a direct Spring Boot endpoint over Logstash deliberately —
 * Logstash is built for continuous streaming ingestion (tailing files,
 * Beats input, a persistent pipeline), not a one-off "a person uploads one
 * file through a web UI" action. Forcing that through Logstash would mean
 * either an awkward filesystem hand-off or running an entire separate
 * JVM/pipeline just to parse text this endpoint already parses directly,
 * with no clean success/failure feedback path back to the UI either way.
 */
@RestController
public class LogUploadController {

    private static final Logger log = LoggerFactory.getLogger(LogUploadController.class);
    private static final String INDEX = "oltmgr-logs-uploaded";

    private final ElasticsearchClient esClient;
    private final LogFileParser parser = new LogFileParser();

    public LogUploadController(ElasticsearchClient esClient) {
        this.esClient = esClient;
    }

    public record UploadResult(String fileName, int entriesIndexed, boolean success, String error) {}

    @PostMapping("/api/logs/upload")
    public UploadResult upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "caseName", required = false) String caseName,
            @RequestParam(value = "jiraTicket", required = false) String jiraTicket) {

        try {
            List<String> lines = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    lines.add(line);
                }
            }

            List<UploadedLogEntry> entries = parser.parse(lines, caseName, jiraTicket, file.getOriginalFilename());

            if (entries.isEmpty()) {
                return new UploadResult(file.getOriginalFilename(), 0, false,
                        "No entries matched the expected log format — is this a real OltMgr log file?");
            }

            BulkRequest.Builder br = new BulkRequest.Builder();
            for (UploadedLogEntry entry : entries) {
                br.operations(op -> op.index(idx -> idx.index(INDEX).document(entry)));
            }
            BulkResponse response = esClient.bulk(br.build());

            if (response.errors()) {
                long failedCount = response.items().stream().filter(item -> item.error() != null).count();
                log.warn("Bulk index for '{}' had {} failed items out of {}",
                        file.getOriginalFilename(), failedCount, entries.size());
                return new UploadResult(file.getOriginalFilename(), (int) (entries.size() - failedCount), false,
                        failedCount + " of " + entries.size() + " entries failed to index — check server logs");
            }

            log.info("Indexed {} log entries from '{}' (case={}, jira={})",
                    entries.size(), file.getOriginalFilename(), caseName, jiraTicket);
            return new UploadResult(file.getOriginalFilename(), entries.size(), true, null);

        } catch (Exception e) {
            log.error("Log upload failed for '{}'", file.getOriginalFilename(), e);
            return new UploadResult(file.getOriginalFilename(), 0, false,
                    e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }
}
