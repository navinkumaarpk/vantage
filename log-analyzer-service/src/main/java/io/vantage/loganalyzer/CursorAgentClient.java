package io.vantage.loganalyzer;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

/**
 * Calls the Python Cursor SDK sidecar (see vantage-cursor-agent/) over SSE.
 *
 * <p><strong>Rewritten 2026-07-30</strong> from a synchronous drain-then-respond
 * call (RestClient, one JSON blob at the end) to real streaming (WebClient,
 * bodyToFlux(ServerSentEvent.class)). The old design directly caused a 38s
 * ReadTimeoutException on a real multi-tool-call investigation -- holding one
 * synchronous connection open across Server3-&gt;Python-&gt;Cursor's
 * infra-&gt;multiple tool round trips has no good fixed timeout -- and gave
 * zero live visibility, when the actual ask was to see tool calls appear as
 * they happen, the way Claude's own interface shows them.
 *
 * <p>bodyToFlux(ServerSentEvent.class) is a standard, well-documented Spring
 * WebClient pattern, not new API risk -- it is the same transport our own
 * activity feed (InvestigationController) already uses successfully.
 *
 * <p>ChatController still returns one final answer to the browser per request
 * (bridging this Flux to a blocking call with .block(Duration)), but now
 * publishes an activity event for every tool call AS it arrives during that
 * wait, rather than only once at the very end. Full inline-in-chat-transcript
 * rendering (tool blocks appearing within the message stream itself, matching
 * Claude's own UI) is a further frontend redesign, deliberately not attempted
 * in this pass.
 */
@Component
public class CursorAgentClient {

    private static final Logger log = LoggerFactory.getLogger(CursorAgentClient.class);

    private final WebClient webClient = WebClient.builder().build();

    @Value("${vantage.cursor-agent.url:}")
    private String sidecarUrl;

    /** One SSE frame from the sidecar. type is one of tool_call/text_delta/done/error. */
    public record StreamEvent(
            String type,
            String toolName,
            String toolStatus,
            String textDelta,
            String finalText,
            String runStatus,
            Integer totalTokens,
            String error
    ) {}

    public boolean isConfigured() {
        return sidecarUrl != null && !sidecarUrl.isBlank();
    }

    /**
     * Streams the run. Callers should subscribe with a side effect (e.g.
     * .doOnNext(...) publishing tool_call events into an activity feed) before
     * blocking for the terminal done/error event, since consuming the Flux is
     * what drives that side effect -- collecting to a List and inspecting it
     * afterward, as ChatController does, satisfies this naturally.
     */
    @SuppressWarnings("unchecked")
    public Flux<StreamEvent> chatStream(String message, String investigationId) {
        return webClient.post()
                .uri(sidecarUrl + "/agent/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(Map.of("message", message, "investigationId", investigationId))
                .retrieve()
                .bodyToFlux(ServerSentEvent.class)
                .map(sse -> parse((String) sse.data()))
                .onErrorResume(ex -> {
                    log.error("Cursor sidecar stream failed", ex);
                    return Flux.just(new StreamEvent("error", null, null, null, null, null, null,
                            ex.getClass().getSimpleName() + ": " + ex.getMessage()));
                });
    }

    @SuppressWarnings("unchecked")
    private StreamEvent parse(String json) {
        try {
            Map<String, Object> m = new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, Map.class);
            String type = String.valueOf(m.get("event"));
            Object tokens = m.get("totalTokens");
            return new StreamEvent(
                    type,
                    (String) m.get("name"),
                    (String) m.get("status"),
                    (String) m.get("text"),
                    "done".equals(type) ? (String) m.get("text") : null,
                    (String) m.get("status"),
                    tokens instanceof Number n ? n.intValue() : null,
                    (String) m.get("error"));
        } catch (Exception e) {
            log.error("Failed to parse SSE frame from Cursor sidecar: {}", json, e);
            return new StreamEvent("error", null, null, null, null, null, null,
                    "Malformed event from sidecar: " + e.getMessage());
        }
    }

    /** Blocking convenience for a caller that only wants the terminal result. */
    public List<StreamEvent> collect(String message, String investigationId, Duration timeout) {
        return chatStream(message, investigationId).collectList().block(timeout);
    }
}
