package meko.agentic.goap.story.agents;

import com.embabel.agent.api.annotation.AchievesGoal;
import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.Agent;
import com.embabel.agent.api.annotation.Condition;
import com.embabel.agent.api.annotation.Provided;
import com.embabel.agent.api.common.Ai;
import com.embabel.agent.api.common.PlannerType;
import meko.agentic.goap.story.KnowledgeDocuments;
import meko.agentic.goap.story.LendingConditions;
import meko.agentic.goap.story.LendingThresholds;
import meko.agentic.goap.story.StoryLedger;
import meko.agentic.goap.story.store.ContextStore;
import meko.agentic.goap.story.store.ContextStore.Turn;
import meko.agentic.goap.story.store.StoreRecord;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Loan story, follow-up: a fair-lending reviewer checks the credit call later, given only the application id. Was the
 * call made under the policy that governs the application, on verified figures, consistently with the policy's
 * thresholds, and with the reasons on record? With Meko it reads the application's conversation (conversation_get by
 * the application id): the origination system's checks, the underwriter's re-asks and repeated checks, its decisions
 * and the credit call, each with who wrote it. Whatever the record lacks, the planner has to request from the party
 * that holds it, and wait.
 */
@Agent(planner = PlannerType.GOAP, description = "Fair-lending reviewer that checks a credit call on a loan application, from the application id alone")
public class FairLendingReviewAgent {
    public static final String AGENT_ID = "compliance:fair-lending-reviewer";
    static final String PERSONA = "Fair-lending reviewer";
    static final String VENDOR = LoanIntakeAgent.AGENT_ID;
    static final String UNDERWRITER = UnderwriterAgent.AGENT_ID;
    static final List<String> VERIFICATIONS = List.of(LoanIntakeAgent.INCOME, LoanIntakeAgent.ASSETS, LoanIntakeAgent.EMPLOYMENT, LoanIntakeAgent.CREDIT);

    public record ReviewRequest(String caseId, ContextStore.Kind store, String applicationId) {}

    /**
     * @param turns     the record so far: the application's conversation, plus whatever other parties handed over
     * @param requested the parties asked for their records
     */
    public record ReviewRecord(String caseId, ContextStore.Kind store, String applicationId, List<Turn> turns,
                               boolean intake, boolean creditCall, List<String> requested, List<String> origins) {
        ReviewRecord withRecordsFrom(String party, String team, List<Turn> received) {
            List<Turn> all = new ArrayList<>(turns);
            all.addAll(received);
            List<String> asked = new ArrayList<>(requested);
            asked.add(party);
            List<String> from = new ArrayList<>(origins);
            received.forEach(t -> from.add("requested from " + team));
            return new ReviewRecord(caseId, store, applicationId, List.copyOf(all), intake || hasIntake(received),
                    creditCall || hasCreditCall(received), List.copyOf(asked), List.copyOf(from));
        }
    }

    /** @param source "knowledge base" when found in shared knowledge, else who provided the text */
    public record PolicyText(String caseId, String policy, List<StoreRecord> text, String source) {}

    /** The model's reading of what the record contains. */
    public record RecordReading(Boolean intake, Boolean creditCall) {}

    /** The model's review judgement. */
    /**
     * @param thresholdsRecorded the credit call's record states the thresholds applied and the factors, as the policy requires
     * @param onlyPolicyFactors  the call rests only on factors the policy names (no other basis)
     * @param declineReasons     a decline records its principal reasons (adverse action); null when the call is not a decline
     * @param conditionsDocumented every condition the policy sets on this file was cleared on documented evidence before the call
     * @param borrowerReexplained  conditions that made the borrower explain again what they had explained at origination
     */
    public record Consistency(String policyApplied, Boolean verifiedBeforeCall, Boolean callMatchesThresholds,
                              Integer hardInquiries, Integer borrowerReexplained, Boolean conditionsDocumented, Boolean thresholdsRecorded,
                              Boolean onlyPolicyFactors, Boolean declineReasons, Boolean consistent, List<String> findings) {}

    public record ReviewChecked(String caseId, Consistency consistency) {}

    public record ReviewFinding(String caseId, String verdict) {}

    private final StorySupport support;
    private final DecisionService decisions;
    private final KnowledgeDocuments documents;

    public FairLendingReviewAgent(StorySupport support, DecisionService decisions, KnowledgeDocuments documents) {
        this.support = support;
        this.decisions = decisions;
        this.documents = documents;
    }

