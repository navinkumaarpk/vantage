package io.vantage.loganalyzer;

import io.modelcontextprotocol.client.McpSyncClient;
import io.vantage.agentcore.mcp.McpClientRegistry;
import io.vantage.agentcore.router.ToolgroupRouter;
import java.util.Map;
import java.util.Optional;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

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
 */
@RestController
public class ChatController {

    private final ChatClient chatClient;
    private final ToolgroupRouter router;
    private final McpClientRegistry registry;

    public ChatController(ChatClient.Builder chatClientBuilder, ToolgroupRouter router, McpClientRegistry registry) {
        this.chatClient = chatClientBuilder.build();
        this.router = router;
        this.registry = registry;
    }

    public record ChatRequest(String message) {}

    @PostMapping("/api/chat")
    public Map<String, Object> chat(@RequestBody ChatRequest request) {
        Optional<String> toolgroup = router.route(request.message(), registry.all().keySet());

        ChatClient.ChatClientRequestSpec promptSpec = chatClient.prompt().user(request.message());

        if (toolgroup.isPresent()) {
            McpSyncClient client = registry.get(toolgroup.get()).orElseThrow();
            promptSpec = promptSpec.tools(new SyncMcpToolCallbackProvider(client));
        }

        String answer = promptSpec.call().content();

        return Map.of(
                "answer", answer,
                "toolgroupUsed", toolgroup.orElse("none")
        );
    }
}
