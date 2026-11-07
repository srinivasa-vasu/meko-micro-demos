package meko.agentic.goap.context;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The workshop's self-learning check: shaped as the live server answered (see {@link MekoSelfLearning}). */
class MekoSelfLearningTest {

    static class FakeMeko extends MekoMcpClient {
        final List<String> calls = new ArrayList<>();
        final List<Map<String, Object>> searches = new ArrayList<>();
        int logged;   // searches the log holds: one behind, as the log lags the search
        private final ObjectMapper json = new ObjectMapper();

        FakeMeko(MekoProperties p) {
            super(p);
        }

        @Override
        public JsonNode callTool(String tool, Map<String, Object> args) {
            calls.add(tool);
            return json.valueToTree(switch (tool) {
                case "datapack_list" -> List.of(Map.of("datapack_name", "meko-workshop-x", "datapack_id", "dp-1"));
                case "conversation_create" -> Map.of("id", "conv-1");
                case "context_search" -> {
                    searches.add(args);
                    boolean policy = String.valueOf(args.get("query")).contains("policy");
                    yield Map.of("results", Map.of(
                                    "memory", List.of(Map.of("id", "m1", "memory", "a memory")),
                                    "knowledge_base", policy ? List.of(Map.of("chunk_text", "a chunk")) : List.of(),
                                    "conversation", Map.of("hits", List.of(), "thread", List.of())),
                            "match_quality", policy ? "high" : "low", "agent_hint", policy ? "Strong match." : "Weak or no match.");
                }
                case "selflearning_status" -> Map.of("quality", Map.of("pct", 50, "potential_pct", 100, "searches", logged++, "high", 1),
                        "todos", List.of(
                                Map.of("id", "add_context", "state", "todo", "title", "Add context for 1 search", "why", "Found little.",
                                        "searches", 1, "points", 50, "examples", List.of(Map.of("label", "dispute history", "detail", "weak match"))),
                                Map.of("id", "add_content", "state", "done", "title", "Add your first document", "why", "", "searches", 0,
                                        "points", 0, "examples", List.of())));
                case "selflearning_shortfalls" -> Map.of("has_more", false, "searches", List.of(
                        Map.of("log_id", "log-1", "query", "dispute history", "state", "open"),
                        Map.of("log_id", "log-2", "query", "another use case's question", "state", "open"),
                        Map.of("log_id", "log-3", "query", "card policy", "state", "verified")));
                case "selflearning_verify" -> Map.of("query", "dispute history", "before", Map.of("band", "low"),
                        "after", Map.of("band", "high"), "outcome", "improved", "nothing_added_since_last_run", false,
                        "indexing", false, "hint", "The fix helped.");
                default -> Map.of();
            });
        }
    }

    private static FakeMeko meko() {
        MekoProperties p = new MekoProperties();
        p.setEnabled(true);
        p.setApiKey("key");
        p.setDatapackName("meko-workshop-x");
        return new FakeMeko(p);
    }

    @Test
    void checksEachQuestionOnceAcrossEverySourceAndWaitsForTheLog() throws Exception {
        FakeMeko meko = meko();
        Map<String, Object> r = new MekoSelfLearning(meko).check(List.of("card policy", " card policy ", "dispute history", ""));

        // Distinct, non-blank questions; never limited to one source (that would log as "source not searched").
        assertThat(meko.searches).extracting(a -> a.get("query")).containsExactly("card policy", "dispute history");
        assertThat(meko.searches).allSatisfy(a -> {
            assertThat(a).doesNotContainKey("force_source");
            assertThat(a).containsEntry("agent_id", MekoSelfLearning.AGENT).containsEntry("datapack_id", "dp-1");
        });
        @SuppressWarnings("unchecked")
        List<MekoSelfLearning.Check> checks = (List<MekoSelfLearning.Check>) r.get("checks");
        assertThat(checks).extracting(MekoSelfLearning.Check::quality).containsExactly("high", "low");
        assertThat(checks.get(0).knowledge()).isEqualTo(1);
        assertThat(checks.get(1).knowledge()).isZero();
        // Only the to-dos with something to do.
        assertThat((List<?>) r.get("todos")).hasSize(1);
        // The status was read again until the log held both searches.
        assertThat(meko.calls.stream().filter("selflearning_status"::equals).count()).isGreaterThan(2);
    }

    @Test
    void replaysOnlyTheOpenWeakSearchesAmongTheQuestions() throws Exception {
        FakeMeko meko = meko();
        List<MekoSelfLearning.Replay> replays = new MekoSelfLearning(meko).verify(List.of("dispute history", "card policy"));

        // log-2 is another use case's, log-3 already verified: only log-1 is replayed.
        assertThat(meko.calls.stream().filter("selflearning_verify"::equals).count()).isEqualTo(1);
        assertThat(replays).singleElement().satisfies(x -> {
            assertThat(x.before()).isEqualTo("low");
            assertThat(x.after()).isEqualTo("high");
            assertThat(x.outcome()).isEqualTo("improved");
        });
    }
}
