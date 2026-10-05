package meko.agentic.sre.meko;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Deterministic adapter over Meko's MCP tools.
 */
@Component
public class MekoToolBridge {

    private static final Logger log = LoggerFactory.getLogger(MekoToolBridge.class);

    /** Row fields that are never the record itself, whatever the payload field ends up called. */
    private static final Set<String> METADATA_FIELDS = Set.of(
            "id", "memory_id", "hash", "score", "raw_similarity_score", "match_type", "rank",
            "rerank_score", "distance", "agent_id", "user_id", "conversation_id", "run_id",
            "app_id", "actor_id", "role", "event", "created_at", "updated_at", "user_name",
            "user_email", "promoted_to_kb_at", "promoted_by_name", "promoted_by_email",
            "document_id", "metadata", "metadata_filters");

    private final SyncMcpToolCallbackProvider provider;
    private final ObjectMapper objectMapper;
    private final String agentId;

    /**
     * One conversation per datapack, created on first use. Meko dedups on session_id, so a
     * restart reconnects the same container rather than accumulating empties.
     * <p>
     * If you would rather have one trace per incident, key this on the incident id instead
     * and pass it down from the agent; conversation_create honours session_id for exactly
     * that. It costs one extra MCP round trip per incident.
     */
    private final Map<String, String> conversationIds = new HashMap<>();

    /**
     * How long a READ is waited for. Writes are bounded by the MCP client's request-timeout
     * instead, which is set higher: a Meko write runs a server-side extraction and was observed
     * taking over 30s on its own. One client-wide timeout cannot serve both — high enough for
     * writes lets a hung read hold a whole run hostage for that long.
     */
    private final java.time.Duration readTimeout;

    @org.springframework.beans.factory.annotation.Autowired
    public MekoToolBridge(
            SyncMcpToolCallbackProvider provider,
            ObjectMapper objectMapper,
            @Value("${meko.agent-id:ai-sre-that-remembers}") String agentId,
            @Value("${meko.read-timeout:30s}") java.time.Duration readTimeout) {
        this.provider = provider;
        this.objectMapper = objectMapper;
        this.agentId = agentId;
        this.readTimeout = readTimeout;
    }

    /** With the default read timeout. */
    public MekoToolBridge(SyncMcpToolCallbackProvider provider, ObjectMapper objectMapper, String agentId) {
        this(provider, objectMapper, agentId, java.time.Duration.ofSeconds(30));
    }

    public String memorySearch(String query, String datapackId, int limit) {
        return memorySearch(query, datapackId, limit, NO_REPORT);
    }

    /**
     * Reads degrade to empty rather than throwing, so a run survives a Meko outage. But empty
     * because it failed and empty because nothing matched are different facts, and only the
     * second is a result: {@code onFailure} receives a description of each failed read, so the
     * caller can report partial recall instead of presenting it as a genuine miss.
     */
    public String memorySearch(String query, String datapackId, int limit, Consumer<String> onFailure) {
        return invokeQuietly("memory_search", datapackId, Map.of(
                "query", query,
                "datapack_id", datapackId,
                "limit", limit
        ), onFailure);
    }

    /** For callers with nowhere to report a failed read. */
    private static final Consumer<String> NO_REPORT = failure -> { };

    /**
     * Knowledge base hits as rows, so chunks stay separable.
     * <p>
     * parseMemories works on this response unchanged: knowledgebase_search rows carry an id and
     * chunk_text, and every other field on them (document_id, rank, match_type, rerank_score,
     * metadata_filters) is already in METADATA_FIELDS, so the longest-string fallback lands on
     * the chunk. Flattening would run the chunks together — they contain newlines themselves —
     * and there would be no way to show them separately.
     */
    public List<MekoMemory> knowledgeSearchRecords(String query, String datapackId, int limit) {
        return knowledgeSearchRecords(query, datapackId, limit, NO_REPORT);
    }

