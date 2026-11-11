package meko.agentic.client;

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
public class MekoMcpClient {
    private static final String PROTOCOL_VERSION = "2025-06-18";
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
    private static final java.util.Set<String> IDEMPOTENT = java.util.Set.of("memory_search", "knowledgebase_search", "conversation_get", "artifact_get",
            "knowledgebase_document_status");
    /**
     * Per-tool timeouts. Calls that make model calls on Meko's side (fact extraction) get longer than the default;
     * conversation_get (about 1.4 s measured) is retried once, so it gets a short one.
     */
    private static final Map<String, Integer> TIMEOUT_SECONDS = Map.of("memory_add", 60, "memory_update", 60, "conversation_get", 15,
            // AMP calls block until the control plane finishes; the compute create can outlast a gateway (504).
            "amp_cluster_create", 300, "amp_compute_create", 300, "amp_branch_delete", 120, "amp_cluster_delete", 180);
    private final AtomicLong requestIds = new AtomicLong(1);
    private volatile boolean initialized;
    private volatile String mcpSessionId;
    private volatile String datapackId;
    private volatile String lastError;
    /** The datapack-lookup conversation (datapack_list and datapack_create need one): datapack-independent, so kept. */
    private volatile String lookupConversationId;
    /** When the datapack in use was last confirmed to exist, so a status poll does not list datapacks every time. */
    private volatile long confirmedAt;
    private static final long CONFIRM_TTL_MILLIS = 20_000;
    /** Run before the datapack in use changes: writes still queued for the old one finish first, caches are dropped. */
    private final List<Runnable> beforeSwitch = new java.util.concurrent.CopyOnWriteArrayList<>();

    public void beforeDatapackSwitch(Runnable hook) {
        beforeSwitch.add(hook);
    }

