package io.vantage.loganalyzer;

import io.vantage.agentcore.streaming.AgentActivityEvent;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;

/**
 * <strong>Bug found and fixed (2026-07-27):</strong> get() and activity()
 * previously used .orElseThrow(NoSuchElementException::new), which produces
 * an unhandled exception -> raw 500 with a full stack trace for an entirely
 * expected case: investigations are in-memory only, so any server restart
 * invalidates every existing ID. A browser tab left open from before a
 * restart still has a live EventSource pointed at a now-nonexistent
 * investigation, and browsers auto-retry EventSource connections by
 * default -- so a stale tab silently hammers the activity endpoint forever,
 * producing a 500 (and a full stack trace in the logs) on every retry.
 * Now returns a proper 404, which is both the correct HTTP semantics and
 * much quieter in the logs.
 */
@RestController
public class InvestigationController {

    private final InvestigationStore store;

    public InvestigationController(InvestigationStore store) {
        this.store = store;
    }

    @GetMapping("/api/investigations")
    public List<Map<String, Object>> list() {
        return store.listAll().stream()
                .map(inv -> Map.<String, Object>of(
                        "id", inv.id,
                        "title", inv.title,
                        "createdAt", inv.createdAt.toString(),
                        "lastActivityAt", inv.lastActivityAt.toString(),
                        "turnCount", inv.turns.size()
                ))
                .toList();
    }

    @GetMapping("/api/investigations/{id}")
    public Map<String, Object> get(@PathVariable String id) {
        Investigation inv = store.get(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No investigation with id " + id));
        return Map.of(
                "id", inv.id,
                "title", inv.title,
                "createdAt", inv.createdAt.toString(),
                "turns", inv.turns
        );
    }

    @GetMapping(value = "/api/investigations/{id}/activity", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<AgentActivityEvent> activity(@PathVariable String id) {
        Investigation inv = store.get(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No investigation with id " + id));
        return inv.activity.stream();
    }
}