    /** As {@link #knowledgeSearchRecords(String, String, int)}, reporting a failed read. */
    public List<MekoMemory> knowledgeSearchRecords(
            String query, String datapackId, int limit, Consumer<String> onFailure) {
        try {
            var rows = parseMemories(call("knowledgebase_search", traced(datapackId, Map.of(
                    "query", query,
                    "datapack_id", datapackId,
                    "limit", limit))));

            // TRIM LOCALLY: knowledgebase_search does not honour limit. Measured on the live
            // datapack, a request for 5 chunks came back with 13 — so a caller that budgets its
            // prompt gets 2.6x what it planned for, silently. Rows arrive ranked, so taking the
            // first `limit` keeps the best ones and makes the budget mean something.
            return rows.size() <= limit ? rows : List.copyOf(rows.subList(0, limit));
        } catch (RuntimeException e) {
            log.warn("Knowledge retrieval failed — continuing without runbook context", e);
            onFailure.accept(describe("knowledgebase_search", e));
            return List.of();
        }
    }

    public String knowledgeSearch(String query, String datapackId, int limit) {
        return knowledgeSearch(query, datapackId, limit, NO_REPORT);
    }

    /** As {@link #knowledgeSearch(String, String, int)}, reporting a failed read. */
    public String knowledgeSearch(String query, String datapackId, int limit, Consumer<String> onFailure) {
        return invokeQuietly("knowledgebase_search", datapackId, Map.of(
                "query", query,
                "datapack_id", datapackId,
                "limit", limit
        ), onFailure);
    }

    /**
     * A failed write is reported through IncidentReport.persisted rather than thrown: losing
     * the learning record is bad, but losing the incident response with it is worse, and the
     * report already carries the flag.
     * <p>
     * {@code memoryId} is separate from {@code ok} because the two can diverge — mem0 answers
     * a duplicate with a successful call and no new row. Promotion needs the id, so it has to
     * be able to tell "wrote nothing" from "call failed".
     */
    public record WriteResult(boolean ok, Optional<String> memoryId) {
        static WriteResult failed() {
            return new WriteResult(false, Optional.empty());
        }
    }

    public WriteResult memoryAdd(String text, String datapackId) {
        return memoryAdd(text, datapackId, agentId);
    }

    /**
     * Write as a different agent. Used by the seed to plant records that a DEPLOY BOT would
     * have written, so the demo can show the SRE agent solving an incident with a record it
     * did not write itself.
     */
    public WriteResult memoryAdd(String text, String datapackId, String asAgent) {
        try {
            var args = new LinkedHashMap<String, Object>(Map.of(
                    "text", text,
                    "datapack_id", datapackId));
            args.put("conversation_id", conversationId(datapackId));
            args.put("agent_id", asAgent);
            var raw = call("memory_add", args);
            var rows = parseMemories(raw);
            return new WriteResult(true, rows.isEmpty()
                    ? Optional.empty()
                    : Optional.of(rows.getFirst().id()));
        } catch (RuntimeException e) {
            log.warn("Meko write failed — incident report will be marked not persisted", e);
            return WriteResult.failed();
        }
    }

    /**
     * Structured view of the same search, for the paths that need a memory's identity rather
     * than its prose: dedup on write-back, and promotion.
     * <p>
     * {@link #memorySearch} deliberately flattens to text because the parser wants records at
     * line start; that flattening throws the row id away, so those two callers get this
     * instead of a second parse of a lossy string.
     */
    public List<MekoMemory> memorySearchRecords(String query, String datapackId, int limit) {
        return memorySearchRecords(query, datapackId, limit, NO_REPORT);
    }

