package meko.agentic.sre.service;

import meko.agentic.sre.config.TokenProperties;
import meko.agentic.sre.domain.ModelTier;
import meko.agentic.sre.meko.MekoToolBridge.MekoMemory;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A real corpus has near-duplicates and the demo has to work anyway.
 *
 * The same incident gets written up twice, an agent's own restatement sits beside the original,
 * two engineers file the same outage. Requiring a clean store is not a property a memory system
 * can ask for, so duplicates are collapsed on READ rather than pruned from the store.
 *
 * Two things break without that, and neither is loud:
 *   - a limit=8 window filled with eight copies of one incident is a window that saw ONE
 *     incident, and the ninth row that might have matched better never arrives;
 *   - a success rate over rows rather than over distinct incidents is wrong by the duplication
 *     factor, which is how "155 stored outcomes" appeared over a few dozen real runs.
 */
class DuplicateToleranceTest {

    private static final TokenProperties PROPS = new TokenProperties(
            Map.of(), Map.of(),
            new TokenProperties.Reuse(0.85, 0.55),
            new TokenProperties.Routing(5, 0.85, false),
            new TokenProperties.Projection(2_000));

    private final MemoryRecordParser parser = new MemoryRecordParser();
    private final RoutingPolicyService routing = new RoutingPolicyService(parser, null, PROPS);

    private static MekoMemory row(String text, double score) {
        return row(text, score, "2026-09-14T05:00:00Z");
    }

    private static MekoMemory row(String text, double score, String createdAt) {
        return new MekoMemory("row-" + Math.random(), text, score, score, "sre-agent", createdAt);
    }

    private static String incident(String id, String rootCause) {
        return "INCIDENT_RECORD; id=" + id + "; service=checkout; "
                + "signature=checkout failures payment provider timeout after deployment; "
                + "deployService=payment-service; deployVersion=4.7.0; rootCause=" + rootCause + "; "
                + "remediation=Roll back; scopeServices=checkout,payment-client; "
                + "observedAt=2026-03-02T09:14:00Z";
    }

    @Test
    void copiesOfTheSameIncidentCollapseToOne() {
        // Same id, differently worded by the model on each run — exactly what write-back produces.
        var rows = List.of(
                row(incident("INC-1842", "Timeout lowered below provider p95."), 0.61),
                row(incident("INC-1842", "The upstream timeout was set under the p95."), 0.69),
                row(incident("INC-1842", "Client timeout below provider latency."), 0.58));

        var incidents = parser.parseIncidents(rows);

        assertEquals(1, incidents.size());
        assertEquals(0.69, incidents.getFirst().semanticScore(), 1e-9,
                "the best-scoring copy is the one worth keeping");
    }

    @Test
    void distinctIncidentsAllSurviveTheCollapse() {
        var rows = List.of(
                row(incident("INC-1842", "a"), 0.7),
                row(incident("INC-1842", "a restated"), 0.6),
                row(incident("INC-2001", "b"), 0.5),
                row(incident("INC-3007", "c"), 0.4));

        assertEquals(3, parser.parseIncidents(rows).size(),
                "collapsing must not cost diversity, which is the whole reason for doing it");
    }

    @Test
    void aRecordWithNoIdFallsBackToServiceAndSignature() {
        var noId = "INCIDENT_RECORD; service=checkout; "
                + "signature=checkout failures payment provider timeout after deployment; "
                + "deployVersion=4.7.0; rootCause=x; remediation=y; observedAt=2026-03-02T09:14:00Z";

        assertEquals(1, parser.parseIncidents(List.of(row(noId, 0.5), row(noId, 0.4))).size());
    }

    @Test
    void successRateCountsDistinctIncidentsNotRows() {
        // Six rows, three incidents, each recorded twice. A rate over rows would still be 100%
        // here, but the SAMPLE SIZE would read as six — and min-observations is a sample-size bar.
        var text = new ArrayList<String>();
        for (var id : List.of("INC-1", "INC-2", "INC-3")) {
            for (int copy = 0; copy < 2; copy++) {
                text.add("ROUTING_OUTCOME; id=" + id + "; service=checkout; modelTier=SLM; "
                        + "escalated=false; validated=true; usd=0.0012");
            }
        }

        var observations = routing.observations("checkout", String.join("\n", text));

        assertEquals(1, observations.size());
        assertEquals(3, observations.getFirst().attempts(),
                "three incidents recorded twice is evidence of three, not six");
    }

