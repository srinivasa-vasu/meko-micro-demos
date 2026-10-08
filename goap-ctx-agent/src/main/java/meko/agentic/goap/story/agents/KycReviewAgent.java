package meko.agentic.goap.story.agents;

import com.embabel.agent.api.annotation.AchievesGoal;
import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.Agent;
import com.embabel.agent.api.annotation.Condition;
import com.embabel.agent.api.annotation.Provided;
import com.embabel.agent.api.common.Ai;
import com.embabel.agent.api.common.PlannerType;
import meko.agentic.goap.story.KycReviewMemory;
import meko.agentic.goap.story.ProfileFingerprints;
import meko.agentic.goap.story.StoryLedger;
import meko.agentic.goap.story.store.ContextStore;
import meko.agentic.goap.story.store.StoreRecord;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Story 3: periodic KYC re-review. The previous review was written by another analyst agent last quarter.
 * If it can be recovered, the planner diffs against it and gathers evidence only for material changes;
 * if not, the only path to the goal is a full re-review of every lane.
 */
@Agent(planner = PlannerType.GOAP, description = "KYC analyst agent performing a periodic customer re-review")
public class KycReviewAgent {
    public static final String AGENT_ID = "kyc:review-agent";
    public static final String CUSTOMER = "CUST-7788";

    /** @param cycle the review cycle this run belongs to; reviews this application wrote in earlier cycles are set aside */
    public record ReviewRequest(String caseId, ContextStore.Kind store, String cycle) {}

    /** The current profile, from core banking. It stays in this run: only its fingerprints are ever written or sent to a model. */
    public record CurrentProfile(String caseId, ContextStore.Kind store, String cycle, String address, String ownership,
                                 List<String> beneficiaries, int monthlyVolume) {}

    /** The previous review as it was recovered: its decision and fingerprints, no profile data. */
    public record PriorReview(String caseId, boolean found, String date, String writtenBy, String memoryId, String riskClass,
                              String address, String ownership, List<String> beneficiaries, String volumeBand) {}

    public record ChangeSet(String caseId, List<String> material, List<String> immaterial) {}

    public record Evidence(String caseId, List<String> requests) {}

    public record ReviewDecision(String caseId, String decision) {}

    /** The model's reading of the previous review's fingerprint, if the context contains one. */
    public record PriorReviewAnswer(Boolean found, String date, String riskClass, String address, String ownership,
                                    List<String> beneficiaries, String volumeBand) {}

    /** The model's materiality judgement. */
    public record Materiality(List<String> material, List<String> notMaterial) {}

    /** The persona this agent's gate decisions are recorded under. */
    static final String PERSONA = "KYC analyst agent";

    /** Event names of the analyst's records in the review's conversation (see {@link ContextStore.Turn#event}). */
    public static final String PRIOR_USED = "prior review used";
    public static final String PASSED_OVER = "passed over";
    /** A review this application wrote in an earlier review cycle, set aside by the cycle, not judged as a candidate. */
    public static final String SET_ASIDE = "set aside from an earlier cycle";
    public static final String MATERIAL = "material changes";
    public static final String EVIDENCE = "evidence requested";
    public static final String SIGN_OFF = "sign-off";
    /** How the record says there is nothing: no prior review, no material change, no evidence needed. */
    public static final String NONE = "none";
    /** How a full re-review request is marked in the record. */
    public static final String FULL_REVIEW = " (full re-review: no prior review to compare against)";
    /** "monthly volume more than 25% outside the previous band is material", as the standard's text states it. */
    private static final java.util.regex.Pattern VOLUME_THRESHOLD = java.util.regex.Pattern.compile(
            "monthly volume more than (\\d+)% outside the previous band is material", java.util.regex.Pattern.CASE_INSENSITIVE);

    /** The volume threshold the standard in reach states; empty when no standard is in reach or it states none. */
    static Optional<Double> volumeThreshold(List<StoreRecord> standard) {
        return standard.stream().map(r -> VOLUME_THRESHOLD.matcher(r.text().replaceAll("\\s+", " "))).filter(java.util.regex.Matcher::find)
                .map(m -> Double.parseDouble(m.group(1))).findFirst();
    }

    private final StorySupport support;
    private final DecisionService decisions;
    private final ProfileFingerprints fingerprints;

    public KycReviewAgent(StorySupport support, DecisionService decisions, ProfileFingerprints fingerprints) {
        this.support = support;
        this.decisions = decisions;
        this.fingerprints = fingerprints;
    }

