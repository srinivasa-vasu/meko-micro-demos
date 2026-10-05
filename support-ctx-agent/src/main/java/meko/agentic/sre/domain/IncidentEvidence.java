package meko.agentic.sre.domain;

import java.util.List;

public record IncidentEvidence(
        IncidentRequest incident,
        List<String> metrics,
        List<String> logs,
        List<String> recentChanges,
        ReuseMode scope,
        /** Services the telemetry was narrowed to. Empty means "everything". */
        List<String> scopedTo,
        /** Log lines that existed but were not read because history narrowed the scope. */
        int logLinesSkipped,
        boolean mekoEnabled
) {
    public IncidentEvidence {
        metrics = metrics == null ? List.of() : List.copyOf(metrics);
        logs = logs == null ? List.of() : List.copyOf(logs);
        recentChanges = recentChanges == null ? List.of() : List.copyOf(recentChanges);
        scopedTo = scopedTo == null ? List.of() : List.copyOf(scopedTo);
    }

    public String asPromptBlock() {
        return "METRICS:\n" + String.join("\n", metrics)
                + "\n\nLOGS:\n" + String.join("\n", logs)
                + "\n\nRECENT CHANGES:\n" + String.join("\n", recentChanges);
    }
}
