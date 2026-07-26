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
 */
public class AgentActivityPublisher {

    private final Sinks.Many<AgentActivityEvent> sink = Sinks.many().multicast().onBackpressureBuffer();

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
