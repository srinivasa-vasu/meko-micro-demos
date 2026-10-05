package meko.agentic.sre.meko;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What arguments each Meko tool is actually sent.
 *
 * Two production failures in a row came from assuming the tool families share an argument
 * contract when they do not:
 *
 *   - datapack_list REQUIRES conversation_id, despite its schema giving the parameter a default
 *     and listing nothing as required. Omitting it returns {"error": "conversation_id_required"}.
 *   - datapack_list REJECTS agent_id outright — "unexpected_keyword_argument" — while every
 *     memory_* tool requires it for namespacing.
 *
 * Neither is visible from the schema, both are invisible until a live call fails, and the
 * generic traced() helper attaches agent_id to everything. So the payloads are asserted here
 * instead: this is a contract test against observed server behaviour, not against the schema.
 */
class ToolArgumentContractTest {

    /** Captures the JSON each tool is called with, and answers with something parseable. */
    private static final class RecordingCallback implements ToolCallback {
        private final String name;
        private final String response;
        private final List<String> payloads = new ArrayList<>();

        RecordingCallback(String name, String response) {
            this.name = name;
            this.response = response;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return DefaultToolDefinition.builder()
                    .name(name).description(name).inputSchema("{}").build();
        }

        @Override
        public String call(String toolInput) {
            payloads.add(toolInput);
            return response;
        }
    }

    private static final String CONVERSATION =
            "{\"id\": \"d991f33f4c9014fafaa659eec9672bc2\", \"title\": \"AI SRE bootstrap\"}";
    private static final String DATAPACKS =
            "[{\"datapack_id\": \"6205bfaf-fdc7-4dd4-b558-003a7237e4b5\", \"datapack_name\": \"change-risk\"}]";
    private static final String EMPTY_RESULTS = "{\"results\": []}";

    private final Map<String, RecordingCallback> calls = new LinkedHashMap<>();

    /** knowledgebase_search returning more chunks than were asked for. */
    private static String chunks(int howMany) {
        var rows = new StringBuilder();
        for (int i = 0; i < howMany; i++) {
            if (i > 0) {
                rows.append(',');
            }
            rows.append("{\"id\":\"chunk-").append(i)
                .append("\",\"chunk_text\":\"Section: Runbook > Part ").append(i)
                .append("\\n\\nSome operational prose that costs tokens to include.\"}");
        }
        return "{\"results\":[" + rows + "],\"count\":" + howMany + "}";
    }

    private MekoToolBridge bridgeWith(String... toolNames) {
        var callbacks = new ArrayList<ToolCallback>();
        for (var name : toolNames) {
            var response = switch (name) {
                case "conversation_create" -> CONVERSATION;
                case "datapack_list" -> DATAPACKS;
                case "knowledgebase_search" -> chunks(13);
                default -> EMPTY_RESULTS;
            };
            // Namespaced the way Spring AI registers MCP tools, to exercise suffix matching.
            var cb = new RecordingCallback("mekodata_" + name, response);
            calls.put(name, cb);
            callbacks.add(cb);
        }
        var provider = Mockito.mock(SyncMcpToolCallbackProvider.class);
        Mockito.when(provider.getToolCallbacks()).thenReturn(callbacks.toArray(new ToolCallback[0]));
        return new MekoToolBridge(provider, new ObjectMapper(), "sre-agent");
    }

    private Map<String, Object> lastArgsFor(String tool) {
        var payloads = calls.get(tool).payloads;
        assertFalse(payloads.isEmpty(), tool + " was never called");
        return new ObjectMapper().readValue(payloads.getLast(), Map.class);
    }

    @Test
    void datapackListIsSentAConversationIdAndNothingElse() {
        var bridge = bridgeWith("conversation_create", "datapack_list");

        var id = bridge.resolveDatapackId("change-risk");

        assertEquals("6205bfaf-fdc7-4dd4-b558-003a7237e4b5", id.orElse(null));
        var args = lastArgsFor("datapack_list");
        assertTrue(args.containsKey("conversation_id"),
                "omitting it returns error conversation_id_required");
        assertFalse(args.containsKey("agent_id"),
                "datapack_list rejects agent_id with unexpected_keyword_argument");
    }

    @Test
    void theBootstrapConversationCarriesNoDatapackId() {
        var bridge = bridgeWith("conversation_create", "datapack_list");

        bridge.resolveDatapackId("change-risk");

        // The whole point of the bootstrap conversation: it exists so datapack_list can run
        // BEFORE a datapack is known, so it cannot itself require one.
        var args = lastArgsFor("conversation_create");
        assertFalse(args.containsKey("datapack_id"));
        assertEquals("sre-agent", args.get("agent_id"));
        assertTrue(String.valueOf(args.get("session_id")).endsWith(":bootstrap"));
    }

