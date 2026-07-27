package io.vantage.loganalyzer;

import io.vantage.agentcore.streaming.AgentActivityPublisher;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class Investigation {

    public final String id;
    public String title;
    public final Instant createdAt;
    public Instant lastActivityAt;
    public final List<Turn> turns = new ArrayList<>();
    public final AgentActivityPublisher activity = new AgentActivityPublisher();

    public Investigation(String id, String title) {
        this.id = id;
        this.title = title;
        this.createdAt = Instant.now();
        this.lastActivityAt = this.createdAt;
    }

    public record Turn(String role, String content, String toolgroupUsed, boolean degraded, Instant at) {}

    public void addTurn(Turn turn) {
        turns.add(turn);
        lastActivityAt = turn.at();
    }
}
