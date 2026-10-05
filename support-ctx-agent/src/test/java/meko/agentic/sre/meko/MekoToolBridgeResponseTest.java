package meko.agentic.sre.meko;

import meko.agentic.sre.service.MemoryRecordParser;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Response handling, pinned against payloads captured from a live Meko datapack rather than
 * from a guess about the envelope.
 *
 * A Meko response is double-wrapped — Spring AI serializes the MCP content list, and Meko
 * nests a second JSON document inside the text part — and MemoryRecordParser anchors its
 * patterns at line start. If the unwrapping regresses, every INCIDENT_RECORD becomes
 * invisible, the reuse gate sees no history, and the demo silently runs the cold path on
 * every incident while reporting that memory was consulted. That failure is invisible from
 * the outside, which is exactly why it is worth a test.
 */
class MekoToolBridgeResponseTest {

    /** Exactly what memory_search returned from the live datapack, abridged to two rows. */
    private static final String INNER = """
            {
              "results": [
                {
                  "id": "f4103f46-fac7-4280-93bc-a21df3d72503",
                  "memory": "INCIDENT_RECORD; id=INC-4821; service=checkout; signature=Checkout failures increased sharply after a payment-service deployment; deployVersion=4.7.0; rootCause=Timeout lowered below p95.; remediation=Roll back.; scopeServices=checkout,payment-client,payment-api; observedAt=2026-09-14T03:34:56Z; modelTier=SLM; confidence=0.95",
                  "score": 0.6904974791838641
                },
                {
                  "id": "c5a9c219-0535-4f7c-bec7-8ba7bca0dad0",
                  "memory": "ROUTING_POLICY; service=checkout; incidentType=deployment regression; preferredTier=SLM; confidenceThreshold=0.75; rationale=Scoped telemetry makes the small model sufficient.",
                  "score": 0.45018733690951995
                }
              ]
            }""";

    private final MekoToolBridge bridge =
            new MekoToolBridge(null, new ObjectMapper(), "ai-sre-that-remembers");
    private final MemoryRecordParser parser = new MemoryRecordParser();

    /** Spring AI hands back the MCP content list, not the text part. */
    private static String springAiEnvelope(String inner) {
        var escaped = inner.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
        return "[{\"type\":\"text\",\"text\":\"" + escaped + "\",\"annotations\":null}]";
    }

    @Test
    void recordsLandAtLineStartSoTheParserCanSeeThem() {
        var flat = bridge.flatten(springAiEnvelope(INNER));

        // The whole point: MemoryRecordParser anchors on "(?m)^INCIDENT_RECORD".
        assertTrue(flat.contains("\nROUTING_POLICY") || flat.startsWith("ROUTING_POLICY"),
                "records must begin a line, got:\n" + flat);

        var incidents = parser.parseIncidents(flat);
        assertEquals(1, incidents.size());
        assertEquals("INC-4821", incidents.getFirst().incidentId());
        assertEquals("checkout", incidents.getFirst().service());
    }

    @Test
    void rawJsonWouldHaveHiddenEveryRecord() {
        // Guards the regression directly: unflattened, the anchored pattern matches nothing.
        assertTrue(parser.parseIncidents(springAiEnvelope(INNER)).isEmpty());
    }

    @Test
    void theSingleWrappedShapeWorksToo() {
        // Some clients hand back {"result": "<json>"} instead of the content list.
        var escaped = INNER.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
        var flat = bridge.flatten("{\"result\":\"" + escaped + "\"}");
        assertEquals(1, parser.parseIncidents(flat).size());
    }

    @Test
    void structuredParseKeepsTheRowIdThatFlatteningDiscards() {
        var rows = bridge.parseMemories(springAiEnvelope(INNER));

        assertEquals(2, rows.size());
        assertEquals("f4103f46-fac7-4280-93bc-a21df3d72503", rows.getFirst().id());
        assertTrue(rows.getFirst().text().startsWith("INCIDENT_RECORD"));
        assertEquals(0.6904974791838641, rows.getFirst().score(), 1e-12);
    }

    @Test
    void upsertMatchingIsExactOnIncidentId() {
        var rows = bridge.parseMemories(springAiEnvelope(INNER));
        var record = rows.getFirst().text();

        // The agent matches on "id=<incidentId>;" — the terminator stops INC-48 from
        // matching INC-4821 and overwriting the wrong incident.
        assertTrue(record.contains("id=INC-4821;"));
        assertFalse(record.contains("id=INC-48;"));
    }

