package meko.agentic.sre.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** A run must not be shown its own earlier answer as history. */
class OwnRecordFilterTest {

    private static final String OWN = "INCIDENT_RECORD; id=INC-5107; service=checkout; "
            + "remediation=Roll back payment-service to the previous stable release (4.7.0)";
    private static final String SEEDED = "INCIDENT_RECORD; id=INC-1842; service=checkout; "
            + "remediation=Roll back payment-service and restore the 5s upstream timeout";

    @Test
    void theRunsOwnRecordIsRecognisedWhateverTheCase() {
        assertTrue(IncidentCommanderAgent.isOwnRecord(OWN, "INC-5107"));
        assertTrue(IncidentCommanderAgent.isOwnRecord(OWN, "inc-5107"));
    }

    @Test
    void anotherIncidentIsHistoryAndStays() {
        assertFalse(IncidentCommanderAgent.isOwnRecord(SEEDED, "INC-5107"));
        assertFalse(IncidentCommanderAgent.isOwnRecord(
                "INCIDENT_RECORD; id=INC-51070; service=x", "INC-5107"), "a prefix is not the same id");
    }

    @Test
    void onlyTheOwnLinesLeaveTheFlattenedBlock() {
        var routing = "ROUTING_OUTCOME; id=INC-5107; modelTier=FRONTIER\n"
                + "ROUTING_OUTCOME; id=INC-1842; modelTier=SLM\n"
                + "ROUTING_POLICY; service=checkout; preferredTier=SLM";
        assertEquals("ROUTING_OUTCOME; id=INC-1842; modelTier=SLM\nROUTING_POLICY; service=checkout; preferredTier=SLM",
                IncidentCommanderAgent.withoutOwnRecords(routing, "INC-5107"));
    }
}