    @Action(description = "Retrieve the application's conversation and records by its id",
            post = {"recordRetrieved", "intakeKnown", "intakeUnknown", "creditCallKnown", "creditCallUnknown"},
            cost = 0.02, canRerun = false)
    public ReviewRecord retrieveRecord(ReviewRequest request, @Provided Ai ai) {
        StoryLedger ledger = support.ledger(request.caseId());
        // Meko queues turns server-side: a case that has just finished may still be landing, so read until its credit call is there.
        List<Turn> turns = support.readBack(support.store(request.store()), request.caseId(), AGENT_ID, request.applicationId(), FairLendingReviewAgent::hasCreditCall);
        turns.forEach(t -> ledger.found("application conversation", StorySupport.describe(t), t.writtenBy()));
        // Conversation turns come back verbatim with their tags: what is on record is an exact check, not a judgement.
        RecordReading reading = decisions.decideByRule(request.caseId(), PERSONA, "decisions/lending-review-record",
                Map.of("application", request.applicationId()), StorySupport.asContext("Record for " + request.applicationId(), turns, true),
                () -> new RecordReading(hasIntake(turns), hasCreditCall(turns)), "the referral and the credit call, by their tags");
        // The review's own decisions go on the same record, so the review can itself be checked.
        support.traceDecisions(support.store(request.store()), request.caseId(), AGENT_ID, request.applicationId(), PERSONA);
        ReviewRecord record = new ReviewRecord(request.caseId(), request.store(), request.applicationId(), turns,
                Boolean.TRUE.equals(reading.intake()), Boolean.TRUE.equals(reading.creditCall()), List.of(),
                turns.stream().map(t -> StorySupport.conversationIn(request.store())).toList());
        ledger.step(PERSONA, "retrieveRecord",
                turns.isEmpty() ? "Nothing on record for " + request.applicationId() + "."
                        : "Retrieved " + turns.size() + " turns for " + request.applicationId() + ", written by "
                        + turns.stream().map(Turn::writtenBy).distinct().toList() + ". Origination checks: "
                        + (record.intake() ? "on record" : "missing") + "; credit call: " + (record.creditCall() ? "on record" : "missing") + ".",
                support.effort("fair-lending-reviewer", "retrieve-record"), StoryLedger.Kind.CONTEXT);
        return record;
    }

    @Action(description = "Request the application file from the loan-origination vendor", pre = {"intakeUnknown"},
            post = {"intakeKnown"}, cost = 0.40, canRerun = false)
    public ReviewRecord requestOriginationFile(ReviewRecord record) {
        return request(record, VENDOR, "the loan-origination vendor", "the application file", "requestOriginationFile", "request-origination-file");
    }

    @Action(description = "Request the underwriting notes from the underwriting team", pre = {"creditCallUnknown"},
            post = {"creditCallKnown"}, cost = 0.30, canRerun = false)
    public ReviewRecord requestUnderwritingNotes(ReviewRecord record) {
        return request(record, UNDERWRITER, "the underwriting team", "the underwriting notes", "requestUnderwritingNotes", "request-underwriting-notes");
    }

    /** Asks one party for its own records on the application; they hand over what their system holds. */
    private ReviewRecord request(ReviewRecord record, String party, String team, String what, String action, String estimate) {
        StoryLedger ledger = support.ledger(record.caseId());
        List<Turn> received = support.systemsOfRecord().request(record.applicationId(), party);
        ledger.say(PERSONA, "Requesting " + what + " for " + record.applicationId() + " from " + team + ".");
        ledger.say(team.substring(0, 1).toUpperCase() + team.substring(1),
                received.isEmpty() ? "We have nothing on record for that application." : "Sent " + received.size() + " records.");
        ledger.step(PERSONA, action, "Requested " + what + " from " + team + ": received " + received.size() + " records.",
                support.effort("fair-lending-reviewer", estimate), StoryLedger.Kind.ASK);
        ledger.count("recordsRequested", 1);
        return record.withRecordsFrom(party, team, received);
    }

