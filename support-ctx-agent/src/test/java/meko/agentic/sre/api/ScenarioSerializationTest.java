package meko.agentic.sre.api;

import meko.agentic.sre.domain.ReuseMode;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The scenario has to survive the wire with its expectations intact.
 *
 * The page marks each run against expectedReuseMode and expectedWarmModelCalls. If either
 * arrives absent, the browser has nothing to compare and — in the first version of that code —
 * reported a pass having checked nothing, green-lighting a run that made a model call under a
 * claim of zero. The verdict now fails closed, but these fields going missing would still turn
 * a working check into a permanent "could not verify", so the contract is pinned here.
 */
class ScenarioSerializationTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @SuppressWarnings("unchecked")
    private Map<String, Object> asJson(Object o) {
        return mapper.readValue(mapper.writeValueAsString(o), Map.class);
    }

    @Test
    void everyScenarioShipsACheckableExpectation() {
        for (var sc : new ScenarioController(null, null).list()) {
            var json = asJson(sc);

            assertNotNull(json.get("expectedReuseMode"),
                    sc.id() + " must declare a reuse mode the page can check");
            assertTrue(json.containsKey("expectedWarmModelCalls"),
                    sc.id() + " must carry the key even when the value is null");
            assertTrue(json.containsKey("expectStaleRejection"), sc.id());

            // Enums must cross as their names, because that is what the browser compares against
            // IncidentReport.reuseMode. A numeric ordinal would silently never match.
            assertEquals(sc.expectedReuseMode().name(), json.get("expectedReuseMode"),
                    "reuse mode must serialize as a name, not an ordinal");
            assertEquals(sc.severity().name(), json.get("severity"));
        }
    }

    @Test
    void theShortCircuitScenarioCarriesItsZero() {
        var json = asJson(new ScenarioController(null, null).list().stream()
                .filter(s -> s.id().equals("remembered")).findFirst().orElseThrow());

        // Zero is the whole claim, and it is the value most likely to be dropped by a
        // serializer configured to omit empties.
        assertEquals(0, json.get("expectedWarmModelCalls"));
        assertEquals(ReuseMode.SHORT_CIRCUIT.name(), json.get("expectedReuseMode"));
    }

    @Test
    void theArmResponseNestsTheScenarioIntact() {
        var scenario = new ScenarioController(null, null).list().getFirst();
        var armed = new ScenarioController.ArmResult(
                scenario, true, true, Map.of("payment-service", "4.7.0"), "Wrote 3 new record(s).");

        var json = asJson(armed);
        @SuppressWarnings("unchecked")
        var nested = (Map<String, Object>) json.get("scenario");

        assertNotNull(nested, "the page reads ARMED.scenario, not the top level");
        assertEquals(ReuseMode.SHORT_CIRCUIT.name(), nested.get("expectedReuseMode"));
        assertEquals(0, nested.get("expectedWarmModelCalls"));
    }
}
