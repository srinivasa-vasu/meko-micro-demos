package meko.agentic.sre.api;

import meko.agentic.sre.domain.ReuseDecision;
import meko.agentic.sre.config.TokenProperties;
import meko.agentic.sre.domain.MekoContext;
import meko.agentic.sre.domain.ModelTier;
import meko.agentic.sre.domain.ReuseMode;
import meko.agentic.sre.domain.Scenario;
import meko.agentic.sre.domain.StoredIncident;
import meko.agentic.sre.service.IncidentDataService;
import meko.agentic.sre.service.ReuseGate;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Each scenario tells the room, in writing, what should happen before anything runs. These
 * tests are what stop that from becoming a lie.
 *
 * A demo whose narration is maintained by hand drifts from the code within about two commits,
 * and the drift is invisible until it happens on stage. Pinning the claims here means a change
 * to the reuse thresholds, the scoring weights or the seed corpus breaks the build rather than
 * the demo.
 */
class ScenarioExpectationTest {

    private static final TokenProperties PROPS = new TokenProperties(
            Map.of(), Map.of(),
            new TokenProperties.Reuse(0.85, 0.55),
            new TokenProperties.Routing(5, 0.85, false),
            new TokenProperties.Projection(2_000));

    private final IncidentDataService data = new IncidentDataService();
    private final ReuseGate gate = new ReuseGate(data, PROPS);

    /**
     * The version that was live when the incident was recorded. Seeding happens BEFORE a
     * scenario moves the world on, so this is a constant here — reading it from the mutable
     * IncidentDataService would make the record follow the deployment and never look stale,
     * which is precisely the bug the moved-on scenario exists to catch.
     */
    private static final String VERSION_AT_SEED_TIME =
            IncidentDataService.INCIDENT_ERA_PAYMENT_VERSION;

    /** Mirrors what SeedService writes, including deployService. */
    private StoredIncident seededIncident() {
        return new StoredIncident(
                "INC-1842",
                "checkout",
                "checkout failures payment provider timeout after deployment",
                "payment-service",
                VERSION_AT_SEED_TIME,
                "Payment client upstream timeout was lowered below the provider p95, so normal "
                        + "provider latency began failing checkout",
                "Roll back payment-service to the previous release and restore the 5s upstream timeout",
                List.of("checkout", "payment-client", "payment-api"),
                "2026-03-02T09:14:00Z",
                "raw",
                0.69,
                0.69,
                "ai-sre-that-remembers");
    }

    private MekoContext context() {
        return new MekoContext(true, List.of(), List.of(), List.of(seededIncident()),
                ModelTier.SLM, 0.75, 0.15, "rationale", "source", null, 1, java.util.List.of(), null);
    }

