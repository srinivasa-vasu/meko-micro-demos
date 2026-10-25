package meko.agentic.goap.sessions;

import meko.agentic.goap.context.MekoMcpClient;
import meko.agentic.goap.story.store.ContextStore;
import meko.agentic.goap.story.store.ContextStores;
import meko.agentic.goap.story.store.LocalContextStore;
import meko.agentic.goap.story.store.OutageSwitch;
import meko.agentic.goap.story.store.ReceiptLog;
import meko.agentic.goap.story.store.StoreRecord;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * "New session. Same context.": a minimal agent harness, standing in for any real one (a coding assistant, a support
 * copilot). Like every harness, it keeps a session's context in the session: when the session ends, the context goes
 * with it, and the next session, or another tool, starts empty. Connected to Meko, each session writes its
 * conversation to the datapack, Meko extracts the facts from what the person typed, and a new session, in any
 * harness, starts with them.
 * <p>
 * Nothing about the outcome is scripted: what the person types is theirs, the facts are what Meko extracted (shown as
 * Meko stored them), and what a new session knows is what memory_search returns. The harness writes no replies with a
 * model: its reply states what it knows and what it would have to ask. A Meko call that fails is reported as a
 * failure, never as "nothing carried".
 */
@Service
public class HarnessSessions {

    /** A live session: the harness it runs in, and the context it holds (gone when it ends). */
    record Session(String id, String harness, List<String> said) {}

    /** A session that has ended: kept only to measure against (what was typed in it), never as context. */
    record Ended(String id, String harness, List<String> said) {}

    /** How many sessions are kept, live and ended: the oldest go first (a page reload starts again anyway). */
    private static final int KEEP = 200;
    /** A harness id, as an agent id: "claude_code:payments-api". Not empty, and not this application's own owners. */
    private static final java.util.regex.Pattern HARNESS = java.util.regex.Pattern.compile("[a-z0-9_.-]{2,40}:[a-z0-9_.-]{2,60}");
    /** The longest message a session takes. */
    static final int MAX_TEXT = 2000;

