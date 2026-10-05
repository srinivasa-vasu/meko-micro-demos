package meko.agentic.sre.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Arming skips rows that already say what the seed would write, and only those. */
class SeedSkipTest {

    @Test
    void identicalTextIsLeftAloneEvenIfTheStoreReflowedIt() {
        assertTrue(SeedService.sameText("INCIDENT_RECORD; id=INC-1842;  seedKey=incident-1842 ",
                "INCIDENT_RECORD; id=INC-1842; seedKey=incident-1842"));
    }

    @Test
    void aRowThatDriftedIsStillRewritten() {
        assertFalse(SeedService.sameText("INCIDENT_RECORD; id=INC-1842; seedKey=incident-1842",
                "INCIDENT_RECORD; id=INC-1842; deployService=payment-service; seedKey=incident-1842"),
                "a schema change must still reach the stored row");
        assertFalse(SeedService.sameText(null, "x"));
    }
}
