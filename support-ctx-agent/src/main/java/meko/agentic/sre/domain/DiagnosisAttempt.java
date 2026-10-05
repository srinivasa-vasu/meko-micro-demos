package meko.agentic.sre.domain;

import java.util.List;

public record DiagnosisAttempt(
        Diagnosis diagnosis,
        ModelTier modelTier,
        String modelRole,
        boolean escalation,
        /** True when the answer came from Meko with no generative call. */
        boolean servedFromMemory,
        ValidationResult validation,
        /**
         * Accounting accumulated across replans. An escalation copies the entries of the
         * attempt it replaced, so a cheap-then-escalate run reports what it really cost
         * rather than only what the final call cost.
         */
        List<TokenLedger.Entry> ledgerEntries
) {
    public DiagnosisAttempt {
        ledgerEntries = ledgerEntries == null ? List.of() : List.copyOf(ledgerEntries);
    }
}
