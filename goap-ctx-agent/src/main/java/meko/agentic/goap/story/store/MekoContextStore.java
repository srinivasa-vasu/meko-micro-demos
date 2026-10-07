package meko.agentic.goap.story.store;

import meko.agentic.goap.context.MekoMcpClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@link ContextStore} backed by the live Meko datapack. Every call is recorded as a receipt. A failed
 * call degrades to "no context" rather than failing the run: the planner then takes the path an agent
 * without memory would take, which is the honest outcome, and the receipt says why.
 */
@Component
public class MekoContextStore implements ContextStore {
    private static final int LIMIT = 8;

    private final MekoMcpClient meko;
    private final ReceiptLog receipts;
    private final OutageSwitch outage;

    /*
     * Writes no agent waits for run in the background: conversation turns (Meko queues them anyway) and results written
     * back for later cases. Turns of one conversation stay in order: each waits for the one before it. A read of a
     * conversation waits for its pending turns first; flush(caseId) waits for everything a case wrote.
     */
    private final java.util.concurrent.ExecutorService writer = java.util.concurrent.Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "meko-writer");
        t.setDaemon(true);
        return t;
    });
    private final Map<String, java.util.concurrent.CompletableFuture<Void>> threadTail = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, List<java.util.concurrent.CompletableFuture<Void>>> pendingByCase = new java.util.concurrent.ConcurrentHashMap<>();

    public MekoContextStore(MekoMcpClient meko, ReceiptLog receipts, OutageSwitch outage) {
        this.meko = meko;
        this.receipts = receipts;
        this.outage = outage;
    }

    @Override
    public Kind kind() {
        return Kind.MEKO_LIVE;
    }

    /** conversation_add_message is queued: a turn is readable a few seconds after it is written. */
    @Override
    public boolean writesAreQueued() {
        return true;
    }

    @Override
    public void addTurn(String caseId, String agentId, String thread, String input, String output, String reasoning) {
        background(caseId, thread, () -> writeTurn(caseId, agentId, thread, input, output, reasoning));
    }

    private void writeTurn(String caseId, String agentId, String thread, String input, String output, String reasoning) {
        call(caseId, agentId, "conversation_add_message", "[" + thread + "] " + abbreviate(reasoning != null ? reasoning : input), () -> {
            meko.callTool("conversation_add_message", meko.args(
                    "conversation_id", thread(thread),
                    "agent_id", MekoMcpClient.CONVERSATION_OWNER,
                    "input", input,
                    "output", output,
                    "reasoning", reasoning,
                    "metadata", meko.json(Map.of("caseId", caseId, "agent", agentId)),
                    "datapack_id", meko.datapack()));
            return new Result<>(null, 1);
        });
    }

    @Override
    public List<Turn> transcript(String caseId, String agentId, String thread) {
        // Whatever this application still has queued for the conversation is written before it is read.
        java.util.concurrent.CompletableFuture<Void> tail = threadTail.get(thread);
        if (tail != null) tail.join();
        return call(caseId, agentId, "conversation_get", "conversation " + thread, () -> {
            JsonNode conversation = meko.callTool("conversation_get", meko.args(
                    "conversation_id", thread(thread),
                    "agent_id", MekoMcpClient.CONVERSATION_OWNER,
                    "include_messages", true,
                    "datapack_id", meko.datapack()));
            List<Turn> turns = new ArrayList<>();
            for (JsonNode message : conversation.path("messages").values()) {
                String input = text(message.path("input"));
                String reasoning = text(message.path("reasoning"));
                String why = reasoning == null || reasoning.isBlank() ? null : reasoning;
                turns.add(new Turn(input, text(message.path("output")), writer(message, why), why));
            }
            return new Result<>(List.copyOf(turns), turns.size());
        }, List.of());
    }

    /**
     * A message field as text, whatever shape Meko returns it in. An input or output that was JSON when written (a
     * decision's answer) can come back parsed, as an object or array: that is written back out as compact JSON,
     * not dropped.
     */
    static String text(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return "";
        return node.isString() ? node.asString("") : node.toString();
    }

    /** Who wrote a message: its metadata when Meko returns it, else the agent id that leads a tagged record. */
    private String writer(JsonNode message, String reasoning) {
        JsonNode metadata = message.path("metadata");
        if (metadata.isString()) {
            try {
                metadata = meko.objectMapper().readTree(metadata.asString());
            } catch (RuntimeException e) {
                metadata = null;
            }
        }
        String agent = metadata == null ? null : metadata.path("agent").asString(null);
        if (agent != null && !agent.isBlank()) return agent;
        return new Turn("", "", "", reasoning).taggedBy().orElse("unknown");
    }

    @Override
    public List<StoreRecord> recallCase(String caseId, String agentId, String query) {
        return search(caseId, agentId, "memory_search", query, true);
    }

    @Override
    public List<StoreRecord> searchMemory(String caseId, String agentId, String query) {
        return search(caseId, agentId, "memory_search", query, false);
    }

    @Override
    public List<StoreRecord> searchKnowledge(String caseId, String agentId, String query) {
        return StoreRecord.onePerDocument(search(caseId, agentId, "knowledgebase_search", query, false));
    }

    private List<StoreRecord> search(String caseId, String agentId, String tool, String query, boolean caseScoped) {
        String detail = (caseScoped ? "[this case] " : "") + query;
        return call(caseId, agentId, tool, detail, () -> {
            String conversationId = conversation(caseId);
            JsonNode payload = meko.callTool(tool, meko.args(
                    "query", query,
                    "conversation_id", conversationId,
                    "run_id", caseScoped ? conversationId : null,
                    "agent_id", agentId,
                    "limit", LIMIT,
                    "datapack_id", meko.datapack()));
            List<StoreRecord> rows = meko.rows(payload).stream()
                    .limit(LIMIT) // knowledgebase_search does not honour limit
                    .map(this::toRecord)
                    .filter(r -> r.text() != null)
                    .toList();
            return new Result<>(rows, rows.size());
        }, List.of());
    }

    private StoreRecord toRecord(JsonNode row) {
        String writer = row.path("agent_id").asString(null);
        if (writer == null) writer = row.path("metadata_filters").path("agent_id").asString(null);
        // Chunks of a document uploaded through the Meko app carry a document_id and no agent.
        if (writer == null) writer = row.hasNonNull("document_id") ? StoreRecord.UPLOADED_DOCUMENT : "unknown";
        return new StoreRecord(
                row.path("id").asString(""),
                meko.rowText(row),
                writer,
                row.path("created_at").asString(""),
                row.path("document_id").asString(null));
    }

    @Override
    public Optional<String> remember(String caseId, String agentId, String text) {
        return call(caseId, agentId, "memory_add", abbreviate(text), () -> {
            JsonNode added = meko.callTool("memory_add", meko.args(
                    "text", text,
                    "agent_id", agentId,
                    "conversation_id", conversation(caseId),
                    "metadata", meko.json(Map.of("caseId", caseId, "agent", agentId)),
                    "datapack_id", meko.datapack()));
            Optional<String> id = meko.rows(added).stream()
                    .map(r -> r.path("id").asString(""))
                    .filter(s -> !s.isBlank())
                    .findFirst();
            return new Result<>(id, id.isPresent() ? 1 : 0);
        }, Optional.empty());
    }

    @Override
    public boolean update(String caseId, String agentId, String memoryId, String text) {
        return call(caseId, agentId, "memory_update", abbreviate(text), () -> {
            meko.callTool("memory_update", meko.args(
                    "memory_id", memoryId,
                    "text", text,
                    "agent_id", agentId,
                    "conversation_id", conversation(caseId),
                    "datapack_id", meko.datapack()));
            return new Result<>(true, 1);
        }, false);
    }

    @Override
    public boolean forget(String caseId, String writtenBy, String memoryId) {
        return call(caseId, writtenBy, "memory_delete_by_id", memoryId, () -> {
            meko.callTool("memory_delete_by_id", meko.args(
                    "memory_id", memoryId,
                    "agent_id", writtenBy,
                    "conversation_id", conversation(caseId),
                    "datapack_id", meko.datapack()));
            return new Result<>(true, 1);
        }, false);
    }

    @Override
    public boolean promote(String caseId, String agentId, List<String> memoryIds) {
        if (memoryIds.isEmpty()) return false;
        return call(caseId, agentId, "memory_promote", memoryIds.size() + " memory row(s) to the shared knowledge base", () -> {
            meko.callTool("memory_promote", meko.args(
                    "memory_ids", memoryIds,
                    "agent_id", agentId,
                    "conversation_id", conversation(caseId),
                    "datapack_id", meko.datapack()));
            return new Result<>(true, memoryIds.size());
        }, false);
    }

    @Override
    public Optional<String> putArtifact(String caseId, String agentId, String filename, Object content) {
        return call(caseId, agentId, "artifact_put", filename, () -> {
            JsonNode result = meko.callTool("artifact_put", meko.args(
                    "filename", filename,
                    "content_base64", Base64.getEncoder().encodeToString(meko.objectMapper().writeValueAsBytes(content)),
                    "content_type", "application/json",
                    "conversation_id", conversation(caseId),
                    "agent_id", agentId,
                    "datapack_id", meko.datapack()));
            return new Result<>(Optional.ofNullable(result.path("content_hash").asString(null)), 1);
        }, Optional.empty());
    }

    @Override
    public Optional<String> getArtifact(String caseId, String agentId, String contentHash) {
        return call(caseId, agentId, "artifact_get", contentHash.substring(0, Math.min(12, contentHash.length())) + "…", () -> {
            JsonNode result = meko.callTool("artifact_get", meko.args(
                    "content_hash", contentHash,
                    "conversation_id", conversation(caseId),
                    "agent_id", agentId,
                    "datapack_id", meko.datapack()));
            String b64 = result.path("content_base64").asString(null);
            Optional<String> json = b64 == null ? Optional.empty()
                    : Optional.of(new String(Base64.getDecoder().decode(b64), StandardCharsets.UTF_8));
            return new Result<>(json, json.isPresent() ? 1 : 0);
        }, Optional.empty());
    }

    @Override
    public void rememberLater(String caseId, String agentId, String text) {
        background(caseId, null, () -> remember(caseId, agentId, text));
    }

    @Override
    public void flush(String caseId) {
        List<java.util.concurrent.CompletableFuture<Void>> pending = pendingByCase.remove(caseId);
        if (pending != null) java.util.concurrent.CompletableFuture.allOf(pending.toArray(java.util.concurrent.CompletableFuture[]::new)).join();
    }

    /** Runs a write in the background; with a thread, after that conversation's earlier writes. Failures are recorded by call(). */
    private void background(String caseId, String thread, Runnable write) {
        java.util.concurrent.CompletableFuture<Void> done = thread == null
                ? java.util.concurrent.CompletableFuture.runAsync(write, writer)
                : threadTail.compute(thread, (k, tail) -> (tail == null ? java.util.concurrent.CompletableFuture.<Void>completedFuture(null) : tail)
                        .exceptionally(e -> null).thenRunAsync(write, writer));
        pendingByCase.computeIfAbsent(caseId, k -> new java.util.concurrent.CopyOnWriteArrayList<>()).add(done.exceptionally(e -> null));
    }

    /** The conversation for a case. Any process that knows the case id reconnects the same one. */
    private String conversation(String caseId) throws Exception {
        return meko.conversation(caseId, "Case " + caseId, Map.of("caseId", caseId));
    }

    /** A conversation keyed by a thread id such as a transaction id: knowing the id is enough to reach it. */
    private String thread(String thread) throws Exception {
        return meko.conversation(thread, "Conversation " + thread, Map.of("thread", thread));
    }

    // ------------------------------------------------------------------

    private record Result<T>(T value, int hits) {}

    @FunctionalInterface
    private interface Body<T> {
        Result<T> run() throws Exception;
    }

    private void call(String caseId, String agentId, String tool, String detail, Body<Void> body) {
        call(caseId, agentId, tool, detail, body, null);
    }

    private <T> T call(String caseId, String agentId, String tool, String detail, Body<T> body, T fallback) {
        long start = System.nanoTime();
        try {
            if (outage.on()) throw new IllegalStateException("Meko unreachable (simulated outage)");
            Result<T> result = body.run();
            receipts.record(caseId, new ReceiptLog.Receipt(agentId, tool, detail, result.hits(), millis(start), true, kind().name()));
            return result.value();
        } catch (Exception e) {
            String error = tool + " failed: " + e.getMessage();
            meko.recordError(error);
            receipts.record(caseId, new ReceiptLog.Receipt(agentId, tool, detail + " — " + error, 0, millis(start), false, kind().name()));
            return fallback;
        }
    }

    private static long millis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private static String abbreviate(String text) {
        return text.length() <= 110 ? text : text.substring(0, 110) + "…";
    }
}
