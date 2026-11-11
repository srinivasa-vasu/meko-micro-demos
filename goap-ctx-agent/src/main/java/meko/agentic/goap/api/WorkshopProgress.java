package meko.agentic.goap.api;

import meko.agentic.client.MekoMcpClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The workshop's progress, kept in Meko as the release demo keeps its records, but in a datapack of its own: the
 * participant's progress datapack ({@code meko-workshop-progress-…}), created once and found again by name. The agents
 * never search it, so a stage's run cannot find an earlier stage's record as context, and the first stage stays empty.
 * <p>
 * One record there for the participant: a single conversation, since conversations count against an account's limit.
 * Each run the page records is a turn on it, tagged with the workshop datapack it ran in, and the run itself an artifact
 * the turn names: the page's progress is read back from the record, so another browser, a restart or cleared storage
 * shows the same runs. A new workshop datapack has no turns yet, so it starts empty.
 */
@Component
public class WorkshopProgress {
    static final String PREFIX = "meko-workshop-progress-";
    private static final String FOLDER = "Workshop progress";
    private static final String FOLDER_DESCRIPTION = "Each participant's workshop progress: the runs of every use case, by workshop datapack. "
            + "Kept apart from the workshop datapacks, so no agent ever reads it as context.";

    private final MekoMcpClient meko;
    private volatile String datapackId;
    /** The participant's record: one conversation in the progress datapack, for every workshop datapack. */
    private volatile String record;
    /** What was read back, by workshop datapack: dropped when that datapack records something new. */
    private final Map<String, Map<String, Object>> read = new ConcurrentHashMap<>();

    public WorkshopProgress(MekoMcpClient meko) {
        this.meko = meko;
    }

    /** Whether progress can be kept in Meko: a key, so a datapack of its own can be found or created. */
    public boolean available() {
        return meko.keyed();
    }

    /** The participant's progress datapack: the one this key owns by the prefix, else a new one, filed in the project. */
    synchronized String datapack() throws Exception {
        if (datapackId != null) return datapackId;
        for (Map<String, String> d : meko.listDatapacks()) {
            if (d.get("name").startsWith(PREFIX) && "owner".equals(d.getOrDefault("grant", "owner"))) return datapackId = d.get("id");
        }
        String name = PREFIX + java.time.LocalDate.now().toString().replace("-", "") + "-"
                + Integer.toString(java.util.concurrent.ThreadLocalRandom.current().nextInt(36 * 36 * 36 * 36), 36);
        String folder = null;
        try {
            folder = meko.projectFolder(WorkshopController.PROJECT, WorkshopController.PROJECT_DESCRIPTION, FOLDER, FOLDER_DESCRIPTION);
        } catch (Exception e) {
            // Filing is a convenience: the progress datapack works unfiled.
        }
        return datapackId = meko.createDatapack(name, folder);
    }

    /** The participant's record: created once, and found again by its session id (Meko keeps one conversation per id). */
    private synchronized String record() throws Exception {
        if (record != null) return record;
        JsonNode created = meko.callTool("conversation_create", meko.args(
                "agent_id", meko.conversationOwner(),
                "session_id", "progress",
                "title", "Workshop progress",
                "datapack_id", datapack()));
        String id = firstId(created);
        if (id == null) throw new IllegalStateException("conversation_create gave no id for the progress record: " + created);
        return record = id;
    }

