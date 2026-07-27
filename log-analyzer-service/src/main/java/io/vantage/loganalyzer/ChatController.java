package io.vantage.loganalyzer;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import io.modelcontextprotocol.client.McpSyncClient;
import io.vantage.agentcore.mcp.McpClientRegistry;
import io.vantage.agentcore.router.ToolgroupRouter;
import io.vantage.agentcore.streaming.AgentActivityEvent;

/**
 * ============================================================================
 * MULTI-MODEL SELECTION — mirrors VISTA's ChatService pattern.
 *
 * Bug found and fixed (2026-07-27): .options(OllamaChatOptions.builder()
 * .model(x).build()) failed to compile -- ChatClientRequestSpec.options()
 * is generic over the BUILDER type, not the built ChatOptions object. Fix
 * confirmed directly from VISTA's own working code, which was pasted into
 * this conversation: ".options(OllamaChatOptions.builder().model(x))" --
 * no .build() call. Copying that exact shape below.
 * ============================================================================
 */
@RestController
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final ChatClient openAiClient;
    private final ChatClient ollamaClient; // null if Ollama not configured/available at startup
    private final MessageChatMemoryAdvisor memoryAdvisor;
    private final List<ModelOption> modelOptions = new java.util.ArrayList<>();
    private final Map<String, String> ollamaModelNamesByKey = new HashMap<>();

    private final ToolgroupRouter router;
    private final McpClientRegistry registry;
    private final InvestigationStore investigations;

    public ChatController(OpenAiChatModel openAiChatModel,
                           ObjectProvider<OllamaChatModel> ollamaProvider,
                           ChatMemory chatMemory,
                           VantageOllamaProperties ollamaProperties,
                           ToolgroupRouter router, McpClientRegistry registry, InvestigationStore investigations) {
        this.memoryAdvisor = MessageChatMemoryAdvisor.builder(chatMemory).build();
        this.router = router;
        this.registry = registry;
        this.investigations = investigations;

        this.openAiClient = ChatClient.builder(openAiChatModel).build();
        modelOptions.add(new ModelOption("server1", "Qwen2.5-7B (Server1, CPU)", "openai", null));

        OllamaChatModel om = ollamaProvider.getIfAvailable();
        if (om != null && !ollamaProperties.getModels().isEmpty()) {
            this.ollamaClient = ChatClient.builder(om).build();
            for (VantageOllamaProperties.ModelEntry entry : ollamaProperties.getModels()) {
                modelOptions.add(new ModelOption(entry.getKey(), entry.getLabel(), "ollama", entry.getModelName()));
                ollamaModelNamesByKey.put(entry.getKey(), entry.getModelName());
            }
        } else {
            this.ollamaClient = null;
        }
    }

    public record ChatRequest(String message, String investigationId, String modelKey) {}

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

    @GetMapping("/api/models")
    public List<ModelOption> models() {
        return List.copyOf(modelOptions);
    }

    @PostMapping("/api/chat")
    public Map<String, Object> chat(@RequestBody ChatRequest request) {
        Investigation investigation = resolveInvestigation(request);
        Optional<String> toolgroup = router.route(request.message(), registry.all().keySet());
        Optional<McpSyncClient> client = toolgroup.flatMap(registry::get);
        String modelKey = (request.modelKey() != null) ? request.modelKey() : "server1";
        String ollamaModelName = ollamaModelNamesByKey.get(modelKey);

        investigation.addTurn(new Investigation.Turn("user", request.message(), null, false, Instant.now()));

        investigation.activity.publish(toolgroup.isPresent()
                ? AgentActivityEvent.succeeded(investigation.id, toolgroup.get(), "routing", "Routed to '" + toolgroup.get() + "'")
                : AgentActivityEvent.succeeded(investigation.id, "none", "routing", "No specific toolgroup matched"));

        if (toolgroup.isPresent() && client.isEmpty()) {
            log.warn("Routed to toolgroup '{}' but it is not currently registered", toolgroup.get());
            return respond(investigation, toolgroup.get(), "that capability is not currently connected", true);
        }

        boolean useOllama = ollamaModelName != null;
        if (!"server1".equals(modelKey) && !useOllama) {
            log.warn("Requested unknown or unavailable model key '{}'", modelKey);
            return respond(investigation, toolgroup.orElse(null), "the requested model ('" + modelKey + "') is not currently available", true);
        }

        investigation.activity.publish(AgentActivityEvent.started(investigation.id, toolgroup.orElse("none"), "llm_call"));

        try {
            ChatClient.ChatClientRequestSpec promptSpec;
            if (useOllama) {
                promptSpec = ollamaClient.prompt()
                        .advisors(a -> a.advisors(memoryAdvisor).param(ChatMemory.CONVERSATION_ID, investigation.id))
                        .options(OllamaChatOptions.builder().model(ollamaModelName))
                        .system(SYSTEM_PROMPT)
                        .user(request.message());
            } else {
                promptSpec = openAiClient.prompt()
                        .advisors(a -> a.advisors(memoryAdvisor).param(ChatMemory.CONVERSATION_ID, investigation.id))
                        .system(SYSTEM_PROMPT)
                        .user(request.message());
            }
            if (client.isPresent()) {
                promptSpec = promptSpec.tools(new SyncMcpToolCallbackProvider(client.get()));
            }
            String answer = promptSpec.call().content();

            investigation.addTurn(new Investigation.Turn("agent", answer, toolgroup.orElse("none"), false, Instant.now()));
            investigation.activity.publish(AgentActivityEvent.succeeded(
                    investigation.id, toolgroup.orElse("none"), "llm_call", "Answer ready"));

            return Map.of("answer", answer, "toolgroupUsed", toolgroup.orElse("none"), "investigationId", investigation.id);
        } catch (Exception e) {
            log.error("Chat request failed (toolgroup={}, model={}): {}", toolgroup.orElse("none"), modelKey, e.getMessage(), e);
            investigation.activity.publish(AgentActivityEvent.failed(
                    investigation.id, toolgroup.orElse("none"), "llm_call", e.getMessage()));
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
