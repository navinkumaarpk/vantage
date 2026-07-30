package io.vantage.mcp.logs;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Unified document shape covering BOTH indices search_logs queries across
 * via the "oltmgr-logs*" wildcard -- oltmgr-logs-sample (rolt_mac/class/
 * event) and oltmgr-logs-uploaded (level/thread/logger/case_name/
 * jira_ticket) have genuinely different schemas.
 *
 * Bug found and fixed (2026-07-27): this only declared the sample schema's
 * fields, so any hit from the uploaded-logs index (added the same day)
 * threw UnrecognizedPropertyException and crashed the whole tool call --
 * confirmed via a real test uploading a real log file and querying it. Same
 * class of bug already fixed once in code-mcp-server's
 * OpenGrokSearchResponse; should have anticipated this the moment the two
 * indices were designed with different schemas.
 */
public class LogDocument {

    public String timestamp;
    public String message;

    // Sample-index fields
    @JsonProperty("rolt_mac")
    public String roltMac;

    @JsonProperty("class")
    public String className;

    public Integer line;
    public String event;

    // Uploaded-index fields
    public String level;
    public String thread;
    public String logger;

    @JsonProperty("source_file")
    public String sourceFile;

    @JsonProperty("source_line")
    public Integer sourceLine;

    @JsonProperty("case_name")
    public String caseName;

    @JsonProperty("jira_ticket")
    public String jiraTicket;

    @JsonProperty("source_file_name")
    public String sourceFileName;
}
