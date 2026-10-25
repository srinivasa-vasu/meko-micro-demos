package meko.agentic.goap.story.agents;

import com.embabel.agent.api.annotation.AchievesGoal;
import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.Agent;
import com.embabel.agent.api.annotation.Condition;
import com.embabel.agent.api.annotation.Provided;
import com.embabel.agent.api.common.Ai;
import com.embabel.agent.api.common.PlannerType;
import meko.agentic.goap.story.KnowledgeDocuments;
import meko.agentic.goap.story.PaymentDisputes;
import meko.agentic.goap.story.StoryLedger;
import meko.agentic.goap.story.agents.SupportFrontlineAgent.TicketNote;
import meko.agentic.goap.story.store.ContextStore;
import meko.agentic.goap.story.store.StoreRecord;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Story 1, phase 2: the payments specialist, a separate agent process that receives only the ticket note.
 * <p>
 * {@code openCaseFile} posts every "known"/"unknown" condition, so the first plan optimistically assumes
 * the facts will be there. After it runs, the planner replans against what was actually recovered: each
 * fact that is still unknown makes an "ask the customer" action necessary.
 */
@Agent(planner = PlannerType.GOAP, description = "Payments specialist that resolves an escalated card-payment dispute")
public class PaymentsSpecialistAgent {
    public static final String AGENT_ID = "support:payments-specialist";

    /**
     * One question the customer had to answer again after the handoff.
     *
     * @param unknown what the specialist did not know, which is why it asked
     */
    public record Exchange(String question, String answer, String unknown) {}

    /**
     * @param ticketNote what crossed the boundary from the vendor chatbot
     * @param asked      each question the customer had to answer again after the handoff, in order
     */
    public record CaseFile(String caseId, ContextStore.Kind store, String ticketNote, String transaction, String amount, String merchant,
                           Boolean troubleshootingDone, Boolean cardWithCustomer, List<Exchange> asked) {
        CaseFile withTransaction(String tx, String amt, String at, Exchange e) { return new CaseFile(caseId, store, ticketNote, tx, amt, at, troubleshootingDone, cardWithCustomer, plus(e)); }
        CaseFile withTroubleshooting(Exchange e) { return new CaseFile(caseId, store, ticketNote, transaction, amount, merchant, true, cardWithCustomer, plus(e)); }
        CaseFile withCard(Exchange e) { return new CaseFile(caseId, store, ticketNote, transaction, amount, merchant, troubleshootingDone, true, plus(e)); }
        private List<Exchange> plus(Exchange e) { List<Exchange> all = new ArrayList<>(asked); all.add(e); return List.copyOf(all); }
    }

    /**
     * How a re-ask is marked in the conversation: the reasoning of the specialist's dialogue turn starts with this, so
     * whoever reads the conversation can count re-asks without a model.
     */
    public static final String REASKED = "re-asked";

    /** Event names of the specialist's records in the transaction's conversation (see {@link ContextStore.Turn#event}). */
    public static final String SETTLEMENT = "settlement check";
    public static final String POLICY = "policy applied";
    public static final String RESOLUTION = "resolution";

    /** The persona this agent's gate decisions are recorded under. */
    static final String PERSONA = "Payments specialist";

    public record SettlementCheck(String caseId, boolean authorized, boolean captured, boolean settled) {
        /** How the record states that no capture settled; whoever reads the record back matches on it. */
        public static final String NO_SETTLED_CAPTURE = "no settled capture";

        String describe() {
            return "authorization " + (authorized ? "accepted" : "declined") + ", "
                    + (captured ? "capture completed" : "capture timed out at the gateway") + ", "
                    + (settled ? "capture settled" : NO_SETTLED_CAPTURE);
        }
    }

    /**
     * Whether a policy's text covers this case, read from what the settlement check found: a capture that timed out
     * with nothing settled, and no chargeback raised through the card issuer (none was, in this case). The fallback
     * rule for the policy gate, and how payments operations picks from its own documents.
     */
    private static Optional<String> idOf(StoreRecord r) {
        return StorySupport.extract(List.of(r), StorySupport.POLICY_ID);
    }

    /** The one retrieved policy whose scope covers the case; empty if none does, or more than one. */
    static Optional<String> onlyCovering(List<StoreRecord> policies, SettlementCheck check) {
        List<String> ids = policies.stream().filter(r -> covers(r.text(), check)).map(PaymentsSpecialistAgent::idOf).flatMap(Optional::stream).distinct().toList();
        return ids.size() == 1 ? Optional.of(ids.getFirst()) : Optional.empty();
    }

