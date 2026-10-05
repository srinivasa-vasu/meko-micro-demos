package meko.agentic.sre.api;

import meko.agentic.sre.domain.IncidentSeverity;
import meko.agentic.sre.domain.ContextClaim;
import meko.agentic.sre.domain.ReuseMode;
import meko.agentic.sre.domain.Scenario;
import meko.agentic.sre.service.ClaimDeriver;
import meko.agentic.sre.service.SeedService;
import meko.agentic.sre.service.IncidentDataService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The three world states worth showing, each armed in one call.
 * <p>
 * WHY THESE THREE. The demo's claim is not "memory makes things cheaper" — that is only true
 * when memory is right. The claim is that the gate makes a correct decision about how much of
 * the stored answer applies, and there are exactly three correct decisions:
 * <p>
 *   remembered  reuse it          the match is strong AND the world has not moved
 *   moved-on    refuse to reuse   the match is strong but the deployment it assumed is gone
 *   unfamiliar  decline to match  nothing in the corpus is about this incident
 * <p>
 * The second and third are the ones that earn trust. Any cache can show the first. A system
 * that only ever demonstrates hits is indistinguishable from one that always claims a hit, and
 * an SRE audience knows it — the refusal is the credible part.
 * <p>
 * Each scenario carries a failureTell: the observation that means it is broken. Showing that
 * before the run turns the demo into something the room can check rather than watch.
 */
@RestController
@RequestMapping("/api/v1/scenarios")
public class ScenarioController {

    /** The version the stored remediation assumes. Scenario 1 leaves it deployed; scenario 2 does not. */
    private static final String LIVE_VERSION = SeedService.RECORDED_DEPLOY_VERSION;
    /** The service whose version the recorded remediation depends on. From the seed, like the version. */
    private static final String PINNED_SERVICE = SeedService.RECORDED_DEPLOY_SERVICE;
    private static final String MOVED_ON_VERSION = "5.0.0";

    /** The incident the seed writes. Named by the predictions below and checked after the run. */
    private static final String RECORDED = SeedService.RECORDED_INCIDENT_ID;

    private static final List<String> FIX_FORWARD = ClaimDeriver.FIX_FORWARD;

    /** Ordered: each one is a harder question than the last. */
    private static final List<Scenario> SCENARIOS = List.of(
            new Scenario(
                    "remembered",
                    "We have seen this before",
                    "The same checkout regression that happened in March, firing the same alert, "
                            + "and nothing about the system has changed since the fix was recorded.",
                    "The gate should recognise this as a repeat of the March incident (" + RECORDED
                            + ", written to memory when you armed this scenario), confirm " + PINNED_SERVICE + " "
                            + LIVE_VERSION + " is still deployed, and answer from memory. The warm run "
                            + "should make zero model calls; the cold run sweeps every log through a model.",
                    "If the warm run still calls a model, the match is not clearing "
                            + "token.reuse.short-circuit-threshold. If it matched a record other than "
                            + RECORDED + ", memory holds something this scenario did not plan for. Note that "
                            + "a loose paraphrase of this alert scores below that bar and correctly does NOT "
                            + "short-circuit — the bar for answering with no model at all is deliberately high.",
                    "INC-4821",
                    "checkout",
                    "Checkout failures: payment provider timeout after deployment",
                    IncidentSeverity.SEV2,
                    // NOT "the answer contains the stored wording". A short circuit answers WITH
                    // the stored record, so that claim checks the record against itself — and the
                    // cold run derives the same 5s fix from the change log, just phrased
                    // differently, so it passed on phrasing rather than on substance. That the
                    // stored answer was served is already checked structurally: zero model calls.
                    //
                    // What memory genuinely changes here is commitment. Without it the model
                    // hedges with a fix-forward alternative; the recorded fix is a rollback only.
                    List.of(
                            new ContextClaim(
                                    "Commits to the recorded rollback instead of offering to raise "
                                            + "the timeout as an alternative",
                                    List.of("roll back", "rollback"),
                                    FIX_FORWARD,
                                    "learned memory"),
                            new ContextClaim(
                                    "Names the alerting follow-up recorded with the original fix",
                                    List.of("alert on payment-api timeout rate"),
                                    "learned memory")),
                    ReuseMode.SHORT_CIRCUIT, RECORDED, 0, false,
                    LIVE_VERSION, null),

            new Scenario(
                    "moved-on",
                    "It looks familiar. The system moved.",
                    "The same symptom and the same strong match — but " + PINNED_SERVICE + " is now on "
                            + MOVED_ON_VERSION + ", and the stored remediation assumed " + LIVE_VERSION + ".",
                    "The gate should find the SAME strong match and then REFUSE to serve it, because "
                            + "the version its remediation depends on is no longer deployed. Expect a stale "
                            + "rejection, telemetry narrowed to the implicated services rather than skipped, "
                            + "and a real model call. Cheaper than the cold run, but not free — which is the "
                            + "correct answer.",
                    "If this short-circuits and serves the stored fix, the staleness check has failed "
                            + "and the demo is broken. That is the failure that causes a second outage. "
                            + "Watch the rollback target too: an answer that rolls back to " + LIVE_VERSION
                            + " has borrowed the stale record's version even though the gate refused it.",
                    // ITS OWN INCIDENT ID. This used to share INC-4821 with "remembered", so
                    // the two scenarios overwrote each other's write-back and each run's memory
                    // depended on which scenario ran last. In the story it is a different incident
                    // anyway — a recurrence after a new release — so it carries a different id.
                    "INC-5107",
                    "checkout",
                    "Checkout failures: payment provider timeout after deployment",
                    IncidentSeverity.SEV2,
                    // The model DOES run here, with history and runbook in the prompt. The
                    // claim is that better material produces a more specific answer: it should
                    // name the version actually deployed and the p95 the runbook tells it to
                    // compare against, rather than blaming the provider.
                    List.of(
                            // AN ORDERING claim, not a ban. The runbook does not forbid fixing
                            // forward; it forbids it "unless the rollback is unavailable". An
                            // answer that rolls back and names the hotfix as the fallback follows
                            // it exactly — the earlier noneOf version failed that answer for
                            // mentioning the hotfix at all. Without the runbook the model leads
                            // with raising the timeout and offers the rollback as the alternative.
                            new ContextClaim(
                                    "Leads with the rollback and raises the timeout only as a "
                                            + "fallback — the runbook allows fixing forward only when "
                                            + "a rollback is unavailable",
                                    List.of("roll back", "rollback"),
                                    // "Roll back, or increase the timeout" puts them side by side
                                    // as equals — rollback first in the text, but not a fallback.
                                    List.of("or increase", "or raise"),
                                    FIX_FORWARD,
                                    "curated knowledge"),
                            // The claim that captures the headline: without relevant context the
                            // model reaches for a performance regression, because the telemetry
                            // shows latency and says nothing about why. With the prior incident
                            // it attributes the cause to configuration. Same numbers, opposite
                            // diagnosis, and only one of them is right.
                            new ContextClaim(
                                    "Attributes the cause to a configuration change, not a "
                                            + "performance or capacity regression",
                                    List.of("timeout"),
                                    List.of("performance regression", "processing latency",
                                            "capacity", "aggressive retry"),
                                    "learned memory"),
                            new ContextClaim(
                                    "Carries the alerting follow-up recorded with the original fix",
                                    List.of("alert on payment-api", "alert on the timeout rate",
                                            "alerting on the timeout"),
                                    "learned memory")),
                    ReuseMode.SCOPED, RECORDED, null, true,
                    MOVED_ON_VERSION, LIVE_VERSION),

            new Scenario(
                    "unfamiliar",
                    "Memory has nothing, and says so",
                    "A search indexing incident. The corpus is all payment and checkout history, so "
                            + "there is genuinely nothing relevant in it.",
                    "The gate should score every candidate at zero on service mismatch and choose a "
                            + "FULL investigation even with memory switched on. The warm and cold runs should "
                            + "look broadly alike.",
                    "If this reports a match or narrows the sweep, the gate is forcing a fit — and "
                            + "every reuse decision in the other two scenarios becomes suspect.",
                    "INC-7730",
                    "search",
                    "Search results returning stale documents after an index rebuild",
                    IncidentSeverity.SEV3,
                    // Memory has nothing, so this is the cleanest test of curated knowledge on
                    // its own. The runbook says to commit or flush before reaching for a
                    // rebuild, and explicitly not to roll back search. Without it a model
                    // reasonably reaches for a reindex.
                    List.of(
                            new ContextClaim(
                                    "Reaches for a flush before a rebuild, which the runbook calls "
                                            + "the smallest action that fixes it",
                                    List.of("flush"),
                                    "curated knowledge"),
                            new ContextClaim(
                                    "Checks whether the indexer is hung rather than assuming the "
                                            + "segments are corrupt",
                                    List.of("hung", "rebuild lock"),
                                    "curated knowledge")),
                    ReuseMode.FULL, null, null, false,
                    LIVE_VERSION, null)
    );

