package meko.console.mcp;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A thin, honest client over Meko's MCP tools.
 * <p>
 * Deliberately generic: it can call ANY registered tool with any arguments, and it reports what
 * it sent alongside what came back. The typed helpers below are conveniences, not the point.
 * <p>
 * THE CONTRACTS THIS ENCODES were each learned by watching a call fail in a way that looked
 * like something else entirely. None are discoverable from the tool schemas:
 * <p>
 *   - conversation_id is REQUIRED by memory_*, knowledgebase_search AND datapack_list, even
 *     where the schema gives it a default and lists nothing as required.
 *   - datapack_list REJECTS agent_id outright ("unexpected_keyword_argument"). The datapack_*
 *     family takes conversation_id and datapack_id only.
 *   - memory_get_all accepts no page parameter. Sending one throws.
 *   - An unknown argument is rejected, never ignored — and the resulting exception, caught and
 *     turned into an empty list, is indistinguishable from an empty datapack.
 *   - Error responses are valid JSON containing zero results, so "no rows" and "the call was
 *     refused" look identical unless you check for an error payload.
 */
@Component
public class MekoClient {

    private static final Logger log = LoggerFactory.getLogger(MekoClient.class);

    /** Row fields that are never the payload, whatever the payload field is called here. */
    private static final Set<String> METADATA = Set.of(
            "id", "memory_id", "document_id", "hash", "score", "raw_similarity_score", "distance",
            "match_type", "rank", "rerank_score", "agent_id", "user_id", "conversation_id",
            "run_id", "app_id", "actor_id", "role", "event", "created_at", "updated_at",
            "promoted_to_kb_at", "promoted_by_name", "promoted_by_email", "user_name",
            "user_email", "metadata", "metadata_filters", "datapack_id", "universe_id", "grant");

    /** Collections Meko answers with, in the order we look for them. */
    private static final List<String> COLLECTIONS =
            List.of("results", "memories", "promoted_memories", "datapacks", "conversations",
                    "messages");

    private final SyncMcpToolCallbackProvider provider;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String agentId;
    private final Map<String, String> conversations = new HashMap<>();

    public MekoClient(SyncMcpToolCallbackProvider provider,
                      @Value("${meko.agent-id:meko-console}") String agentId) {
        this.provider = provider;
        this.agentId = agentId;
    }

    public boolean connected() {
        return provider.getToolCallbacks().length > 0;
    }

    public List<String> tools() {
        return Arrays.stream(provider.getToolCallbacks())
                .map(c -> c.getToolDefinition().name())
                .sorted()
                .toList();
    }

    public String agentId() {
        return agentId;
    }

    // ------------------------------------------------------------------
    // The one method everything else is built on
    // ------------------------------------------------------------------

    /**
     * Call any tool with any arguments, verbatim. Nothing is added.
     * <p>
     * Use this when a typed helper does not exist or when you need to prove what a tool does
     * with a particular argument set — which, given how often the schemas mislead, is often.
     */
    public McpCall callRaw(String tool, Map<String, Object> arguments) {
        var callback = Arrays.stream(provider.getToolCallbacks())
                .filter(c -> matches(c.getToolDefinition().name(), tool))
                .findFirst()
                .orElse(null);
        if (callback == null) {
            return McpCall.failed(tool, arguments,
                    "Tool not registered. Available: " + String.join(", ", tools()));
        }

        var started = System.currentTimeMillis();
        String raw;
        try {
            raw = callback.call(mapper.writeValueAsString(arguments));
        } catch (RuntimeException e) {
            log.warn("{} failed with {}", tool, arguments, e);
            return new McpCall(tool, callback.getToolDefinition().name(), arguments, false,
                    e.getMessage(), null, null, List.of(), System.currentTimeMillis() - started);
        }

        var error = errorIn(raw);
        return new McpCall(
                tool, callback.getToolDefinition().name(), arguments,
                error == null, error, raw, flatten(raw), rows(raw),
                System.currentTimeMillis() - started);
    }