    @Action(description = "Retrieve the text of the underwriting policy applied", pre = {"intakeKnown", "creditCallKnown"},
            post = {"policyKnown", "policyUnavailable"}, cost = 0.02, canRerun = false)
    public PolicyText retrievePolicy(ReviewRecord record) {
        StoryLedger ledger = support.ledger(record.caseId());
        Optional<String> applied = policyOnRecord(record.turns());
        List<StoreRecord> policies = support.store(record.store())
                .searchKnowledge(record.caseId(), AGENT_ID, applied.orElse("") + " unsecured personal loan underwriting policy credit score DTI")
                .stream().filter(r -> StorySupport.extract(List.of(r), StorySupport.POLICY_ID).filter(id -> id.startsWith("LEND-")).isPresent()).toList();
        List<StoreRecord> used = applied.map(p -> policies.stream()
                .filter(r -> StorySupport.extract(List.of(r), StorySupport.POLICY_ID).filter(p::equals).isPresent()).toList()).orElse(List.of());
        support.found(ledger, "knowledge base", used);
        policies.stream().filter(r -> !used.contains(r)).forEach(r -> support.passedOver(ledger, "knowledge base", r,
                StorySupport.extract(List.of(r), StorySupport.POLICY_ID).orElse("another policy") + " is not the policy applied to this application"));
        ledger.step(PERSONA, "retrievePolicy",
                applied.isEmpty() ? "The record names no policy." : used.isEmpty() ? "No text of " + applied.get() + " in reach."
                        : "Found the text of " + applied.get() + " in shared knowledge.",
                support.effort("fair-lending-reviewer", "retrieve-policy"), StoryLedger.Kind.CONTEXT);
        return new PolicyText(record.caseId(), applied.orElse(null), used, used.isEmpty() ? null : "knowledge base");
    }

    @Action(description = "Request the policy text from credit risk, the policy owner", pre = {"policyUnavailable"},
            post = {"policyKnown"}, cost = 0.30, canRerun = false)
    public PolicyText requestPolicy(ReviewRecord record, PolicyText missing) {
        StoryLedger ledger = support.ledger(record.caseId());
        // Credit risk owns the policy documents and sends the one the record names.
        List<StoreRecord> text = missing.policy() == null ? List.of() : documents.all().stream()
                .filter(d -> d.key().equals(missing.policy()))
                .flatMap(d -> d.chunks().stream().map(c -> new StoreRecord(d.key(), c, UnderwriterAgent.CREDIT_RISK, "", d.filename())))
                .toList();
        ledger.say(PERSONA, "Requesting the text of " + (missing.policy() == null ? "the applicable underwriting policy" : missing.policy()) + " from credit risk.");
        ledger.say("Credit risk", text.isEmpty() ? "We can't tell which policy you mean." : "Attached: " + missing.policy() + ".");
        ledger.step(PERSONA, "requestPolicy", "Requested the policy text from credit risk: " + (text.isEmpty() ? "not provided." : "received."),
                support.effort("fair-lending-reviewer", "request-policy"), StoryLedger.Kind.ASK);
        ledger.count("recordsRequested", 1);
        return new PolicyText(record.caseId(), missing.policy(), text, UnderwriterAgent.CREDIT_RISK);
    }

    @Action(description = "Check the credit call against the policy", pre = {"intakeKnown", "creditCallKnown", "policyKnown"},
            post = {"reviewChecked"}, cost = 0.10, canRerun = false)
    public ReviewChecked checkAgainstPolicy(ReviewRecord record, PolicyText policy, @Provided Ai ai) {
        StoryLedger ledger = support.ledger(record.caseId());
        String context = StorySupport.asContext("Record for " + record.applicationId(), record.turns(), true) + StorySupport.asContext("Policy", policy.text());
        Consistency byRule = consistencyByRule(record.turns(), policy);
        String policyText = String.join("\n\n", policy.text().stream().map(StoreRecord::text).toList());
        // Settled by rule when there is no policy text, or when the policy's thresholds and conditions parse and the record
        // holds the figures and the credit call: every field is then computed from the record. The model weighs the rest.
        boolean computable = LendingThresholds.parse(policyText).isPresent() && LendingConditions.parse(policyText).isPresent()
                && hasCreditCall(record.turns()) && record.turns().stream().anyMatch(t -> t.isEvent(UNDERWRITER, UnderwriterAgent.AFFORDABILITY));
        Consistency consistency = decisions.decideUnlessClear(record.caseId(), PERSONA, "decisions/lending-review-compliance",
                Map.of("application", record.applicationId()), context, Consistency.class,
                () -> policy.text().isEmpty() || computable ? Optional.of(byRule) : Optional.empty(),
                policy.text().isEmpty() ? "no policy text to check against" : "the policy's thresholds and conditions, applied to the figures on record",
                () -> byRule,
                // Counts the record settles must agree with it.
                c -> java.util.Objects.equals(c.hardInquiries(), byRule.hardInquiries()) && java.util.Objects.equals(c.borrowerReexplained(), byRule.borrowerReexplained()),
                ai);
        support.traceDecisions(support.store(record.store()), record.caseId(), AGENT_ID, record.applicationId(), PERSONA);
        // How many of the other agents' decisions the record shows how they were made, not just their outcome.
        ledger.count("decisionTraces", (int) record.turns().stream()
                .filter(t -> t.decisionGate().isPresent() && !t.taggedBy().map(AGENT_ID::equals).orElse(false)).count());
        ledger.step(PERSONA, "checkAgainstPolicy", verdict(consistency) + " " + String.join(" ", findings(consistency)),
                support.effort("fair-lending-reviewer", "check-against-policy"), StoryLedger.Kind.WORK);
        return new ReviewChecked(record.caseId(), consistency);
    }

