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
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Story 1, follow-up: a compliance auditor reviews how the payment dispute was handled, weeks later, given only the
 * transaction id. With Meko it reads the transaction's conversation (conversation_get by the transaction id): the
 * customer's account, the handoff, every re-ask, the settlement check, the policy and the resolution, each with who
 * wrote it. Whatever the record lacks, the planner has to request from the party that holds it, and wait.
 */
@Agent(planner = PlannerType.GOAP, description = "Compliance auditor that reviews how a payment dispute was handled, from the transaction id alone")
public class DisputeAuditorAgent {
    public static final String AGENT_ID = "compliance:dispute-auditor";
    static final String PERSONA = "Compliance auditor";
    static final String VENDOR = SupportFrontlineAgent.AGENT_ID;
    static final String SPECIALIST = PaymentsSpecialistAgent.AGENT_ID;

    public record AuditRequest(String caseId, ContextStore.Kind store, String transaction) {}

    /**
     * @param turns     the record so far: the transaction's conversation, plus whatever other parties handed over
     * @param requested the parties asked for their records
     */
    public record AuditRecord(String caseId, ContextStore.Kind store, String transaction, List<Turn> turns,
                              boolean customerAccount, boolean resolution, List<String> requested, List<String> origins) {
        AuditRecord withRecordsFrom(String party, String team, List<Turn> received) {
            List<Turn> all = new ArrayList<>(turns);
            all.addAll(received);
            List<String> asked = new ArrayList<>(requested);
            asked.add(party);
            List<String> from = new ArrayList<>(origins);
            received.forEach(t -> from.add("requested from " + team));
            return new AuditRecord(caseId, store, transaction, List.copyOf(all),
                    customerAccount || hasAccount(received), resolution || hasResolution(received), List.copyOf(asked), List.copyOf(from));
        }
    }

    /** @param source "knowledge base" when found in shared knowledge, else who provided the text */
    public record PolicyText(String caseId, String policy, List<StoreRecord> text, String source) {}

    /** The model's reading of what the record contains. */
    public record RecordReading(Boolean customerAccount, Boolean resolution) {}

    /** The model's audit judgement. */
    public record Compliance(String policyApplied, Boolean settlementVerifiedBeforeRefund, Integer customerReasked,
                             Boolean compliant, List<String> findings) {}

    public record AuditChecked(String caseId, Compliance compliance) {}

    public record AuditFinding(String caseId, String verdict) {}

    private final StorySupport support;
    private final DecisionService decisions;
    private final KnowledgeDocuments documents;

    public DisputeAuditorAgent(StorySupport support, DecisionService decisions, KnowledgeDocuments documents) {
        this.support = support;
        this.decisions = decisions;
        this.documents = documents;
    }

    @Action(description = "Retrieve the transaction's conversation and records by its id",
            post = {"recordRetrieved", "customerAccountKnown", "customerAccountUnknown", "resolutionKnown", "resolutionUnknown"},
            cost = 0.02, canRerun = false)
    public AuditRecord retrieveRecord(AuditRequest request, @Provided Ai ai) {
        StoryLedger ledger = support.ledger(request.caseId());
        List<Turn> turns = support.store(request.store()).transcript(request.caseId(), AGENT_ID, request.transaction());
        turns.forEach(t -> ledger.found("transaction conversation", StorySupport.describe(t), t.writtenBy()));
        RecordReading reading = decisions.decide(request.caseId(), PERSONA, "decisions/audit-record",
                Map.of("transaction", request.transaction()), StorySupport.asContext("Record for " + request.transaction(), turns, true),
                RecordReading.class, () -> new RecordReading(hasAccount(turns), hasResolution(turns)), ai);
        // The audit's own decisions go on the same record, so the audit can itself be audited.
        support.traceDecisions(support.store(request.store()), request.caseId(), AGENT_ID, request.transaction(), PERSONA);
        AuditRecord record = new AuditRecord(request.caseId(), request.store(), request.transaction(), turns,
                Boolean.TRUE.equals(reading.customerAccount()), Boolean.TRUE.equals(reading.resolution()), List.of(),
                turns.stream().map(t -> StorySupport.conversationIn(request.store())).toList());
        ledger.step(PERSONA, "retrieveRecord",
                turns.isEmpty() ? "Nothing on record for " + request.transaction() + "."
                        : "Retrieved " + turns.size() + " turns for " + request.transaction() + ", written by "
                        + turns.stream().map(Turn::writtenBy).distinct().toList() + ". Customer's account: "
                        + (record.customerAccount() ? "on record" : "missing") + "; resolution: " + (record.resolution() ? "on record" : "missing") + ".",
                support.effort("compliance-auditor", "retrieve-record"), StoryLedger.Kind.CONTEXT);
        return record;
    }

