package meko.agentic.sre.domain;

import java.util.List;

public record IncidentReport(
        String incidentId,
        String mode,
        /** Which goal the planner achieved. See IncidentOutcome. */
        IncidentOutcome outcome,
        String summary,
        String rootCause,
        double confidence,
        String recommendedAction,
        String learningToRemember,
        boolean historicalMatch,
        boolean learningPersisted,
        int historicalContextItems,
        /**
         * The records memory actually returned, with their scores and writers.
         *
         * A count alone ("8 context items") asks the audience to take the retrieval on faith,
         * which is a strange thing to ask in a demo whose entire subject is retrieval.
         */
        List<StoredIncident> recalled,
        /** Rows memory returned before filtering to incident records. See MekoContext. */
        int memoryRowsReturned,
        /** Runbook chunks from uploaded knowledge-base documents that reached this run. */
        List<String> runbookChunks,
        /**
         * What the model was actually given, by origin, with token counts.
         *
         * The ledger says what a run spent. This says what it was composed of — which is the
         * question that matters when the claim is "the right context", not "less of it".
         */
        List<ContextSource> contextSources,
        long promptTokens,
        ModelTier selectedModelTier,
        String selectedModelRole,
        List<String> modelsUsed,
        String routingRationale,
        /** Which of the three stores answered: promoted knowledge, this agent's outcomes, or config. */
        String routingPolicySource,
        int routingObservations,
        /** True when this run's evidence graduated a private estimate into shared knowledge. */
        boolean routingPolicyShared,
        boolean escalated,

        // --- reuse + savings ---
        ReuseMode reuseMode,
        /** The reuse gate's deterministic keyword overlap. Zero tokens, and lexical only. */
        double matchScore,
        /**
         * Meko's semantic score for the same record. The GAP between this and matchScore is
         * the part of the demo a customer cannot rebuild with grep: a record can share almost
         * no vocabulary with the incident and still be the right one.
         */
        double semanticScore,
        /** Vector similarity before Meko's blend. Negative when the row was listed, not ranked. */
        double rawScore,
        String matchedIncidentId,
        String matchedSignature,
        /** Which agent wrote the matched record. Cross-agent recall, made visible. */
        String matchedWrittenBy,
        /** Records for this same incident id that were skipped as self-matches. */
        int selfMatchesIgnored,
        boolean staleMatchRejected,
        String stalenessReason,
        List<String> scopedTo,
        int logLinesSkipped,
        boolean remediationValidated,
        List<String> failedAssumptions,

        // --- accounting ---
        int llmCalls,
        long inputTokens,
        long outputTokens,
        double actualUsd,
        long counterfactualTokens,
        double counterfactualUsd,
        long tokensSaved,
        double usdSaved,
        boolean costMeasured,
        double budgetCeilingUsd,
        /** Meko's own cost, reported rather than omitted. See TokenLedger.Retrieval. */
        int mekoRetrievalCalls,
        double mekoRetrievalUsd,
        /** Model spend plus Meko spend. Compare THIS against a baseline, not actualUsd. */
        double totalUsd,
        List<TokenLedger.Entry> ledger,
        List<TokenLedger.Retrieval> retrievals,
        /**
         * The actions the planner actually executed, in order, as Embabel reported them.
         *
         * The page draws the run's path from THIS rather than reconstructing it from the reuse
         * mode and models used — a reconstruction shows what should have happened; this shows
         * what did, including an escalation or a hand-off nobody predicted.
         */
        List<String> actionsTaken,
        /**
         * Meko reads that failed during this run. Non-empty means recall was PARTIAL: what the
         * run did without that material is not evidence that memory had nothing.
         */
        List<String> recallFailures
) {
    public IncidentReport {
        modelsUsed = modelsUsed == null ? List.of() : List.copyOf(modelsUsed);
        scopedTo = scopedTo == null ? List.of() : List.copyOf(scopedTo);
        failedAssumptions = failedAssumptions == null ? List.of() : List.copyOf(failedAssumptions);
        ledger = ledger == null ? List.of() : List.copyOf(ledger);
        retrievals = retrievals == null ? List.of() : List.copyOf(retrievals);
        runbookChunks = runbookChunks == null ? List.of() : List.copyOf(runbookChunks);
        contextSources = contextSources == null ? List.of() : List.copyOf(contextSources);
        actionsTaken = actionsTaken == null ? List.of() : List.copyOf(actionsTaken);
        recallFailures = recallFailures == null ? List.of() : List.copyOf(recallFailures);
    }

    /** The same report with the executed actions attached, once the planner has finished. */
    public IncidentReport withActionsTaken(List<String> actions) {
        return new IncidentReport(
                incidentId,
                mode,
                outcome,
                summary,
                rootCause,
                confidence,
                recommendedAction,
                learningToRemember,
                historicalMatch,
                learningPersisted,
                historicalContextItems,
                recalled,
                memoryRowsReturned,
                runbookChunks,
                contextSources,
                promptTokens,
                selectedModelTier,
                selectedModelRole,
                modelsUsed,
                routingRationale,
                routingPolicySource,
                routingObservations,
                routingPolicyShared,
                escalated,
                reuseMode,
                matchScore,
                semanticScore,
                rawScore,
                matchedIncidentId,
                matchedSignature,
                matchedWrittenBy,
                selfMatchesIgnored,
                staleMatchRejected,
                stalenessReason,
                scopedTo,
                logLinesSkipped,
                remediationValidated,
                failedAssumptions,
                llmCalls,
                inputTokens,
                outputTokens,
                actualUsd,
                counterfactualTokens,
                counterfactualUsd,
                tokensSaved,
                usdSaved,
                costMeasured,
                budgetCeilingUsd,
                mekoRetrievalCalls,
                mekoRetrievalUsd,
                totalUsd,
                ledger,
                retrievals,
                actions,
                recallFailures);
    }
}
