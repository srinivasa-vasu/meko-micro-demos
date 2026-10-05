package meko.agentic.sre.domain;

/**
 * One service's experience with one model tier.
 *
 * Two shapes collapse into this record on purpose. The agent writes ONE observation per
 * incident ({@code attempts=1}); the seeded corpus carries pre-aggregated rows
 * ({@code attempts=17; escalations=1; successRate=0.94}). Weighting by {@code attempts}
 * lets a seeded datapack produce a credible policy on the first run instead of needing
 * seventeen live incidents before the demo has anything to show.
 */
public record RoutingObservation(
        /** The incident this outcome came from, when the row records one. Used to deduplicate. */
        String incidentId,
        String service,
        ModelTier tier,
        int attempts,
        int escalations,
        int successes,
        double meanUsd,
        double meanConfidence
) {
    public RoutingObservation {
        attempts = Math.max(1, attempts);
        escalations = Math.clamp(escalations, 0, attempts);
        successes = Math.clamp(successes, 0, attempts);
    }

    public double successRate() {
        return (double) successes / attempts;
    }

    public double escalationRate() {
        return (double) escalations / attempts;
    }

    /** Merge two observations of the same service+tier, weighting by attempts. */
    public RoutingObservation merge(RoutingObservation other) {
        int total = attempts + other.attempts;
        return new RoutingObservation(
                incidentId,
                service,
                tier,
                total,
                escalations + other.escalations,
                successes + other.successes,
                weighted(meanUsd, other.meanUsd, other.attempts, total),
                weighted(meanConfidence, other.meanConfidence, other.attempts, total)
        );
    }

    private double weighted(double mine, double theirs, int theirAttempts, int total) {
        return (mine * attempts + theirs * theirAttempts) / total;
    }
}
