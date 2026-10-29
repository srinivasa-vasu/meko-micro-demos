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

    private final com.embabel.common.textio.template.TemplateRenderer templates;
    private final meko.agentic.goap.model.LlmTiers models;
    /** The workshop runs without a model, whatever model settings it is started with: it shows the input only. */
    private final boolean workshop;

    public HarnessSessions(MekoMcpClient meko, ContextStores stores, ReceiptLog receipts, OutageSwitch outage,
                           com.embabel.common.textio.template.TemplateRenderer templates, meko.agentic.goap.model.LlmTiers models,
                           meko.agentic.goap.story.Workshop workshop) {
        this.workshop = workshop.enabled();
        this.templates = templates;
        this.models = models;
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
        Recall recall = withMeko ? recall(id, harness, task) : new Recall(List.of(), true);
        // The right context: of what Meko returned, what this prompt needs (a model judges in the demo; a rule in the workshop).
        Selection chosen = withMeko ? select(id, task, recall.found()) : new Selection(List.of(), List.of(), null);
        List<Map<String, String>> known = chosen.kept().stream().map(Candidate::view).toList();
        List<Map<String, String>> setAside = chosen.setAside().stream().map(a -> {
            Map<String, String> v = new LinkedHashMap<>(a.candidate().view());
            v.put("reason", a.reason());
            return v;
        }).toList();
        // Each of the earlier session's facts: sent to the model, set aside as not needed for this prompt, or not found.
        List<Map<String, String>> carried = established.stream().filter(f -> chosen.kept().stream().anyMatch(c -> c.is(f))).toList();
        List<Map<String, String>> notNeeded = established.stream().filter(f -> chosen.setAside().stream().anyMatch(a -> a.candidate().is(f))).toList();
        List<Map<String, String>> retell = established.stream().filter(f -> !carried.contains(f) && !notNeeded.contains(f)).toList();
        int wordsToRetype = retell.stream().mapToInt(f -> f.get("text").trim().split("\\s+").length).sum();
        // What the harness sends to the model: the context it could load, then the prompt (prompts/harness/model-input).
        String modelInput = templates.renderLoadedTemplate("harness/model-input",
                Map.of("harness", harness, "facts", known, "prompt", task)).strip().replaceAll("\n{3,}", "\n\n");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sessionId", id);
        out.put("harness", harness);
        out.put("withMeko", withMeko);
        out.put("known", known);
        out.put("setAside", setAside);
        if (chosen.decidedBy() != null) out.put("relevance", chosen.decidedBy());
        out.put("modelInput", modelInput);
        // An estimate (characters / 4), as the stories estimate context size; the model's own count is in its reply's usage.
        out.put("modelInputTokens", Math.max(1, modelInput.length() / 4));
        // Where models are on (the demo, with a key), the model answers exactly this input; where they are off, the input
        // is what a model would receive.
        if (workshop) {
            out.put("modelNote", "The workshop runs without a model: this is the input a model would receive.");
        } else try {
            models.answer(id, modelInput, "llm", "slm").ifPresentOrElse(a -> {
                out.put("model", a.model());
                out.put("modelReply", a.text());
            }, () -> out.put("modelNote", models.enabled() ? "No model tier is available: this is the input a model would receive."
                    : "Models are off here: this is the input a model would receive."));
        } catch (RuntimeException e) {
            out.put("modelNote", "The model call failed (" + e.getMessage() + "): this is the input it was sent.");
        }
        out.put("numbers", Map.of("established", established.size(), "carried", carried.size(), "setAside", notNeeded.size(),
                "toRetell", retell.size(), "wordsToRetype", wordsToRetype));
        out.put("retell", retell);
        // A failed Meko call is a failure, not a measurement: the numbers above are incomplete, and say so.
        if (!earlier.ok() || !recall.ok()) out.put("failed", "A Meko call failed, so these numbers are incomplete: see the calls.");
        // The new session is one question and its answer: it ends here, keeping nothing.
        live.remove(id);
        return withCalls(id, out);
    }

    /** What a session loads at its start besides its task: the project's standing context, as a harness does. */
    static final String PROJECT_CONTEXT = "the project's stack, conventions, constraints and decisions";

    /**
     * A fact Meko returned for a new session: which search found it, and Meko's relevance score for the prompt (null
     * when only the project-context search returned it, or on the stand-in, which has no scores).
     */
    record Candidate(String id, String text, String writtenBy, String foundBy, Double score) {
        Map<String, String> view() {
            Map<String, String> v = new LinkedHashMap<>(Map.of("text", text, "writtenBy", writtenBy, "foundBy", foundBy));
            if (score != null) v.put("score", String.format("%.2f", score));
            return v;
        }

        /** Whether this is the earlier session's fact {@code f}: by id, else by its text. */
        boolean is(Map<String, String> f) {
            return (!f.get("id").isBlank() && f.get("id").equals(id)) || f.get("text").equals(text);
        }
    }

    private record Recall(List<Candidate> found, boolean ok) {}

    private record Searched(List<Candidate> rows, boolean ok) {}

    /** What was kept for the model, what was set aside and why, and who decided. */
    record Selection(List<Candidate> kept, List<SetAside> setAside, String decidedBy) {}

    record SetAside(Candidate candidate, String reason) {}

    /** The workshop's rule: a fact is kept when Meko's score for the prompt is at least this share of the top score. */
    static final double RELATIVE_CUTOFF = 0.7;

    /**
     * The right context for a prompt. Where a model is available (the demo), it reads what Meko returned and keeps what
     * the prompt needs, with a reason for each fact it sets aside (prompts/harness/relevance). Otherwise (the workshop,
     * or no model) a rule decides from Meko's own scores: kept when at least 70% of the top score; a fact only the
     * project-context search returned is standing context, kept. A model's answer that does not account for every fact
     * once is not used: the rule decides, and says so.
     */
    Selection select(String sessionId, String prompt, List<Candidate> candidates) {
        if (candidates.isEmpty()) return new Selection(List.of(), List.of(), null);
        if (!workshop && models.enabled()) {
            try {
                String ask = templates.renderLoadedTemplate("harness/relevance", Map.of("prompt", prompt,
                        "candidates", candidates.stream().map(c -> Map.of("text", c.text(), "score", c.score() == null ? "none" : String.format("%.2f", c.score()))).toList()));
                var answer = models.answer(sessionId, ask, "slm", "llm");
                if (answer.isPresent()) {
                    Selection judged = parseJudgement(answer.get().text(), candidates, answer.get().model());
                    if (judged != null) return judged;
                    return byRule(candidates, " (" + answer.get().model() + "'s answer did not account for every fact once)");
                }
            } catch (RuntimeException e) {
                return byRule(candidates, " (the model call failed: " + e.getMessage() + ")");
            }
        }
        return byRule(candidates, "");
    }

    private Selection byRule(List<Candidate> candidates, String why) {
        double top = candidates.stream().map(Candidate::score).filter(java.util.Objects::nonNull).mapToDouble(Double::doubleValue).max().orElse(Double.NaN);
        if (Double.isNaN(top)) {
            return new Selection(List.copyOf(candidates), List.of(), "rule: no relevance scores to compare (the offline stand-in), so all were kept" + why);
        }
        double cutoff = top * RELATIVE_CUTOFF;
        List<Candidate> kept = new ArrayList<>();
        List<SetAside> aside = new ArrayList<>();
        for (Candidate c : candidates) {
            if (c.score() == null || c.score() >= cutoff) kept.add(c);
            else aside.add(new SetAside(c, String.format("Meko's score %.2f, below %.0f%% of the top score (%.2f)", c.score(), RELATIVE_CUTOFF * 100, top)));
        }
        return new Selection(kept, aside, String.format("rule: kept what scores at least %.0f%% of Meko's top score for the prompt; "
                + "standing context the project search returned is kept", RELATIVE_CUTOFF * 100) + why);
    }

    /** A model's choice, if it accounts for every fact exactly once; else null. */
    Selection parseJudgement(String reply, List<Candidate> candidates, String model) {
        try {
            String json = reply.substring(reply.indexOf('{'), reply.lastIndexOf('}') + 1);
            JsonNode node = meko.objectMapper().readTree(json);
            java.util.Set<Integer> seen = new java.util.HashSet<>();
            List<Candidate> kept = new ArrayList<>();
            List<SetAside> aside = new ArrayList<>();
            for (JsonNode n : node.path("keep").values()) {
                int i = n.asInt(-1);
                if (i < 1 || i > candidates.size() || !seen.add(i)) return null;
                kept.add(candidates.get(i - 1));
            }
            for (JsonNode a : node.path("setAside").values()) {
                int i = a.path("n").asInt(-1);
                if (i < 1 || i > candidates.size() || !seen.add(i)) return null;
                aside.add(new SetAside(candidates.get(i - 1), a.path("reason").asString("not needed for this prompt")));
            }
            if (seen.size() != candidates.size()) return null;
            kept.sort(java.util.Comparator.comparingInt(candidates::indexOf));
            return new Selection(kept, aside, model + ", reading what Meko returned and the prompt");
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * What a new session loads at its start, across every conversation and harness: memory search for its task, and
     * for the project's standing context (a fact that shares no words with the task, such as a logging rule, is still
     * the project's). Both searches run at once; each fact once, with the search that found it first.
     */
    private Recall recall(String sessionId, String harness, String task) {
        var byTask = CompletableFuture.supplyAsync(() -> search(sessionId, harness, task, "the prompt", true), SEARCHES);
        var byProject = CompletableFuture.supplyAsync(() -> search(sessionId, harness, PROJECT_CONTEXT, "the project context", false), SEARCHES);
        List<Candidate> found = new ArrayList<>();
        boolean ok = true;
        // The prompt's own search first: its scores are the prompt's relevance; the project search adds what it missed.
        for (Searched s : List.of(byTask.join(), byProject.join())) {
            ok &= s.ok();
            for (Candidate c : s.rows()) {
                boolean seen = found.stream().anyMatch(k -> (!c.id().isBlank() && c.id().equals(k.id())) || c.text().equals(k.text()));
                if (!seen) found.add(c);
            }
        }
        return new Recall(found, ok);
    }

    /** @param scored whether Meko's scores for this search are the prompt's relevance (the prompt's own search) */
    private Searched search(String sessionId, String harness, String query, String foundBy, boolean scored) {
        if (!liveMeko()) {
            return new Searched(standIn.searchMemory(sessionId, harness, query, 10).stream()
                    .map(r -> new Candidate(r.id(), r.text(), String.valueOf(r.writtenBy()), foundBy, null)).toList(), true);
        }
        List<Candidate> rows = new ArrayList<>();
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
                rows.add(new Candidate(r.path("id").asString(""), text, r.path("agent_id").asString(""), foundBy,
                        scored && r.path("score").isNumber() ? r.path("score").asDouble() : null));
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

}