    @Test
    void theNewerRecordWinsEvenWhenAnOlderOneScoresHigher() {
        // Schema evolution: last week's record has no deployService, this week's does. The old
        // one out-scored the new one by 0.006, which meant the gate read a record missing the
        // field it needed and rejected every match as stale.
        var legacy = "INCIDENT_RECORD; id=INC-1842; service=checkout; signature=s; "
                + "deployVersion=4.7.0; rootCause=r; remediation=m; observedAt=2026-03-02T09:14:00Z";
        var current = "INCIDENT_RECORD; id=INC-1842; service=checkout; signature=s; "
                + "deployService=payment-service; deployVersion=4.7.0; rootCause=r; remediation=m; "
                + "observedAt=2026-03-02T09:14:00Z";

        var incidents = parser.parseIncidents(List.of(
                row(legacy, 0.268, "2026-09-14T03:31:53Z"),
                row(current, 0.262, "2026-09-14T05:01:11Z")));

        assertEquals(1, incidents.size());
        assertEquals("payment-service", incidents.getFirst().deployService(),
                "the later write-up supersedes the earlier one");
        assertEquals(0.268, incidents.getFirst().semanticScore(), 1e-9,
                "but the best score across copies is still what retrieval achieved");
    }

    @Test
    void aRankedCopyBeatsAnUnrankedOneForTheSameIncident() {
        // The same record reaches the gate twice: once from memory_search carrying a similarity
        // score, once from memory_get_all carrying none. Keeping the unranked copy would print
        // "Meko semantic score 0.00" beside a record memory matched perfectly.
        var rows = List.of(
                row(incident("INC-1842", "from the listing"), MekoMemory.UNRANKED),
                row(incident("INC-1842", "from the search"), 0.71));

        var incidents = parser.parseIncidents(rows);

        assertEquals(1, incidents.size());
        assertEquals(0.71, incidents.getFirst().semanticScore(), 1e-9);
    }

    @Test
    void anUnrankedRecordStaysNegativeRatherThanReadingAsZero() {
        var incidents = parser.parseIncidents(
                List.of(row(incident("INC-1842", "listing only"), MekoMemory.UNRANKED)));

        // Below zero, so the page can say "not ranked" instead of reporting a measurement
        // that was never taken as a measurement of zero.
        assertTrue(incidents.getFirst().semanticScore() < 0);
    }

    @Test
    void theBlendedScoreAndVectorSimilarityAreBothCarried() {
        // Measured on the live datapack for the query "INCIDENT_RECORD checkout": short routing
        // rows came back with score == raw, while the three INC-1842 records — the ones the gate
        // actually needs — were cut to roughly 54% of their vector similarity. Ranked by raw they
        // are first, second and third; ranked by score they are eighth, ninth and tenth.
        //
        // The gate survives that only because it scans every candidate instead of trusting rank
        // order, so both numbers are carried and neither is silently substituted for the other.
        var rows = List.of(
                new MekoMemory("a", incident("INC-1842", "cut by the blend"), 0.2681, 0.4978,
                        "sre-agent", "2026-09-14T05:00:00Z"));

        var incident = parser.parseIncidents(rows).getFirst();

        assertEquals(0.2681, incident.semanticScore(), 1e-9);
        assertEquals(0.4978, incident.rawScore(), 1e-9);
        assertTrue(incident.rawScore() > incident.semanticScore(),
                "the blend demotes long structured records; losing raw would hide that");
    }

    @Test
    void incidentRecordsSurviveAWindowDominatedByRoutingOutcomes() {
        // What the live corpus actually looks like: one ROUTING_OUTCOME per run, forever,
        // against a handful of incidents. Those rows carry the same service and incident id, so
        // they score well on an incident query and fill a narrow window completely — which is
        // why the search asks for a wide one and filters locally rather than trusting top-k to
        // return the record type it wanted.
        var rows = new ArrayList<MekoMemory>();
        for (int i = 0; i < 28; i++) {
            rows.add(row("ROUTING_OUTCOME; id=INC-RUN-" + i + "; service=checkout; modelTier=SLM; "
                    + "escalated=false; validated=true; usd=0.0029", 0.72 - i * 0.001));
        }
        rows.add(row(incident("INC-1842", "Timeout below provider p95."), 0.64));

        var incidents = parser.parseIncidents(rows);

        assertEquals(1, incidents.size(), "the incident record must survive the noise");
        assertEquals("INC-1842", incidents.getFirst().incidentId());
    }

    @Test
    void theSeededAggregateRowStillCountsOnce() {
        // It carries no id and legitimately represents 17 prior incidents.
        var seeded = "ROUTING_OUTCOME; service=checkout; modelTier=SLM; attempts=17; "
                + "escalations=1; successRate=0.94; meanUsd=0.00121";

        var observations = routing.observations("checkout", seeded);

        assertEquals(17, observations.getFirst().attempts());
        assertEquals(ModelTier.SLM, observations.getFirst().tier());
    }
}
