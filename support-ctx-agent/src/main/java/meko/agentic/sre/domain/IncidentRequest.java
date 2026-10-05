package meko.agentic.sre.domain;

/**
 * @param useMeko      consult learned memory: prior incidents and routing outcomes
 * @param useKnowledge consult curated knowledge: uploaded runbooks and promoted policy
 *
 * The two are separate because they are separate claims. Memory is what the system LEARNED;
 * knowledge is what people WROTE. Being able to switch them independently is what turns "memory
 * helps" from an assertion into an ablation you can watch degrade.
 */
public record IncidentRequest(
        String incidentId,
        String service,
        String symptom,
        boolean useMeko,
        boolean useKnowledge,
        IncidentSeverity severity
) {
    public IncidentRequest {
        severity = severity == null ? IncidentSeverity.SEV2 : severity;
        // Knowledge without memory is a coherent configuration; memory without the datapack is
        // not, so knowledge is simply gated on the same connection.
    }

    public IncidentRequest(String incidentId, String service, String symptom) {
        this(incidentId, service, symptom, true, true, IncidentSeverity.SEV2);
    }

    public IncidentRequest(String incidentId, String service, String symptom, boolean useMeko) {
        this(incidentId, service, symptom, useMeko, useMeko, IncidentSeverity.SEV2);
    }

    public IncidentRequest(String incidentId, String service, String symptom, boolean useMeko,
                           IncidentSeverity severity) {
        this(incidentId, service, symptom, useMeko, useMeko, severity);
    }

    /** What to call this configuration on screen. */
    public String contextMode() {
        if (!useMeko && !useKnowledge) {
            return "NO_CONTEXT";
        }
        return useKnowledge ? "MEMORY_AND_KNOWLEDGE" : "MEMORY_ONLY";
    }
}
