package meko.console.mcp;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The browser reads these field names off the wire, so they are a contract.
 *
 * MekoRow is a Java record. Jackson serialises record COMPONENTS; extra zero-arg methods like
 * ranked() and hybridApplied() are not components and do not appear. The console read r.ranked,
 * got undefined, treated it as false, and rendered every score as an em dash — while raw
 * similarity displayed fine, because that one is a component. Nothing failed; a whole column
 * was just quietly blank.
 */
class RowSerializationTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @SuppressWarnings("unchecked")
    private Map<String, Object> json(Object o) {
        return mapper.readValue(mapper.writeValueAsString(o), Map.class);
    }

    @Test
    void everyFieldTheUiReadsIsOnTheWire() {
        var row = new MekoRow("id-1", "INCIDENT_RECORD; id=INC-1842", 0.268, 0.498,
                "sre-agent", "2026-09-14T05:00:00Z");

        var fields = json(row).keySet();

        for (var name : List.of("id", "text", "score", "rawScore", "writtenBy", "createdAt")) {
            assertTrue(fields.contains(name), "the console reads r." + name + ": " + fields);
        }
    }

    @Test
    void derivedHelpersAreNotOnTheWireAndMustNotBeReliedOn() {
        var fields = json(new MekoRow("id", "t", 0.5, 0.5, "a", "")).keySet();

        // If these ever start serialising, the JS could use them — but it must not assume so.
        assertFalse(fields.contains("ranked"));
        assertFalse(fields.contains("hybridApplied"));
    }

    @Test
    void anUnrankedRowIsNegativeNotZero() {
        // Zero is a real score. Unranked must be distinguishable from "scored zero", or a
        // listing looks like a search that matched nothing.
        var row = new MekoRow("id", "t", MekoRow.UNRANKED, MekoRow.UNRANKED, "a", "");

        assertTrue(((Number) json(row).get("score")).doubleValue() < 0);
        assertFalse(row.ranked());
    }

    @Test
    void theCallCarriesArgumentsAndRawForDebugging() {
        var call = new McpCall("memory_search", "mekodata_memory_search",
                Map.of("query", "INC-1842"), true, null, "{\"results\":[]}", "", List.of(), 12);

        var fields = json(call).keySet();

        for (var name : List.of("tool", "resolvedTool", "arguments", "ok", "raw", "text", "rows")) {
            assertTrue(fields.contains(name), "the console reads call." + name + ": " + fields);
        }
    }

    @Test
    void hybridIsDetectableFromComponentsAlone() {
        // The JS recomputes this from score and rawScore rather than trusting a helper.
        var hybrid = new MekoRow("a", "t", 0.268, 0.498, "x", "");
        var pureVector = new MekoRow("b", "t", 0.498, 0.498, "x", "");

        assertEquals(true, hybrid.score() != hybrid.rawScore());
        assertEquals(false, pureVector.score() != pureVector.rawScore());
    }
}
