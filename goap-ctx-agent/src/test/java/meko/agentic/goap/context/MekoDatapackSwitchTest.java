package meko.agentic.goap.context;

import meko.agentic.client.MekoProperties;
import meko.agentic.client.MekoMcpClient;
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
        final Map<String, String> projects = new java.util.LinkedHashMap<>();
        final Map<String, String> folders = new java.util.LinkedHashMap<>();
        final Map<String, String> filed = new java.util.LinkedHashMap<>();
        private final ObjectMapper json = new ObjectMapper();

        FakeMeko(MekoProperties p) {
            super(p);
        }

        @Override
        public JsonNode callTool(String tool, Map<String, Object> args) {
            calls.add(tool);
            if (tool.equals("datapack_create") && args.containsKey("folder_id")) {
                if (!folders.containsValue(args.get("folder_id"))) throw new IllegalStateException("datapack_create failed: unknown folder");
                filed.put("dp-" + datapacks.size(), String.valueOf(args.get("folder_id")));
            }
            return switch (tool) {
                case "datapack_create" -> {
                    datapacks.put(String.valueOf(args.get("name")), "dp-" + datapacks.size());
                    yield json.valueToTree(Map.of("datapack_id", datapacks.get(String.valueOf(args.get("name")))));
                }
                case "datapack_list" -> json.valueToTree(datapacks.entrySet().stream()
                        .map(e -> Map.of("datapack_name", e.getKey(), "datapack_id", e.getValue())).toList());
                case "conversation_create" -> json.valueToTree(Map.of("id", "conv-" + args.get("session_id")));
                case "project_list" -> json.valueToTree(Map.of("owned", projects.entrySet().stream()
                        .map(e -> Map.of("project_name", e.getKey(), "project_id", e.getValue())).toList(), "shared", List.of()));
                case "project_create" -> {
                    String id = "proj-" + projects.size();
                    projects.put(String.valueOf(args.get("project_name")), id);
                    for (Object f : (List<?>) args.get("folders")) folders.put(id + "/" + f, "folder-" + folders.size());
                    yield json.valueToTree(Map.of("project_id", id));
                }
                case "project_describe" -> json.valueToTree(Map.of("folders", folders.entrySet().stream()
                        .filter(e -> e.getKey().startsWith(args.get("project_id") + "/"))
                        .map(e -> Map.of("folder_name", e.getKey().substring(e.getKey().indexOf('/') + 1), "folder_id", e.getValue())).toList()));
                case "project_folder_create" -> {
                    String id = "folder-" + folders.size();
                    folders.put(args.get("project_id") + "/" + args.get("folder_name"), id);
                    yield json.valueToTree(Map.of("folder_id", id));
                }
                case "project_add_datapack" -> {
                    filed.put(String.valueOf(args.get("datapack_id")), String.valueOf(args.get("folder_id")));
                    yield json.valueToTree(Map.of("ok", true));
                }
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

    @Test
    void aCreatedDatapackIsFiledInTheWorkshopProjectByVertical() throws Exception {
        MekoProperties p = new MekoProperties();
        p.setEnabled(true);
        p.setApiKey("key");
        FakeMeko meko = new FakeMeko(p);
        // A project of the participant's own stays as it is.
        meko.projects.put("goap", "proj-existing");
        meko.folders.put("proj-existing/policy", "folder-existing");

        String bfsi = meko.useDatapack("meko-workshop-20261106-ab12-dispute", true);
        meko.fileInProject(bfsi, "meko-workshop", "", "BFSI", "");
        String sdlc = meko.useDatapack("meko-workshop-20261106-ab12-sessions", true);
        meko.fileInProject(sdlc, "meko-workshop", "", "SDLC", "");
        String kyc = meko.useDatapack("meko-workshop-20261106-ab12-kyc", true);
        meko.fileInProject(kyc, "meko-workshop", "", "BFSI", "");

        // One project, created once, with one folder per vertical; both BFSI datapacks in the same folder.
        assertThat(meko.calls.stream().filter("project_create"::equals).count()).isEqualTo(1);
        assertThat(meko.calls.stream().filter("project_folder_create"::equals).count()).isEqualTo(1);
        String project = meko.projects.get("meko-workshop");
        assertThat(meko.filed).containsEntry(bfsi, meko.folders.get(project + "/BFSI"))
                .containsEntry(kyc, meko.folders.get(project + "/BFSI"))
                .containsEntry(sdlc, meko.folders.get(project + "/SDLC"));
        assertThat(meko.folders).containsEntry("proj-existing/policy", "folder-existing").hasSize(3);
    }

    @Test
    void aKnownFolderCostsNoCallAndTheDatapackIsCreatedIntoIt() throws Exception {
        MekoProperties p = new MekoProperties();
        p.setEnabled(true);
        p.setApiKey("key");
        FakeMeko meko = new FakeMeko(p);
        meko.projects.put("meko-workshop", "proj-w");
        meko.folders.put("proj-w/BFSI", "folder-b");

        // The warm-up finds the project and its folders, creating nothing.
        meko.findProject("meko-workshop");
        assertThat(meko.calls).doesNotContain("project_create", "project_folder_create");
        meko.calls.clear();

        String folder = meko.projectFolder("meko-workshop", "", "BFSI", "");
        assertThat(folder).isEqualTo("folder-b");
        assertThat(meko.calls).as("known from the warm-up").isEmpty();

        String id = meko.useDatapack("meko-workshop-20261107-ab12-kyc", true, null, folder);
        assertThat(meko.filed).containsEntry(id, "folder-b");
        assertThat(meko.calls).doesNotContain("project_add_datapack");

        // A project that is not there is not created by the warm-up.
        FakeMeko fresh = new FakeMeko(p);
        fresh.findProject("meko-workshop");
        assertThat(fresh.projects).doesNotContainKey("meko-workshop");
    }

    @Test
    void aRecentListingIsReusedAndACreateForgetsIt() throws Exception {
        MekoProperties p = new MekoProperties();
        p.setEnabled(true);
        p.setApiKey("key");
        FakeMeko meko = new FakeMeko(p);
        meko.listDatapacks();
        long listings = meko.calls.stream().filter("datapack_list"::equals).count();
        assertThat(meko.listDatapacks(60_000)).extracting(d -> d.get("name")).contains("meko-goap");
        assertThat(meko.calls.stream().filter("datapack_list"::equals).count()).isEqualTo(listings);
        // Older than allowed: listed again.
        meko.listDatapacks(-1);
        assertThat(meko.calls.stream().filter("datapack_list"::equals).count()).isEqualTo(listings + 1);
        // A create changes the account: the next check lists again and sees it.
        meko.useDatapack("meko-workshop-20261107-cd34", true);
        assertThat(meko.listDatapacks(60_000)).extracting(d -> d.get("name")).contains("meko-workshop-20261107-cd34");
    }
}