    static boolean covers(String policyText, SettlementCheck check) {
        String text = policyText.toLowerCase();
        boolean captureTimeout = text.matches("(?s).*capture (times|timed) out.*");
        boolean issuerChargebackOnly = text.contains("chargeback") && text.contains("issuer");
        return check.authorized() && !check.captured() && !check.settled() && captureTimeout && !issuerChargebackOnly;
    }

    /**
     * @param reason why this policy governs the case, as the agent judged it (or who confirmed it)
     * @param text   the policy's text as the specialist has it (retrieved, or as payments operations sent it); null if none
     */
    public record PolicyLookup(String caseId, String policy, String writtenBy, String reason, String text) {}

    /** What a policy's text says about a provisional refund for this case. */
    enum Refund { APPROVE, REFUSE, NOT_STATED }

    /**
     * Reads the policy's own rule on provisional refunds: the sentence that mentions one. "Do not" refuses; "may be
     * approved" approves, when the conditions it states about settlement hold for what the settlement check found. A
     * policy that says nothing about refunds decides nothing: the case goes to a person.
     *
     * @return the decision and the sentence it was read from (null when not stated)
     */
    static Map.Entry<Refund, String> refundRule(String policyText, SettlementCheck check) {
        if (policyText == null) return Map.entry(Refund.NOT_STATED, "");
        for (String sentence : policyText.replaceAll("\\s+", " ").split("(?<=[.;])\\s+|\\s\\d+\\.\\s")) {
            String lower = sentence.toLowerCase();
            if (!lower.contains("provisional refund")) continue;
            String clause = sentence.trim();
            // A refusal, active ("do not issue a provisional refund") or passive ("a provisional refund must not be issued").
            if (lower.matches(".*\\b(do not|don't|must not|never) (issue|approve|grant|give|offer)\\b.*provisional refund.*|.*\\bno provisional refund\\b.*"
                    + "|.*provisional refund.*\\b(must not|may not|should not|shall not|cannot|can't|is not to|will not|never) (be )?(approved|issued|granted|given)\\b.*")) return Map.entry(Refund.REFUSE, clause);
            if (lower.matches(".*provisional refund.*\\b(may|can|should|is to) be (approved|issued)\\b.*")) {
                // A condition on settlement the policy states must hold for what the check found.
                boolean needsNoSettled = lower.contains("no settled capture") || lower.contains("nothing settled") || lower.contains("not settled");
                return needsNoSettled && check.settled() ? Map.entry(Refund.REFUSE, clause) : Map.entry(Refund.APPROVE, clause);
            }
        }
        return Map.entry(Refund.NOT_STATED, "");
    }

    /** Who answers when the specialist escalates for a policy, and how the specialist records it. */
    static final String PAYMENTS_OPS = "payments-ops";
    /** How a policy confirmed by payments operations, with no written policy in reach, is recorded. */
    public static final String VERBAL = "confirmed verbally by payments operations";
    /** How an escalation nobody could answer from a written policy is recorded: the case is with a person, undecided. */
    public static final String HANDED_TO_HUMAN = "no written policy in reach; handed to payments operations for a human decision";

    public record SupportOutcome(String caseId, String resolution) {}

    /** The model's choice of the policy that governs this situation. */
    public record PolicyChoice(String policy, String reason) {}

    /** The model's reading of which established facts the specialist has. */
    public record KnownFacts(String transaction, String amount, String merchant, Boolean troubleshootingDone, Boolean cardWithCustomer) {}

    private final StorySupport support;
    private final DecisionService decisions;
    private final KnowledgeDocuments documents;

    /**
     * Whether an escalation for a missing policy is answered (the demo: a simulated payments-ops team confirms the policy,
     * so the cost of escalating can be measured). In the workshop it is not: with no written policy in reach, the case
     * goes to a human and nothing is decided.
     */
    private final boolean escalationsAnswered;

    public PaymentsSpecialistAgent(StorySupport support, DecisionService decisions, KnowledgeDocuments documents,
                                   @org.springframework.beans.factory.annotation.Value("${goap.escalation.answered:true}") boolean escalationsAnswered) {
        this.escalationsAnswered = escalationsAnswered;
        this.support = support;
        this.decisions = decisions;
        this.documents = documents;
    }

