package io.vantage.loganalyzer;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    private final McpClientRegistry registry;
    private final InvestigationStore investigations;
    private final CursorAgentClient cursorAgent;

    public ChatController(OpenAiChatModel openAiChatModel,
                           ObjectProvider<OllamaChatModel> ollamaProvider,
                           ChatMemory chatMemory,
                           VantageOllamaProperties ollamaProperties,
                           McpClientRegistry registry, InvestigationStore investigations,
                           CursorAgentClient cursorAgent) {
        this.memoryAdvisor = MessageChatMemoryAdvisor.builder(chatMemory).build();
        this.registry = registry;
        this.investigations = investigations;
        this.cursorAgent = cursorAgent;

        this.openAiClient = ChatClient.builder(openAiChatModel).build();
        modelOptions.add(new ModelOption("server1", "Qwen2.5-7B (Server1, CPU)", "openai", null));
        if (cursorAgent.isConfigured()) {
            // Frontier model via the Cursor subscription, through the Python
            // sidecar. Additive: listed alongside the local options so they can
            // be A/B compared rather than replaced.
            modelOptions.add(new ModelOption("cursor", "Cursor (Composer 2.5)", "cursor", null));
        }

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
            You have tools for searching application logs, searching the codebase, and searching past Jira
            tickets. Choose whichever fit and call them directly, using your own interpretation of the
            user's message to fill in arguments - extract the relevant search terms, symbol names, or
            anomaly description yourself rather than asking the user to restate before you try.

            When investigating logs, work in two steps rather than answering from a single search. First
            locate an anchor entry, then expand: pass the anchor's timestamp AND its thread to
            get_log_context to see the chronological sequence leading to the failure. This matters because
            one device often appears under several different identifiers across log lines, so the entries
            explaining WHY something failed frequently do not mention the identifier you searched for --
            but they do share the worker thread. On an unfamiliar log set call summarize_logs first.

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
        // Router removed 2026-07-27, for two converging reasons. It failed
        // silently on elliptical follow-ups ("try this timestamp instead"):
        // keyword matching found nothing, no tool was attached, and the model
        // fabricated claims about data it never retrieved -- observed across
        // three consecutive turns. And after consolidating the three MCP
        // servers into one, there is only a single connection to route to
        // anyway. agent-core's ToolgroupRouter classes are left in place as
        // unused library capability, should a constrained-model deployment
        // ever need per-toolgroup context control again.
        Map<String, McpSyncClient> clients = registry.all();
        String modelKey = (request.modelKey() != null) ? request.modelKey() : "server1";
        String ollamaModelName = ollamaModelNamesByKey.get(modelKey);

        investigation.addTurn(new Investigation.Turn("user", request.message(), null, false, Instant.now()));

        investigation.activity.publish(AgentActivityEvent.succeeded(investigation.id,
                String.join(",", clients.keySet()), "tools",
                clients.isEmpty() ? "No MCP server connected" : "All tools available"));

        // Cursor path. The sidecar's Agent owns conversation state, so the
        // Spring AI ChatMemory advisor is deliberately not used here.
        if ("cursor".equals(modelKey)) {
            if (!cursorAgent.isConfigured()) {
                return respond(investigation, null, "the Cursor agent sidecar is not configured", true);
            }
            investigation.activity.publish(AgentActivityEvent.started(investigation.id, "cursor", "agent_run"));

            // Consume the SSE stream, publishing a real activity event for
            // every tool_call frame AS it arrives -- not once at the end.
            // .doOnNext runs during collectList()'s subscription, so these
            // publishes happen live while this request is still blocking,
            // and the browser's already-open activity SSE connection shows
            // them immediately even though /api/chat itself hasn't returned.
            List<CursorAgentClient.StreamEvent> events;
            try {
                events = cursorAgent.chatStream(request.message(), investigation.id)
                        .doOnNext(evt -> {
                            if ("tool_call".equals(evt.type())) {
                                investigation.activity.publish(AgentActivityEvent.succeeded(investigation.id,
                                        "cursor", "tool_call", evt.toolName() + " (" + evt.toolStatus() + ")"));
                            }
                        })
                        .collectList()
                        .block(java.time.Duration.ofMinutes(5));
            } catch (Exception e) {
                log.error("Cursor agent stream failed", e);
                investigation.activity.publish(AgentActivityEvent.failed(investigation.id, "cursor", "agent_run",
                        e.getMessage()));
                return respond(investigation, null, "the Cursor agent could not complete that request", true);
            }

            CursorAgentClient.StreamEvent terminal = (events == null) ? null : events.stream()
                    .filter(e -> "done".equals(e.type()) || "error".equals(e.type()))
                    .reduce((first, second) -> second) // last one wins
                    .orElse(null);

            if (terminal == null || "error".equals(terminal.type())
                    || terminal.finalText() == null || terminal.finalText().isBlank()) {
                String reason = (terminal != null && terminal.error() != null) ? terminal.error() : "empty response";
                log.error("Cursor agent failed: {}", reason);
                investigation.activity.publish(AgentActivityEvent.failed(investigation.id, "cursor", "agent_run", reason));
                return respond(investigation, null, "the Cursor agent could not complete that request", true);
            }

            investigation.activity.publish(AgentActivityEvent.succeeded(investigation.id, "cursor",
                    "agent_run", "Answer ready"));
            investigation.addTurn(new Investigation.Turn("agent", terminal.finalText(), "cursor", false, Instant.now()));

            var out = new java.util.LinkedHashMap<String, Object>();
            out.put("answer", terminal.finalText());
            out.put("toolgroupUsed", "cursor");
            out.put("investigationId", investigation.id);
            out.put("modelUsed", modelKey);
            if (terminal.totalTokens() != null) {
                out.put("totalTokens", terminal.totalTokens());
            }
            return out;
        }

        // Unknown-model guard sits after the cursor branch on purpose: "cursor"
        // is neither server1 nor an Ollama key, so checking earlier rejected it.
        boolean useOllama = ollamaModelName != null;
        if (!"server1".equals(modelKey) && !useOllama) {
            log.warn("Requested unknown or unavailable model key '{}'", modelKey);
            return respond(investigation, null, "the requested model ('" + modelKey + "') is not currently available", true);
        }

        investigation.activity.publish(AgentActivityEvent.started(investigation.id, "model", "llm_call"));

        try {
            ChatClient.ChatClientRequestSpec promptSpec;
            if (useOllama) {
                // Bug found and fixed (2026-07-27): responses were cutting off
                // mid-generation (confirmed via a real test: stopped cold right
                // after a markdown table header, no error). gpt-oss:20b natively
                // supports up to 128K context, but Ollama's runtime default
                // num_ctx is far smaller (often 2048-4096) unless explicitly
                // raised -- the model's own capability doesn't help if the
                // runtime never gives it the budget to use it. This bites hardest
                // on the "code" toolgroup specifically, where tool results
                // (get_source_context returning up to ~50 lines, stacked search
                // matches) plus the system prompt can consume most of a small
                // default window before generation even starts. Raised both the
                // context window and the output-length cap explicitly.
                OllamaChatOptions.Builder optionsBuilder = OllamaChatOptions.builder()
                        .model(ollamaModelName)
                        .numCtx(8192)
                        .numPredict(4096);
                // GPT-OSS is a reasoning model whose actual answer can land in a
                // separate "thinking" field rather than "content" -- and per
                // Ollama's own docs, it specifically REQUIRES an explicit thinking
                // level (low/medium/high); passing nothing or a boolean is silently
                // ignored. "low" keeps reasoning brief since we want direct answers
                // for tool-grounded investigation queries, not deep chain-of-thought.
                if (ollamaModelName != null && ollamaModelName.startsWith("gpt-oss")) {
                    optionsBuilder.thinkLow();
                }
                promptSpec = ollamaClient.prompt()
                        .advisors(a -> a.advisors(memoryAdvisor).param(ChatMemory.CONVERSATION_ID, investigation.id))
                        .options(optionsBuilder)
                        .system(SYSTEM_PROMPT)
                        .user(request.message());
            } else {
                promptSpec = openAiClient.prompt()
                        .advisors(a -> a.advisors(memoryAdvisor).param(ChatMemory.CONVERSATION_ID, investigation.id))
                        .system(SYSTEM_PROMPT)
                        .user(request.message());
            }
            if (!clients.isEmpty()) {
                // One provider per connected MCP client, attached together.
                // Varargs form rather than a possible List-accepting
                // constructor, to stay near the single-provider call shape
                // already known to compile against this Spring AI version.
                var providers = clients.values().stream()
                        .map(SyncMcpToolCallbackProvider::new)
                        .toArray(org.springframework.ai.tool.ToolCallbackProvider[]::new);
                promptSpec = promptSpec.tools((Object[]) providers);
            }

            // Fallback for reasoning models that can put their real answer in a
            // "thinking" metadata field instead of "content" under some
            // conditions -- not fully confirmed which conditions trigger this
            // (tool-calling is the leading theory, not verified), but the
            // fallback is harmless when content is already populated normally.
            var chatResponse = promptSpec.call().chatResponse();
            String answer = chatResponse.getResult().getOutput().getText();
            if (answer == null || answer.isBlank()) {
                Object thinking = chatResponse.getResult().getMetadata().get("thinking");
                if (thinking != null && !thinking.toString().isBlank()) {
                    log.warn("Model '{}' returned empty content; falling back to 'thinking' metadata field", modelKey);
                    answer = thinking.toString();
                } else {
                    answer = "(The model returned an empty response. This can happen with reasoning models under "
                            + "certain conditions -- try rephrasing, or switch models if it persists.)";
                }
            }

            investigation.addTurn(new Investigation.Turn("agent", answer, "all", false, Instant.now()));
            investigation.activity.publish(AgentActivityEvent.succeeded(
                    investigation.id, "model", "llm_call", "Answer ready"));

            return Map.of("answer", answer, "toolgroupUsed", "all",
                    "investigationId", investigation.id, "modelUsed", modelKey);
        } catch (Exception e) {
            log.error("Chat request failed (model={}): {}", modelKey, e.getMessage(), e);
            investigation.activity.publish(AgentActivityEvent.failed(
                    investigation.id, "model", "llm_call", e.getMessage()));
            return respond(investigation, null, "something went wrong processing that request", true);
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