    /**
     * Owner of this application's record conversations ({@code meko.conversation-owner}). conversation_add_message must
     * use the agent_id that created the conversation, so each turn names its real writer in metadata.
     */
    public String conversationOwner() {
        return properties.getConversationOwner();
    }

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
        if (Boolean.TRUE.equals(status.get("connected"))) status.put("knowledge", cachedKnowledgeCounts());
        status.put("lastError", lastError);
        return status;
    }

    /**
     * What the datapack's shared knowledge holds: documents uploaded through the Meko app, and memories promoted by
     * agents. Null values mean the datapack status endpoint did not say.
     */
    /** The document counts change only on an upload or a promotion: kept for 15 s, or until {@link #forgetKnowledgeCounts}. */
    private volatile Map<String, Object> counts;
    private volatile String countsFor;
    private volatile long countsAt;

    private Map<String, Object> cachedKnowledgeCounts() {
        String id;
        try {
            id = datapack();
        } catch (Exception e) {
            return Map.of("error", String.valueOf(e.getMessage()));
        }
        Map<String, Object> cached = counts;
        if (cached != null && id.equals(countsFor) && System.currentTimeMillis() - countsAt < 15_000) return cached;
        Map<String, Object> fresh = knowledgeCounts();
        if (!fresh.containsKey("error")) {
            counts = fresh;
            countsFor = id;
            countsAt = System.currentTimeMillis();
        }
        return fresh;
    }

    public void forgetKnowledgeCounts() {
        counts = null;
    }

    private Map<String, Object> knowledgeCounts() {
        Map<String, Object> counts = new LinkedHashMap<>();
        try {
            JsonNode described = callTool("datapack_describe", args(
                    "datapack_id", datapack(),
                    "include_status", true,
                    "conversation_id", conversation(conversationOwner() + ":status", "Datapack status", Map.of())));
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
    /** Bumped when the datapack changes: a conversation created for the old one is not kept for the new one. */
    private volatile long generation;

    private void forgetConversations() {
        generation++;
        conversations.clear();
        creating.clear();
    }

    /** Keeps a created conversation only if the datapack it was created in is still the one in use. */
    private void keep(String sessionKey, String id, long createdIn) {
        if (generation == createdIn) conversations.put(sessionKey, id);
    }

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

    /**
     * A conversation owned by {@code agentId} rather than this application's records owner: a harness's session, so the
     * facts Meko extracts from it are attributed to that harness. One create per key, as {@link #conversation}.
     */
    public String conversationAs(String sessionKey, String agentId, String title) throws Exception {
        String existing = conversations.get(sessionKey);
        if (existing != null) return existing;
        synchronized (creating.computeIfAbsent(sessionKey, k -> new Object())) {
            existing = conversations.get(sessionKey);
            if (existing != null) return existing;
            long createdIn = generation;
            JsonNode created = callTool("conversation_create", args(
                    "agent_id", agentId,
                    "session_id", sessionKey,
                    "title", title,
                    "run_id", sessionKey,
                    "metadata", json(Map.of("session", sessionKey, "harness", agentId)),
                    "datapack_id", datapack()));
            String id = created.path("id").asString(null);
            if (id == null) throw new IllegalStateException("conversation_create returned no id: " + created);
            keep(sessionKey, id, createdIn);
            return id;
        }
    }

    /** The conversation already created for {@code sessionKey}, or null. */
    public String conversationIfKnown(String sessionKey) {
        return conversations.get(sessionKey);
    }

    private String create(String sessionKey, String title, Map<String, Object> metadata) throws Exception {
        long createdIn = generation;
        JsonNode created = callTool("conversation_create", args(
                "agent_id", conversationOwner(),
                "session_id", sessionKey,
                "title", title,
                "run_id", sessionKey,
                "metadata", json(metadata.isEmpty() ? Map.of("caseId", sessionKey) : metadata),
                "datapack_id", datapack()));
        String id = created.path("id").asString(null);
        if (id == null) throw new IllegalStateException("conversation_create returned no id: " + created);
        keep(sessionKey, id, createdIn);
        return id;
    }

    public boolean keyed() {
        return properties.keyed();
    }

    /**
     * Switches to another datapack, creating it first if asked (the workshop: each participant starts from a fresh,
     * empty datapack). Conversations are per datapack, so the ones this client knew are forgotten.
     */
    public synchronized String useDatapack(String name, boolean create) throws Exception {
        return useDatapack(name, create, null);
    }

    /**
     * As {@link #useDatapack(String, boolean)}; with {@code knownId}, an existing datapack whose id the caller already
     * has (the workshop's per-use-case datapacks, switched between tabs) is used without looking it up by name. A
     * wrong id is caught like a deleted datapack, by {@link #datapackStillExists}.
     */
    public String useDatapack(String name, boolean create, String knownId) throws Exception {
        return useDatapack(name, create, knownId, null);
    }

    /** As {@link #useDatapack(String, boolean, String)}; a datapack created with {@code folderId} is created straight into that project folder. */
    public String useDatapack(String name, boolean create, String knownId, String folderId) throws Exception {
        if (!properties.keyed()) throw new IllegalStateException("No Meko API key: set MEKO_API_KEY and restart.");
        // Writes still queued for the datapack in use finish first, outside this client's lock.
        beforeSwitch.forEach(Runnable::run);
        return switchTo(name, create, knownId, folderId);
    }

    /**
     * Creates a datapack without switching to it (the organization's shared one, created for a solo run), straight into
     * {@code folderId} if given. Returns its id.
     */
    public String createDatapack(String name, String folderId) throws Exception {
        listed = null;
        JsonNode result = callTool("datapack_create", args("name", name, "folder_id", folderId, "conversation_id", lookupConversation()));
        for (String path : List.of("datapack_id", "id")) {
            if (result.path(path).isString()) return result.path(path).asString();
        }
        if (result.path("datapack").path("id").isString()) return result.path("datapack").path("id").asString();
        for (Map<String, String> row : listDatapacks()) if (name.equals(row.get("name"))) return row.get("id");
        throw new IllegalStateException("datapack_create returned no id for " + name + ": " + result);
    }

    private synchronized String switchTo(String name, boolean create, String knownId, String folderId) throws Exception {
        String created = null;
        if (create) {
            listed = null;   // the account now holds one more
            JsonNode result = callTool("datapack_create", args("name", name, "folder_id", folderId, "conversation_id", lookupConversation()));
            for (String path : List.of("datapack_id", "id")) {
                if (result.path(path).isString()) created = result.path(path).asString();
            }
            if (created == null && result.path("datapack").path("id").isString()) created = result.path("datapack").path("id").asString();
        }
        properties.setDatapackName(name);
        properties.setDatapackId(null);
        forgetConversations();
        // A known id applies to an existing datapack only; a new one is resolved by name if its create gave no id.
        datapackId = created != null ? created : (create || knownId == null || knownId.isBlank() ? null : knownId);
        confirmedAt = System.currentTimeMillis();
        lastError = null;
        // A datapack just created may take a moment to appear in the listing the name is resolved from.
        for (int attempt = 0; ; attempt++) {
            try {
                return datapack();
            } catch (IllegalStateException e) {
                if (!create || attempt >= 4) throw e;
                Thread.sleep(1_000);
            }
        }
    }

    /**
     * Whether the datapack in use still exists in this account. A datapack can be deleted in the Meko app while this
     * application runs; then it is forgotten (with its conversations), so the next status asks for a datapack again.
     */
    public boolean datapackStillExists() {
        String id = datapackId;
        if (id == null || !properties.keyed()) return true;
        // Confirmed moments ago: a status poll does not list every datapack again.
        if (System.currentTimeMillis() - confirmedAt < CONFIRM_TTL_MILLIS) return true;
        // The listing runs outside this client's lock, so a slow datapack_list never holds up a session re-initialisation.
        try {
            JsonNode list = callTool("datapack_list", args("conversation_id", lookupConversation()));
            for (JsonNode row : list.values()) {
                if (id.equals(row.path("datapack_id").asString(""))) {
                    confirmedAt = System.currentTimeMillis();
                    return true;
                }
            }
        } catch (Exception e) {
            return true; // unreachable is not the same as deleted: keep it
        }
        beforeSwitch.forEach(Runnable::run);   // outside the lock, as in useDatapack
        synchronized (this) {
            if (!id.equals(datapackId)) return true; // switched meanwhile: the new one stands
            forgetDeleted();
        }
        return false;
    }

    private void forgetDeleted() {
        listed = null;
        datapackId = null;
        properties.setDatapackId(null);
        properties.setDatapackName(null);
        forgetConversations();
        lastError = "The datapack in use no longer exists: create or choose another.";
    }

    /**
     * Deletes a datapack, with everything in it: its memory, knowledge base and conversations (which count against the
     * account's limits). The datapack in use is forgotten when it is the one deleted: a new one is chosen next.
     */
    public void deleteDatapack(String id) throws Exception {
        boolean inUse;
        synchronized (this) {
            inUse = id.equals(datapackId);
        }
        // Writes still queued for the datapack in use finish first, as before a switch.
        if (inUse) beforeSwitch.forEach(Runnable::run);
        callTool("datapack_delete", args("datapack_id", id, "conversation_id", lookupConversation()));
        synchronized (this) {
            listed = null;
            if (id.equals(datapackId)) {
                datapackId = null;
                properties.setDatapackId(null);
                properties.setDatapackName(null);
                forgetConversations();
                lastError = null;
            }
        }
    }

    /** Forgets when the datapack was last confirmed, so the next check asks Meko again. */
    public void expireConfirmation() {
        confirmedAt = 0;
    }

    /** The last listing, and when: a choice made from a list just shown is checked against it, not a new listing. */
    private volatile List<Map<String, String>> listed;
    private volatile long listedAt;

    /** The datapacks this key can see, as last listed if that was at most {@code maxAgeMillis} ago. */
    public List<Map<String, String>> listDatapacks(long maxAgeMillis) throws Exception {
        List<Map<String, String>> last = listed;
        if (last != null && System.currentTimeMillis() - listedAt <= maxAgeMillis) return last;
        return listDatapacks();
    }

    /** The datapacks this key can see: name, id and when each was created. */
    public synchronized List<Map<String, String>> listDatapacks() throws Exception {
        if (!properties.keyed()) return List.of();
        JsonNode list = callTool("datapack_list", args("conversation_id", lookupConversation()));
        List<Map<String, String>> out = new ArrayList<>();
        for (JsonNode row : list.values()) {
            // Shared datapacks are listed too, with this key's grant on them and who owns them.
            out.add(Map.of("name", row.path("datapack_name").asString(""), "id", row.path("datapack_id").asString(""),
                    "created", row.path("created_at").asString(""), "grant", row.path("grant").asString(""),
                    "owner", row.path("datapack_owner_name").asString("")));
        }
        listed = List.copyOf(out);
        listedAt = System.currentTimeMillis();
        return out;
    }

    /** Project and folder ids already found or created, by "project" and "project/folder": one lookup per start. */
    private final Map<String, String> projectIds = new ConcurrentHashMap<>();
    /** Names a project was created under before: an owned project still called one of them is renamed, not duplicated. */
    private final Map<String, List<String>> formerNames = new ConcurrentHashMap<>();

    /** Records the names {@code project} had before, so the project already holding the datapacks is kept, under its new name. */
    public void formerlyNamed(String project, List<String> names) {
        formerNames.put(project, List.copyOf(names));
    }

    /**
     * Files a datapack into a folder of one of this key's own projects, creating the project and the folder the first
     * time. Only projects this key owns are looked at: a shared project of the same name is never written to.
     */
    public synchronized void fileInProject(String datapackId, String project, String projectDescription,
                                           String folder, String folderDescription) throws Exception {
        String folderId = projectFolder(project, projectDescription, folder, folderDescription);
        try {
            callTool("project_add_datapack", args("datapack_id", datapackId, "folder_id", folderId, "conversation_id", lookupConversation()));
        } catch (Exception e) {
            forgetProject(project);
            throw e;
        }
    }

    /** Forgets a project's ids: it may have been deleted in the Meko app, so it is looked up afresh next time. */
    public void forgetProject(String project) {
        projectIds.keySet().removeIf(k -> k.equals(project) || k.startsWith(project + "/"));
    }

    /**
     * Looks up an owned project and its folders, creating nothing, so a later {@link #projectFolder} for a folder that
     * exists costs no call. A warm-up: run before the participant creates a datapack.
     */
    public synchronized void findProject(String project) throws Exception {
        if (projectIds.containsKey(project)) return;
        String conversation = lookupConversation();
        String projectId = ownedOrRenamed(project, null, conversation);
        if (projectId == null) return;
        projectIds.put(project, projectId);
        for (JsonNode row : callTool("project_describe", args("project_id", projectId, "conversation_id", conversation)).path("folders").values()) {
            String id = row.path("folder_id").asString("");
            if (!id.isBlank()) projectIds.put(project + "/" + row.path("folder_name").asString(""), id);
        }
    }

    /** The id of a folder in one of this key's own projects, creating the project and the folder the first time. */
    public synchronized String projectFolder(String project, String projectDescription,
                                             String folder, String folderDescription) throws Exception {
        String conversation = lookupConversation();
        String projectId = projectIds.get(project);
        if (projectId == null) {
            projectId = ownedOrRenamed(project, projectDescription, conversation);
            if (projectId == null) {
                JsonNode created = callTool("project_create", args("project_name", project, "description", projectDescription,
                        "folders", List.of(folder), "conversation_id", conversation));
                projectId = firstText(created, "project_id", "id");
                if (projectId == null) projectId = firstText(created.path("project"), "project_id", "id");
                if (projectId == null) projectId = ownedProject(project, conversation);
                if (projectId == null) throw new IllegalStateException("project_create gave no id for " + project + ": " + created);
            }
            projectIds.put(project, projectId);
        }
        String folderId = projectIds.get(project + "/" + folder);
        if (folderId == null) {
            folderId = folderIn(projectId, folder, conversation);
            if (folderId == null) {
                JsonNode created = callTool("project_folder_create", args("project_id", projectId, "folder_name", folder,
                        "description", folderDescription, "conversation_id", conversation));
                folderId = firstText(created, "folder_id", "id");
                if (folderId == null) folderId = firstText(created.path("folder"), "folder_id", "id");
                if (folderId == null) folderId = folderIn(projectId, folder, conversation);
                if (folderId == null) throw new IllegalStateException("project_folder_create gave no id for " + folder + ": " + created);
            }
            projectIds.put(project + "/" + folder, folderId);
        }
        return folderId;
    }

    /** An owned project by its name; failing that, one still under a former name, renamed (and re-described) to the current one. */
    private String ownedOrRenamed(String project, String description, String conversation) throws Exception {
        String id = ownedProject(project, conversation);
        if (id != null) return id;
        for (String former : formerNames.getOrDefault(project, List.of())) {
            id = ownedProject(former, conversation);
            if (id == null) continue;
            Map<String, Object> update = new java.util.LinkedHashMap<>(Map.of("project_id", id, "project_name", project, "conversation_id", conversation));
            if (description != null) update.put("description", description);
            callTool("project_update", update);
            return id;
        }
        return null;
    }

    private String ownedProject(String name, String conversation) throws Exception {
        for (JsonNode row : callTool("project_list", args("conversation_id", conversation)).path("owned").values()) {
            if (name.equals(row.path("project_name").asString(""))) return row.path("project_id").asString();
        }
        return null;
    }

    private String folderIn(String projectId, String folder, String conversation) throws Exception {
        JsonNode described = callTool("project_describe", args("project_id", projectId, "conversation_id", conversation));
        for (JsonNode row : described.path("folders").values()) {
            if (folder.equals(row.path("folder_name").asString(""))) return row.path("folder_id").asString();
        }
        return null;
    }

    private static String firstText(JsonNode node, String... names) {
        for (String name : names) {
            if (node.path(name).isString() && !node.path(name).asString().isBlank()) return node.path(name).asString();
        }
        return null;
    }

    /** datapack_list and datapack_create need a conversation id even though no datapack is chosen yet. */
    private String lookupConversation() throws Exception {
        String id = lookupConversationId;
        if (id != null) return id;
        id = callTool("conversation_create", args(
                "agent_id", conversationOwner(),
                "session_id", conversationOwner() + ":datapack-lookup",
                "title", "Datapack lookup")).path("id").asString();
        if (!id.isBlank()) lookupConversationId = id;
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
            JsonNode list = callTool("datapack_list", args("conversation_id", lookupConversation()));
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
        Duration timeout = timeoutFor(tool);
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

    /** A tool the server offers: its name, what it says it does, and its input schema as JSON. */
    public record Tool(String name, String description, String inputSchema) {}

    /**
     * One call exactly as made: the arguments sent, and what came back verbatim. {@code raw} is the tool's content as
     * the server sent it (a JSON array of content items); {@code ok} is false when the server marked the result an
     * error ({@code error} then holds its text) or the call never got an answer ({@code raw} is then null).
     */
    public record RawCall(String tool, Map<String, Object> arguments, boolean ok, String error, String raw, long millis) {}

    private volatile List<Tool> tools;

    /** The tools the server offers, with their schemas (listed once per MCP session). */
    public List<Tool> tools() throws Exception {
        ensureInitialized();
        List<Tool> known = tools;
        if (known != null) return known;
        List<Tool> out = new ArrayList<>();
        String cursor = null;
        do {
            Map<String, Object> params = new LinkedHashMap<>();
            if (cursor != null) params.put("cursor", cursor);
            JsonNode result = rpcResult("tools/list", post(mcpRequest("tools/list", params), true));
            for (JsonNode t : result.path("tools").values()) {
                out.add(new Tool(t.path("name").asString(""), t.path("description").asString(""),
                        t.path("inputSchema").isMissingNode() ? "{}" : objectMapper.writeValueAsString(t.path("inputSchema"))));
            }
            cursor = result.path("nextCursor").asString(null);
        } while (cursor != null && !cursor.isBlank());
        out.sort(java.util.Comparator.comparing(Tool::name));
        return tools = List.copyOf(out);
    }

    /**
     * Calls a tool with exactly these arguments (nothing is added, nothing retried) and reports what came back,
     * verbatim. Never throws: a refused or failed call is a result like any other. For consoles and probes; agents use
     * {@link #callTool}, which unwraps the payload and throws on an error.
     */
    public RawCall raw(String tool, Map<String, Object> arguments) {
        long started = System.currentTimeMillis();
        try {
            ensureInitialized();
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("name", tool);
            params.put("arguments", arguments);
            Duration timeout = timeoutFor(tool);
            JsonNode result = rpcResult(tool, post(mcpRequest("tools/call", params), true, timeout));
            String raw = objectMapper.writeValueAsString(result.path("content"));
            boolean failed = result.path("isError").asBoolean(false);
            String error = failed ? result.path("content").path(0).path("text").asString("the server marked the result an error") : null;
            return new RawCall(tool, arguments, !failed, error, raw, System.currentTimeMillis() - started);
        } catch (Exception e) {
            return new RawCall(tool, arguments, false, String.valueOf(e.getMessage()), null, System.currentTimeMillis() - started);
        }
    }

    /** How long a call to {@code tool} is waited for: the application's setting, else this client's default. */
    private Duration timeoutFor(String tool) {
        Duration configured = properties.getTimeouts().get(tool);
        return configured != null ? configured : Duration.ofSeconds(TIMEOUT_SECONDS.getOrDefault(tool, 30));
    }

    private void ensureInitialized() throws Exception {
        // A key is enough to connect: the datapack may be chosen later (the workshop creates it from its page).
        if (!properties.keyed()) throw new IllegalStateException("Meko is not configured: set MEKO_API_KEY");
        if (initialized) return;
        synchronized (this) {
            if (initialized) return;
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("protocolVersion", PROTOCOL_VERSION);
            params.put("capabilities", Map.of());
            params.put("clientInfo", Map.of("name", properties.getClientName(), "version", "0.1.0"));
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
                tools = null;
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

    /**
     * An upload to the datapack's knowledge base: its document key (for {@link #documentStatus}), and whether Meko
     * already held identical content (then nothing was sent).
     */
    public record Upload(String key, boolean alreadyUploaded) {}

    /**
     * Uploads a file to the knowledge base of the datapack in use. Meko issues a presigned storage URL for exactly these
     * bytes (their size and SHA-256, with the first 3,072 bytes to check the type against the extension); the file is
     * then posted there as a form, Meko's fields first and the file last. Meko registers and indexes it on its own;
     * {@link #documentStatus} follows that. Identical content already in the datapack is not sent again.
     */
    public Upload uploadKnowledgeDocument(String conversationId, String agentId, String filename, byte[] bytes) throws Exception {
        return uploadKnowledgeDocument(conversationId, agentId, filename, bytes, datapack());
    }

    /** As {@link #uploadKnowledgeDocument(String, String, String, byte[])}, into the knowledge base of {@code datapackId}. */
    public Upload uploadKnowledgeDocument(String conversationId, String agentId, String filename, byte[] bytes, String datapackId) throws Exception {
        String hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        byte[] head = java.util.Arrays.copyOf(bytes, Math.min(3072, bytes.length));
        JsonNode issued = callTool("knowledgebase_upload_document", args(
                "filename", filename,
                "size", bytes.length,
                "content_hash", hash,
                "content_head_base64", java.util.Base64.getEncoder().encodeToString(head),
                "conversation_id", conversationId,
                "agent_id", agentId,
                "datapack_id", datapackId));
        String key = issued.path("key").asString("");
        if (issued.path("already_uploaded").asBoolean(false)) return new Upload(key, true);
        String url = issued.path("url").asString("");
        if (url.isBlank()) throw new IllegalStateException("knowledgebase_upload_document issued no upload URL for " + filename);
        postForm(url, issued.path("fields"), filename, bytes);
        return new Upload(key, false);
    }

    /** The upload and indexing state of a document uploaded with {@link #uploadKnowledgeDocument}, as Meko reports it. */
    public JsonNode documentStatus(String conversationId, String agentId, String documentKey) throws Exception {
        return callTool("knowledgebase_document_status", args(
                "document_key", documentKey,
                "conversation_id", conversationId,
                "agent_id", agentId,
                "datapack_id", datapack()));
    }

    /** A multipart form POST to presigned storage: every field in the order given, then the file, named "file". */
    protected void postForm(String url, JsonNode fields, String filename, byte[] bytes) throws Exception {
        String boundary = "----meko-" + java.util.UUID.randomUUID();
        java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
        java.util.function.Consumer<String> write = text -> body.writeBytes(text.getBytes(StandardCharsets.UTF_8));
        for (String name : fields.propertyNames()) {
            write.accept("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n"
                    + fields.path(name).asString("") + "\r\n");
        }
        write.accept("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + filename.replace("\"", "").replace("\r", "").replace("\n", "")
                + "\"\r\nContent-Type: application/octet-stream\r\n\r\n");
        body.writeBytes(bytes);
        write.accept("\r\n--" + boundary + "--\r\n");
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                .build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("Uploading " + filename + " to storage failed: HTTP " + response.statusCode()
                    + " " + abbreviate(response.body() == null ? "" : response.body()));
        }
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
