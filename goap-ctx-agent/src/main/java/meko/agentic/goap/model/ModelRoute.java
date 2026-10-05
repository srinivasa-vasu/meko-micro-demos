package meko.agentic.goap.model;

public record ModelRoute(
        String tier,
        String model,
        String rationale,
        double planningCost,
        String selectedBy) {
}
