package meko.agentic.goap.story.agents;

import com.embabel.agent.api.annotation.AchievesGoal;
import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.Agent;
import com.embabel.agent.api.annotation.Condition;
import com.embabel.agent.api.annotation.Provided;
import com.embabel.agent.api.common.Ai;
import com.embabel.agent.api.common.PlannerType;
import meko.agentic.goap.story.KnowledgeDocuments;
import meko.agentic.goap.story.StoryLedger;
import meko.agentic.goap.story.store.ContextStore;
import meko.agentic.goap.story.store.ContextStore.Turn;
import meko.agentic.goap.story.store.StoreRecord;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Story 3, follow-up: a model-risk reviewer checks a KYC review that an agent decided, given only the review id. With
 * Meko it reads the review's conversation (conversation_get by the review id): the previous review used and the ones
 * passed over, each gate decision with who made it, the material changes and the standard they were judged against,
 * every evidence request and the sign-off. Whatever the record lacks has to be requested, and waited for.
 */
@Agent(planner = PlannerType.GOAP, description = "Model-risk reviewer that checks an agent's KYC review against the review standard, from the review id alone")
public class KycReviewCheckAgent {
    public static final String AGENT_ID = "compliance:kyc-review-checker";
    static final String PERSONA = "KYC review checker";
    static final String ANALYST = KycReviewAgent.AGENT_ID;

    public record CheckRequest(String caseId, ContextStore.Kind store, String review) {}

    /** @param requested the parties asked for their records */
    public record ReviewRecord(String caseId, ContextStore.Kind store, String review, List<Turn> turns,
                               boolean workingRecord, List<String> requested, List<String> origins) {
        ReviewRecord withRecordsFrom(String party, String team, List<Turn> received) {
            List<Turn> all = new ArrayList<>(turns);
            all.addAll(received);
            List<String> asked = new ArrayList<>(requested);
            asked.add(party);
            List<String> from = new ArrayList<>(origins);
            received.forEach(t -> from.add("requested from " + team));
            return new ReviewRecord(caseId, store, review, List.copyOf(all), workingRecord || hasWorkingRecord(received), List.copyOf(asked), List.copyOf(from));
        }
    }

    /** @param source "knowledge base" when found in shared knowledge, else who sent it; null while not in reach */
    public record StandardText(String caseId, String standard, List<StoreRecord> text, String source) {}

    public record RecordReading(Boolean workingRecord) {}

    public record Check(Boolean comparedAgainstLatest, Boolean evidenceProportionate, Boolean signedOff, Boolean compliant,
                        List<String> findings) {}

    public record ReviewChecked(String caseId, Check check) {}

    public record CheckFinding(String caseId, String verdict) {}

    private final StorySupport support;
    private final DecisionService decisions;
    private final KnowledgeDocuments documents;

    public KycReviewCheckAgent(StorySupport support, DecisionService decisions, KnowledgeDocuments documents) {
        this.support = support;
        this.decisions = decisions;
        this.documents = documents;
    }