    private Scenario scenario(String id) {
        return new ScenarioController(null, null).list().stream()
                .filter(s -> s.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no scenario " + id));
    }

    private ReuseDecision run(String scenarioId) {
        var scenario = scenario(scenarioId);
        data.setDeployedVersion("payment-service", scenario.deployedPaymentVersion());
        return gate.evaluate(scenario.toRequest(true), context());
    }

    @Test
    void rememberedShortCircuitsWithNoModelCall() {
        var decision = run("remembered");

        assertEquals(ReuseMode.SHORT_CIRCUIT, decision.mode(),
                "scenario 'remembered' promises zero model calls; got " + decision.explanation());
        assertFalse(decision.stale());
        assertTrue(decision.matchScore() >= PROPS.reuse().shortCircuitThreshold(),
                "match scored " + decision.matchScore());
    }

    @Test
    void movedOnFindsTheSameMatchAndRefusesToServeIt() {
        var decision = run("moved-on");

        assertEquals(ReuseMode.SCOPED, decision.mode(),
                "a stale match must narrow telemetry, never answer");
        assertTrue(decision.stale(), "the whole scenario is the staleness rejection");
        assertTrue(decision.stalenessReason().contains("5.0.0"),
                "the rejection should name the version that is actually deployed: "
                        + decision.stalenessReason());

        // The match itself is still strong — that is what makes the refusal interesting.
        assertTrue(decision.matchScore() >= PROPS.reuse().shortCircuitThreshold(),
                "scored " + decision.matchScore() + "; if this drops below the short-circuit "
                        + "threshold the scenario proves nothing, because it would have been "
                        + "rejected on score alone");
    }

    @Test
    void unfamiliarDeclinesToMatchRatherThanForcingAFit() {
        var decision = run("unfamiliar");

        assertEquals(ReuseMode.FULL, decision.mode(),
                "memory must decline when it has nothing relevant");
        assertEquals(0.0, decision.matchScore(), 1e-9);
        assertEquals(null, decision.match());
    }

    @Test
    void armingTheSameScenarioTwiceStillBehavesTheSameWay() {
        // The bug this pins: the seed used to write deployVersion=currentVersion(), so arming
        // moved-on wrote 5.0.0 into the record it was about to compare 5.0.0 against, and the
        // staleness rejection silently stopped happening on the second arm. Nothing errored —
        // the demo just quietly started showing the wrong thing.
        run("moved-on");
        var second = run("moved-on");

        assertEquals(ReuseMode.SCOPED, second.mode());
        assertTrue(second.stale(), "re-arming must not make a stale record look fresh");
    }

    @Test
    void switchingBetweenScenariosLeavesEachOneIntact() {
        assertEquals(ReuseMode.SHORT_CIRCUIT, run("remembered").mode());
        assertEquals(ReuseMode.SCOPED, run("moved-on").mode());
        assertEquals(ReuseMode.SHORT_CIRCUIT, run("remembered").mode(),
                "going back to scenario 1 must restore the deployment it depends on");
        assertEquals(ReuseMode.FULL, run("unfamiliar").mode());
    }

    @Test
    void theSeededRecordReferencesTheIncidentEraVersionNotWhateverIsDeployed() {
        incidentData_setLive("9.9.9");
        assertEquals(IncidentDataService.INCIDENT_ERA_PAYMENT_VERSION, seededIncident().deployVersion(),
                "the March incident happened at a fixed version; that is a fact about the past");
    }

    private void incidentData_setLive(String version) {
        data.setDeployedVersion("payment-service", version);
    }

    @Test
    void theDeclaredExpectationMatchesWhatTheGateActuallyDoes() {
        // The page marks each run against scenario.expectedReuseMode. If that field and the
        // gate ever disagree, the demo reports a failure that is not real, or passes one that
        // is — either way the verdict stops meaning anything.
        for (var sc : new ScenarioController(null, null).list()) {
            var decision = run(sc.id());
            assertEquals(sc.expectedReuseMode(), decision.mode(),
                    "scenario '" + sc.id() + "' declares " + sc.expectedReuseMode()
                            + " but the gate produced " + decision.mode());
            assertEquals(sc.expectStaleRejection(), decision.stale(),
                    "scenario '" + sc.id() + "' declares stale=" + sc.expectStaleRejection());
            var matched = decision.mode() == ReuseMode.FULL || decision.match() == null
                    ? null : decision.match().incidentId();
            assertEquals(sc.expectedMatchId(), matched,
                    "scenario '" + sc.id() + "' names " + sc.expectedMatchId()
                            + " in its prediction but the gate matched " + matched);
        }
    }

    @Test
    void onlyTheShortCircuitScenarioPromisesZeroModelCalls() {
        for (var sc : new ScenarioController(null, null).list()) {
            if (sc.expectedWarmModelCalls() != null) {
                assertEquals(0, sc.expectedWarmModelCalls(),
                        "the only defensible model-call claim is zero, from a short circuit");
                assertEquals(ReuseMode.SHORT_CIRCUIT, sc.expectedReuseMode(),
                        "claiming zero model calls without short-circuiting is not achievable");
            }
        }
    }

    @Test
    void aRunNeverMatchesItsOwnWriteUpOfTheSameIncident() {
        // Every finishing run writes an INCIDENT_RECORD keyed on the incident id, so the second
        // run of any incident finds a record whose signature IS its own symptom. Scoring that
        // gives ~1.0 and short-circuits on an answer the agent generated itself minutes ago.
        //
        // It broke scenario 3 outright: a search indexing incident, whose whole point is that
        // the corpus knows nothing about it, short-circuited at 0.89 against its own prior run.
        var scenario = scenario("unfamiliar");
        var ownWriteUp = new StoredIncident(
                scenario.incidentId(),          // same id as the run
                scenario.service(),
                scenario.symptom(),             // identical signature: scores 1.0
                "payment-service", VERSION_AT_SEED_TIME,
                "Index rebuild did not commit", "Re-run the indexer",
                List.of(scenario.service()), "2026-09-14T05:00:00Z", "raw", 0.74, 0.74, "sre-agent");

        var context = new MekoContext(true, List.of(), List.of(), List.of(ownWriteUp),
                ModelTier.SLM, 0.75, 0.15, "r", "s", null, 1, List.of(), null);

        var decision = gate.evaluate(scenario.toRequest(true), context);

        assertEquals(ReuseMode.FULL, decision.mode(),
                "an echo of this run's own write-up is not history");
        assertEquals(0.0, decision.matchScore(), 1e-9);
    }

    @Test
    void anIgnoredSelfMatchIsCountedAndExplained() {
        // "No stored match" when a perfect one was deliberately skipped reads as retrieval
        // failing, which is the wrong thing to go and debug.
        var scenario = scenario("unfamiliar");
        var ownWriteUp = new StoredIncident(
                scenario.incidentId(), scenario.service(), scenario.symptom(),
                "payment-service", VERSION_AT_SEED_TIME, "c", "r",
                List.of(scenario.service()), "2026-09-14T05:00:00Z", "raw", 0.74, 0.74, "sre-agent");
        var context = new MekoContext(true, List.of(), List.of(), List.of(ownWriteUp),
                ModelTier.SLM, 0.75, 0.15, "r", "s", null, 1, List.of(), null);

        var decision = gate.evaluate(scenario.toRequest(true), context);

        assertEquals(1, decision.selfMatchesIgnored());
        assertTrue(decision.explanation().contains("echo"), decision.explanation());
        assertTrue(decision.explanation().contains("new incident id"),
                "the operator needs to be told how to actually see reuse: " + decision.explanation());
    }

    @Test
    void aDifferentIncidentThatResemblesThisOneStillCounts() {
        // The exclusion must be by id only. Excluding by similarity would remove exactly the
        // matches reuse exists to find.
        var scenario = scenario("remembered");
        var other = new StoredIncident(
                "INC-1842",                     // a DIFFERENT incident
                scenario.service(), scenario.symptom(),
                "payment-service", VERSION_AT_SEED_TIME,
                "Timeout below p95", "Roll back",
                List.of(scenario.service()), "2026-03-02T09:14:00Z", "raw", 0.74, 0.74, "sre-agent");

        var context = new MekoContext(true, List.of(), List.of(), List.of(other),
                ModelTier.SLM, 0.75, 0.15, "r", "s", null, 1, List.of(), null);
        data.setDeployedVersion("payment-service", scenario.deployedPaymentVersion());

        assertEquals(ReuseMode.SHORT_CIRCUIT, gate.evaluate(scenario.toRequest(true), context).mode());
    }

    @Test
    void everyScenarioStatesBothAnExpectationAndAFailureTell() {
        for (var s : new ScenarioController(null, null).list()) {
            assertTrue(s.expectation() != null && s.expectation().length() > 40,
                    s.id() + " needs a concrete expectation");
            assertTrue(s.failureTell() != null && s.failureTell().length() > 40,
                    s.id() + " needs a stated failure tell, or the room cannot check it");
        }
    }

    /**
     * The two answers from an observed compare of "remembered". The old claim ("contains the
     * 5s upstream timeout wording") passed as "only with context" although the cold answer
     * also restores a 5s timeout — it passed on phrasing. The cold answer's real difference is
     * the hedge: it offers to raise the timeout instead of committing to the rollback.
     */
    @Test
    void theRememberedClaimJudgesSubstanceNotPhrasing() {
        var cold = "Reduced payment client timeout to 3s in payment-service 4.7.0, which is lower than "
                + "the observed Stripe response latency, leading to systematic timeouts and checkout "
                + "failures. Rollback payment-service to the previous version or increase the payment "
                + "client timeout back to >=5s; optionally add circuit breaker/retry backoff while "
                + "investigating Stripe latency.";
        var warm = "Payment client upstream timeout was lowered below the provider p95, so normal "
                + "provider latency began failing checkout Roll back payment-service to the previous "
                + "release and restore the 5s upstream timeout, then alert on payment-api timeout rate "
                + "after deploys";

        for (var claim : scenario("remembered").contextClaims()) {
            var result = new meko.agentic.sre.domain.ContextClaim.Result(
                    claim, claim.presentIn(warm), claim.presentIn(cold));
            assertTrue(result.distinctive(), claim.label() + " -> " + result.verdict());
        }
        // The hedge itself must be caught, in the exact form it was observed.
        var commits = scenario("remembered").contextClaims().getFirst();
        assertFalse(commits.presentIn(cold), "the cold answer offers to raise the timeout");
    }

    /**
     * Answers from an observed compare of "moved-on". Each verdict is pinned, including the two
     * that are not wins: an honest panel is the point.
     */
    @Test
    void theMovedOnClaimsJudgeTheObservedAnswersCorrectly() {
        var cold = "Payment client timeout reduced to 3s in recent deployment, causing timeouts with "
                + "Stripe's ~4.8s latency Increase the payment client timeout back to at least 5 seconds "
                + "(or to a value above the observed provider latency) or roll back the timeout change "
                + "until the provider latency improves.";
        var warm = "The recent payment-service deployment lowered the upstream payment provider timeout "
                + "from 5 seconds to 3 seconds, which is below the provider's observed p95 latency "
                + "(~4.8s). This caused normal provider responses to be treated as timeouts, leading to "
                + "high checkout failure rates. Rollback payment-service to the previous stable release "
                + "(e.g., 4.7.0) that configures the upstream payment provider timeout to 5 seconds. If a "
                + "rollback is not feasible, apply a hot-fix to increase the upstream timeout to >=5 "
                + "seconds and restart the service. After the system stabilizes, verify checkout "
                + "error_rate returns to baseline, then add an alert on payment-api timeout rate that "
                + "triggers on new deployments.";

        var verdicts = scenario("moved-on").contextClaims().stream()
                .map(c -> new meko.agentic.sre.domain.ContextClaim.Result(c, c.presentIn(warm), c.presentIn(cold)))
                .map(meko.agentic.sre.domain.ContextClaim.Result::verdict)
                .toList();

        assertEquals(List.of(
                "only with context",            // rollback first, hotfix only as the fallback
                "both runs — not distinctive",  // the change log hands the cold run the cause
                "only with context"),           // the alerting follow-up
                verdicts);
    }

    @Test
    void anEqualAlternativeIsNotAFallback() {
        var fallback = scenario("moved-on").contextClaims().getFirst();
        assertFalse(fallback.presentIn("Roll back payment-service, or increase the timeout to 5s."),
                "rollback first in the text, but offered as an equal choice");
    }
}