    @Action(description = "Request the chat log from the vendor's support team", pre = {"customerAccountUnknown"},
            post = {"customerAccountKnown"}, cost = 0.40, canRerun = false)
    public AuditRecord requestVendorTranscript(AuditRecord record) {
        return request(record, VENDOR, "the vendor's support team", "the chat log", "requestVendorTranscript", "request-vendor-transcript");
    }

    @Action(description = "Request the case notes from the payments specialist team", pre = {"resolutionUnknown"},
            post = {"resolutionKnown"}, cost = 0.30, canRerun = false)
    public AuditRecord requestCaseNotes(AuditRecord record) {
        return request(record, SPECIALIST, "the payments specialist team", "the case notes", "requestCaseNotes", "request-case-notes");
    }

    /** Asks one party for its own records on the transaction; they hand over what their system holds. */
    private AuditRecord request(AuditRecord record, String party, String team, String what, String action, String estimate) {
        StoryLedger ledger = support.ledger(record.caseId());
        List<Turn> received = support.systemsOfRecord().request(record.transaction(), party);
        ledger.say(PERSONA, "Requesting " + what + " for " + record.transaction() + " from " + team + ".");
        ledger.say(team.substring(0, 1).toUpperCase() + team.substring(1),
                received.isEmpty() ? "We have nothing on record for that transaction." : "Sent " + received.size() + " records.");
        ledger.step(PERSONA, action, "Requested " + what + " from " + team + ": received " + received.size() + " records.",
                support.effort("compliance-auditor", estimate), StoryLedger.Kind.ASK);
        ledger.count("recordsRequested", 1);
        return record.withRecordsFrom(party, team, received);
    }

    @Action(description = "Retrieve the text of the dispute policy applied", pre = {"customerAccountKnown", "resolutionKnown"},
            post = {"policyKnown", "policyUnavailable"}, cost = 0.02, canRerun = false)
    public PolicyText retrievePolicy(AuditRecord record) {
        StoryLedger ledger = support.ledger(record.caseId());
        Optional<String> applied = policyOnRecord(record.turns());
        List<StoreRecord> policies = support.store(record.store())
                .searchKnowledge(record.caseId(), AGENT_ID, applied.orElse("") + " card dispute policy failed capture after successful authorization")
                .stream().filter(r -> r.text().contains("policy")).toList();
        List<StoreRecord> used = applied.map(p -> policies.stream().filter(r -> r.text().contains(p)).toList()).orElse(List.of());
        support.found(ledger, "knowledge base", used);
        policies.stream().filter(r -> !used.contains(r)).forEach(r -> support.passedOver(ledger, "knowledge base", r,
                StorySupport.extract(List.of(r), StorySupport.POLICY_ID).orElse("another policy") + " is not the policy applied to this case"));
        ledger.step(PERSONA, "retrievePolicy",
                applied.isEmpty() ? "The record names no policy." : used.isEmpty() ? "No text of " + applied.get() + " in reach."
                        : "Found the text of " + applied.get() + " in shared knowledge.",
                support.effort("compliance-auditor", "retrieve-policy"), StoryLedger.Kind.CONTEXT);
        return new PolicyText(record.caseId(), applied.orElse(null), used, used.isEmpty() ? null : "knowledge base");
    }

