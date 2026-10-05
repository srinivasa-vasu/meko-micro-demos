package meko.agentic.sre.service;

import meko.agentic.sre.config.SweepProperties;
import meko.agentic.sre.domain.IncidentRequest;
import meko.agentic.sre.domain.IncidentSeverity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The sweep is sizeable, and a reduced one admits it.
 *
 * The knob exists for one reason: Groq's free tier rejects the full sweep outright (413, 8,000
 * TPM against ~32,000 requested), and that rejection lands on the cold run — half of the only
 * comparison the demo makes. A demo that cannot run its own expensive path cannot make its point.
 *
 * The risk the knob introduces is not arithmetic. Every figure is measured from the run that
 * happened, so a smaller sweep yields a smaller, still-true saving. The risk is the caption: a
 * 200-line sweep presented as the 1,200-line one overstates what memory saved. So what is under
 * test here is mostly the disclosure.
 */
class SweepSizeTest {

    private final IncidentRequest request =
            new IncidentRequest("INC-1", "checkout", "Checkout failures", true, IncidentSeverity.SEV2);

    @Test
    void theDefaultSweepIsTheHonestOne() {
        var data = new IncidentDataService();

        // 20 services in the estate, at the default lines each.
        assertEquals(20 * SweepProperties.FULL_DEFAULT, data.allLogs(request).size());
        assertFalse(data.sweep().reduced());
        assertEquals("", data.sweep().disclosure(), "nothing to disclose at the default");
    }

    @Test
    void loweringTheKnobLowersTheSweep() {
        var data = new IncidentDataService(new SweepProperties(10, 8));

        assertEquals(200, data.allLogs(request).size());
        assertTrue(data.sweep().reduced());
    }

    /**
     * The scoped path is the saving the demo is built on, so it has to stay far below the sweep
     * even after the sweep is cut. At the groq profile's 10 that is 200 lines against 24.
     */
    @Test
    void theScopedPathStaysMuchSmallerThanAReducedSweep() {
        var data = new IncidentDataService(new SweepProperties(10, 8));

        var scoped = data.scopedLogs(request, java.util.List.of("checkout", "payment-client", "payment-api"));

        assertEquals(24, scoped.size());
        assertTrue(data.allLogs(request).size() > scoped.size() * 5,
                "a sweep cut this far stops being a contrast worth drawing");
    }

    /** The one sentence that stops a throttled run being presented as an unthrottled one. */
    @Test
    void aReducedSweepNamesBothNumbersInItsDisclosure() {
        var disclosure = new SweepProperties(10, 8).disclosure();

        assertTrue(disclosure.contains("10"), disclosure);
        assertTrue(disclosure.contains(String.valueOf(SweepProperties.FULL_DEFAULT)), disclosure);
        assertTrue(disclosure.toLowerCase().contains("measured"),
                "the point of saying it is that the numbers are still real: " + disclosure);
    }

    @Test
    void raisingTheKnobBackToTheDefaultRemovesTheDisclosure() {
        // What a paid key does. The banner has to go away, or it becomes noise people learn to
        // ignore on the runs where it matters.
        var data = new IncidentDataService(new SweepProperties(SweepProperties.FULL_DEFAULT, 8));

        assertFalse(data.sweep().reduced());
        assertEquals("", data.sweep().disclosure());
    }

    @Test
    void aSweepThatReadsNothingIsRejected() {
        // Zero would make the comparison meaningless rather than cheap, and would read as a
        // spectacular saving.
        assertThrows(IllegalArgumentException.class, () -> new SweepProperties(0, 8));
        assertThrows(IllegalArgumentException.class, () -> new SweepProperties(60, 0));
    }

    @Test
    void anUnsetKnobTakesTheHonestDefault() {
        var sweep = new SweepProperties(null, null);

        assertEquals(SweepProperties.FULL_DEFAULT, sweep.linesPerService());
        assertEquals(SweepProperties.SCOPED_DEFAULT, sweep.scopedLinesPerService());
        assertFalse(sweep.reduced());
    }
}
