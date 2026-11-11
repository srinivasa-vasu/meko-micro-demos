package meko.agentic.goap.api;

import meko.agentic.client.MekoMcpClient;
import meko.agentic.client.MekoProperties;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WorkshopProgressTest {

    /** Meko as far as progress needs it: datapacks, one conversation per session id, its messages, and artifacts. */
    static class FakeMeko extends MekoMcpClient {
        final ObjectMapper mapper = new ObjectMapper();
        final List<Map<String, String>> datapacks = new ArrayList<>();
        final Map<String, String> sessions = new HashMap<>();
        final Map<String, List<Map<String, Object>>> messages = new HashMap<>();
        final Map<String, String> artifacts = new HashMap<>();
        final List<String> datapackIdsUsed = new ArrayList<>();
        int created;

        FakeMeko() {
            super(withKey());
        }

        private static MekoProperties withKey() {
            MekoProperties p = new MekoProperties();
            p.setEnabled(true);
            p.setApiKey("test");
            return p;
        }

        @Override
        public List<Map<String, String>> listDatapacks() {
            return datapacks;
        }

        @Override
        public String createDatapack(String name, String folderId) {
            created++;
            datapacks.add(Map.of("name", name, "id", "dp-progress", "grant", "owner"));
            return "dp-progress";
        }

        @Override
        public synchronized String projectFolder(String project, String description, String folder, String folderDescription) {
            return "folder-1";
        }

        @Override
        public JsonNode callTool(String tool, Map<String, Object> a) {
            datapackIdsUsed.add(String.valueOf(a.get("datapack_id")));
            return switch (tool) {
                case "conversation_create" -> mapper.valueToTree(Map.of("conversation_id",
                        sessions.computeIfAbsent((String) a.get("session_id"), k -> "conv-" + sessions.size())));
                case "artifact_put" -> {
                    String hash = "h" + artifacts.size();
                    artifacts.put(hash, (String) a.get("content_base64"));
                    yield mapper.valueToTree(Map.of("content_hash", hash));
                }
                case "conversation_add_message" -> {
                    messages.computeIfAbsent((String) a.get("conversation_id"), k -> new ArrayList<>())
                            .add(Map.of("metadata", a.get("metadata"), "input", a.get("input")));
                    yield mapper.valueToTree(Map.of("ok", true));
                }
                case "conversation_get" -> mapper.valueToTree(Map.of("messages", messages.getOrDefault((String) a.get("conversation_id"), List.of())));
                case "artifact_get" -> mapper.valueToTree(Map.of("content_base64", artifacts.get((String) a.get("content_hash"))));
                default -> throw new IllegalStateException("unexpected " + tool);
            };
        }
    }

    @Test
    void runsAreRecordedInTheProgressDatapackAndReadBackLatestFirst() throws Exception {
        FakeMeko meko = new FakeMeko();
        WorkshopProgress progress = new WorkshopProgress(meko);
        progress.save("dp-workshop-a", "meko-workshop-a", "what-changed", 0, "run", Map.of("outcome", "first"));
        progress.save("dp-workshop-a", "meko-workshop-a", "what-changed", 0, "run", Map.of("outcome", "again"));
        progress.save("dp-workshop-a", "meko-workshop-a", "what-changed", 3, "audit", Map.of("outcome", "audited"));

        // A fresh application (a restart): the record is read back from Meko, not from anything kept in memory.
        WorkshopProgress restarted = new WorkshopProgress(meko);
        Map<String, Object> read = restarted.load("dp-workshop-a");
        assertThat(read).containsEntry("available", true);
        assertThat(((Map<?, ?>) ((Map<?, ?>) read.get("runs")).get("what-changed")).get("0")).isEqualTo(Map.of("outcome", "again"));
        assertThat(((Map<?, ?>) ((Map<?, ?>) read.get("audits")).get("what-changed")).get("3")).isEqualTo(Map.of("outcome", "audited"));

        // One progress datapack, found again after the restart; every record written there, never in a workshop datapack.
        assertThat(meko.created).isEqualTo(1);
        assertThat(meko.datapackIdsUsed).containsOnly("dp-progress");
    }

    @Test
    void aNewWorkshopDatapackStartsWithAnEmptyRecord() throws Exception {
        FakeMeko meko = new FakeMeko();
        WorkshopProgress progress = new WorkshopProgress(meko);
        progress.save("dp-workshop-a", "a", "what-changed", 0, "run", Map.of("outcome", "a's run"));
        Map<String, Object> other = progress.load("dp-workshop-b");
        assertThat((Map<?, ?>) other.get("runs")).isEmpty();
        progress.save("dp-workshop-b", "b", "what-changed", 0, "run", Map.of("outcome", "b's run"));
        // Both workshop datapacks on one record: one conversation for the participant, whatever the number of datapacks.
        assertThat(meko.sessions).hasSize(1);
        assertThat(((Map<?, ?>) ((Map<?, ?>) new WorkshopProgress(meko).load("dp-workshop-a").get("runs")).get("what-changed")).get("0"))
                .isEqualTo(Map.of("outcome", "a's run"));
    }

    @Test
    void theProgressDatapackIsNeverOfferedAsAWorkshopDatapack() {
        assertThat(WorkshopProgress.isProgress("meko-workshop-progress-20261110-ab12")).isTrue();
        assertThat(WorkshopProgress.isProgress("meko-workshop-20261110-ab12")).isFalse();
    }
}
