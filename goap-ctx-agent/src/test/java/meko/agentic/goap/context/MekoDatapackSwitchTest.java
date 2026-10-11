package meko.agentic.goap.context;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The workshop creates a fresh datapack from its page, and the client switches to it. */
class MekoDatapackSwitchTest {

    /** A fake Meko that knows one datapack and creates others on request. */
    static class FakeMeko extends MekoMcpClient {
        final List<String> calls = new ArrayList<>();
        final Map<String, String> datapacks = new java.util.LinkedHashMap<>(Map.of("meko-goap", "dp-demo"));
        private final ObjectMapper json = new ObjectMapper();

        FakeMeko(MekoProperties p) {
            super(p);
        }

        @Override
        public JsonNode callTool(String tool, Map<String, Object> args) {
            calls.add(tool);
            return switch (tool) {
                case "datapack_create" -> {
                    datapacks.put(String.valueOf(args.get("name")), "dp-" + datapacks.size());
                    yield json.valueToTree(Map.of("datapack_id", datapacks.get(String.valueOf(args.get("name")))));
                }
                case "datapack_list" -> json.valueToTree(datapacks.entrySet().stream()
                        .map(e -> Map.of("datapack_name", e.getKey(), "datapack_id", e.getValue())).toList());
                case "conversation_create" -> json.valueToTree(Map.of("id", "conv-" + args.get("session_id")));
                default -> json.valueToTree(Map.of());
            };
        }
    }

    @Test
    void aCreatedDatapackBecomesTheOneInUse() throws Exception {
        MekoProperties p = new MekoProperties();
        p.setEnabled(true);
        p.setApiKey("key");
        FakeMeko meko = new FakeMeko(p);
        assertThat(meko.keyed()).isTrue();
        assertThat(p.configured()).as("a key and no datapack: the workshop asks for one").isFalse();

        assertThat(meko.useDatapack("meko-workshop-20261010-ab12", true)).isEqualTo("dp-1");
        assertThat(p.configured()).isTrue();
        assertThat(meko.datapackName()).isEqualTo("meko-workshop-20261010-ab12");
        assertThat(meko.calls).contains("datapack_create");

        // Using an existing one resolves it by name.
        assertThat(meko.useDatapack("meko-goap", false)).isEqualTo("dp-demo");

        // "Use existing" lists what the account holds.
        assertThat(meko.listDatapacks()).extracting(d -> d.get("name")).contains("meko-goap", "meko-workshop-20261010-ab12");

        // Deleted in the Meko app while the application runs: noticed, and forgotten, so a new one is asked for.
        meko.expireConfirmation();
        assertThat(meko.datapackStillExists()).isTrue();
        // Confirmed moments ago: a status poll does not list the datapacks again.
        int listings = (int) meko.calls.stream().filter("datapack_list"::equals).count();
        assertThat(meko.datapackStillExists()).isTrue();
        assertThat(meko.calls.stream().filter("datapack_list"::equals).count()).isEqualTo(listings);
        // The lookup conversation is created once, not before every listing.
        assertThat(meko.calls.stream().filter("conversation_create"::equals).count()).isEqualTo(1);
        meko.datapacks.remove("meko-goap");
        meko.expireConfirmation();
        assertThat(meko.datapackStillExists()).isFalse();
        assertThat(p.configured()).isFalse();
        assertThat(meko.keyed()).isTrue();
    }
}
