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
    /** The text of memories each case wrote, by id, until promoted or the case is flushed: what a promotion moved. */
    private final Map<String, Map<String, String>> rememberedText = new java.util.concurrent.ConcurrentHashMap<>();
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
    /** Set on the writer threads: what they record is background time, not time the agents waited for. */
    private static final ThreadLocal<Boolean> IN_BACKGROUND = ThreadLocal.withInitial(() -> false);
    private final Map<String, java.util.concurrent.CompletableFuture<Void>> threadTail = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, List<java.util.concurrent.CompletableFuture<Void>>> pendingByCase = new java.util.concurrent.ConcurrentHashMap<>();

    public MekoContextStore(MekoMcpClient meko, ReceiptLog receipts, OutageSwitch outage) {
        this.meko = meko;
        this.receipts = receipts;
        this.outage = outage;
        // Another datapack: what is still queued for this one is written to it first, and nothing cached carries over.
        meko.beforeDatapackSwitch(() -> {
            // Bounded: a write that cannot finish (Meko unreachable) does not hold up the switch for ever.
            pendingByCase.values().forEach(list -> list.forEach(f -> {
                try {
                    f.get(30, java.util.concurrent.TimeUnit.SECONDS);
                } catch (Exception e) {
                    // Recorded as a failed write by its own call; the switch goes ahead.
                }
            }));
            knowledgeCache.clear();
            rememberedText.clear();
        });
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

    /**
     * Meko extracts memories from a turn's input side, so an agent's own record carries a short label as its input; the
     * request it answers travels in the turn's metadata and is restored as the input when the conversation is read.
     */
    @Override
    public void addRecord(String caseId, String agentId, String thread, String label, String request, String output, String reasoning) {
        background(caseId, thread, () -> writeTurn(caseId, agentId, thread, label, request, output, reasoning));
    }

    private void writeTurn(String caseId, String agentId, String thread, String input, String output, String reasoning) {
        writeTurn(caseId, agentId, thread, input, null, output, reasoning);
    }

    private void writeTurn(String caseId, String agentId, String thread, String input, String request, String output, String reasoning) {
        Map<String, Object> metadata = new java.util.LinkedHashMap<>(Map.of("caseId", caseId, "agent", agentId));
        if (request != null) metadata.put(REQUEST, request);
        // An agent's record: Meko extracts memories from a turn's input, and even a short label ("KYC-4F6AA1 · evidence
        // requested") came back as memories ("the user's KYC case is at 'evidence requested'") that crowd out real ones
        // in searches. The input is a constant with nothing to extract; the label travels in the metadata.
        String sent = input;
        if (request != null) {
            metadata.put(LABEL, input);
            sent = RECORD_INPUT;
        }
        String sentInput = sent;
        call(caseId, agentId, "conversation_add_message", "[" + thread + "] " + abbreviate(reasoning != null ? reasoning : input), () -> {
            meko.callTool("conversation_add_message", meko.args(
                    "conversation_id", thread(thread),
                    "agent_id", MekoMcpClient.CONVERSATION_OWNER,
                    "input", sentInput,
                    "output", output,
                    "reasoning", reasoning,
                    "metadata", meko.json(metadata),
                    "datapack_id", meko.datapack()));
            receipts.wrote(caseId, new ReceiptLog.Written(agentId, request != null ? "record" : "turn", request != null ? input : thread,
                    (request != null ? request : input) + " → " + output));
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
                String request = metadata(message).map(m -> m.path(REQUEST).asString(null)).orElse(null);
                turns.add(new Turn(request != null ? request : input, text(message.path("output")), writer(message, why), why));
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

    /** Where an agent's record keeps the request it answers (its input in Meko is a short label). */
    static final String REQUEST = "request";
    /** Where an agent's record keeps its label, and what it sends as its input instead (nothing to extract a memory from). */
    static final String LABEL = "label";
    static final String RECORD_INPUT = "(agent record)";

    /** A message's metadata as an object, whether Meko returns it as an object or as a JSON string. */
    private Optional<JsonNode> metadata(JsonNode message) {
        JsonNode metadata = message.path("metadata");
        if (metadata.isString()) {
            try {
                metadata = meko.objectMapper().readTree(metadata.asString());
            } catch (RuntimeException e) {
                return Optional.empty();
            }
        }
        return metadata.isObject() ? Optional.of(metadata) : Optional.empty();
    }

    /** Who wrote a message: its metadata when Meko returns it, else the agent id that leads a tagged record. */
    private String writer(JsonNode message, String reasoning) {
        JsonNode metadata = metadata(message).orElse(null);
        String agent = metadata == null ? null : metadata.path("agent").asString(null);
        if (agent != null && !agent.isBlank()) return agent;
        return new Turn("", "", "", reasoning).taggedBy().orElse("unknown");
    }

    @Override
    public List<StoreRecord> recallCase(String caseId, String agentId, String query) {
        return search(caseId, agentId, "memory_search", query, true, LIMIT);
    }

    @Override
    public List<StoreRecord> searchMemory(String caseId, String agentId, String query) {
        return search(caseId, agentId, "memory_search", query, false, LIMIT);
    }

    @Override
    public List<StoreRecord> searchMemory(String caseId, String agentId, String query, int limit) {
        return search(caseId, agentId, "memory_search", query, false, limit);
    }

    /**
     * Knowledge searches already made in a case, by query. Shared knowledge changes in a run only when something is
     * promoted, which clears the case's entries; a repeated search (the loan story's second application looking up the
     * same policy) is answered from here and receipted as cached, so no call is hidden.
     */
    private final Map<String, List<StoreRecord>> knowledgeCache = new java.util.concurrent.ConcurrentHashMap<>();

    @Override
    public List<StoreRecord> searchKnowledge(String caseId, String agentId, String query) {
        String key = caseId + "\u0000" + query;
        List<StoreRecord> cached = knowledgeCache.get(key);
        if (cached != null) {
            receipts.record(caseId, new ReceiptLog.Receipt(agentId, "knowledgebase_search", "[cached in this run] " + query, cached.size(), 0, true, kind().name()));
            return cached;
        }
        List<StoreRecord> rows = StoreRecord.onePerDocument(search(caseId, agentId, "knowledgebase_search", query, false, LIMIT));
        if (!rows.isEmpty()) knowledgeCache.put(key, rows);
        return rows;
    }

    private List<StoreRecord> search(String caseId, String agentId, String tool, String query, boolean caseScoped, int limit) {
        String detail = (caseScoped ? "[this case] " : "") + query;
        return call(caseId, agentId, tool, detail, () -> {
            String conversationId = conversation(caseId);
            JsonNode payload = meko.callTool(tool, meko.args(
                    "query", query,
                    "conversation_id", conversationId,
                    "run_id", caseScoped ? conversationId : null,
                    "agent_id", agentId,
                    "limit", limit,
                    "datapack_id", meko.datapack()));
            List<StoreRecord> rows = meko.rows(payload).stream()
                    .limit(limit) // knowledgebase_search does not honour limit
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
            receipts.wrote(caseId, new ReceiptLog.Written(agentId, "memory", null, text));
            id.ifPresent(m -> rememberedText.computeIfAbsent(caseId, k -> new java.util.concurrent.ConcurrentHashMap<>()).put(m, text));
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
        // Shared knowledge is about to change: searches made earlier in this case no longer stand.
        knowledgeCache.keySet().removeIf(k -> k.startsWith(caseId + "\u0000"));
        return call(caseId, agentId, "memory_promote", memoryIds.size() + " memory row(s) to the shared knowledge base", () -> {
            meko.callTool("memory_promote", meko.args(
                    "memory_ids", memoryIds,
                    "agent_id", agentId,
                    "conversation_id", conversation(caseId),
                    "datapack_id", meko.datapack()));
            Map<String, String> mine = rememberedText.getOrDefault(caseId, Map.of());
            memoryIds.stream().map(mine::remove).filter(java.util.Objects::nonNull)
                    .forEach(t -> receipts.wrote(caseId, new ReceiptLog.Written(agentId, "promoted", null, t)));
            return new Result<>(true, memoryIds.size());
        }, false);
    }

    @Override
    public Optional<Uploaded> uploadDocument(String caseId, String agentId, String filename, byte[] bytes) {
        long start = System.nanoTime();
        String detail = filename + " (" + bytes.length + " bytes)";
        try {
            if (outage.on()) throw new IllegalStateException("Meko unreachable (simulated outage)");
            MekoMcpClient.Upload upload = meko.uploadKnowledgeDocument(conversation(caseId), agentId, filename, bytes);
            // Shared knowledge is about to change: searches made earlier in this case no longer stand.
            knowledgeCache.keySet().removeIf(k -> k.startsWith(caseId + "\u0000"));
            receipts.record(caseId, new ReceiptLog.Receipt(agentId, "knowledgebase_upload_document",
                    detail + (upload.alreadyUploaded() ? ": already in the datapack" : ": uploaded, indexing"), 1, millis(start), true, kind().name(), IN_BACKGROUND.get()));
            return Optional.of(new Uploaded(upload.key(), upload.alreadyUploaded(), null));
        } catch (Exception e) {
            String error = "knowledgebase_upload_document failed: " + e.getMessage();
            meko.recordError(error);
            receipts.record(caseId, new ReceiptLog.Receipt(agentId, "knowledgebase_upload_document", detail + " — " + error, 0, millis(start), false, kind().name(), IN_BACKGROUND.get()));
            return Optional.of(new Uploaded(null, false, e.getMessage()));
        }
    }

    @Override
    public Optional<String> documentState(String caseId, String agentId, String key) {
        return call(caseId, agentId, "knowledgebase_document_status", key, () -> {
            JsonNode status = meko.documentStatus(conversation(caseId), agentId, key);
            // The state Meko reports, by whichever name it uses for it.
            String state = java.util.stream.Stream.of("state", "status", "indexing_status").map(f -> status.path(f).asString(""))
                    .filter(v -> !v.isBlank()).findFirst().orElse(status.isString() ? status.asString("") : "");
            return new Result<>(Optional.of(state.isBlank() ? "unknown" : state), 1);
        }, Optional.empty());
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
        knowledgeCache.keySet().removeIf(k -> k.startsWith(caseId + "\u0000"));
        List<java.util.concurrent.CompletableFuture<Void>> pending = pendingByCase.remove(caseId);
        if (pending != null) java.util.concurrent.CompletableFuture.allOf(pending.toArray(java.util.concurrent.CompletableFuture[]::new)).join();
        rememberedText.remove(caseId);
    }

    /** Runs a write in the background; with a thread, after that conversation's earlier writes. Failures are recorded by call(). */
    private void background(String caseId, String thread, Runnable work) {
        Runnable write = () -> {
            IN_BACKGROUND.set(true);
            try {
                work.run();
            } finally {
                IN_BACKGROUND.set(false);
            }
        };
        java.util.concurrent.CompletableFuture<Void> done = thread == null
                ? java.util.concurrent.CompletableFuture.runAsync(write, writer)
                : threadTail.compute(thread, (k, tail) -> (tail == null ? java.util.concurrent.CompletableFuture.<Void>completedFuture(null) : tail)
                        .exceptionally(e -> null).thenRunAsync(write, writer));
        // A conversation's last write, once done and still the last, is forgotten: the map holds only pending tails.
        if (thread != null) done.whenComplete((v, e) -> threadTail.remove(thread, done));
        java.util.concurrent.CompletableFuture<Void> settled = done.exceptionally(e -> null);
        List<java.util.concurrent.CompletableFuture<Void>> list = pendingByCase.computeIfAbsent(caseId, k -> new java.util.concurrent.CopyOnWriteArrayList<>());
        list.add(settled);
        // Done writes leave the list, so a case whose run never flushes (it failed) leaves nothing behind.
        settled.whenComplete((v, e) -> list.remove(settled));
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
            receipts.record(caseId, new ReceiptLog.Receipt(agentId, tool, detail, result.hits(), millis(start), true, kind().name(), IN_BACKGROUND.get()));
            return result.value();
        } catch (Exception e) {
            String error = tool + " failed: " + e.getMessage();
            meko.recordError(error);
            receipts.record(caseId, new ReceiptLog.Receipt(agentId, tool, detail + " — " + error, 0, millis(start), false, kind().name(), IN_BACKGROUND.get()));
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