    @AchievesGoal(description = "The fair-lending review finding is filed")
    @Action(description = "File the review finding", pre = {"reviewChecked"}, post = {"findingFiled"}, cost = 0.05, canRerun = false)
    public ReviewFinding fileFinding(ReviewRecord record, PolicyText policy, ReviewChecked checked) {
        StoryLedger ledger = support.ledger(record.caseId());
        Consistency c = checked.consistency();
        // What was reviewed, as the record states it (the credit call, without its record-keeping), then the verdict.
        String decided = record.turns().stream().filter(t -> t.isEvent(UNDERWRITER, UnderwriterAgent.CREDIT_CALL)).map(Turn::output)
                .reduce((a, b) -> b).map(o -> o.contains(UnderwriterAgent.THRESHOLDS_APPLIED) ? o.substring(0, o.indexOf(UnderwriterAgent.THRESHOLDS_APPLIED)).trim() : o)
                .map(o -> o.replaceFirst("^" + java.util.regex.Pattern.quote(record.applicationId()) + ":\\s*", "") + " ").orElse("");
        String verdict = record.applicationId() + ": " + decided + verdict(c);
        ledger.count(c.consistent() == null ? "findingsUnverified" : c.consistent() ? "findingsCompliant" : "findingsNotCompliant", 1);
        ledger.step(PERSONA, "fileFinding", "Filed the finding: " + verdict, support.effort("fair-lending-reviewer", "file-finding"), StoryLedger.Kind.WORK);
        ledger.outcome(ledger.outcome() == null ? verdict : ledger.outcome() + " " + verdict);
        List<String> sources = record.turns().stream().map(Turn::writtenBy).distinct().toList();
        List<String> facts = new ArrayList<>(List.of(
                "Application " + record.applicationId(),
                "Record from " + (record.requested().isEmpty() ? "the application's conversation" : "the application's conversation and records requested from " + record.requested())
                        + ", written by " + sources,
                "Policy applied: " + (c.policyApplied() == null ? "not on record" : c.policyApplied())
                        + (policy.source() == null ? "" : " (policy text from " + policy.source() + ")"),
                "Figures verified before the credit call: " + yesNo(c.verifiedBeforeCall()),
                "Credit call matches the policy's thresholds: " + yesNo(c.callMatchesThresholds()),
                "Thresholds and factors recorded with the credit call: " + yesNo(c.thresholdsRecorded()),
                "Credit call rests only on factors the policy names: " + yesNo(c.onlyPolicyFactors()),
                "Principal reasons recorded for a decline: " + (c.declineReasons() == null ? "not applicable" : yesNo(c.declineReasons())),
                "Hard inquiries on the borrower's credit file: " + (c.hardInquiries() == null ? "unknown" : c.hardInquiries()),
                "Policy conditions cleared on documented evidence before the credit call: " + yesNo(c.conditionsDocumented()),
                "Borrower asked to explain again what they explained at origination: " + (c.borrowerReexplained() == null ? "unknown" : c.borrowerReexplained() + " times"),
                "Decision traces on record: " + StorySupport.decisionTraces(record.turns(), UNDERWRITER).size() + " from the underwriter",
                "Verdict: " + verdict(c)));
        facts.addAll(findings(c));
        record.turns().stream().filter(t -> t.isEvent(UNDERWRITER, UnderwriterAgent.CREDIT_CALL)).forEach(t -> facts.add("Credit call on record: " + t.output()));
        List<String> gaps = new ArrayList<>();
        if (!record.intake()) gaps.add("The origination system's checks are not on record");
        if (!record.creditCall()) gaps.add("The credit call is not on record");
        if (policy.text().isEmpty()) gaps.add("The text of the policy applied was not available to check against");
        ledger.auditTrail(auditTrail(record, policy, c, verdict));
        ledger.handToWriter(new ResponseWriterAgent.WritingTask(record.caseId(), PERSONA, "fair-lending review finding on " + record.applicationId(),
                support.render("writer/lending-review-finding", Map.of()), facts, List.copyOf(gaps), verdict + "."));
        support.fileFinding(support.store(record.store()), record.caseId(), AGENT_ID, record.applicationId(), "Fair-lending review of", verdict(c), findings(c));
        return new ReviewFinding(record.caseId(), verdict);
    }

