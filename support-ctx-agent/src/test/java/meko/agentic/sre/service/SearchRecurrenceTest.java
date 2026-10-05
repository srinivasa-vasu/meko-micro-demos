package meko.agentic.sre.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;
import java.util.Map;
import meko.agentic.sre.config.TokenProperties;
import meko.agentic.sre.domain.IncidentRequest;
import meko.agentic.sre.domain.IncidentSeverity;
import meko.agentic.sre.domain.MekoContext;
import meko.agentic.sre.domain.ModelTier;
import meko.agentic.sre.domain.ReuseMode;
import meko.agentic.sre.domain.StoredIncident;
import org.junit.jupiter.api.Test;

/**
 * A search incident written back by one run must be usable by the next. With no tracked search
 * version the write-back recorded a blank deployVersion, and the gate could only ever narrow
 * with it: "Stored incident does not record a deployment version".
 */
class SearchRecurrenceTest {

    private final IncidentDataService data = new IncidentDataService();
    private final ReuseGate gate = new ReuseGate(data, new TokenProperties(Map.of(), Map.of(),
            new TokenProperties.Reuse(0.85, 0.55), new TokenProperties.Routing(5, 0.85, false),
            new TokenProperties.Projection(2_000)));

    @Test
    void theWriteBackHasAVersionToRecordForSearch() {
        assertEquals("search", data.deployServiceFor("search"));
        assertEquals("3.8.2", data.currentVersion("search"));
    }

    @Test
    void aRecurrenceOnAnUnchangedSearchDeploymentCanBeAnsweredFromMemory() {
        var symptom = "Search results returning stale documents after an index rebuild";
        var written = record(data.currentVersion("search"), symptom);   // what the first run writes now

        var decision = gate.evaluate(recurrence(symptom), context(written));

        assertFalse(decision.stale(), decision.stalenessReason());
        assertEquals(ReuseMode.SHORT_CIRCUIT, decision.mode());
    }

    @Test
    void aSearchReleaseSinceStillMakesItStale() {
        var symptom = "Search results returning stale documents after an index rebuild";
        var written = record("3.8.2", symptom);
        data.setDeployedVersion("search", "3.9.0");

        var decision = gate.evaluate(recurrence(symptom), context(written));

        assertEquals(ReuseMode.SCOPED, decision.mode());
        assertEquals(true, decision.stale());
    }

    private static IncidentRequest recurrence(String symptom) {
        return new IncidentRequest("INC-7731", "search", symptom, true, IncidentSeverity.SEV3);
    }

    private static StoredIncident record(String version, String symptom) {
        return new StoredIncident("INC-7730", "search", symptom, "search", version,
                "Indexer hung during the scheduled rebuild and failed to commit segments",
                "Force a commit or flush on the indexer", List.of("search"), "2026-10-04T08:00:00Z",
                "raw", 0.7, 0.7, "sre-agent");
    }

    private static MekoContext context(StoredIncident stored) {
        return new MekoContext(true, List.of(), List.of(), List.of(stored), ModelTier.SLM, 0.75, 0.05,
                "r", "s", null, 1, List.of(), null);
    }
}