    /** Call a tool with conversation_id and agent_id attached. Wrong for the datapack_* family. */
    public McpCall call(String tool, String datapackId, Map<String, Object> arguments) {
        return call(tool, datapackId, null, arguments);
    }

    /** As {@link #call(String, String, Map)}, acting as another agent when one is given. */
    public McpCall call(String tool, String datapackId, String asAgent,
                        Map<String, Object> arguments) {
        var args = new LinkedHashMap<String, Object>(arguments);
        args.put("conversation_id", conversationFor(datapackId));
        args.put("agent_id", agentOr(asAgent));
        return callRaw(tool, args);
    }

    /**
     * The agent to act as: the one asked for, else this console.
     * <p>
     * Needed because agent_id is a FILTER on most of the surface — memory_update, _delete_by_id,
     * _delete_all, _promote, knowledgebase_search and every conversation_* except _list. Acting
     * only as the console makes rows written by any other agent unreachable, and the miss reads
     * as "not found" rather than as the wrong agent.
     */
    String agentOr(String asAgent) {
        return asAgent == null || asAgent.isBlank() ? agentId : asAgent.strip();
    }

    // ------------------------------------------------------------------
    // Raw call templates
    // ------------------------------------------------------------------

    /** Arguments that are needed in practice whatever the schema says — when the tool has them. */
    private static final List<String> CONTEXT_ARGS =
            List.of("conversation_id", "datapack_id", "agent_id");

    public record ToolTemplate(String tool, String resolvedTool, Map<String, Object> arguments,
                               List<String> required, List<String> optional, String error) {}

    /**
     * A starting argument set for a raw call, built from the tool's own input schema.
     * <p>
     * The schema decides WHICH arguments exist: every tool rejects unknown ones, so a template
     * carrying an argument the schema lacks would fail before testing anything — datapack_list
     * has no agent_id, and gets none. The schema does NOT decide which are needed: it marks
     * conversation_id optional where the server requires it. So the template holds the schema's
     * required arguments plus conversation_id, datapack_id and agent_id wherever the tool has
     * them, filled with the live values this console uses. Everything else is listed as optional
     * rather than sent, since an argument you did not mean to test muddies the answer.
     */
    public ToolTemplate template(String tool, String datapackId) {
        var callback = Arrays.stream(provider.getToolCallbacks())
                .filter(c -> matches(c.getToolDefinition().name(), tool))
                .findFirst()
                .orElse(null);
        if (callback == null) {
            return new ToolTemplate(tool, null, Map.of(), List.of(), List.of(),
                    "Tool not registered.");
        }
        var schema = parse(callback.getToolDefinition().inputSchema());
        var properties = schema == null ? null : schema.get("properties");
        if (properties == null || !properties.isObject()) {
            return new ToolTemplate(tool, callback.getToolDefinition().name(), Map.of(),
                    List.of(), List.of(), null);
        }
        var required = new ArrayList<String>();
        if (schema.has("required")) {
            schema.get("required").values().forEach(n -> required.add(n.asString()));
        }
        // The one tool where conversation_id is genuinely optional, and better left out.
        var skipConversation = tool.endsWith("conversation_list");

        var args = new LinkedHashMap<String, Object>();
        var optional = new ArrayList<String>();
        for (var name : properties.propertyNames()) {
            var include = required.contains(name)
                    || CONTEXT_ARGS.contains(name)
                    && !(skipConversation && name.equals("conversation_id"));
            if (include) {
                args.put(name, placeholder(name, properties.get(name), datapackId));
            } else {
                optional.add(name);
            }
        }
        return new ToolTemplate(tool, callback.getToolDefinition().name(), args,
                List.copyOf(required), List.copyOf(optional), null);
    }

