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
import meko.agentic.goap.story.LoanApplications;
import meko.agentic.goap.story.StoryLedger;
import meko.agentic.goap.story.agents.LoanIntakeAgent.Referral;
import meko.agentic.goap.story.store.ContextStore;
import meko.agentic.goap.story.store.StoreRecord;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Loan story, phase 2: your credit underwriter, a separate agent process that receives the referral with the whole
 * application file. The figures are all there; what the file does not say is what the borrower already explained.
 * <p>
 * The policy sets conditions: a large deposit must be sourced, an employment gap explained. {@code reviewFlags} posts both
 * "cleared" and "conditions needed", so the first plan optimistically assumes the explanations will be in reach. After it
 * runs, the planner replans against what was actually found: each flag still unexplained becomes a condition sent back
 * to the borrower, who explains again. The credit call is then a gate decision over the file's figures and the
 * thresholds in the policy text; nothing about the outcome is fixed.
 */
@Agent(planner = PlannerType.GOAP, description = "Credit underwriter that makes the credit call on a referred personal-loan application")
public class UnderwriterAgent {
    public static final String AGENT_ID = "lending:underwriter";
    static final String PERSONA = "Credit underwriter";

    /**
     * Something in the file the policy says must be explained.
     *
     * @param id          "deposit" or "employment gap"
     * @param condition   the condition the policy sets for it, as it would be sent to the borrower
     * @param explained   the explanation and its evidence, and where it came from, once in hand (null before)
     * @param kind        the kind of source the explanation gives ("a vehicle sale"), if it names one
     * @param evidence    the document offered as evidence, once in hand
     * @param clearedFrom how the condition was cleared (the explanation, and why its evidence is accepted), or null while open
     */
    public record Flag(String id, String description, String condition, String explained, String kind, String evidence, String clearedFrom) {
        Flag(String id, String description, String condition) { this(id, description, condition, null, null, null, null); }
        boolean inHand() { return explained != null; }
        boolean open() { return clearedFrom == null; }
        Flag explainedBy(String how, String k, String e) { return new Flag(id, description, condition, how, k, e, null); }
        Flag cleared(String how) { return new Flag(id, description, condition, explained, kind, evidence, how); }
    }

    /**
     * @param referral   what crossed the boundary from the origination system: the terms and the application file
     * @param flags      what the policy says needs explaining, once reviewed (null before)
     * @param conditions the conditions sent back to the borrower, with their answers, in order
     * @param precedents the set of credit-risk rulings that count as precedent (see {@link #precedentTag})
     * @param evidenceChecked whether the evidence behind each explanation was checked against the policy and precedent
     */
    public record ApplicationFile(String caseId, ContextStore.Kind store, String applicationId, String referral, int amountUsd,
                                  int instalment, int monthlyIncome, String employment, int reservesMonths, int creditScore,
                                  int monthlyDebts, int depositUsd, String depositDate, int gapDays, String gapPeriod,
                                  List<Flag> flags, List<Exchange> conditions, String precedents, boolean evidenceChecked) {
        ApplicationFile withFlags(List<Flag> f) { return new ApplicationFile(caseId, store, applicationId, referral, amountUsd, instalment, monthlyIncome, employment, reservesMonths, creditScore, monthlyDebts, depositUsd, depositDate, gapDays, gapPeriod, List.copyOf(f), conditions, precedents, evidenceChecked); }
        ApplicationFile withConditions(List<Flag> f, List<Exchange> c) { return new ApplicationFile(caseId, store, applicationId, referral, amountUsd, instalment, monthlyIncome, employment, reservesMonths, creditScore, monthlyDebts, depositUsd, depositDate, gapDays, gapPeriod, List.copyOf(f), List.copyOf(c), precedents, evidenceChecked); }
        ApplicationFile withEvidenceChecked(List<Flag> f) { return new ApplicationFile(caseId, store, applicationId, referral, amountUsd, instalment, monthlyIncome, employment, reservesMonths, creditScore, monthlyDebts, depositUsd, depositDate, gapDays, gapPeriod, List.copyOf(f), conditions, precedents, true); }

        /** What the borrower said last: the referral before any condition, then their latest answer. */
        String lastSaid() { return conditions.isEmpty() ? referral : conditions.getLast().answer(); }
    }

    /** One condition sent back to the borrower, and their answer. */
    public record Exchange(String question, String answer, String unknown) {}

    /** How a condition the borrower had to answer again is marked in the conversation (see {@link ContextStore.Turn#event}). */
    public static final String REASKED = "re-asked";
    /** Event names of the underwriter's records in the application's conversation. */
    public static final String CONDITION_CLEARED = "condition cleared";
    public static final String AFFORDABILITY = "affordability";
    public static final String POLICY = "policy applied";
    public static final String CREDIT_CALL = "credit call";
    /** How a cleared condition says where its explanation came from. */
    public static final String FROM_ORIGINATION = "cleared from the borrower's explanation at origination";
    public static final String FROM_CONDITION = "cleared on the borrower's answer to the condition";
    /** How a cleared condition says why its evidence is accepted, and how a credit-risk ruling is recorded and found again. */
    public static final String EVIDENCE_ACCEPTED = "evidence accepted: ";
    public static final String RULING = "credit-risk ruling";
    public static final String RULING_MARKER = "Credit-risk ruling under ";
    public static final String PRECEDENT_FROM = "a credit-risk ruling in shared knowledge, written by ";
    public static final String RULED_ON_REQUEST = "ruled by credit risk on request";

    /** Who answers when the underwriter escalates for a policy, and how a policy it sent on request is recorded. */
    static final String CREDIT_RISK = "credit-risk";
    public static final String SENT_ON_REQUEST = "sent by credit risk on request";
    /** How the credit-call record states the thresholds applied and the factors the call rested on (policy record-keeping). */
    public static final String THRESHOLDS_APPLIED = "Thresholds applied: ";
    public static final String FACTORS = "Factors: ";

