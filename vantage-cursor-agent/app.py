"""
Vantage <-> Cursor SDK sidecar.

Exposes a Cursor agent over HTTP so log-analyzer-service (Java) can offer it as
an additional model option. One Agent per investigation: the Agent holds
conversation state, which is what gives this path multi-turn memory, so Spring
AI's ChatMemory advisor is deliberately unused here.

Written against the documented Python SDK surface. Notes on choices that are
not obvious from the quick start:

* mcp_servers uses HttpMcpServerConfig(type="http"). "http" is the documented
  value; there is no "streamable-http" here even though that is what the MCP
  server itself speaks.
* setting_sources is intentionally NOT set. Per the docs, "Without
  local.setting_sources, only inline servers are loaded" -- so the IDE's
  ~/.cursor/mcp.json is ignored and this sidecar is self-contained. To pick up
  the IDE config instead you would pass setting_sources=["user"].
* A run stream is consumable once: run.messages(), run.events() and
  run.iter_text() all advance the same stream. So we drain messages() to
  collect tool calls and text, then call run.wait() for the terminal
  RunResult rather than run.text(), which would race the drained stream.
* Local runtime means the agent harness runs here, so MCP calls originate on
  this host and can reach vantage-mcp-server on the private network. It does
  NOT mean local inference -- the model runs on Cursor's servers, no GPU here.
* Local agents never raise AgentBusyError (that is cloud-only), so concurrent
  sends degrade rather than 409. local={"force": True} clears a stuck run.
"""

import logging
import os
from typing import Any, Dict, List, Optional

from fastapi import FastAPI
from pydantic import BaseModel

try:
    from cursor_sdk import (
        Agent,
        AgentOptions,
        CursorAgentError,
        CursorClient,
        HttpMcpServerConfig,
        LocalAgentOptions,
    )

    SDK_IMPORT_ERROR: Optional[str] = None
except ImportError as exc:  # pragma: no cover
    Agent = AgentOptions = CursorClient = HttpMcpServerConfig = LocalAgentOptions = None
    CursorAgentError = Exception
    SDK_IMPORT_ERROR = str(exc)

logging.basicConfig(level=logging.INFO)
log = logging.getLogger("vantage-cursor-agent")

CURSOR_API_KEY = os.environ.get("CURSOR_API_KEY", "")
VANTAGE_MCP_URL = os.environ.get("VANTAGE_MCP_URL", "http://192.168.1.108:8091/mcp")
CURSOR_MODEL = os.environ.get("CURSOR_MODEL", "composer-2.5")
# The local runtime needs a workspace. We are not editing code, but the bridge
# is workspace-scoped, so keep it stable across restarts so local persistence
# and Agent.resume() resolve the same agents.
WORKSPACE = os.environ.get("AGENT_CWD", os.getcwd())

app = FastAPI(title="vantage-cursor-agent")

_client: Any = None
_agents: Dict[str, Any] = {}
_greeted: set = set()

GUIDANCE = """\
You are Vantage, an assistant for diagnosing application issues in OltMgr/DCI.

You have MCP tools for searching application logs, searching the codebase, and
searching past Jira tickets. Call them directly using your own interpretation of
the question rather than asking the user to restate it.

When investigating logs, work in two steps. First locate an anchor entry with
search_logs, then expand: pass that entry's timestamp AND its thread to
get_log_context to see the chronological sequence leading to the failure. This
matters because one device often appears under several different identifiers
across log lines, so the entries explaining WHY something failed frequently do
not mention the identifier you searched for -- but they do share the worker
thread. On an unfamiliar log set, call summarize_logs first: in Java logs every
statement has a unique source file:line, so grouping by source_file is exact
template detection and collapses thousands of lines into a handful of call sites.

If a tool call fails, say plainly that the capability is unavailable. Do not ask
the user for more detail -- more detail cannot fix a broken connection.
"""


class ChatRequest(BaseModel):
    message: str
    investigationId: str


class ChatResponse(BaseModel):
    text: str
    toolCalls: List[Dict[str, Any]] = []
    totalTokens: Optional[int] = None
    status: Optional[str] = None
    error: Optional[str] = None
    requestId: Optional[str] = None


def _get_client() -> Any:
    """
    One long-lived bridge for the process. The docs call this out specifically:
    when the bridge runs as a long-lived sidecar, give it the same workspace as
    the agents so local list/get/resume resolve correctly.
    """
    global _client
    if _client is None:
        _client = CursorClient.launch_bridge(workspace=WORKSPACE)
        log.info("Cursor bridge launched for workspace %s", WORKSPACE)
    return _client


def _mcp_servers() -> Dict[str, Any]:
    return {"vantage": HttpMcpServerConfig(url=VANTAGE_MCP_URL, type="http")}


