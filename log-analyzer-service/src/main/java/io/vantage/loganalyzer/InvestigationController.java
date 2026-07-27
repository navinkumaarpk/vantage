package io.vantage.loganalyzer;

import io.vantage.agentcore.streaming.AgentActivityEvent;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

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
        Investigation inv = store.get(id).orElseThrow(NoSuchElementException::new);
        return Map.of(
                "id", inv.id,
                "title", inv.title,
                "createdAt", inv.createdAt.toString(),
                "turns", inv.turns
        );
    }

    @GetMapping(value = "/api/investigations/{id}/activity", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<AgentActivityEvent> activity(@PathVariable String id) {
        Investigation inv = store.get(id).orElseThrow(NoSuchElementException::new);
        return inv.activity.stream();
    }
}