    @Action(description = "Pull the customer's current profile from core banking", post = {"profileLoaded"}, cost = 0.03, canRerun = false)
    public CurrentProfile pullCurrentProfile(ReviewRequest request) {
        // The simulated core banking system's answer for this customer.
        CurrentProfile profile = new CurrentProfile(request.caseId(), request.store(), request.cycle(), "12 Harbour Road",
                "60/40 between two directors", List.of("Northwind Supplies", "Acme Logistics", "Orion Trading FZE"), 44800);
        support.ledger(request.caseId()).step(PERSONA, "pullCurrentProfile",
                CUSTOMER + ": profile loaded from core banking (" + profile.beneficiaries().size() + " beneficiaries, monthly volume band "
                        + ProfileFingerprints.volumeBand(profile.monthlyVolume()) + " USD). It stays in core banking; only fingerprints leave this run.",
                support.effort("kyc-review", "pull-current-profile"), StoryLedger.Kind.WORK);
        return profile;
    }

    @Action(description = "Retrieve the previous KYC review for this customer", pre = {"profileLoaded"},
            post = {"priorKnown", "priorMissing"}, cost = 0.02, canRerun = false)
    public PriorReview retrievePriorReview(CurrentProfile profile, @Provided Ai ai) {
        StoryLedger ledger = support.ledger(profile.caseId());
        List<StoreRecord> found = support.store(profile.store())
                .searchMemory(profile.caseId(), AGENT_ID, "KYC review of customer " + CUSTOMER + " risk class profile fingerprint");
        // Reviews this application wrote in an earlier review cycle are set aside, so each cycle compares with the last one.
        List<StoreRecord> rows = new ArrayList<>();
        for (StoreRecord r : found) {
            Optional<KycReviewMemory> review = KycReviewMemory.parse(r.text());
            if (review.isPresent() && review.get().cycle() != null && !review.get().cycle().equals(profile.cycle())) {
                String reason = "written in an earlier review cycle (" + review.get().cycle() + "); a new cycle compares with the last one";
                support.passedOver(ledger, "memory", r, reason);
                note(profile.store(), profile.caseId(), SET_ASIDE, reference(r) + " — " + reason);
            } else {
                rows.add(r);
            }
        }
        // Settled by rule when nothing was recalled, or when the reviews parse (the latest of exactly this customer); the
        // model reads them only when memory came back in a form the rule cannot parse.
        boolean parses = rows.stream().anyMatch(r -> KycReviewMemory.parse(r.text()).isPresent());
        PriorReviewAnswer answer = decisions.decideUnlessClear(profile.caseId(),
                PERSONA,
                "decisions/kyc-prior-review",
                Map.of("customer", CUSTOMER),
                StorySupport.asContext("Memories", rows),
                PriorReviewAnswer.class,
                () -> rows.isEmpty() || parses ? Optional.of(priorReviewByRule(rows)) : Optional.empty(),
                rows.isEmpty() ? "no review recalled" : "the latest parsed review of exactly this customer",
                () -> priorReviewByRule(rows),
                ai);
        support.traceDecisions(support.store(profile.store()), profile.caseId(), AGENT_ID, profile.caseId(), PERSONA);
        if (!Boolean.TRUE.equals(answer.found())) {
            note(profile.store(), profile.caseId(), PRIOR_USED, NONE + " on record for " + CUSTOMER);
            ledger.step(PERSONA, "retrievePriorReview", "No previous review available to compare against.", support.effort("kyc-review", "retrieve-prior-review"), StoryLedger.Kind.CONTEXT);
            return new PriorReview(profile.caseId(), false, null, null, null, null, null, null, List.of(), null);
        }
        StoreRecord row = rows.stream().filter(r -> KycReviewMemory.parse(r.text())
                        .filter(m -> m.customer().equals(CUSTOMER) && m.date().equals(answer.date())).isPresent())
                .findFirst().orElse(null);
        if (row != null) support.found(ledger, "memory", List.of(row));
        // What the comparison uses comes from the review as stored, not from a copy of it.
        Optional<KycReviewMemory> stored = row == null ? Optional.empty() : KycReviewMemory.parse(row.text());
        // The right context is one review: the latest of exactly this customer. Say why each other review was not it,
        // by reference only: nothing from another review, least of all another customer's, is copied into this record.
        rows.stream().filter(r -> r != row).forEach(r -> KycReviewMemory.parse(r.text()).ifPresent(other -> {
            String reason = !other.customer().equals(CUSTOMER)
                    ? "a different customer, though the reviews look alike"
                    : answer.date() != null && other.date().compareTo(answer.date()) < 0
                    ? "outdated: the " + other.date() + " review of the same customer, superseded by the " + answer.date() + " one"
                    : "the " + other.date() + " review of the same customer, not the one compared against";
            support.passedOver(ledger, "memory", r, reason);
            note(profile.store(), profile.caseId(), PASSED_OVER, reference(r) + " — " + reason);
        }));
        PriorReview prior = new PriorReview(profile.caseId(), true, answer.date() == null ? "unknown date" : answer.date(),
                row == null ? "unknown" : row.writtenBy(), row == null ? null : row.id(),
                stored.map(KycReviewMemory::riskClass).orElse(answer.riskClass() == null || answer.riskClass().isBlank() ? null : answer.riskClass().toUpperCase()),
                stored.map(KycReviewMemory::address).orElse(answer.address()), stored.map(KycReviewMemory::ownership).orElse(answer.ownership()),
                stored.map(KycReviewMemory::beneficiaries).orElse(answer.beneficiaries() == null ? List.of() : answer.beneficiaries()),
                stored.map(KycReviewMemory::volumeBand).orElse(answer.volumeBand()));
        note(profile.store(), profile.caseId(), PRIOR_USED, "the " + prior.date() + " review of " + CUSTOMER + ", written by " + prior.writtenBy()
                + (prior.memoryId() == null ? "" : " (memory " + prior.memoryId() + ")"));
        ledger.step(PERSONA, "retrievePriorReview",
                "Recovered the " + prior.date() + " review written by " + prior.writtenBy() + ".", support.effort("kyc-review", "retrieve-prior-review"), StoryLedger.Kind.CONTEXT);
        return prior;
    }

