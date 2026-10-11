package meko.console.mcp;

import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * context_search buckets, memory_get_by_id's bare object, and raw call templates.
 *
 * The bucket names below are illustrative: no live context_search payload has been captured
 * yet, which is exactly why the parser reads names off the response instead of expecting them.
 */
class ContextAndTemplateTest {

    private final MekoClient client = new MekoClient(null, "meko-console");

    private static String sse(String inner) {
        var escaped = inner.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
        return "event: message\ndata: [{\"type\":\"text\",\"text\":\"" + escaped + "\"}]\n\n";
    }

    @Test
    void everySourceIsItsOwnBucketAndAnEmptyOneIsKept() {
        var inner = "{\"conversation\":[],"
                + "\"memory\":{\"results\":[{\"id\":\"m1\",\"memory\":\"INCIDENT_RECORD; id=INC-1\","
                + "\"score\":0.4,\"raw_similarity_score\":0.3}]},"
                + "\"knowledge_base\":[{\"id\":\"k1\",\"chunk_text\":\"Roll back the release first.\"}],"
                + "\"query\":\"checkout\"}";

        var buckets = client.buckets(sse(inner));

        assertEquals(List.of("conversation", "memory", "knowledge_base"),
                List.copyOf(buckets.keySet()), "a scalar field like query is not a bucket");
        assertTrue(buckets.get("conversation").isEmpty(),
                "an empty source must say so, not disappear");
        assertEquals("m1", buckets.get("memory").getFirst().id());
        assertEquals("k1", buckets.get("knowledge_base").getFirst().id());
    }

    @Test
    void aResultEnvelopeIsPeeled() {
        var raw = "{\"result\":\"{\\\"memory\\\":[{\\\"id\\\":\\\"m1\\\","
                + "\\\"memory\\\":\\\"a memory long enough\\\"}]}\"}";

        assertEquals(1, client.buckets(raw).get("memory").size());
    }

    @Test
    void withBucketsKeepsRowsAsTheUnionSoCountsStayHonest() {
        var row = new MekoRow("a", "text", -1, -1, "", "");
        var call = McpCall.failed("context_search", java.util.Map.of(), null)
                .withBuckets(java.util.Map.of("memory", List.of(row), "conversation", List.of()));

        assertEquals(1, call.rows().size());
        assertEquals(2, call.buckets().size());
    }

    @Test
    void aBareMemoryObjectReadsAsOneRow() {
        var row = client.singleRow(sse("{\"id\":\"m1\",\"memory\":\"SPEND_POLICY; budget=0.15\","
                + "\"agent_id\":\"sre-agent\"}"));

        assertNotNull(row);
        assertEquals("m1", row.id());
        assertEquals("sre-agent", row.writtenBy());
    }

    @Test
    void anErrorIsNotReadAsAMemory() {
        assertNull(client.singleRow(sse("{\"error\":\"not_found\",\"detail\":\"no memory m1\"}")));
    }

    // ------------------------------------------------------------------
    // Raw call templates
    // ------------------------------------------------------------------

    private MekoClient withTool(String name, String schema) {
        var callback = mock(ToolCallback.class);
        when(callback.getToolDefinition()).thenReturn(DefaultToolDefinition.builder()
                .name(name).description("").inputSchema(schema).build());
        var provider = mock(SyncMcpToolCallbackProvider.class);
        when(provider.getToolCallbacks()).thenReturn(new ToolCallback[] {callback});
        return new MekoClient(provider, "meko-console");
    }

    @Test
    void theTemplateHoldsRequiredArgumentsAsTypedPlaceholders() {
        var client = withTool("mekodata_artifact_get", """
                {"type":"object","properties":{
                  "artifact_id":{"type":"string"},
                  "limit":{"type":"integer","default":25},
                  "tags":{"type":"array","items":{"type":"string"}},
                  "verbose":{"anyOf":[{"type":"boolean"},{"type":"null"}],"default":null}},
                 "required":["artifact_id","limit","tags"]}""");

        var t = client.template("artifact_get", null);

        assertNull(t.error());
        assertEquals("<artifact_id>", t.arguments().get("artifact_id"));
        assertEquals(25, t.arguments().get("limit"), "a schema default beats a bare zero");
        assertEquals(List.of("<tags>"), t.arguments().get("tags"));
        assertFalse(t.arguments().containsKey("verbose"), "optional arguments are listed, not sent");
        assertEquals(List.of("verbose"), t.optional());
    }

    @Test
    void anArgumentTheSchemaLacksIsNeverAdded() {
        // datapack_list rejects agent_id. The template must not hand it one.
        var client = withTool("mekodata_datapack_list", """
                {"type":"object","properties":{
                  "datapack_id":{"anyOf":[{"type":"string"},{"type":"null"}],"default":null}}}""");

        var t = client.template("datapack_list", "dp-1");

        assertEquals(java.util.Map.of("datapack_id", "dp-1"), t.arguments());
    }

    @Test
    void contextArgumentsAreIncludedEvenWhenTheSchemaCallsThemOptional() {
        var client = withTool("mekodata_memory_get_by_id", """
                {"type":"object","properties":{
                  "agent_id":{"anyOf":[{"type":"string"},{"type":"null"}],"default":null},
                  "datapack_id":{"anyOf":[{"type":"string"},{"type":"null"}],"default":null},
                  "memory_id":{"type":"string"}},
                 "required":["memory_id"]}""");

        var t = client.template("memory_get_by_id", "dp-1");

        assertEquals("meko-console", t.arguments().get("agent_id"));
        assertEquals("dp-1", t.arguments().get("datapack_id"));
        assertEquals("<memory_id>", t.arguments().get("memory_id"));
    }

    @Test
    void anUnregisteredToolSaysSo() {
        var t = withTool("mekodata_memory_search", "{}").template("no_such_tool", null);

        assertNotNull(t.error());
    }
}