    private final Map<String, Session> live = bounded();
    private final Map<String, Ended> ended = bounded();
    private final MekoMcpClient meko;
    private final ContextStores stores;
    private final ReceiptLog receipts;
    /**
     * Offline, the sessions' own in-memory store: what a person types here never reaches the store the stories' agents
     * search, so a session cannot change a story's run.
     */
    private final LocalContextStore standIn;
    /** The parallel searches' own threads: they wait on the network, so they do not borrow the common pool's few. */
    private static final ExecutorService SEARCHES = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "harness-search");
        t.setDaemon(true);
        return t;
    });

    public HarnessSessions(MekoMcpClient meko, ContextStores stores, ReceiptLog receipts, OutageSwitch outage) {
        this.meko = meko;
        this.stores = stores;
        this.receipts = receipts;
        this.standIn = new LocalContextStore(receipts, outage, ContextStore.Kind.LOCAL_STAND_IN);
    }

    private static <V> Map<String, V> bounded() {
        return Collections.synchronizedMap(new LinkedHashMap<>() {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, V> eldest) {
                return size() > KEEP;
            }
        });
    }

    private boolean liveMeko() {
        return stores.forLane(ContextStores.Lane.MEKO).kind() == ContextStore.Kind.MEKO_LIVE;
    }

    /** Opens a session in a harness: a new id, an empty context. */
    public Map<String, Object> start(String harness) {
        validHarness(harness);
        String id = "SES-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        live.put(id, new Session(id, harness, Collections.synchronizedList(new ArrayList<>())));
        return Map.of("sessionId", id, "harness", harness, "store", storeName());
    }

    /**
     * Something the person says in a session. It joins the session's context; with Meko it is also written to the
     * session's conversation, as the person's input, which is what Meko extracts facts from.
     */
    public Map<String, Object> say(String sessionId, String text) {
        if (text.isBlank()) throw new IllegalArgumentException("Type something to say.");
        if (text.length() > MAX_TEXT) throw new IllegalArgumentException("Keep a message under " + MAX_TEXT + " characters.");
        Session s = session(sessionId);
        s.said().add(text);
        boolean ok;
        if (liveMeko()) {
            ok = timed(sessionId, s.harness(), "conversation_add_message", "[" + sessionId + "] " + abbreviate(text), () -> {
                meko.callTool("conversation_add_message", meko.args(
                        "conversation_id", conversation(s),
                        "agent_id", s.harness(),
                        "input", text,
                        "output", "Noted.",
                        "datapack_id", meko.datapack()));
                return 1;
            });
        } else {
            // The stand-in has no fact extraction: it keeps what was said, as written.
            ok = standIn.remember(sessionId, s.harness(), text).isPresent();
        }
        Map<String, Object> out = new LinkedHashMap<>(Map.of("reply", "Noted.", "context", List.copyOf(s.said())));
        if (!ok) out.put("failed", "The message is in the session, but writing it to the store failed: see the calls.");
        return withCalls(sessionId, out);
    }

    /** Ends a session: its context is discarded, as a harness discards it. What Meko stored from it is reported. */
    public Map<String, Object> end(String sessionId) {
        Session s = session(sessionId);
        live.remove(sessionId);
        ended.put(sessionId, new Ended(sessionId, s.harness(), List.copyOf(s.said())));
        return facts(sessionId);
    }

    /**
     * The facts Meko holds from a session, as Meko stored them (its own extraction from what was typed; it rephrases,
     * and may take a moment). On the stand-in, what was said.
     */
    public Map<String, Object> facts(String sessionId) {
        String harness = harnessOf(sessionId);
        Facts facts = sessionFacts(sessionId, harness);
        Map<String, Object> out = new LinkedHashMap<>(Map.of("sessionId", sessionId, "facts", facts.facts(), "ended", ended.containsKey(sessionId)));
        if (!facts.ok()) out.put("failed", "Reading the session's facts from Meko failed: see the calls.");
        return withCalls(sessionId, out);
    }

    private record Facts(List<Map<String, String>> facts, boolean ok) {}

    private Facts sessionFacts(String sessionId, String harness) {
        if (!liveMeko()) {
            Ended e = ended.get(sessionId);
            Session s = live.get(sessionId);
            List<String> said = e != null ? e.said() : s != null ? List.copyOf(s.said()) : List.of();
            return new Facts(said.stream().map(t -> Map.of("id", "", "text", t, "writtenBy", harness)).toList(), true);
        }
        String conversation = meko.conversationIfKnown(sessionId);
        // Nothing was ever written for it (it said nothing, or it belongs to a datapack no longer in use).
        if (conversation == null) return new Facts(List.of(), true);
        List<Map<String, String>> out = new ArrayList<>();
        boolean ok = timed(sessionId, harness, "memory_get_all", "[this session] facts Meko extracted", () -> {
            JsonNode payload = meko.callTool("memory_get_all", meko.args(
                    "conversation_id", conversation,
                    "run_id", conversation,
                    "datapack_id", meko.datapack()));
            List<JsonNode> rows = payload.isArray() ? new ArrayList<>(payload.valueStream().toList()) : meko.rows(payload);
            if (rows.isEmpty() && payload.path("memories").isArray()) rows = payload.path("memories").valueStream().toList();
            for (JsonNode r : rows) {
                String text = meko.rowText(r);
                if (text == null || text.isBlank()) continue;   // a row with no text is not a fact
                out.add(Map.of("id", r.path("id").asString(""), "text", text, "writtenBy", r.path("agent_id").asString(harness)));
            }
            return out.size();
        });
        return new Facts(out, ok);
    }

    /**
     * A new session in a harness, given a task. Before the person types anything, it loads what it can: nothing
     * without Meko (a harness keeps context in the session); with Meko, what memory search returns for the task and
     * for the project's standing context. It then answers with what it knows, and what it would have to be told again,
     * measured against the facts the earlier session established.
     */
    public Map<String, Object> begin(String harness, String task, boolean withMeko, String earlierSessionId) {
        if (task == null || task.isBlank()) throw new IllegalArgumentException("Give the session a task.");
        if (task.length() > MAX_TEXT) throw new IllegalArgumentException("Keep the task under " + MAX_TEXT + " characters.");
        if (earlierSessionId != null && !ended.containsKey(earlierSessionId) && !live.containsKey(earlierSessionId)) {
            throw new IllegalArgumentException("No earlier session " + earlierSessionId + ": it is from before a restart. Start again from session 1.");
        }
        String id = (String) start(harness).get("sessionId");
        Facts earlier = earlierSessionId == null ? new Facts(List.of(), true) : sessionFacts(earlierSessionId, harnessOf(earlierSessionId));
        List<Map<String, String>> established = earlier.facts();
        Recall recall = withMeko ? recall(id, harness, task) : new Recall(Map.of(), true);
        List<StoreRecord> recalled = List.copyOf(recall.found().keySet());
        List<Map<String, String>> known = recalled.stream().map(r -> Map.of("text", r.text(), "writtenBy", String.valueOf(r.writtenBy()),
                "foundBy", recall.found().get(r))).toList();
        // A fact from the earlier session is carried when the new session's recall returned it (by id, else by text).
        List<Map<String, String>> carried = established.stream().filter(f -> recalled.stream().anyMatch(r ->
                (!f.get("id").isBlank() && f.get("id").equals(r.id())) || f.get("text").equals(r.text()))).toList();
        List<Map<String, String>> retell = established.stream().filter(f -> !carried.contains(f)).toList();
        int wordsToRetype = retell.stream().mapToInt(f -> f.get("text").trim().split("\\s+").length).sum();
        String reply = known.isEmpty()
                ? "Starting fresh: I have no context from earlier sessions. To " + lower(task) + ", tell me about your stack, your conventions and any decisions I should follow."
                : "Before you typed, I loaded what this project already knows (" + known.size() + " fact" + (known.size() == 1 ? "" : "s") + "). To "
                + lower(task) + ", I'll work from those" + (retell.isEmpty() ? "." : "; I still need: " + retell.size() + " more, told again.");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sessionId", id);
        out.put("harness", harness);
        out.put("withMeko", withMeko);
        out.put("known", known);
        out.put("reply", reply);
        out.put("numbers", Map.of("established", established.size(), "carried", carried.size(), "toRetell", retell.size(), "wordsToRetype", wordsToRetype));
        out.put("retell", retell);
        // A failed Meko call is a failure, not a measurement: the numbers above are incomplete, and say so.
        if (!earlier.ok() || !recall.ok()) out.put("failed", "A Meko call failed, so these numbers are incomplete: see the calls.");
        // The new session is one question and its answer: it ends here, keeping nothing.
        live.remove(id);
        return withCalls(id, out);
    }

    /** What a session loads at its start besides its task: the project's standing context, as a harness does. */
    static final String PROJECT_CONTEXT = "the project's stack, conventions, constraints and decisions";

    private record Recall(Map<StoreRecord, String> found, boolean ok) {}

    private record Searched(List<StoreRecord> rows, boolean ok) {}

    /**
     * What a new session loads at its start, across every conversation and harness: memory search for its task, and
     * for the project's standing context (a fact that shares no words with the task, such as a logging rule, is still
     * the project's). Both searches run at once; each fact once, with the search that found it first.
     */
    private Recall recall(String sessionId, String harness, String task) {
        Map<StoreRecord, String> found = new LinkedHashMap<>();
        var byTask = CompletableFuture.supplyAsync(() -> search(sessionId, harness, task), SEARCHES);
        var byProject = CompletableFuture.supplyAsync(() -> search(sessionId, harness, PROJECT_CONTEXT), SEARCHES);
        boolean ok = true;
        for (var e : List.of(Map.entry("the task", byTask.join()), Map.entry("the project context", byProject.join()))) {
            ok &= e.getValue().ok();
            for (StoreRecord r : e.getValue().rows()) {
                boolean seen = found.keySet().stream().anyMatch(k -> (!r.id().isBlank() && r.id().equals(k.id())) || r.text().equals(k.text()));
                if (!seen) found.put(r, e.getKey());
            }
        }
        return new Recall(found, ok);
    }

    private Searched search(String sessionId, String harness, String query) {
        if (!liveMeko()) return new Searched(standIn.searchMemory(sessionId, harness, query, 10), true);
        List<StoreRecord> rows = new ArrayList<>();
        boolean ok = timed(sessionId, harness, "memory_search", query, () -> {
            JsonNode payload = meko.callTool("memory_search", meko.args(
                    "query", query,
                    "conversation_id", conversation(session(sessionId)),
                    "agent_id", harness,
                    "limit", 10,
                    "datapack_id", meko.datapack()));
            for (JsonNode r : meko.rows(payload)) {
                String text = meko.rowText(r);
                if (text == null || text.isBlank()) continue;
                rows.add(new StoreRecord(r.path("id").asString(""), text, r.path("agent_id").asString(""), r.path("created_at").asString(""), null));
            }
            return rows.size();
        });
        return new Searched(rows, ok);
    }

    private String conversation(Session s) throws Exception {
        return meko.conversationAs(s.id(), s.harness(), "Session " + s.id() + " in " + s.harness());
    }

    private Session session(String id) {
        Session s = live.get(id);
        if (s == null) throw new IllegalArgumentException("No live session " + id + ": it has ended, or the application restarted.");
        return s;
    }

    private String harnessOf(String sessionId) {
        Ended e = ended.get(sessionId);
        if (e != null) return e.harness();
        Session s = live.get(sessionId);
        if (s != null) return s.harness();
        throw new IllegalArgumentException("No session " + sessionId + ": it is from before a restart.");
    }

    private static void validHarness(String harness) {
        if (harness == null || !HARNESS.matcher(harness).matches()) {
            throw new IllegalArgumentException("A harness is named like an agent id, tool:project (claude_code:payments-api).");
        }
    }

    private String storeName() {
        return liveMeko() ? "Meko: " + meko.datapackName() : "the offline stand-in";
    }

    private Map<String, Object> withCalls(String sessionId, Map<String, Object> body) {
        Map<String, Object> out = new LinkedHashMap<>(body);
        out.put("calls", receipts.forCase(sessionId));
        return out;
    }

    private interface Call {
        int run() throws Exception;
    }

    /** One Meko call, receipted under the session like any other, with what came back; whether it succeeded. */
    private boolean timed(String sessionId, String agent, String tool, String detail, Call call) {
        long start = System.nanoTime();
        try {
            int hits = call.run();
            receipts.record(sessionId, new ReceiptLog.Receipt(agent, tool, detail, hits, (System.nanoTime() - start) / 1_000_000, true, ContextStore.Kind.MEKO_LIVE.name()));
            return true;
        } catch (Exception e) {
            meko.recordError(tool + " failed: " + e.getMessage());
            receipts.record(sessionId, new ReceiptLog.Receipt(agent, tool, detail + " — " + tool + " failed: " + e.getMessage(), 0,
                    (System.nanoTime() - start) / 1_000_000, false, ContextStore.Kind.MEKO_LIVE.name()));
            return false;
        }
    }

    private static String abbreviate(String text) {
        return text.length() <= 110 ? text : text.substring(0, 110) + "…";
    }

    private static String lower(String task) {
        String t = task.strip().replaceAll("[.!]+$", "");
        return t.isEmpty() ? "do this" : Character.toLowerCase(t.charAt(0)) + t.substring(1);
    }
}
