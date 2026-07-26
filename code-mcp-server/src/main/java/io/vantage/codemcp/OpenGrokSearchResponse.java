package io.vantage.codemcp;

import java.util.List;
import java.util.Map;

/**
 * Matches OpenGrok's actual confirmed REST API response shape:
 * {"time":..,"resultCount":..,"results":{"<file path>":[{"line":..,"lineNumber":..,"tag":..}]}}
 * Confirmed empirically against the real oltmgr/dci index (2026-07-26) —
 * not guessed from docs, which didn't expose the literal query param names
 * or response shape clearly enough to trust blind.
 */
public class OpenGrokSearchResponse {
    public int resultCount;
    public Map<String, List<LineHit>> results;

    public static class LineHit {
        public String line;
        public String lineNumber;
        public String tag;
    }
}
