package meko.agentic.release.agent;

import meko.agentic.release.amp.AmpDatabase;
import meko.agentic.client.MekoMcpClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Everything the release agent reads from and writes to Meko: the standard and policy (shared knowledge), other teams'
 * incidents and critical queries (their memory), the pull request's record (a conversation keyed by the pull request
 * id) and its evidence (an artifact), and the lessons it learns (memory, which the datapack owner can promote).
 */
@Component
public class ReleaseMemory {
    public static final String AGENT_ID = "release-mgmt:rehearsal-agent";
    /** The pull request every rehearsal in the workshop is about. */
    public static final String PR = "PR-4821";
    public static final List<String> KNOWLEDGE = List.of("zero-downtime-migration-standard.md", "orders-release-policy.md");

    /** A rule id opens a rule; the rule runs to the next id. Meko's ingestion keeps the words but not the line breaks. */
    private static final Pattern RULE_ID = Pattern.compile("\\b((?:MIG|REL)-\\d{2})\\b");
    private static final Pattern RUNBOOK_ID = Pattern.compile("\\b(REC-\\d{2})\\b");
    private static final Pattern LSN = Pattern.compile("\\b([0-9A-F]{1,8}/[0-9A-F]{1,8})\\b");
    public static final String SCHEDULER = "platform-batch:scheduler-agent";
    public static final String SRE = "platform-sre:incident-agent";
    public static final String RUNBOOK = "data-repair-runbook.md";
    private static final Pattern LOCK_TIMEOUT = Pattern.compile("lock_timeout (?:to|=) '?(\\d+\\s*m?s)\\b");
    private static final Pattern ATTEMPTS = Pattern.compile("up to (\\d+) attempts");
    private static final Pattern STALL = Pattern.compile("stalls? for more than (\\d+) s");
    private static final Pattern REGRESSION = Pattern.compile("more than (\\d+) times the buffers");
    private static final String RECALL_QUERY = "migration standard and release policy rules for the orders table: lock timeout, "
            + "volatile defaults, constraints, indexes, rollback, approval; incidents and critical queries on the orders table";

    private final MekoMcpClient meko;
    private final OrgContext org;
    /** Lessons this application wrote, by memory id: what the promote step offers. */
    private final Map<String, String> lessons = new java.util.concurrent.ConcurrentHashMap<>();
    /** The gist of every lesson written since this application started (promoted ones included), so none is written twice. */
    private final java.util.Set<String> written = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public ReleaseMemory(MekoMcpClient meko, OrgContext org) {
        this.meko = meko;
        this.org = org;
        meko.beforeDatapackSwitch(() -> {
            lessons.clear();
            written.clear();
        });
    }

    /** The incident the recovery use case is about. */
    public static final String INCIDENT = "INC-3107";

    /**
     * A record: one conversation, found again by its id alone. A pull request's record ({@code PR-…}) or an incident's
     * ({@code INC-…}).
     */
    private String record(String id) throws Exception {
        boolean incident = id.startsWith("INC-");
        return meko.conversation((incident ? "incident:" : "release:") + id, (incident ? "Incident record " : "Release record ") + id,
                Map.of(incident ? "incident" : "pr", id));
    }

    // ------------------------------------------------------------------
    // Reads
    // ------------------------------------------------------------------

    /** A datapack the agent reads: its own, or the organization's shared one. */
    private record Source(String id, String name) {}

    private List<Source> sources() throws Exception {
        List<Source> out = new ArrayList<>(List.of(new Source(meko.datapack(), meko.datapackName())));
        OrgContext.Shared shared = org.shared();
        if (shared != null && !shared.id().equals(meko.datapack())) out.add(new Source(shared.id(), shared.name()));
        return out;
    }