    private Object placeholder(String name, JsonNode property, String datapackId) {
        var hasDatapack = datapackId != null && !datapackId.isBlank();
        switch (name) {
            case "conversation_id" -> {
                return conversationFor(hasDatapack ? datapackId : "");
            }
            case "datapack_id" -> {
                return hasDatapack ? datapackId : "<datapack_id>";
            }
            case "agent_id" -> {
                return agentId;
            }
            default -> { }
        }
        var type = typeOf(property);
        var fallback = property.get("default");
        return switch (type) {
            case "integer", "number" -> fallback != null && fallback.isNumber()
                    ? fallback.numberValue() : 0;
            case "boolean" -> fallback != null && fallback.isBoolean() && fallback.asBoolean();
            case "array" -> List.of("<" + name + ">");
            case "object" -> Map.of();
            default -> "<" + name + ">";
        };
    }

    /** The schema type, looking through anyOf [T, null] to T. */
    private String typeOf(JsonNode property) {
        if (property.has("type") && property.get("type").isString()) {
            return property.get("type").asString();
        }
        if (property.has("anyOf")) {
            for (var option : property.get("anyOf").values()) {
                var t = option.get("type");
                if (t != null && t.isString() && !t.asString().equals("null")) {
                    return t.asString();
                }
            }
        }
        return "string";
    }

    // ------------------------------------------------------------------
    // Conversations. Everything needs one, so this comes first.
    // ------------------------------------------------------------------

    /**
     * A conversation for this datapack, created once and reused.
     * <p>
     * Pass a blank datapack id for the BOOTSTRAP conversation: datapack_id is genuinely optional
     * on conversation_create, which is the only reason datapack_list can be called at all before
     * a datapack is known.
     */
    public synchronized String conversationFor(String datapackId) {
        var key = datapackId == null ? "" : datapackId;
        var existing = conversations.get(key);
        if (existing != null) {
            return existing;
        }
        var args = new LinkedHashMap<String, Object>();
        args.put("agent_id", agentId);
        if (!key.isBlank()) {
            args.put("datapack_id", key);
        }
        args.put("session_id", agentId + ":" + (key.isBlank() ? "bootstrap" : key));
        args.put("title", "Meko console");

        var call = callRaw("conversation_create", args);
        var id = firstId(call.raw());
        if (id == null) {
            throw new IllegalStateException(
                    "conversation_create returned no id; every other call needs one. " + call.raw());
        }
        conversations.put(key, id);
        return id;
    }

    // ------------------------------------------------------------------
    // Typed helpers
    // ------------------------------------------------------------------

    public McpCall listDatapacks() {
        // conversation_id required, agent_id rejected. Both contradict the schema.
        return callRaw("datapack_list", Map.of("conversation_id", conversationFor("")));
    }

    public McpCall describeDatapack(String datapackId) {
        return callRaw("datapack_describe", Map.of(
                "conversation_id", conversationFor(datapackId),
                "datapack_id", datapackId,
                "include_status", true));
    }

    public McpCall searchMemory(String datapackId, String query, int limit) {
        return call("memory_search", datapackId, Map.of(
                "query", query, "datapack_id", datapackId, "limit", limit));
    }

    public McpCall allMemories(String datapackId, boolean promoted) {
        // No page parameter exists. Sending one is rejected, and the resulting empty list reads
        // as an empty datapack.
        return call("memory_get_all", datapackId, Map.of(
                "datapack_id", datapackId, "promoted", promoted));
    }

    public McpCall addMemory(String datapackId, String text, String asAgent) {
        var args = new LinkedHashMap<String, Object>();
        args.put("text", text);
        args.put("datapack_id", datapackId);
        args.put("conversation_id", conversationFor(datapackId));
        args.put("agent_id", agentOr(asAgent));
        return callRaw("memory_add", args);
    }

    public McpCall updateMemory(String datapackId, String memoryId, String text, String asAgent) {
        return call("memory_update", datapackId, asAgent, Map.of(
                "memory_id", memoryId, "text", text, "datapack_id", datapackId));
    }

    public McpCall deleteMemory(String datapackId, String memoryId, String asAgent) {
        return call("memory_delete_by_id", datapackId, asAgent, Map.of(
                "memory_id", memoryId, "datapack_id", datapackId));
    }

