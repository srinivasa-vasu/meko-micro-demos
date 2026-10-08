package meko.agentic.goap.context;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.StringNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Minimal MCP client over Meko's streamable-HTTP endpoint.
 * <p>
 * Contract details below were verified against the live server, not inferred from the tool schemas:
 * <ul>
 *   <li>Responses arrive as SSE ({@code event: message} / {@code data: {...}}).</li>
 *   <li>The tool payload is a JSON document inside {@code result.content[0].text}.</li>
 *   <li>Argument validation failures set {@code isError: true}; application errors such as
 *       {@code conversation_id_required} come back with {@code isError: false} and an
 *       {@code {"error": ...}} payload, so both have to be checked.</li>
 *   <li>{@code metadata} must be a JSON <em>string</em>, not an object.</li>
 *   <li>{@code conversation_id} is required by every memory, knowledge and artifact tool, and
 *       {@code conversation_create} returns it as {@code id}. {@code session_id} deduplicates, so
 *       the same session key reconnects the same conversation, even from another process.</li>
 *   <li>{@code memory_add} tags the row with the conversation id as {@code run_id};
 *       {@code memory_search(run_id=...)} then scopes recall to that conversation.</li>
 *   <li>{@code datapack_*} tools reject {@code agent_id}.</li>
 *   <li>{@code memory_search} is cross-agent within a datapack; {@code agent_id} is attribution only.</li>
 *   <li>{@code knowledgebase_search} does not honour {@code limit}.</li>
 *   <li>{@code conversation_add_message} is queued: a turn takes a few seconds to become readable.</li>
 * </ul>
 */
@Component
public class MekoMcpClient {
    private static final String PROTOCOL_VERSION = "2025-06-18";
    /**
     * Owner of every case and transaction conversation. Many agents write to one conversation, and
     * conversation_add_message must use the agent_id that created it, so each turn names its real writer in metadata.
     */
    public static final String CONVERSATION_OWNER = "case-records";
    private static final Set<String> ROW_METADATA = Set.of(
            "id", "memory_id", "hash", "score", "raw_similarity_score", "match_type", "rank",
            "rerank_score", "distance", "agent_id", "user_id", "conversation_id", "run_id",
            "app_id", "actor_id", "role", "event", "created_at", "updated_at", "document_id",
            "metadata", "metadata_filters", "filename", "source");

    private final MekoProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .build();
    private final Map<String, String> conversations = new ConcurrentHashMap<>();
    private final Map<String, Object> creating = new ConcurrentHashMap<>();
    /** Reads that change nothing, so a timeout or a server error can be retried once. */
    private static final java.util.Set<String> IDEMPOTENT = java.util.Set.of("memory_search", "knowledgebase_search", "conversation_get", "artifact_get");
    /** Calls that make model calls on Meko's side (fact extraction, summaries) get longer than the default. */
    private static final java.util.Set<String> SLOW = java.util.Set.of("memory_add", "memory_update", "conversation_get");
    private final AtomicLong requestIds = new AtomicLong(1);
    private volatile boolean initialized;
    private volatile String mcpSessionId;
    private volatile String datapackId;
    private volatile String lastError;

    public MekoMcpClient(MekoProperties properties) {
        this.properties = properties;
    }

    public boolean configured() {
        return properties.configured();
    }

    public String datapackName() {
        return properties.getDatapackName();
    }

    public String lastError() {
        return lastError;
    }

    public void recordError(String error) {
        lastError = error;
    }

