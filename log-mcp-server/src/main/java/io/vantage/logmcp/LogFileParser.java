package io.vantage.logmcp;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses OltMgr's real log format, confirmed against an actual uploaded
 * sample file (2026-07-27) — not guessed:
 *
 * <pre>
 * 2026-07-27 13:36:59,318 ERROR [ROLT Manager ONT Queue-5] PopulatorService (PopulatorService.java:698) Exception while...
 * java.lang.Exception: Error for request : ...
 *
 * 	at com.arris.pon.dci.server.xgs.util.secure.HttpPatchClientApache.sendGet(HttpPatchClientApache.java:189)
 * 	at ...
 * </pre>
 *
 * A new entry starts only when a line begins with the timestamp pattern.
 * Everything else — including blank lines and every stack frame — belongs
 * to the PREVIOUS entry and gets appended to its message. This is exactly
 * how multi-line exceptions get captured whole rather than shredded into
 * meaningless fragment documents, one per stack frame.
 */
public class LogFileParser {

    private static final Pattern ENTRY_START = Pattern.compile(
            "^(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2},\\d{3})\\s+(\\w+)\\s+\\[(.*?)\\]\\s+(\\S+)\\s+\\(([^:]+):(\\d+)\\)\\s(.*)$"
    );

    // Log timestamps carry no timezone. Assuming the indexing machine's
    // local zone as the best available default rather than silently
    // guessing UTC — flagged explicitly since if the real OltMgr servers
    // and this box run in different zones, timestamps will be off by
    // whatever that offset is. Revisit if that turns out to matter.
    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss,SSS");

    public List<UploadedLogEntry> parse(List<String> lines, String caseName, String jiraTicket, String sourceFileName) {
        List<UploadedLogEntry> entries = new ArrayList<>();
        UploadedLogEntry current = null;
        StringBuilder messageBuilder = null;

        for (String line : lines) {
            Matcher m = ENTRY_START.matcher(line);
            if (m.matches()) {
                if (current != null) {
                    current.message = messageBuilder.toString();
                    entries.add(current);
                }
                current = new UploadedLogEntry();
                current.timestamp = LocalDateTime.parse(m.group(1), TIMESTAMP_FORMAT)
                        .atZone(ZoneId.systemDefault()).toInstant().toString();
                current.level = m.group(2);
                current.thread = m.group(3);
                current.logger = m.group(4);
                current.sourceFile = m.group(5);
                current.sourceLine = Integer.parseInt(m.group(6));
                current.caseName = caseName;
                current.jiraTicket = jiraTicket;
                current.sourceFileName = sourceFileName;
                messageBuilder = new StringBuilder(m.group(7));
            } else if (current != null) {
                // Continuation line (stack frame, exception message, blank
                // line inside a trace) — belongs to the entry currently
                // being built.
                messageBuilder.append("\n").append(line);
            }
            // Lines before the first recognized entry are dropped rather
            // than crashing — shouldn't happen with a real OltMgr log, but
            // defensive against a truncated/corrupted upload.
        }
        if (current != null) {
            current.message = messageBuilder.toString();
            entries.add(current);
        }
        return entries;
    }
}
