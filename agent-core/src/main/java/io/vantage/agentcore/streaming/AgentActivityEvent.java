package io.vantage.agentcore.streaming;

import java.time.Instant;

/**
 * A single step in an investigation's activity feed — maps directly onto the
 * "live activity" panel in both product wireframes (tool call started/
 * finished, with timing). Kept generic/domain-agnostic here; each service
 * decides what counts as a step (an MCP tool call, a routing decision, a
 * judge pass, etc.).
 */
public record AgentActivityEvent(
        String investigationId,
        String toolgroup,
        String toolName,
        Status status,
        String summary,
        Instant timestamp
) {
    public enum Status { STARTED, SUCCEEDED, FAILED }

    public static AgentActivityEvent started(String investigationId, String toolgroup, String toolName) {
        return new AgentActivityEvent(investigationId, toolgroup, toolName, Status.STARTED, null, Instant.now());
    }

    public static AgentActivityEvent succeeded(String investigationId, String toolgroup, String toolName, String summary) {
        return new AgentActivityEvent(investigationId, toolgroup, toolName, Status.SUCCEEDED, summary, Instant.now());
    }

    public static AgentActivityEvent failed(String investigationId, String toolgroup, String toolName, String summary) {
        return new AgentActivityEvent(investigationId, toolgroup, toolName, Status.FAILED, summary, Instant.now());
    }
}
