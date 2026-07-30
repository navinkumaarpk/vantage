# vantage-cursor-agent

Python sidecar exposing a Cursor agent (Composer 2.5 / Router / Claude via your
Cursor subscription) as an additional Vantage model option, alongside the
existing Server1-CPU and local-GPU paths. Not a Maven module -- it is Python,
deliberately outside the reactor.

## Why a sidecar

The Cursor SDK is TypeScript/Python only and Vantage is Spring Boot, so
`log-analyzer-service` proxies here the same way it already proxies log uploads
to `vantage-mcp-server`.

## Why local runtime

`LocalAgentOptions` runs the agent harness on this host, so MCP tool calls
originate here and reach `vantage-mcp-server` over the private network. Cloud
runtime would execute tools inside Cursor's infrastructure, which cannot reach a
private address.

Local runtime does NOT mean local inference: the model runs on Cursor's servers.
No GPU is needed here.

## Why MCP is inline rather than from .cursor/mcp.json

Per the SDK docs, "Without local.setting_sources, only inline servers are
loaded" -- file-based MCP config requires explicitly opting in with
`setting_sources=["user"]` (for `~/.cursor/mcp.json`) or `["project"]` (for
`.cursor/mcp.json`). Inline config is used instead so this sidecar is
self-contained and does not depend on ambient state existing on whichever host
runs it.

## Run

    python3 -m venv .venv && . .venv/bin/activate
    pip install -r requirements.txt
    export CURSOR_API_KEY='crsr_...'
    export VANTAGE_MCP_URL='http://192.168.1.108:8091/mcp'
    uvicorn app:app --host 0.0.0.0 --port 8094

Check before touching the UI:

    curl localhost:8094/health    # sdk import, key present, mcp url
    curl localhost:8094/models    # what this account can actually use

## Endpoints

    GET    /health
    GET    /models                    -> Cursor.models.list() for this account
    POST   /agent/chat                -> {text, toolCalls[], totalTokens, status}
    DELETE /agent/{investigationId}   -> close and drop a cached agent

## Known follow-ups

* Live streaming. The stream is drained server-side and results return in one
  response, so tool events reach the activity feed at end-of-run rather than
  live. `run.iter_text()` plus SSE through to the browser is the natural next
  step.
* `Agent.resume()`. Local agents persist conversation state through the bridge,
  so agent ids could be stored per investigation and resumed after a sidecar
  restart. Note inline MCP servers are NOT persisted across resume and must be
  passed again.
