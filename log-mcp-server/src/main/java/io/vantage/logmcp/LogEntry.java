package io.vantage.logmcp;

/**
 * One matched log line, returned from search_logs. Fields are nullable
 * since sample-index and uploaded-index hits populate different subsets --
 * a sample hit has roltMac/className/event, an uploaded hit has
 * level/logger/caseName/jiraTicket instead. Widened 2026-07-27 to actually
 * surface the uploaded-log fields to the LLM rather than silently drop
 * them, alongside the LogDocument deserialization fix.
 */
public record LogEntry(
        String timestamp,
        String message,
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
