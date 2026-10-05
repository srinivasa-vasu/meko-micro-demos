package meko.agentic.sre.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import meko.agentic.sre.domain.ContextClaim;
import meko.agentic.sre.domain.IncidentReport;
import meko.agentic.sre.domain.ReuseMode;
import meko.agentic.sre.domain.StoredIncident;
import org.junit.jupiter.api.Test;

/**
 * Claims derived for a custom incident, checked against answers actually observed in the demo.
 * The point of each test is that a verdict reflects SUBSTANCE: the same fix in different words
 * is the same fix, and a step only one run took is distinctive.
 */
class ClaimDeriverTest {

    private static final String REMEDIATION = "Roll back payment-service to the previous release and "
            + "restore the 5s upstream timeout, then alert on payment-api timeout rate after deploys";

    /** The warm run served from memory: its answer is the stored record. */
    private static final String WARM_ROOT = "Payment client upstream timeout was lowered below the "
            + "provider p95, so normal provider latency began failing checkout";

    /** Observed cold answer, custom incident. */
    private static final String COLD_ROOT = "Deployment of payment-service 4.7.0 reduced the payment "
            + "provider client timeout from 5 seconds to 3 seconds, causing timeouts when Stripe "
            + "responses exceed the new limit.";
    private static final String COLD_ACTION = "Rollback the payment-service timeout configuration to "
            + "the previous 5s value or increase it to accommodate current Stripe latency (e.g., 6s). "
            + "Deploy the change and monitor checkout error_rate and payment latency to confirm "
            + "resolution.";

    private final ClaimDeriver deriver = new ClaimDeriver();

    @Test
    void theRemediationSplitsIntoItsSteps() {
        assertEquals(List.of(
                        "Roll back payment-service to the previous release",
                        "restore the 5s upstream timeout",
                        "alert on payment-api timeout rate after deploys"),
                deriver.steps(REMEDIATION));
    }

    @Test
    void theObservedCustomRunIsJudgedOnSubstance() {
        var warm = report(ReuseMode.SHORT_CIRCUIT, WARM_ROOT, REMEDIATION, "INC-1842");
        var cold = report(ReuseMode.FULL, COLD_ROOT, COLD_ACTION, "");

        var derived = deriver.derive(warm, cold);
        Map<String, String> verdicts = derived.claims().stream()
                .collect(Collectors.toMap(r -> r.claim().label(), ContextClaim.Result::verdict));

        assertEquals("INC-1842", derived.fromIncident());
        assertTrue(derived.servedFromMemory(), "the page must say the warm column passes by construction");

        // Both runs roll back and both return to 5s: the same fix in different words.
        assertEquals("both runs — not distinctive", verdicts.get("Roll back payment-service to the previous release"));
        assertEquals("both runs — not distinctive", verdicts.get("Restore the 5s upstream timeout"));
        // Only memory carried the alerting follow-up, and only memory committed without a hedge.
        assertEquals("only with context", verdicts.get("Alert on payment-api timeout rate after deploys"));
        assertEquals("only with context", verdicts.get(
                "Commits to the recorded rollback instead of offering to raise the timeout as an alternative"));
    }

    @Test
    void aFullInvestigationMatchedNothingSoThereIsNothingToDerive() {
        var warm = report(ReuseMode.FULL, "x", "y", "INC-1842");
        assertTrue(deriver.derive(warm, report(ReuseMode.FULL, "x", "y", "")).claims().isEmpty(),
                "a FULL run carries its best below-threshold candidate, but did not use it");
    }

    @Test
    void wordingVariationsFoldTogether() {
        assertTrue(deriver.keywords("roll back").contains("revert"));
        assertTrue(deriver.keywords("rolled back").contains("revert"));
        assertTrue(deriver.keywords("Rollback the release").contains("revert"));
        assertTrue(deriver.keywords("restore the timeout").contains("revert"));
        assertTrue(deriver.keywords("a 5 second timeout").contains("5s"));
        assertTrue(deriver.keywords("5000ms").contains("5s"));
        assertTrue(deriver.keywords("alerting on deploys").containsAll(List.of("alert", "deploy")));
    }

    @Test
    void sharedWordsWithoutTheActionDoNotPassAStep() {
        assertFalse(deriver.present("alert on payment-api timeout rate after deploys", COLD_ACTION),
                "monitoring error_rate after a deploy is not alerting on the timeout rate");
    }

    @Test
    void oneSharedWordIsNotEnoughToPassAStep() {
        assertFalse(deriver.present("alert on payment-api timeout rate after deploys",
                "the timeout was too low"));
    }

    /**
     * Observed on a search recurrence. The recorded step was "Force a commit or flush on the
     * indexer"; the run with memory said "invoke a manual commit or flush", and the run without
     * it named "segment commits" only as the symptom. Scored "neither run" before.
     */
    @Test
    void aStepIsItsActionNotItsVerbAndOnlyTheActionIsChecked() {
        var step = "Force a commit or flush on the indexer";
        var warmAction = "1. Invoke a manual commit or flush on the search indexer via its admin endpoint. "
                + "2. If the commit does not result in any documents being committed, restart the indexer "
                + "service to release the rebuild lock.";
        var coldRoot = "Indexer stalled, compounded by a possibly hung scheduled index rebuild job "
                + "preventing segment commits.";
        var coldAction = "1. Restart the search indexer pods to clear any stalled state. 2. Check the status "
                + "of the scheduled index rebuild job; if it is still running or hung, cancel or roll it back.";

        assertTrue(deriver.present(step, warmAction), "invoke vs force is the same step");
        assertFalse(deriver.present(step, coldAction), "the run without memory never commits or flushes");
        assertTrue(deriver.present(step, coldRoot + " " + coldAction),
                "which is why the root cause must not be part of what is checked");
    }

    private IncidentReport report(ReuseMode mode, String root, String action, String matchedId) {
        var report = mock(IncidentReport.class);
        when(report.reuseMode()).thenReturn(mode);
        when(report.rootCause()).thenReturn(root);
        when(report.recommendedAction()).thenReturn(action);
        when(report.matchedIncidentId()).thenReturn(matchedId);
        when(report.recalled()).thenReturn(List.of(new StoredIncident(
                "INC-1842", "checkout", "checkout failures payment provider timeout after deployment",
                "payment-service", "4.7.0", WARM_ROOT, REMEDIATION,
                List.of("checkout", "payment-client", "payment-api"), "2026-03-02T09:14:00Z",
                "raw", 0.69, 0.69, "sre-agent")));
        return report;
    }
}
