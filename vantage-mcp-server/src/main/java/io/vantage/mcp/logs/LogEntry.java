package io.vantage.mcp.logs;

/**
 * One matched log entry. Fields are nullable since sample-index and
 * uploaded-index hits populate different subsets.
 *
 * <p><strong>thread is load-bearing, not incidental:</strong> the
 * anchor-then-expand pattern depends on the model reading thread off a
 * search_logs hit and passing it to get_log_context. Without it exposed
 * here, thread-scoped context expansion is unusable from the model's side.
 */
public record LogEntry(
        String timestamp,
        String message,
        String thread,
        String roltMac,
        String className,
        Integer line,
        String event,
        String level,
        String logger,
        String sourceFile,
        Integer sourceLine,
        String caseName,
        String jiraTicket,
        String sourceFileName
) {
}
