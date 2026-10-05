package meko.agentic.sre.domain;

/**
 * Severity drives the spend ceiling. The ceiling itself is organizational policy and
 * is read from Meko knowledge where available, falling back to configuration.
 */
public enum IncidentSeverity {
    SEV1, SEV2, SEV3
}