def _build_agent() -> Any:
    return _get_client().agents.create(
        AgentOptions(
            model=CURSOR_MODEL,
            api_key=CURSOR_API_KEY,
            local=LocalAgentOptions(cwd=WORKSPACE),
            mcp_servers=_mcp_servers(),
        )
    )


def _drain(run: Any) -> ChatResponse:
    """
    Drain the run stream once, collecting tool calls and assistant text, then
    read the terminal result via wait().

    SDKToolUseMessage is emitted twice per call -- status="running" with args,
    then status="completed"/"error" with result -- so both are recorded. That is
    useful rather than noisy: it gives the activity feed a real start/finish
    sequence per tool instead of a single opaque event.
    """
    tool_calls: List[Dict[str, Any]] = []
    text_parts: List[str] = []

    for message in run.messages():
        kind = getattr(message, "type", None)
        if kind == "assistant":
            for block in getattr(message.message, "content", []) or []:
                if getattr(block, "type", None) == "text":
                    text_parts.append(getattr(block, "text", "") or "")
        elif kind == "tool_call":
            tool_calls.append(
                {
                    "name": getattr(message, "name", "?"),
                    "status": getattr(message, "status", "?"),
                    "callId": getattr(message, "call_id", None),
                }
            )

    result = run.wait()
    final_text = (getattr(result, "result", None) or "").strip() or "".join(text_parts).strip()
    usage = getattr(result, "usage", None)

    return ChatResponse(
        text=final_text,
        toolCalls=tool_calls,
        totalTokens=getattr(usage, "total_tokens", None) if usage else None,
        status=getattr(result, "status", None),
    )


@app.get("/health")
def health() -> Dict[str, Any]:
    return {
        "status": "ok" if Agent is not None and CURSOR_API_KEY else "degraded",
        "sdkImported": Agent is not None,
        "sdkImportError": SDK_IMPORT_ERROR,
        "apiKeySet": bool(CURSOR_API_KEY),
        "mcpUrl": VANTAGE_MCP_URL,
        "model": CURSOR_MODEL,
        "workspace": WORKSPACE,
        "liveAgents": len(_agents),
    }


@app.get("/models")
def models() -> Dict[str, Any]:
    """
    Discover what this account can actually use, rather than hard-coding a model
    id. Useful because the catalog is account- and team-specific -- Cursor Router
    only appears as auto-smart when it is enabled for the key's team.
    """
    if Agent is None:
        return {"error": "cursor-sdk not installed", "models": []}
    try:
        from cursor_sdk import Cursor

        found = Cursor.models.list()
        return {"models": [{"id": m.id} for m in found]}
    except CursorAgentError as exc:
        return {"error": f"{type(exc).__name__}: {exc}", "models": []}
    except Exception as exc:  # noqa: BLE001
        return {"error": str(exc), "models": []}


@app.post("/agent/chat", response_model=ChatResponse)
def chat(req: ChatRequest) -> ChatResponse:
    if Agent is None:
        return ChatResponse(text="", error=f"cursor-sdk not installed: {SDK_IMPORT_ERROR}")
    if not CURSOR_API_KEY:
        return ChatResponse(text="", error="CURSOR_API_KEY is not set on the sidecar")

    try:
        agent = _agents.get(req.investigationId)
        if agent is None:
            agent = _build_agent()
            _agents[req.investigationId] = agent
            log.info(
                "Created agent %s for investigation %s",
                getattr(agent, "agent_id", "?"),
                req.investigationId,
            )

        # Prepend guidance to the FIRST message only. The Agent retains
        # conversation state, so sending it as its own agent.send() would burn a
        # whole extra run for no benefit.
        if req.investigationId in _greeted:
            prompt = req.message
        else:
            prompt = GUIDANCE + "\n\n---\n\n" + req.message
            _greeted.add(req.investigationId)

        return _drain(agent.send(prompt))

    except CursorAgentError as exc:
        log.exception("Cursor run failed for investigation %s", req.investigationId)
        return ChatResponse(
            text="",
            error=f"{type(exc).__name__}: {exc}",
            requestId=getattr(exc, "request_id", None),
        )
    except Exception as exc:  # noqa: BLE001
        log.exception("Unexpected sidecar failure for investigation %s", req.investigationId)
        return ChatResponse(text="", error=f"{type(exc).__name__}: {exc}")


@app.delete("/agent/{investigation_id}")
def drop(investigation_id: str) -> Dict[str, Any]:
    agent = _agents.pop(investigation_id, None)
    _greeted.discard(investigation_id)
    if agent is not None:
        try:
            agent.close()
        except Exception:  # noqa: BLE001
            pass
    return {"dropped": agent is not None, "liveAgents": len(_agents)}


@app.on_event("shutdown")
def shutdown() -> None:
    for agent in _agents.values():
        try:
            agent.close()
        except Exception:  # noqa: BLE001
            pass
    _agents.clear()
    if _client is not None:
        try:
            _client.close()
        except Exception:  # noqa: BLE001
            pass