    /** The record entry by entry, with what the review flags on each, then the review's own decisions and its verdict. */
    private List<StoryLedger.AuditEntry> auditTrail(ReviewRecord record, PolicyText policy, Consistency c, String verdict) {
        List<Turn> turns = record.turns();
        List<StoryLedger.AuditEntry> trail = new ArrayList<>(support.auditTrail(record.caseId(), turns, record.origins(),
                t -> t.event().isEmpty() || t.isEvent(UNDERWRITER, UnderwriterAgent.REASKED),
                i -> {
                    Turn t = turns.get(i);
                    if (t.isEvent(UNDERWRITER, UnderwriterAgent.REASKED)) return "The borrower had to explain this again: " + t.why();
                    if (t.isEvent(UNDERWRITER, UnderwriterAgent.CONDITION_CLEARED) && !documented(t)) return "Condition cleared with no evidence on record";
                    if (t.isEvent(UNDERWRITER, UnderwriterAgent.POLICY)) {
                        if (t.output().startsWith("none:")) return "No written policy covers this application";
                        if (policy.text().isEmpty()) return "The policy text was not available to verify this choice";
                    }
                    if (t.isEvent(UNDERWRITER, UnderwriterAgent.CREDIT_CALL)) {
                        if (Boolean.FALSE.equals(c.callMatchesThresholds())) return "The credit call does not follow the policy's thresholds";
                        if (Boolean.FALSE.equals(c.thresholdsRecorded())) return "The thresholds applied are not recorded, as the policy requires";
                        if (Boolean.FALSE.equals(c.onlyPolicyFactors())) return "The call rests on a factor the policy does not name";
                        if (Boolean.FALSE.equals(c.declineReasons())) return "A decline with no principal reasons on record";
                    }
                    return StorySupport.decisionIssue(t);
                }, PERSONA, AGENT_ID));
        List<String> issues = new ArrayList<>();
        if (StorySupport.decisionTraces(turns, UNDERWRITER).isEmpty()) issues.add("No decision traces on record: how the credit call was reached cannot be checked");
        if (!record.intake()) issues.add("The origination system's checks are not on record");
        trail.add(new StoryLedger.AuditEntry(trail.size() + 1, AGENT_ID, "finding", "Verdict on " + record.applicationId(),
                "Checked against " + (c.policyApplied() == null ? "no policy" : c.policyApplied())
                        + (policy.source() == null ? "" : ", text from " + policy.source()), verdict, String.join("; ", issues), "this review"));
        return trail;
    }

    // ------------------------------------------------------------------ rules over the record

    static boolean hasIntake(List<Turn> turns) {
        return turns.stream().anyMatch(t -> t.isEvent(VENDOR, LoanIntakeAgent.REFERRAL));
    }

    static boolean hasCreditCall(List<Turn> turns) {
        return turns.stream().anyMatch(t -> t.isEvent(UNDERWRITER, UnderwriterAgent.CREDIT_CALL));
    }

    static Optional<String> policyOnRecord(List<Turn> turns) {
        return turns.stream()
                .filter(t -> t.isEvent(UNDERWRITER, UnderwriterAgent.POLICY) || t.isEvent(UNDERWRITER, UnderwriterAgent.CREDIT_CALL))
                .map(t -> StorySupport.extract(List.of(new StoreRecord("", t.output(), t.writtenBy(), "", null)), StorySupport.POLICY_ID))
                .flatMap(Optional::stream).findFirst();
    }

