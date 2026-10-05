package meko.agentic.sre.domain;

import java.util.List;

/**
 * Deterministic check of the proposed remediation against live system state.
 * Costs zero tokens and is the thing that stops a confident wrong answer from a
 * superficially matching historical incident.
 */
public record ValidationResult(
        boolean valid,
        List<String> failedAssumptions,
        String summary
) {
    public ValidationResult {
        failedAssumptions = failedAssumptions == null ? List.of() : List.copyOf(failedAssumptions);
    }

    public static ValidationResult ok() {
        return new ValidationResult(true, List.of(), "All remediation assumptions hold against current state.");
    }
}