    /** @param dtiPercent debt-to-income including the new instalment, computed exactly from the file's figures */
    public record Affordability(String caseId, double dtiPercent, int creditScore, int reservesMonths, String working) {
        /** The factors the credit call rests on, as recorded with it. */
        String factors() {
            return "credit score " + creditScore + ", DTI " + String.format("%.1f", dtiPercent) + "%, reserves " + reservesMonths + " months";
        }
    }

    /**
     * @param text   the policy text the underwriter has: retrieved from knowledge, or what credit risk sent
     * @param reason why this policy governs the application, as the agent judged it (or who sent it)
     */
    public record PolicyLookup(String caseId, String policy, List<StoreRecord> text, String writtenBy, String reason) {}

    public record CreditCall(String caseId, String applicationId, String decision, String reason) {}

    public record PolicyChoice(String policy, String reason) {}

    /** The model's reading of which flagged items the borrower already explained, with evidence. */
    public record FlagReading(List<String> explained, List<String> unexplained) {}

    /** The model's reading of whether a ruling in shared knowledge accepts this evidence. */
    public record PrecedentFit(Boolean accepts, String reason) {}

    /** The model's judgement on whether a ruling is general enough to share as precedent. */
    public record ShareJudgement(Boolean share, String reason) {}

    /** The model's credit call over the file's figures and the policy text. */
    public record CallJudgement(String decision, String thresholdsApplied, String reason) {}

    private final StorySupport support;
    private final DecisionService decisions;
    private final KnowledgeDocuments documents;

    /** As in {@link PaymentsSpecialistAgent}: in the workshop, an escalation for a missing policy is not answered. */
    private final boolean escalationsAnswered;
    /** How a ruling request nobody has answered yet is recorded. */
    public static final String RULING_PENDING = "ruling pending with credit risk";

    public UnderwriterAgent(StorySupport support, DecisionService decisions, KnowledgeDocuments documents,
                            @org.springframework.beans.factory.annotation.Value("${goap.escalation.answered:true}") boolean escalationsAnswered) {
        this.escalationsAnswered = escalationsAnswered;
        this.support = support;
        this.decisions = decisions;
        this.documents = documents;
    }

    @Action(description = "Open the application file the origination system sent", post = {"fileOpen"}, cost = 0.02, canRerun = false)
    public ApplicationFile openApplicationFile(Referral referral) {
        StoryLedger ledger = support.ledger(referral.caseId());
        ledger.note("Referral", referral.note());
        // The file crossed with the referral: read its figures as the origination system recorded them.
        String all = referral.note() + "\n" + String.join("\n", referral.file());
        ApplicationFile file = new ApplicationFile(referral.caseId(), referral.store(), referral.applicationId(), referral.note(),
                amount(all, "([\\d,]+) USD unsecured personal loan"), amount(all, "instalment ([\\d,]+) USD a month"),
                amount(all, "Income ([\\d,]+) USD a month"),
                StorySupport.extract(rows(all), LoanIntakeAgent.EMPLOYMENT_TYPE + "([\\w-]+)").orElse("unknown"),
                amount(all, "reserves cover (\\d+) months"), amount(all, "score (\\d{3})"), amount(all, "monthly obligations ([\\d,]+) USD"),
                amount(all, LoanIntakeAgent.LARGEST_DEPOSIT + "([\\d,]+) USD"),
                StorySupport.extract(rows(all), LoanIntakeAgent.LARGEST_DEPOSIT + "[\\d,]+ USD on (\\d{4}-\\d{2}-\\d{2})").orElse(""),
                amount(all, LoanIntakeAgent.LONGEST_GAP + "(\\d+) days"),
                StorySupport.extract(rows(all), LoanIntakeAgent.LONGEST_GAP + "\\d+ days \\(([^)]+)\\)").orElse(""),
                null, List.of(), referral.precedents(), false);
        ledger.step(PERSONA, "openApplicationFile", "Opened the application file the origination system sent: income, assets, employment and credit, "
                        + "with no document to ask for again.", support.effort("underwriter", "open-application-file"), StoryLedger.Kind.WORK);
        return file;
    }

