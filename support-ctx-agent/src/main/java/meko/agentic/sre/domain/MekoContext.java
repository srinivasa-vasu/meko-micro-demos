package meko.agentic.sre.domain;

import org.jspecify.annotations.Nullable;

import java.util.List;

import meko.agentic.sre.domain.TokenLedger.Retrieval;

public record MekoContext(
        boolean enabled,
        List<String> memory,
        List<String> knowledge,
        List<StoredIncident> priorIncidents,
        ModelTier preferredTier,
        double confidenceThreshold,
        /** USD ceiling for this run, from Meko knowledge (policy) or config fallback. */
        double budgetCeilingUsd,
        String routingRationale,
        @Nullable String policySource,
        /** The resolved policy, so the report can say WHERE the routing decision came from. */
        @Nullable RoutingPolicy routingPolicy,
        /**
         * Rows memory returned, before filtering to incident records.
         *
         * Kept separate from priorIncidents so a failure can name itself: zero rows is a
         * retrieval problem, but rows with no incident records among them is a completely
         * different one — the window went to another record type — and they had been reported
         * identically.
         */
        int memoryRowsReturned,
        /** Runbook chunks from uploaded documents, kept separate so the UI can show them. */
        List<String> runbookChunks,
        /** What the retrieval that built this context cost, carried to the ledger. */
        @Nullable Retrieval retrievalCost,
        /**
         * Meko reads that FAILED while building this context, one line each.
         *
         * A failed read degrades to empty so the run survives, which makes it look exactly like
         * memory having nothing — and the gate then plans a full investigation that reads as a
         * genuine miss. Carried to the report so a partial recall is said, not inferred.
         */
        List<String> recallFailures
) {
    /** A context built without tracking failed reads: none are reported. */
    public MekoContext(boolean enabled, List<String> memory, List<String> knowledge,
                       List<StoredIncident> priorIncidents, ModelTier preferredTier,
                       double confidenceThreshold, double budgetCeilingUsd, String routingRationale,
                       @Nullable String policySource, @Nullable RoutingPolicy routingPolicy,
                       int memoryRowsReturned, List<String> runbookChunks,
                       @Nullable Retrieval retrievalCost) {
        this(enabled, memory, knowledge, priorIncidents, preferredTier, confidenceThreshold,
                budgetCeilingUsd, routingRationale, policySource, routingPolicy, memoryRowsReturned,
                runbookChunks, retrievalCost, List.of());
    }

    public MekoContext {
        memory = memory == null ? List.of() : List.copyOf(memory);
        knowledge = knowledge == null ? List.of() : List.copyOf(knowledge);
        priorIncidents = priorIncidents == null ? List.of() : List.copyOf(priorIncidents);
        runbookChunks = runbookChunks == null ? List.of() : List.copyOf(runbookChunks);
        recallFailures = recallFailures == null ? List.of() : List.copyOf(recallFailures);
        preferredTier = preferredTier == null ? ModelTier.LLM : preferredTier;
        confidenceThreshold = confidenceThreshold <= 0 ? 0.75 : confidenceThreshold;
        budgetCeilingUsd = budgetCeilingUsd <= 0 ? 0.15 : budgetCeilingUsd;
        routingRationale = routingRationale == null
                ? "No historical routing policy was found; use the balanced model."
                : routingRationale;
    }

    public int contextItems() {
        return memory.size() + knowledge.size();
    }

    public String memoryBlock() {
        return String.join("\n", memory);
    }

    public String knowledgeBlock() {
        return String.join("\n", knowledge);
    }
}