    /** As {@link #memorySearchRecords(String, String, int)}, reporting a failed read. */
    public List<MekoMemory> memorySearchRecords(
            String query, String datapackId, int limit, Consumer<String> onFailure) {
        try {
            var raw = call("memory_search", traced(datapackId, Map.of(
                    "query", query,
                    "datapack_id", datapackId,
                    "limit", limit
            )));
            return parseMemories(raw);
        } catch (RuntimeException e) {
            log.warn("Meko structured retrieval failed — caller will treat this as no match", e);
            onFailure.accept(describe("memory_search", e));
            return List.of();
        }
    }

    public boolean memoryUpdate(String memoryId, String text, String datapackId) {
        try {
            call("memory_update", traced(datapackId, Map.of(
                    "memory_id", memoryId,
                    "text", text,
                    "datapack_id", datapackId
            )));
            return true;
        } catch (RuntimeException e) {
            log.warn("Meko update of memory {} failed", memoryId, e);
            return false;
        }
    }

    /**
     * Move memories out of this agent's private mem0 store into the datapack's shared
     * knowledge base.
     * <p>
     * ONE WAY. Promotion evicts the row from mem0, and only datapack owners and maintainers
     * may do it — a viewer or contributor key gets 403. Both are handled as "stayed in
     * memory" rather than as failures, because a policy that could not be promoted is still
     * a usable policy; it just is not shared yet.
     */
    public boolean memoryPromote(List<String> memoryIds, String datapackId) {
        if (memoryIds == null || memoryIds.isEmpty()) {
            return false;
        }
        try {
            call("memory_promote", traced(datapackId, Map.of(
                    "memory_ids", memoryIds,
                    "datapack_id", datapackId
            )));
            log.info("Promoted {} memory row(s) to the shared knowledge base: {}", memoryIds.size(), memoryIds);
            return true;
        } catch (RuntimeException e) {
            log.warn("Promotion declined ({}). The policy stays in this agent's private memory.",
                    e.getMessage());
            return false;
        }
    }

    /**
     * Look a datapack up by its human name.
     * <p>
     * Deliberately NOT routed through {@link #traced}: every other call attaches a
     * conversation_id, and obtaining one requires a datapack id — which is the thing we are
     * trying to resolve. datapack_list is the one tool whose conversation_id is optional, so
     * this is the single call that can run before a datapack is known.
     *
     * @return the id, or empty when no datapack carries that name
     */
    public Optional<String> resolveDatapackId(String datapackName) {
        if (datapackName == null || datapackName.isBlank()) {
            return Optional.empty();
        }
        String raw;
        try {
            // Two hard-won details here, both contradicting what the tool schema implies.
            //
            // 1. datapack_list REQUIRES conversation_id. The parameter carries a default and
            //    appears in no "required" list, but the server answers
            //    {"error": "conversation_id_required"}. Hence the bootstrap conversation:
            //    created with no datapack_id, purely so this call can be made before a datapack
            //    is known. Do not "simplify" this back to an untraced call.
            //
            // 2. datapack_list REJECTS agent_id — "unexpected_keyword_argument". The datapack_*
            //    tools take only conversation_id and datapack_id; agent_id belongs to the
            //    memory_*, knowledgebase_search and conversation_* families. So this call is
            //    built by hand rather than through traced(), which adds agent_id to everything.
            raw = call("datapack_list", Map.of("conversation_id", conversationId("")));
        } catch (RuntimeException e) {
            log.warn("datapack_list call failed while resolving '{}'. Registered tools: {}",
                    datapackName, registeredToolNames(), e);
            return Optional.empty();
        }
        var serverError = errorIn(raw);
        if (serverError != null) {
            log.warn("datapack_list refused the call while resolving '{}': {}", datapackName, serverError);
            return Optional.empty();
        }
        var found = findDatapackId(raw, datapackName);
        if (found.isEmpty()) {
            log.warn("datapack_list returned no datapack named '{}'. Visible names: [{}]. Raw response: {}",
                    datapackName, visibleNames(raw), truncate(raw));
        }
        return found;
    }