    /**
     * Delete EVERY memory in this datapack for this agent.
     * <p>
     * The most destructive call on the surface and the least reversible. Exposed because a demo
     * corpus does need resetting — the sibling project accumulated 56 memories and 29 duplicate
     * promoted rows before anyone noticed — but nothing should reach it without an explicit,
     * typed confirmation.
     */
    public McpCall deleteAllMemories(String datapackId, String asAgent) {
        return call("memory_delete_all", datapackId, asAgent, Map.of("datapack_id", datapackId));
    }

    /** One way: promotion evicts the row from mem0 and needs owner or maintainer rights. */
    public McpCall promoteMemories(String datapackId, List<String> memoryIds, String asAgent) {
        return call("memory_promote", datapackId, asAgent, Map.of(
                "memory_ids", memoryIds, "datapack_id", datapackId));
    }

    // ------------------------------------------------------------------
    // Conversations
    //
    // agent_id CHANGES MEANING HERE, and it is the difference between a row and nothing.
    //
    //   memory_search            trace attribution only; does not filter results
    //   conversation_get/update/delete  OWNERSHIP FILTER — the wrong agent returns "not found",
    //                            which is indistinguishable from the conversation not existing
    //   datapack_list            rejected outright as an unexpected argument
    //
    // So a conversation created by one agent is invisible to another, and the failure looks
    // like a missing record rather than a permissions mismatch. The console shows which agent
    // it is acting as on every call for exactly this reason.
    // ------------------------------------------------------------------

    /**
     * List conversations, most recently updated first.
     *
     * The only tool on this surface where conversation_id is genuinely optional — omitting it
     * gives the call its own ephemeral trace rather than attributing it to an unrelated
     * conversation, which is what we want for a listing.
     */
    public McpCall listConversations(String datapackId, int limit, int offset) {
        var args = new LinkedHashMap<String, Object>();
        args.put("datapack_id", datapackId);
        args.put("agent_id", agentId);
        args.put("limit", limit);
        args.put("offset", offset);
        return callRaw("conversation_list", args);
    }

    /** Create a conversation explicitly, separate from the console's own cached one. */
    public McpCall createConversation(String datapackId, String title, String sessionId,
                                      String asAgent) {
        var args = new LinkedHashMap<String, Object>();
        args.put("agent_id", agentOr(asAgent));
        if (datapackId != null && !datapackId.isBlank()) {
            args.put("datapack_id", datapackId);
        }
        if (title != null && !title.isBlank()) {
            args.put("title", title);
        }
        // Honoured as a dedup key: the same seed reconnects the same conversation rather than
        // creating a duplicate. Non-hex ids are normalised, so the returned id may differ.
        if (sessionId != null && !sessionId.isBlank()) {
            args.put("session_id", sessionId);
        }
        return callRaw("conversation_create", args);
    }

    public McpCall getConversation(String datapackId, String conversationId,
                                   boolean includeMessages, int limit, int offset,
                                   String asAgent) {
        var args = new LinkedHashMap<String, Object>();
        args.put("conversation_id", conversationId);
        args.put("datapack_id", datapackId);
        args.put("agent_id", agentOr(asAgent));
        args.put("include_messages", includeMessages);
        args.put("limit", limit);
        args.put("offset", offset);
        return callRaw("conversation_get", args);
    }