    /**
     * Every argument this bridge sends, per tool, as observed from the live server.
     *
     * Sending an argument a tool does not declare is rejected outright with
     * "unexpected_keyword_argument" — it is not ignored. That has now broken two different calls
     * in this file (datapack_list + agent_id, memory_get_all + page), and both times the call
     * threw, the catch returned an empty list, and an empty list reads as "the datapack has
     * nothing in it". Silent, and identical to the failure it is not.
     */
    @Test
    void memoryGetAllIsSentOnlyTheArgumentsItDeclares() {
        var bridge = bridgeWith("conversation_create", "memory_get_all");
        var datapack = "6205bfaf-fdc7-4dd4-b558-003a7237e4b5";

        bridge.memoryGetAll(datapack, false);

        var args = lastArgsFor("memory_get_all");
        assertEquals(Set.of("conversation_id", "datapack_id", "agent_id", "promoted"), args.keySet(),
                "memory_get_all declares agent_id, conversation_id, datapack_id, promoted and "
                        + "run_id. Anything else — 'page' especially — is rejected, not ignored.");
        assertEquals(false, args.get("promoted"));
    }

    @Test
    void thePromotedCollectionIsRequestedWithTheSameArgumentSet() {
        var bridge = bridgeWith("conversation_create", "memory_get_all");

        bridge.memoryGetAll("6205bfaf-fdc7-4dd4-b558-003a7237e4b5", true);

        var args = lastArgsFor("memory_get_all");
        assertEquals(Set.of("conversation_id", "datapack_id", "agent_id", "promoted"), args.keySet());
        assertEquals(true, args.get("promoted"));
    }

    @Test
    void knowledgeChunksAreTrimmedToTheRequestedLimit() {
        // knowledgebase_search does not honour limit. Measured live, a request for 5 chunks came
        // back with 13 — so a caller that budgets its prompt silently gets 2.6x what it planned
        // for, and on this corpus that is roughly 1,800 tokens of prose competing for the model's
        // attention with the short records carrying the exact version and timeout value.
        var bridge = bridgeWith("conversation_create", "knowledgebase_search");

        var rows = bridge.knowledgeSearchRecords("checkout timeout",
                "6205bfaf-fdc7-4dd4-b558-003a7237e4b5", 5);

        assertEquals(5, rows.size(), "the caller's budget has to mean something");
        // Rows arrive ranked, so trimming keeps the best ones rather than an arbitrary slice.
        assertEquals("chunk-0", rows.getFirst().id());
    }

    @Test
    void askingForMoreChunksThanExistIsNotAnError() {
        var bridge = bridgeWith("conversation_create", "knowledgebase_search");

        assertEquals(13, bridge.knowledgeSearchRecords("q", "dp", 50).size());
    }

    @Test
    void memoryToolsDoGetTheAgentId() {
        var bridge = bridgeWith("conversation_create", "memory_search", "memory_add");
        var datapack = "6205bfaf-fdc7-4dd4-b558-003a7237e4b5";

        bridge.memorySearch("checkout failures", datapack, 8);
        bridge.memoryAdd("INCIDENT_RECORD; id=X;", datapack);

        for (var tool : List.of("memory_search", "memory_add")) {
            var args = lastArgsFor(tool);
            assertEquals("sre-agent", args.get("agent_id"), tool + " needs agent_id for namespacing");
            assertTrue(args.containsKey("conversation_id"), tool + " needs conversation_id");
            assertEquals(datapack, args.get("datapack_id"), tool + " needs the datapack");
        }
    }

    @Test
    void aPerDatapackConversationIsCreatedOnceAndReused() {
        var bridge = bridgeWith("conversation_create", "memory_search");
        var datapack = "6205bfaf-fdc7-4dd4-b558-003a7237e4b5";

        bridge.memorySearch("one", datapack, 8);
        bridge.memorySearch("two", datapack, 8);
        bridge.memorySearch("three", datapack, 8);

        assertEquals(1, calls.get("conversation_create").payloads.size(),
                "a conversation per call would litter the datapack with empty containers");
        assertEquals(3, calls.get("memory_search").payloads.size());
    }

    @Test
    void writingAsAnotherAgentOverridesOnlyTheAgentId() {
        var bridge = bridgeWith("conversation_create", "memory_add");
        var datapack = "6205bfaf-fdc7-4dd4-b558-003a7237e4b5";

        bridge.memoryAdd("DEPLOY_EVENT; service=payment-service;", datapack, "platform-deploy-bot");

        var args = lastArgsFor("memory_add");
        assertEquals("platform-deploy-bot", args.get("agent_id"));
        assertEquals(datapack, args.get("datapack_id"));
        assertTrue(args.containsKey("conversation_id"));
    }
}