    @Action(description = "Retrieve the review's conversation and records by its id",
            post = {"recordRetrieved", "workingRecordKnown", "workingRecordUnknown"}, cost = 0.02, canRerun = false)
    public ReviewRecord retrieveRecord(CheckRequest request, @Provided Ai ai) {
        StoryLedger ledger = support.ledger(request.caseId());
        // Meko queues turns server-side: a review that has just finished may still be landing, so read until its sign-off is there.
        List<Turn> turns = support.readBack(support.store(request.store()), request.caseId(), AGENT_ID, request.review(), t -> !events(t, KycReviewAgent.SIGN_OFF).isEmpty());
        turns.forEach(t -> ledger.found("review conversation", StorySupport.describe(t), t.writtenBy()));
        // Conversation turns come back verbatim with their tags: what is on record is an exact check, not a judgement.
        RecordReading reading = decisions.decideByRule(request.caseId(), PERSONA, "decisions/kyc-check-record",
                Map.of("review", request.review()), StorySupport.asContext("Record for " + request.review(), turns, true),
                () -> new RecordReading(hasWorkingRecord(turns)), "the analyst's prior-review and sign-off records, by their tags");
        support.traceDecisions(support.store(request.store()), request.caseId(), AGENT_ID, request.review(), PERSONA);
        ReviewRecord record = new ReviewRecord(request.caseId(), request.store(), request.review(), turns,
                Boolean.TRUE.equals(reading.workingRecord()), List.of(),
                turns.stream().map(t -> StorySupport.conversationIn(request.store())).toList());
        ledger.step(PERSONA, "retrieveRecord",
                turns.isEmpty() ? "Nothing on record for " + request.review() + "."
                        : "Retrieved " + turns.size() + " turns for " + request.review() + ", written by "
                        + turns.stream().map(Turn::writtenBy).distinct().toList() + ". Working record: "
                        + (record.workingRecord() ? "on record" : "missing") + ".",
                support.effort("kyc-review-checker", "retrieve-record"), StoryLedger.Kind.CONTEXT);
        return record;
    }

    @Action(description = "Request the review's working papers from the analyst desk", pre = {"workingRecordUnknown"},
            post = {"workingRecordKnown"}, cost = 0.40, canRerun = false)
    public ReviewRecord requestWorkingPapers(ReviewRecord record) {
        StoryLedger ledger = support.ledger(record.caseId());
        List<Turn> received = support.systemsOfRecord().request(record.review(), ANALYST);
        ledger.say(PERSONA, "Requesting the working papers for " + record.review() + " from the analyst desk.");
        ledger.say("The analyst desk", received.isEmpty() ? "We have nothing on record for that review." : "Sent " + received.size() + " records.");
        ledger.step(PERSONA, "requestWorkingPapers", "Requested the working papers from the analyst desk: received " + received.size() + " records.",
                support.effort("kyc-review-checker", "request-working-papers"), StoryLedger.Kind.ASK);
        ledger.count("recordsRequested", 1);
        return record.withRecordsFrom(ANALYST, "the analyst desk", received);
    }

    @Action(description = "Retrieve the text of the standard the review was judged against", pre = {"workingRecordKnown"},
            post = {"standardKnown", "standardUnavailable"}, cost = 0.02, canRerun = false)
    public StandardText retrieveStandard(ReviewRecord record) {
        StoryLedger ledger = support.ledger(record.caseId());
        Optional<String> named = standardOnRecord(record.turns());
        List<StoreRecord> found = support.store(record.store())
                .searchKnowledge(record.caseId(), AGENT_ID, named.orElse("") + " KYC periodic re-review standard material change")
                .stream().filter(r -> named.map(n -> r.text().contains(n)).orElse(false)).toList();
        support.found(ledger, "knowledge base", found);
        ledger.step(PERSONA, "retrieveStandard",
                named.isEmpty() ? "The record names no standard." : found.isEmpty() ? "No text of " + named.get() + " in reach."
                        : "Found the text of " + named.get() + " in shared knowledge.",
                support.effort("kyc-review-checker", "retrieve-standard"), StoryLedger.Kind.CONTEXT);
        return new StandardText(record.caseId(), named.orElse(null), found, found.isEmpty() ? null : "knowledge base");
    }