    /** How the record refers to a retrieved review: by date, customer and memory id, never by its content. */
    private static String reference(StoreRecord r) {
        return KycReviewMemory.parse(r.text())
                .map(m -> m.customer().equals(CUSTOMER)
                        ? "the " + m.date() + " review of " + CUSTOMER + ", written by " + r.writtenBy() + " (memory " + r.id() + ")"
                        : "a review of another customer, written by " + r.writtenBy() + " (memory " + r.id() + ")")
                .orElse("a retrieved memory (" + r.id() + ")");
    }

    /** What each of the analyst's records answers, as the input of its turn. */
    private static final java.util.Map<String, String> ASKS = java.util.Map.of(
            PRIOR_USED, "Retrieve the previous KYC review of " + CUSTOMER,
            PASSED_OVER, "Set aside a retrieved review",
            SET_ASIDE, "Set aside a review from an earlier review cycle",
            MATERIAL, "Diff the current profile against the previous review",
            EVIDENCE, "Request evidence",
            SIGN_OFF, "Send the review for sign-off");

    /** Writes one of the analyst's records to the review's conversation, keyed by the review id. */
    private void note(ContextStore.Kind store, String reviewId, String event, String output) {
        support.recordEvent(support.store(store), reviewId, AGENT_ID, reviewId, event, null, ASKS.get(event), output);
    }

    private static PriorReviewAnswer priorReviewByRule(List<StoreRecord> rows) {
        // Exactly this customer, most recent review first (ISO dates sort lexically).
        return rows.stream().map(r -> KycReviewMemory.parse(r.text())).flatMap(Optional::stream)
                .filter(m -> m.customer().equals(CUSTOMER))
                .max(java.util.Comparator.comparing(KycReviewMemory::date))
                .map(m -> new PriorReviewAnswer(true, m.date(), m.riskClass(), m.address(), m.ownership(), m.beneficiaries(), m.volumeBand()))
                .orElse(new PriorReviewAnswer(false, null, null, null, null, List.of(), null));
    }

