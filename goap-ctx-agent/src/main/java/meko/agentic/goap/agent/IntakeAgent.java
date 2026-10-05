package meko.agentic.goap.agent;

import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.AchievesGoal;
import com.embabel.agent.api.annotation.Agent;
import meko.agentic.goap.api.CaseModels.CaseOpened;
import meko.agentic.goap.api.CaseModels.ContextReady;
import meko.agentic.goap.context.ContextEngine;
import meko.agentic.goap.context.ContextEngines;
import meko.agentic.goap.context.ContextPack;
import meko.agentic.goap.context.ContextSnapshot;

import java.util.List;

@Agent(description = "Customer-service intake specialist that reconstructs the initial case context")
public class IntakeAgent {
    private final ContextEngines engines;

    public IntakeAgent(ContextEngines engines) { this.engines = engines; }

    @Action(description = "Build the initial case context from the configured context engine", cost = 0.08)
    @AchievesGoal(description = "Initial case context is ready")
    public ContextReady prepare(CaseOpened opened) {
        boolean meko = opened.mekoEnabled() && engines.mekoAvailable();
        ContextEngine engine = engines.forMode(meko);
        ContextSnapshot snapshot = engine.prepare(
                "intake-agent", opened.caseId(), opened.caseId(), opened.customerId(),
                opened.transactionId(), opened.complaint());

        ContextPack pack = new ContextPack(
                opened.caseId(), opened.customerId(), opened.transactionId(), opened.complaint(),
                meko, opened.simulateTakeover(), !snapshot.memories().isEmpty(), snapshot.completeness(), snapshot.currentFacts(), snapshot.memories(),
                snapshot.knowledge(), snapshot.artifacts(), snapshot.decisions(), snapshot.missingContext(),
                List.of("duplicate charge", "delayed capture"), "retrieve settlement evidence", "investigation-agent",
                snapshot.conversationId(), null);

        return new ContextReady(
                opened.caseId(), opened.customerId(), opened.transactionId(), opened.complaint(),
                meko, opened.simulateTakeover(), snapshot.completeness(),
                engine.describeSources(snapshot),
                "Customer reports a failed card payment with a debit visible on the account.", pack);
    }
}
