package meko.agentic.sre.domain;

import java.util.List;

public record Diagnosis(
        String rootCause,
        double confidence,
        String reasoning,
        String recommendedAction,
        boolean resemblesHistoricalIncident,
        /*
          Concrete, checkable claims the remediation depends on, e.g.
          "payment-service 4.7.0 is currently deployed". Validated against live state before
          the run is allowed to finish — a confidence score alone will happily wave through a
          remediation that was correct six months ago and is wrong now.
         */
        List<String> remediationAssumptions
) {
    public Diagnosis {
        remediationAssumptions = remediationAssumptions == null ? List.of() : List.copyOf(remediationAssumptions);
    }
}
