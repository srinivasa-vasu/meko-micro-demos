package meko.agentic.goap.context;

import java.util.List;
import java.util.Map;

public interface ContextEngine {
    ContextSnapshot prepare(String agentId, String sessionId, String caseId, String customerId,
                            String transactionId, String complaint);

    ContextSnapshot search(String agentId, String caseId, String query);

    String checkpoint(String agentId, String caseId, Map<String, Object> state);

    Map<String, Object> restoreCheckpoint(String agentId, String caseId, String checkpointHash);

    void remember(String agentId, String caseId, String memory);

    default String persistContextPack(String agentId, String caseId, ContextPack pack) {
        java.util.LinkedHashMap<String, Object> state = new java.util.LinkedHashMap<>();
        state.put("type", "context-pack");
        state.put("caseId", pack.caseId());
        state.put("completeness", pack.completeness());
        state.put("facts", pack.currentFacts());
        state.put("memories", pack.memories());
        state.put("knowledge", pack.knowledge());
        state.put("artifacts", pack.artifacts());
        state.put("decisions", pack.decisions());
        state.put("missingContext", pack.missingContext());
        state.put("nextAction", pack.nextAction());
        state.put("ownerAgent", pack.ownerAgent());
        state.put("modelRoute", pack.modelRoute() == null ? Map.of() : Map.of(
                "tier", pack.modelRoute().tier(),
                "model", pack.modelRoute().model()));
        state.put("conversationId", pack.conversationId());
        return checkpoint(agentId, caseId, state);
    }

    boolean isConnected();

    default List<String> describeSources(ContextSnapshot snapshot) {
        return List.of(
                "current conversation",
                snapshot.memories().size() + " durable memories",
                snapshot.knowledge().size() + " knowledge matches",
                snapshot.artifacts().size() + " artifacts",
                snapshot.decisions().size() + " prior decisions"
        );
    }
}