    private final SeedService seedService;
    private final IncidentDataService incidentData;

    public ScenarioController(SeedService seedService, IncidentDataService incidentData) {
        this.seedService = seedService;
        this.incidentData = incidentData;
    }

    @GetMapping
    public List<Scenario> list() {
        return SCENARIOS;
    }

    /**
     * Put the world into this scenario's state: seed the corpus, then set the deployment
     * version the scenario depends on.
     * <p>
     * Seeding runs on every arm, which is safe because SeedService is idempotent: existing
     * rows are matched on seedKey and updated in place, and policy is skipped entirely when the
     * knowledge base already holds it. That matters more than it sounds — a presenter arms all
     * three scenarios in a row, and promotion evicts rows from mem0, so a naive re-seed would
     * add a fresh duplicate policy document to the knowledge base every single time.
     */
    @PostMapping("/{id}/arm")
    public ResponseEntity<ArmResult> arm(@PathVariable String id) {
        var scenario = SCENARIOS.stream()
                .filter(s -> s.id().equalsIgnoreCase(id))
                .findFirst()
                .orElse(null);
        if (scenario == null) {
            return ResponseEntity.notFound().build();
        }

        var seed = seedService.seed();

        var previousVersion = incidentData.currentVersion(PINNED_SERVICE);
        incidentData.setDeployedVersion(PINNED_SERVICE, scenario.deployedPaymentVersion());

        var world = new LinkedHashMap<String, String>();
        world.put(PINNED_SERVICE, scenario.deployedPaymentVersion());
        world.putAll(incidentData.deploymentState());

        var notes = new StringBuilder();
        if (!seed.seeded()) {
            notes.append(seed.message()).append(' ');
        } else {
            notes.append(seed.message()).append(' ');
        }
        if (!scenario.deployedPaymentVersion().equals(previousVersion)) {
            notes.append((PINNED_SERVICE + " moved %s to %s.")
                    .formatted(previousVersion, scenario.deployedPaymentVersion()));
        }

        return ResponseEntity.ok(new ArmResult(
                scenario,
                seed.seeded(),
                seed.promotedToKnowledgeBase(),
                Map.copyOf(world),
                notes.toString().trim()));
    }

    public record ArmResult(
            Scenario scenario,
            boolean seeded,
            boolean promotedToKnowledgeBase,
            Map<String, String> deploymentState,
            String notes) {}
}
