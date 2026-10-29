package meko.agentic.goap.sessions;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** New session, same context: on the stand-in, a harness session's context ends with it; through the store it carries. */
@SpringBootTest(properties = {"meko.enabled=false", "goap.llm.enabled=false"})
class HarnessSessionsTest {
    @Autowired HarnessSessions sessions;

    @SuppressWarnings("unchecked")
    private static Map<String, Integer> numbers(Map<String, Object> r) {
        return (Map<String, Integer>) r.get("numbers");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, String>> known(Map<String, Object> r) {
        return (List<Map<String, String>>) r.get("known");
    }

    @Test
    void aNewSessionStartsEmptyOnItsOwnAndWithWhatTheLastOneEstablishedThroughTheStore() {
        String s1 = (String) sessions.start("claude_code:payments-api").get("sessionId");
        sessions.say(s1, "Payment retries must be idempotent: key every retry by the original authorization id.");
        sessions.say(s1, "Never log card numbers or CVVs in the payment capture path.");
        sessions.end(s1);
        // Ended: its context is gone, and saying more to it fails.
        assertThatThrownBy(() -> sessions.say(s1, "one more thing")).hasMessageContaining("No live session");

        String task = "Add a retry to the payment capture call.";
        Map<String, Object> without = sessions.begin("claude_code:payments-api", task, false, s1);
        assertThat(known(without)).isEmpty();
        assertThat(numbers(without).get("established")).isEqualTo(2);
        assertThat(numbers(without).get("toRetell")).isEqualTo(2);
        assertThat(numbers(without).get("wordsToRetype")).isPositive();
        // What the model receives: the prompt, and no context (prompts/harness/model-input.jinja).
        assertThat((String) without.get("modelInput")).contains(task).contains("(none: a new session starts empty)");
        // Models are off here: the input is shown, and nothing pretends to be a model's answer.
        assertThat(without).doesNotContainKey("modelReply").containsKey("modelNote");

        Map<String, Object> with = sessions.begin("claude_code:payments-api", task, true, s1);
        assertThat(known(with)).isNotEmpty();
        assertThat(numbers(with).get("carried")).isPositive();
        assertThat(numbers(with).get("toRetell")).isLessThan(numbers(without).get("toRetell"));
        // The same prompt, with the context Meko returned, word for word; who wrote it is for the page, not the model.
        assertThat((String) with.get("modelInput")).contains(task).contains("Context (loaded from Meko before the prompt)")
                .contains(known(with).getFirst().get("text")).doesNotContain("written by");

        // Another harness, the same store: it knows what the first harness wrote, and says who wrote it.
        Map<String, Object> codex = sessions.begin("codex:payments-api", task, true, s1);
        assertThat(known(codex)).anySatisfy(f -> assertThat(f.get("writtenBy")).isEqualTo("claude_code:payments-api"));
    }

    @Test
    void badInputIsRefusedAndAnUnknownEarlierSessionIsNotTakenAsNothingCarried() {
        assertThatThrownBy(() -> sessions.start("")).hasMessageContaining("tool:project");
        assertThatThrownBy(() -> sessions.start("case-records")).hasMessageContaining("tool:project");
        String s1 = (String) sessions.start("claude_code:payments-api").get("sessionId");
        assertThatThrownBy(() -> sessions.say(s1, "x".repeat(HarnessSessions.MAX_TEXT + 1))).hasMessageContaining("characters");
        assertThatThrownBy(() -> sessions.begin("claude_code:payments-api", " ", true, s1)).hasMessageContaining("task");
        assertThatThrownBy(() -> sessions.begin("claude_code:payments-api", "Add a retry.", true, "SES-NOPE")).hasMessageContaining("before a restart");
    }

    /** Meko's scores for "Add a retry to the payment capture call", as a live datapack returned them. */
    private static List<HarnessSessions.Candidate> scored() {
        return List.of(
                new HarnessSessions.Candidate("m1", "Payment retries must be idempotent, keyed by the original authorization ID.", "claude_code:payments-api", "the prompt", 0.53),
                new HarnessSessions.Candidate("m2", "The team publishes payment events with the outbox pattern.", "claude_code:payments-api", "the prompt", 0.40),
                new HarnessSessions.Candidate("m3", "Never log card numbers or CVVs.", "claude_code:payments-api", "the prompt", 0.40),
                new HarnessSessions.Candidate("m4", "A payments API in Java 21 on Spring Boot, with YugabyteDB.", "claude_code:payments-api", "the prompt", 0.24),
                new HarnessSessions.Candidate("m5", "Release every Tuesday.", "claude_code:payments-api", "the project context", null));
    }

    @Test
    void withoutAModelTheRuleKeepsWhatScoresWithin70PercentOfTheTopAndSaysWhy() {
        HarnessSessions.Selection s = sessions.select("SES-T", "Add a retry to the payment capture call.", scored());
        // 70% of 0.53 is 0.371: the 0.40s are kept, 0.24 is set aside; the project search's own find is standing context.
        assertThat(s.kept()).extracting(HarnessSessions.Candidate::id).containsExactly("m1", "m2", "m3", "m5");
        assertThat(s.setAside()).singleElement().satisfies(a -> {
            assertThat(a.candidate().id()).isEqualTo("m4");
            assertThat(a.reason()).contains("0.24").contains("70%").contains("0.53");
        });
        assertThat(s.decidedBy()).startsWith("rule:");
    }

    @Test
    void aModelsJudgementIsUsedOnlyIfItAccountsForEveryFactOnce() {
        var c = scored();
        var judged = sessions.parseJudgement("Here: {\"keep\": [1, 3, 4, 5], \"setAside\": [{\"n\": 2, \"reason\": \"not about retries\"}]}", c, "model-x");
        assertThat(judged.kept()).extracting(HarnessSessions.Candidate::id).containsExactly("m1", "m3", "m4", "m5");
        assertThat(judged.setAside()).singleElement().satisfies(a -> assertThat(a.reason()).isEqualTo("not about retries"));
        assertThat(judged.decidedBy()).startsWith("model-x");
        // A fact left out, one counted twice, a number out of range, or no JSON: not used (the rule decides instead).
        assertThat(sessions.parseJudgement("{\"keep\": [1, 2, 3], \"setAside\": []}", c, "m")).isNull();
        assertThat(sessions.parseJudgement("{\"keep\": [1, 1, 2, 3, 4], \"setAside\": [{\"n\": 5}]}", c, "m")).isNull();
        assertThat(sessions.parseJudgement("{\"keep\": [1, 2, 3, 4, 9], \"setAside\": [{\"n\": 5}]}", c, "m")).isNull();
        assertThat(sessions.parseJudgement("I keep them all.", c, "m")).isNull();
    }
}
