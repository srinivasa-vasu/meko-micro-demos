package meko.agentic.goap.context;

import meko.agentic.goap.model.ModelRoute;

import java.util.List;

public record ContextPack(
        String caseId,
        String customerId,
        String transactionId,
        String complaint,
        boolean mekoEnabled,
        boolean simulateTakeover,
        boolean priorLearningReused,
        int completeness,
        List<String> currentFacts,
        List<String> memories,
        List<String> knowledge,
        List<String> artifacts,
        List<String> decisions,
        List<String> missingContext,
        List<String> hypotheses,
        String nextAction,
        String ownerAgent,
        String conversationId,
        ModelRoute modelRoute) {

    public int contextSources() {
        return currentFacts.size() + memories.size() + knowledge.size() + artifacts.size() + decisions.size();
    }

    public boolean hasPriorLearning() {
        return !memories.isEmpty();
    }

    public ContextPack withRoute(ModelRoute route) {
        return new ContextPack(caseId, customerId, transactionId, complaint, mekoEnabled, simulateTakeover, priorLearningReused,
                completeness, currentFacts, memories, knowledge, artifacts, decisions,
                missingContext, hypotheses, nextAction, ownerAgent, conversationId, route);
    }
}
