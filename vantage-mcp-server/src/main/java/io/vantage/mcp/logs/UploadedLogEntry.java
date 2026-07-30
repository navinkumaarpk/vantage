package io.vantage.mcp.logs;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Document shape for the oltmgr-logs-uploaded index. */
public class UploadedLogEntry {
    public String timestamp;
    public String level;
    public String thread;
    public String logger;

    @JsonProperty("source_file")
    public String sourceFile;

    @JsonProperty("source_line")
    public int sourceLine;

    public String message;

    @JsonProperty("case_name")
    public String caseName;

    @JsonProperty("jira_ticket")
    public String jiraTicket;

    @JsonProperty("source_file_name")
    public String sourceFileName;
}