    /**
     * Append one turn: the user's input and the assistant's output.
     *
     * Note the argument types differ in a way that is easy to get backwards — {@code plan} is a
     * real array of strings, while {@code metadata} is a JSON STRING. Passing either in the
     * other's shape is rejected.
     */
    public McpCall addMessage(String datapackId, String conversationId, String input,
                              String output, String reasoning, List<String> plan,
                              String metadataJson, String seed, String asAgent) {
        var args = new LinkedHashMap<String, Object>();
        args.put("conversation_id", conversationId);
        args.put("input", input);
        args.put("output", output);
        args.put("datapack_id", datapackId);
        // Must match the agent that created the conversation, or the write is not accepted.
        args.put("agent_id", agentOr(asAgent));
        if (reasoning != null && !reasoning.isBlank()) {
            args.put("reasoning", reasoning);
        }
        if (plan != null && !plan.isEmpty()) {
            args.put("plan", plan);
        }
        if (metadataJson != null && !metadataJson.isBlank()) {
            args.put("metadata", metadataJson);
        }
        if (seed != null && !seed.isBlank()) {
            args.put("seed", seed);
        }
        return callRaw("conversation_add_message", args);
    }

    public McpCall updateConversation(String datapackId, String conversationId,
                                      String title, String metadataJson, String asAgent) {
        var args = new LinkedHashMap<String, Object>();
        args.put("conversation_id", conversationId);
        args.put("datapack_id", datapackId);
        args.put("agent_id", agentOr(asAgent));
        // Omitted fields are kept; passing one replaces it.
        if (title != null && !title.isBlank()) {
            args.put("title", title);
        }
        if (metadataJson != null && !metadataJson.isBlank()) {
            args.put("metadata", metadataJson);
        }
        return callRaw("conversation_update", args);
    }

    /** Hard delete of the conversation and every trace under it. No undo. */
    public McpCall deleteConversation(String datapackId, String conversationId, String asAgent) {
        var args = new LinkedHashMap<String, Object>();
        args.put("conversation_id", conversationId);
        args.put("datapack_id", datapackId);
        args.put("agent_id", agentOr(asAgent));
        return callRaw("conversation_delete", args);
    }

    /** The conversation this console attaches to every other call, for display and copying. */
    public String activeConversation(String datapackId) {
        return conversationFor(datapackId);
    }

    /**
     * One memory by id.
     * <p>
     * Answers with the bare memory object rather than a collection, so the generic row extractor
     * finds nothing; read it as a single row instead.
     */
    public McpCall getMemory(String datapackId, String memoryId, String asAgent) {
        var call = call("memory_get_by_id", datapackId, asAgent, Map.of(
                "memory_id", memoryId, "datapack_id", datapackId));
        if (!call.ok() || !call.rows().isEmpty()) {
            return call;
        }
        var row = singleRow(call.raw());
        return row == null ? call : call.withRows(List.of(row));
    }

    /**
     * Retrieval across the conversation cache, memory and the knowledge base in one call.
     * <p>
     * Each source answers in its own bucket and has its own floor: memory keeps the FULL mem0
     * list on "auto" where memory_search drops rows below its min score, and the knowledge base
     * gets an absolute cutoff knowledgebase_search lacks. The same query returning different
     * rows here than from the single-source tools is expected, and it is the comparison worth
     * making. force_source="memory" reapplies the floor.
     */
    public McpCall searchContext(String datapackId, String query, int limit, String forceSource,
                                 String asAgent) {
        var args = new LinkedHashMap<String, Object>();
        args.put("query", query);
        args.put("datapack_id", datapackId);
        args.put("limit", limit);
        if (forceSource != null && !forceSource.isBlank()) {
            args.put("force_source", forceSource);
        }
        var call = call("context_search", datapackId, asAgent, args);
        var buckets = buckets(call.raw());
        return buckets.isEmpty() ? call : call.withBuckets(buckets);
    }

    public McpCall searchKnowledge(String datapackId, String query, int limit, String asAgent) {
        return call("knowledgebase_search", datapackId, asAgent, Map.of(
                "query", query, "datapack_id", datapackId, "limit", limit));
    }

    // ------------------------------------------------------------------
    // Response handling
    // ------------------------------------------------------------------

    /** Every string leaf, one per line. Field-name agnostic, so it survives schema drift. */
    String flatten(String raw) {
        var leaves = new ArrayList<String>();
        collect(parse(raw), leaves);
        return leaves.isEmpty() ? raw : String.join("\n", leaves);
    }

