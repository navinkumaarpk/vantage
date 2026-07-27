package io.vantage.agentcore.streaming;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/**
 * Publishes {@link AgentActivityEvent}s and exposes them as a {@link Flux},
 * intended to back an SSE endpoint that streams live progress to the
 * frontend's activity feed panel (per both product wireframes). One instance
 * per running investigation — services are responsible for creating and
 * disposing these per-investigation, not sharing a single global instance
 * across concurrent investigations.
 *
 * <p><strong>Changed 2026-07-26:</strong> was a multicast sink, which only
 * delivers events to subscribers already connected at emit time — a client
 * that opens the SSE connection even slightly late (page load, switching to
 * this investigation in a sidebar, a reconnect after a network blip) would
 * silently miss everything before it subscribed. Replay-with-limit fixes
 * this: a new subscriber gets the last 50 events immediately, then live
 * events as they happen.
 */
public class AgentActivityPublisher {

    private final Sinks.Many<AgentActivityEvent> sink = Sinks.many().replay().limit(50);

    public void publish(AgentActivityEvent event) {
        sink.tryEmitNext(event);
    }

    public Flux<AgentActivityEvent> stream() {
        return sink.asFlux();
    }

    public void complete() {
        sink.tryEmitComplete();
    }
}
