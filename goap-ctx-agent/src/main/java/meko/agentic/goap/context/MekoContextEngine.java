package meko.agentic.goap.context;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * {@link ContextEngine} for the original {@code /api/cases} commander flow, on top of {@link MekoMcpClient}.
 */
@Component
public class MekoContextEngine implements ContextEngine {
    private static final int SEARCH_LIMIT = 6;

    private final MekoMcpClient meko;

    public MekoContextEngine(MekoMcpClient meko) {
        this.meko = meko;
    }

    @Override
    public boolean isConnected() {
        return meko.configured();
    }

    @Override
    public ContextSnapshot prepare(String agentId, String sessionId, String caseId, String customerId,
                                   String transactionId, String complaint) {
        return guarded("prepare", () -> {
            String conversationId = meko.conversation(caseId, "Case " + caseId,
                    Map.of("customerId", customerId, "transactionId", transactionId));
            meko.callTool("conversation_add_message", meko.args(
                    "conversation_id", conversationId,
                    "input", complaint,
                    "output", "Intake opened case " + caseId,
                    "agent_id", MekoMcpClient.CONVERSATION_OWNER,
                    "reasoning", "Case opened by " + agentId + "; context retrieval follows.",
                    "plan", List.of("Retrieve historical memory", "Retrieve institutional knowledge", "Hand the case to the investigator"),
                    "metadata", meko.json(Map.of("caseId", caseId, "agent", agentId)),
                    "datapack_id", meko.datapack()));
            return doSearch(agentId, caseId, complaint);
        });
    }

    @Override
    public ContextSnapshot search(String agentId, String caseId, String query) {
        return guarded("search", () -> doSearch(agentId, caseId, query));
    }

    private ContextSnapshot doSearch(String agentId, String caseId, String query) throws Exception {
        String conversationId = meko.conversation(caseId, "Case " + caseId, Map.of("caseId", caseId));
        String datapack = meko.datapack();

        // Independent lookups: run them concurrently to halve the stage-visible latency.
        CompletableFuture<JsonNode> memoryCall = CompletableFuture.supplyAsync(() -> unchecked(() -> meko.callTool("memory_search", meko.args(
                "query", query, "conversation_id", conversationId, "agent_id", agentId,
                "limit", SEARCH_LIMIT, "datapack_id", datapack))));
        CompletableFuture<JsonNode> knowledgeCall = CompletableFuture.supplyAsync(() -> unchecked(() -> meko.callTool("knowledgebase_search", meko.args(
                "query", query, "conversation_id", conversationId, "agent_id", agentId,
                "limit", SEARCH_LIMIT, "datapack_id", datapack))));
        JsonNode memory;
        JsonNode knowledge;
        try {
            memory = memoryCall.join();
            knowledge = knowledgeCall.join();
        } catch (CompletionException e) {
            throw e.getCause() instanceof Exception cause ? cause : e;
        }

        List<String> memories = meko.rows(memory).stream()
                .map(row -> {
                    String text = meko.rowText(row);
                    String writer = row.path("agent_id").asString("");
                    return text == null ? null : writer.isBlank() ? text : text + " (written by " + writer + ")";
                })
                .filter(s -> s != null)
                .distinct()
                .toList();
        List<String> knowledgeMatches = meko.rows(knowledge).stream()
                .map(meko::rowText)
                .filter(s -> s != null)
                .distinct()
                .limit(SEARCH_LIMIT)
                .toList();

        List<String> facts = new ArrayList<>();
        facts.add("Meko context search: " + query);
        if (!memories.isEmpty()) facts.add(memories.size() + " durable memories retrieved from datapack");
        if (!knowledgeMatches.isEmpty()) facts.add(knowledgeMatches.size() + " knowledge-base matches retrieved");

        List<String> missing = new ArrayList<>();
        if (knowledgeMatches.isEmpty()) missing.add("shared knowledge");
        if (memories.isEmpty()) missing.add("historical reasoning");

        int completeness = 48 + Math.min(24, memories.size() * 4) + Math.min(28, knowledgeMatches.size() * 5);
        return new ContextSnapshot(Math.min(94, completeness), facts, memories, knowledgeMatches,
                List.of(), List.of(), missing, conversationId);
    }

    @Override
    public void remember(String agentId, String caseId, String memory) {
        guarded("memory_add", () -> meko.callTool("memory_add", meko.args(
                "text", memory,
                "agent_id", agentId,
                "run_id", caseId,
                "conversation_id", meko.conversation(caseId, "Case " + caseId, Map.of("caseId", caseId)),
                "metadata", meko.json(Map.of("caseId", caseId, "agent", agentId)),
                "datapack_id", meko.datapack())));
    }

    @Override
    public String checkpoint(String agentId, String caseId, Map<String, Object> state) {
        return guarded("checkpoint", () -> putArtifact(agentId, caseId, caseId + "-investigator-checkpoint.json", state));
    }

    @Override
    public String persistContextPack(String agentId, String caseId, ContextPack pack) {
        return guarded("context-pack persistence", () -> putArtifact(agentId, caseId, caseId + "-context-pack.json", pack));
    }

    private String putArtifact(String agentId, String caseId, String filename, Object content) throws Exception {
        JsonNode result = meko.callTool("artifact_put", meko.args(
                "filename", filename,
                "content_base64", Base64.getEncoder().encodeToString(meko.objectMapper().writeValueAsBytes(content)),
                "content_type", "application/json",
                "conversation_id", meko.conversation(caseId, "Case " + caseId, Map.of("caseId", caseId)),
                "agent_id", agentId,
                "datapack_id", meko.datapack()));
        String hash = result.path("content_hash").asString(null);
        if (hash == null) throw new IllegalStateException("artifact_put returned no content_hash: " + result);
        return hash;
    }

    @Override
    public Map<String, Object> restoreCheckpoint(String agentId, String caseId, String checkpointHash) {
        return guarded("artifact_get", () -> {
            JsonNode result = meko.callTool("artifact_get", meko.args(
                    "content_hash", checkpointHash,
                    "conversation_id", meko.conversation(caseId, "Case " + caseId, Map.of("caseId", caseId)),
                    "agent_id", agentId,
                    "datapack_id", meko.datapack()));
            String b64 = result.path("content_base64").asString(null);
            if (b64 == null) {
                // Artifacts of 1 MB or more are written to local disk instead; checkpoints never get that big.
                throw new IllegalStateException("artifact_get returned no inline content for " + checkpointHash);
            }
            JsonNode parsed = meko.objectMapper().readTree(Base64.getDecoder().decode(b64));
            Map<String, Object> answer = new LinkedHashMap<>();
            parsed.properties().forEach(e -> answer.put(e.getKey(), meko.objectMapper().convertValue(e.getValue(), Object.class)));
            return answer;
        });
    }

    private <T> T guarded(String operation, ThrowingSupplier<T> body) {
        try {
            return body.get();
        } catch (Exception e) {
            String error = "Meko " + operation + " failed: " + e.getMessage();
            meko.recordError(error);
            throw new IllegalStateException(error, e);
        }
    }

    private static <T> T unchecked(ThrowingSupplier<T> body) {
        try {
            return body.get();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    @FunctionalInterface
    interface ThrowingSupplier<T> {
        T get() throws Exception;
    }
}
