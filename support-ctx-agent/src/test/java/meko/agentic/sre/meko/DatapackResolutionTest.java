package meko.agentic.sre.meko;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Datapack resolution, pinned against the real datapack_list payload.
 *
 * "No datapack resolved" is the error with the worst signal-to-cause ratio in this app: it
 * fires identically whether the MCP client never connected, the key is wrong, the tool is
 * absent, the response shape changed, or the name genuinely does not exist. This class removes
 * the response-shape branch from that list so the remaining causes are distinguishable.
 */
class DatapackResolutionTest {

    /** Verbatim from a live datapack_list call, abridged to three rows. */
    private static final String INNER = """
            [
              {"datapack_id": "07660b6e-99aa-44cb-b4f1-33a8f79cba83", "datapack_name": "funds-transfer", "grant": "owner"},
              {"datapack_id": "6205bfaf-fdc7-4dd4-b558-003a7237e4b5", "datapack_name": "change-risk", "grant": "owner"},
              {"datapack_id": "2bca48c2-14d2-4dc9-8b36-db50c8a6465e", "datapack_name": "flight-delay", "grant": "owner"}
            ]""";

    private final MekoToolBridge bridge =
            new MekoToolBridge(null, new ObjectMapper(), "sre-agent");

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    /** What Spring AI hands back: the MCP content list, serialized. */
    private static String springAi(String inner) {
        return "[{\"type\":\"text\",\"text\":\"" + escape(inner) + "\",\"annotations\":null}]";
    }

    /** What some clients hand back instead. */
    private static String resultWrapped(String inner) {
        return "{\"result\":\"" + escape(inner) + "\"}";
    }

    @Test
    void resolvesFromTheSpringAiContentList() {
        assertEquals("6205bfaf-fdc7-4dd4-b558-003a7237e4b5",
                bridge.findDatapackId(springAi(INNER), "change-risk").orElse(null));
    }

    @Test
    void resolvesFromTheResultWrappedShape() {
        assertEquals("6205bfaf-fdc7-4dd4-b558-003a7237e4b5",
                bridge.findDatapackId(resultWrapped(INNER), "change-risk").orElse(null));
    }

    @Test
    void resolvesFromABareArray() {
        assertEquals("2bca48c2-14d2-4dc9-8b36-db50c8a6465e",
                bridge.findDatapackId(INNER, "flight-delay").orElse(null));
    }

    @Test
    void nameMatchingIgnoresCase() {
        assertEquals("6205bfaf-fdc7-4dd4-b558-003a7237e4b5",
                bridge.findDatapackId(springAi(INNER), "Change-Risk").orElse(null));
    }

    @Test
    void anUnknownNameReportsWhatDoesExist() {
        assertTrue(bridge.findDatapackId(springAi(INNER), "sre-demo").isEmpty());
        // The operator's next question is always "then what IS there?" — answer it in the log.
        var visible = bridge.visibleNames(springAi(INNER));
        assertTrue(visible.contains("change-risk"), visible);
        assertTrue(visible.contains("flight-delay"), visible);
    }

    @Test
    void aServerErrorIsReportedAsAnErrorNotAsNameNotFound() {
        // Verbatim shape of the failure that actually happened: datapack_list rejects a call
        // with no conversation_id, and the rejection is valid JSON containing zero datapacks.
        // Without errorIn() this reads as "your datapack name is wrong", which is the wrong
        // thing to go and check.
        var refusal = springAi("{\"error\": \"conversation_id_required\", "
                + "\"detail\": \"Pass conversation_id from the stored chat\"}");

        assertTrue(bridge.findDatapackId(refusal, "change-risk").isEmpty());
        var err = bridge.errorIn(refusal);
        assertTrue(err != null && err.contains("conversation_id_required"), String.valueOf(err));
    }

    @Test
    void aNormalListingCarriesNoError() {
        assertNull(bridge.errorIn(springAi(INNER)));
    }

    @Test
    void garbageIsReportedAsUnparseableRatherThanAsNotFound() {
        assertTrue(bridge.findDatapackId("not json at all", "change-risk").isEmpty());
        assertEquals("<unparseable>", bridge.visibleNames("not json at all"));
    }
}