    /**
     * A Meko error payload, if the response is one.
     * <p>
     * Worth its own path: an error body parses perfectly well as JSON and simply contains no
     * datapacks, so without this it reports as "name not found" — which sends you looking at
     * the name when the problem is the call.
     */
    String errorIn(String raw) {
        var node = nodeWithField(parseOrNull(raw), "error");
        if (node == null) {
            return null;
        }
        var error = text(node, "error");
        var detail = text(node, "detail");
        if (error == null && detail == null) {
            return null;
        }
        return detail == null ? error : error + ": " + detail;
    }

    /**
     * Pull one datapack's id out of a datapack_list response, by name.
     * <p>
     * Split from the call so it can be tested against real captured payloads. Every previous
     * failure here has been in the unwrapping rather than the lookup, and unwrapping is the
     * part that cannot be reasoned about from the tool schema alone.
     */
    Optional<String> findDatapackId(String raw, String datapackName) {
        var root = unwrapToArray(raw);
        if (root == null) {
            return Optional.empty();
        }
        for (var row : root) {
            if (row.isObject() && datapackName.equalsIgnoreCase(text(row, "datapack_name"))) {
                return Optional.ofNullable(text(row, "datapack_id"));
            }
        }
        return Optional.empty();
    }

    /** Names in a datapack_list response, for the "you asked for X, here is what exists" message. */
    String visibleNames(String raw) {
        var root = unwrapToArray(raw);
        return root == null ? "<unparseable>" : namesIn(root);
    }

    private String truncate(String raw) {
        if (raw == null) {
            return "<null>";
        }
        return raw.length() <= 600 ? raw : raw.substring(0, 600) + "... (" + raw.length() + " chars)";
    }

    /** datapack_list answers with a JSON array, wrapped the same way everything else is. */
    JsonNode unwrapToArray(String raw) {
        var node = parseOrNull(raw);
        return findArray(node);
    }

