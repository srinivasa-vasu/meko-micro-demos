package meko.agentic.sre.domain;

/**
 * Three rungs of reuse, not a boolean. The middle rung is where most real savings sit:
 * history does not answer the question, but it tells us which slice of telemetry matters,
 * which collapses the prompt from every log line to a handful of services.
 */
public enum ReuseMode {
    /** Deterministic signature match, not stale: serve stored remediation, zero LLM calls. */
    SHORT_CIRCUIT,
    /** Partial match: gather telemetry only for the services history implicates. */
    SCOPED,
    /** No usable match: full telemetry sweep. */
    FULL
}
