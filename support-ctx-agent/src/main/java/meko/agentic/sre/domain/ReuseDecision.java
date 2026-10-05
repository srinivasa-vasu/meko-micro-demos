package meko.agentic.sre.domain;

import org.jspecify.annotations.Nullable;

/**
 * Output of the deterministic gate. Costs nothing to produce and decides whether we are
 * about to spend 200k input tokens or 4k.
 * <p>
 * The three booleans are real record components rather than derived helper methods so that
 * the planner's SpEL preconditions read them through exactly the same accessor shape as the
 * other record components already used in this agent.
 */
public record ReuseDecision(
        ReuseMode mode,
        boolean shortCircuit,
        boolean scoped,
        boolean full,
        double matchScore,
        boolean stale,
        String stalenessReason,
        @Nullable StoredIncident match,
        int selfMatchesIgnored,
        String explanation
) {
    public static ReuseDecision of(
            ReuseMode mode,
            double matchScore,
            boolean stale,
            String stalenessReason,
            @Nullable StoredIncident match,
            String explanation) {
        return of(mode, matchScore, stale, stalenessReason, match, 0, explanation);
    }

    public static ReuseDecision of(
            ReuseMode mode,
            double matchScore,
            boolean stale,
            String stalenessReason,
            @Nullable StoredIncident match,
            int selfMatchesIgnored,
            String explanation) {
        return new ReuseDecision(
                mode,
                mode == ReuseMode.SHORT_CIRCUIT,
                mode == ReuseMode.SCOPED,
                mode == ReuseMode.FULL,
                matchScore,
                stale,
                stalenessReason == null ? "" : stalenessReason,
                match,
                selfMatchesIgnored,
                explanation
        );
    }

    public static ReuseDecision none(String why) {
        return of(ReuseMode.FULL, 0.0, false, "", null, 0, why);
    }

    public static ReuseDecision none(int selfMatchesIgnored, String why) {
        return of(ReuseMode.FULL, 0.0, false, "", null, selfMatchesIgnored, why);
    }
}