    @Action(description = "Retrieve the underwriting policy for this application", pre = {"fileOpen"},
            post = {"policyKnown", "policyUnavailable"}, cost = 0.02, canRerun = false)
    public PolicyLookup retrievePolicy(ApplicationFile file, @Provided Ai ai) {
        StoryLedger ledger = support.ledger(file.caseId());
        List<StoreRecord> policies = support.store(file.store())
                .searchKnowledge(file.caseId(), AGENT_ID, "unsecured personal loan underwriting policy " + file.employment() + " borrowers conditions credit score DTI")
                .stream().filter(r -> StorySupport.extract(List.of(r), StorySupport.POLICY_ID).filter(id -> id.startsWith("LEND-")).isPresent()).toList();
        // The situation as the file establishes it: the loan's terms from the referral, the employment type as verified.
        String situation = "Unsecured personal loan of " + String.format("%,d", file.amountUsd()) + " USD; the borrower is "
                + file.employment() + " (from the employment verification). " + file.referral();
        PolicyChoice choice = policies.isEmpty() ? new PolicyChoice(null, "no underwriting policies retrieved")
                : decisions.decideUnlessClear(file.caseId(), PERSONA, "decisions/lending-policy", Map.of(),
                "Application: " + situation + "\n" + StorySupport.asContext("Policies", policies), PolicyChoice.class,
                // Settled by the policies' own scope when exactly one covers this borrower and amount; otherwise the model weighs them.
                () -> onlyCovering(policies, file).map(id -> new PolicyChoice(id, "its scope covers a " + file.employment() + " borrower and a loan of "
                        + String.format("%,d", file.amountUsd()) + " USD")),
                "exactly one policy's scope covers the application",
                () -> policies.stream().filter(r -> covers(r.text(), file)).findFirst()
                        .flatMap(r -> StorySupport.extract(List.of(r), StorySupport.POLICY_ID))
                        .map(id -> new PolicyChoice(id, "its scope covers a " + file.employment() + " borrower and a loan of "
                                + String.format("%,d", file.amountUsd()) + " USD"))
                        .orElse(new PolicyChoice(null, "no retrieved policy covers a " + file.employment() + " borrower for this amount")), ai);
        String chosen = blankToNull(choice.policy());
        List<StoreRecord> used = chosen == null ? List.of() : policies.stream().filter(r -> StorySupport.extract(List.of(r), StorySupport.POLICY_ID).filter(chosen::equals).isPresent()).toList();
        support.found(ledger, "knowledge base", used);
        if (chosen != null) {
            // Why each other policy is not it, from that policy's own scope: whom it covers.
            policies.stream().filter(r -> !used.contains(r)).forEach(r -> support.passedOver(ledger, "knowledge base", r,
                    StorySupport.extract(List.of(r), StorySupport.POLICY_ID).orElse("another policy") + " covers "
                            + StorySupport.extract(List.of(r), "(?:only for|to|for) ([\\w-]+) borrowers").map(t -> t + " borrowers").orElse("a different borrower")
                            + "; this borrower is " + file.employment()));
        }
        support.traceDecisions(support.store(file.store()), file.caseId(), AGENT_ID, file.applicationId(), PERSONA);
        if (chosen != null && !used.isEmpty()) {
            support.recordEvent(support.store(file.store()), file.caseId(), AGENT_ID, file.applicationId(), POLICY, null,
                    "Apply the underwriting policy for " + file.applicationId(), chosen + ": " + choice.reason());
        }
        ledger.step(PERSONA, "retrievePolicy",
                chosen == null || used.isEmpty() ? "No underwriting policy found for this application."
                        : "Chose policy " + chosen + " from " + policies.size() + " retrieved: " + choice.reason(),
                support.effort("underwriter", "retrieve-policy"), StoryLedger.Kind.CONTEXT);
        return chosen == null || used.isEmpty() ? new PolicyLookup(file.caseId(), null, List.of(), null, choice.reason())
                : new PolicyLookup(file.caseId(), chosen, used, used.getFirst().writtenBy(), choice.reason());
    }

    @Action(description = "Escalate to credit risk for the policy that applies", pre = {"policyUnavailable"},
            post = {"policyKnown"}, cost = 0.40, canRerun = false)
    public PolicyLookup escalateForPolicy(ApplicationFile file, PolicyLookup lookup) {
        StoryLedger ledger = support.ledger(file.caseId());
        ledger.say(PERSONA, "I need credit risk to send the policy for this application.");
        ledger.step(PERSONA, "escalateForPolicy", "Waited on credit risk to send the applicable policy.",
                support.effort("underwriter", "escalate-for-policy"), StoryLedger.Kind.ASK);
        // The simulated credit-risk team owns the policy documents and sends the one that covers the application; in the
        // workshop nobody answers, so with no written policy in reach the call goes to a human underwriter.
        Optional<KnowledgeDocuments.Document> doc = escalationsAnswered
                ? documents.all().stream().filter(d -> covers(d.markdown(), file)).findFirst() : Optional.empty();
        List<StoreRecord> sent = doc.map(d -> d.chunks().stream().map(c -> new StoreRecord(d.key(), c, CREDIT_RISK, "", d.filename())).toList()).orElse(List.of());
        String policy = doc.map(KnowledgeDocuments.Document::key).orElse(null);
        ledger.say("Credit risk", policy == null ? (escalationsAnswered ? "There is no written policy for this; refer it to a human underwriter."
                : "We'll look at it; refer the application to a human underwriter for now.") : "Attached: " + policy + ".");
        PolicyLookup confirmed = new PolicyLookup(file.caseId(), policy, sent, CREDIT_RISK, policy == null ? "no written policy covers this application" : SENT_ON_REQUEST);
        support.recordEvent(support.store(file.store()), file.caseId(), AGENT_ID, file.applicationId(), POLICY, null,
                "Apply the underwriting policy for " + file.applicationId(), (policy == null ? "none" : policy) + ": " + confirmed.reason());
        return confirmed;
    }

