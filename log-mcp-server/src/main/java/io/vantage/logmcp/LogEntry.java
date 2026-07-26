package io.vantage.logmcp;

/** One matched log line, returned from search_logs. */
public record LogEntry(
        String timestamp,
        String roltMac,
        String className,
        int line,
        String event,
        String message
) {
}
