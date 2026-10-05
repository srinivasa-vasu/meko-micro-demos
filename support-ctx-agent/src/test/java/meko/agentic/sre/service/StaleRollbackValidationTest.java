package meko.agentic.sre.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import meko.agentic.sre.domain.Diagnosis;
import meko.agentic.sre.domain.ReuseDecision;
import meko.agentic.sre.domain.ReuseMode;
import meko.agentic.sre.domain.StoredIncident;
import org.junit.jupiter.api.Test;

/**
 * The gate refusing a stale fix does not stop the model borrowing that fix's version. Validation
 * is where a rollback to it gets caught, so the planner escalates instead of finalising.
 */
class StaleRollbackValidationTest {

    /** Observed in a moved-on run, verbatim. */
    private static final String OBSERVED = "Rollback payment-service to the previous stable release "
            + "(e.g., 4.7.0) to restore the upstream timeout to 5 seconds. If a rollback cannot be "
            + "performed quickly, apply a hot-fix to increase the upstream timeout to >=5 seconds and "
            + "restart the service.";

    private final IncidentDataService data = new IncidentDataService();
    private final RemediationValidator validator = new RemediationValidator(data);

    @Test
    void aRollbackToTheStaleVersionFailsValidation() {
        data.setDeployedVersion("payment-service", "5.0.0");

        var result = validator.validate(diagnosis(OBSERVED), staleMatch());

        assertFalse(result.valid());
        assertEquals(1, result.failedAssumptions().size());
        assertTrue(result.failedAssumptions().getFirst().contains("4.7.0"), result.failedAssumptions().toString());
        assertTrue(result.failedAssumptions().getFirst().contains("5.0.0 is deployed now"),
                "the failure says what is deployed, read from deployment state");
    }

    @Test
    void theSameAnswerIsFineWhenNothingWasRejectedAsStale() {
        assertTrue(validator.validate(diagnosis(OBSERVED), null).valid(),
                "without a stale rejection there is no stale version to avoid");
    }

    @Test
    void namingTheStaleVersionAsTheBoundaryIsTheCorrectTarget() {
        data.setDeployedVersion("payment-service", "5.0.0");
        var correct = "Roll back payment-service to the release before 4.7.0, which still had the 5s timeout.";
        assertTrue(validator.validate(diagnosis(correct), staleMatch()).valid());
    }

    @Test
    void theRuleMatchesThePagesCases() {
        assertTrue(RemediationValidator.rollsBackTo("Revert to v4.7.0 immediately.", "4.7.0"));
        assertFalse(RemediationValidator.rollsBackTo("Roll back payment-service to 4.6.2.", "4.7.0"));
        assertFalse(RemediationValidator.rollsBackTo(
                "Roll back payment-service to the previous release. 4.7.0 introduced the 3s timeout.", "4.7.0"),
                "a different sentence naming the version is not a rollback target");
        assertFalse(RemediationValidator.rollsBackTo("Roll back to 14.7.0.", "4.7.0"));
    }

    private static Diagnosis diagnosis(String action) {
        return new Diagnosis("timeout below p95", 0.9, "r", action, true, List.of());
    }

    private static ReuseDecision staleMatch() {
        var stored = new StoredIncident("INC-1842", "checkout", "sig", "payment-service", "4.7.0",
                "rc", "rem", List.of("checkout"), "2026-03-02T09:14:00Z", "raw", 0.69, 0.69, "sre-agent");
        return ReuseDecision.of(ReuseMode.SCOPED, 0.89, true,
                "Stored remediation references payment-service 4.7.0 but 5.0.0 is deployed now.", stored, "stale");
    }
}