    @Action(description = "Check the file against the policy's conditions, and what the borrower already explained",
            pre = {"fileOpen", "policyKnown"}, post = {"flagsReviewed", "explanationsInHand", "conditionsNeeded"}, cost = 0.05, canRerun = false)
    public ApplicationFile reviewFlags(ApplicationFile file, PolicyLookup lookup, @Provided Ai ai) {
        StoryLedger ledger = support.ledger(file.caseId());
        ContextStore store = support.store(file.store());
        // What needs explaining, by the conditions the policy text states, applied to the file's figures.
        Optional<LendingConditions> conditions = LendingConditions.parse(policyText(lookup));
        List<Flag> flags = new ArrayList<>();
        conditions.ifPresent(c -> {
            if (c.depositNeedsSource(file.depositUsd(), file.monthlyIncome())) flags.add(new Flag("deposit",
                    "the " + String.format("%,d", file.depositUsd()) + " USD deposit on " + file.depositDate() + " (" + Math.round(file.depositUsd() * 100.0 / file.monthlyIncome())
                            + "% of monthly income, above " + c.depositPercent() + "%)",
                    "a letter of explanation and proof of the source of the " + String.format("%,d", file.depositUsd()) + " USD deposit on " + file.depositDate()));
            if (c.gapNeedsExplanation(file.gapDays())) flags.add(new Flag("employment gap",
                    "the " + file.gapDays() + "-day employment gap (" + file.gapPeriod() + "; above " + c.gapDays() + " days)",
                    "a letter of explanation for the employment gap from " + file.gapPeriod()));
        });
        // What the borrower already said about this application: case memory, or the origination system's records in the
        // application's conversation where memory has nothing for an item.
        List<StoreRecord> recalled = flags.isEmpty() ? List.of()
                : store.recallCase(file.caseId(), AGENT_ID, "loan application " + file.applicationId() + " borrower explanations deposit source employment gap evidence")
                .stream().filter(r -> r.text().contains(file.applicationId())).toList();
        List<StoreRecord> said = recalled;
        String source = "case memory";
        if (!flags.isEmpty() && flags.stream().anyMatch(f -> explanation(recalled, f.id()).isEmpty()) && store.kind() != ContextStore.Kind.NONE) {
            said = support.readBack(store, file.caseId(), AGENT_ID, file.applicationId(),
                            // Ready once every flagged item's explanation is readable: those turns are written before the referral.
                            turns -> flags.stream().allMatch(f -> explanation(turns.stream().filter(t -> t.isEvent(LoanIntakeAgent.AGENT_ID, LoanIntakeAgent.EXPLAINED))
                                    .map(t -> new StoreRecord("", t.output(), t.writtenBy(), "", null)).toList(), f.id()).isPresent()))
                    .stream().filter(t -> t.isEvent(LoanIntakeAgent.AGENT_ID, LoanIntakeAgent.EXPLAINED))
                    .map(t -> new StoreRecord("", t.output(), t.writtenBy(), "", null)).toList();
            source = "application conversation";
        }
        support.found(ledger, source, said);
        List<StoreRecord> explanations = said;
        FlagReading reading = flags.isEmpty() ? new FlagReading(List.of(), List.of())
                // Each explanation is read from the origination system's own record of it, which states the explanation and
                // the evidence received: whether an item is explained is an exact check, not a judgement.
                : decisions.decideByRule(file.caseId(), PERSONA, "decisions/lending-flags",
                Map.of("flags", String.join("; ", flags.stream().map(f -> f.id() + ": " + f.description()).toList())),
                StorySupport.asContext("What the borrower already explained", explanations),
                () -> {
                    List<String> explained = flags.stream().map(Flag::id).filter(id -> explanation(explanations, id).isPresent()).toList();
                    return new FlagReading(explained, flags.stream().map(Flag::id).filter(id -> !explained.contains(id)).toList());
                }, "the explanation and its evidence are on record for the item");
        support.traceDecisions(store, file.caseId(), AGENT_ID, file.applicationId(), PERSONA);
        List<String> explained = reading.explained() == null ? List.of() : reading.explained().stream().map(String::toLowerCase).toList();
        List<Flag> reviewed = new ArrayList<>();
        for (Flag f : flags) {
            Optional<Explanation> why = explained.contains(f.id()) ? explanation(explanations, f.id()) : Optional.empty();
            reviewed.add(why.map(e -> f.explainedBy(FROM_ORIGINATION + ": " + e.text() + " (written by " + e.writtenBy() + ")", e.kind(), e.evidence())).orElse(f));
        }
        long open = reviewed.stream().filter(f -> !f.inHand()).count();
        ledger.step(PERSONA, "reviewFlags", flags.isEmpty() ? "Nothing in the file needs explaining under the policy's conditions."
                        : flags.size() + (flags.size() == 1 ? " item needs" : " items need") + " explaining under the policy: " + (reviewed.size() - open)
                        + " already explained, with evidence, at origination; " + open + " still open.",
                support.effort("underwriter", "review-flags"), StoryLedger.Kind.CONTEXT);
        return file.withFlags(reviewed);
    }

    /** A borrower's explanation of a flagged item, as recorded: the source, its kind and the evidence. */
    record Explanation(String text, String kind, String evidence, String writtenBy) {}

    /** "Deposit: the sale of the borrower's car (a vehicle sale); evidence received: the bill of sale" */
    private static final java.util.regex.Pattern EXPLAINED_ITEM = java.util.regex.Pattern.compile(
            "(Deposit|Employment gap): ([^;]+?)(?: \\(([^)]+)\\))?; evidence received: ([^.;]+)");

    /** The borrower's explanation of a flagged item, with its evidence, among what was said: none if it is not there. */
    static Optional<Explanation> explanation(List<StoreRecord> said, String flagId) {
        for (StoreRecord r : said) {
            java.util.regex.Matcher m = EXPLAINED_ITEM.matcher(r.text());
            while (m.find()) {
                if (m.group(1).equalsIgnoreCase(flagId)) {
                    return Optional.of(new Explanation(m.group(), m.group(3), m.group(4).trim(), r.writtenBy()));
                }
            }
        }
        return Optional.empty();
    }

