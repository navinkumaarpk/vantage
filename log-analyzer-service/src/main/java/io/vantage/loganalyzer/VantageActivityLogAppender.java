package io.vantage.loganalyzer;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import io.vantage.agentcore.streaming.AgentActivityEvent;

/**
 * Bridges Spring AI's own internal DEBUG logging into per-investigation live
 * activity events -- the same pattern VISTA uses (a custom Logback appender
 * broadcasting log lines live), but scoped per-investigation via MDC rather
 * than VISTA's single global broadcast-to-everyone stream, since our
 * architecture already has a per-investigation activity publisher.
 *
 * <p>Confirmed by direct verification (2026-08-03), not guessed: Spring AI's
 * {@code org.springframework.ai.model.tool.DefaultToolCallingManager} logs
 * {@code "Executing tool call: <name>"} at DEBUG for each individual tool
 * invocation, on the SAME thread handling the HTTP request -- confirmed by
 * observing the DEBUG line and the servlet worker thread name match in a
 * real log. This is what makes MDC-based correlation work: Spring AI's
 * tool-calling loop runs synchronously on the calling thread for our
 * blocking {@code .call()} usage, so an MDC value set in ChatController
 * before the call is still present when this appender receives these
 * events.
 *
 * <p><strong>Known fragility, inherent to this whole approach (VISTA has the
 * same tradeoff):</strong> this parses a literal internal log MESSAGE
 * STRING, not a stable public API. If a future Spring AI version rewords
 * this message, matching silently stops working -- there is no structured
 * event API for this today, which is the entire reason this approach exists.
 * Also: we only have confirmed visibility into the MOMENT Spring AI decides
 * to invoke a tool, not a separate "tool call finished" signal, so each
 * observed line is published as one discrete event, not a started/completed
 * pair the way the Cursor path's real SDK events are.
 */
public class VantageActivityLogAppender extends AppenderBase<ILoggingEvent> {

    private static final String TOOL_CALL_PREFIX = "Executing tool call: ";
    private static volatile InvestigationStore investigationStore;

    public static void setInvestigationStore(InvestigationStore store) {
        investigationStore = store;
    }

    @Override
    protected void append(ILoggingEvent event) {
        InvestigationStore store = investigationStore;
        if (store == null) {
            return;
        }
        String investigationId = event.getMDCPropertyMap().get("investigationId");
        if (investigationId == null) {
            return; // log line from a request we didn't tag, or a background thread
        }
        String message = event.getFormattedMessage();
        if (message == null || !message.startsWith(TOOL_CALL_PREFIX)) {
            return;
        }
        String toolName = message.substring(TOOL_CALL_PREFIX.length()).trim();

        store.get(investigationId).ifPresent(investigation ->
                investigation.activity.publish(AgentActivityEvent.succeeded(
                        investigationId, "local", "tool_call", toolName)));
    }
}
