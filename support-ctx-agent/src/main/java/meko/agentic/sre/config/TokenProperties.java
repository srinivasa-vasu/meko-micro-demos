package meko.agentic.sre.config;

import meko.agentic.sre.domain.IncidentSeverity;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

/**
 * Real per-token pricing and severity budgets. Costs are reported in dollars rather than
 * arbitrary planner units so the numbers on screen survive being questioned.
 */
@ConfigurationProperties(prefix = "token")
public record TokenProperties(
        Map<String, Price> pricing,
        Map<IncidentSeverity, Double> budgetUsd,
        Reuse reuse,
        Routing routing,
        Projection projection
) {
    /** USD per 1,000,000 tokens. */
    public record Price(double inputPerMillion, double outputPerMillion) {
        public double cost(long inputTokens, long outputTokens) {
            return (inputTokens / 1_000_000.0) * inputPerMillion
                    + (outputTokens / 1_000_000.0) * outputPerMillion;
        }
    }

    public record Reuse(double shortCircuitThreshold, double scopedThreshold) {}

    /**
     * When this agent's own outcome history is allowed to become a shared routing policy.
     * <p>
     * {@code promote} is one-way: promotion evicts the row from this agent's private store
     * and moves it into the datapack knowledge base for every agent to read. It also needs
     * owner or maintainer rights on the datapack.
     */
    public record Routing(int minObservations, double targetSuccessRate, boolean promote) {}

    /** The assumption behind any "annual saving" figure, stated rather than buried. */
    public record Projection(int incidentsPerMonth) {}

    public TokenProperties {
        pricing = pricing == null ? Map.of() : Map.copyOf(pricing);
        budgetUsd = budgetUsd == null ? Map.of() : Map.copyOf(budgetUsd);
        reuse = reuse == null ? new Reuse(0.85, 0.55) : reuse;
        routing = routing == null ? new Routing(5, 0.85, false) : routing;
        projection = projection == null ? new Projection(2_000) : projection;
    }

    public Price priceFor(String role) {
        return pricing.getOrDefault(role, new Price(0.30, 2.50));
    }

    public double budgetFor(IncidentSeverity severity) {
        return budgetUsd.getOrDefault(severity, 0.15);
    }
}
