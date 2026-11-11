package meko.agentic.client;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MekoPropertiesTest {

    @Test
    void perToolTimeoutsBindByTheToolsOwnName() {
        var source = new MapConfigurationPropertySource(Map.of(
                "meko.timeouts.[memory_add]", "90s",
                "meko.conversation-owner", "sre-agent"));
        var bound = new Binder(source).bind("meko", MekoProperties.class).get();
        assertThat(bound.getTimeouts()).containsEntry("memory_add", Duration.ofSeconds(90));
        assertThat(bound.getConversationOwner()).isEqualTo("sre-agent");
    }
}
