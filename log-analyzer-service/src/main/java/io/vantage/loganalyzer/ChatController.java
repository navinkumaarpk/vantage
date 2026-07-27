package io.vantage.loganalyzer;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import io.modelcontextprotocol.client.McpSyncClient;
import io.vantage.agentcore.mcp.McpClientRegistry;
import io.vantage.agentcore.router.ToolgroupRouter;
import io.vantage.agentcore.streaming.AgentActivityEvent;

/**
 * ============================================================================
 * MEMORY WIRING — this is the direct equivalent of VISTA's ChatService.
 * ============================================================================
 *
 * VISTA (single global session):
 *   private static final String SESSION_ID = "vista-default-session";
 *   this.memoryAdvisor = MessageChatMemoryAdvisor.builder(chatMemory).build();
 *   ...
 *   ollamaClient.prompt()
 *       .advisors(a -> a.advisors(memoryAdvisor).param(ChatMemory.CONVERSATION_ID, SESSION_ID))
 *
 * Vantage (below) — identical pattern, extended to per-investigation
 * conversation IDs instead of one hardcoded global session, since we support
 * multiple concurrent investigations (VISTA doesn't):
 *   .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())   <- constructor, below
 *   .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, investigation.id))    <- per-request, in chat()
 *
 * Same advisor class, same ChatMemory.CONVERSATION_ID key, same auto-configured
 * ChatMemory bean (Spring AI provides this automatically once a model starter
 * is on the classpath — neither VISTA nor Vantage manually construct one).
 * The only real difference is VISTA uses one fixed ID; Vantage uses
 * investigation.id so each investigation gets its own independent memory.
 * ============================================================================
 */
@RestController
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final ChatClient chatClient;
    private final ToolgroupRouter router;
    private final McpClientRegistry registry;
    private final InvestigationStore investigations;

    public ChatController(ChatClient.Builder chatClientBuilder, ChatMemory chatMemory,
                           ToolgroupRouter router, McpClientRegistry registry, InvestigationStore investigations) {
        this.chatClient = chatClientBuilder
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .build();
        this.router = router;
        this.registry = registry;
        this.investigations = investigations;
    }

    public record ChatRequest(String message, String investigationId) {}

    private static final String SYSTEM_PROMPT = """
            You have access to specialized tools for this request when a relevant toolgroup is attached.
            When a tool is available, call it directly using your best interpretation of the user's message
            as input - extract the relevant search terms, symbol names, or anomaly description yourself
            rather than asking the user to restate or clarify before you try.

            If a tool call fails or errors out (a connection problem, a timeout, a system error - anything
            that isn't simply "no results found"), tell the user plainly that the underlying capability is
            currently unavailable and the request could not be completed right now. Do not respond by asking
            the user for more detail or rephrasing the question - more detail from them cannot fix a broken
            connection, and implying otherwise is misleading. Only ask a genuine clarifying question when the
            request itself is ambiguous about which tool or data to use, before any tool call is attempted.

            This is an ongoing conversation - treat earlier turns as real context. A follow-up like "what
            about the other one" or "check that same device" refers back to what was already discussed;
            don't ask the user to repeat information already established earlier in this conversation.
            """;

    @PostMapping("/api/chat")
    public Map<String, Object> chat(@RequestBody ChatRequest request) {
        Investigation investigation = resolveInvestigation(request);
        Optional<String> toolgroup = router.route(request.message(), registry.all().keySet());
        Optional<McpSyncClient> client = toolgroup.flatMap(registry::get);

        investigation.addTurn(new Investigation.Turn("user", request.message(), null, false, Instant.now()));
        investigation.activity.publish(AgentActivityEvent.started(investigation.id, toolgroup.orElse("none"), "chat_turn"));

        if (toolgroup.isPresent() && client.isEmpty()) {
            log.warn("Routed to toolgroup '{}' but it is not currently registered", toolgroup.get());
            return respond(investigation, toolgroup.get(), "that capability is not currently connected", true);
        }

        try {
            ChatClient.ChatClientRequestSpec promptSpec = chatClient.prompt()
                    .system(SYSTEM_PROMPT)
                    .user(request.message())
                    .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, investigation.id));
            if (client.isPresent()) {
                promptSpec = promptSpec.tools(new SyncMcpToolCallbackProvider(client.get()));
            }
            String answer = promptSpec.call().content();

            investigation.addTurn(new Investigation.Turn("agent", answer, toolgroup.orElse("none"), false, Instant.now()));
            investigation.activity.publish(AgentActivityEvent.succeeded(
                    investigation.id, toolgroup.orElse("none"), "chat_turn", "answered"));

            return Map.of("answer", answer, "toolgroupUsed", toolgroup.orElse("none"), "investigationId", investigation.id);
        } catch (Exception e) {
            log.error("Chat request failed (toolgroup={}): {}", toolgroup.orElse("none"), e.getMessage(), e);
            investigation.activity.publish(AgentActivityEvent.failed(
                    investigation.id, toolgroup.orElse("none"), "chat_turn", e.getMessage()));
            return respond(investigation, toolgroup.orElse(null), "something went wrong processing that request", true);
        }
    }

    private Investigation resolveInvestigation(ChatRequest request) {
        if (request.investigationId() != null) {
            return investigations.get(request.investigationId())
                    .orElseGet(() -> investigations.create(titleFrom(request.message())));
        }
        return investigations.create(titleFrom(request.message()));
    }

    private String titleFrom(String message) {
        String trimmed = message.trim();
        return trimmed.length() > 60 ? trimmed.substring(0, 60) + "..." : trimmed;
    }

    private Map<String, Object> respond(Investigation investigation, String toolgroup, String reason, boolean degraded) {
        String toolgroupPart = (toolgroup != null) ? " ('" + toolgroup + "')" : "";
        String answer = "This request couldn't be completed" + toolgroupPart + " - " + reason
                + ". The issue has been logged; try again shortly.";
        investigation.addTurn(new Investigation.Turn("agent", answer, toolgroup, true, Instant.now()));
        return Map.of(
                "answer", answer,
                "toolgroupUsed", "none",
                "investigationId", investigation.id,
                "degraded", degraded
        );
    }
}