    @Action(description = "Request the standard from its owner", pre = {"standardUnavailable"},
            post = {"standardKnown"}, cost = 0.30, canRerun = false)
    public StandardText requestStandard(ReviewRecord record, StandardText missing) {
        StoryLedger ledger = support.ledger(record.caseId());
        // By the id the record names; with none named, the owner is asked for the periodic re-review standard.
        String asked = missing.standard() != null ? missing.standard() : REREVIEW_STANDARD;
        Optional<KnowledgeDocuments.Document> doc = documents.all().stream()
                .filter(d -> missing.standard() != null ? d.key().equals(missing.standard()) : titleMatches(d.title(), REREVIEW_STANDARD))
                .findFirst();
        String owner = doc.map(d -> StorySupport.extract(List.of(new StoreRecord("", d.markdown(), "", "", null)), "Owner: ([^.\\n]+)").orElse("its owner"))
                .orElse("the standards team");
        List<StoreRecord> text = doc.map(d -> d.chunks().stream().map(c -> new StoreRecord(d.key(), c, owner, "", d.filename())).toList()).orElse(List.of());
        ledger.say(PERSONA, "Requesting " + asked + " from " + owner + ".");
        ledger.say(owner, text.isEmpty() ? "We can't tell which standard you mean." : "Attached: " + doc.get().key() + ".");
        ledger.step(PERSONA, "requestStandard", "Requested " + asked + " from " + owner + ": " + (text.isEmpty() ? "not provided." : "received " + doc.get().key() + "."),
                support.effort("kyc-review-checker", "request-standard"), StoryLedger.Kind.ASK);
        ledger.count("recordsRequested", 1);
        return new StandardText(record.caseId(), doc.map(KnowledgeDocuments.Document::key).orElse(missing.standard()), text, owner);
    }

    @Action(description = "Check the review against the standard", pre = {"workingRecordKnown", "standardKnown"},
            post = {"reviewChecked"}, cost = 0.10, canRerun = false)
    public ReviewChecked checkAgainstStandard(ReviewRecord record, StandardText standard, @Provided Ai ai) {
        StoryLedger ledger = support.ledger(record.caseId());
        String context = StorySupport.asContext("Record for " + record.review(), record.turns(), true) + StorySupport.asContext("Standard", standard.text());
        Check byRule = checkByRule(record.turns(), standard);
        // Settled by rule when there is no standard text, or when the analyst's records (prior used, material changes,
        // evidence, sign-off) are all on record: every field is then computed from them. The model weighs the rest.
        boolean complete = java.util.stream.Stream.of(KycReviewAgent.PRIOR_USED, KycReviewAgent.EVIDENCE, KycReviewAgent.SIGN_OFF)
                .allMatch(e -> !events(record.turns(), e).isEmpty());
        Check check = decisions.decideUnlessClear(record.caseId(), PERSONA, "decisions/kyc-check-standard",
                Map.of("review", record.review()), context, Check.class,
                () -> standard.text().isEmpty() || complete ? Optional.of(byRule) : Optional.empty(),
                standard.text().isEmpty() ? "no standard text to check against" : "the analyst's records, checked against the standard",
                () -> byRule,
                c -> java.util.Objects.equals(c.signedOff(), byRule.signedOff()),
                ai);
        support.traceDecisions(support.store(record.store()), record.caseId(), AGENT_ID, record.review(), PERSONA);
        ledger.count("decisionTraces", (int) record.turns().stream()
                .filter(t -> t.decisionGate().isPresent() && !t.taggedBy().map(AGENT_ID::equals).orElse(false)).count());
        ledger.step(PERSONA, "checkAgainstStandard", verdict(check, standard) + " " + String.join(" ", findings(check)),
                support.effort("kyc-review-checker", "check-against-standard"), StoryLedger.Kind.WORK);
        return new ReviewChecked(record.caseId(), check);
    }

