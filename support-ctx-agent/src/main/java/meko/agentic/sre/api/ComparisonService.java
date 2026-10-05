package meko.agentic.sre.api;

import com.embabel.agent.api.event.AgenticEventListener;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import meko.agentic.sre.api.ComparisonController.AblationResult;
import meko.agentic.sre.api.ComparisonController.ClaimBasis;
import meko.agentic.sre.api.ComparisonController.ComparisonRequest;
import meko.agentic.sre.api.ComparisonController.ComparisonResult;
import meko.agentic.sre.api.ComparisonController.Delta;
import meko.agentic.sre.config.TokenProperties;
import meko.agentic.sre.domain.ContextClaim;
import meko.agentic.sre.domain.IncidentReport;
import meko.agentic.sre.domain.IncidentRequest;
import meko.agentic.sre.domain.IncidentSeverity;
import meko.agentic.sre.service.ClaimDeriver;
import org.springframework.stereotype.Service;

/**
 * The comparison and the ablation, shared by the JSON endpoints and the streaming ones so the
 * two can never disagree about what a run means. The only difference between them is whether
 * anyone is listening.
 */
@Service
public class ComparisonService {

    /** Who to tell about a run, by its label. */
    @FunctionalInterface
    public interface Listeners {
        List<AgenticEventListener> forRun(String run);

        Listeners NONE = run -> List.of();
    }

    private final IncidentController.IncidentInvoker invoker;
    private final TokenProperties properties;
    private final ClaimDeriver claimDeriver;

    public ComparisonService(
            IncidentController.IncidentInvoker invoker, TokenProperties properties, ClaimDeriver claimDeriver) {
        this.invoker = invoker;
        this.properties = properties;
        this.claimDeriver = claimDeriver;
    }

    public IncidentReport investigate(IncidentRequest request, Listeners listeners) {
        return invoker.invoke(request, listeners.forRun(request.useMeko() ? "warm" : "cold"));
    }

    /**
     * Cold and warm run CONCURRENTLY. Safe because the cold run never touches Meko — it neither
     * reads nor writes back — so neither run can see the other's effects, and the comparison is
     * the same one the sequential version made in roughly half the wall-clock time.
     */
    public ComparisonResult compare(ComparisonRequest body, Listeners listeners) {
        var severity = severityOf(body);
        IncidentReport cold;
        IncidentReport warm;
        try (var runs = Executors.newVirtualThreadPerTaskExecutor()) {
            var coldRun = CompletableFuture.supplyAsync(() -> invoker.invoke(new IncidentRequest(
                    body.incidentId(), body.service(), body.symptom(), false, false, severity),
                    listeners.forRun("cold")), runs);
            var warmRun = CompletableFuture.supplyAsync(() -> invoker.invoke(new IncidentRequest(
                    body.incidentId(), body.service(), body.symptom(), true, true, severity),
                    listeners.forRun("warm")), runs);
            cold = join(coldRun);
            warm = join(warmRun);
        }
        var delta = Delta.between(cold, warm, properties.projection().incidentsPerMonth());
        if (declared(body)) {
            return new ComparisonResult(cold, warm, delta,
                    checkClaims(body.contextClaims(), cold, warm), ClaimBasis.DECLARED);
        }
        var derived = claimDeriver.derive(warm, cold);
        return new ComparisonResult(cold, warm, delta, derived.claims(), basisOf(derived));
    }

    /**
     * ABLATION: the same incident three ways, so context can be watched degrade.
     * <p>
     * Three real runs, not a counterfactual. The argument this demo makes is that the model
     * answers better because it was given better material — and the only way to show that rather
     * than assert it is to take the material away one source at a time and let the room read
     * what happens to the answer.
     * <p>
     * SEQUENTIAL, unlike the comparison. The memory-only run writes back before the full run
     * reads, so running them together would make the third column depend on a race.
     */
    public AblationResult ablate(ComparisonRequest body, Listeners listeners) {
        var severity = severityOf(body);
        var none = invoker.invoke(new IncidentRequest(
                body.incidentId(), body.service(), body.symptom(), false, false, severity),
                listeners.forRun("none"));
        var memoryOnly = invoker.invoke(new IncidentRequest(
                body.incidentId(), body.service(), body.symptom(), true, false, severity),
                listeners.forRun("memory"));
        var everything = invoker.invoke(new IncidentRequest(
                body.incidentId(), body.service(), body.symptom(), true, true, severity),
                listeners.forRun("everything"));

        // ATTRIBUTE EACH CLAIM TO THE RIGHT BASELINE.
        //
        // Checking everything against the no-context run conflates the two sources: a claim about
        // the runbook compared against a run that also lacked memory tells you only that context
        // helped, not which part. A knowledge claim is compared against MEMORY-ONLY, so the
        // single variable is the runbook. Everything else compares against no context at all.
        if (!declared(body)) {
            // No scenario claims: derive them from what the full run matched, against the run
            // with no memory at all — derived claims are about memory, so that is the baseline.
            var derived = claimDeriver.derive(everything, none);
            return new AblationResult(none, memoryOnly, everything, derived.claims(), basisOf(derived));
        }
        var claims = body.contextClaims()
                .stream()
                .map(c -> {
                    var baseline = "curated knowledge".equals(c.source()) ? memoryOnly : none;
                    return new ContextClaim.Result(
                            c, c.presentIn(answerOf(everything)), c.presentIn(answerOf(baseline)));
                })
                .toList();

        return new AblationResult(none, memoryOnly, everything, claims, ClaimBasis.DECLARED);
    }

    private static boolean declared(ComparisonRequest body) {
        return body.contextClaims() != null && !body.contextClaims().isEmpty();
    }

    private static ClaimBasis basisOf(ClaimDeriver.Derived derived) {
        return derived.claims().isEmpty()
                ? ClaimBasis.NONE
                : new ClaimBasis("derived", derived.fromIncident(), derived.servedFromMemory());
    }

    private static IncidentSeverity severityOf(ComparisonRequest body) {
        return body.severity() == null ? IncidentSeverity.SEV2 : body.severity();
    }

    /** Rethrow what the run threw, not the future's wrapper, so callers see the same errors as before. */
    private static IncidentReport join(CompletableFuture<IncidentReport> run) {
        try {
            return run.join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            throw e;
        }
    }

    /**
     * Check each claim against BOTH runs.
     *
     * "The warm answer mentions 4.7.0" proves nothing on its own — a model can produce a version
     * number unaided and nobody reading can tell whether it did. "Present with context, absent
     * without it" is the claim that means something, and it self-calibrates: a claim the cold run
     * also satisfies reports as not distinctive rather than passing.
     */
    private List<ContextClaim.Result> checkClaims(
            List<ContextClaim> claims, IncidentReport cold, IncidentReport warm) {
        if (claims == null || claims.isEmpty()) {
            return List.of();
        }
        return claims.stream()
                .map(c -> new ContextClaim.Result(
                        c, c.presentIn(answerOf(warm)), c.presentIn(answerOf(cold))))
                .toList();
    }

    /** Root cause and recommended action together: the claim is about the answer, not a field. */
    private String answerOf(IncidentReport report) {
        return report == null ? "" : report.rootCause() + " " + report.recommendedAction();
    }
}