    private void collect(JsonNode node, List<String> out) {
        if (node == null) {
            return;
        }
        if (node.isString()) {
            var text = node.stringValue();
            var nested = parse(text);
            if (nested != null && (nested.isObject() || nested.isArray())) {
                collect(nested, out);
            } else if (!text.isBlank()) {
                out.add(text);
            }
            return;
        }
        if (node.isObject() || node.isArray()) {
            node.values().forEach(child -> collect(child, out));
        } else if (!node.isNull()) {
            out.add(node.asString());
        }
    }

    List<MekoRow> rows(String raw) {
        var parsed = parse(raw);
        JsonNode holder = null;
        String key = null;
        for (var candidate : COLLECTIONS) {
            holder = nodeWith(parsed, candidate);
            if (holder != null) {
                key = candidate;
                break;
            }
        }
        // datapack_list answers with a bare array, not a named collection.
        var collection = holder != null ? holder.get(key) : bareArray(parsed);
        return rowsOf(collection);
    }

    private List<MekoRow> rowsOf(JsonNode collection) {
        if (collection == null || !collection.isArray()) {
            return List.of();
        }
        var out = new ArrayList<MekoRow>();
        for (var row : collection.values()) {
            var parsed = rowOf(row);
            if (parsed != null) {
                out.add(parsed);
            }
        }
        return List.copyOf(out);
    }

    private MekoRow rowOf(JsonNode row) {
        if (row == null || !row.isObject()) {
            return null;
        }
        var id = Optional.ofNullable(text(row, "id"))
                .or(() -> Optional.ofNullable(text(row, "memory_id")))
                .or(() -> Optional.ofNullable(text(row, "datapack_id")))
                .orElse(null);
        var payload = payloadOf(row);
        if (id == null && payload == null) {
            return null;
        }
        return new MekoRow(
                id,
                payload,
                row.has("score") ? row.get("score").asDouble() : MekoRow.UNRANKED,
                row.has("raw_similarity_score")
                        ? row.get("raw_similarity_score").asDouble() : MekoRow.UNRANKED,
                Optional.ofNullable(text(row, "agent_id")).orElse(""),
                Optional.ofNullable(text(row, "created_at"))
                        .or(() -> Optional.ofNullable(text(row, "promoted_to_kb_at")))
                        .orElse(""));
    }

    /** A response that is one bare object (memory_get_by_id), read as a row. */
    MekoRow singleRow(String raw) {
        var node = unwrap(parse(raw));
        return node != null && node.isObject() && errorIn(raw) == null ? rowOf(node) : null;
    }

    /**
     * Rows per source, for a response made of labelled buckets.
     * <p>
     * Bucket names are read off the response, not hard-coded: every field of the payload object
     * that holds a list — directly, or as the one list inside a nested object — is a bucket,
     * named by its field. An EMPTY list is still a bucket. "That source found nothing" is an
     * answer, and dropping it would make two sources look like the whole response.
     */
    Map<String, List<MekoRow>> buckets(String raw) {
        var root = unwrap(parse(raw));
        var out = new LinkedHashMap<String, List<MekoRow>>();
        if (root == null || !root.isObject()) {
            return out;
        }
        for (var name : root.propertyNames()) {
            var list = listIn(root.get(name));
            if (list != null) {
                out.put(name, rowsOf(list));
            }
        }
        return out;
    }

    /** The list a bucket holds: the node itself, or a known collection or sole list inside it. */
    private JsonNode listIn(JsonNode node) {
        if (node == null) {
            return null;
        }
        if (node.isString()) {
            var nested = parse(node.stringValue());
            return nested != null && (nested.isArray() || nested.isObject()) ? listIn(nested) : null;
        }
        if (node.isArray()) {
            return node;
        }
        if (!node.isObject()) {
            return null;
        }
        for (var key : COLLECTIONS) {
            if (node.has(key) && node.get(key).isArray()) {
                return node.get(key);
            }
        }
        JsonNode only = null;
        for (var child : node.values()) {
            if (child.isArray()) {
                if (only != null) {
                    return null;
                }
                only = child;
            }
        }
        return only;
    }

