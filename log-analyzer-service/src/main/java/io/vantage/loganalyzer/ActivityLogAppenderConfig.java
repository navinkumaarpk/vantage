package io.vantage.loganalyzer;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import jakarta.annotation.PostConstruct;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;

/**
 * Wires {@link VantageActivityLogAppender} into Logback at startup, matching
 * VISTA's {@code LogStreamConfig} pattern. Attached specifically to
 * {@code org.springframework.ai.model.tool} -- the confirmed namespace from
 * direct verification, not {@code org.springframework.ai} broadly, which
 * would produce far more log volume than this feature needs.
 *
 * <p>Sets the logger level programmatically rather than relying solely on
 * application.yml, so this feature is self-contained: it doesn't silently
 * stop working if someone edits logging config elsewhere without knowing
 * this depends on it.
 */
@Configuration
public class ActivityLogAppenderConfig {

    private final InvestigationStore investigationStore;

    public ActivityLogAppenderConfig(InvestigationStore investigationStore) {
        this.investigationStore = investigationStore;
    }

    @PostConstruct
    public void attach() {
        VantageActivityLogAppender.setInvestigationStore(investigationStore);

        LoggerContext ctx = (LoggerContext) LoggerFactory.getILoggerFactory();
        VantageActivityLogAppender appender = new VantageActivityLogAppender();
        appender.setContext(ctx);
        appender.start();

        Logger toolLogger = ctx.getLogger("org.springframework.ai.model.tool");
        toolLogger.setLevel(Level.DEBUG);
        toolLogger.addAppender(appender);
    }
}
