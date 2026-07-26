package io.vantage.loganalyzer;

import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import io.modelcontextprotocol.client.McpSyncClient;
import io.vantage.agentcore.mcp.McpClientRegistry;
import io.vantage.agentcore.router.ToolgroupRouter;

/**
 * <strong>Not verified against real dependencies</strong> — same caveat
 * class as the earlier MCP client wiring. The ChatClient fluent API shape
 * (.prompt().user().tools().call().content()) and SyncMcpToolCallbackProvider's
 * exact package are best-effort based on the pattern documented in
 * agent-core's README, not compiled against the real spring-ai-starter-model-openai
 * jar in this sandbox.
 *
 * <p>This is the first real end-to-end path: user message -> router picks a
 * toolgroup (or none) -> if picked, that toolgroup's MCP tools get attached
 * to the LLM call, scoped to just that one toolgroup's schemas per the
 * context-control design from the architecture discussion -> LLM decides
 * whether to actually call the tool and synthesizes a response either way.
 *
 * <p><strong>Resilience added 2026-07-26:</strong> previously the whole
 * flow — registry lookup, tool attachment, the actual LLM call — had zero
 * error handling. Any failure (a toolgroup unregistered because it never
 * connected, per agent-core's matching fix; or a registered toolgroup whose
 * session died after the fact, the gap that fix explicitly doesn't cover)
 * would bubble up as an unhandled exception and a generic 500. Now wrapped
 * so a broken toolgroup degrades this one request gracefully — a clear
 * message and {@code degraded: true} in the response — instead of an
 * opaque failure.
 */
@RestController
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final ChatClient chatClient;
    private final ToolgroupRouter router;
    private final McpClientRegistry registry;

    public ChatController(ChatClient.Builder chatClientBuilder, ToolgroupRouter router, McpClientRegistry registry) {
        this.chatClient = chatClientBuilder.build();
        this.router = router;
        this.registry = registry;
    }

    public record ChatRequest(String message) {}

    private static final String SYSTEM_PROMPT = """
            You have access to specialized tools for this request when a relevant toolgroup is attached.
            When a tool is available, call it directly using your best interpretation of the user's message
            as input — extract the relevant search terms, symbol names, or anomaly description yourself
            rather than asking the user to restate or clarify before you try.

            If a tool call fails or errors out (a connection problem, a timeout, a system error — anything
            that isn't simply "no results found"), tell the user plainly that the underlying capability is
            currently unavailable and the request could not be completed right now. Do not respond by asking
            the user for more detail or rephrasing the question — more detail from them cannot fix a broken
            connection, and implying otherwise is misleading. Only ask a genuine clarifying question when the
            request itself is ambiguous about which tool or data to use, before any tool call is attempted.
            """;

    @PostMapping("/api/chat")
    public Map<String, Object> chat(@RequestBody ChatRequest request) {
        Optional<String> toolgroup = router.route(request.message(), registry.all().keySet());
        Optional<McpSyncClient> client = toolgroup.flatMap(registry::get);

        if (toolgroup.isPresent() && client.isEmpty()) {
            // Defense-in-depth: shouldn't happen today since the router only
            // sees currently-registered toolgroups, but don't rely on that
            // invariant holding forever (e.g. if a future deregister-on-
            // failure mechanism gets added).
            log.warn("Routed to toolgroup '{}' but it is not currently registered", toolgroup.get());
            return degradedResponse(toolgroup.get(), "that capability is not currently connected");
        }

        try {
            ChatClient.ChatClientRequestSpec promptSpec = chatClient.prompt()
                    .system(SYSTEM_PROMPT)
                    .user(request.message());
            if (client.isPresent()) {
                promptSpec = promptSpec.tools(new SyncMcpToolCallbackProvider(client.get()));
            }
            String answer = promptSpec.call().content();
            return Map.of("answer", answer, "toolgroupUsed", toolgroup.orElse("none"));
        } catch (Exception e) {
            // Covers the real gap: a toolgroup that connected fine at
            // startup but whose session has since died (server restarted,
            // crashed, network dropped) — no reconnect logic exists yet, so
            // this is what actually catches that case today.
            log.error("Chat request failed (toolgroup={}): {}", toolgroup.orElse("none"), e.getMessage(), e);
            return degradedResponse(toolgroup.orElse(null), "something went wrong processing that request");
        }
    }

    private Map<String, Object> degradedResponse(String toolgroup, String reason) {
        String toolgroupPart = (toolgroup != null) ? " ('" + toolgroup + "')" : "";
        return Map.of(
                "answer", "This request couldn't be completed" + toolgroupPart + " — " + reason
                        + ". The issue has been logged; try again shortly.",
                "toolgroupUsed", "none",
                "degraded", true
        );
    }
}