    /**
     * Records one run of a use case's stage (or its audit): the run whole, as an artifact, and a turn on the workshop
     * datapack's record naming it. The latest turn for a stage is the one the page shows.
     */
    public synchronized void save(String context, String contextName, String useCase, int stage, String kind, Object run) throws Exception {
        String conversation = record();
        String filename = useCase + "-stage-" + stage + "-" + kind + ".json";
        JsonNode stored = meko.callTool("artifact_put", meko.args(
                "filename", filename,
                "content_base64", Base64.getEncoder().encodeToString(meko.objectMapper().writeValueAsBytes(run)),
                "content_type", "application/json",
                "conversation_id", conversation,
                "agent_id", meko.conversationOwner(),
                "datapack_id", datapack()));
        String hash = stored.path("content_hash").asString(null);
        if (hash == null) throw new IllegalStateException("artifact_put gave no content hash: " + stored);
        // The input is a tag, not a sentence: Meko extracts facts from a turn's input, and a record is no one's instruction.
        meko.callTool("conversation_add_message", meko.args(
                "conversation_id", conversation,
                "agent_id", meko.conversationOwner(),
                "input", (contextName == null || contextName.isBlank() ? context : contextName) + " · " + useCase + " · stage " + stage + " · " + kind,
                "output", "Recorded " + filename + " (" + hash + ").",
                "metadata", meko.json(Map.of("context", context, "useCase", useCase, "stage", stage, "kind", kind, "hash", hash)),
                "datapack_id", datapack()));
        read.remove(context);
    }

    /** Forgets what was read for a workshop datapack that was deleted: its turns stay on the record, unread. */
    public void forget(String context) {
        read.remove(context);
    }

    /**
     * A workshop datapack's progress, read back from its record: for each use case, the latest run of each stage and
     * of each audit, as recorded. Empty for a datapack with no record yet.
     */
    public Map<String, Object> load(String context) throws Exception {
        Map<String, Object> cached = read.get(context);
        if (cached != null) return cached;
        Map<String, Map<String, Object>> runs = new LinkedHashMap<>(), audits = new LinkedHashMap<>();
        String conversation = record();
        {
            JsonNode got = meko.callTool("conversation_get", meko.args("conversation_id", conversation,
                    "agent_id", meko.conversationOwner(), "include_messages", true, "datapack_id", datapack()));
            // The latest turn for each use case, stage and kind: later turns replace earlier ones.
            Map<String, String[]> latest = new LinkedHashMap<>();
            for (JsonNode message : got.path("messages").values()) {
                JsonNode m = metadata(message);
                String useCase = m.path("useCase").asString(""), kind = m.path("kind").asString(""), hash = m.path("hash").asString("");
                // Only this workshop datapack's runs: the record holds every one of the participant's.
                if (useCase.isBlank() || hash.isBlank() || !context.equals(m.path("context").asString(""))) continue;
                latest.put(useCase + "|" + m.path("stage").asInt() + "|" + kind, new String[]{useCase, String.valueOf(m.path("stage").asInt()), kind, hash});
            }
            for (String[] at : latest.values()) {
                JsonNode artifact = meko.callTool("artifact_get", meko.args("content_hash", at[3], "conversation_id", conversation,
                        "agent_id", meko.conversationOwner(), "datapack_id", datapack()));
                String b64 = artifact.path("content_base64").asString(null);
                if (b64 == null) continue;
                Object run = meko.objectMapper().readValue(new String(Base64.getDecoder().decode(b64), StandardCharsets.UTF_8), Object.class);
                (at[2].equals("audit") ? audits : runs).computeIfAbsent(at[0], k -> new LinkedHashMap<>()).put(at[1], run);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("available", true);
        out.put("runs", runs);
        out.put("audits", audits);
        read.put(context, out);
        return out;
    }

    private JsonNode metadata(JsonNode message) {
        JsonNode metadata = message.path("metadata");
        if (metadata.isString()) {
            try {
                return meko.objectMapper().readTree(metadata.asString());
            } catch (Exception e) {
                return meko.objectMapper().createObjectNode();
            }
        }
        return metadata;
    }

    private static String firstId(JsonNode node) {
        for (String path : List.of("conversation_id", "id")) {
            if (node.path(path).isString()) return node.path(path).asString();
        }
        JsonNode inner = node.path("conversation");
        return inner.path("id").isString() ? inner.path("id").asString() : null;
    }

    /** The datapacks this key owns that hold progress: never offered as a workshop datapack. */
    static boolean isProgress(String name) {
        return name != null && name.startsWith(PREFIX);
    }
}