    @AchievesGoal(description = "The review-check finding is filed")
    @Action(description = "File the review-check finding", pre = {"reviewChecked"}, post = {"findingFiled"}, cost = 0.05, canRerun = false)
    public CheckFinding fileFinding(ReviewRecord record, StandardText standard, ReviewChecked checked) {
        StoryLedger ledger = support.ledger(record.caseId());
        Check c = checked.check();
        // What was checked, as the record states it (the sign-off), then the verdict.
        String decided = events(record.turns(), KycReviewAgent.SIGN_OFF).stream().map(Turn::output).reduce((a, b) -> b).map(o -> o + " ").orElse("");
        String verdict = record.review() + ": " + decided + verdict(c, standard);
        ledger.count(c.compliant() == null ? "findingsUnverified" : c.compliant() ? "findingsCompliant" : "findingsNotCompliant", 1);
        ledger.step(PERSONA, "fileFinding", "Filed the finding: " + verdict, support.effort("kyc-review-checker", "file-finding"), StoryLedger.Kind.WORK);
        ledger.outcome(verdict);
        List<String> facts = new ArrayList<>(List.of(
                "Review " + record.review(),
                "Record from " + (record.requested().isEmpty() ? "the review's conversation" : "the review's conversation and records requested from " + record.requested())
                        + ", written by " + record.turns().stream().map(Turn::writtenBy).distinct().toList(),
                "Standard: " + (standard.standard() == null ? "not identified" : standard.standard())
                        + (standard.source() == null ? "" : " (text from " + standard.source() + ")"),
                "Compared against the latest review of this customer: " + yesNo(c.comparedAgainstLatest()),
                "Evidence proportionate to the material changes: " + yesNo(c.evidenceProportionate()),
                "Sent for sign-off: " + yesNo(c.signedOff()),
                "Decision traces on record: " + StorySupport.decisionTraces(record.turns(), ANALYST).size() + " from the KYC analyst agent",
                "Verdict: " + verdict(c, standard)));
        facts.addAll(findings(c));
        List<String> gaps = new ArrayList<>();
        if (!record.workingRecord()) gaps.add("The analyst's working record is not on record");
        if (standard.text().isEmpty()) gaps.add("The text of the standard was not available to check against");
        ledger.auditTrail(auditTrail(record, standard, c, verdict));
        ledger.handToWriter(new ResponseWriterAgent.WritingTask(record.caseId(), PERSONA,
                "review-check finding for the model-risk file",
                support.render("writer/kyc-check-finding", Map.of()),
                facts, List.copyOf(gaps), verdict + "."));
        support.fileFinding(support.store(record.store()), record.caseId(), AGENT_ID, record.review(), "Model-risk check of", verdict(c, standard), findings(c));
        return new CheckFinding(record.caseId(), verdict);
    }

    /** The record entry by entry, with what the check flags on each, then the check's own decisions and its verdict. */
    private List<StoryLedger.AuditEntry> auditTrail(ReviewRecord record, StandardText standard, Check c, String verdict) {
        List<Turn> turns = record.turns();
        List<StoryLedger.AuditEntry> trail = new ArrayList<>(support.auditTrail(record.caseId(), turns, record.origins(),
                t -> t.event().isEmpty(),
                i -> {
                    Turn t = turns.get(i);
                    if (t.isEvent(ANALYST, KycReviewAgent.PRIOR_USED) && Boolean.FALSE.equals(c.comparedAgainstLatest()))
                        return "A later review of this customer was passed over";
                    if (t.isEvent(ANALYST, KycReviewAgent.MATERIAL) && Boolean.FALSE.equals(c.evidenceProportionate()))
                        return "The evidence requested does not match the material changes";
                    if (t.isEvent(ANALYST, KycReviewAgent.EVIDENCE) && t.output().endsWith(KycReviewAgent.FULL_REVIEW))
                        return "Full re-review: no previous review on record to compare against";
                    return StorySupport.decisionIssue(t);
                }, PERSONA, AGENT_ID));
        List<String> issues = new ArrayList<>();
        if (StorySupport.decisionTraces(turns, ANALYST).isEmpty()) issues.add("No decision traces on record: how the review's decisions were made cannot be checked");
        if (!Boolean.TRUE.equals(c.signedOff())) issues.add("Not sent for sign-off");
        trail.add(new StoryLedger.AuditEntry(trail.size() + 1, AGENT_ID, "finding", "Verdict on " + record.review(),
                "Checked against " + (standard.standard() == null ? "no standard" : standard.standard())
                        + (standard.source() == null ? "" : ", text from " + standard.source()), verdict, String.join("; ", issues), "this check"));
        return trail;
    }