    @Action(description = "Request the policy text from payments operations, the policy owner", pre = {"policyUnavailable"},
            post = {"policyKnown"}, cost = 0.30, canRerun = false)
    public PolicyText requestPolicy(AuditRecord record, PolicyText missing) {
        StoryLedger ledger = support.ledger(record.caseId());
        // Payments operations owns the policy documents and sends the one the record names.
        List<StoreRecord> text = missing.policy() == null ? List.of() : documents.all().stream()
                .filter(d -> d.key().equals(missing.policy()))
                .flatMap(d -> d.chunks().stream().map(c -> new StoreRecord(d.key(), c, PaymentsSpecialistAgent.PAYMENTS_OPS, "", d.filename())))
                .toList();
        ledger.say(PERSONA, "Requesting the text of " + (missing.policy() == null ? "the applicable dispute policy" : missing.policy()) + " from payments operations.");
        ledger.say("Payments operations", text.isEmpty() ? "We can't tell which policy you mean." : "Attached: " + missing.policy() + ".");
        ledger.step(PERSONA, "requestPolicy", "Requested the policy text from payments operations: " + (text.isEmpty() ? "not provided." : "received."),
                support.effort("compliance-auditor", "request-policy"), StoryLedger.Kind.ASK);
        ledger.count("recordsRequested", 1);
        return new PolicyText(record.caseId(), missing.policy(), text, PaymentsSpecialistAgent.PAYMENTS_OPS);
    }

    @Action(description = "Check the handling against the policy", pre = {"customerAccountKnown", "resolutionKnown", "policyKnown"},
            post = {"auditChecked"}, cost = 0.10, canRerun = false)
    public AuditChecked checkAgainstPolicy(AuditRecord record, PolicyText policy, @Provided Ai ai) {
        StoryLedger ledger = support.ledger(record.caseId());
        String context = StorySupport.asContext("Record for " + record.transaction(), record.turns(), true) + StorySupport.asContext("Policy", policy.text());
        Compliance compliance = decisions.decide(record.caseId(), PERSONA, "decisions/audit-compliance",
                Map.of("transaction", record.transaction()), context, Compliance.class,
                () -> complianceByRule(record.turns(), policy), ai);
        support.traceDecisions(support.store(record.store()), record.caseId(), AGENT_ID, record.transaction(), PERSONA);
        // How many of the other agents' decisions the record shows how they were made, not just their outcome.
        ledger.count("decisionTraces", (int) record.turns().stream()
                .filter(t -> t.decisionGate().isPresent() && !t.taggedBy().map(AGENT_ID::equals).orElse(false)).count());
        ledger.step(PERSONA, "checkAgainstPolicy", verdict(compliance) + " " + String.join(" ", findings(compliance)),
                support.effort("compliance-auditor", "check-against-policy"), StoryLedger.Kind.WORK);
        return new AuditChecked(record.caseId(), compliance);
    }

    @AchievesGoal(description = "The audit finding is filed")
    @Action(description = "File the audit finding", pre = {"auditChecked"}, post = {"findingFiled"}, cost = 0.05, canRerun = false)
    public AuditFinding fileFinding(AuditRecord record, PolicyText policy, AuditChecked checked) {
        StoryLedger ledger = support.ledger(record.caseId());
        Compliance c = checked.compliance();
        String verdict = record.transaction() + ": " + verdict(c);
        ledger.step(PERSONA, "fileFinding", "Filed the finding: " + verdict, support.effort("compliance-auditor", "file-finding"), StoryLedger.Kind.WORK);
        ledger.outcome(verdict);
        List<String> sources = record.turns().stream().map(Turn::writtenBy).distinct().toList();
        List<String> facts = new ArrayList<>(List.of(
                "Transaction " + record.transaction(),
                "Record from " + (record.requested().isEmpty() ? "the transaction's conversation" : "the transaction's conversation and records requested from " + record.requested())
                        + ", written by " + sources,
                "Policy applied: " + (c.policyApplied() == null ? "not on record" : c.policyApplied())
                        + (policy.source() == null ? "" : " (policy text from " + policy.source() + ")"),
                "Settlement verified before the refund: " + yesNo(c.settlementVerifiedBeforeRefund()),
                "Customer asked to repeat information after the handoff: " + (c.customerReasked() == null ? "unknown" : c.customerReasked() + " times"),
                "Decision traces on record: " + StorySupport.decisionTraces(record.turns(), SPECIALIST).size() + " from the payments specialist",
                "Verdict: " + verdict(c)));
        facts.addAll(findings(c));
        record.turns().stream().filter(t -> t.isEvent(SPECIALIST, PaymentsSpecialistAgent.RESOLUTION))
                .forEach(t -> facts.add("Resolution on record: " + t.output()));
        List<String> gaps = new ArrayList<>();
        if (!record.customerAccount()) gaps.add("The customer's own account of the problem is not on record");
        if (!record.resolution()) gaps.add("How the case was resolved is not on record");
        if (policy.text().isEmpty()) gaps.add("The text of the policy applied was not available to check against");
        ledger.auditTrail(auditTrail(record, policy, c, verdict));
        ledger.handToWriter(new ResponseWriterAgent.WritingTask(record.caseId(), PERSONA,
                "audit finding for the compliance file",
                support.render("writer/audit-finding", Map.of()),
                facts, List.copyOf(gaps), verdict + "."));
        return new AuditFinding(record.caseId(), verdict);
    }