    /** Connects, resolves the datapack and reports what happened. Never throws. */
    public Map<String, Object> status() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("configured", properties.configured());
        status.put("mcpUrl", properties.getMcpUrl());
        status.put("datapackName", properties.getDatapackName());
        if (!properties.configured()) {
            status.put("connected", false);
            status.put("diagnosis", "Set MEKO_ENABLED=true, MEKO_API_KEY and MEKO_DATAPACK_NAME (or MEKO_DATAPACK_ID).");
            return status;
        }
        try {
            status.put("datapackId", datapack());
            status.put("connected", true);
        } catch (Exception e) {
            status.put("connected", false);
            status.put("diagnosis", e.getMessage());
        }
        if (Boolean.TRUE.equals(status.get("connected"))) status.put("knowledge", knowledgeCounts());
        status.put("lastError", lastError);
        return status;
    }

    /**
     * What the datapack's shared knowledge holds: documents uploaded through the Meko app, and memories promoted by
     * agents. Null values mean the datapack status endpoint did not say.
     */
    private Map<String, Object> knowledgeCounts() {
        Map<String, Object> counts = new LinkedHashMap<>();
        try {
            JsonNode described = callTool("datapack_describe", args(
                    "datapack_id", datapack(),
                    "include_status", true,
                    "conversation_id", conversation(CONVERSATION_OWNER + ":status", "Datapack status", Map.of())));
            counts.put("documents", described.path("knowledge_base_file_count").isNumber() ? described.path("knowledge_base_file_count").asInt() : null);
            counts.put("promoted", described.path("collective_memory_count").isNumber() ? described.path("collective_memory_count").asInt() : null);
        } catch (Exception e) {
            counts.put("error", e.getMessage());
        }
        return counts;
    }

    /**
     * One conversation per session key (a case id, a transaction id), deduplicated server-side on session_id: any
     * process that knows the key reaches the same conversation.
     */
    public String conversation(String sessionKey, String title, Map<String, Object> metadata) throws Exception {
        String existing = conversations.get(sessionKey);
        if (existing != null) return existing;
        // One create per key, even when a background writer and the agent reach a new key at the same moment.
        synchronized (creating.computeIfAbsent(sessionKey, k -> new Object())) {
            existing = conversations.get(sessionKey);
            if (existing != null) return existing;
            return create(sessionKey, title, metadata);
        }
    }

    private String create(String sessionKey, String title, Map<String, Object> metadata) throws Exception {
        JsonNode created = callTool("conversation_create", args(
                "agent_id", CONVERSATION_OWNER,
                "session_id", sessionKey,
                "title", title,
                "run_id", sessionKey,
                "metadata", json(metadata.isEmpty() ? Map.of("caseId", sessionKey) : metadata),
                "datapack_id", datapack()));
        String id = created.path("id").asString(null);
        if (id == null) throw new IllegalStateException("conversation_create returned no id: " + created);
        conversations.put(sessionKey, id);
        return id;
    }

    public String datapack() throws Exception {
        if (datapackId != null) return datapackId;
        if (properties.getDatapackId() != null && !properties.getDatapackId().isBlank()) {
            return datapackId = properties.getDatapackId();
        }
        synchronized (this) {
            if (datapackId != null) return datapackId;
            String name = properties.getDatapackName();
            // datapack_list requires a conversation_id even though no datapack is known yet, so it
            // gets a bootstrap conversation created without one. It also rejects agent_id.
            JsonNode bootstrap = callTool("conversation_create", args(
                    "agent_id", CONVERSATION_OWNER,
                    "session_id", CONVERSATION_OWNER + ":datapack-lookup",
                    "title", "Datapack lookup"));
            JsonNode list = callTool("datapack_list", args("conversation_id", bootstrap.path("id").asString()));
            List<String> visible = new ArrayList<>();
            for (JsonNode row : list.values()) {
                String rowName = row.path("datapack_name").asString("");
                visible.add(rowName);
                if (rowName.equalsIgnoreCase(name)) return datapackId = row.path("datapack_id").asString();
            }
            throw new IllegalStateException("No Meko datapack named '" + name + "'. Visible: " + visible);
        }
    }

    // ------------------------------------------------------------------
    // MCP transport
    // ------------------------------------------------------------------

    public JsonNode callTool(String tool, Map<String, Object> arguments) throws Exception {
        ensureInitialized();
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("name", tool);
        params.put("arguments", arguments);
        Duration timeout = Duration.ofSeconds(SLOW.contains(tool) ? 60 : 30);
        JsonNode result;
        try {
            result = rpcResult(tool, post(mcpRequest("tools/call", params), true, timeout));
        } catch (java.io.IOException | IllegalStateException e) {
            boolean serverError = e instanceof IllegalStateException && String.valueOf(e.getMessage()).startsWith("Meko MCP HTTP 5");
            if (!IDEMPOTENT.contains(tool) || (e instanceof IllegalStateException && !serverError)) throw e;
            result = rpcResult(tool, post(mcpRequest("tools/call", params), true, timeout));
        }
        String text = result.path("content").path(0).path("text").asString("");
        if (result.path("isError").asBoolean(false)) {
            throw new IllegalStateException(tool + " rejected the call: " + text);
        }
        JsonNode payload = looksLikeJson(text) ? objectMapper.readTree(text) : StringNode.valueOf(text);
        if (payload.isObject() && payload.has("error")) {
            throw new IllegalStateException(tool + " failed: " + payload.path("error").asString()
                    + (payload.has("detail") ? " — " + payload.path("detail").asString() : ""));
        }
        return payload;
    }

    private void ensureInitialized() throws Exception {
        if (!properties.configured()) throw new IllegalStateException("Meko is not configured");
        if (initialized) return;
        synchronized (this) {
            if (initialized) return;
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("protocolVersion", PROTOCOL_VERSION);
            params.put("capabilities", Map.of());
            params.put("clientInfo", Map.of("name", properties.getClientName(), "version", "0.3.0"));
            HttpResponse<String> response = post(mcpRequest("initialize", params), false);
            rpcResult("initialize", response);
            mcpSessionId = response.headers().firstValue("mcp-session-id").orElse(null);
            post(mcpNotification("notifications/initialized"), true);
            initialized = true;
        }
    }

    private HttpResponse<String> post(String payload, boolean sessionAware) throws Exception {
        return post(payload, sessionAware, Duration.ofSeconds(30));
    }

    private HttpResponse<String> post(String payload, boolean sessionAware, Duration timeout) throws Exception {
        HttpResponse<String> response = send(payload, sessionAware, timeout);
        if (response.statusCode() == 404 && sessionAware && mcpSessionId != null) {
            // The server dropped our MCP session (restart or idle expiry): re-initialise once and retry.
            synchronized (this) {
                initialized = false;
                mcpSessionId = null;
            }
            ensureInitialized();
            response = send(payload, true, timeout);
        }
        if (response.statusCode() >= 300) {
            throw new IllegalStateException("Meko MCP HTTP " + response.statusCode() + ": " + response.body());
        }
        return response;
    }

    private HttpResponse<String> send(String payload, boolean sessionAware, Duration timeout) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(properties.getMcpUrl()))
                .timeout(timeout)
                .header("Authorization", "Bearer " + properties.getApiKey())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8));
        if (sessionAware && mcpSessionId != null) {
            builder.header("mcp-session-id", mcpSessionId);
            builder.header("MCP-Protocol-Version", PROTOCOL_VERSION);
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    /** Extracts the JSON-RPC result from a plain JSON or SSE body. */
    private JsonNode rpcResult(String method, HttpResponse<String> response) throws Exception {
        String body = response.body() == null ? "" : response.body().trim();
        JsonNode message = null;
        if (looksLikeJson(body)) {
            message = objectMapper.readTree(body);
        } else {
            StringBuilder data = new StringBuilder();
            for (String line : body.split("\\R")) {
                if (line.startsWith("data:")) {
                    data.append(line.substring(5).trim());
                } else if (line.isBlank() && !data.isEmpty()) {
                    JsonNode event = objectMapper.readTree(data.toString());
                    data.setLength(0);
                    if (event.has("result") || event.has("error")) message = event;
                }
            }
            if (!data.isEmpty()) {
                JsonNode event = objectMapper.readTree(data.toString());
                if (event.has("result") || event.has("error")) message = event;
            }
        }
        if (message == null) throw new IllegalStateException(method + ": no JSON-RPC response in body: " + abbreviate(body));
        if (message.has("error")) throw new IllegalStateException(method + ": " + message.get("error"));
        return message.path("result");
    }

    // ------------------------------------------------------------------
    // Payload helpers
    // ------------------------------------------------------------------

    /** Search rows live under "results" (memory and knowledge base alike). */
    public List<JsonNode> rows(JsonNode payload) {
        JsonNode results = payload.path("results");
        List<JsonNode> out = new ArrayList<>();
        if (results.isArray()) results.values().forEach(out::add);
        return out;
    }

    /** The record text, without depending on the field name (memory_search uses "memory", KB chunks "chunk_text"). */
    public String rowText(JsonNode row) {
        for (String name : List.of("memory", "chunk_text", "data", "text", "content")) {
            String value = row.path(name).asString("");
            if (!value.isBlank()) return value;
        }
        String longest = null;
        for (String name : row.propertyNames()) {
            if (ROW_METADATA.contains(name)) continue;
            String value = row.path(name).asString("");
            if (longest == null || value.length() > longest.length()) longest = value;
        }
        return longest != null && longest.length() >= 24 ? longest : null;
    }

    public String json(Object value) {
        return objectMapper.writeValueAsString(value);
    }

    public ObjectMapper objectMapper() {
        return objectMapper;
    }

    public Map<String, Object> args(Object... values) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) {
            if (values[i + 1] != null) map.put(String.valueOf(values[i]), values[i + 1]);
        }
        return map;
    }

    private static boolean looksLikeJson(String text) {
        return text.startsWith("{") || text.startsWith("[");
    }

    private static String abbreviate(String text) {
        return text.length() <= 300 ? text : text.substring(0, 300) + "...";
    }

    private String mcpRequest(String method, Map<String, Object> params) {
        return objectMapper.writeValueAsString(Map.of(
                "jsonrpc", "2.0",
                "id", requestIds.getAndIncrement(),
                "method", method,
                "params", params));
    }

    private String mcpNotification(String method) {
        return objectMapper.writeValueAsString(Map.of("jsonrpc", "2.0", "method", method));
    }
}