    @Action(description = "Send the open conditions back to the borrower and take the answers", pre = {"conditionsNeeded"},
            post = {"explanationsInHand"}, cost = 0.50, canRerun = false)
    public ApplicationFile issueConditions(ApplicationFile file) {
        StoryLedger ledger = support.ledger(file.caseId());
        ContextStore store = support.store(file.store());
        // The simulated borrower answers with what they already told the origination system, and sends the proof again.
        LoanApplications.Application app = support.loans().forApplication(file.applicationId()).orElseThrow();
        List<Flag> flags = new ArrayList<>(file.flags());
        List<Exchange> asked = new ArrayList<>(file.conditions());
        for (int i = 0; i < flags.size(); i++) {
            Flag f = flags.get(i);
            if (f.inHand()) continue;
            boolean deposit = f.id().equals("deposit");
            String question = "Condition: please send " + f.condition() + ".";
            String answer = deposit
                    ? "I already explained this when I applied: it's from " + app.deposit().source() + ". Sending " + app.deposit().evidence() + " again."
                    : "I told your loan officer: it was " + app.gap().reason() + ". Here is " + app.gap().evidence() + " again.";
            ledger.say(PERSONA, question);
            ledger.say("Borrower", answer);
            support.recordEvent(store, file.caseId(), AGENT_ID, file.applicationId(), REASKED,
                    "not in the context the underwriter had: the borrower's explanation of " + f.description(), lastSaid(file.referral(), asked), question);
            asked.add(new Exchange(question, answer, f.description()));
            ledger.step(PERSONA, "issueConditions", "Condition sent to the borrower: " + f.condition() + ". Waited for the letter and the proof.",
                    support.effort("underwriter", "issue-condition"), StoryLedger.Kind.ASK);
            ledger.count("conditionsIssued", 1);
            ledger.count("borrowerReexplained", 1);
            // The underwriter's own reading of the answer: the source, its kind and the evidence received.
            String kind = deposit ? app.deposit().kind() : null;
            String evidence = deposit ? app.deposit().evidence() : app.gap().evidence();
            flags.set(i, f.explainedBy(FROM_CONDITION + ": " + answer, kind, evidence));
        }
        support.record(store, file.caseId(), AGENT_ID, file.applicationId(), lastSaid(file.referral(), asked), "Thank you. We have your letters and proof.");
        return file.withConditions(flags, asked);
    }

    private static String lastSaid(String referral, List<Exchange> asked) {
        return asked.isEmpty() ? referral : asked.getLast().answer();
    }

    @Action(description = "Accept the evidence behind each explanation: listed in the policy, or covered by a credit-risk ruling",
            pre = {"explanationsInHand"}, post = {"evidenceAccepted", "rulingNeeded"}, cost = 0.05, canRerun = false)
    public ApplicationFile acceptEvidence(ApplicationFile file, PolicyLookup lookup) {
        StoryLedger ledger = support.ledger(file.caseId());
        ContextStore store = support.store(file.store());
        Optional<LendingConditions> conditions = LendingConditions.parse(policyText(lookup));
        List<Flag> flags = new ArrayList<>(file.flags());
        List<String> notes = new ArrayList<>();
        for (int i = 0; i < flags.size(); i++) {
            Flag f = flags.get(i);
            if (conditions.map(c -> c.listsEvidence(f.id(), f.evidence())).orElse(false)) {
                flags.set(i, clear(file, f, f.evidence() + " is listed as acceptable in " + lookup.policy()));
                notes.add(f.evidence() + " for the " + f.id() + ": listed in " + lookup.policy());
                continue;
            }
            // Not listed: a ruling from credit risk on this kind of evidence, shared as precedent, would cover it.
            List<StoreRecord> rulings = store.searchKnowledge(file.caseId(), AGENT_ID, RULING_MARKER + lookup.policy() + " acceptable "
                            + ITEM_PHRASE.get(f.id()) + " " + f.evidence() + " " + (f.kind() == null ? "" : f.kind()) + " " + precedentTag(file.precedents()))
                    .stream().filter(r -> r.text().contains(RULING_MARKER) && r.text().contains(precedentTag(file.precedents()))).toList();
            if (rulings.isEmpty()) {
                notes.add(f.evidence() + " for the " + f.id() + ": not listed in " + lookup.policy() + ", and no credit-risk ruling covers it");
                continue;
            }
            PrecedentFit fit = decisions.decideByRule(file.caseId(), PERSONA, "decisions/lending-precedent",
                    Map.of("evidence", f.evidence(), "item", ITEM_PHRASE.get(f.id()), "kind", f.kind() == null ? "not stated" : f.kind()),
                    StorySupport.asContext("Credit-risk rulings", rulings),
                    () -> rulings.stream().filter(r -> covers(r.text(), lookup.policy(), f)).findFirst()
                            .map(r -> new PrecedentFit(true, "the ruling accepts " + f.evidence() + " as " + ITEM_PHRASE.get(f.id()) + (f.kind() == null ? "" : " for " + f.kind())))
                            .orElse(new PrecedentFit(false, "no ruling accepts " + f.evidence() + " for this")),
                    "the ruling names the same policy, evidence and kind of source");
            Optional<StoreRecord> used = Boolean.TRUE.equals(fit.accepts()) ? rulings.stream().filter(r -> covers(r.text(), lookup.policy(), f)).findFirst() : Optional.empty();
            if (used.isPresent()) {
                support.found(ledger, "knowledge base", List.of(used.get()));
                flags.set(i, clear(file, f, f.evidence() + " is accepted under " + PRECEDENT_FROM + used.get().writtenBy() + ": " + ruling(used.get().text())));
                notes.add(f.evidence() + " for the " + f.id() + ": accepted under a credit-risk ruling in shared knowledge");
            } else {
                rulings.forEach(r -> support.passedOver(ledger, "knowledge base", r, fit.reason() == null ? "does not cover this evidence" : fit.reason()));
                notes.add(f.evidence() + " for the " + f.id() + ": not covered by the rulings found");
            }
        }
        support.traceDecisions(store, file.caseId(), AGENT_ID, file.applicationId(), PERSONA);
        ledger.step(PERSONA, "acceptEvidence", notes.isEmpty() ? "No evidence to check." : "Checked the evidence: " + String.join("; ", notes) + ".",
                support.effort("underwriter", "accept-evidence"), StoryLedger.Kind.CONTEXT);
        return file.withEvidenceChecked(flags);
    }

