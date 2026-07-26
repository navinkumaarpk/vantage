# agent-core

Shared library for `log-analyzer-service` and `testcase-generator-service`.
Not a runnable service on its own.

## Scope (what belongs here)

Domain-agnostic scaffolding only:

- `mcp/` — `McpClientRegistry`, holding one MCP client per named toolgroup
- `router/` — `ToolgroupRouter` interface + `RuleBasedToolgroupRouter`, the
  cheap deterministic first-pass toolgroup selection described in the
  architecture discussion
- `streaming/` — `AgentActivityEvent` / `AgentActivityPublisher`, backing the
  live-activity-feed SSE endpoints both product frontends expect
- `rag/` — `EmbeddingClient`, abstracting over the llamafile embedding
  endpoint on Server1

## Scope (what does NOT belong here)

- Anything specific to log analysis, code navigation, Jira content, or test
  generation — that's each service's own concern, living in that service's
  module, not here
- Actual MCP server implementations (the ES tool, OpenGrok tool, report-gen
  tool, etc.) — those are separate deployables, not part of this library
- Fixed toolgroup names — `log-analyzer-service` and `testcase-generator-service`
  have entirely different toolgroup sets; agent-core takes them as
  configuration, never hardcodes them

## Why a monorepo module instead of a separate repo

See the parent `pom.xml` comment and the architecture discussion: this keeps
`agent-core` changes in sync across both consumers without a separate
publish/version-bump cycle, appropriate while both products are under
active, roughly-parallel development. Revisit splitting this into its own
published artifact if the two services' release cadences diverge later.

## MCP client wiring (config-driven, in agent-core)

Auto-activates for any service depending on agent-core. Each service supplies its own toolgroup names and URLs in `application.yml`:

```yaml
vantage:
  mcp:
    toolgroups:
      logs:
        url: http://server2:8081/mcp
      jira_rag:
        url: http://server2:8083/mcp
```

`VantageMcpAutoConfiguration` reads this into `VantageMcpProperties`, and `McpClientRegistrar` builds one `McpSyncClient` per entry at startup, registering each into `McpClientRegistry`. Client construction happens eagerly (not lazily on first use) so a misconfigured or unreachable MCP server fails startup loudly rather than failing confusingly on the first request that tries to route to it.

**Not yet verified against the real dependency** — see the javadoc on `McpClientRegistrar`. The transport/builder shape is best-effort; this sandbox can't reach Maven Central to confirm it compiles as written.

## Toolgroup router usage pattern

The router only *selects* a toolgroup name — it's the caller's job to then
look that name up in `McpClientRegistry` and attach only that client's tools
to the next `ChatClient` call, which is what actually keeps context from
bloating:

```java
Optional<String> toolgroup = router.route(userQuery, registry.all().keySet());
McpSyncClient client = toolgroup
        .flatMap(registry::get)
        .orElseThrow();

ChatResponse response = chatClient.prompt()
        .user(userQuery)
        .tools(new SyncMcpToolCallbackProvider(client))
        .call()
        .chatResponse();
```

If the model's response indicates it needs a different toolgroup mid-investigation
(e.g. logs pointed at a class:line reference, now needs the code toolgroup),
re-run routing and re-attach rather than keeping every toolgroup's tools
attached for the whole conversation.
