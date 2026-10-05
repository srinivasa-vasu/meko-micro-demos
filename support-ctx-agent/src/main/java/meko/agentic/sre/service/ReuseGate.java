package meko.agentic.sre.service;

import meko.agentic.sre.config.TokenProperties;
import meko.agentic.sre.domain.IncidentRequest;
import meko.agentic.sre.domain.MekoContext;
import meko.agentic.sre.domain.ReuseDecision;
import meko.agentic.sre.domain.ReuseMode;
import meko.agentic.sre.domain.StoredIncident;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Decides how much of the stored answer we can reuse — using only deterministic signals.
 * <p>
 * The ordering is the whole point. Cheap filters (service identity, signature overlap,
 * deployment liveness) run first and eliminate most bad candidates for free. A model is
 * only consulted afterwards, and only when the judgement it is being asked for is at least
 * an order of magnitude cheaper than the work it would avoid.
 */
@Service
public class ReuseGate {

    private static final Set<String> STOPWORDS = Set.of(
            "the", "a", "an", "and", "or", "of", "to", "in", "on", "after", "before",
            "increased", "sharply", "started", "we", "our", "is", "are", "was", "were", "has"
    );

    private final IncidentDataService incidentData;
    private final TokenProperties properties;

    public ReuseGate(IncidentDataService incidentData, TokenProperties properties) {
        this.incidentData = incidentData;
        this.properties = properties;
    }

    public ReuseDecision evaluate(IncidentRequest request, MekoContext context) {
        if (!context.enabled() || context.priorIncidents().isEmpty()) {
            return ReuseDecision.none("No Meko context available; full investigation required.");
        }

        StoredIncident best = null;
        double bestScore = 0.0;
        int selfMatches = 0;
        for (var candidate : context.priorIncidents()) {
            if (sameIncident(request, candidate)) {
                selfMatches++;
                continue;
            }
            double score = score(request, candidate);
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }

        if (best == null || bestScore < properties.reuse().scopedThreshold()) {
            var why = selfMatches > 0
                    ? ("This incident's own earlier write-up was found (%d record(s)) and ignored: a run "
                            + "matching itself is an echo, not history. No OTHER incident scored above the "
                            + "reuse threshold, so a full investigation is required. Re-run under a new "
                            + "incident id to see reuse, which is what a real recurrence would carry.")
                            .formatted(selfMatches)
                    : "No historical incident scored above the reuse threshold; full investigation required.";
            return ReuseDecision.of(ReuseMode.FULL, bestScore, false, "", best, selfMatches, why);
        }

        // Staleness: a stored remediation can be right about the diagnosis and wrong about
        // the fix, because the system moved on. Checking costs nothing and is what stops this
        // from confidently applying a fix to a version that is no longer deployed.
        var staleness = staleness(best);
        if (staleness != null) {
            return ReuseDecision.of(ReuseMode.SCOPED, bestScore, true, staleness, best,
                    "Strong match found but its remediation is stale, so it is used to narrow "
                            + "telemetry only, not to answer. " + staleness);
        }

        if (bestScore >= properties.reuse().shortCircuitThreshold()) {
            return ReuseDecision.of(ReuseMode.SHORT_CIRCUIT, bestScore, false, "", best,
                    "Deterministic signature match against %s at %.2f with live deployment state; "
                            .formatted(best.incidentId(), bestScore)
                            + "stored remediation reused without a generative call.");
        }

        return ReuseDecision.of(ReuseMode.SCOPED, bestScore, false, "", best,
                "Partial match against %s at %.2f. History does not answer the question but it "
                        .formatted(best.incidentId(), bestScore)
                        + "identifies the services that matter, so telemetry is narrowed to "
                        + String.join(", ", best.scopeServices()) + ".");
    }

    private boolean sameIncident(IncidentRequest request, StoredIncident candidate) {
        return request.incidentId() != null
                && candidate.incidentId() != null
                && request.incidentId().equalsIgnoreCase(candidate.incidentId());
    }

    /** @return null when fresh, otherwise a human-readable reason. */
    private String staleness(StoredIncident candidate) {
        if (candidate.deployVersion() == null || candidate.deployVersion().isBlank()) {
            return "Stored incident does not record a deployment version, so its remediation cannot be confirmed current.";
        }
        if (candidate.deployService() == null || candidate.deployService().isBlank()) {
            return "Stored incident does not record which service its version refers to, so its "
                    + "remediation cannot be confirmed current. Re-seed to refresh the record.";
        }
        var service = candidate.deployService();
        var referenced = candidate.deployVersion();
        if (!incidentData.versionStillDeployed(service, referenced)) {
            var current = incidentData.currentVersion(service);
            return "Stored remediation references %s %s but %s is deployed now."
                    .formatted(service, referenced, current == null ? "a different version" : current);
        }
        return null;
    }

    private double score(IncidentRequest request, StoredIncident candidate) {
        double serviceScore = equalsIgnoreCase(request.service(), candidate.service())
                || candidate.scopeServices().stream().anyMatch(s -> equalsIgnoreCase(s, request.service()))
                ? 1.0 : 0.0;
        if (serviceScore == 0.0) {
            return 0.0; // cheapest possible rejection
        }
        double signatureScore = jaccard(tokens(request.symptom()), tokens(candidate.signature()));
        double rootCauseScore = jaccard(tokens(request.symptom()), tokens(candidate.rootCause()));
        return 0.45 * serviceScore + 0.40 * signatureScore + 0.15 * rootCauseScore;
    }

    private boolean equalsIgnoreCase(String a, String b) {
        return a != null && b != null && a.equalsIgnoreCase(b.trim());
    }

    private Set<String> tokens(String text) {
        if (text == null) {
            return Set.of();
        }
        var out = new LinkedHashSet<String>();
        Arrays.stream(text.toLowerCase(Locale.ROOT).split("[^a-z0-9.]+"))
                .filter(t -> t.length() > 2 && !STOPWORDS.contains(t))
                .forEach(out::add);
        return out;
    }

    private double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) {
            return 0.0;
        }
        var intersection = new LinkedHashSet<>(a);
        intersection.retainAll(b);
        var union = new LinkedHashSet<>(a);
        union.addAll(b);
        return (double) intersection.size() / union.size();
    }
}
