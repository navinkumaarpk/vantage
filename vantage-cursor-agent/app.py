"""
Vantage <-> Cursor SDK sidecar.

Exposes a Cursor agent over SSE so log-analyzer-service (Java) gets real live
progress instead of one response after the whole run finishes.

=============================================================================
Changed 2026-07-30: was drain-then-respond (call run.messages() to exhaustion,
throw away the incremental structure, return one JSON blob at the end). That
directly caused two separate real problems: a 38s ReadTimeoutException on
Java's client for a real multi-tool-call investigation (holding one
synchronous connection open across Server3->Python->Cursor's infra->multiple
tool round trips has no good timeout value), and no live visibility at all --
confirmed by a user expecting to see "calling X tool" appear as it happens,
the way Claude's own interface shows it, and instead getting nothing until
the whole run either finished or timed out.

Now emits real SSE as run.messages() yields them. Java (WebClient,
bodyToFlux(ServerSentEvent.class) -- a standard, well-documented Spring
pattern) publishes each event into the investigation's activity feed as it
arrives, live, rather than at the end. This also removes the need for one
long blocking request/response pair on the Python<->Java hop; the eventual
POST /api/chat to the browser still returns once, at the end, but the
SEPARATE activity SSE connection the browser already holds now shows real
progress DURING that wait instead of nothing.

Message field shapes below (message.type, SDKToolUseMessage.name/status/
call_id, SDKAssistantMessage.message.content with TextBlock) are confirmed
directly from the Python SDK reference docs, not guessed.

Known follow-up, deliberately not built here: inline tool-call blocks WITHIN
the chat transcript itself (matching Claude's own UI). This gives real live
sidebar/activity-feed updates; rendering the same events inline in the
message stream is a separate frontend redesign.
=============================================================================
"""

import json
import logging
import os
from typing import Any, Dict, Optional

from fastapi import FastAPI
from fastapi.responses import StreamingResponse
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
thread. On an unfamiliar log set, call summarize_logs first.

Never guess a source file's full path. A log entry's source_file field is only
ever a bare class name, which is not enough to locate the file -- call
find_definition or search_by_symptom with the class name first to discover its
real path, then pass that to get_source_context.

If a tool call fails, say plainly that the capability is unavailable. Do not ask
the user for more detail -- more detail cannot fix a broken connection.
"""


class ChatRequest(BaseModel):
    message: str
    investigationId: str


def _get_client() -> Any:
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


def _sse(payload: Dict[str, Any]) -> str:
    return f"data: {json.dumps(payload)}\n\n"


def _event_stream(req: ChatRequest):
    """
    Generator yielding real SSE frames as the run progresses. Kept as a plain
    generator (not async) to match the sync SDK surface used throughout this
    sidecar; FastAPI runs sync generators for StreamingResponse in a worker
    thread, which is fine for one agent run at a time.
    """
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

        if req.investigationId in _greeted:
            prompt = req.message
        else:
            prompt = GUIDANCE + "\n\n---\n\n" + req.message
            _greeted.add(req.investigationId)

        run = agent.send(prompt)

        for message in run.messages():
            kind = getattr(message, "type", None)
            if kind == "tool_call":
                raw_name = getattr(message, "name", "?")
                args = getattr(message, "args", None) or {}

                # Bug found and fixed (2026-08-03): observed real activity feed
                # entries all showing the generic "mcp" wrapper name instead of
                # the actual tool (search_logs, get_log_context, ...) -- Cursor
                # appears to route every MCP-server tool call through a single
                # dispatcher-named tool, with the real target encoded inside
                # args. The exact nested shape isn't documented ("Tool call
                # payload schemas are intentionally not strongly typed"), so
                # this tries the most plausible keys and logs the raw shape at
                # INFO the first few times either way -- if the guess is wrong,
                # the real key name will be visible directly in this log rather
                # than needing another round of trial and error.
                resolved_name = raw_name
                if raw_name == "mcp" and isinstance(args, dict):
                    resolved_name = (
                        args.get("tool") or args.get("toolName") or args.get("tool_name")
                        or args.get("name") or raw_name
                    )
                    if resolved_name == raw_name:
                        log.info("Unresolved 'mcp' tool_call, raw args shape: %s", args)

                yield _sse({
                    "event": "tool_call",
                    "name": resolved_name,
                    "rawName": raw_name,
                    "status": getattr(message, "status", "?"),
                    "callId": getattr(message, "call_id", None),
                })
            elif kind == "assistant":
                for block in getattr(message.message, "content", []) or []:
                    if getattr(block, "type", None) == "text":
                        text = getattr(block, "text", "") or ""
                        if text:
                            yield _sse({"event": "text_delta", "text": text})

        result = run.wait()
        usage = getattr(result, "usage", None)
        final_text = (getattr(result, "result", None) or "").strip()
        status = getattr(result, "status", None)

        # Bug found and fixed (2026-07-30): a run that fails on Cursor's side
        # (rate limit, quota, internal error) does not necessarily raise a
        # CursorAgentError -- per the SDK docs, run.wait() can resolve
        # normally with status in {"error","cancelled","expired"}. This code
        # previously only read result.result/status and always yielded a
        # "done" event, so a silently-failed run produced a "done" event with
        # blank text -- which Java then reported as an unhelpful generic
        # "empty response" with no indication of what actually went wrong.
        # Checking status explicitly surfaces the real reason instead.
        if status != "finished" or not final_text:
            log.warning(
                "Cursor run for investigation %s ended without usable output: status=%s, text_len=%d",
                req.investigationId, status, len(final_text),
            )
            yield _sse({
                "event": "error",
                "error": f"Cursor run ended with status={status!r} and "
                         f"{'no' if not final_text else 'some'} text. This usually means a rate limit, "
                         f"quota, or an internal Cursor-side error rather than a Vantage bug -- check "
                         f"this sidecar's own logs and the Cursor dashboard's usage page.",
            })
        else:
            yield _sse({
                "event": "done",
                "text": final_text,
                "status": status,
                "totalTokens": getattr(usage, "total_tokens", None) if usage else None,
            })

    except CursorAgentError as exc:
        log.exception("Cursor run failed for investigation %s", req.investigationId)
        yield _sse({
            "event": "error",
            "error": f"{type(exc).__name__}: {exc}",
            "requestId": getattr(exc, "request_id", None),
        })
    except Exception as exc:  # noqa: BLE001
        log.exception("Unexpected sidecar failure for investigation %s", req.investigationId)
        yield _sse({"event": "error", "error": f"{type(exc).__name__}: {exc}"})


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


@app.post("/agent/chat")
def chat(req: ChatRequest) -> StreamingResponse:
    if Agent is None:
        return StreamingResponse(
            iter([_sse({"event": "error", "error": f"cursor-sdk not installed: {SDK_IMPORT_ERROR}"})]),
            media_type="text/event-stream",
        )
    if not CURSOR_API_KEY:
        return StreamingResponse(
            iter([_sse({"event": "error", "error": "CURSOR_API_KEY is not set on the sidecar"})]),
            media_type="text/event-stream",
        )
    return StreamingResponse(_event_stream(req), media_type="text/event-stream")


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
