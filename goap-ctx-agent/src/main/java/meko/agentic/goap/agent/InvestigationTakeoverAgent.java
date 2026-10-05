package meko.agentic.goap.agent;

import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.AchievesGoal;
import com.embabel.agent.api.annotation.Agent;
import meko.agentic.goap.api.CaseModels.InvestigationComplete;
import meko.agentic.goap.api.CaseModels.InvestigationNeedsTakeover;
import meko.agentic.goap.context.ContextEngine;
import meko.agentic.goap.context.ContextEngines;
import meko.agentic.goap.context.ContextPack;
import meko.agentic.goap.context.ContextSnapshot;
import meko.agentic.goap.model.ModelRoute;
import meko.agentic.goap.model.ModelRouter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Agent(description = "Replacement investigator that takes over an incomplete case from persisted context")
public class InvestigationTakeoverAgent {
    private final ContextEngines engines;
    private final ModelRouter modelRouter;

    public InvestigationTakeoverAgent(ContextEngines engines, ModelRouter modelRouter) {
        this.engines = engines;
        this.modelRouter = modelRouter;
    }

    @Action(description = "Take over an interrupted investigation and resume from its checkpoint", cost = 0.18)
    @AchievesGoal(description = "Investigation is resumed and complete")
    public InvestigationComplete takeover(InvestigationNeedsTakeover input) {
        ContextEngine engine = engines.forMode(input.mekoEnabled());
        Map<String, Object> checkpoint = engine.restoreCheckpoint(
                "investigation-takeover-agent", input.caseId(), input.checkpointHash());
        ContextSnapshot snapshot = engine.search(
                "investigation-takeover-agent", input.caseId(),
                "resume settlement state payment evidence rejected hypotheses prior decision");

        boolean resumed = input.mekoEnabled() && !checkpoint.isEmpty();
        int recoveredCompleteness = resumed
                ? Math.clamp(input.contextCompleteness(), snapshot.completeness() + 6, 100)
                : Math.max(42, input.contextCompleteness() - 8);

        List<String> artifacts = new ArrayList<>(input.contextPack().artifacts());
        if (resumed) artifacts.add("artifact:" + input.checkpointHash());

        List<String> decisions = new ArrayList<>(input.contextPack().decisions());
        decisions.add(resumed
                ? "takeover recovered the unfinished settlement-verification step"
                : "takeover had to reconstruct the unfinished settlement-verification step");

        ContextPack pack = new ContextPack(
                input.contextPack().caseId(), input.contextPack().customerId(), input.contextPack().transactionId(),
                input.contextPack().complaint(), input.mekoEnabled(), input.contextPack().simulateTakeover(), input.contextPack().priorLearningReused(),
                recoveredCompleteness,
                input.evidence(),
                merge(input.contextPack().memories(), snapshot.memories()),
                merge(input.contextPack().knowledge(), snapshot.knowledge()),
                List.copyOf(artifacts),
                List.copyOf(decisions),
                snapshot.missingContext(),
                input.hypotheses(),
                "verify settlement state",
                "investigation-takeover-agent",
                snapshot.conversationId() == null ? input.contextPack().conversationId() : snapshot.conversationId(),
                recoveryRoute(input, resumed));

        if (!resumed) {
            engine.remember("investigation-takeover-agent", input.caseId(),
                    "Takeover required rework because the prior checkpoint was not durable in the baseline.");
        } else {
            engine.remember("investigation-takeover-agent", input.caseId(),
                    "Takeover resumed from persisted checkpoint and confirmed settlement verification remains the next step.");
        }

        return new InvestigationComplete(
                input.caseId(), "investigation-takeover-agent", recoveredCompleteness,
                input.evidence(), input.hypotheses(),
                resumed
                        ? "Replacement investigator restored the checkpoint and continued from the unfinished settlement-verification step."
                        : "Replacement investigator had no durable checkpoint and reconstructed the unfinished investigation path.",
                resumed, input.mekoEnabled(), pack, pack.modelRoute());
    }

    private ModelRoute recoveryRoute(InvestigationNeedsTakeover input, boolean resumed) {
        String tier = resumed ? "LLM" : "FRONTIER";
        String selectedBy = "takeover policy";
        return modelRouter.route(tier, input.contextPack(), selectedBy);
    }

    private List<String> merge(List<String> first, List<String> second) {
        ArrayList<String> all = new ArrayList<>(first);
        for (String value : second) if (!all.contains(value)) all.add(value);
        return List.copyOf(all);
    }
}