    @Action(description = "Ask credit risk to rule on evidence the policy does not list, and share the ruling as precedent",
            pre = {"rulingNeeded"}, post = {"evidenceAccepted"}, cost = 0.40, canRerun = false)
    public ApplicationFile requestRuling(ApplicationFile file, PolicyLookup lookup) {
        StoryLedger ledger = support.ledger(file.caseId());
        ContextStore store = support.store(file.store());
        List<Flag> flags = new ArrayList<>(file.flags());
        for (int i = 0; i < flags.size(); i++) {
            Flag f = flags.get(i);
            if (!f.open()) continue;
            String question = "Is " + f.evidence() + " acceptable " + ITEM_PHRASE.get(f.id()) + (f.kind() == null ? "" : " for " + f.kind())
                    + " under " + lookup.policy() + "? The policy does not list it.";
            ledger.say(PERSONA, question);
            ledger.step(PERSONA, "requestRuling", "Waited on credit risk to rule on " + f.evidence() + " as " + ITEM_PHRASE.get(f.id()) + ".",
                    support.effort("underwriter", "request-ruling"), StoryLedger.Kind.ASK);
            ledger.count("rulingsRequested", 1);
            if (!escalationsAnswered) {
                // Nobody answers (the workshop): the condition stays with credit risk, and the credit call can only refer.
                ledger.say("Credit risk", "We'll review it and get back to you.");
                support.recordEvent(store, file.caseId(), AGENT_ID, file.applicationId(), RULING, null, question, RULING_PENDING);
                flags.set(i, f.cleared(RULING_PENDING + ": " + f.evidence() + " for the " + f.id()));
                continue;
            }
            // The simulated credit-risk team rules on the kind of evidence, not on the borrower: no application, no amounts.
            String ruling = RULING_MARKER + lookup.policy() + ": " + f.evidence() + " is acceptable " + ITEM_PHRASE.get(f.id())
                    + (f.kind() == null ? "" : " for " + f.kind()) + ". " + precedentTag(file.precedents());
            ledger.say("Credit risk", "Yes: " + f.evidence() + " is acceptable " + ITEM_PHRASE.get(f.id()) + (f.kind() == null ? "" : " for " + f.kind()) + ". It applies to later applications too.");
            support.recordEvent(store, file.caseId(), AGENT_ID, file.applicationId(), RULING, null, question, ruling(ruling) + " (" + RULED_ON_REQUEST + ")");
            flags.set(i, clear(file, f, f.evidence() + " is accepted under a " + RULING + " (" + RULED_ON_REQUEST + "): " + ruling(ruling)));
            share(file, ledger, store, ruling);
        }
        return file.withEvidenceChecked(flags);
    }

    /**
     * Shares a ruling as precedent: written as the underwriter's memory, then, if it is general enough to apply beyond
     * this application, promoted into the shared knowledge base, where any agent's knowledge search finds it.
     */
    private void share(ApplicationFile file, StoryLedger ledger, ContextStore store, String ruling) {
        if (store.kind() == ContextStore.Kind.NONE) {
            ledger.step(PERSONA, "requestRuling", "The ruling stays in this application's correspondence: there is nowhere to share it.",
                    support.effort("underwriter", "share-precedent"), StoryLedger.Kind.CONTEXT);
            return;
        }
        // What may leave this application is a privacy check: a deterministic one, not a model's judgement.
        ShareJudgement judgement = decisions.decideByRule(file.caseId(), PERSONA, "decisions/lending-share-ruling", Map.of(),
                StorySupport.asContext("Ruling", List.of(new StoreRecord("", ruling, CREDIT_RISK, "", null))),
                () -> APPLICATION_OR_AMOUNT.matcher(ruling).find() ? new ShareJudgement(false, "it names an application or an amount")
                        : new ShareJudgement(true, "it rules on a kind of evidence, with no borrower, application or amount in it"),
                "no application id or amount in the ruling");
        support.traceDecisions(store, file.caseId(), AGENT_ID, file.applicationId(), PERSONA);
        Optional<String> id = store.remember(file.caseId(), AGENT_ID, ruling);
        boolean promoted = Boolean.TRUE.equals(judgement.share()) && id.map(m -> store.promote(file.caseId(), AGENT_ID, List.of(m))).orElse(false);
        if (promoted) ledger.count("rulingsShared", 1);
        ledger.step(PERSONA, "requestRuling", id.isEmpty() ? support.writeOutcome(file.caseId(), AGENT_ID, file.store(), false, "the ruling")
                        : promoted ? "Wrote the ruling as a memory and promoted it into the shared knowledge base: " + judgement.reason() + "."
                        : Boolean.TRUE.equals(judgement.share()) ? "Wrote the ruling as a memory; promoting it into shared knowledge failed."
                        : "Wrote the ruling as a memory, not shared: " + judgement.reason() + ".",
                support.effort("underwriter", "share-precedent"), StoryLedger.Kind.CONTEXT);
    }

    private Flag clear(ApplicationFile file, Flag f, String accepted) {
        String how = f.explained() + "; " + EVIDENCE_ACCEPTED + accepted;
        // The item cleared is named in the tag too, which every reader gets back, whatever the store returns as the input.
        support.recordEvent(support.store(file.store()), file.caseId(), AGENT_ID, file.applicationId(), CONDITION_CLEARED, f.id(),
                "Clear the condition on " + f.description(), how);
        return f.cleared(how);
    }

    /** The phrase a ruling uses for each flagged item. */
    static final Map<String, String> ITEM_PHRASE = Map.of("deposit", "evidence of a deposit's source", "employment gap", "evidence for an employment gap");
    private static final java.util.regex.Pattern APPLICATION_OR_AMOUNT = java.util.regex.Pattern.compile("APP-\\d+|\\d[\\d,]* USD");