    // ------------------------------------------------------------------ rules over the record

    /** What the checker asks a standard's owner for when the record names none. */
    static final String REREVIEW_STANDARD = "periodic re-review standard";

    static boolean titleMatches(String title, String request) {
        String lower = title.toLowerCase();
        return Arrays.stream(request.toLowerCase().split("\\s+")).allMatch(lower::contains);
    }

    static boolean hasWorkingRecord(List<Turn> turns) {
        return !events(turns, KycReviewAgent.PRIOR_USED).isEmpty() && !events(turns, KycReviewAgent.SIGN_OFF).isEmpty();
    }

    static Optional<String> standardOnRecord(List<Turn> turns) {
        return events(turns, KycReviewAgent.MATERIAL).stream()
                .map(t -> after(t.output(), "judged against: "))
                .map(s -> StorySupport.extract(List.of(new StoreRecord("", s, "", "", null)), StorySupport.POLICY_ID))
                .flatMap(Optional::stream).findFirst();
    }

    private static Check checkByRule(List<Turn> turns, StandardText standard) {
        List<String> findings = new ArrayList<>();
        Optional<Turn> used = events(turns, KycReviewAgent.PRIOR_USED).stream().findFirst();
        boolean priorOnRecord = used.isPresent() && !used.get().output().startsWith(KycReviewAgent.NONE);
        Boolean comparedAgainstLatest = null;
        if (priorOnRecord) {
            String usedText = used.get().output();
            String customer = extract(usedText, "(CUST-\\d+)").orElse("");
            String usedDate = extract(usedText, "(\\d{4}-\\d{2}-\\d{2})").orElse("");
            // A later review of the same customer among those passed over would mean the comparison was not with the latest.
            List<String> laterSkipped = events(turns, KycReviewAgent.PASSED_OVER).stream().map(Turn::output)
                    .filter(o -> o.contains(customer + " ") || o.contains(customer + ","))
                    .map(o -> extract(o, "(\\d{4}-\\d{2}-\\d{2})").orElse(""))
                    .filter(d -> d.compareTo(usedDate) > 0).toList();
            comparedAgainstLatest = laterSkipped.isEmpty();
            findings.add("Compared against the " + after(usedText, "the ").split(" \\| ")[0] + "."
                    + (laterSkipped.isEmpty() ? "" : " A later review of the same customer (" + String.join(", ", laterSkipped) + ") was passed over."));
            events(turns, KycReviewAgent.PASSED_OVER).forEach(t -> findings.add("Passed over: " + after(t.output(), "— ")));
        } else {
            findings.add("No previous review was on record, so a full re-review was the standard's required path.");
        }
        List<String> material = events(turns, KycReviewAgent.MATERIAL).stream().findFirst()
                .map(t -> after(t.output(), "material: ").split(" \\| ")[0])
                .filter(m -> !m.equals(KycReviewAgent.NONE)).map(m -> List.of(m.split("; "))).orElse(List.of());
        List<Turn> evidence = events(turns, KycReviewAgent.EVIDENCE);
        long fullReview = evidence.stream().filter(t -> t.output().endsWith(KycReviewAgent.FULL_REVIEW)).count();
        long targeted = evidence.stream().filter(t -> !t.output().endsWith(KycReviewAgent.FULL_REVIEW) && !t.output().startsWith(KycReviewAgent.NONE)).count();
        Boolean proportionate = priorOnRecord ? targeted == material.size() : fullReview > 0;
        findings.add(priorOnRecord
                ? material.size() + " material change" + (material.size() == 1 ? "" : "s") + " (" + (material.isEmpty() ? "none" : String.join("; ", material)) + "), "
                + targeted + " evidence request" + (targeted == 1 ? "" : "s") + "."
                : fullReview + " full re-review request" + (fullReview == 1 ? "" : "s") + ".");
        // The materiality judgement itself, re-checked against the rules the standard's own text states.
        List<String> notMaterial = events(turns, KycReviewAgent.MATERIAL).stream().findFirst()
                .map(t -> after(t.output(), "not material: ").split(" \\| ")[0])
                .filter(m -> !m.equals(KycReviewAgent.NONE)).map(m -> List.of(m.split("; "))).orElse(List.of());
        Optional<KycReviewAgent.KycRules> rules = KycReviewAgent.rules(standard.text());
        List<String> misjudged = rules.map(r -> notMaterial.stream().filter(n -> {
            java.util.regex.Matcher volume = java.util.regex.Pattern.compile("monthly volume (\\d+)% outside").matcher(n);
            boolean overThreshold = volume.find() && r.volumeThreshold().map(t -> Double.parseDouble(volume.group(1)) > t).orElse(false);
            return overThreshold || (r.beneficiaryMaterial() && n.startsWith("new beneficiary"));
        }).toList()).orElse(List.of());
        if (!misjudged.isEmpty()) findings.add("Judged not material, but material by the standard's text: " + String.join("; ", misjudged) + ".");
        boolean signedOff = !events(turns, KycReviewAgent.SIGN_OFF).isEmpty();
        List<Turn> traces = StorySupport.decisionTraces(turns, ANALYST);
        findings.add(traces.size() + " decision trace" + (traces.size() == 1 ? "" : "s") + " from the KYC analyst agent are on record.");
        findings.addAll(StorySupport.traceFindings(traces));
        Boolean compliant = standard.text().isEmpty() ? null
                : signedOff && Boolean.TRUE.equals(proportionate) && misjudged.isEmpty() && (!priorOnRecord || Boolean.TRUE.equals(comparedAgainstLatest));
        return new Check(comparedAgainstLatest, proportionate, signedOff, compliant, findings);
    }

