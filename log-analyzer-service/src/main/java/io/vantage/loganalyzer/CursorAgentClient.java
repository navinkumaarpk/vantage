package io.vantage.loganalyzer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Calls the Python Cursor SDK sidecar (see vantage-cursor-agent/).
 *
 * <p>Same proxy shape as LogUploadProxyController: the browser only talks to
 * this service, and this service makes the server-to-server hop.
 *
 * <p>Non-streaming by design for now. The sidecar drains the run and returns
 * the answer plus the tool calls the agent actually made. That delivers the
 * thing the local-model path cannot -- real per-tool-call visibility, which
 * Spring AI never surfaces from its internal tool loop -- without rewriting
 * /api/chat and the frontend for streaming. Tool events therefore arrive at
 * end-of-run rather than live.
 */
@Component
public class CursorAgentClient {

    private static final Logger log = LoggerFactory.getLogger(CursorAgentClient.class);

    // Bug found and fixed (2026-07-30): RestClient.create() with no explicit
    // timeout hit ReadTimeoutException at 38s on a real multi-tool-call
    // investigation -- the simplest possible probe query alone burned 45,871
    // tokens, so a real "what went wrong with X" run (multiple round trips to
    // a remote LLM provider, not local inference) was never going to fit in
    // 38s. First fix attempt used ClientHttpRequestFactoryBuilder /
    // ClientHttpRequestFactorySettings, which do not exist on this classpath
    // -- falling back to SimpleClientHttpRequestFactory, a much older and
    // more conservatively-maintained API, less likely to have moved.
    private final RestClient restClient;

    {
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(300_000); // 5 minutes -- generous on purpose,
                                          // this path is categorically slower
                                          // than the local-model paths.
        restClient = RestClient.builder().requestFactory(factory).build();
    }

    @Value("${vantage.cursor-agent.url:}")
    private String sidecarUrl;

    /** One tool invocation as reported by the SDK stream. */
    public record ToolCall(String name, String status) {}

    public record Result(String text, List<ToolCall> toolCalls, Integer totalTokens, String error) {}

    public boolean isConfigured() {
        return sidecarUrl != null && !sidecarUrl.isBlank();
    }

    @SuppressWarnings("unchecked")
    public Result chat(String message, String investigationId) {
        try {
            Map<String, Object> body = restClient.post()
                    .uri(sidecarUrl + "/agent/chat")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("message", message, "investigationId", investigationId))
                    .retrieve()
                    .body(Map.class);

            if (body == null) {
                return new Result("", List.of(), null, "Empty response from Cursor sidecar");
            }

            List<ToolCall> tools = new ArrayList<>();
            Object raw = body.get("toolCalls");
            if (raw instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof Map<?, ?> m) {
                        tools.add(new ToolCall(
                                Objects.toString(m.get("name"), "?"),
                                Objects.toString(m.get("status"), "?")));
                    }
                }
            }

            Object err = body.get("error");
            Object tokens = body.get("totalTokens");
            return new Result(
                    String.valueOf(body.getOrDefault("text", "")),
                    tools,
                    tokens instanceof Number n ? n.intValue() : null,
                    err == null ? null : String.valueOf(err));
        } catch (Exception e) {
            log.error("Cursor sidecar call failed", e);
            return new Result("", List.of(), null, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }
}
