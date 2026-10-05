package meko.agentic.sre.api;

import meko.agentic.sre.service.IncidentDataService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The staging controls describe what they did, and pressing one twice is not a lie.
 *
 * "Ship a release" defaulted to a fixed 5.0.0, so the second press changed nothing but still
 * reported success — "now 5.0.0, up from 5.0.0". Worse, the effect sentence is phrased in terms
 * of the PREVIOUS version, so when previous and current collapsed to the same value it claimed
 * remediations referencing the currently-deployed version were stale. Exactly backwards, and
 * staleness is the mechanism scenario 2 exists to demonstrate.
 */
class DeploymentControlTest {

    private final IncidentDataService data = new IncidentDataService();
    private final MekoController controller = new MekoController(null, data, null, null);

    @Test
    void shippingTwiceProducesTwoDifferentVersions() {
        var first = controller.advanceDeployment("payment-service", null);
        var second = controller.advanceDeployment("payment-service", null);

        assertTrue(first.changed());
        assertTrue(second.changed(), "a second press must ship something, not silently no-op");
        assertEquals(first.currentVersion(), second.previousVersion());
        assertFalse(first.currentVersion().equals(second.currentVersion()));
    }

    @Test
    void theStaleVersionNamedIsTheOneThatWasDeployed() {
        var before = data.currentVersion("payment-service");

        var change = controller.advanceDeployment("payment-service", null);

        assertTrue(change.effect().contains("assume " + before),
                "the stale remediations are the ones pinned to what WAS deployed: " + change.effect());
        assertFalse(change.effect().contains("assume " + change.currentVersion()),
                "what is deployed now cannot be the stale version: " + change.effect());
    }

    @Test
    void shippingTheVersionAlreadyDeployedReportsANoOp() {
        var current = data.currentVersion("payment-service");

        var change = controller.advanceDeployment("payment-service", current);

        assertFalse(change.changed());
        assertTrue(change.effect().contains("Nothing changed"), change.effect());
        // It may mention staleness — saying "nothing became stale" is the useful thing to say.
        // What it must not do is assert that something IS stale.
        assertTrue(change.effect().contains("nothing became stale"), change.effect());
        assertFalse(change.effect().contains("are now stale"), change.effect());
    }

    @Test
    void rollingBackRestoresTheVersionTheStoredRemediationAssumes() {
        controller.advanceDeployment("payment-service", "9.9.9");

        var change = controller.resetDeployment();

        assertTrue(change.changed());
        assertEquals(IncidentDataService.INCIDENT_ERA_PAYMENT_VERSION, change.currentVersion());
        assertTrue(change.effect().contains("Reuse is possible again"), change.effect());
    }

    @Test
    void rollingBackWhenAlreadyThereSaysSo() {
        controller.resetDeployment();

        var change = controller.resetDeployment();

        assertFalse(change.changed());
        assertTrue(change.effect().contains("already on"), change.effect());
    }

    @Test
    void aNonSemverVersionIsExtendedRatherThanGuessedAt() {
        controller.advanceDeployment("payment-service", "nightly");

        var change = controller.advanceDeployment("payment-service", null);

        assertEquals("nightly.1", change.currentVersion());
    }
}