    private static final Pattern WORKING = Pattern.compile("DTI ([\\d.]+)% .*score (\\d+); reserves (\\d+) months");
    /** "credit score 712, DTI 37.4%, reserves 4 months": the factor names before each figure. */
    private static final Pattern FACTOR = Pattern.compile("([A-Za-z][A-Za-z -]*?) [\\d.]+");
    private static final Pattern WINDOW = Pattern.compile("within (\\d+) days", Pattern.CASE_INSENSITIVE);

    private static Consistency consistencyByRule(List<Turn> turns, PolicyText policy) {
        int call = indexOf(turns, UNDERWRITER, UnderwriterAgent.CREDIT_CALL);
        // The origination system runs its checks before it refers the application, whenever its records were obtained.
        Boolean verifiedFirst = call < 0 ? null : VERIFICATIONS.stream().allMatch(v -> turns.stream().anyMatch(t -> t.isEvent(VENDOR, v)));
        int inquiries = (int) turns.stream().filter(t -> t.isEvent(VENDOR, LoanIntakeAgent.CREDIT) || t.isEvent(UNDERWRITER, LoanIntakeAgent.CREDIT)).count();
        // Conditions re-issued for something the borrower had already explained, with evidence, at origination.
        List<String> explainedAtOrigination = turns.stream().filter(t -> t.isEvent(VENDOR, LoanIntakeAgent.EXPLAINED)).map(t -> t.output().toLowerCase()).toList();
        List<Turn> reasks = turns.stream().filter(t -> t.isEvent(UNDERWRITER, UnderwriterAgent.REASKED)).toList();
        int reexplained = (int) reasks.stream().filter(t -> ITEMS.stream().anyMatch(i -> t.why().toLowerCase().contains(i)
                && explainedAtOrigination.stream().anyMatch(e -> e.contains(i)))).count();
        List<Turn> traces = StorySupport.decisionTraces(turns, UNDERWRITER);
        String applied = policyOnRecord(turns).orElse(null);
        // The thresholds as the policy text the reviewer has states them, applied to the figures on record.
        Optional<LendingThresholds> thresholds = LendingThresholds.parse(String.join("\n\n", policy.text().stream().map(StoreRecord::text).toList()));
        Optional<Matcher> figures = turns.stream().filter(t -> t.isEvent(UNDERWRITER, UnderwriterAgent.AFFORDABILITY))
                .map(t -> WORKING.matcher(t.output())).filter(Matcher::find).findFirst();
        Optional<String> callOutput = turns.stream().filter(t -> t.isEvent(UNDERWRITER, UnderwriterAgent.CREDIT_CALL)).map(Turn::output).findFirst();
        Boolean matches = thresholds.isEmpty() || figures.isEmpty() || callOutput.isEmpty() ? null
                : callOutput.get().contains(": " + thresholds.get().apply(Integer.parseInt(figures.get().group(2)),
                        Double.parseDouble(figures.get().group(1)), Integer.parseInt(figures.get().group(3))) + " under");
        String policyText = String.join("\n\n", policy.text().stream().map(StoreRecord::text).toList());
        // The policy's record-keeping: the thresholds applied and the factors, stated with the call.
        Boolean recorded = callOutput.map(o -> after(o, UnderwriterAgent.THRESHOLDS_APPLIED).length() > 1 && o.contains(UnderwriterAgent.FACTORS)).orElse(null);
        // Fair lending: the call rests only on factors the policy names.
        List<String> factorNames = callOutput.filter(o -> o.contains(UnderwriterAgent.FACTORS))
                .map(o -> FACTOR.matcher(after(o, UnderwriterAgent.FACTORS)).results().map(m -> m.group(1).replaceFirst("^[, ]+", "").trim()).toList())
                .orElse(List.of());
        Boolean onlyPolicyFactors = policyText.isBlank() || factorNames.isEmpty() ? null
                : factorNames.stream().allMatch(f -> policyText.toLowerCase().contains(f.toLowerCase()));
        // Fair lending: a decline records its principal reasons.
        Boolean declineReasons = callOutput.filter(o -> o.contains(": decline under"))
                .map(o -> after(after(o, ": decline under"), ". ").replace(UnderwriterAgent.THRESHOLDS_APPLIED, "").trim().length() > 1)
                .orElse(null);
        // The policy's conditions, applied to the figures the origination system recorded: each must be cleared on evidence before the call.
        List<String> required = requiredConditions(turns, policyText);
        List<Turn> cleared = turns.stream().filter(t -> t.isEvent(UNDERWRITER, UnderwriterAgent.CONDITION_CLEARED)).toList();
        Boolean conditionsDocumented = policyText.isBlank() || call < 0 || required == null ? null
                : required.stream().allMatch(item -> cleared.stream().anyMatch(t -> t.why().equalsIgnoreCase(item) && documented(t)
                && turns.indexOf(t) < call));
        List<String> findings = new ArrayList<>();
        if (verifiedFirst != null) findings.add(verifiedFirst ? "Income, assets, employment and credit were verified before the credit call."
                : "The credit call was made before every figure was verified.");
        if (matches != null) findings.add(matches ? "The credit call follows the policy's thresholds for the figures on record: "
                + thresholds.get().explain(Integer.parseInt(figures.get().group(2)), Double.parseDouble(figures.get().group(1)), Integer.parseInt(figures.get().group(3))) + "."
                : "The credit call does not follow the policy's thresholds for the figures on record.");
        if (recorded != null) findings.add(recorded ? "The thresholds applied and the factors are recorded with the credit call."
                : "The thresholds applied are not recorded with the credit call, as the policy requires.");
        if (onlyPolicyFactors != null) findings.add(onlyPolicyFactors ? "The credit call rests only on factors the policy names: " + String.join(", ", factorNames) + "."
                : "The credit call rests on a factor the policy does not name.");
        if (declineReasons != null) findings.add(declineReasons ? "The decline records its principal reasons." : "The decline records no principal reasons.");
        WINDOW.matcher(policyText).results().findFirst().ifPresent(m -> findings.add("The policy's " + m.group(1)
                + "-day window for the employment verification cannot be checked: the record carries no dates."));
        findings.add(inquiries <= 1 ? "One hard inquiry on the borrower's credit file." : inquiries + " hard inquiries on the borrower's credit file.");
        if (conditionsDocumented != null) {
            long fromOrigination = cleared.stream().filter(t -> t.output().startsWith(UnderwriterAgent.FROM_ORIGINATION)).count();
            findings.add(required.isEmpty() ? "The policy's conditions require nothing to be explained for this file."
                    : "The policy's conditions applied to this file (" + String.join(", ", required) + ") "
                    + (conditionsDocumented ? "were each cleared on documented evidence before the credit call: " + fromOrigination + " from the borrower's explanation at origination, "
                    + (cleared.size() - fromOrigination) + " on the borrower's answer to a condition."
                    : "were not all cleared on documented evidence before the credit call."));
        }
        evidenceBases(cleared).forEach(b -> findings.add("Evidence accepted: " + UnderwriterAgent.ruling(b) + "."));
        turns.stream().filter(t -> t.isEvent(UNDERWRITER, UnderwriterAgent.RULING)).forEach(t -> findings.add(
                "A ruling was requested from credit risk for this application, on evidence the policy does not list: " + t.output() + "."));
        findings.add(reasks.isEmpty() ? "The borrower was not asked to explain anything again after the referral."
                : reasks.size() + " condition" + (reasks.size() == 1 ? " was" : "s were") + " sent back to the borrower"
                + (reexplained == 0 ? "." : "; " + reexplained + " asked again for what the borrower had already explained, with evidence, at origination."));
        findings.add(traces.isEmpty() ? "No decision traces from the underwriter are on record."
                : traces.size() + " decision trace" + (traces.size() == 1 ? "" : "s") + " from the underwriter are on record.");
        findings.addAll(StorySupport.traceFindings(traces));
        Boolean consistent = policy.text().isEmpty() || verifiedFirst == null || matches == null ? null
                : verifiedFirst && matches && Boolean.TRUE.equals(recorded) && !Boolean.FALSE.equals(conditionsDocumented)
                && !Boolean.FALSE.equals(onlyPolicyFactors) && !Boolean.FALSE.equals(declineReasons);
        return new Consistency(applied, verifiedFirst, matches, inquiries, reexplained, conditionsDocumented, recorded, onlyPolicyFactors, declineReasons, consistent, findings);
    }

