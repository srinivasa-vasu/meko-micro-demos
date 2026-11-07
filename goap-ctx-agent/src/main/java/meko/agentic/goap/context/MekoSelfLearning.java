package meko.agentic.goap.context;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Meko's self-learning, for the workshop: how well the datapack answers the questions a run's agents asked, what Meko
 * says to fix, and whether a fix worked.
 * <p>
 * The agents keep their own searches (memory_search, knowledgebase_search): those are scoped and floored as the stories
 * need. Self-learning only counts searches made through context_search, so the check asks the same questions again
 * through it, across every source at once. A search limited to one source is logged as "source not searched" and
 * would show as a false to-do, which is why every check here searches all sources. Verified against the live server:
 * <ul>
 *   <li>context_search returns {@code results.{memory, knowledge_base, conversation}}, {@code match_quality} (low, mid,
 *       high) and {@code agent_hint}; its memory rows are shaped as memory_search's.</li>
 *   <li>A search reaches the self-learning log a few seconds after it returns.</li>
 *   <li>selflearning_verify reports {@code before.band}, {@code after.band} and {@code outcome} ("improved").</li>
 * </ul>
 */
@Component
public class MekoSelfLearning {
    /** Who asks the check's questions: not one of the stories' agents, so the log tells them apart. */
    public static final String AGENT = "workshop:coverage-check";
    /** Each check is a real search against the participant's search limit, and a replay is limited to 10 a minute. */
    public static final int MAX_QUESTIONS = 8;
    private static final Set<String> STATES_TO_REPLAY = Set.of("open", "unverified");

    private final MekoMcpClient meko;

    public MekoSelfLearning(MekoMcpClient meko) {
        this.meko = meko;
    }

    /** One question asked through context_search: the band Meko gave it, its hint, and what each source found. */
    public record Check(String question, String quality, String hint, int memory, int knowledge, int conversation) {}

    /** One weak search replayed: its band before and after, and Meko's verdict on the fix. */
    public record Replay(String question, String before, String after, String outcome, String hint, boolean indexing) {}

    /** Asks each question through context_search, then reads the datapack's self-learning status once it includes them. */
    public Map<String, Object> check(List<String> questions) throws Exception {
        List<String> asked = distinct(questions);
        String conversation = conversation();
        int before = searches(status(conversation));
        List<Check> checks = new ArrayList<>();
        for (String q : asked) {
            JsonNode r = meko.callTool("context_search", meko.args(
                    "query", q, "conversation_id", conversation, "agent_id", AGENT,
                    "datapack_id", meko.datapack(), "limit", 8));
            JsonNode results = r.path("results");
            checks.add(new Check(q, r.path("match_quality").asString("low"), r.path("agent_hint").asString(""),
                    results.path("memory").size(), results.path("knowledge_base").size(),
                    results.path("conversation").path("hits").size()));
        }
        // The log is written a few seconds after each search returns: wait (briefly) until it holds these.
        JsonNode status = status(conversation);
        for (int i = 0; i < 6 && searches(status) < before + asked.size(); i++) {
            Thread.sleep(1_500);
            status = status(conversation);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("checks", checks);
        out.putAll(summary(status));
        return out;
    }

    /** The datapack's self-learning status, as its Self-learning tab shows it. */
    public Map<String, Object> status() throws Exception {
        return summary(status(conversation()));
    }

    /**
     * Replays the weak searches Meko listed under "add context" (only those asking one of {@code questions}, when given),
     * so the page shows whether what was added since fixed them.
     */
    public List<Replay> verify(List<String> questions) throws Exception {
        String conversation = conversation();
        Set<String> wanted = questions == null || questions.isEmpty() ? null : Set.copyOf(distinct(questions));
        List<String> logIds = new ArrayList<>();
        Map<String, String> asked = new LinkedHashMap<>();
        for (int page = 1; page <= 5 && logIds.size() < MAX_QUESTIONS; page++) {
            JsonNode r = meko.callTool("selflearning_shortfalls", meko.args(
                    "todo_id", "add_context", "datapack_id", meko.datapack(), "conversation_id", conversation, "page", page));
            for (JsonNode s : r.path("searches").values()) {
                String q = s.path("query").asString("");
                if (!STATES_TO_REPLAY.contains(s.path("state").asString("")) || (wanted != null && !wanted.contains(q))) continue;
                if (asked.containsValue(q) || logIds.size() >= MAX_QUESTIONS) continue;
                String id = s.path("log_id").asString("");
                if (id.isBlank()) continue;
                logIds.add(id);
                asked.put(id, q);
            }
            if (!r.path("has_more").asBoolean(false)) break;
        }
        List<Replay> replays = new ArrayList<>();
        for (String id : logIds) {
            JsonNode r = meko.callTool("selflearning_verify", meko.args(
                    "log_id", id, "datapack_id", meko.datapack(), "conversation_id", conversation));
            replays.add(new Replay(r.path("query").asString(asked.get(id)),
                    r.path("before").path("band").asString(""), r.path("after").path("band").asString(""),
                    r.path("nothing_added_since_last_run").asBoolean(false) ? "nothing added since" : r.path("outcome").asString(""),
                    r.path("hint").asString(""), r.path("indexing").asBoolean(false)));
        }
        return replays;
    }

    private String conversation() throws Exception {
        return meko.conversation(AGENT, "Workshop coverage check", Map.of("purpose", "self-learning check"));
    }

    private JsonNode status(String conversation) throws Exception {
        return meko.callTool("selflearning_status", meko.args("datapack_id", meko.datapack(), "conversation_id", conversation));
    }

    private static int searches(JsonNode status) {
        return status.path("quality").path("searches").asInt(0);
    }

    /** What the page shows of the status: the quality figure, and the to-dos with something to do. */
    private static Map<String, Object> summary(JsonNode status) {
        JsonNode q = status.path("quality");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("quality", Map.of("pct", q.path("pct").asInt(0), "potential", q.path("potential_pct").asInt(0),
                "searches", q.path("searches").asInt(0), "high", q.path("high").asInt(0)));
        List<Map<String, Object>> todos = new ArrayList<>();
        for (JsonNode t : status.path("todos").values()) {
            if (!"todo".equals(t.path("state").asString("")) || t.path("searches").asInt(0) == 0) continue;
            List<String> examples = new ArrayList<>();
            t.path("examples").values().forEach(e -> examples.add(e.path("label").asString("")
                    + (e.path("detail").asString("").isBlank() ? "" : " (" + e.path("detail").asString() + ")")));
            todos.add(Map.of("id", t.path("id").asString(""), "title", t.path("title").asString(""), "why", t.path("why").asString(""),
                    "searches", t.path("searches").asInt(0), "points", t.path("points").asInt(0), "examples", examples));
        }
        out.put("todos", todos);
        return out;
    }

    private static List<String> distinct(List<String> questions) {
        if (questions == null) return List.of();
        return questions.stream().filter(q -> q != null && !q.isBlank()).map(String::strip)
                .map(q -> q.length() > 500 ? q.substring(0, 500) : q).distinct().limit(MAX_QUESTIONS).toList();
    }
}