    @Action(description = "Open the case file from whatever context is available beyond the ticket note",
            post = {"caseFileOpen", "transactionKnown", "transactionUnknown", "troubleshootingKnown",
                    "troubleshootingUnknown", "cardStatusKnown", "cardStatusUnknown"},
            cost = 0.02, canRerun = false)
    public CaseFile openCaseFile(TicketNote ticket, @Provided Ai ai) {
        StoryLedger ledger = support.ledger(ticket.caseId());
        ContextStore store = support.store(ticket.store());
        ledger.note("Ticket note", ticket.note());
        List<StoreRecord> facts = store.recallCase(ticket.caseId(), AGENT_ID, "customer transaction amount merchant troubleshooting card possession");
        // The handoff facts in case memory carry what the specialist needs. It does not read the whole conversation
        // (conversation_get): that is the expensive call, kept for the auditor, who needs the full record.
        support.found(ledger, "case memory", facts);
        // Everything the specialist has: the ticket note, plus whatever the context store returned.
        StringBuilder context = new StringBuilder("Ticket note: ").append(ticket.note()).append("\n");
        context.append(StorySupport.asContext("Case memory", facts));
        java.util.function.Supplier<KnownFacts> byRule = () -> new KnownFacts(
                StorySupport.extract(facts, "(TX-\\d+)").orElse(null),
                StorySupport.extract(facts, "(\\d+\\.\\d{2}\\s*USD)").orElse(null),
                StorySupport.extract(facts, "\\d+\\.\\d{2}\\s*USD at ([^.]+)\\.").orElse(null),
                StorySupport.mentions(facts, "retried|restarted") ? Boolean.TRUE : null,
                StorySupport.mentions(facts, "possession|not lost") ? Boolean.TRUE : null);
        String said = context.toString().toLowerCase();
        KnownFacts known = decisions.decideUnlessClear(ticket.caseId(),
                PERSONA,
                "decisions/support-known-facts",
                Map.of(),
                context.toString(),
                KnownFacts.class,
                // Settled without a model when nothing beyond the ticket was recalled, or when every fact parses from the handoff.
                () -> facts.isEmpty() ? Optional.of(new KnownFacts(null, null, null, null, null))
                        : Optional.of(byRule.get()).filter(k -> k.transaction() != null && k.amount() != null && k.merchant() != null
                        && k.troubleshootingDone() != null && k.cardWithCustomer() != null),
                facts.isEmpty() ? "nothing beyond the ticket note" : "every fact parses from the handoff memory",
                byRule,
                // A fact the model reports must be in what the specialist has; a confirmation needs recalled facts behind it.
                k -> inContext(k.transaction(), said) && inContext(k.amount(), said) && inContext(k.merchant(), said)
                        && (!Boolean.TRUE.equals(k.troubleshootingDone()) && !Boolean.TRUE.equals(k.cardWithCustomer()) || !facts.isEmpty()),
                ai);
        CaseFile file = new CaseFile(ticket.caseId(), ticket.store(), ticket.note(),
                blankToNull(known.transaction()), blankToNull(known.amount()), blankToNull(known.merchant()),
                Boolean.TRUE.equals(known.troubleshootingDone()) ? Boolean.TRUE : null,
                Boolean.TRUE.equals(known.cardWithCustomer()) ? Boolean.TRUE : null, List.of());
        int count = (transactionKnown(file) ? 1 : 0) + (file.troubleshootingDone() != null ? 1 : 0) + (file.cardWithCustomer() != null ? 1 : 0);
        ledger.step("Payments specialist", "openCaseFile",
                "Opened the case file: " + count + " of 3 established facts recovered beyond the ticket note.", support.effort("payments-specialist", "open-case-file"), StoryLedger.Kind.CONTEXT);
        return file;
    }

    /**
     * Writes the re-asks to the transaction's conversation as dialogue: each turn is what the customer said (the ticket
     * note for the first) and the specialist's reply, with the reasoning saying why it had to ask.
     */
    private void recordReasks(ContextStore store, CaseFile file) {
        if (file.asked().isEmpty()) return;
        String said = file.ticketNote();
        for (Exchange e : file.asked()) {
            support.recordEvent(store, file.caseId(), AGENT_ID, file.transaction(), REASKED,
                    "not in the context the specialist had: " + e.unknown(), said, e.question());
            said = e.answer();
        }
        String reply = "Thank you. I'm checking the settlement now.";
        support.ledger(file.caseId()).say(PERSONA, reply);
        support.record(store, file.caseId(), AGENT_ID, file.transaction(), said, reply);
    }

