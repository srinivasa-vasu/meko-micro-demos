package meko.agentic.sre.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import meko.agentic.sre.domain.ContextClaim;
import meko.agentic.sre.domain.IncidentReport;
import meko.agentic.sre.domain.IncidentSeverity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The A/B is now a controlled comparison with the same tools, same telemetry access and the
 * same planner on both sides. Only memory is removed.
 * <p>
 * If the baseline looks artificially crippled, a technical audience discounts the whole
 * result — so the cold run is a real investigation, just an uninformed one.
 */
@RestController
@RequestMapping("/api/v1")
public class ComparisonController {

    private final ComparisonService comparisons;

    public ComparisonController(ComparisonService comparisons) {
        this.comparisons = comparisons;
    }

    @PostMapping("/compare")
    public ComparisonResult compare(@Valid @RequestBody ComparisonRequest body) {
        return comparisons.compare(body, ComparisonService.Listeners.NONE);
    }

    /** The same incident three ways, so context can be watched degrade. See ComparisonService. */
    @PostMapping("/ablate")
    public AblationResult ablate(@Valid @RequestBody ComparisonRequest body) {
        return comparisons.ablate(body, ComparisonService.Listeners.NONE);
    }

    public record AblationResult(
            IncidentReport noContext,
            IncidentReport memoryOnly,
            IncidentReport memoryAndKnowledge,
            List<ContextClaim.Result> claims,
            ClaimBasis claimBasis) {}

    /**
     * Where the claims came from, so the page can frame them honestly.
     *
     * {@code declared}: written with the scenario, before the run. {@code derived}: built after
     * the run from the stored record the warm run matched (see ClaimDeriver) — and when that run
     * was served from memory, its column passes by construction, which the page has to say.
     * {@code none}: nothing to check.
     */
    public record ClaimBasis(String kind, String fromIncident, boolean servedFromMemory) {
        public static final ClaimBasis DECLARED = new ClaimBasis("declared", "", false);
        public static final ClaimBasis NONE = new ClaimBasis("none", "", false);
    }

    public record ComparisonRequest(
            @NotBlank String incidentId,
            @NotBlank String service,
            @NotBlank String symptom,
            IncidentSeverity severity,
            /** Passed through from the armed scenario; empty for a custom incident. */
            List<ContextClaim> contextClaims
    ) {}

    public record ComparisonResult(
            IncidentReport withoutMeko, IncidentReport withMeko, Delta delta,
            List<ContextClaim.Result> claims, ClaimBasis claimBasis) {}

    /**
     * The end-card numbers.
     *
     * THIS is the honest measurement, and it is why IncidentReport.tokensSaved should not be
     * the headline. That field compares the run against a counterfactual: a full sweep through
     * the frontier tier with an assumed 600-token completion, priced from a local
     * chars-per-token estimate. Nobody runs that, so it flatters the result.
     *
     * Everything below compares two real runs of the same incident with the same planner,
     * tools and telemetry, differing only in whether memory was available. Both sides are
     * measured the same way, so the delta survives being questioned.
     *
     * Meko's own cost is included in the totals rather than omitted. It is small, it is real,
     * and leaving it out is the first thing a sceptical audience goes looking for.
     */
    public record Delta(
            long tokensCold,
            long tokensWarm,
            long tokensSaved,
            double usdCold,
            double usdWarm,
            double usdSaved,
            double reductionPercent,
            int llmCallsCold,
            int llmCallsWarm,
            int logLinesSkipped,
            String coldTier,
            String warmTier,
            boolean measured,
            /** Model spend only, before Meko's own calls. */
            double modelUsdCold,
            double modelUsdWarm,
            /** What the memory layer charged on each side. Zero on the cold run by definition. */
            double mekoUsdCold,
            double mekoUsdWarm,
            int mekoCallsWarm,
            /** Spelled out so nobody has to infer how the figures were produced. */
            String basis,
            /**
             * Per-incident dollars are small by nature. The number that moves a buyer is the
             * same delta at their incident volume, so state it rather than leaving the
             * audience to do the arithmetic — and show the assumption openly.
             *
             * It is a straight-line extrapolation from ONE incident. Real incident mixes are
             * not uniform, so treat it as an order of magnitude, not a forecast.
             */
            int projectedIncidentsPerMonth,
            double projectedMonthlySavingUsd,
            double projectedAnnualSavingUsd
    ) {
        static Delta between(IncidentReport cold, IncidentReport warm, int incidentsPerMonth) {
            long coldTotal = cold.inputTokens() + cold.outputTokens();
            long warmTotal = warm.inputTokens() + warm.outputTokens();
            long saved = Math.max(0, coldTotal - warmTotal);
            double pct = coldTotal == 0 ? 0 : (saved * 100.0) / coldTotal;

            // Compare totals, not model spend: the warm run pays Meko to avoid paying the
            // model, and the comparison is only meaningful if both sides of that trade show.
            double perIncident = Math.max(0.0, cold.totalUsd() - warm.totalUsd());

            return new Delta(
                    coldTotal,
                    warmTotal,
                    saved,
                    cold.totalUsd(),
                    warm.totalUsd(),
                    perIncident,
                    pct,
                    cold.llmCalls(),
                    warm.llmCalls(),
                    warm.logLinesSkipped(),
                    String.valueOf(cold.selectedModelTier()),
                    String.valueOf(warm.selectedModelTier()),
                    cold.costMeasured() && warm.costMeasured(),
                    cold.actualUsd(),
                    warm.actualUsd(),
                    cold.mekoRetrievalUsd(),
                    warm.mekoRetrievalUsd(),
                    warm.mekoRetrievalCalls(),
                    "Two real runs of the same incident, same planner and telemetry, memory the only "
                            + "difference. Model tokens "
                            + (cold.costMeasured() && warm.costMeasured()
                                    ? "from provider usage metadata"
                                    : "estimated locally from prompt and response length")
                            + "; Meko retrieval always estimated. Annual figure extrapolates one incident "
                            + "to " + incidentsPerMonth + " a month.",
                    incidentsPerMonth,
                    perIncident * incidentsPerMonth,
                    perIncident * incidentsPerMonth * 12
            );
        }
    }
}
