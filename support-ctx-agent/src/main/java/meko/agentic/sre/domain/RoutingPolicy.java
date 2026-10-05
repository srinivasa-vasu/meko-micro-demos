package meko.agentic.sre.domain;

/**
 * Which model tier to try first for a service, and how confident it has to be before the
 * answer is accepted.
 *
 * {@link Source} is the field that matters on stage. A policy read from promoted knowledge
 * is organizational: somebody curated it and every agent on the datapack sees the same one.
 * A DERIVED policy is this agent's own running estimate, not yet shared. A DEFAULT policy
 * means Meko had nothing to say and the number came from application.yml. Showing which of
 * the three produced the routing decision is the difference between "the system learned
 * this" and "trust me".
 */
public record RoutingPolicy(
        String service,
        ModelTier preferredTier,
        double confidenceThreshold,
        String rationale,
        Source source,
        int observations,
        double successRate,
        double meanUsd
) {
    public enum Source {
        /** Curated, shared, read from the datapack knowledge base. Authoritative. */
        PROMOTED_KNOWLEDGE,
        /** Computed this run from ROUTING_OUTCOME rows. Private to this agent until promoted. */
        DERIVED_FROM_OUTCOMES,
        /** Meko had nothing. This is application.yml talking. */
        CONFIG_DEFAULT
    }

    public static RoutingPolicy configDefault(String service, ModelTier tier, double threshold) {
        return new RoutingPolicy(
                service,
                tier,
                threshold,
                "No routing policy and no usable outcome history in Meko; falling back to the "
                        + "configured default tier and escalating on confidence.",
                Source.CONFIG_DEFAULT,
                0,
                0.0,
                0.0);
    }

    public boolean isShared() {
        return source == Source.PROMOTED_KNOWLEDGE;
    }

    /** The line written to Meko when this policy is good enough to share. */
    public String asRecord() {
        return ("ROUTING_POLICY; service=%s; preferredTier=%s; confidenceThreshold=%.2f; "
                + "observations=%d; successRate=%.2f; meanUsd=%.5f; rationale=%s")
                .formatted(service, preferredTier, confidenceThreshold,
                        observations, successRate, meanUsd, rationale);
    }
}
