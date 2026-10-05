package meko.agentic.sre.domain;

/**
 * How the run ended. Two goals, two endings.
 *
 * The agent has always been able to produce a diagnosis. It could not, until now, decide that a
 * diagnosis was not worth buying — and "keep escalating until the confidence threshold is met"
 * is a policy that spends without a ceiling. A system that cannot hand off has only one move.
 */
public enum IncidentOutcome {
    /** A diagnosis cleared the confidence bar and was validated against live state. */
    DIAGNOSED,
    /**
     * Handed to a human with the evidence gathered so far.
     *
     * Chosen when the planner judges another escalation not worth its cost against the
     * severity budget. Deliberately does NOT write a reusable remediation: an answer nobody
     * accepted must not become history that a later run can short-circuit on.
     */
    HANDED_OFF
}
