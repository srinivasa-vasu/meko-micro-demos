package meko.agentic.sre.service;

import java.util.Objects;
import meko.agentic.sre.config.TokenProperties;
import meko.agentic.sre.domain.ModelTier;
import meko.agentic.sre.domain.RoutingObservation;
import meko.agentic.sre.domain.RoutingPolicy;
import meko.agentic.sre.meko.MekoToolBridge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns accumulated routing outcomes into a routing decision — and, once the evidence is
 * strong enough, into shared organizational policy.
 * <p>
 * The previous design stored ROUTING_POLICY as a memory and read it back with a semantic
 * top-k search, then regex-parsed a threshold out of whatever came back. That works until
 * the corpus grows. Measured against the live datapack, the seeded ROUTING_POLICY row scored
 * 0.450 on a representative query while incident records scored 0.64-0.69 — so the policy was
 * already near the bottom of a limit=8 window, competing for that window against every
 * incident the agent will ever write. When it eventually falls out, parseDouble silently
 * returns its fallback and the run reports a confident routing decision made from a constant.
 * <p>
 * A decision table wants a keyed read, not a similarity search. So the three concerns are
 * separated by STORE rather than crammed into one:
 * <p>
 *   ROUTING_OUTCOME  -> mem0. An append-only observation log. Noisy, per-agent, high volume.
 *                       Vector search is fine here: we aggregate, we don't look rows up.
 *   ROUTING_POLICY   -> the datapack knowledge base, via memory_promote. Curated, shared,
 *                       and out of the incident corpus entirely, so it cannot be crowded out.
 *   Fallback         -> application.yml, clearly labelled as such on the report.
 * <p>
 * The promotion step is the part worth showing. An observation earns its way out of one
 * agent's private memory and into the organization's knowledge only after it has been
 * confirmed enough times — which is a far better argument for a memory layer than "we
 * cached an answer".
 * <p>
 * Deliberately does no retrieval of its own: the agent passes in text it already fetched, so
 * every Meko call stays visible in one place and in the token ledger.
 */
@Service
public class RoutingPolicyService {

    private static final Logger log = LoggerFactory.getLogger(RoutingPolicyService.class);

    /** Cheapest first. The whole thesis is "cheapest sufficient tier", so order matters. */
    private static final List<ModelTier> BY_COST =
            List.of(ModelTier.SLM, ModelTier.LLM, ModelTier.FRONTIER);

    private final MemoryRecordParser parser;
    private final MekoToolBridge meko;
    private final TokenProperties properties;

    public RoutingPolicyService(
            MemoryRecordParser parser, MekoToolBridge meko, TokenProperties properties) {
        this.parser = parser;
        this.meko = meko;
        this.properties = properties;
    }

    /**
     * @param knowledgeText what knowledgebase_search returned (promoted, curated policy)
     * @param outcomeText   what memory_search returned for ROUTING_OUTCOME rows
     */
    public RoutingPolicy resolve(
            String service,
            String knowledgeText,
            String outcomeText,
            ModelTier fallbackTier,
            double fallbackThreshold) {

        // 1. A promoted policy is organizational. It outranks anything this agent inferred.
        var promoted = parser.parseRoutingPolicy(
                knowledgeText, service, RoutingPolicy.Source.PROMOTED_KNOWLEDGE);
        if (promoted.isPresent()) {
            return promoted.get();
        }

        // 2. Nothing shared yet — derive a private estimate from the outcome log.
        var derived = derive(service, outcomeText, fallbackThreshold);
        if (derived != null) {
            return derived;
        }

        // 3. Meko had nothing usable. Say so rather than implying a learned decision.
        return RoutingPolicy.configDefault(service, fallbackTier, fallbackThreshold);
    }

    /**
     * Aggregate ROUTING_OUTCOME rows and pick the cheapest tier that has actually been
     * sufficient often enough. Deterministic and free — no model is consulted to decide
     * which model to consult.
     */
    private RoutingPolicy derive(String service, String outcomeText, double threshold) {
        var byTier = aggregate(service, outcomeText);
        if (byTier.isEmpty()) {
            return null;
        }

        int minObservations = properties.routing().minObservations();
        double target = properties.routing().targetSuccessRate();

        var qualified = BY_COST.stream()
                .map(byTier::get)
                .filter(Objects::nonNull)
                .filter(o -> o.attempts() >= minObservations && o.successRate() >= target)
                .toList();

        if (!qualified.isEmpty()) {
            var winner = qualified.getFirst();
            return new RoutingPolicy(
                    service,
                    winner.tier(),
                    threshold,
                    ("%s handled %d of %d %s incidents without escalating (%.0f%%), at a mean of "
                            + "$%.5f. It is the cheapest tier meeting the %.0f%% bar, so it is tried first.")
                            .formatted(winner.tier(), winner.successes(), winner.attempts(), service,
                                    winner.successRate() * 100, winner.meanUsd(), target * 100),
                    RoutingPolicy.Source.DERIVED_FROM_OUTCOMES,
                    winner.attempts(),
                    winner.successRate(),
                    winner.meanUsd());
        }

        // Nothing clears the bar. The evidence still says something: the cheapest tier we
        // have tried is not good enough for this service, so start one step up. This is the
        // case a static config gets wrong, because it has no way to notice.
        var cheapestTried = BY_COST.stream()
                .map(byTier::get)
                .filter(Objects::nonNull)
                .min(Comparator.comparingInt(o -> BY_COST.indexOf(o.tier())))
                .orElse(null);
        if (cheapestTried == null) {
            return null;
        }
        var stepped = stepUp(cheapestTried.tier());

        // Two different reasons land here and they are not the same claim. "100% of 1 incident,
        // below the 85% bar" is a contradiction that was being printed verbatim: the rate was
        // fine, the SAMPLE was too small. Saying which one it is matters, because thin evidence
        // resolves itself as the corpus grows and a genuinely low rate does not.
        var why = cheapestTried.attempts() < minObservations
                ? ("only %d %s incident(s) have been recorded at %s, fewer than the %d needed to "
                        + "trust the rate, so routing starts one tier up at %s rather than "
                        + "assuming the cheap tier is safe.")
                        .formatted(cheapestTried.attempts(), service, cheapestTried.tier(),
                                minObservations, stepped)
                : ("%s cleared %.0f%% of %d %s incidents without escalating, below the %.0f%% bar, "
                        + "so routing starts at %s instead. Escalation history is what makes this "
                        + "visible; a fixed default would keep paying for the retry.")
                        .formatted(cheapestTried.tier(), cheapestTried.successRate() * 100,
                                cheapestTried.attempts(), service, target * 100, stepped);

        return new RoutingPolicy(
                service,
                stepped,
                threshold,
                why,
                RoutingPolicy.Source.DERIVED_FROM_OUTCOMES,
                cheapestTried.attempts(),
                cheapestTried.successRate(),
                cheapestTried.meanUsd());
    }