    @Action(description = "Diff the current profile against the previous review", pre = {"priorKnown"},
            post = {"changesKnown", "materialChange", "noMaterialChange"}, cost = 0.05, canRerun = false)
    public ChangeSet diffAgainstPrior(CurrentProfile profile, PriorReview prior, @Provided Ai ai) {
        StoryLedger ledger = support.ledger(profile.caseId());
        // Only the KYC standards among what the search returned: the judgement is made, and named, against those alone.
        List<StoreRecord> standard = support.store(profile.store())
                .searchKnowledge(profile.caseId(), AGENT_ID, "KYC periodic review material change standard")
                .stream().filter(r -> r.text().contains("KYC")).toList();
        support.found(ledger, "knowledge base", standard);
        // The diff is computed here, from fingerprints made with the same key: the model judges the changes, never sees the profile.
        Diff diff = diff(profile, prior);
        String context = "Changes since the previous review (" + prior.date() + "), from keyed fingerprints of the profile:\n"
                + "- address " + (diff.addressChanged() ? "changed" : "unchanged") + "\n"
                + "- ownership " + (diff.ownershipChanged() ? "changed" : "unchanged") + "\n"
                + "- beneficiaries: " + (diff.newBeneficiaries().isEmpty() ? "no new ones" : diff.newBeneficiaries().size() + " new (" + String.join(", ", diff.newBeneficiaries()) + ")")
                + ", " + diff.removedBeneficiaries() + " removed\n"
                + "- monthly volume " + (prior.volumeBand() == null ? "not on record before" : String.format("%.0f%% outside the previous band %s USD", diff.volumeOutsidePercent(), prior.volumeBand())) + "\n"
                + StorySupport.asContext("Review standards", standard);
        Optional<Double> threshold = volumeThreshold(standard);
        Materiality byRule = materialityByRule(diff, prior, threshold);
        // Settled by rule when address and ownership are unchanged and volume stayed in its band: the standard states
        // outright that a new beneficiary is material and unchanged details are not. The model weighs anything else.
        Materiality materiality = decisions.decideUnlessClear(profile.caseId(),
                PERSONA,
                "decisions/kyc-materiality",
                Map.of(),
                context,
                Materiality.class,
                () -> !diff.addressChanged() && !diff.ownershipChanged() && diff.volumeOutsidePercent() == 0 ? Optional.of(byRule) : Optional.empty(),
                "address and ownership unchanged, volume within its band; new beneficiaries are material by the standard",
                () -> byRule,
                // Every new beneficiary is material, and nothing unchanged is.
                m -> m.material() != null && diff.newBeneficiaries().stream().allMatch(b -> m.material().stream().anyMatch(x -> x.contains(b)))
                        && m.material().stream().noneMatch(x -> x.toLowerCase().contains("unchanged")),
                ai);
        List<String> material = materiality.material() == null ? List.of() : materiality.material().stream().map(x -> x.replace(';', ',')).toList();
        List<String> immaterial = materiality.notMaterial() == null ? List.of() : materiality.notMaterial().stream().map(x -> x.replace(';', ',')).toList();
        support.traceDecisions(support.store(profile.store()), profile.caseId(), AGENT_ID, profile.caseId(), PERSONA);
        // Which standard the judgement was made against, as named in the standards the analyst actually read.
        List<String> standardIds = standard.stream().map(r -> StorySupport.extract(List.of(r), StorySupport.POLICY_ID)).flatMap(Optional::stream).distinct().toList();
        note(profile.store(), profile.caseId(), MATERIAL, "material: " + (material.isEmpty() ? NONE : String.join("; ", material))
                + " | not material: " + (immaterial.isEmpty() ? NONE : String.join("; ", immaterial))
                + " | judged against: " + (standardIds.isEmpty() ? "no written standard in reach" : String.join(", ", standardIds)));
        ledger.step(PERSONA, "diffAgainstPrior",
                "Material: " + (material.isEmpty() ? NONE : String.join(", ", material)) + ". Not material: " + String.join(", ", immaterial) + ".",
                support.effort("kyc-review", "diff-against-prior"), StoryLedger.Kind.WORK);
        return new ChangeSet(profile.caseId(), List.copyOf(material), List.copyOf(immaterial));
    }

    /** What changed, from fingerprints: new beneficiaries by fingerprint, volume by how far it left the previous band. */
    record Diff(boolean addressChanged, boolean ownershipChanged, List<String> newBeneficiaries, int removedBeneficiaries,
                double volumeOutsidePercent) {}

    private Diff diff(CurrentProfile profile, PriorReview prior) {
        List<String> current = fingerprints.beneficiaries(CUSTOMER, profile.beneficiaries());
        return new Diff(!fingerprints.of(CUSTOMER, "address", profile.address()).equals(prior.address()),
                !fingerprints.of(CUSTOMER, "ownership", profile.ownership()).equals(prior.ownership()),
                current.stream().filter(b -> !prior.beneficiaries().contains(b)).toList(),
                (int) prior.beneficiaries().stream().filter(b -> !current.contains(b)).count(),
                prior.volumeBand() == null ? 0 : ProfileFingerprints.percentOutside(prior.volumeBand(), profile.monthlyVolume()));
    }

