package meko.console.mcp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Response handling, pinned against payloads captured from a live Meko datapack.
 *
 * Each shape here was a bug before it was a test. The field name carrying the payload differs by
 * endpoint, the framing differs by transport, and an error is valid JSON with zero rows — so
 * "nothing came back" and "the call was refused" are indistinguishable without checking.
 */
class MekoClientResponseTest {

    private final MekoClient client = new MekoClient(null, "meko-console");

    private static String sse(String inner) {
        var escaped = inner.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
        return "event: message\ndata: [{\"type\":\"text\",\"text\":\"" + escaped + "\"}]\n\n";
    }

    @Test
    void memorySearchRowsAreRead() {
        var inner = "{\"results\":[{\"id\":\"11111111-1111-1111-1111-111111111111\","
                + "\"memory\":\"INCIDENT_RECORD; id=INC-1842; service=checkout\","
                + "\"score\":0.268,\"raw_similarity_score\":0.498,\"agent_id\":\"sre-agent\"}]}";

        var rows = client.rows(sse(inner));

        assertEquals(1, rows.size());
        assertTrue(rows.getFirst().text().startsWith("INCIDENT_RECORD"));
        assertEquals("sre-agent", rows.getFirst().writtenBy());
        assertTrue(rows.getFirst().hybridApplied(),
                "score differing from raw similarity is how you tell a second ranking stage ran");
    }

    @Test
    void memoryGetAllUsesDataNotMemory() {
        // The exact difference that made an existence check silently return nothing.
        var inner = "{\"memories\":[{\"id\":\"22222222-2222-2222-2222-222222222222\","
                + "\"data\":\"SPEND_POLICY; budgetUsd_sev2=0.15\",\"agent_id\":\"sre-agent\"}],"
                + "\"total\":1}";

        var rows = client.rows(sse(inner));

        assertEquals(1, rows.size());
        assertTrue(rows.getFirst().text().startsWith("SPEND_POLICY"));
    }

    @Test
    void promotedMemoriesAreTheirOwnCollection() {
        var inner = "{\"promoted_memories\":[{\"id\":\"33333333-3333-3333-3333-333333333333\","
                + "\"data\":\"ROUTING_POLICY; service=checkout; preferredTier=SLM\"}],\"total\":1}";

        assertEquals(1, client.rows(sse(inner)).size());
    }

    @Test
    void knowledgeChunksAreRead() {
        var inner = "{\"results\":[{\"id\":\"44444444-4444-4444-4444-444444444444\","
                + "\"chunk_text\":\"Section: Runbook > Remediation\\n\\nRoll back the release.\","
                + "\"document_id\":\"doc-1\",\"match_type\":\"hybrid\",\"rank\":0.068,"
                + "\"rerank_score\":0.998}]}";

        var rows = client.rows(sse(inner));

        assertEquals(1, rows.size());
        assertTrue(rows.getFirst().text().contains("Roll back"));
    }

    @Test
    void datapackListIsABareArray() {
        var inner = "[{\"datapack_id\":\"6205bfaf-fdc7-4dd4-b558-003a7237e4b5\","
                + "\"datapack_name\":\"change-risk\",\"grant\":\"owner\"}]";

        var rows = client.rows(sse(inner));

        assertEquals(1, rows.size());
        assertEquals("6205bfaf-fdc7-4dd4-b558-003a7237e4b5", rows.getFirst().id());
        assertEquals("change-risk", rows.getFirst().text());
    }

    @Test
    void anUnknownPayloadFieldStillReads() {
        // Guessing field names cost two rounds of debugging; the fallback is the longest
        // non-metadata string, which the payload always is.
        var inner = "{\"results\":[{\"id\":\"55555555-5555-5555-5555-555555555555\","
                + "\"agent_id\":\"x\",\"someFutureField\":\"INCIDENT_RECORD; id=INC-9; service=s\"}]}";

        assertEquals(1, client.rows(sse(inner)).size());
    }

    @Test
    void anErrorPayloadIsNotMistakenForAnEmptyResult() {
        var refusal = sse("{\"error\":\"conversation_id_required\","
                + "\"detail\":\"Pass conversation_id from the stored chat\"}");

        var error = client.errorIn(refusal);

        assertNotNull(error);
        assertTrue(error.contains("conversation_id_required"), error);
        assertTrue(client.rows(refusal).isEmpty());
    }

    @Test
    void aNormalResponseCarriesNoError() {
        assertNull(client.errorIn(sse("{\"results\":[]}")));
    }

    @Test
    void metadataAloneIsNotMistakenForAPayload() {
        var inner = "{\"results\":[{\"id\":\"66666666-6666-6666-6666-666666666666\","
                + "\"agent_id\":\"x\",\"created_at\":\"2026-09-14T05:00:00Z\"}]}";

        // An id with no payload is still a row (datapacks look like this), but the text is null
        // rather than a timestamp masquerading as content.
        var rows = client.rows(inner);
        assertTrue(rows.isEmpty() || rows.getFirst().text() == null);
    }

    @Test
    void plainJsonWorksAsWellAsSse() {
        var inner = "{\"results\":[{\"id\":\"77777777-7777-7777-7777-777777777777\","
                + "\"memory\":\"hello world from a plain response\"}]}";

        assertEquals(1, client.rows(inner).size());
    }
}
