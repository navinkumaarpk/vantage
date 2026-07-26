package io.vantage.logmcp;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Matches the oltmgr-logs-sample index mapping field-for-field. */
public class LogDocument {

    public String timestamp;

    @JsonProperty("rolt_mac")
    public String roltMac;

    @JsonProperty("class")
    public String className;

    public int line;
    public String event;
    public String message;
}