    /**
     * Peel transport framing — SSE data, MCP text content, a {"result": "..."} envelope — down
     * to the tool's own JSON.
     */
    private JsonNode unwrap(JsonNode node) {
        while (node != null) {
            if (node.isString()) {
                node = parse(node.stringValue());
            } else if (node.isArray() && node.size() == 1 && node.get(0).has("text")) {
                node = node.get(0).get("text");
            } else if (node.isObject() && node.size() == 1
                    && (node.has("result") || node.has("text"))) {
                node = node.values().iterator().next();
            } else if (node.isObject() && node.has("content") && node.get("content").isArray()
                    && node.get("content").size() == 1) {
                node = node.get("content").get(0).get("text");
            } else {
                return node;
            }
        }
        return null;
    }

    /**
     * The payload of a row without depending on what the field is called.
     * <p>
     * Known names first; otherwise the longest non-metadata string, because in every shape seen
     * the payload dwarfs ids, emails, agent names and timestamps. Guessing field names cost two
     * rounds of debugging in the sibling project before this existed.
     */
    private String payloadOf(JsonNode row) {
        for (var name : List.of("memory", "data", "chunk_text", "text", "content",
                "datapack_name", "title", "input", "message")) {
            var known = text(row, name);
            if (known != null) {
                return known;
            }
        }
        String longest = null;
        for (var name : row.propertyNames()) {
            if (METADATA.contains(name)) {
                continue;
            }
            var value = text(row, name);
            if (value != null && (longest == null || value.length() > longest.length())) {
                longest = value;
            }
        }
        return longest != null && longest.length() >= 8 ? longest : null;
    }

    /** A Meko error payload, if that is what came back. Valid JSON with zero rows otherwise. */
    String errorIn(String raw) {
        var node = nodeWith(parse(raw), "error");
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

    String firstId(String raw) {
        return findId(parse(raw));
    }

    private String findId(JsonNode node) {
        if (node == null) {
            return null;
        }
        if (node.isString()) {
            return findId(parse(node.stringValue()));
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

    /** Descend through the double wrapping until a node carrying this field turns up. */
    private JsonNode nodeWith(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        if (node.isString()) {
            return nodeWith(parse(node.stringValue()), field);
        }
        if (node.isObject() && node.has(field)) {
            return node;
        }
        if (node.isObject() || node.isArray()) {
            for (var child : node.values()) {
                var found = nodeWith(child, field);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private JsonNode bareArray(JsonNode node) {
        if (node == null) {
            return null;
        }
        if (node.isString()) {
            return bareArray(parse(node.stringValue()));
        }
        if (node.isArray()) {
            for (var row : node.values()) {
                if (row.isObject() && (row.has("datapack_name") || row.has("memory"))) {
                    return node;
                }
            }
        }
        if (node.isObject() || node.isArray()) {
            for (var child : node.values()) {
                var found = bareArray(child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** Accepts plain JSON or an SSE stream, because streamable HTTP may answer with either. */
    private JsonNode parse(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        var data = text.lines()
                .filter(l -> l.startsWith("data:"))
                .map(l -> l.substring(5).trim())
                .reduce("", String::concat);
        for (var candidate : List.of(data, text.strip())) {
            var trimmed = candidate.strip();
            if (!(trimmed.startsWith("{") || trimmed.startsWith("["))) {
                continue;
            }
            try {
                return mapper.readTree(trimmed);
            } catch (Exception e) {
                // try the next shape
            }
        }
        return null;
    }

    private String text(JsonNode row, String field) {
        var value = row.get(field);
        return value != null && value.isString() && !value.stringValue().isBlank() ? value.stringValue() : null;
    }

    /** Spring AI namespaces MCP callbacks with the connection name. */
    private boolean matches(String registered, String tool) {
        return registered != null && (registered.equals(tool) || registered.endsWith("_" + tool));
    }
}