    /** The items a policy's conditions can flag, as the underwriter names them. */
    private static final List<String> ITEMS = List.of("deposit", "employment gap");
    private static final Pattern INCOME = Pattern.compile("Income ([\\d,]+) USD a month");
    private static final Pattern DEPOSIT = Pattern.compile(LoanIntakeAgent.LARGEST_DEPOSIT + "([\\d,]+) USD");
    private static final Pattern GAP = Pattern.compile(LoanIntakeAgent.LONGEST_GAP + "(\\d+) days");

    /**
     * What the policy text's conditions require explained for this file, from the figures the origination system
     * recorded: null when the policy states no conditions or the figures are not on record.
     */
    static List<String> requiredConditions(List<Turn> turns, String policyText) {
        Optional<LendingConditions> conditions = LendingConditions.parse(policyText);
        Optional<Integer> income = figure(turns, LoanIntakeAgent.INCOME, INCOME), deposit = figure(turns, LoanIntakeAgent.ASSETS, DEPOSIT),
                gap = figure(turns, LoanIntakeAgent.EMPLOYMENT, GAP);
        if (conditions.isEmpty() || income.isEmpty() || deposit.isEmpty() || gap.isEmpty()) return null;
        List<String> required = new ArrayList<>();
        if (conditions.get().depositNeedsSource(deposit.get(), income.get())) required.add("deposit");
        if (conditions.get().gapNeedsExplanation(gap.get())) required.add("employment gap");
        return required;
    }

