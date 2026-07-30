package io.vantage.mcp.jira;

import io.vantage.agentcore.rag.EmbeddingClient;
import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * find_similar_tickets — the whole point of this toolgroup: given a newly
 * observed anomaly, check whether something like it was already seen and
 * resolved before. Matches are semantic leads, not confirmed duplicates —
 * confidence tiering (see TicketMatch) exists specifically so the calling
 * LLM (and the human reading its report) treats a 0.72 match very
 * differently from a 0.91 match, per the original design guardrail.
 */
@Component
public class JiraRagTools {

    private final EmbeddingClient embeddingClient;
    private final JdbcTemplate jdbcTemplate;

    public JiraRagTools(EmbeddingClient embeddingClient, JdbcTemplate jdbcTemplate) {
        this.embeddingClient = embeddingClient;
        this.jdbcTemplate = jdbcTemplate;
    }

    @McpTool(name = "find_similar_tickets", description = "Search past Jira tickets for anomalies semantically "
            + "similar to a newly observed log pattern or symptom. Returns candidate matches with similarity "
            + "scores and confidence tiers — treat these as leads to verify, not confirmed duplicates.")
    public List<TicketMatch> findSimilarTickets(
            @McpToolParam(description = "Description of the observed anomaly or symptom", required = true) String anomalySummary,
            @McpToolParam(description = "Max number of results to return", required = false) Integer topK) {

        int limit = (topK == null) ? 5 : topK;
        List<Float> queryEmbedding = embeddingClient.embed(anomalySummary);
        String vectorLiteral = toVectorLiteral(queryEmbedding);

        String sql = """
                SELECT ticket_key, title, symptom_summary, root_cause_summary, resolution_summary,
                       1 - (embedding <=> CAST(? AS vector)) AS similarity
                FROM jira_tickets
                ORDER BY embedding <=> CAST(? AS vector)
                LIMIT ?
                """;

        List<TicketMatch> matches = new ArrayList<>();
        jdbcTemplate.query(sql, rs -> {
            double similarity = rs.getDouble("similarity");
            String confidence = TicketMatch.confidenceFor(similarity);
            if (confidence == null) {
                return; // below 0.70 — excluded per design, not returned as "Low"
            }
            matches.add(new TicketMatch(
                    rs.getString("ticket_key"),
                    rs.getString("title"),
                    rs.getString("symptom_summary"),
                    rs.getString("root_cause_summary"),
                    rs.getString("resolution_summary"),
                    similarity,
                    confidence
            ));
        }, vectorLiteral, vectorLiteral, limit);

        return matches;
    }

    private String toVectorLiteral(List<Float> embedding) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < embedding.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(embedding.get(i));
        }
        return sb.append("]").toString();
    }
}
