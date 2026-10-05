package meko.agentic.sre.domain;

import java.util.List;

/**
 * A parsed INCIDENT_RECORD out of Meko memory. Structured deliberately: the reuse gate
 * has to score matches without spending a single token, which means the fields have to be
 * machine-readable rather than prose.
 */
public record StoredIncident(
        String incidentId,
        String service,
        String signature,
        /**
         * WHICH service {@link #deployVersion} refers to.
         *
         * Not the same as {@link #service}, and conflating them killed the short-circuit path
         * entirely. The demo incident is on 'checkout' but the version its remediation depends
         * on is payment-service's, so staleness was checking whether checkout was running
         * payment-service 4.7.0 — never true — and every match was rejected as stale. The
         * headline "answered from memory, zero model calls" was unreachable.
         */
        String deployService,
        String deployVersion,
        String rootCause,
        String remediation,
        List<String> scopeServices,
        String observedAt,
        String raw,
        /**
         * Meko's similarity score for this row against the incident query.
         *
         * Kept alongside the reuse gate's lexical Jaccard on purpose: the gap between the two
         * IS the product. A record can share almost no words with the incident being
         * investigated and still be the right one, and only one of these two numbers can see
         * that. Zero when the record came from a path that carries no score.
         */
        double semanticScore,
        /**
         * Vector similarity before Meko's blend.
         *
         * Kept beside the blended score because on this corpus they disagree in a way that
         * matters: the blend penalises long structured records by roughly half, so the record
         * the gate wants can rank last on score while ranking first on raw.
         */
        double rawScore,
        /** The agent that wrote this record. See MekoToolBridge.MekoMemory. */
        String writtenBy
) {
    public StoredIncident {
        scopeServices = scopeServices == null ? List.of() : List.copyOf(scopeServices);
        writtenBy = writtenBy == null || writtenBy.isBlank() ? "unknown" : writtenBy;
    }
}