    private static Optional<Integer> figure(List<Turn> turns, String event, Pattern pattern) {
        return turns.stream().filter(t -> t.isEvent(VENDOR, event)).map(t -> pattern.matcher(t.output())).filter(Matcher::find)
                .map(m -> Integer.parseInt(m.group(1).replace(",", ""))).findFirst();
    }

    /** A cleared condition is documented when it records the evidence it was cleared on, and why that evidence is accepted. */
    static boolean documented(Turn cleared) {
        String o = cleared.output();
        return ((o.startsWith(UnderwriterAgent.FROM_ORIGINATION) && o.contains("evidence received")) || o.startsWith(UnderwriterAgent.FROM_CONDITION))
                && o.contains(UnderwriterAgent.EVIDENCE_ACCEPTED);
    }

    /** Why each cleared condition's evidence was accepted: listed in the policy, a ruling on request, or a ruling found as precedent. */
    static List<String> evidenceBases(List<Turn> cleared) {
        return cleared.stream().map(t -> after(t.output(), UnderwriterAgent.EVIDENCE_ACCEPTED)).filter(b -> !b.isEmpty()).toList();
    }

    private static String after(String text, String marker) {
        int at = text.indexOf(marker);
        return at < 0 ? "" : text.substring(at + marker.length());
    }

    private static int indexOf(List<Turn> turns, String agentId, String event) {
        for (int i = 0; i < turns.size(); i++) if (turns.get(i).isEvent(agentId, event)) return i;
        return -1;
    }

    private static String verdict(Consistency c) {
        String policy = c.policyApplied() == null ? "the applicable policy" : c.policyApplied();
        return c.consistent() == null ? "Consistency with " + policy + " could not be verified."
                : c.consistent() ? "Credit call made consistently with " + policy + "." : "Credit call not consistent with " + policy + ".";
    }

    private static List<String> findings(Consistency c) {
        return c.findings() == null ? List.of() : c.findings();
    }

    private static String yesNo(Boolean value) {
        return value == null ? "unknown" : value ? "yes" : "no";
    }

    // ------------------------------------------------------------------ conditions

    @Condition(name = "recordRetrieved")
    public boolean recordRetrieved(ReviewRecord record) {
        return record != null;
    }

    @Condition(name = "intakeKnown")
    public boolean intakeKnown(ReviewRecord record) {
        return record != null && (record.intake() || record.requested().contains(VENDOR));
    }

    @Condition(name = "intakeUnknown")
    public boolean intakeUnknown(ReviewRecord record) {
        return record != null && !intakeKnown(record);
    }

    @Condition(name = "creditCallKnown")
    public boolean creditCallKnown(ReviewRecord record) {
        return record != null && (record.creditCall() || record.requested().contains(UNDERWRITER));
    }

    @Condition(name = "creditCallUnknown")
    public boolean creditCallUnknown(ReviewRecord record) {
        return record != null && !creditCallKnown(record);
    }

    @Condition(name = "policyKnown")
    public boolean policyKnown(PolicyText policy) {
        return policy != null && (!policy.text().isEmpty() || policy.source() != null);
    }

    @Condition(name = "policyUnavailable")
    public boolean policyUnavailable(PolicyText policy) {
        return policy != null && !policyKnown(policy);
    }

    @Condition(name = "reviewChecked")
    public boolean reviewChecked(ReviewChecked checked) {
        return checked != null;
    }

    @Condition(name = "findingFiled")
    public boolean findingFiled(ReviewFinding finding) {
        return finding != null;
    }
}