    /**
     * Everything the agent knows before it touches a database: one {@code context_search} per datapack it reads (its
     * own, and the organization's shared one if connected), which returns the knowledge base and memory side by side.
     * Every fact keeps the datapack it came from and the agent that wrote it.
     */
    public ReleaseModels.ReleaseContext recall(String runId, String prId, Consumer<String> log) throws Exception {
        String conversation = record(prId);
        Map<String, ReleaseModels.Rule> rules = new LinkedHashMap<>();
        List<ReleaseModels.Incident> incidents = new ArrayList<>();
        List<ReleaseModels.CriticalQuery> queries = new ArrayList<>();
        List<ReleaseModels.SetAside> setAside = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Source source : sources()) {
            JsonNode found = meko.callTool("context_search", meko.args("query", RECALL_QUERY, "conversation_id", conversation,
                    "agent_id", AGENT_ID, "datapack_id", source.id(), "limit", 20));
            for (JsonNode chunk : found.path("results").path("knowledge_base").values()) {
                String text = meko.rowText(chunk);
                if (text == null) continue;
                rules(text, source.name()).forEach(rules::putIfAbsent);
            }
            for (JsonNode row : found.path("results").path("memory").values()) {
                String id = row.path("id").asString("");
                if (!id.isBlank() && !seen.add(id)) continue;
                String text = meko.rowText(row);
                String writer = row.path("agent_id").asString("");
                JsonNode metadata = metadata(row);
                String kind = metadata.path("kind").asString("");
                String table = metadata.path("table").asString("");
                if (kind.isBlank()) continue;   // the agent's own lessons and anything untagged are not team context
                if (!"orders".equals(table)) {
                    setAside.add(new ReleaseModels.SetAside(text, writer, source.name(),
                            "about the " + (table.isBlank() ? "unnamed" : table) + " table, not orders"));
                } else if ("incident".equals(kind) && !metadata.path("topic").asString("migration").equals("migration")) {
                    setAside.add(new ReleaseModels.SetAside(text, writer, source.name(), "an incident about " + metadata.path("topic").asString() + ", not migrations"));
                } else if ("incident".equals(kind)) {
                    incidents.add(new ReleaseModels.Incident(metadata.path("incident").asString(""), text, writer, source.name(),
                            metadata.path("workload").asString("")));
                } else if ("critical-query".equals(kind) && !metadata.path("sql").asString("").isBlank()
                        && queries.stream().noneMatch(q -> q.sql().equals(metadata.path("sql").asString()))) {
                    queries.add(new ReleaseModels.CriticalQuery(metadata.path("name").asString("critical query"),
                            metadata.path("sql").asString(), writer, source.name()));
                }
            }
        }
        // Each limit as the rule that sets it states it: MIG-01 the lock timeout and its attempts, REL-03 the approval limits.
        String lockTimeout = stated(rules, "MIG-01", LOCK_TIMEOUT);
        if (lockTimeout != null) lockTimeout = lockTimeout.replace(" ", "");
        String attempts = stated(rules, "MIG-01", ATTEMPTS);
        String stall = stated(rules, "REL-03", STALL);
        String factor = stated(rules, "REL-03", REGRESSION);
        long stallMillis = stall == null ? 0 : Long.parseLong(stall) * 1000;
        log.accept(rules.isEmpty() ? "Shared knowledge: no migration standard or release policy found."
                : "Shared knowledge: rules " + String.join(", ", new java.util.TreeSet<>(rules.keySet()))
                + (lockTimeout != null ? "; lock timeout " + lockTimeout + (attempts != null ? ", up to " + attempts + " attempts" : "") : "")
                + (stallMillis > 0 ? "; stall limit " + stall + " s" : "") + (factor != null ? "; plan regression at " + factor + "× the buffers" : "") + ".");
        log.accept("Memory: " + incidents.size() + " incident(s) and " + queries.size() + " critical query(ies) on orders"
                + (setAside.isEmpty() ? "" : "; " + setAside.size() + " set aside")
                + (org.shared() != null ? " (read from " + meko.datapackName() + " and " + org.shared().name() + ")." : "."));
        return new ReleaseModels.ReleaseContext(runId, prId, Map.copyOf(rules), lockTimeout, attempts == null ? 0 : Integer.parseInt(attempts),
                stallMillis, factor == null ? 0 : Long.parseLong(factor), List.copyOf(incidents), List.copyOf(queries), List.copyOf(setAside));
    }

    // ------------------------------------------------------------------
    // The incident use case: markers, the incident, the runbook
    // ------------------------------------------------------------------

    /**
     * The batch scheduler's agent, before it starts a run of a job: the run id, the table, when it started and the WAL
     * position just before it. Positions travel in the metadata, as recorded; the text says the same for a reader.
     */
    public void writeRunStart(String datapackId, String job, String table, String runId, String lsn, String startedAt) throws Exception {
        String conversation = meko.conversationAs("team:" + SCHEDULER, SCHEDULER, "Batch scheduler job log");
        meko.callTool("memory_add", meko.args(
                "text", "Scheduled job " + job + " run " + runId + " started on the " + table + " table at " + startedAt
                        + " UTC. The WAL position just before it ran was " + lsn + ".",
                "agent_id", SCHEDULER,
                "conversation_id", conversation,
                "metadata", meko.json(Map.of("kind", "job-marker", "phase", "start", "job", job, "run_id", runId, "table", table,
                        "lsn", lsn, "started_at", startedAt)),
                "datapack_id", datapackId));
    }

    /** The batch scheduler's agent, once the run committed: when it finished, the position after it, and the rows it changed. */
    public void writeRunEnd(String datapackId, String job, String table, String runId, String lsnAfter, String finishedAt, int rowsChanged) throws Exception {
        String conversation = meko.conversationAs("team:" + SCHEDULER, SCHEDULER, "Batch scheduler job log");
        meko.callTool("memory_add", meko.args(
                "text", "Scheduled job " + job + " run " + runId + " finished on the " + table + " table at " + finishedAt
                        + " UTC, having changed " + rowsChanged + " rows. The WAL position after it was " + lsnAfter + ".",
                "agent_id", SCHEDULER,
                "conversation_id", conversation,
                "metadata", meko.json(Map.of("kind", "job-marker", "phase", "end", "job", job, "run_id", runId, "table", table,
                        "lsn_after", lsnAfter, "finished_at", finishedAt, "rows_changed", rowsChanged)),
                "datapack_id", datapackId));
    }

    /** The SRE agent's incident: what it saw and when. It knows the time and the symptom, not any WAL position. */
    public void writeIncident(String datapackId, String id, String table, String detectedAt, String symptom) throws Exception {
        String conversation = meko.conversationAs("team:" + SRE, SRE, "Platform SRE notes");
        meko.callTool("memory_add", meko.args(
                "text", "Incident " + id + " detected at " + detectedAt + " UTC: " + symptom,
                "agent_id", SRE,
                "conversation_id", conversation,
                "metadata", meko.json(Map.of("kind", "incident", "incident", id, "table", table, "topic", "data", "detected_at", detectedAt)),
                "datapack_id", datapackId));
    }

    /**
     * What the recovery agent knows about an incident: its own datapack always; the organization's shared datapack only
     * when {@code readShared} (shared with the recovery team). The incident and the job markers come from memory, the
     * runbook's rules from the knowledge base. Every fact keeps where it came from and who wrote it.
     */
    public RecoveryModels.IncidentContext recallIncident(String runId, String incidentId, boolean readShared, Consumer<String> log) throws Exception {
        String conversation = record(incidentId);
        List<Source> sources = new ArrayList<>(List.of(new Source(meko.datapack(), meko.datapackName())));
        OrgContext.Shared shared = org.shared();
        if (readShared && shared != null && !shared.id().equals(meko.datapack())) sources.add(new Source(shared.id(), shared.name()));
        RecoveryModels.Fact incident = null;
        int occurrences = 0;
        List<RecoveryModels.Marker> markers = new ArrayList<>();
        Map<String, JsonNode[]> runs = new LinkedHashMap<>();   // run id → {start marker, end marker}, as metadata
        Map<String, String[]> about = new LinkedHashMap<>();    // run id → {start marker's text, writer, datapack}
        Map<String, ReleaseModels.Rule> rules = new LinkedHashMap<>();
        List<ReleaseModels.CriticalQuery> queries = new ArrayList<>();
        for (Source source : sources) {
            JsonNode found = meko.callTool("context_search", meko.args("query",
                    "incident " + incidentId + " on the orders table; scheduled job markers on orders with their WAL position; "
                            + "critical queries on the orders table; data repair runbook",
                    "conversation_id", conversation, "agent_id", AGENT_ID, "datapack_id", source.id(), "limit", 20));
            for (JsonNode chunk : found.path("results").path("knowledge_base").values()) {
                String text = meko.rowText(chunk);
                if (text == null) continue;
                Matcher m = RUNBOOK_ID.matcher(text);
                List<Integer> at = new ArrayList<>();
                List<String> ids = new ArrayList<>();
                while (m.find()) {
                    at.add(m.start());
                    ids.add(m.group(1));
                }
                for (int i = 0; i < ids.size(); i++) {
                    int end = i + 1 < at.size() ? at.get(i + 1) : text.length();
                    rules.putIfAbsent(ids.get(i), new ReleaseModels.Rule(ids.get(i), clean(text.substring(at.get(i), end)), source.name()));
                }
            }
            for (JsonNode row : found.path("results").path("memory").values()) {
                JsonNode metadata = metadata(row);
                String kind = metadata.path("kind").asString("");
                String text = meko.rowText(row);
                String writer = row.path("agent_id").asString("");
                if ("incident".equals(kind) && incidentId.equals(metadata.path("incident").asString())) {
                    // Each occurrence of the incident is its own record: the current one is the latest detected, not
                    // whichever the search happened to return last.
                    RecoveryModels.Fact occurrence = new RecoveryModels.Fact(text, writer, source.name(), metadata.path("detected_at").asString(""));
                    occurrences++;
                    if (incident == null || occurrence.at().compareTo(incident.at()) > 0) incident = occurrence;
                } else if ("job-marker".equals(kind) && "orders".equals(metadata.path("table").asString())) {
                    // A run's start and end markers share its run id; a marker without one predates run ids: a start alone.
                    String id = metadata.path("run_id").asString("");
                    String key = id.isBlank() ? "legacy:" + metadata.path("started_at").asString("") : id;
                    JsonNode[] pair = runs.computeIfAbsent(key, k -> new JsonNode[2]);
                    boolean end = "end".equals(metadata.path("phase").asString(""));
                    pair[end ? 1 : 0] = metadata;
                    if (!end) about.put(key, new String[]{text, writer, source.name()});
                } else if ("critical-query".equals(kind) && "orders".equals(metadata.path("table").asString())
                        && !metadata.path("sql").asString("").isBlank() && queries.stream().noneMatch(q -> q.sql().equals(metadata.path("sql").asString()))) {
                    queries.add(new ReleaseModels.CriticalQuery(metadata.path("name").asString("critical query"), metadata.path("sql").asString(), writer, source.name()));
                }
            }
        }
        runs.forEach((key, pair) -> {
            JsonNode start = pair[0], end = pair[1];
            if (start == null) return;   // an end without its start: nothing to fork at
            String lsn = start.path("lsn").asString("");
            String[] who = about.get(key);
            if (lsn.isBlank() && who[0] != null) {
                Matcher l = LSN.matcher(who[0]);
                if (l.find()) lsn = l.group(1);
            }
            if (lsn.isBlank()) return;
            markers.add(new RecoveryModels.Marker(start.path("job").asString("job"), start.path("run_id").asString(""), lsn,
                    start.path("started_at").asString(""), end == null ? null : end.path("lsn_after").asString(null),
                    end == null ? null : end.path("finished_at").asString(null), end == null ? -1 : end.path("rows_changed").asInt(-1),
                    who[0], who[1], who[2]));
        });
        RecoveryModels.Marker marker = runBefore(markers, incident != null ? incident.at() : null);
        log.accept("Read " + sources.stream().map(Source::name).toList() + ": "
                + (incident != null ? "the incident (" + incident.writtenBy() + (occurrences > 1 ? "; " + occurrences + " occurrences, using the latest, detected at " + incident.at() : "") + ")"
                        : "no record of the incident beyond the page's alert") + "; "
                + (marker != null ? "the run before it: " + marker.job() + (marker.runId().isBlank() ? "" : " run " + marker.runId())
                        + " started at " + marker.startedAt() + ", position " + marker.lsn()
                        + (marker.completed() ? ", finished at " + marker.finishedAt() + " having changed " + marker.rowsChanged() + " rows" : ", no end marker")
                        + " (" + marker.writtenBy() + ")" : "no job marker on orders") + "; "
                + (queries.isEmpty() ? "no critical query on orders" : queries.size() + " critical query(ies) on orders") + "; " + (rules.isEmpty() ? "no runbook." : "runbook " + String.join(", ", new java.util.TreeSet<>(rules.keySet())) + "."));
        return new RecoveryModels.IncidentContext(runId, incidentId, readShared && shared != null, incident, marker, List.copyOf(markers), Map.copyOf(rules), List.copyOf(queries),
                sources.stream().map(Source::name).toList());
    }

    // ------------------------------------------------------------------
    // Which shared datapack this team reads: kept in its own datapack, so a restart finds it again
    // ------------------------------------------------------------------

    private String settings() throws Exception {
        return meko.conversation("settings:org", "Release team settings", Map.of("settings", "org"));
    }

    /** Records which organization's shared datapack this team reads (null: none). */
    public void rememberShared(String id, String name) {
        try {
            meko.callTool("conversation_add_message", meko.args(
                    "conversation_id", settings(),
                    "agent_id", meko.conversationOwner(),
                    "input", "settings · shared datapack",
                    "output", meko.json(id == null ? Map.of("connected", false) : Map.of("connected", true, "id", id, "name", name)),
                    "metadata", meko.json(Map.of("writer", AGENT_ID, "event", "shared-datapack")),
                    "datapack_id", meko.datapack()));
        } catch (Exception e) {
            meko.recordError("Could not record the shared datapack: " + e.getMessage());
        }
    }

    /** The shared datapack this team last connected, as {@code [id, name]}, or null. */
    public String[] recallShared() throws Exception {
        JsonNode read = meko.callTool("conversation_get", meko.args("conversation_id", settings(),
                "agent_id", meko.conversationOwner(), "include_messages", true, "datapack_id", meko.datapack()));
        String[] last = null;
        for (JsonNode message : read.path("messages").values()) {
            JsonNode value;
            try {
                value = meko.objectMapper().readTree(text(message.path("output")));
            } catch (Exception e) {
                continue;
            }
            if (!value.has("connected")) continue;
            last = value.path("connected").asBoolean(false) ? new String[]{value.path("id").asString(""), value.path("name").asString("")} : null;
        }
        return last;
    }

    /**
     * The run an incident detected at {@code detected} is about: the run that last finished before it. A run with no end
     * marker (recorded before runs had ids) only when no completed run qualifies. Ordered by the times the scheduler
     * recorded, never by the order a search returned the markers in.
     */
    static RecoveryModels.Marker runBefore(List<RecoveryModels.Marker> markers, String detected) {
        java.util.function.Predicate<RecoveryModels.Marker> beforeIt = mk -> detected == null || detected.isBlank()
                || (mk.completed() ? mk.finishedAt() : mk.startedAt()).compareTo(detected) <= 0;
        java.util.Comparator<RecoveryModels.Marker> latest = java.util.Comparator.comparing(RecoveryModels.Marker::startedAt);
        return markers.stream().filter(RecoveryModels.Marker::completed).filter(beforeIt).max(latest)
                .orElseGet(() -> markers.stream().filter(beforeIt).max(latest).orElse(null));
    }

    /** Whether a datapack's knowledge base holds the data repair runbook. */
    public boolean runbookPresent(String datapackId) throws Exception {
        JsonNode found = meko.callTool("context_search", meko.args("query", "data repair runbook REC-01 point in time",
                "conversation_id", record(INCIDENT), "agent_id", AGENT_ID, "datapack_id", datapackId, "force_source", "knowledge_base"));
        for (JsonNode chunk : found.path("results").path("knowledge_base").values()) {
            String text = meko.rowText(chunk);
            if (text != null && RUNBOOK_ID.matcher(text).find()) return true;
        }
        return false;
    }

    /** The platform data team's runbook, into a datapack's knowledge base; waits until a search finds its rules. */
    public void uploadRunbook(String datapackId, Consumer<String> log) throws Exception {
        String conversation = meko.conversation(meko.conversationOwner() + ":knowledge", "Shared knowledge uploads", Map.of());
        meko.uploadKnowledgeDocument(conversation, "platform-data:standards", RUNBOOK,
                AmpDatabase.read("kb/" + RUNBOOK).getBytes(StandardCharsets.UTF_8), datapackId);
        log.accept("Uploaded the data repair runbook; indexing…");
        long deadline = System.currentTimeMillis() + 240_000;
        while (System.currentTimeMillis() < deadline) {
            if (runbookPresent(datapackId)) {
                log.accept("The runbook is searchable.");
                return;
            }
            Thread.sleep(5_000);
        }
        log.accept("The runbook is not searchable yet: check again before recovering.");
    }

    /** The rules in a chunk of a document: each id, with the text up to the next id. */
    static Map<String, ReleaseModels.Rule> rules(String text, String datapack) {
        Map<String, ReleaseModels.Rule> out = new LinkedHashMap<>();
        Matcher m = RULE_ID.matcher(text);
        List<int[]> at = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        while (m.find()) {
            at.add(new int[]{m.start()});
            ids.add(m.group(1));
        }
        for (int i = 0; i < ids.size(); i++) {
            int end = i + 1 < at.size() ? at.get(i + 1)[0] : text.length();
            out.putIfAbsent(ids.get(i), new ReleaseModels.Rule(ids.get(i), clean(text.substring(at.get(i)[0], end)), datapack));
        }
        return out;
    }

    /** What a rule found states for {@code pattern} (its first group), or null when the rule was not found or does not state it. */
    static String stated(Map<String, ReleaseModels.Rule> rules, String ruleId, Pattern pattern) {
        ReleaseModels.Rule rule = rules.get(ruleId);
        if (rule == null) return null;
        Matcher m = pattern.matcher(rule.text());
        return m.find() ? m.group(1) : null;
    }

    /** Whether other teams' incidents or critical queries are in a datapack's memory. */
    public boolean teamContextPresent(String datapackId) throws Exception {
        JsonNode found = meko.callTool("context_search", meko.args("query", "incidents and critical queries on the orders table",
                "conversation_id", record(PR), "agent_id", AGENT_ID, "datapack_id", datapackId, "force_source", "memory", "limit", 20));
        for (JsonNode row : found.path("results").path("memory").values()) {
            String kind = metadata(row).path("kind").asString("");
            if (kind.equals("critical-query") || kind.equals("incident")) return true;
        }
        return false;
    }

    private JsonNode metadata(JsonNode row) {
        JsonNode metadata = row.path("metadata");
        if (metadata.isString()) {
            try {
                return meko.objectMapper().readTree(metadata.asString());
            } catch (Exception e) {
                return meko.objectMapper().createObjectNode();
            }
        }
        return metadata;
    }

    private static String clean(String text) {
        return text.replace("**", "").replaceAll("\\s+", " ").strip();
    }

    // ------------------------------------------------------------------
    // The pull request's record
    // ------------------------------------------------------------------

    /** One turn on the pull request's record: what was done, what came of it, and who did it. */
    public void note(String prId, String runId, String event, String input, String output) {
        try {
            // The input is a tag, not a sentence: Meko extracts facts from a turn's input, and a record's turn is not an
            // instruction from anyone. What was done goes in the reasoning, which the audit reads back.
            meko.callTool("conversation_add_message", meko.args(
                    "conversation_id", record(prId),
                    "agent_id", meko.conversationOwner(),
                    "input", prId + " · " + runId + " · " + event,
                    "reasoning", input,
                    "output", output,
                    "metadata", meko.json(Map.of("writer", AGENT_ID, "event", event, "run", runId, "pr", prId)),
                    "datapack_id", meko.datapack()));
        } catch (Exception e) {
            meko.recordError("Could not write to the record of " + prId + ": " + e.getMessage());
        }
    }

    /** Stores the rehearsal's evidence whole; returns its content hash. */
    public String putEvidence(String prId, String runId, Object evidence) throws Exception {
        JsonNode stored = meko.callTool("artifact_put", meko.args(
                "filename", prId + "-" + runId + "-evidence.json",
                "content_base64", Base64.getEncoder().encodeToString(meko.objectMapper().writeValueAsBytes(evidence)),
                "content_type", "application/json",
                "conversation_id", record(prId),
                "agent_id", AGENT_ID,
                "datapack_id", meko.datapack()));
        return stored.path("content_hash").asString(null);
    }

    public void learn(String prId, String lesson) {
        // The same lesson from a repeated run is not written twice (the numbers in it may differ: compare its first sentence).
        String gist = lesson.split("[.:]", 2)[0];
        if (lessons.values().stream().anyMatch(l -> l.startsWith(gist)) || written.contains(gist)) return;
        written.add(gist);
        try {
            JsonNode added = meko.callTool("memory_add", meko.args(
                    "text", lesson,
                    "agent_id", AGENT_ID,
                    "conversation_id", record(prId),
                    "metadata", meko.json(Map.of("pr", prId, "table", "orders", "lesson", true)),
                    "datapack_id", meko.datapack()));
            for (JsonNode row : meko.rows(added)) {
                String id = row.path("id").asString("");
                if (!id.isBlank()) lessons.put(id, meko.rowText(row) != null ? meko.rowText(row) : lesson);
            }
        } catch (Exception e) {
            meko.recordError("Could not write the lesson: " + e.getMessage());
        }
    }

    /** The record of a pull request, read back from its id alone: each turn, and the evidence it names. */
    public Map<String, Object> audit(String prId) throws Exception {
        return audit(prId, true);
    }

    /** As {@link #audit(String)}; without {@code withEvidence}, the evidence artifact is named but not downloaded. */
    public Map<String, Object> audit(String prId, boolean withEvidence) throws Exception {
        String conversation = record(prId);
        JsonNode read = meko.callTool("conversation_get", meko.args("conversation_id", conversation,
                "agent_id", meko.conversationOwner(), "include_messages", true, "datapack_id", meko.datapack()));
        List<Map<String, Object>> turns = new ArrayList<>();
        String evidence = null;
        for (JsonNode message : read.path("messages").values()) {
            JsonNode metadata = metadata(message);
            String output = text(message.path("output"));
            Matcher hash = Pattern.compile("(?i)evidence artifact ([0-9a-f]{64})").matcher(output);
            if (hash.find()) evidence = hash.group(1);
            String done = text(message.path("reasoning"));
            turns.add(Map.of("event", metadata.path("event").asString(""), "run", metadata.path("run").asString(""),
                    "writer", metadata.path("writer").asString(""), "input", done.isBlank() ? text(message.path("input")) : done, "output", output));
        }
        Map<String, Object> audit = new LinkedHashMap<>();
        audit.put("pr", prId);
        audit.put("turns", turns);
        if (evidence != null && withEvidence) {
            JsonNode artifact = meko.callTool("artifact_get", meko.args("content_hash", evidence, "conversation_id", conversation,
                    "agent_id", AGENT_ID, "datapack_id", meko.datapack()));
            String b64 = artifact.path("content_base64").asString(null);
            if (b64 != null) audit.put("evidence", meko.objectMapper().readTree(new String(Base64.getDecoder().decode(b64), StandardCharsets.UTF_8)));
            audit.put("evidenceHash", evidence);
        }
        return audit;
    }

    private static String text(JsonNode node) {
        if (node.isString()) return node.asString();
        if (node.isMissingNode() || node.isNull()) return "";
        return node.toString();
    }

    // ------------------------------------------------------------------
    // What the workshop adds to the datapack between runs
    // ------------------------------------------------------------------

    /** Uploads the standard and the release policy to the datapack's knowledge base, and waits for them to be indexed. */
    public void uploadKnowledge(Consumer<String> log) throws Exception {
        String conversation = meko.conversation(meko.conversationOwner() + ":knowledge", "Shared knowledge uploads", Map.of());
        List<MekoMcpClient.Upload> uploads = new ArrayList<>();
        for (String name : KNOWLEDGE) {
            MekoMcpClient.Upload upload = meko.uploadKnowledgeDocument(conversation, "platform-data:standards",
                    name, AmpDatabase.read("kb/" + name).getBytes(StandardCharsets.UTF_8));
            uploads.add(upload);
            log.accept(upload.alreadyUploaded() ? name + " was already in the knowledge base." : "Uploaded " + name + "; indexing…");
        }
        // Indexing finishes on Meko's side: a document is ready when a search finds its rules.
        long deadline = System.currentTimeMillis() + 240_000;
        while (true) {
            java.util.Set<String> found = recall("upload-check", "upload-check", line -> { }).rules().keySet();
            boolean standard = found.stream().anyMatch(id -> id.startsWith("MIG-"));
            boolean policy = found.stream().anyMatch(id -> id.startsWith("REL-"));
            if (standard && policy) {
                log.accept("Both documents are searchable: rules " + String.join(", ", new java.util.TreeSet<>(found)) + ".");
                return;
            }
            if (System.currentTimeMillis() > deadline) {
                for (int i = 0; i < uploads.size(); i++) {
                    JsonNode status = meko.documentStatus(conversation, "platform-data:standards", uploads.get(i).key());
                    log.accept(KNOWLEDGE.get(i) + " is not searchable yet (Meko reports: " + abbreviate(status.toString()) + "). Check again before the next run.");
                }
                return;
            }
            Thread.sleep(5_000);
        }
    }

    /**
     * Other teams' agents write what they know to the datapack, each in its own name: the SRE team's incidents and the
     * warehouse and checkout teams' critical queries, with look-alikes about other tables.
     */
    public void addOtherTeams(String datapackId, Consumer<String> log) throws Exception {
        JsonNode entries = meko.objectMapper().readTree(AmpDatabase.read("context/other-teams.json"));
        for (JsonNode entry : entries.values()) {
            String agent = entry.path("agent").asString();
            String conversation = meko.conversationAs("team:" + agent, agent, entry.path("team").asString() + " notes");
            meko.callTool("memory_add", meko.args(
                    "text", entry.path("text").asString(),
                    "agent_id", agent,
                    "conversation_id", conversation,
                    "metadata", meko.json(entry.path("metadata")),
                    "datapack_id", datapackId));
            log.accept(entry.path("team").asString() + " (" + agent + ") wrote: " + abbreviate(entry.path("text").asString()));
        }
    }

    public Map<String, String> lessons() {
        return Map.copyOf(lessons);
    }

    /** Promotes the lessons this application wrote to the datapack's shared knowledge (one way). */
    public int promoteLessons(Consumer<String> log) throws Exception {
        List<String> ids = new CopyOnWriteArrayList<>(lessons.keySet());
        if (ids.isEmpty()) {
            log.accept("No lesson to promote: run a rehearsal that teaches one first.");
            return 0;
        }
        meko.callTool("memory_promote", meko.args("memory_ids", ids, "agent_id", AGENT_ID,
                "conversation_id", meko.conversation(meko.conversationOwner() + ":lessons", "Promoted lessons", Map.of()),
                "datapack_id", meko.datapack()));
        ids.forEach(id -> log.accept("Promoted: " + lessons.remove(id)));
        return ids.size();
    }

    private static String abbreviate(String text) {
        return text.length() <= 140 ? text : text.substring(0, 140) + "…";
    }
}