    /** A value the model reported is usable only if it is blank or appears in the context it was given. */
    private static boolean inContext(String value, String context) {
        String v = blankToNull(value);
        return v == null || context.contains(v.toLowerCase());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() || value.equalsIgnoreCase("null") || value.equalsIgnoreCase("unknown") ? null : value;
    }

    @Action(description = "Ask the customer for the transaction details again", pre = {"transactionUnknown"},
            post = {"transactionKnown"}, cost = 0.25, canRerun = false)
    public CaseFile askForTransaction(CaseFile file) {
        StoryLedger ledger = support.ledger(file.caseId());
        String question = "Sorry, could you give me the transaction ID, the amount and the merchant?";
        // The simulated customer answers with the facts of their dispute, the same ones they gave the vendor chatbot.
        PaymentDisputes.Dispute dispute = support.disputes().forCase(file.caseId());
        String answer = "I already told the other agent... " + dispute.transaction() + ", " + dispute.amount() + " at " + dispute.merchant() + ".";
        ledger.say("Payments specialist", question);
        ledger.say("Customer", answer);
        ledger.step("Payments specialist", "askForTransaction", "Asked the customer to repeat the transaction details.", support.effort("payments-specialist", "ask-transaction"), StoryLedger.Kind.ASK);
        ledger.count("customerQuestions", 1);
        return file.withTransaction(dispute.transaction(), dispute.amount(), dispute.merchant(),
                new Exchange(question, answer, "the transaction id, amount and merchant"));
    }

    @Action(description = "Ask the customer to repeat their troubleshooting", pre = {"troubleshootingUnknown"},
            post = {"troubleshootingKnown"}, cost = 0.30, canRerun = false)
    public CaseFile askToRepeatTroubleshooting(CaseFile file) {
        StoryLedger ledger = support.ledger(file.caseId());
        String question = "Could you try the payment once more and restart the app, so we can rule that out?";
        String answer = "I did that already. Fine, trying again... still failed.";
        ledger.say("Payments specialist", question);
        ledger.say("Customer", answer);
        ledger.step("Payments specialist", "askToRepeatTroubleshooting", "Had the customer redo troubleshooting they had already done.", support.effort("payments-specialist", "ask-redo-troubleshooting"), StoryLedger.Kind.ASK);
        ledger.count("customerQuestions", 1);
        ledger.count("repeatedSteps", 1);
        return file.withTroubleshooting(new Exchange(question, answer, "whether the customer had already retried and restarted the app"));
    }

    @Action(description = "Ask the customer whether the card is still in their possession", pre = {"cardStatusUnknown"},
            post = {"cardStatusKnown"}, cost = 0.15, canRerun = false)
    public CaseFile askCardStatus(CaseFile file) {
        StoryLedger ledger = support.ledger(file.caseId());
        String question = "Is the card still with you?";
        String answer = "Yes. I said that too.";
        ledger.say("Payments specialist", question);
        ledger.say("Customer", answer);
        ledger.step("Payments specialist", "askCardStatus", "Asked the customer to confirm card possession again.", support.effort("payments-specialist", "ask-card-status"), StoryLedger.Kind.ASK);
        ledger.count("customerQuestions", 1);
        return file.withCard(new Exchange(question, answer, "whether the card is still with the customer"));
    }

    @Action(description = "Check authorization, capture and settlement for the transaction",
            pre = {"transactionKnown", "troubleshootingKnown", "cardStatusKnown"}, post = {"settlementChecked"},
            cost = 0.10, canRerun = false)
    public SettlementCheck verifySettlement(CaseFile file) {
        // The simulated payments console's answer for this scenario.
        SettlementCheck check = new SettlementCheck(file.caseId(), true, false, false);
        // The transaction is known from here on: record the re-asks and the check in its conversation, in order.
        ContextStore store = support.store(file.store());
        support.traceDecisions(store, file.caseId(), AGENT_ID, file.transaction(), PERSONA);
        recordReasks(store, file);
        support.recordEvent(store, file.caseId(), AGENT_ID, file.transaction(), SETTLEMENT, null,
                "Check authorization, capture and settlement for " + file.transaction(), check.describe());
        support.ledger(file.caseId()).step("Payments specialist", "verifySettlement",
                file.transaction() + ": " + check.describe() + ".", support.effort("payments-specialist", "verify-settlement"), StoryLedger.Kind.WORK);
        return check;
    }