    /** How a ruling is tagged with the set of rulings it belongs to: rulings outside the current set are not precedent. */
    public static String precedentTag(String precedents) {
        return "[precedent set " + (precedents == null ? "none" : precedents) + "]";
    }

    /** Whether a ruling's text accepts this flag's evidence (and kind of source) under the policy. */
    static boolean covers(String rulingText, String policy, Flag f) {
        String t = rulingText.toLowerCase();
        return t.contains(RULING_MARKER.toLowerCase() + String.valueOf(policy).toLowerCase()) && t.contains(f.evidence().toLowerCase())
                && t.contains(ITEM_PHRASE.get(f.id()).toLowerCase()) && (f.kind() == null || t.contains(f.kind().toLowerCase()));
    }

    /** A ruling's text without its precedent-set tag. */
    public static String ruling(String text) {
        return text.replaceAll("\\s*\\[precedent set [^]]*]", "").trim();
    }

    private static String policyText(PolicyLookup lookup) {
        return String.join("\n\n", lookup.text().stream().map(StoreRecord::text).toList());
    }

    @Action(description = "Compute debt-to-income from the file's figures", pre = {"evidenceAccepted"}, post = {"affordabilityAssessed"}, cost = 0.10, canRerun = false)
    public Affordability assessAffordability(ApplicationFile file) {
        ContextStore store = support.store(file.store());
        int obligations = file.monthlyDebts() + file.instalment();
        double dti = obligations * 100.0 / file.monthlyIncome();
        String working = "DTI " + String.format("%.1f", dti) + "% = (" + String.format("%,d", file.monthlyDebts()) + " obligations + " + String.format("%,d", file.instalment())
                + " instalment) / " + String.format("%,d", file.monthlyIncome()) + " income; score " + file.creditScore() + "; reserves " + file.reservesMonths() + " months";
        support.recordEvent(store, file.caseId(), AGENT_ID, file.applicationId(), AFFORDABILITY, null,
                "Compute debt-to-income for " + file.applicationId(), working);
        support.ledger(file.caseId()).step(PERSONA, "assessAffordability", working + ".", support.effort("underwriter", "assess-affordability"), StoryLedger.Kind.WORK);
        return new Affordability(file.caseId(), dti, file.creditScore(), file.reservesMonths(), working);
    }

    @AchievesGoal(description = "The credit call is made")
    @Action(description = "Make the credit call against the policy's thresholds", pre = {"affordabilityAssessed", "policyKnown"}, post = {"creditCallMade"},
            cost = 0.05, canRerun = false)
    public CreditCall makeCreditCall(ApplicationFile file, Affordability affordability, PolicyLookup lookup, @Provided Ai ai) {
        StoryLedger ledger = support.ledger(file.caseId());
        ContextStore store = support.store(file.store());
        String policyText = String.join("\n\n", lookup.text().stream().map(StoreRecord::text).toList());
        Optional<LendingThresholds> thresholds = LendingThresholds.parse(policyText);
        String context = "Figures in the application file: " + affordability.working() + ".\n" + StorySupport.asContext("Policy " + lookup.policy(), lookup.text());
        java.util.function.Supplier<CallJudgement> byRule = () -> thresholds.map(t -> new CallJudgement(t.apply(affordability.creditScore(), affordability.dtiPercent(), affordability.reservesMonths()),
                        lookup.policy() + ": " + t.describe(),
                        t.explain(affordability.creditScore(), affordability.dtiPercent(), affordability.reservesMonths())))
                .orElse(new CallJudgement("refer", "none: the policy text states no thresholds the underwriter could apply",
                        "no thresholds to apply; refer to a human underwriter"));
        // Settled by rule when there is no policy (the call is refer) or the policy's thresholds parse (an exact comparison);
        // the model reads the policy only when it states thresholds in a form the rule cannot apply.
        CallJudgement judgement = decisions.decideUnlessClear(file.caseId(), PERSONA, "decisions/lending-credit-call", Map.of(), context, CallJudgement.class,
                () -> lookup.policy() == null || thresholds.isPresent() ? Optional.of(byRule.get()) : Optional.empty(),
                lookup.policy() == null ? "no written policy: the call is refer" : "the policy's thresholds, compared exactly with the file's figures",
                byRule, ai);
        // A condition still waiting on a credit-risk ruling leaves only one call: refer, saying which.
        List<String> pending = (file.flags() == null ? List.<Flag>of() : file.flags()).stream().map(Flag::clearedFrom).filter(c -> c != null && c.startsWith(RULING_PENDING)).toList();
        String decision = lookup.policy() == null || !pending.isEmpty() ? "refer" : normalize(judgement.decision());
        if (!pending.isEmpty()) judgement = new CallJudgement("refer", judgement.thresholdsApplied(), String.join("; ", pending) + ": refer to a human underwriter");
        boolean sent = CREDIT_RISK.equals(lookup.writtenBy());
        String outcome = file.applicationId() + ": " + decision + " under " + (lookup.policy() == null ? "no written policy" : lookup.policy())
                + (sent && lookup.policy() != null ? " (" + SENT_ON_REQUEST + ")" : "") + ". " + judgement.reason() + ".";
        support.traceDecisions(store, file.caseId(), AGENT_ID, file.applicationId(), PERSONA);
        // The record states the thresholds applied and the factors the call rested on, as the policy requires.
        support.recordEvent(store, file.caseId(), AGENT_ID, file.applicationId(), CREDIT_CALL, null,
                "Make the credit call on " + file.applicationId(), outcome + " " + THRESHOLDS_APPLIED + judgement.thresholdsApplied()
                        + ". " + FACTORS + affordability.factors() + ".");
        store.rememberLater(file.caseId(), AGENT_ID, "Loan application " + file.applicationId() + " underwritten: " + outcome.substring(outcome.indexOf(": ") + 2));
        ledger.step(PERSONA, "makeCreditCall", outcome, support.effort("underwriter", "make-credit-call"), StoryLedger.Kind.WORK);
        ledger.count("refer".equals(decision) ? "handedToPerson" : "decided", 1);
        ledger.outcome(ledger.outcome() == null ? outcome : ledger.outcome() + " " + outcome);
        List<String> facts = new ArrayList<>(List.of(
                file.referral(),
                "Figures: " + affordability.working(),
                "Policy: " + lookup.policy() + " (" + lookup.reason() + ")",
                "Credit call: " + decision + ". " + judgement.reason()));
        (file.flags() == null ? List.<Flag>of() : file.flags()).forEach(f -> facts.add("Condition on " + f.description() + ": " + f.clearedFrom()));
        int reexplained = ledger.counters().getOrDefault("borrowerReexplained", 0);
        facts.add(reexplained == 0 ? "The borrower was not asked to explain anything again after the referral"
                : "After the referral the borrower had to explain " + reexplained + " thing" + (reexplained == 1 ? "" : "s") + " again, with the proof");
        List<String> gaps = lookup.policy() == null ? List.of("No written underwriting policy covers this application") : List.of();
        ledger.handToWriter(new ResponseWriterAgent.WritingTask(file.caseId(), PERSONA, "message to the borrower of " + file.applicationId(),
                support.render("writer/borrower-credit-call", Map.of("reexplained", reexplained, "decision", decision)), facts, gaps,
                "Your application " + file.applicationId() + " has been reviewed: " + decision + "."));
        return new CreditCall(file.caseId(), file.applicationId(), decision, judgement.reason());
    }

