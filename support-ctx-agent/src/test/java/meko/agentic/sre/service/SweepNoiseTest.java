package meko.agentic.sre.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import meko.agentic.sre.domain.IncidentRequest;
import meko.agentic.sre.domain.IncidentSeverity;
import org.junit.jupiter.api.Test;

/**
 * The rest of the estate is noise and must look like it. A full sweep that carries another
 * incident's failure hands the run without memory a false lead the scoped run never sees.
 */
class SweepNoiseTest {

    private final IncidentDataService data = new IncidentDataService();

    private static IncidentRequest incident(String service) {
        return new IncidentRequest("INC-T", service, "symptom", false, IncidentSeverity.SEV2);
    }

    @Test
    void aSearchIncidentsSweepShowsNoPaymentOutage() {
        var sweep = String.join("\n", data.allLogs(incident("search")));
        assertFalse(sweep.contains("PaymentProviderTimeout"), "observed: blamed Stripe for stale search results");
        assertFalse(sweep.contains("provider=stripe"));
        assertTrue(sweep.contains("serving stale segment"), "the incident's own failure is still there");
    }

    @Test
    void aPaymentIncidentsSweepShowsNoSearchFailure() {
        var sweep = String.join("\n", data.allLogs(incident("checkout")));
        assertFalse(sweep.contains("serving stale segment"));
        assertTrue(sweep.contains("PaymentProviderTimeout"));
    }
}