    @Action(description = "Retrieve the institutional dispute policy for this situation", pre = {"settlementChecked"},
            post = {"policyKnown", "policyUnavailable"}, cost = 0.02, canRerun = false)
    public PolicyLookup retrievePolicy(CaseFile file, SettlementCheck check, @Provided Ai ai) {
        StoryLedger ledger = support.ledger(file.caseId());
        List<StoreRecord> policies = support.store(file.store())
                // The dispute policies in general, then the one whose scope covers this case: a narrower query can miss the
                // look-alike altogether, and then there is nothing to tell apart.
                .searchKnowledge(file.caseId(), AGENT_ID, "card dispute policy for a disputed card payment")
                .stream().filter(r -> r.text().contains("policy")).toList();
        PolicyChoice choice = policies.isEmpty() ? new PolicyChoice(null, "no policies retrieved")
                : decisions.decideUnlessClear(file.caseId(),
                PERSONA,
                "decisions/support-policy",
                Map.of(),
                StorySupport.asContext("Policies", policies),
                PolicyChoice.class,
                // Settled by the policies' own scope when exactly one covers what the settlement check found.
                () -> onlyCovering(policies, check).map(id -> new PolicyChoice(id,
                        "its scope covers a capture timeout after a successful authorization with no settled capture")),
                "exactly one policy's scope covers what the settlement check found",
                () -> policies.stream().filter(r -> covers(r.text(), check)).findFirst()
                        .flatMap(r -> StorySupport.extract(List.of(r), StorySupport.POLICY_ID))
                        .map(id -> new PolicyChoice(id, "its scope covers a capture timeout after a successful authorization with no settled capture"))
                        .orElse(new PolicyChoice(null, "no retrieved policy covers a capture timeout with no settled capture")),
                // The model's choice must be a retrieved policy whose scope covers the case: never the look-alike.
                c -> blankToNull(c.policy()) == null || policies.stream().anyMatch(r -> idOf(r).filter(c.policy().trim()::equals).isPresent() && covers(r.text(), check)),
                ai);
        String chosen = blankToNull(choice.policy());
        List<StoreRecord> used = chosen == null ? List.of() : policies.stream().filter(r -> idOf(r).filter(chosen.trim()::equals).isPresent()).toList();
        support.found(ledger, "knowledge base", used);
        if (chosen != null) {
            policies.stream().filter(r -> !used.contains(r)).forEach(r -> {
                String id = StorySupport.extract(List.of(r), StorySupport.POLICY_ID).orElse("another policy");
                support.passedOver(ledger, "knowledge base", r, id + " covers a different situation"
                        + (r.text().toLowerCase().contains("chargeback") ? ": a chargeback the customer raised with their card issuer" : "")
                        + "; this case is a capture timeout after a successful authorization");
            });
        }
        support.traceDecisions(support.store(file.store()), file.caseId(), AGENT_ID, file.transaction(), PERSONA);
        if (chosen != null) {
            support.recordEvent(support.store(file.store()), file.caseId(), AGENT_ID, file.transaction(), POLICY, null,
                    "Apply the dispute policy for " + file.transaction(), chosen + ": " + choice.reason());
        }
        ledger.step("Payments specialist", "retrievePolicy",
                chosen == null ? "No institutional policy found for failed capture."
                        : "Chose policy " + chosen + " from " + policies.size() + " retrieved: " + choice.reason(), support.effort("payments-specialist", "retrieve-policy"), StoryLedger.Kind.CONTEXT);
        return chosen == null || used.isEmpty() ? new PolicyLookup(file.caseId(), null, null, choice.reason(), null)
                : new PolicyLookup(file.caseId(), chosen, used.getFirst().writtenBy(), choice.reason(),
                        String.join("\n\n", used.stream().map(StoreRecord::text).toList()));
    }

