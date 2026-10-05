package meko.agentic.goap.story.store;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

class MekoContextStoreTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void aDecisionAnswerReturnedAsParsedJsonIsKeptAsText() {
        // A decision's answer is written as a JSON string; conversation_get can hand it back parsed.
        var message = json.readTree("{\"output\":{\"policy\":\"CARD-DISPUTE-07\",\"reason\":\"covers a capture timeout\"},"
                + "\"input\":\"Which policy governs this situation?\",\"reasoning\":null}");
        assertThat(MekoContextStore.text(message.path("output"))).isEqualTo("{\"policy\":\"CARD-DISPUTE-07\",\"reason\":\"covers a capture timeout\"}");
        assertThat(MekoContextStore.text(message.path("input"))).isEqualTo("Which policy governs this situation?");
        assertThat(MekoContextStore.text(message.path("reasoning"))).isEmpty();
        assertThat(MekoContextStore.text(message.path("missing"))).isEmpty();
    }
}
