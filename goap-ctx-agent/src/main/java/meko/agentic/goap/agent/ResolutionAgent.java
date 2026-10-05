package meko.agentic.goap.agent;

import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.AchievesGoal;
import com.embabel.agent.api.annotation.Agent;
import meko.agentic.goap.api.CaseModels.PolicyAssessment;
import meko.agentic.goap.api.CaseModels.ResolutionOutput;
import meko.agentic.goap.context.ContextEngine;
import meko.agentic.goap.context.ContextEngines;
import meko.agentic.goap.context.ContextPack;
import meko.agentic.goap.model.ModelRoute;

import java.util.List;

@Agent(description = "Resolution specialist that closes the dispute and records the learning")
public class ResolutionAgent {
    private final ContextEngines engines;

    public ResolutionAgent(ContextEngines engines) {
        this.engines = engines;
    }

    @Action(description = "Resolve the case and persist a reusable learning", cost = 0.07)
    @AchievesGoal(description = "Resolution decision is complete")
    public ResolutionOutput resolve(PolicyAssessment policy) {
        ContextEngine engine = engines.forMode(policy.mekoEnabled());
        String learning = "Reusable learning: a gateway capture timeout after successful authorization should follow the failed-capture settlement/refund path.";
        if (policy.refundEligible()) engine.remember("resolution-agent", policy.caseId(), learning);

        ContextPack pack = policy.contextPack();
        String contextArtifact = engine.persistContextPack("resolution-agent", policy.caseId(), pack);
        java.util.ArrayList<String> finalArtifacts = new java.util.ArrayList<>(pack.artifacts());
        if (contextArtifact != null && !contextArtifact.isBlank() && !finalArtifacts.contains("artifact:" + contextArtifact)) {
            finalArtifacts.add("artifact:" + contextArtifact);
        }
        pack = new ContextPack(
                pack.caseId(), pack.customerId(), pack.transactionId(), pack.complaint(),
                pack.mekoEnabled(), pack.simulateTakeover(), pack.priorLearningReused(), pack.completeness(),
                pack.currentFacts(), pack.memories(), pack.knowledge(), List.copyOf(finalArtifacts),
                pack.decisions(), pack.missingContext(), pack.hypotheses(), pack.nextAction(),
                "resolution-agent", pack.conversationId(), null);

        String outcome = policy.refundEligible()
                ? "Provisional refund approved; monitor settlement reconciliation and notify the customer."
                : "Escalate to a human payments specialist because institutional policy context was insufficient.";
        return new ResolutionOutput(
                policy.caseId(), outcome,
                policy.refundEligible() ? List.of(learning) : List.of(),
                pack,
                new ModelRoute("LLM", "simulated-llm-resolution",
                        "Resolution is customer-impacting and uses a stronger model profile after policy interpretation.",
                        0.18, "resolution-agent"));
    }
}