    @Action(description = "Escalate to payments operations to confirm which policy applies", pre = {"policyUnavailable"},
            post = {"policyKnown"}, cost = 0.40, canRerun = false)
    public PolicyLookup escalateForPolicy(CaseFile file, SettlementCheck check, PolicyLookup lookup) {
        StoryLedger ledger = support.ledger(file.caseId());
        if (!escalationsAnswered) {
            ledger.say("Payments specialist", "I can't find a written policy for this, so a colleague in payments operations will review your case and get back to you.");
            ledger.step("Payments specialist", "escalateForPolicy", "No written policy in reach: handed the case to payments operations for a human decision.",
                    support.effort("payments-specialist", "escalate-for-policy"), StoryLedger.Kind.ASK);
            PolicyLookup handed = new PolicyLookup(file.caseId(), null, PAYMENTS_OPS, HANDED_TO_HUMAN, null);
            support.recordEvent(support.store(file.store()), file.caseId(), AGENT_ID, file.transaction(), POLICY, null,
                    "Apply the dispute policy for " + file.transaction(), "none: " + HANDED_TO_HUMAN);
            return handed;
        }
        ledger.say("Payments specialist", "I need to check with our payments operations team. I'll get back to you within the hour.");
        ledger.step("Payments specialist", "escalateForPolicy", "Waited on payments operations to confirm the applicable policy.", support.effort("payments-specialist", "escalate-for-policy"), StoryLedger.Kind.ASK);
        // The simulated payments-ops team owns the policy documents and answers from them, for what the check found.
        Optional<KnowledgeDocuments.Document> doc = documents.all().stream().filter(d -> covers(d.markdown(), check)).findFirst();
        String policy = doc.map(KnowledgeDocuments.Document::key).orElse("no written policy (payments operations decided case by case)");
        PolicyLookup confirmed = new PolicyLookup(file.caseId(), policy, PAYMENTS_OPS, VERBAL, doc.map(KnowledgeDocuments.Document::markdown).orElse(null));
        support.recordEvent(support.store(file.store()), file.caseId(), AGENT_ID, file.transaction(), POLICY, null,
                "Apply the dispute policy for " + file.transaction(), confirmed.policy() + ": " + confirmed.reason());
        return confirmed;
    }

