package meko.agentic.goap.context;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class NoMekoContextEngine implements ContextEngine {
    private final Map<String, String> conversations = new ConcurrentHashMap<>();

    @Override
    public ContextSnapshot prepare(String agentId, String sessionId, String caseId, String customerId,
                                   String transactionId, String complaint) {
        String conversationId = UUID.randomUUID().toString();
        conversations.put(caseId, conversationId);
        return new ContextSnapshot(
                28,
                List.of("customerId=" + customerId, "transactionId=" + transactionId, "complaint=" + complaint),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of("historical case reasoning", "shared policy knowledge", "durable checkpoint"),
                conversationId);
    }

    @Override
    public ContextSnapshot search(String agentId, String caseId, String query) {
        return new ContextSnapshot(
                39,
                List.of("transaction TX-48291 has an authorization record", "gateway returned timeout"),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of("historical case reasoning", "shared policy knowledge", "durable checkpoint"),
                conversations.get(caseId));
    }

    @Override
    public String checkpoint(String agentId, String caseId, Map<String, Object> state) {
        return "local-ephemeral-checkpoint-" + UUID.randomUUID();
    }

    @Override
    public String persistContextPack(String agentId, String caseId, ContextPack pack) {
        return null; // Nothing durable to point at in the baseline.
    }

    @Override
    public Map<String, Object> restoreCheckpoint(String agentId, String caseId, String checkpointHash) {
        return new HashMap<>();
    }

    @Override
    public void remember(String agentId, String caseId, String memory) {
        // Intentionally no-op: this is the baseline without Meko persistence.
    }

    @Override
    public boolean isConnected() {
        return false;
    }
}