    static List<Turn> events(List<Turn> turns, String event) {
        return turns.stream().filter(t -> t.isEvent(ANALYST, event)).toList();
    }

    private static Optional<String> extract(String text, String regex) {
        return StorySupport.extract(List.of(new StoreRecord("", text, "", "", null)), regex);
    }

    private static String after(String text, String marker) {
        int at = text.indexOf(marker);
        return at < 0 ? text : text.substring(at + marker.length());
    }

    private static String verdict(Check c, StandardText standard) {
        String name = standard.standard() == null ? "the review standard" : standard.standard();
        return c.compliant() == null ? "Compliance with " + name + " could not be verified."
                : c.compliant() ? "Reviewed in line with " + name + "." : "Not reviewed in line with " + name + ".";
    }

    private static List<String> findings(Check c) {
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

    @Condition(name = "workingRecordKnown")
    public boolean workingRecordKnown(ReviewRecord record) {
        return record != null && (record.workingRecord() || record.requested().contains(ANALYST));
    }

    @Condition(name = "workingRecordUnknown")
    public boolean workingRecordUnknown(ReviewRecord record) {
        return record != null && !workingRecordKnown(record);
    }

    @Condition(name = "standardKnown")
    public boolean standardKnown(StandardText standard) {
        return standard != null && (!standard.text().isEmpty() || standard.source() != null);
    }

    @Condition(name = "standardUnavailable")
    public boolean standardUnavailable(StandardText standard) {
        return standard != null && !standardKnown(standard);
    }

    @Condition(name = "reviewChecked")
    public boolean reviewChecked(ReviewChecked checked) {
        return checked != null;
    }

    @Condition(name = "findingFiled")
    public boolean findingFiled(CheckFinding finding) {
        return finding != null;
    }
}