    private JsonNode findArray(JsonNode node) {
        if (node == null) {
            return null;
        }
        if (node.isString()) {
            return findArray(parseOrNull(node.stringValue()));
        }
        if (node.isArray()) {
            // The datapack list is an array of objects carrying datapack_name.
            for (var row : node.values()) {
                if (row.isObject() && row.has("datapack_name")) {
                    return node;
                }
            }
        }
        if (node.isObject() || node.isArray()) {
            for (var child : node.values()) {
                var found = findArray(child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private String namesIn(JsonNode array) {
        var names = new ArrayList<String>();
        for (var row : array) {
            if (row.isObject()) {
                var name = text(row, "datapack_name");
                if (name != null) {
                    names.add(name);
                }
            }
        }
        return String.join(", ", names);
    }

    public boolean available() {
        return provider.getToolCallbacks().length > 0;
    }

    /**
     * One Meko row, with what {@link #memorySearch}'s flattening discards: the row id, the
     * semantic score, and which agent wrote it.
     *
     * {@code writtenBy} is the cross-agent story made visible. memory_search is scoped by
     * datapack and user, NOT by agent_id, so a record written by a deploy bot is readable by
     * the SRE agent — that is the property that needs a shared memory layer rather than a
     * local table, and it is invisible unless the writer is surfaced.
     */
    public record MekoMemory(String id, String text, double score, double rawScore,
                            String writtenBy, String createdAt) {
        /**
         * Score for a row that came from an enumeration rather than a ranking.
         *
         * memory_get_all is a listing and carries no similarity score. Defaulting that to 0.0
         * printed "Meko semantic score 0.00" next to a record memory had in fact matched
         * perfectly — a measurement that was never taken, displayed as a measurement of zero.
         * Negative means "not ranked", and sorts below any real score so a ranked copy of the
         * same record always wins.
         */
        public static final double UNRANKED = -1.0;

        public boolean ranked() {
            return score >= 0;
        }
    }

    /** Reads degrade to empty; the agent then plans the cold path. The failure is reported. */
    private String invokeQuietly(
            String toolName, String datapackId, Map<String, Object> args, Consumer<String> onFailure) {
        try {
            return invoke(toolName, datapackId, args);
        } catch (RuntimeException e) {
            log.warn("Meko retrieval failed for {} — continuing without historical context", toolName, e);
            onFailure.accept(describe(toolName, e));
            return "";
        }
    }

    /** One line a person can act on: which tool, and the innermost cause (a timeout, usually). */
    private static String describe(String toolName, RuntimeException e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        var why = root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
        return toolName + ": " + (why.length() > 160 ? why.substring(0, 160) + "…" : why);
    }

    private String invoke(String toolName, String datapackId, Map<String, Object> args) {
        return flatten(call(toolName, traced(datapackId, args)));
    }

    /**
     * Attach the conversation (required everywhere) and the writing agent.
     *
     * NOT universal: the datapack_* tools accept conversation_id and datapack_id only, and
     * reject agent_id outright with "unexpected_keyword_argument". Use this for the memory_*,
     * knowledgebase_search and conversation_* families; build datapack_* calls by hand.
     */
    private Map<String, Object> traced(String datapackId, Map<String, Object> args) {
        var withTrace = new LinkedHashMap<String, Object>(args);
        withTrace.put("conversation_id", conversationId(datapackId));
        withTrace.put("agent_id", agentId);
        return withTrace;
    }

    /**
     * Resolve the conversation this process writes under, creating it on first use.
     * Deliberately not routed through {@link #invoke}: it must not recurse, and the id has
     * to be read out of the unflattened response.
     */
    private synchronized String conversationId(String datapackId) {
        var key = datapackId == null ? "" : datapackId;
        var existing = conversationIds.get(key);
        if (existing != null) {
            return existing;
        }
        // datapack_id is genuinely optional on conversation_create, which is what makes the
        // bootstrap case work: a conversation with no datapack, used only to satisfy the
        // conversation_id that datapack_list demands before any datapack is known.
        var args = new LinkedHashMap<String, Object>();
        args.put("agent_id", agentId);
        if (!key.isBlank()) {
            args.put("datapack_id", key);
        }
        args.put("session_id", agentId + ":" + (key.isBlank() ? "bootstrap" : key));
        args.put("title", key.isBlank() ? "AI SRE bootstrap" : "AI SRE incident response");
        var raw = call("conversation_create", args);
        var id = firstIdIn(raw);
        if (id == null) {
            throw new IllegalStateException(
                    "conversation_create returned no usable id. Every Meko call needs one. Response: " + raw);
        }
        log.info("Meko conversation for datapack {}: {}", key.isBlank() ? "<bootstrap>" : key, id);
        conversationIds.put(key, id);
        return id;
    }

    private String call(String toolName, Map<String, Object> args) {
        var callback = Arrays.stream(provider.getToolCallbacks())
                .filter(c -> matches(c.getToolDefinition().name(), toolName))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Meko MCP tool not available: " + toolName
                                + ". Registered tools: " + registeredToolNames()));

        String payload;
        try {
            payload = objectMapper.writeValueAsString(args);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Could not encode Meko MCP arguments for " + toolName, e);
        }

        // WRITES GO ONE AT A TIME; reads do not. A Meko write runs mem0's extraction — a model
        // call on the server — so each takes seconds, and sent together they queue behind one
        // another server-side while the client's request timeout runs from the moment of
        // sending. Write-back and seeding issued them in parallel, and the last one in the queue
        // timed out at 30s. Waiting HERE, before sending, costs nothing against that timeout.
        boolean write = WRITE_TOOLS.contains(toolName);
        if (write) {
            writeLane.acquireUninterruptibly();
        }
        long started = System.nanoTime();
        try {
            return write ? callback.call(payload) : withinReadTimeout(() -> callback.call(payload), toolName);
        } catch (RuntimeException e) {
            // SyncMcpToolCallback's own error names no tool; this line is how a timeout is traced.
            log.warn("Meko {} failed after {} ms: {}", toolName, elapsedMs(started), e.getMessage());
            throw e;
        } finally {
            if (write) {
                writeLane.release();
            }
            long ms = elapsedMs(started);
            if (ms >= SLOW_CALL_MS) {
                log.warn("Meko {} took {} ms", toolName, ms);
            }
        }
    }

    /**
     * Stop waiting for a read after {@link #readTimeout}. The call itself is left to finish or
     * fail on its own virtual thread; what matters is that the run stops being held by it.
     */
    private String withinReadTimeout(java.util.function.Supplier<String> read, String toolName) {
        var pending = java.util.concurrent.CompletableFuture.supplyAsync(read, READS);
        try {
            return pending.get(readTimeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            pending.cancel(true);
            throw new IllegalStateException("%s read timed out after %d ms (meko.read-timeout)"
                    .formatted(toolName, readTimeout.toMillis()), e);
        } catch (java.util.concurrent.ExecutionException e) {
            if (e.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            throw new IllegalStateException(e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(toolName + " read interrupted", e);
        }
    }

    private static final java.util.concurrent.ExecutorService READS =
            java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();

    private static long elapsedMs(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }

    /** Tools that change the datapack. See {@link #call} for why they are serialised. */
    private static final Set<String> WRITE_TOOLS = Set.of(
            "memory_add", "memory_update", "memory_promote", "memory_delete_by_id");

    /** Calls slower than this are logged by name, so slowness is attributable without a profiler. */
    private static final long SLOW_CALL_MS = 10_000;

    private final java.util.concurrent.Semaphore writeLane = new java.util.concurrent.Semaphore(1, true);

    // ------------------------------------------------------------------
    // Response handling
    //
    // A Meko response arrives double-wrapped: Spring AI serializes the MCP content list, and
    // the text part of that list is itself a JSON document. Rather than hard-code either
    // shape — the item field name is mem0's to change, not ours — walk the tree and keep the
    // text leaves. Anything that is not JSON comes back untouched.
    // ------------------------------------------------------------------

    /** Every string leaf, one per line, so line-anchored record patterns match. */
    // Package-private: tested against real captured Meko payloads.
    String flatten(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        var leaves = new ArrayList<String>();
        collectText(parseOrNull(raw), leaves);
        return leaves.isEmpty() ? raw : String.join("\n", leaves);
    }

    private void collectText(JsonNode node, List<String> out) {
        if (node == null) {
            return;
        }
        if (node.isString()) {
            var text = node.stringValue();
            // A text leaf is often another JSON document (Meko nests one inside the MCP
            // text part). Recurse when it parses, otherwise it is the payload itself.
            var nested = parseOrNull(text);
            if (nested != null) {
                collectText(nested, out);
            } else if (!text.isBlank()) {
                out.add(text);
            }
            return;
        }
        if (node.isObject() || node.isArray()) {
            node.values().forEach(child -> collectText(child, out));
            return;
        }
        if (!node.isNull()) {
            out.add(node.asString());
        }
    }

    private JsonNode parseOrNull(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        var trimmed = text.strip();
        if (!(trimmed.startsWith("{") || trimmed.startsWith("["))) {
            return null;
        }
        try {
            return objectMapper.readTree(trimmed);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Meko returns a 32-hex Langfuse trace id, not a dashed UUID, and it may differ from the
     * session_id seed we sent — so it has to be read back rather than assumed.
     */
    private String firstIdIn(String raw) {
        return findId(parseOrNull(raw));
    }

    private String findId(JsonNode node) {
        if (node == null) {
            return null;
        }
        if (node.isString()) {
            return findId(parseOrNull(node.stringValue()));
        }
        if (node.isObject()) {
            var id = node.get("id");
            if (id != null && id.isString() && !id.stringValue().isBlank()) {
                return id.stringValue();
            }
        }
        if (node.isObject() || node.isArray()) {
            for (var child : node.values()) {
                var found = findId(child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /**
     * Pull {id, memory, score} out of a memory_search response.
     *
     * Unlike {@link #flatten}, this does have to know mem0's field names. It accepts the
     * usual aliases for the text field so a rename downstream degrades to "no dedup" rather
     * than to a crash.
     */
    List<MekoMemory> parseMemories(String raw) {
        // memory_search answers under "results"; memory_get_all under "memories" or,
        // for the shared collection, "promoted_memories".
        var parsed = parseOrNull(raw);
        String key = null;
        JsonNode holder = null;
        for (var candidate : List.of("results", "memories", "promoted_memories")) {
            holder = nodeWithField(parsed, candidate);
            if (holder != null) {
                key = candidate;
                break;
            }
        }
        if (holder == null) {
            return List.of();
        }
        var out = new ArrayList<MekoMemory>();
        var collection = holder.get(key);
        for (var row : collection) {
            if (!row.isObject()) {
                continue;
            }
            var id = Optional.ofNullable(text(row, "id"))
                    .or(() -> Optional.ofNullable(text(row, "memory_id")))
                    .orElse(null);
            var memory = payloadOf(row);
            if (id == null || memory == null) {
                continue;
            }
            var score = row.has("score") ? row.get("score").asDouble() : 0.0;
            // Vector similarity before the blend. Worth carrying: measured on the live datapack,
            // the blend halves exactly the long structured records this agent needs (0.4978 raw
            // becomes 0.2681) while leaving short routing rows untouched, so the correct incident
            // records rank LAST in a window they would otherwise top.
            var rawScore = row.has("raw_similarity_score")
                    ? row.get("raw_similarity_score").asDouble()
                    : MekoMemory.UNRANKED;
            var writtenBy = Optional.ofNullable(text(row, "agent_id")).orElse("unknown");
            var createdAt = Optional.ofNullable(text(row, "created_at"))
                    .or(() -> Optional.ofNullable(text(row, "updated_at")))
                    .orElse("");
            out.add(new MekoMemory(id, memory, score, rawScore, writtenBy, createdAt));
        }

        // A collection with rows in it that yields nothing is the worst failure mode here: the
        // caller sees an empty corpus and plans the cold path, and nothing anywhere says why.
        // It has already happened once — memory_get_all names its text field "data" while
        // memory_search uses "memory" — so say which field was not recognised rather than
        // leaving the next person to diff two code paths to find it.
        if (out.isEmpty() && !collection.isEmpty()) {
            var first = collection.get(0);
            var fields = new ArrayList<String>();
            if (first.isObject()) {
				fields.addAll(first.propertyNames());
            }
            log.warn("memory rows came back under '{}' but none could be read. Expected an id plus one "
                    + "of memory/data/text/content; this row has {}. Row: {}",
                    key, fields, truncate(first.toString()));
        }
        return List.copyOf(out);
    }

    /** Descend through the double wrapping until a node carrying {@code field} turns up. */
    private JsonNode nodeWithField(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        if (node.isString()) {
            return nodeWithField(parseOrNull(node.stringValue()), field);
        }
        if (node.isObject() && node.has(field)) {
            return node;
        }
        if (node.isObject() || node.isArray()) {
            for (var child : node.values()) {
                var found = nodeWithField(child, field);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /**
     * The record text in a Meko row, without depending on what the field is called.
     *
     * Meko is not consistent across endpoints — memory_search answers with "memory",
     * memory_get_all with "data" — and a row whose text field is not recognised gets dropped
     * silently, which is indistinguishable from an empty corpus. That failure has now cost two
     * rounds of guessing, so the guessing stops here.
     *
     * Known names first, because they are unambiguous. Otherwise take the longest string value
     * that is not metadata: in every shape seen so far the record dwarfs every other string in
     * the row, and ids, emails, agent names and timestamps are all both short and enumerable.
     */
    private String payloadOf(JsonNode row) {
        for (var name : List.of("memory", "data", "text", "content")) {
            var known = text(row, name);
            if (known != null) {
                return known;
            }
        }
        String longest = null;
        for (var name : row.propertyNames()) {
            if (METADATA_FIELDS.contains(name)) {
                continue;
            }
            var value = text(row, name);
            if (value != null && (longest == null || value.length() > longest.length())) {
                longest = value;
            }
        }
        // A bare id or timestamp is not a record; require something with substance in it.
        return longest != null && longest.length() >= 24 ? longest : null;
    }

    private String text(JsonNode row, String field) {
        var value = row.get(field);
        return value != null && value.isString() && !value.stringValue().isBlank()
                ? value.stringValue()
                : null;
    }

    /**
     * Every row in the datapack, paged.
     *
     * This is the existence-check primitive, and using semantic search for that job instead was
     * a mistake made twice in this file. memory_search answers "what is similar to this", which
     * is a ranking over a growing corpus — perfectly capable of not returning a row that is
     * definitely there. Asking "does this row exist" with a top-k similarity query means every
     * miss writes another duplicate, which pushes the original further down the ranking, which
     * causes more misses.
     *
     * @param promoted true to list the shared/promoted collection instead of this agent's mem0
     */
    public List<MekoMemory> memoryGetAll(String datapackId, boolean promoted) {
        try {
            // NO page PARAMETER. memory_get_all accepts agent_id, conversation_id, datapack_id,
            // promoted and run_id — nothing else. Sending page returns
            // "unexpected_keyword_argument", the call throws, and this method quietly answers
            // with an empty list, which reads as "the datapack is empty" and makes every
            // existence check fail open. Exactly the datapack_list/agent_id bug again.
            //
            // The cost is that only the first page comes back. Rows are newest-first and the
            // seeded records are rewritten on every arm, so they are always near the top; that
            // is enough for this job but it is a ceiling, not a full enumeration.
            var args = new LinkedHashMap<String, Object>();
            args.put("conversation_id", conversationId(datapackId));
            args.put("datapack_id", datapackId);
            args.put("agent_id", agentId);
            args.put("promoted", promoted);
            // Marked unranked: this is a listing, and a listing has no opinion about relevance.
            return parseMemories(call("memory_get_all", args)).stream()
                    .map(m -> new MekoMemory(m.id(), m.text(), MekoMemory.UNRANKED,
                            MekoMemory.UNRANKED, m.writtenBy(), m.createdAt()))
                    .toList();
        } catch (RuntimeException e) {
            log.warn("memory_get_all failed for datapack {} (promoted={})", datapackId, promoted, e);
            return List.of();
        }
    }

    public boolean memoryDelete(String memoryId, String datapackId) {
        try {
            call("memory_delete_by_id", traced(datapackId, Map.of(
                    "memory_id", memoryId,
                    "datapack_id", datapackId)));
            return true;
        } catch (RuntimeException e) {
            log.warn("Could not delete memory {}", memoryId, e);
            return false;
        }
    }

    /** Spring AI prefixes callbacks with the client/connection name. */
    private boolean matches(String registeredName, String toolName) {
        return registeredName != null
                && (registeredName.equals(toolName) || registeredName.endsWith("_" + toolName));
    }

    /** Public for the status endpoint: "which tools did we actually get?" is the first question. */
    public List<String> registeredTools() {
        return Arrays.stream(provider.getToolCallbacks())
                .map(c -> c.getToolDefinition().name())
                .sorted()
                .toList();
    }

    private String registeredToolNames() {
        return Arrays.stream(provider.getToolCallbacks())
                .map(c -> c.getToolDefinition().name())
                .reduce((a, b) -> a + ", " + b)
                .orElse("<none>");
    }
}
