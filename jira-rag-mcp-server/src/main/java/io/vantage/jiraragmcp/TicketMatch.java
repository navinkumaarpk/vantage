package io.vantage.jiraragmcp;

/**
 * Confidence tiers per the original design decision (recorded much earlier
 * in this project's planning): >=0.85 High, 0.70-0.85 Medium, <0.70
 * excluded entirely rather than returned as Low — a semantic match this
 * weak is more likely to mislead than help.
 */
public record TicketMatch(
        String ticketKey,
        String title,
        String symptomSummary,
        String rootCauseSummary,
        String resolutionSummary,
        double similarity,
        String confidence
) {
    public static String confidenceFor(double similarity) {
        if (similarity >= 0.85) return "High";
        if (similarity >= 0.70) return "Medium";
        return null; // caller should exclude
    }
}