    private static Materiality materialityByRule(Diff diff, PriorReview prior, Optional<Double> threshold) {
        List<String> material = new ArrayList<>();
        List<String> immaterial = new ArrayList<>();
        (diff.addressChanged() ? material : immaterial).add("address " + (diff.addressChanged() ? "changed" : "unchanged"));
        (diff.ownershipChanged() ? material : immaterial).add("ownership " + (diff.ownershipChanged() ? "changed" : "unchanged"));
        diff.newBeneficiaries().forEach(b -> material.add("new beneficiary " + b));
        if (prior.volumeBand() != null) {
            if (threshold.isPresent()) {
                (diff.volumeOutsidePercent() > threshold.get() ? material : immaterial).add(String.format(
                        "monthly volume %.0f%% outside the previous band (threshold %.0f%%)", diff.volumeOutsidePercent(), threshold.get()));
            } else {
                immaterial.add(String.format("monthly volume %.0f%% outside the previous band (no written threshold in reach to judge it)", diff.volumeOutsidePercent()));
            }
        }
        return new Materiality(material, immaterial);
    }

    @Action(description = "Request evidence only for the material changes", pre = {"changesKnown", "materialChange"},
            post = {"evidenceReady"}, cost = 0.15, canRerun = false)
    public Evidence targetedEvidence(CurrentProfile profile, ChangeSet changes) {
        StoryLedger ledger = support.ledger(changes.caseId());
        List<String> requests = changes.material().stream()
                .map(c -> c.toLowerCase().contains("beneficiar") ? "source of funds and sanctions screening (" + c + ")" : "evidence for " + c)
                .toList();
        requests.forEach(r -> note(profile.store(), profile.caseId(), EVIDENCE, r));
        requests.forEach(r -> ledger.step(PERSONA, "targetedEvidence", "Requested " + r + ".", support.effort("kyc-review", "targeted-evidence"), StoryLedger.Kind.WORK));
        ledger.count("evidenceRequests", requests.size());
        return new Evidence(changes.caseId(), requests);
    }

    @Action(description = "Record that no new evidence is needed", pre = {"changesKnown", "noMaterialChange"},
            post = {"evidenceReady"}, cost = 0.01, canRerun = false)
    public Evidence noEvidenceNeeded(CurrentProfile profile, ChangeSet changes) {
        note(profile.store(), profile.caseId(), EVIDENCE, NONE + ": no material change");
        support.ledger(changes.caseId()).count("evidenceRequests", 0);
        support.ledger(changes.caseId()).step(PERSONA, "noEvidenceNeeded", "No material change: no new evidence requested.", support.effort("kyc-review", "no-evidence-needed"), StoryLedger.Kind.WORK);
        return new Evidence(changes.caseId(), List.of());
    }

    @Action(description = "Re-verify every KYC lane from scratch", pre = {"priorMissing"},
            post = {"evidenceReady"}, cost = 0.70, canRerun = false)
    public Evidence fullReview(CurrentProfile profile, PriorReview prior) {
        StoryLedger ledger = support.ledger(profile.caseId());
        List<String> lanes = List.of("identity documents", "proof of address", "ownership structure chart",
                "source of funds", "screening of all " + profile.beneficiaries().size() + " beneficiaries");
        for (String lane : lanes) {
            note(profile.store(), profile.caseId(), EVIDENCE, lane + FULL_REVIEW);
            // Everything but the new beneficiary was verified last quarter: this agent just cannot know it.
            ledger.step(PERSONA, "fullReview", "Requested " + lane + ".", support.effort("kyc-review", "full-review-item"), StoryLedger.Kind.REWORK);
        }
        ledger.count("evidenceRequests", lanes.size());
        return new Evidence(profile.caseId(), lanes);
    }

