package io.vantage.mcp.jira;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.vantage.agentcore.rag.EmbeddingClient;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/**
 * Implements agent-core's {@link EmbeddingClient} against Server1's
 * llamafile embedding service (nomic-embed-text, port 8082 — separate
 * process from the main chat model on 8080). Response shape assumed
 * OpenAI-compatible ({@code data[0].embedding}), matching the already-
 * confirmed /v1/models surface on the chat model — verify with a direct
 * curl test before trusting this if embeddings come back malformed.
 */
@Component
public class LlamafileEmbeddingClient implements EmbeddingClient {

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${vantage.embedding.url}")
    private String embeddingUrl;

    @Override
    public List<Float> embed(String text) {
        try {
            String requestBody = objectMapper.writeValueAsString(java.util.Map.of("input", text));
            String responseBody = restTemplate.postForObject(embeddingUrl, buildRequest(requestBody), String.class);
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode embeddingNode = root.path("data").get(0).path("embedding");

            List<Float> embedding = new ArrayList<>();
            embeddingNode.forEach(n -> embedding.add(n.floatValue()));
            return embedding;
        } catch (Exception e) {
            throw new RuntimeException("Embedding request failed: " + e.getMessage(), e);
        }
    }

    private org.springframework.http.HttpEntity<String> buildRequest(String body) {
        var headers = new org.springframework.http.HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        return new org.springframework.http.HttpEntity<>(body, headers);
    }
}
