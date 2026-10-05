package meko.agentic.sre.service;

import meko.agentic.sre.config.TokenProperties;
import meko.agentic.sre.domain.ModelTier;
import meko.agentic.sre.domain.RoutingPolicy;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The routing derivation decides which model gets paid, so it is worth pinning down.
 * Everything here is pure: text in, policy out, no Meko and no model.
 */
class RoutingPolicyServiceTest {

    private static final TokenProperties PROPS = new TokenProperties(
            Map.of(), Map.of(),
            new TokenProperties.Reuse(0.85, 0.55),
            new TokenProperties.Routing(5, 0.85, false),
            new TokenProperties.Projection(2_000));

    private final MemoryRecordParser parser = new MemoryRecordParser();
    private final RoutingPolicyService service = new RoutingPolicyService(parser, null, PROPS);

    private RoutingPolicy resolve(String knowledge, String outcomes) {
        return service.resolve("checkout", knowledge, outcomes, ModelTier.LLM, 0.75);
    }

    @Test
    void promotedKnowledgeOutranksEverythingElse() {
        var knowledge = "ROUTING_POLICY; service=checkout; preferredTier=FRONTIER; "
                + "confidenceThreshold=0.90; rationale=Curated by the SRE leads.";
        // Outcome history that would otherwise derive SLM.
        var outcomes = "ROUTING_OUTCOME; service=checkout; modelTier=SLM; attempts=40; "
                + "escalations=1; successRate=0.98; meanUsd=0.001";

        var policy = resolve(knowledge, outcomes);

        assertEquals(RoutingPolicy.Source.PROMOTED_KNOWLEDGE, policy.source());
        assertEquals(ModelTier.FRONTIER, policy.preferredTier());
        assertEquals(0.90, policy.confidenceThreshold(), 1e-9);
    }

    @Test
    void derivesCheapestTierThatClearsTheBar() {
        // SLM qualifies and is cheapest, so it wins even though FRONTIER scores higher.
        var outcomes = String.join("\n",
                "ROUTING_OUTCOME; service=checkout; modelTier=SLM; attempts=17; escalations=1; successRate=0.94; meanUsd=0.00121",
                "ROUTING_OUTCOME; service=checkout; modelTier=FRONTIER; attempts=9; escalations=0; successRate=1.00; meanUsd=0.04");

        var policy = resolve("", outcomes);

        assertEquals(RoutingPolicy.Source.DERIVED_FROM_OUTCOMES, policy.source());
        assertEquals(ModelTier.SLM, policy.preferredTier());
        assertEquals(17, policy.observations());
    }

    @Test
    void stepsUpWhenTheCheapestTierKeepsEscalating() {
        // 40% success is well under the 0.85 bar: routing should start one tier higher.
        var outcomes = "ROUTING_OUTCOME; service=checkout; modelTier=SLM; attempts=10; "
                + "escalations=6; successRate=0.40; meanUsd=0.001";

        var policy = resolve("", outcomes);

        assertEquals(RoutingPolicy.Source.DERIVED_FROM_OUTCOMES, policy.source());
        assertEquals(ModelTier.LLM, policy.preferredTier());
        assertTrue(policy.rationale().contains("below"), policy.rationale());
    }

    @Test
    void perIncidentRowsAggregateIntoTheSameDecision() {
        // What the agent actually writes: one row per incident, no pre-aggregation.
        var rows = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            rows.append("ROUTING_OUTCOME; id=INC-%d; service=checkout; modelTier=SLM; escalated=false; "
                    .formatted(i))
                    .append("usd=0.0012; confidence=0.91; validated=true\n");
        }

        var policy = resolve("", rows.toString());

        assertEquals(ModelTier.SLM, policy.preferredTier());
        assertEquals(6, policy.observations());
        assertEquals(1.0, policy.successRate(), 1e-9);
    }

    @Test
    void anEscalatedOrInvalidRunCountsAgainstTheTier() {
        var outcomes = String.join("\n",
                "ROUTING_OUTCOME; id=A; service=checkout; modelTier=SLM; escalated=true; validated=true",
                "ROUTING_OUTCOME; id=B; service=checkout; modelTier=SLM; escalated=false; validated=false",
                "ROUTING_OUTCOME; id=C; service=checkout; modelTier=SLM; escalated=false; validated=true");

        var observations = service.observations("checkout", outcomes);

        assertEquals(1, observations.size());
        assertEquals(3, observations.getFirst().attempts());
        assertEquals(1, observations.getFirst().successes());
    }

    @Test
    void anotherServicesHistoryIsIgnored() {
        var outcomes = "ROUTING_OUTCOME; service=billing; modelTier=SLM; attempts=40; "
                + "escalations=0; successRate=1.00; meanUsd=0.001";

        var policy = resolve("", outcomes);

        assertEquals(RoutingPolicy.Source.CONFIG_DEFAULT, policy.source());
        assertEquals(ModelTier.LLM, policy.preferredTier());
    }

    @Test
    void shortCircuitRunsSaySoNothingAboutTierSufficiency() {
        // modelTier=NONE means no model ran. Counting it as a "success" for the cheapest tier
        // would let cache hits argue for downgrading the model.
        var outcomes = "ROUTING_OUTCOME; id=A; service=checkout; modelTier=NONE; escalated=false; validated=true";

        assertTrue(service.observations("checkout", outcomes).isEmpty());
        assertEquals(RoutingPolicy.Source.CONFIG_DEFAULT, resolve("", outcomes).source());
    }

    @Test
    void thinEvidenceIsNotEnoughToChangeRouting() {
        // Two observations, below min-observations=5. Should not qualify SLM outright...
        var outcomes = "ROUTING_OUTCOME; service=checkout; modelTier=SLM; attempts=2; "
                + "escalations=0; successRate=1.00; meanUsd=0.001";

        var policy = resolve("", outcomes);

        // ...but it is still evidence, so we start one step up rather than trusting n=2.
        assertEquals(ModelTier.LLM, policy.preferredTier());
    }

    @Test
    void thinEvidenceSaysSoRatherThanBlamingTheSuccessRate() {
        // One observation at 100%. The rate is not the problem and the rationale must not claim
        // it is — "cleared only 100% ... below the 85% bar" was being printed verbatim.
        var outcomes = "ROUTING_OUTCOME; id=A; service=checkout; modelTier=SLM; "
                + "escalated=false; validated=true; usd=0.001";

        var rationale = resolve("", outcomes).rationale();

        assertTrue(rationale.contains("fewer than the 5 needed"), rationale);
        assertFalse(rationale.contains("85% bar"),
                "a sample-size problem must not be reported as a success-rate problem: " + rationale);
    }

    @Test
    void aGenuinelyPoorRateStillBlamesTheRate() {
        var outcomes = "ROUTING_OUTCOME; service=checkout; modelTier=SLM; attempts=10; "
                + "escalations=6; successRate=0.40; meanUsd=0.001";

        var rationale = resolve("", outcomes).rationale();

        assertTrue(rationale.contains("85% bar"), rationale);
        assertFalse(rationale.contains("fewer than"), rationale);
    }
}