    @Test
    void scoreAndWriterSurviveIntoTheParsedIncident() {
        // The lexical-vs-semantic panel and the cross-agent attribution both read these two
        // fields. Flattening to text drops them, which is why the agent takes the rows path.
        var incidents = parser.parseIncidents(bridge.parseMemories(springAiEnvelope(INNER)));

        assertEquals(1, incidents.size());
        assertEquals(0.6904974791838641, incidents.getFirst().semanticScore(), 1e-12);
        assertEquals("INC-4821", incidents.getFirst().incidentId());
    }

    @Test
    void aRecordWrittenByAnotherAgentKeepsThatAttribution() {
        var inner = """
                {"results":[{"id":"11111111-2222-3333-4444-555555555555",
                "memory":"INCIDENT_RECORD; id=INC-9; service=checkout; signature=x; deployVersion=1.0.0; rootCause=y; remediation=z; scopeServices=checkout; observedAt=2026-01-01T00:00:00Z",
                "score":0.5,"agent_id":"deploy-bot"}]}""";

        var incidents = parser.parseIncidents(bridge.parseMemories(springAiEnvelope(inner)));

        assertEquals("deploy-bot", incidents.getFirst().writtenBy());
    }

    @Test
    void rowsAreReadableWhateverTheTextFieldIsCalled() {
        // Three real shapes plus one invented. memory_search says "memory", memory_get_all says
        // "data", and a rename downstream must degrade to "still works" rather than to an empty
        // corpus — that failure looks identical to an unseeded datapack and cost two rounds of
        // guessing before it was found.
        var record = "INCIDENT_RECORD; id=INC-1842; service=checkout; signature=x; "
                + "deployVersion=4.7.0; rootCause=y; remediation=z; observedAt=2026-03-02T09:14:00Z";

        for (var field : java.util.List.of("memory", "data", "text", "content", "recordBody")) {
            var inner = "{\"results\":[{\"id\":\"11111111-2222-3333-4444-555555555555\","
                    + "\"agent_id\":\"sre-agent\",\"created_at\":\"2026-09-14T05:00:00Z\","
                    + "\"" + field + "\":\"" + record + "\"}]}";

            var rows = bridge.parseMemories(springAiEnvelope(inner));
            assertEquals(1, rows.size(), "field name '" + field + "' produced no rows");
            assertTrue(rows.getFirst().text().startsWith("INCIDENT_RECORD"), field);
        }
    }

    @Test
    void metadataAloneIsNotMistakenForARecord() {
        // Nothing here is a record. Returning the longest metadata string would be worse than
        // returning nothing, because it would parse as an incident with empty fields.
        var inner = "{\"results\":[{\"id\":\"11111111-2222-3333-4444-555555555555\","
                + "\"agent_id\":\"sre-agent\",\"user_email\":\"someone@example.com\","
                + "\"created_at\":\"2026-09-14T05:00:00Z\"}]}";

        assertTrue(bridge.parseMemories(springAiEnvelope(inner)).isEmpty());
    }

    @Test
    void theGetAllCollectionsAreReadToo() {
        var record = "SPEND_POLICY; budgetUsd_sev1=0.50; budgetUsd_sev2=0.15; rationale=By severity.";
        for (var key : java.util.List.of("memories", "promoted_memories")) {
            var inner = "{\"" + key + "\":[{\"id\":\"11111111-2222-3333-4444-555555555555\","
                    + "\"data\":\"" + record + "\",\"agent_id\":\"sre-agent\"}],\"total\":1}";

            var rows = bridge.parseMemories(springAiEnvelope(inner));
            assertEquals(1, rows.size(), key + " was not read");
            assertTrue(rows.getFirst().text().startsWith("SPEND_POLICY"), key);
        }
    }

    @Test
    void anEmptyResultSetIsNotAnError() {
        assertTrue(bridge.parseMemories(springAiEnvelope("{\"results\": []}")).isEmpty());
        assertTrue(parser.parseIncidents(bridge.flatten(springAiEnvelope("{\"results\": []}"))).isEmpty());
    }

    @Test
    void nonJsonComesBackUntouched() {
        assertEquals("plain text", bridge.flatten("plain text"));
        assertEquals("", bridge.flatten(null));
    }
}