    /** The record entry by entry, with what the audit flags on each, then the audit's own decisions and its verdict. */
    private List<StoryLedger.AuditEntry> auditTrail(AuditRecord record, PolicyText policy, Compliance c, String verdict) {
        List<Turn> turns = record.turns();
        int settlement = indexOf(turns, PaymentsSpecialistAgent.SETTLEMENT);
        List<StoryLedger.AuditEntry> trail = new ArrayList<>(support.auditTrail(record.caseId(), turns, record.origins(),
                t -> t.event().isEmpty() || t.isEvent(SPECIALIST, PaymentsSpecialistAgent.REASKED),
                i -> {
                    Turn t = turns.get(i);
                    if (t.isEvent(SPECIALIST, PaymentsSpecialistAgent.REASKED)) return "The customer had to repeat information: " + t.why();
                    if (t.isEvent(SPECIALIST, PaymentsSpecialistAgent.RESOLUTION)) {
                        if (settlement < 0) return "Refund approved with no settlement check on record";
                        if (settlement > i) return "Refund approved before the settlement was checked";
                    }
                    if (t.isEvent(SPECIALIST, PaymentsSpecialistAgent.POLICY)) {
                        if (t.output().contains(PaymentsSpecialistAgent.VERBAL)) return "Policy confirmed verbally: no written basis on record";
                        if (policy.text().isEmpty()) return "The policy text was not available to verify this choice";
                    }
                    return StorySupport.decisionIssue(t);
                }, PERSONA, AGENT_ID));
        List<String> issues = new ArrayList<>();
        if (StorySupport.decisionTraces(turns, SPECIALIST).isEmpty()) issues.add("No decision traces on record: how the decisions were made cannot be checked");
        if (!record.customerAccount()) issues.add("The customer's own account is not on record");
        trail.add(new StoryLedger.AuditEntry(trail.size() + 1, AGENT_ID, "finding", "Verdict on " + record.transaction(),
                "Checked against " + (c.policyApplied() == null ? "no policy" : c.policyApplied())
                        + (policy.source() == null ? "" : ", text from " + policy.source()), verdict, String.join("; ", issues), "this audit"));
        return trail;
    }

    // ------------------------------------------------------------------ rules over the record

    static boolean hasAccount(List<Turn> turns) {
        return turns.stream().anyMatch(t -> t.writtenBy().equals(VENDOR) || t.isEvent(VENDOR, SupportFrontlineAgent.HANDOFF));
    }

    static boolean hasResolution(List<Turn> turns) {
        return turns.stream().anyMatch(t -> t.isEvent(SPECIALIST, PaymentsSpecialistAgent.RESOLUTION));
    }

    static Optional<String> policyOnRecord(List<Turn> turns) {
        return turns.stream()
                .filter(t -> t.isEvent(SPECIALIST, PaymentsSpecialistAgent.POLICY) || t.isEvent(SPECIALIST, PaymentsSpecialistAgent.RESOLUTION))
                .map(t -> StorySupport.extract(List.of(new StoreRecord("", t.output(), t.writtenBy(), "", null)), StorySupport.POLICY_ID))
                .flatMap(Optional::stream).findFirst();
    }

