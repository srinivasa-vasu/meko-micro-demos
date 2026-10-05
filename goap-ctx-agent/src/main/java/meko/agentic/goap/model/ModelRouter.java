package meko.agentic.goap.model;

import meko.agentic.goap.context.ContextPack;
import org.springframework.stereotype.Component;

@Component
public class ModelRouter {

    public double planningCost(String tier, ContextPack pack) {
        if (pack == null) return 0.6;

        boolean rich = pack.completeness() >= 75 && pack.missingContext().size() <= 1;
        boolean priorLearning = !pack.memories().isEmpty();
        boolean ambiguity = pack.hypotheses().size() > 1 || !pack.missingContext().isEmpty();
        boolean highImpact = pack.knowledge().stream().anyMatch(k ->
                k.toLowerCase().contains("fraud") || k.toLowerCase().contains("regulatory"));

        return switch (tier) {
            case "SLM" -> {
                // Small/cheap models are attractive only when the context pack is rich enough.
                double c = rich ? 0.08 : 0.68;
                if (!pack.mekoEnabled()) c += 0.08;
                yield clamp(c);
            }
            case "LLM" -> {
                // General reasoning is the middle path: useful when ambiguity exists or
                // when the context is not rich enough for an SLM.
                double c = rich && !ambiguity ? 0.34 : 0.18;
                if (priorLearning) c -= 0.03;
                yield clamp(c);
            }
            case "FRONTIER" -> {
                // Reserve the most expensive route for ambiguous/high-impact cases.
                double c = (ambiguity || highImpact) ? 0.22 : 0.62;
                if (!pack.mekoEnabled()) c += 0.10;
                yield clamp(c);
            }
            default -> 0.9;
        };
    }

    public ModelRoute route(String tier, ContextPack pack, String selectedBy) {
        double cost = planningCost(tier, pack);
        String rationale = switch (tier) {
            case "SLM" -> pack.mekoEnabled()
                    ? "Rich Meko context makes a cheaper model viable for routine evidence synthesis."
                    : "Baseline has insufficient durable context; this route is penalized by GOAP cost. ";
            case "LLM" -> "Balanced reasoning route for incomplete or moderately ambiguous investigation context.";
            case "FRONTIER" -> "Higher-cost route reserved for ambiguity, higher-impact policy signals, or context gaps.";
            default -> "Fallback route.";
        };
        String model = switch (tier) {
            case "SLM" -> "simulated-slm-fast";
            case "LLM" -> "simulated-llm-reasoning";
            case "FRONTIER" -> "simulated-frontier-deep";
            default -> "simulated-llm-reasoning";
        };
        return new ModelRoute(tier, model, rationale.trim(), cost, selectedBy);
    }

    private double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }
}
