package meko.agentic.goap.agent;

import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.AchievesGoal;
import com.embabel.agent.api.annotation.Agent;
import meko.agentic.goap.api.CaseModels.InvestigationComplete;
import meko.agentic.goap.api.CaseModels.PolicyAssessment;
import meko.agentic.goap.context.ContextEngine;
import meko.agentic.goap.context.ContextEngines;
import meko.agentic.goap.context.ContextPack;
import meko.agentic.goap.context.ContextSnapshot;
import meko.agentic.goap.model.ModelRoute;

import java.util.ArrayList;
import java.util.List;

@Agent(description = "Policy specialist that turns investigation evidence into an explicit case decision")
public class PolicyAgent {
    private final ContextEngines engines;

    public PolicyAgent(ContextEngines engines) { this.engines = engines; }

    @Action(description = "Retrieve the applicable payment-dispute policy and assess refund eligibility", cost = 0.16)
    @AchievesGoal(description = "Policy assessment is complete")
    public PolicyAssessment assess(InvestigationComplete investigation) {
        ContextEngine engine = engines.forMode(investigation.mekoEnabled());
        ContextSnapshot snapshot = engine.search(
                "policy-agent", investigation.caseId(),
                "card dispute failed capture successful authorization refund eligibility settlement timeout regulatory");

        List<String> policySources = snapshot.knowledge().isEmpty()
                ? List.of("fallback rule: use human review when institutional policy was not retrieved")
                : List.copyOf(snapshot.knowledge());

        boolean eligible = investigation.mekoEnabled() && !snapshot.knowledge().isEmpty();
        String policy = eligible
                ? "CARD-DISPUTE-07 / failed capture after authorization"
                : "baseline / policy retrieval incomplete";
        String rationale = eligible
                ? "Institutional policy retrieved: a failed capture after successful authorization is eligible for a provisional refund while settlement is verified."
                : "Institutional policy context was not available, so the agent escalates instead of auto-resolving.";

        List<String> decisions = new ArrayList<>(investigation.contextPack().decisions());
        decisions.add(eligible ? "policy supports provisional refund" : "policy context insufficient for automatic resolution");

        ContextPack pack = new ContextPack(
                investigation.contextPack().caseId(), investigation.contextPack().customerId(),
                investigation.contextPack().transactionId(), investigation.contextPack().complaint(),
                investigation.mekoEnabled(), investigation.contextPack().simulateTakeover(),
                investigation.contextPack().priorLearningReused(),
				Math.clamp(investigation.contextCompleteness(), snapshot.completeness() + 8, 100),
                investigation.contextPack().currentFacts(), investigation.contextPack().memories(),
                merge(investigation.contextPack().knowledge(), snapshot.knowledge()),
                investigation.contextPack().artifacts(), List.copyOf(decisions), snapshot.missingContext(),
                investigation.hypotheses(), eligible ? "approve provisional refund" : "human review",
                "policy-agent", snapshot.conversationId() == null ? investigation.contextPack().conversationId() : snapshot.conversationId(),
                policyRoute());

        return new PolicyAssessment(
                investigation.caseId(), policy, eligible, rationale, pack.completeness(), policySources,
                investigation.mekoEnabled(), pack, pack.modelRoute());
    }

    private ModelRoute policyRoute() {
        return new ModelRoute("LLM", "simulated-llm-compliance",
                "Policy interpretation uses a stronger reasoning profile because the action is compliance-sensitive.",
                0.22, "policy-agent");
    }

    private List<String> merge(List<String> first, List<String> second) {
        ArrayList<String> all = new ArrayList<>(first);
        for (String value : second) if (!all.contains(value)) all.add(value);
        return List.copyOf(all);
    }
}