    private static Compliance complianceByRule(List<Turn> turns, PolicyText policy) {
        int settlement = indexOf(turns, PaymentsSpecialistAgent.SETTLEMENT);
        int resolution = indexOf(turns, PaymentsSpecialistAgent.RESOLUTION);
        Boolean settlementFirst = settlement < 0 || resolution < 0 ? null : settlement < resolution;
        boolean noSettledCapture = settlement >= 0 && turns.get(settlement).output().contains(PaymentsSpecialistAgent.SettlementCheck.NO_SETTLED_CAPTURE);
        int reasked = (int) turns.stream().filter(t -> t.isEvent(SPECIALIST, PaymentsSpecialistAgent.REASKED)).count();
        List<Turn> traces = StorySupport.decisionTraces(turns, SPECIALIST);
        String applied = policyOnRecord(turns).orElse(null);
        List<String> findings = new ArrayList<>();
        if (settlementFirst != null) findings.add(settlementFirst ? "The settlement was checked before the refund was approved."
                : "The refund was approved before the settlement was checked.");
        if (settlement >= 0) findings.add("Settlement check on record: " + turns.get(settlement).output() + ".");
        findings.add(reasked == 0 ? "The customer was not asked to repeat anything after the handoff."
                : "The customer was asked to repeat information " + reasked + " times after the handoff.");
        findings.add(traces.isEmpty() ? "No decision traces from the payments specialist are on record."
                : traces.size() + " decision trace" + (traces.size() == 1 ? "" : "s") + " from the payments specialist are on record.");
        findings.addAll(StorySupport.traceFindings(traces));
        Boolean compliant = policy.text().isEmpty() || settlementFirst == null ? null : settlementFirst && noSettledCapture;
        return new Compliance(applied, settlementFirst, reasked, compliant, findings);
    }

    /** The gate decisions {@code agentId} wrote to the record: who decided, on how much context. */
    private static int indexOf(List<Turn> turns, String event) {
        for (int i = 0; i < turns.size(); i++) if (turns.get(i).isEvent(SPECIALIST, event)) return i;
        return -1;
    }

    private static String verdict(Compliance c) {
        String policy = c.policyApplied() == null ? "the applicable policy" : c.policyApplied();
        return c.compliant() == null ? "Compliance with " + policy + " could not be verified."
                : c.compliant() ? "Handled in compliance with " + policy + "." : "Not handled in compliance with " + policy + ".";
    }

    private static List<String> findings(Compliance c) {
        return c.findings() == null ? List.of() : c.findings();
    }

    private static String yesNo(Boolean value) {
        return value == null ? "unknown" : value ? "yes" : "no";
    }

    // ------------------------------------------------------------------ conditions

    @Condition(name = "recordRetrieved")
    public boolean recordRetrieved(AuditRecord record) {
        return record != null;
    }

    @Condition(name = "customerAccountKnown")
    public boolean customerAccountKnown(AuditRecord record) {
        return record != null && (record.customerAccount() || record.requested().contains(VENDOR));
    }

    @Condition(name = "customerAccountUnknown")
    public boolean customerAccountUnknown(AuditRecord record) {
        return record != null && !customerAccountKnown(record);
    }

    @Condition(name = "resolutionKnown")
    public boolean resolutionKnown(AuditRecord record) {
        return record != null && (record.resolution() || record.requested().contains(SPECIALIST));
    }

    @Condition(name = "resolutionUnknown")
    public boolean resolutionUnknown(AuditRecord record) {
        return record != null && !resolutionKnown(record);
    }

    @Condition(name = "policyKnown")
    public boolean policyKnown(PolicyText policy) {
        return policy != null && (!policy.text().isEmpty() || policy.source() != null);
    }

    @Condition(name = "policyUnavailable")
    public boolean policyUnavailable(PolicyText policy) {
        return policy != null && !policyKnown(policy);
    }

    @Condition(name = "auditChecked")
    public boolean auditChecked(AuditChecked checked) {
        return checked != null;
    }

    @Condition(name = "findingFiled")
    public boolean findingFiled(AuditFinding finding) {
        return finding != null;
    }
}
