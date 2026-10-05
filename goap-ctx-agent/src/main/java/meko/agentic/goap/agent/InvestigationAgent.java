package meko.agentic.goap.agent;

import com.embabel.agent.api.annotation.AchievesGoal;
import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.Agent;
import com.embabel.agent.api.annotation.Cost;
import meko.agentic.goap.api.CaseModels.ContextReady;
import meko.agentic.goap.api.CaseModels.InvestigationComplete;
import meko.agentic.goap.api.CaseModels.InvestigationNeedsTakeover;
import meko.agentic.goap.api.CaseModels.InvestigationResult;
import meko.agentic.goap.context.ContextEngine;
import meko.agentic.goap.context.ContextEngines;
import meko.agentic.goap.context.ContextPack;
import meko.agentic.goap.context.ContextSnapshot;
import meko.agentic.goap.model.ModelRoute;
import meko.agentic.goap.model.ModelRouter;
import org.springframework.lang.Nullable;

import java.util.List;
import java.util.Map;

@Agent(description = "Payments investigator that reconstructs context, selects an appropriate model tier, and records reasoning for downstream agents")
public class InvestigationAgent {
    private final ContextEngines engines;
    private final ModelRouter modelRouter;

    public InvestigationAgent(ContextEngines engines, ModelRouter modelRouter) {
        this.engines = engines;
        this.modelRouter = modelRouter;
    }

    @Action(description = "Assemble the investigation context; in Meko mode, enrich it with cross-agent memory and shared knowledge", cost = 0.10)
    public ContextPack assembleContext(ContextReady context) {
        if (!context.mekoEnabled()) {
            return context.pack();
        }
        ContextSnapshot snapshot = engines.forMode(true).search(
                "investigation-agent", context.caseId(),
                "payment dispute authorization capture timeout previous cases policy settlement");
        return new ContextPack(
                context.caseId(), context.customerId(), context.transactionId(), context.complaint(),
                true, context.simulateTakeover(), !snapshot.memories().isEmpty(),
				Math.clamp(context.completeness(), snapshot.completeness() + 8, 100),
                snapshot.currentFacts(), snapshot.memories(), snapshot.knowledge(), snapshot.artifacts(),
                snapshot.decisions(), snapshot.missingContext(),
                List.of("duplicate charge — investigate", "delayed capture — investigate"),
                "verify settlement state", "investigation-agent", snapshot.conversationId(), null);
    }

    @Cost(name = "slmRouteCost")
    public double slmRouteCost(@Nullable ContextPack pack) {
        return modelRouter.planningCost("SLM", pack);
    }

    @Cost(name = "llmRouteCost")
    public double llmRouteCost(@Nullable ContextPack pack) {
        return modelRouter.planningCost("LLM", pack);
    }

    @Cost(name = "frontierRouteCost")
    public double frontierRouteCost(@Nullable ContextPack pack) {
        return modelRouter.planningCost("FRONTIER", pack);
    }

    // The route tier is carried as a type, not a field: the planner can only chain on conditions it
    // knows an action will produce, and it cannot know a ModelRoute's tier before the route exists.
    public record SlmRoute(ModelRoute route) {}
    public record LlmRoute(ModelRoute route) {}
    public record FrontierRoute(ModelRoute route) {}

    @Action(description = "Route the investigation to a small/cheap model when rich context makes it safe", costMethod = "slmRouteCost")
    public SlmRoute routeToSlm(ContextPack pack) {
        return new SlmRoute(modelRouter.route("SLM", pack, "Embabel GOAP dynamic cost"));
    }

    @Action(description = "Route the investigation to a general reasoning model for moderate ambiguity", costMethod = "llmRouteCost")
    public LlmRoute routeToLlm(ContextPack pack) {
        return new LlmRoute(modelRouter.route("LLM", pack, "Embabel GOAP dynamic cost"));
    }

    @Action(description = "Route the investigation to a frontier model for high-impact or ambiguous cases", costMethod = "frontierRouteCost")
    public FrontierRoute routeToFrontier(ContextPack pack) {
        return new FrontierRoute(modelRouter.route("FRONTIER", pack, "Embabel GOAP dynamic cost"));
    }

