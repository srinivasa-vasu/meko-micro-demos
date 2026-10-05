package meko.agentic.sre.domain;

import java.util.List;

/**
 * A demo scenario: a known world state, a matching incident, and a stated expectation.
 *
 * The expectation is written down BEFORE the run and shown on screen before anyone presses
 * anything. That is the difference between a demo and a performance — the audience gets to
 * check the claim rather than being told afterwards that what happened was what should have
 * happened.
 *
 * {@link #failureTell} is the other half of that. Each scenario names the observation that
 * would mean the system is broken, so a bad run is visibly a bad run instead of being narrated
 * past. A demo that cannot fail on stage is not demonstrating anything.
 */
public record Scenario(
        String id,
        String title,
        /** What the room is looking at, in one line. */
        String premise,
        /** What the planner should do, and why. Stated up front. */
        String expectation,
        /** The observation that would mean this is broken. */
        String failureTell,
        String incidentId,
        String service,
        String symptom,
        IncidentSeverity severity,
        /**
         * The same claim as {@link #expectation}, in a form the page can check.
         *
         * Prose alone cannot fail. A presenter reads "should make ZERO model calls", the ledger
         * shows one, and the room is left to work out whether that is the point being made or a
         * broken demo. Declaring the expected outcome lets the result be marked against it.
         */
        /**
         * What the context should put in the answer that the run without it cannot have.
         *
         * Checked against both runs, so a claim that also shows up in the cold answer reports
         * itself as not distinctive instead of quietly passing.
         */
        List<ContextClaim> contextClaims,
        ReuseMode expectedReuseMode,
        /**
         * The stored incident the gate should match, or null when it should match nothing.
         *
         * The expectation names a record, so the check has to as well. Checking only the mode
         * passes a run that short-circuited on some OTHER record while the text on screen says
         * which one it would be — the claim false, the verdict green.
         */
        String expectedMatchId,
        /** Null when the scenario makes no claim about model calls. */
        Integer expectedWarmModelCalls,
        boolean expectStaleRejection,
        /**
         * The payment-service version to put in place before the run. This is the whole
         * mechanism behind the staleness scenario: the stored remediation references a version,
         * and whether that version is still deployed is a zero-token check.
         */
        String deployedPaymentVersion,
        /**
         * A version the answer must not roll back to, or null.
         *
         * The staleness gate stops memory's stored FIX being served, but the model still reads
         * the stale record and can borrow its version number: observed, a run told to "roll back
         * to the previous stable release (e.g., 4.7.0)" — the release that introduced the bug.
         * The gate refusing to reuse and the answer still being confidently wrong is the failure
         * this scenario exists for, so it is checked rather than left to whoever is watching.
         */
        String staleVersion
) {
    public IncidentRequest toRequest(boolean useMeko) {
        return new IncidentRequest(incidentId, service, symptom, useMeko, useMeko, severity);
    }

    /** For the ablation: memory and knowledge switched independently. */
    public IncidentRequest toRequest(boolean useMeko, boolean useKnowledge) {
        return new IncidentRequest(incidentId, service, symptom, useMeko, useKnowledge, severity);
    }
}
