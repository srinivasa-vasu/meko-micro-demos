package meko.agentic.client;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DeleteDatapackTest {

    static class FakeMeko extends MekoMcpClient {
        final List<String> calls = new ArrayList<>();
        final ObjectMapper mapper = new ObjectMapper();

        FakeMeko() {
            super(properties());
        }

        static MekoProperties properties() {
            MekoProperties p = new MekoProperties();
            p.setEnabled(true);
            p.setApiKey("test");
            return p;
        }

        @Override
        public JsonNode callTool(String tool, Map<String, Object> a) {
            calls.add(tool + (a.containsKey("datapack_id") ? ":" + a.get("datapack_id") : ""));
            return switch (tool) {
                case "conversation_create" -> mapper.valueToTree(Map.of("conversation_id", "lookup"));
                case "datapack_create" -> mapper.valueToTree(Map.of("datapack_id", "dp-in-use"));
                default -> mapper.valueToTree(Map.of("ok", true));
            };
        }
    }

    @Test
    void deletingTheDatapackInUseForgetsItAndDeletingAnotherDoesNot() throws Exception {
        FakeMeko meko = new FakeMeko();
        meko.useDatapack("meko-workshop-a", true, null, null);
        assertThat(meko.datapack()).isEqualTo("dp-in-use");

        meko.deleteDatapack("dp-other");
        assertThat(meko.datapack()).isEqualTo("dp-in-use");

        meko.deleteDatapack("dp-in-use");
        assertThat(meko.calls).contains("datapack_delete:dp-other", "datapack_delete:dp-in-use");
        assertThat(meko.datapackName()).isNull();
        assertThat(meko.configured()).isFalse();
    }
}
