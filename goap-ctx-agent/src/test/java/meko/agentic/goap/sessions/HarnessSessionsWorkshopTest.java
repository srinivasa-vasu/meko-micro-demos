package meko.agentic.goap.sessions;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** In the workshop, a new session shows what the model would receive, and never asks one, whatever the model settings. */
@SpringBootTest(properties = {"meko.enabled=false", "goap.llm.enabled=true"})
@ActiveProfiles("workshop")
class HarnessSessionsWorkshopTest {
    @Autowired HarnessSessions sessions;

    @Test
    void theWorkshopShowsTheModelInputWithoutCallingAModel() {
        String s1 = (String) sessions.start("claude_code:payments-api").get("sessionId");
        sessions.say(s1, "Never log card numbers or CVVs in the payment capture path.");
        sessions.end(s1);
        Map<String, Object> r = sessions.begin("claude_code:payments-api", "Add a retry to the payment capture call.", true, s1);
        assertThat((String) r.get("modelInput")).contains("Add a retry to the payment capture call.");
        assertThat(r).doesNotContainKey("modelReply");
        assertThat((String) r.get("modelNote")).startsWith("The workshop runs without a model");
    }
}
