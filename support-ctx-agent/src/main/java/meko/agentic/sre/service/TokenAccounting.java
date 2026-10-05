package meko.agentic.sre.service;

import meko.agentic.sre.config.TokenProperties;
import meko.agentic.sre.domain.ModelTier;
import meko.agentic.sre.domain.TokenLedger;
import org.springframework.stereotype.Service;

/**
 * Token and dollar accounting for a single agent run.
 *
 * IMPORTANT — provenance. {@link #record} takes the provider-reported usage when it is
 * available and falls back to {@link #estimate} only when it is not. Whichever path was
 * taken is carried through to the report and rendered on screen, because "we read it off
 * the usage field" is the only answer to "where did that number come from" that survives
 * contact with a sceptical audience.
 *
 * If your Embabel/Spring AI version surfaces usage metadata on the LLM response, wire it
 * into {@link #record} at the single call site in the agent and every number on the UI
 * becomes measured. Nothing else needs to change.
 */
@Service
public class TokenAccounting {

    /** Rough chars-per-token for English + logs. Only used when usage metadata is absent. */
    private static final double CHARS_PER_TOKEN = 3.8;

    private final TokenProperties properties;

    public TokenAccounting(TokenProperties properties) {
        this.properties = properties;
    }

    public long estimate(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        return Math.max(1, Math.round(text.length() / CHARS_PER_TOKEN));
    }

    public TokenLedger.Entry record(
            String stage,
            ModelTier tier,
            String role,
            Long reportedInput,
            Long reportedOutput,
            String promptFallback,
            String responseFallback) {

        boolean measured = reportedInput != null && reportedOutput != null;
        long input = measured ? reportedInput : estimate(promptFallback);
        long output = measured ? reportedOutput : estimate(responseFallback);
        double usd = properties.priceFor(role).cost(input, output);
        return new TokenLedger.Entry(stage, tier, role, input, output, usd, measured);
    }

    public double cost(String role, long input, long output) {
        return properties.priceFor(role).cost(input, output);
    }

    /**
     * Price the Meko calls a stage made. Embeddings are input-only, so output is zero and the
     * "embedding" pricing entry's output rate is never used.
     *
     * Always estimated — Meko reports no usage metadata — which is why it is reported
     * separately rather than blended into the measured model figures.
     */
    public TokenLedger.Retrieval recordRetrieval(String op, int calls, String... texts) {
        long tokens = 0;
        for (var text : texts) {
            tokens += estimate(text);
        }
        return new TokenLedger.Retrieval(op, calls, tokens, cost("embedding", tokens, 0));
    }

    /**
     * What the pruned plan would have cost: the full telemetry sweep through the frontier
     * tier. Reporting the counterfactual is what turns "used 6k tokens" into
     * "saved 187k tokens", and the trace shows where the delta came from.
     */
    public void recordCounterfactual(TokenLedger ledger, String fullPrompt, String frontierRole) {
        recordCounterfactual(ledger, fullPrompt, frontierRole, null);
    }

    /**
     * @param observedOutput the output tokens this run's own diagnosis actually took, when it made
     *                       one. A served-from-memory run made none, and only then does the
     *                       counterfactual fall back to a typical completion length.
     */
    public void recordCounterfactual(
            TokenLedger ledger, String fullPrompt, String frontierRole, Long observedOutput) {
        long input = estimate(fullPrompt);
        long output = observedOutput != null && observedOutput > 0 ? observedOutput : TYPICAL_COMPLETION_TOKENS;
        ledger.recordCounterfactual(input, output, cost(frontierRole, input, output));
    }

    /** Only for a run that produced no completion of its own to measure against. */
    static final long TYPICAL_COMPLETION_TOKENS = 600;
}
