package io.vantage.mcp.code;

/** One matched line, flattened out of OpenGrok's per-file result grouping. */
public record CodeMatch(
        String filePath,
        int lineNumber,
        String line,
        String tag
) {
}
