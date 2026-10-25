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

        Map<String, Object> with = sessions.begin("claude_code:payments-api", task, true, s1);
        assertThat(known(with)).isNotEmpty();
        assertThat(numbers(with).get("carried")).isPositive();
        assertThat(numbers(with).get("toRetell")).isLessThan(numbers(without).get("toRetell"));

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
}