    @AchievesGoal(description = "The periodic review is decided and signed off")
    @Action(description = "Decide the risk class and send for human sign-off", pre = {"evidenceReady"},
            post = {"reviewSignedOff"}, cost = 0.05, canRerun = false)
    public ReviewDecision signOff(CurrentProfile profile, PriorReview prior, Evidence evidence) {
        StoryLedger ledger = support.ledger(profile.caseId());
        // The agent proposes keeping the previous risk class; with no previous review there is nothing to retain.
        String riskClass = prior.riskClass();
        String decision = (riskClass != null ? "Risk class " + riskClass + " retained" : "Risk class to be set by the reviewer (no previous risk class on record)")
                + ", pending " + evidence.requests().size() + " evidence request(s); sent for human sign-off.";
        ledger.step(PERSONA, "signOff", decision, support.effort("kyc-review", "sign-off"), StoryLedger.Kind.WORK);
        ledger.outcome(decision);
        note(profile.store(), profile.caseId(), SIGN_OFF, decision);
        // The result goes back to memory for the next review: the decision and the profile's fingerprints, not the profile.
        KycReviewMemory review = KycReviewMemory.of(fingerprints, CUSTOMER, java.time.LocalDate.now().toString(), profile.caseId(),
                profile.cycle(), riskClass, profile.address(), profile.ownership(), profile.beneficiaries(), profile.monthlyVolume());
        ContextStore store = support.store(profile.store());
        // A result that supersedes this agent's own review from the same cycle updates it in place (one current review per
        // cycle); otherwise it is a new memory. A review another desk wrote is never touched.
        boolean supersedes = prior.found() && AGENT_ID.equals(prior.writtenBy()) && prior.memoryId() != null;
        boolean written = supersedes
                ? store.update(profile.caseId(), AGENT_ID, prior.memoryId(), review.text())
                : store.remember(profile.caseId(), AGENT_ID, review.text()).isPresent();
        boolean fromPrior = ledger.steps().stream().noneMatch(st -> st.action().equals("fullReview"));
        // What the writer (an external model) sees is minimized the same way: counts, fingerprints and the decision.
        List<String> newBeneficiaries = fromPrior
                ? review.beneficiaries().stream().filter(b -> !prior.beneficiaries().contains(b)).toList() : List.of();
        List<String> facts = new ArrayList<>(List.of(
                "Customer " + CUSTOMER + ", profile from core banking (referred to by keyed fingerprint, not by its details)",
                "Beneficiaries: " + profile.beneficiaries().size() + (fromPrior ? ", new since the previous review: "
                        + (newBeneficiaries.isEmpty() ? NONE : String.join(", ", newBeneficiaries)) : ""),
                "Monthly volume band " + review.volumeBand() + " USD",
                "Evidence requested: " + (evidence.requests().isEmpty() ? NONE : String.join("; ", evidence.requests())),
                "Decision: " + decision));
        List<String> gaps = fromPrior ? List.of()
                : List.of("No previous review to compare against: what changed since the last review is unknown",
                "Which beneficiaries were previously approved is unknown");
        if (fromPrior) facts.add("Compared against the " + prior.date() + " review written by " + prior.writtenBy()
                + "; evidence requested only for the material changes listed above");
        if (written) facts.add("The result was written back to memory as a decision and keyed fingerprints, for the next review");
        ledger.handToWriter(new ResponseWriterAgent.WritingTask(profile.caseId(), PERSONA,
                "review rationale for the human sign-off reviewer",
                support.render("writer/kyc-rationale", Map.of()),
                facts, gaps, decision));
        return new ReviewDecision(profile.caseId(), decision);
    }

    @Condition(name = "profileLoaded")
    public boolean profileLoaded(CurrentProfile profile) {
        return profile != null;
    }

    @Condition(name = "priorKnown")
    public boolean priorKnown(PriorReview prior) {
        return prior != null && prior.found();
    }

    @Condition(name = "priorMissing")
    public boolean priorMissing(PriorReview prior) {
        return prior != null && !prior.found();
    }

    @Condition(name = "changesKnown")
    public boolean changesKnown(ChangeSet changes) {
        return changes != null;
    }

    @Condition(name = "materialChange")
    public boolean materialChange(ChangeSet changes) {
        return changes != null && !changes.material().isEmpty();
    }

    @Condition(name = "noMaterialChange")
    public boolean noMaterialChange(ChangeSet changes) {
        return changes != null && changes.material().isEmpty();
    }

    @Condition(name = "evidenceReady")
    public boolean evidenceReady(Evidence evidence) {
        return evidence != null;
    }

    @Condition(name = "reviewSignedOff")
    public boolean reviewSignedOff(ReviewDecision decision) {
        return decision != null;
    }
}