    /**
     * One observation per incident, then merged by tier.
     * <p>
     * The per-incident pass matters because a success rate is only meaningful over DISTINCT
     * incidents. A corpus that holds the same outcome three times — a re-run, a re-seed, two
     * agents recording the same result — would otherwise report 155 observations when the
     * evidence is a few dozen, and a confidence that is wrong by the duplication factor.
     * Duplicates are normal in a real store, so the statistic has to be robust to them here
     * rather than assuming the store is clean.
     * <p>
     * Rows carrying no id cannot be deduplicated and are kept as written; the seeded aggregate
     * row is one of these, and counting it once is correct.
     */
    private Map<ModelTier, RoutingObservation> aggregate(String service, String outcomeText) {
        var distinct = new LinkedHashMap<String, RoutingObservation>();
        int anonymous = 0;
        for (var observation : parser.parseRoutingOutcomes(outcomeText)) {
            if (!service.equalsIgnoreCase(observation.service())) {
                continue;
            }
            var id = observation.incidentId();
            var key = id == null || id.isBlank()
                    ? "anon:" + (anonymous++)
                    : "id:" + id.toLowerCase(java.util.Locale.ROOT);
            distinct.putIfAbsent(key, observation);
        }

        var byTier = new LinkedHashMap<ModelTier, RoutingObservation>();
        for (var observation : distinct.values()) {
            byTier.merge(observation.tier(), observation, RoutingObservation::merge);
        }
        return byTier;
    }

    private ModelTier stepUp(ModelTier tier) {
        int next = BY_COST.indexOf(tier) + 1;
        return next < BY_COST.size() ? BY_COST.get(next) : ModelTier.FRONTIER;
    }

    // ------------------------------------------------------------------
    // Promotion
    // ------------------------------------------------------------------

    /**
     * Publish a derived policy to the datapack knowledge base once it is backed by enough
     * evidence, so the next run — and every other agent on this datapack — reads it as
     * organizational policy instead of re-deriving it privately.
     * <p>
     * Guarded three ways, because promotion is irreversible: it must be enabled in config,
     * the policy must be this agent's own derivation (never a re-promotion of something
     * already shared), and the evidence must clear the same bar used to pick the tier.
     * <p>
     * A fresh record is written and that record is promoted, rather than promoting a row
     * found by search — otherwise a demo run would quietly evict hand-seeded corpus data.
     *
     * @return true if the policy is now shared
     */
    /**
     * What sharing did, including the Meko calls it made and the text it wrote, so the ledger
     * prices the calls that happened rather than a guess at them.
     */
    public record ShareOutcome(boolean shared, int mekoCalls, String written) {
        static final ShareOutcome NOT_ATTEMPTED = new ShareOutcome(false, 0, "");
    }

    public ShareOutcome shareIfReady(RoutingPolicy policy, String datapackId) {
        if (!properties.routing().promote()
                || policy == null
                || policy.source() != RoutingPolicy.Source.DERIVED_FROM_OUTCOMES) {
            return ShareOutcome.NOT_ATTEMPTED;
        }
        if (policy.observations() < properties.routing().minObservations()
                || policy.successRate() < properties.routing().targetSuccessRate()) {
            return ShareOutcome.NOT_ATTEMPTED;
        }

        var record = policy.asRecord();
        var written = meko.memoryAdd(record, datapackId);
        if (written.memoryId().isEmpty()) {
            log.debug("Routing policy for {} not written as a new row; nothing to promote.", policy.service());
            return new ShareOutcome(false, 1, record);
        }
        boolean shared = meko.memoryPromote(List.of(written.memoryId().get()), datapackId);
        if (shared) {
            log.info("Routing policy for {} promoted to shared knowledge: {} at {}% over {} observations",
                    policy.service(), policy.preferredTier(),
                    Math.round(policy.successRate() * 100), policy.observations());
        }
        return new ShareOutcome(shared, 2, record);
    }

    public String describe(RoutingPolicy policy) {
        return switch (policy.source()) {
            case PROMOTED_KNOWLEDGE -> "shared knowledge base (promoted, organization-wide)";
            case DERIVED_FROM_OUTCOMES -> "derived this run from %d stored outcomes (not yet shared)"
                    .formatted(policy.observations());
            case CONFIG_DEFAULT -> "application.yml default (Meko had no routing history)";
        };
    }

    /** Unused today; kept because the aggregate view is what a routing dashboard would read. */
    public List<RoutingObservation> observations(String service, String outcomeText) {
        return new ArrayList<>(aggregate(service, outcomeText).values());
    }
}