    // ------------------------------------------------------------------ rules over the context

    /**
     * Whether a policy's text covers this application: its scope names the borrower's employment type ("to salaried
     * borrowers", "for self-employed borrowers"), and the loan is within any amount the scope states ("up to 50,000 USD").
     */
    public static boolean covers(String policyText, ApplicationFile file) {
        String text = policyText.toLowerCase();
        if (file.employment() == null || !text.matches("(?s).*\\b(to|for) " + java.util.regex.Pattern.quote(file.employment().toLowerCase()) + " borrowers.*")) return false;
        Optional<Integer> limit = StorySupport.extract(rows(policyText), "up to ([\\d,]+) USD").map(s -> Integer.valueOf(s.replace(",", "")));
        return limit.isEmpty() || file.amountUsd() <= limit.get();
    }

    /** The one policy among those retrieved whose scope covers the application; empty if none does, or more than one. */
    static Optional<String> onlyCovering(List<StoreRecord> policies, ApplicationFile file) {
        List<String> ids = policies.stream().filter(r -> covers(r.text(), file))
                .map(r -> StorySupport.extract(List.of(r), StorySupport.POLICY_ID)).flatMap(Optional::stream).distinct().toList();
        return ids.size() == 1 ? Optional.of(ids.getFirst()) : Optional.empty();
    }

    /** A credit-call answer as approve, decline or refer, however the model worded it ("Approved.", "DECLINE"). */
    public static String normalize(String decision) {
        String d = decision == null ? "" : decision.trim().toLowerCase();
        if (d.startsWith("approv")) return "approve";
        if (d.startsWith("declin") || d.startsWith("reject") || d.startsWith("deny")) return "decline";
        return "refer";
    }

    private static List<StoreRecord> rows(String text) {
        return List.of(new StoreRecord("", text, "", "", null));
    }

    private static int amount(String text, String regex) {
        return StorySupport.extract(rows(text), regex).map(s -> Integer.parseInt(s.replace(",", ""))).orElse(0);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() || value.equalsIgnoreCase("null") || value.equalsIgnoreCase("unknown") ? null : value;
    }

    // ------------------------------------------------------------------ conditions

    @Condition(name = "fileOpen")
    public boolean fileOpen(ApplicationFile file) {
        return file != null;
    }

    @Condition(name = "flagsReviewed")
    public boolean flagsReviewed(ApplicationFile file) {
        return file != null && file.flags() != null;
    }

    @Condition(name = "explanationsInHand")
    public boolean explanationsInHand(ApplicationFile file) {
        return flagsReviewed(file) && file.flags().stream().allMatch(Flag::inHand);
    }

    @Condition(name = "conditionsNeeded")
    public boolean conditionsNeeded(ApplicationFile file) {
        return flagsReviewed(file) && !explanationsInHand(file);
    }

    @Condition(name = "evidenceAccepted")
    public boolean evidenceAccepted(ApplicationFile file) {
        return explanationsInHand(file) && file.evidenceChecked() && file.flags().stream().noneMatch(Flag::open);
    }

    @Condition(name = "rulingNeeded")
    public boolean rulingNeeded(ApplicationFile file) {
        return explanationsInHand(file) && file.evidenceChecked() && file.flags().stream().anyMatch(Flag::open);
    }

    @Condition(name = "affordabilityAssessed")
    public boolean affordabilityAssessed(Affordability affordability) {
        return affordability != null;
    }

    @Condition(name = "policyKnown")
    public boolean policyKnown(PolicyLookup lookup) {
        // Known once a policy was found, or once credit risk answered (with a policy or with none: then the call is refer).
        return lookup != null && (lookup.policy() != null || CREDIT_RISK.equals(lookup.writtenBy()));
    }

    @Condition(name = "policyUnavailable")
    public boolean policyUnavailable(PolicyLookup lookup) {
        return lookup != null && !policyKnown(lookup);
    }

    @Condition(name = "creditCallMade")
    public boolean creditCallMade(CreditCall call) {
        return call != null;
    }
}