    @Action(description = "Investigate using the cheap SLM route", cost = 0.12)
    @AchievesGoal(description = "Investigation result is available")
    public InvestigationResult investigateSlm(ContextPack pack, SlmRoute route) {
        return produce(pack, route.route());
    }

    @Action(description = "Investigate using the general LLM route", cost = 0.20)
    @AchievesGoal(description = "Investigation result is available")
    public InvestigationResult investigateLlm(ContextPack pack, LlmRoute route) {
        return produce(pack, route.route());
    }

    @Action(description = "Investigate using the frontier deep-reasoning route", cost = 0.38)
    @AchievesGoal(description = "Investigation result is available")
    public InvestigationResult investigateFrontier(ContextPack pack, FrontierRoute route) {
        return produce(pack, route.route());
    }

    private InvestigationResult produce(ContextPack pack, ModelRoute route) {
        ContextEngine engine = engines.forMode(pack.mekoEnabled());
        ContextSnapshot snapshot = engine.search(
                "investigation-agent", pack.caseId(),
                "transaction authorization capture timeout settlement prior decision");

        int completeness = Math.min(100, Math.max(pack.completeness(), snapshot.completeness()));
        List<String> evidence = List.of(
                "authorization was accepted",
                "capture timed out at the payment gateway",
                "ledger does not show a settled capture");
        List<String> hypotheses = List.of(
                "duplicate charge — rejected because authorization and capture identifiers differ",
                "delayed capture — remains possible until settlement is checked");

        ContextPack investigationPack = new ContextPack(
                pack.caseId(), pack.customerId(), pack.transactionId(), pack.complaint(), pack.mekoEnabled(), pack.simulateTakeover(), pack.priorLearningReused(),
                completeness,
                evidence,
                merge(pack.memories(), snapshot.memories()),
                merge(pack.knowledge(), snapshot.knowledge()),
                pack.artifacts(),
                merge(pack.decisions(), List.of("duplicate charge hypothesis rejected")),
                pack.missingContext(),
                hypotheses,
                "verify settlement state",
                "investigation-agent",
                snapshot.conversationId() == null ? pack.conversationId() : snapshot.conversationId(),
                route);

        if (shouldTakeover(pack)) {
            String checkpoint = engine.checkpoint("investigation-agent", pack.caseId(), Map.of(
                    "caseId", pack.caseId(),
                    "evidence", evidence,
                    "hypotheses", hypotheses,
                    "nextAction", "verify settlement state",
                    "contextCompleteness", completeness,
                    "modelRoute", Map.of("tier", route.tier(), "model", route.model()),
                    "decisions", List.of("duplicate charge hypothesis rejected")));
            engine.remember("investigation-agent", pack.caseId(),
                    "Investigation checkpoint: duplicate charge hypothesis rejected; settlement verification remains.");
            return new InvestigationNeedsTakeover(
                    pack.caseId(), "investigation-agent", completeness, evidence, hypotheses,
                    "Investigator stopped after establishing evidence and persisted a takeover checkpoint.",
                    checkpoint, pack.mekoEnabled(), investigationPack, route);
        }

        engine.remember("investigation-agent", pack.caseId(),
                "Investigation completed: gateway timeout caused failed capture after successful authorization.");
        return new InvestigationComplete(
                pack.caseId(), "investigation-agent", completeness, evidence, hypotheses,
                "Authorization succeeded but capture timed out; settlement and refund policy should be checked.",
                false, pack.mekoEnabled(), investigationPack, route);
    }

    private boolean shouldTakeover(ContextPack pack) {
        // The controller always starts the case with takeover enabled through the case flag.
        // This flag is represented in the complaint/context here; production code would carry it as case state.
        return pack.simulateTakeover();
    }

    private List<String> merge(List<String> first, List<String> second) {
        java.util.ArrayList<String> all = new java.util.ArrayList<>(first);
        for (String value : second) if (!all.contains(value)) all.add(value);
        return List.copyOf(all);
    }
}