    @AchievesGoal(description = "The customer's dispute is resolved")
    @Action(description = "Resolve the dispute and tell the customer", pre = {"policyKnown"}, post = {"disputeResolved"},
            cost = 0.05, canRerun = false)
    public SupportOutcome resolve(CaseFile file, SettlementCheck check, PolicyLookup lookup) {
        StoryLedger ledger = support.ledger(file.caseId());
        boolean verbal = PAYMENTS_OPS.equals(lookup.writtenBy());
        boolean written = lookup.policy() != null && StorySupport.extract(List.of(new StoreRecord("", lookup.policy(), "", "", null)), StorySupport.POLICY_ID).isPresent();
        // The refund follows the applied policy's own rule on refunds, read from its text; no rule, no decision.
        Map.Entry<Refund, String> rule = written ? refundRule(lookup.text(), check) : Map.entry(Refund.NOT_STATED, "");
        boolean undecided = rule.getKey() == Refund.NOT_STATED;
        String basis = "under " + lookup.policy() + (verbal ? " (confirmed by payments ops)" : "");
        String resolution = switch (rule.getKey()) {
            case APPROVE -> "Provisional refund of " + file.amount() + " approved " + basis + " while settlement is verified. The policy: \"" + rule.getValue() + "\"";
            case REFUSE -> "No provisional refund of " + file.amount() + " " + basis + ". The policy: \"" + rule.getValue() + "\"";
            case NOT_STATED -> "No refund decided for " + file.amount() + ": " + (written
                    ? lookup.policy() + " states no rule on a provisional refund"
                    : "no written dispute policy in reach") + ", so the case is with payments operations for a human decision.";
        };
        ledger.count(undecided ? "handedToPerson" : "decided", 1);
        ledger.step("Payments specialist", "resolve", resolution, support.effort("payments-specialist", "resolve"), StoryLedger.Kind.WORK);
        ledger.outcome(resolution);
        int reasked = ledger.counters().getOrDefault("customerQuestions", 0);
        // What the customer was actually asked after the handoff, from the steps that ran.
        List<String> asked = ledger.steps().stream()
                .filter(st -> st.kind() == StoryLedger.Kind.ASK && st.persona().equals("Payments specialist") && st.action().startsWith("ask"))
                .map(StoryLedger.Step::text).toList();
        List<String> facts = new ArrayList<>(List.of(
                "Transaction " + file.transaction() + ", " + file.amount() + " at " + file.merchant(),
                "Settlement check: " + check.describe(),
                "Troubleshooting already done by the customer: " + (Boolean.TRUE.equals(file.troubleshootingDone()) ? "yes" : "unknown")
                        + "; card with the customer: " + (Boolean.TRUE.equals(file.cardWithCustomer()) ? "yes" : "unknown"),
                "Resolution: " + resolution));
        facts.add(asked.isEmpty() ? "The customer was not asked to repeat anything after the handoff"
                : "After the handoff the customer had to be asked " + asked.size() + " times: " + String.join(" ", asked));
        List<String> gaps = verbal
                ? List.of("The written dispute policy text was not available; payments ops confirmed the outcome verbally")
                : List.of();
        facts.add("Policy: " + lookup.policy() + " (" + lookup.reason() + ")");
        ledger.handToWriter(new ResponseWriterAgent.WritingTask(file.caseId(), "Payments specialist",
                "reply to the customer",
                support.render("writer/support-reply", Map.of("reasked", reasked,
                        "redidTroubleshooting", ledger.steps().stream().anyMatch(st -> st.action().equals("askToRepeatTroubleshooting")))),
                facts, gaps,
                (undecided ? "Thanks for your patience. A colleague in payments operations is reviewing " + file.transaction() + " and will get back to you."
                        : rule.getKey() == Refund.REFUSE ? "Thanks for your patience. Under our policy we can't issue a provisional refund for " + file.transaction() + " right now; we'll keep you posted."
                        : "Thanks for your patience. I've approved a provisional refund of " + file.amount() + " for " + file.transaction()
                        + " while we confirm settlement.")));
        support.store(file.store()).rememberLater(file.caseId(), AGENT_ID, "Support case " + file.caseId() + " (" + file.transaction() + ") resolved: " + resolution
                + " Because: the settlement check found " + check.describe() + "; " + (lookup.policy() == null ? "no policy" : lookup.policy()) + " was applied: " + lookup.reason() + ".");
        support.traceDecisions(support.store(file.store()), file.caseId(), AGENT_ID, file.transaction(), PERSONA);
        support.recordEvent(support.store(file.store()), file.caseId(), AGENT_ID, file.transaction(), RESOLUTION, null,
                "Resolve the dispute on " + file.transaction(), resolution);
        return new SupportOutcome(file.caseId(), resolution);
    }

    @Condition(name = "caseFileOpen")
    public boolean caseFileOpen(CaseFile file) {
        return file != null;
    }

    @Condition(name = "transactionKnown")
    public boolean transactionKnown(CaseFile file) {
        return file != null && file.transaction() != null && file.amount() != null && file.merchant() != null;
    }

    @Condition(name = "transactionUnknown")
    public boolean transactionUnknown(CaseFile file) {
        return file != null && (file.transaction() == null || file.amount() == null || file.merchant() == null);
    }

    @Condition(name = "troubleshootingKnown")
    public boolean troubleshootingKnown(CaseFile file) {
        return file != null && file.troubleshootingDone() != null;
    }

    @Condition(name = "troubleshootingUnknown")
    public boolean troubleshootingUnknown(CaseFile file) {
        return file != null && file.troubleshootingDone() == null;
    }

    @Condition(name = "cardStatusKnown")
    public boolean cardStatusKnown(CaseFile file) {
        return file != null && file.cardWithCustomer() != null;
    }

    @Condition(name = "cardStatusUnknown")
    public boolean cardStatusUnknown(CaseFile file) {
        return file != null && file.cardWithCustomer() == null;
    }

    @Condition(name = "settlementChecked")
    public boolean settlementChecked(SettlementCheck check) {
        return check != null;
    }

    @Condition(name = "policyKnown")
    public boolean policyKnown(PolicyLookup lookup) {
        // Known once a policy is found or confirmed, or once payments operations has the case (then nothing is decided here).
        return lookup != null && (lookup.policy() != null || PAYMENTS_OPS.equals(lookup.writtenBy()));
    }

    @Condition(name = "policyUnavailable")
    public boolean policyUnavailable(PolicyLookup lookup) {
        return lookup != null && lookup.policy() == null;
    }

    @Condition(name = "disputeResolved")
    public boolean disputeResolved(SupportOutcome outcome) {
        return outcome != null;
    }
}
