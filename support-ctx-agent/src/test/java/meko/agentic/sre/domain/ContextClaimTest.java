package meko.agentic.sre.domain;

import meko.agentic.sre.api.ScenarioController;
import meko.agentic.sre.service.IncidentDataService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Context claims must be COMPARATIVE, or they prove nothing.
 *
 * "The answer with context mentions 4.7.0" is not evidence: a model can produce a version number
 * unaided, and nobody reading the screen can tell whether it did. The claim that carries weight
 * is "present with context, absent without it", checked across two real runs of the same incident.
 *
 * The self-calibrating part matters most. A claim the run WITHOUT context also satisfies has to
 * report itself as not distinctive rather than passing, or the demo flatters itself exactly where
 * an audience is least able to check.
 */
class ContextClaimTest {

    private final ContextClaim claim = new ContextClaim(
            "Names the deployed version", List.of("4.7.0"), "learned memory");

    private ContextClaim.Result check(String warm, String cold) {
        return new ContextClaim.Result(claim, claim.presentIn(warm), claim.presentIn(cold));
    }

    @Test
    void presentWithContextAndAbsentWithoutItIsTheOnlyPass() {
        var result = check("Roll back payment-service 4.7.0", "Roll back the payment service");

        assertTrue(result.distinctive());
        assertEquals("only with context", result.verdict());
    }

    @Test
    void aFactBothRunsProduceIsNotDistinctive() {
        // The model guessed it unaided. Passing this would be the demo flattering itself.
        var result = check("payment-service 4.7.0 timeout", "the 4.7.0 release lowered the timeout");

        assertFalse(result.distinctive());
        assertEquals("both runs — not distinctive", result.verdict());
    }

    @Test
    void contextFailingToSupplyItIsReportedPlainly() {
        assertEquals("neither run", check("Roll back the release", "Roll back").verdict());
    }

    @Test
    void theEmbarrassingCaseIsNamedRatherThanHidden() {
        // Only the context-free run got it right. That is a real outcome and the demo has to be
        // able to say so.
        var result = check("Roll back the release", "Roll back to 4.7.0");

        assertFalse(result.distinctive());
        assertEquals("only without context", result.verdict());
    }

    @Test
    void anyOfTheAlternativesCounts() {
        var either = new ContextClaim("Commit or flush", List.of("commit", "flush"), "runbook");

        assertTrue(either.presentIn("Force a FLUSH on the indexer"));
        assertTrue(either.presentIn("Trigger a manual index commit"));
        assertFalse(either.presentIn("Rebuild the index from scratch"));
    }

    @Test
    void aMixedClaimDiscriminatesOnTheAbsenceEvenWhenItsPositiveTokenIsEverywhere() {
        // The case the telemetry guard has to make an exception for. "timeout" is in the metrics,
        // so both answers contain it — but only the context-free run calls the cause a
        // performance regression, and that is what separates them.
        var claim = new ContextClaim(
                "Configuration, not capacity", List.of("timeout"),
                List.of("performance regression", "capacity"), "learned memory");

        var withContext = "an upstream timeout of 3000ms below the 4.8s p95";
        var without = "a performance regression causing latency to exceed the 3s timeout";

        var result = new ContextClaim.Result(claim, claim.presentIn(withContext),
                claim.presentIn(without));

        assertTrue(result.distinctive());
        assertEquals("only with context", result.verdict());
    }

    @Test
    void aNegativeClaimPassesOnTheAbsenceOfTheWrongAnswer() {
        // The clearest thing a runbook does is rule options OUT, and no positive token catches
        // that. Observed in the ablation: without the runbook the model offered "roll back, or
        // increase the timeout to >5s"; with it, only the rollback.
        var noFixForward = new ContextClaim(
                "Does not offer raising the timeout", List.of(),
                List.of("increase the timeout", ">5s"), "curated knowledge");

        assertTrue(noFixForward.presentIn("Roll back payment-service to the previous version."));
        assertFalse(noFixForward.presentIn(
                "Roll back, or increase the timeout to >5s to accommodate the p95."));
    }

    @Test
    void aNegativeClaimStillHonoursItsPositiveTokens() {
        // Both halves apply: the required phrase must appear AND the forbidden one must not.
        var both = new ContextClaim("Rollback, and only rollback", List.of("roll back"),
                List.of("increase the timeout"), "curated knowledge");

        assertTrue(both.presentIn("Roll back the release."));
        assertFalse(both.presentIn("Roll back, or increase the timeout."));
        assertFalse(both.presentIn("Restart the service."));
    }

    @Test
    void aClaimWithNothingToMatchNeverPasses() {
        // Guards against an empty anyOf silently matching everything.
        var empty = new ContextClaim("Nothing declared", List.of(), "somewhere");

        assertFalse(empty.presentIn("any answer at all"));
    }

    /**
     * A claim about a fact TELEMETRY already carries can never be distinctive.
     *
     * This is the error the first version shipped with, and it is invisible on screen: the claim
     * renders, the check runs, and it reports "both runs — not distinctive" forever, which reads
     * as context failing rather than as the claim being mis-specified. Scenario 2 asserted that
     * context supplied the deployed version — which appears in recentChanges — and scenario 3
     * asserted "indexer" and "commit", both of which are in the metrics fixture.
     *
     * Mechanically checkable, so it is checked rather than reviewed.
     */
    @Test
    void noClaimAssertsSomethingTelemetryAlreadyGivesBothRuns() {
        var data = new IncidentDataService();

        for (var sc : new ScenarioController(null, null).list()) {
            var request = sc.toRequest(false);
            var telemetry = (String.join(" ", data.metrics(request)) + " "
                    + String.join(" ", data.allLogs(request)) + " "
                    + String.join(" ", data.recentChanges(request)))
                    .toLowerCase(java.util.Locale.ROOT);

            for (var claim : sc.contextClaims()) {
                // Only applies to PURELY POSITIVE claims.
                //
                // When a claim also carries noneOf, its discriminating power is the absence, not
                // the presence: "mentions a timeout AND does not call it a performance regression"
                // is distinctive even though telemetry hands both runs the word "timeout" — the
                // anyOf is there to stop an unrelated answer satisfying the claim vacuously, not
                // to do the discriminating. Applying the rule there would ban the most useful
                // claim shape the demo has.
                if (!claim.noneOf().isEmpty()) {
                    continue;
                }
                for (var token : claim.anyOf()) {
                    assertFalse(telemetry.contains(token.toLowerCase(java.util.Locale.ROOT)),
                            sc.id() + " claims context supplies \"" + token + "\", but the cold "
                                    + "run reads it straight off telemetry — this claim can never "
                                    + "be distinctive: " + claim.label());
                }
            }
        }
    }

    @Test
    void everyScenarioDeclaresClaimsFromANamedSource() {
        for (var sc : new ScenarioController(null, null).list()) {
            assertFalse(sc.contextClaims().isEmpty(),
                    sc.id() + " makes no checkable claim about what context contributed");
            for (var c : sc.contextClaims()) {
                // Either kind counts: a positive claim names what should appear, a negative one
                // names what the context should have ruled out. A claim with neither checks
                // nothing and would report "neither run" forever.
                assertFalse(c.anyOf().isEmpty() && c.noneOf().isEmpty(),
                        sc.id() + " declares a claim that checks nothing: " + c.label());
                assertFalse(c.source().isBlank(),
                        "a claim has to say which source should have supplied it: " + c.label());
            }
        }
    }
}
