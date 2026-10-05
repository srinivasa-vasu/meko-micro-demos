package meko.agentic.sre.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * Per-run token and dollar accounting, split by tier and by input/output.
 *
 * Input dominates on this workload and is exactly where memory saves money, so a single
 * blended number would hide the result we are trying to demonstrate.
 *
 * Every entry records whether the counts came from provider usage metadata or from the
 * local estimator. Mixed provenance is surfaced rather than smoothed over: a cost claim
 * an audience cannot trace is worth nothing on stage.
 */
public final class TokenLedger {

    public record Entry(
            String stage,
            ModelTier tier,
            String modelRole,
            long inputTokens,
            long outputTokens,
            double usd,
            boolean measured
    ) {}

    /**
     * What Meko itself cost. Kept apart from {@link Entry} on purpose: retrieval is not a
     * generative call and must not inflate llmCalls() or the model-token comparison, but a
     * demo whose entire claim is "this saves money" cannot leave its own bill off the
     * invoice. Embedding a query is cheap, not free, and an audience that spots the omission
     * discounts every other number on the page.
     */
    public record Retrieval(String op, int calls, long tokens, double usd) {}

    private final List<Entry> entries = new ArrayList<>();
    private final List<Retrieval> retrievals = new ArrayList<>();
    private long counterfactualInput;
    private long counterfactualOutput;
    private double counterfactualUsd;

    public void add(Entry entry) {
        entries.add(entry);
    }

    /** What the plan Embabel pruned would have cost, had it run. */
    public void recordCounterfactual(long input, long output, double usd) {
        this.counterfactualInput = input;
        this.counterfactualOutput = output;
        this.counterfactualUsd = usd;
    }

    public void addRetrieval(Retrieval retrieval) {
        if (retrieval != null && retrieval.calls() > 0) {
            retrievals.add(retrieval);
        }
    }

    public List<Entry> entries() {
        return List.copyOf(entries);
    }

    public List<Retrieval> retrievals() {
        return List.copyOf(retrievals);
    }

    public int retrievalCalls() {
        return retrievals.stream().mapToInt(Retrieval::calls).sum();
    }

    public long retrievalTokens() {
        return retrievals.stream().mapToLong(Retrieval::tokens).sum();
    }

    public double retrievalUsd() {
        return retrievals.stream().mapToDouble(Retrieval::usd).sum();
    }

    /** Model spend plus Meko spend. The number that should be compared against a baseline. */
    public double totalUsd() {
        return usd() + retrievalUsd();
    }

    public long inputTokens() {
        return entries.stream().mapToLong(Entry::inputTokens).sum();
    }

    public long outputTokens() {
        return entries.stream().mapToLong(Entry::outputTokens).sum();
    }

    public long totalTokens() {
        return inputTokens() + outputTokens();
    }

    public double usd() {
        return entries.stream().mapToDouble(Entry::usd).sum();
    }

    public int llmCalls() {
        return (int) entries.stream().filter(e -> e.tier() != ModelTier.NONE).count();
    }

    public long counterfactualTokens() {
        return counterfactualInput + counterfactualOutput;
    }

    public double counterfactualUsd() {
        return counterfactualUsd;
    }

    public long tokensSaved() {
        return Math.max(0, counterfactualTokens() - totalTokens());
    }

    /**
     * Model tokens only. This is the directly measured quantity the A/B compares, and it is
     * the honest headline: no counterfactual, no assumed completion length, just what was
     * actually sent on this run versus what was actually sent on the run without memory.
     */
    public long modelTokens() {
        return totalTokens();
    }

    public double usdSaved() {
        return Math.max(0.0, counterfactualUsd - totalUsd());
    }

    /** True only if every entry came from provider usage metadata. */
    public boolean fullyMeasured() {
        return !entries.isEmpty() && entries.stream().allMatch(Entry::measured);
    }
}
