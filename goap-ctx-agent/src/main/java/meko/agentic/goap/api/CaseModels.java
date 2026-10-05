package meko.agentic.goap.api;

import meko.agentic.goap.context.ContextPack;
import meko.agentic.goap.model.ModelRoute;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class CaseModels {
    private CaseModels() {}

    public record CaseOpened(
            String caseId,
            String customerId,
            String transactionId,
            String complaint,
            boolean mekoEnabled,
            boolean simulateTakeover) {}

    public record ContextReady(
            String caseId,
            String customerId,
            String transactionId,
            String complaint,
            boolean mekoEnabled,
            boolean simulateTakeover,
            int completeness,
            List<String> sources,
            String handoffSummary,
            ContextPack pack) {}

    public sealed interface InvestigationResult
            permits InvestigationComplete, InvestigationNeedsTakeover {
        String caseId();
        String agent();
        int contextCompleteness();
        List<String> evidence();
        List<String> hypotheses();
        String summary();
        ContextPack contextPack();
        ModelRoute modelRoute();
        boolean mekoEnabled();
    }

    public record InvestigationComplete(
            String caseId,
            String agent,
            int contextCompleteness,
            List<String> evidence,
            List<String> hypotheses,
            String summary,
            boolean resumed,
            boolean mekoEnabled,
            ContextPack contextPack,
            ModelRoute modelRoute) implements InvestigationResult {}

    public record InvestigationNeedsTakeover(
            String caseId,
            String agent,
            int contextCompleteness,
            List<String> evidence,
            List<String> hypotheses,
            String summary,
            String checkpointHash,
            boolean mekoEnabled,
            ContextPack contextPack,
            ModelRoute modelRoute) implements InvestigationResult {}

    public record PolicyAssessment(
            String caseId,
            String policy,
            boolean refundEligible,
            String rationale,
            int contextCompleteness,
            List<String> policySources,
            boolean mekoEnabled,
            ContextPack contextPack,
            ModelRoute modelRoute) {}

    public record ResolutionOutput(
            String caseId,
            String outcome,
            List<String> learnings,
            ContextPack contextPack,
            ModelRoute modelRoute) {}

    public record CaseResolution(
            String caseId,
            String status,
            String outcome,
            String owner,
            int contextCompleteness,
            int reworkActions,
            boolean resumedFromCheckpoint,
            List<TimelineEvent> timeline,
            Map<String, Object> comparison,
            ContextPack contextPack,
            List<String> learnings,
            boolean priorLearningReused,
            boolean mekoEnabled) {}

    public record TimelineEvent(
            Instant at,
            String agent,
            String event,
            String detail,
            double cost,
            String model,
            String routeReason) {}
}
