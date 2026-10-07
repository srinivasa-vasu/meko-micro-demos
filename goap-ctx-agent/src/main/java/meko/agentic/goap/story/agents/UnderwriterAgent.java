package meko.agentic.goap.story.agents;

import com.embabel.agent.api.annotation.AchievesGoal;
import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.Agent;
import com.embabel.agent.api.annotation.Condition;
import com.embabel.agent.api.annotation.Provided;
import com.embabel.agent.api.common.Ai;
import com.embabel.agent.api.common.PlannerType;
import meko.agentic.goap.story.KnowledgeDocuments;
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
 * Loan story, phase 2: your credit underwriter, a separate agent process that receives only the referral (the
 * application id and the loan terms).
 * <p>
 * {@code openApplicationFile} posts every "known"/"unknown" condition, so the first plan optimistically assumes the
 * checks' results will be there. After it runs, the planner replans against what was actually recovered: each result
 * still unknown makes asking the borrower again, or redoing a check, necessary. The credit call is a gate decision over
 * the figures in the file and the thresholds in the policy text the underwriter has; nothing about the outcome is fixed.
 */
@Agent(planner = PlannerType.GOAP, description = "Credit underwriter that makes the credit call on a referred personal-loan application")
public class UnderwriterAgent {
    public static final String AGENT_ID = "lending:underwriter";
    static final String PERSONA = "Credit underwriter";

    /**
     * One thing the borrower had to do again after the referral.
     *
     * @param unknown what the underwriter did not know, which is why it asked
     */
    public record Exchange(String question, String answer, String unknown) {}

    /**
     * @param referral what crossed the boundary from the origination system
     * @param asked    what the borrower had to do again after the referral, in order
     */
    /**
     * @param amountUsd  the loan amount, a term the referral carries
     * @param employment the borrower's employment type as the employment verification recorded it, e.g. "salaried"
     */
    public record ApplicationFile(String caseId, ContextStore.Kind store, String applicationId, String referral, Integer amountUsd,
                                  Integer instalment, Integer monthlyIncome, Boolean employmentConfirmed, String employment,
                                  Integer reservesMonths, Integer creditScore, Integer monthlyDebts, List<Exchange> asked) {
        ApplicationFile withIncome(int income, Exchange e) { return new ApplicationFile(caseId, store, applicationId, referral, amountUsd, instalment, income, employmentConfirmed, employment, reservesMonths, creditScore, monthlyDebts, plus(e)); }
        ApplicationFile withReserves(int months, Exchange e) { return new ApplicationFile(caseId, store, applicationId, referral, amountUsd, instalment, monthlyIncome, employmentConfirmed, employment, months, creditScore, monthlyDebts, plus(e)); }
        ApplicationFile withEmployment(String type) { return new ApplicationFile(caseId, store, applicationId, referral, amountUsd, instalment, monthlyIncome, true, type, reservesMonths, creditScore, monthlyDebts, asked); }
        ApplicationFile withCredit(int score, int debts, Exchange e) { return new ApplicationFile(caseId, store, applicationId, referral, amountUsd, instalment, monthlyIncome, employmentConfirmed, employment, reservesMonths, score, debts, plus(e)); }
        private List<Exchange> plus(Exchange e) { List<Exchange> all = new ArrayList<>(asked); all.add(e); return List.copyOf(all); }

        /** What the borrower said last: the referral before any re-ask, then their latest answer. */
        String lastSaid() { return asked.isEmpty() ? referral : asked.getLast().answer(); }
    }

    /** How a re-ask is marked in the conversation (see {@link ContextStore.Turn#event}). */
    public static final String REASKED = "re-asked";
    /** Event names of the underwriter's records in the application's conversation. */
    public static final String AFFORDABILITY = "affordability";
    public static final String POLICY = "policy applied";
    public static final String CREDIT_CALL = "credit call";

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
     * @param text   the policy text the underwriter has: retrieved from knowledge, or what credit risk read out
     * @param reason why this policy governs the application, as the agent judged it (or who confirmed it)
     */
    public record PolicyLookup(String caseId, String policy, List<StoreRecord> text, String writtenBy, String reason) {}

    public record CreditCall(String caseId, String applicationId, String decision, String reason) {}

    /** The model's reading of which checks' results the underwriter already has. */
    public record KnownFacts(Integer monthlyIncome, Boolean employmentConfirmed, String employmentType, Integer reservesMonths,
                             Integer creditScore, Integer monthlyDebts, Integer instalment) {}

    public record PolicyChoice(String policy, String reason) {}

    /** The model's credit call over the file's figures and the policy text. */
    public record CallJudgement(String decision, String thresholdsApplied, String reason) {}

    private final StorySupport support;
    private final DecisionService decisions;
    private final KnowledgeDocuments documents;

    public UnderwriterAgent(StorySupport support, DecisionService decisions, KnowledgeDocuments documents) {
        this.support = support;
        this.decisions = decisions;
        this.documents = documents;
    }

    @Action(description = "Open the application file from whatever context is available beyond the referral",
            post = {"fileOpen", "incomeKnown", "incomeUnknown", "assetsKnown", "assetsUnknown", "employmentKnown",
                    "employmentUnknown", "creditKnown", "creditUnknown"},
            cost = 0.02, canRerun = false)
    public ApplicationFile openApplicationFile(Referral referral, @Provided Ai ai) {
        StoryLedger ledger = support.ledger(referral.caseId());
        ContextStore store = support.store(referral.store());
        ledger.note("Referral", referral.note());
        List<StoreRecord> facts = store.recallCase(referral.caseId(), AGENT_ID, "loan application intake income pay stubs bank statements employment credit report score");
        support.found(ledger, "case memory", facts);
        if (facts.isEmpty() && store.kind() != ContextStore.Kind.NONE) {
            // Nothing in case memory (a write that failed, or one the store's extraction kept nothing from). The
            // origination system also recorded each check, verbatim, in the application's conversation, keyed by the
            // application id the referral carries: read the checks from there.
            List<ContextStore.Turn> record = support.readBack(store, referral.caseId(), AGENT_ID, referral.applicationId(),
                    turns -> turns.stream().anyMatch(t -> t.isEvent(LoanIntakeAgent.AGENT_ID, LoanIntakeAgent.REFERRAL)));
            facts = record.stream().filter(t -> t.event().isPresent() && LoanIntakeAgent.AGENT_ID.equals(t.writtenBy()))
                    .map(t -> new StoreRecord("", t.output(), t.writtenBy(), "", null)).toList();
            support.found(ledger, "application conversation", facts);
        }
        String context = "Referral: " + referral.note() + "\n" + StorySupport.asContext("Case memory", facts);
        String all = referral.note() + "\n" + String.join("\n", facts.stream().map(StoreRecord::text).toList());
        KnownFacts known = decisions.decide(referral.caseId(), PERSONA, "decisions/lending-known-facts", Map.of(), context,
                KnownFacts.class, () -> new KnownFacts(
                        amount(all, "Income ([\\d,]+) USD a month"),
                        StorySupport.mentions(rows(all), "employment confirmed") ? Boolean.TRUE : null,
                        StorySupport.extract(rows(all), LoanIntakeAgent.EMPLOYMENT_TYPE + "([\\w-]+)").orElse(null),
                        amount(all, "reserves cover (\\d+) months"),
                        amount(all, "score (\\d{3})"),
                        amount(all, "monthly obligations ([\\d,]+) USD"),
                        amount(all, "instalment ([\\d,]+) USD a month")), ai);
        ApplicationFile file = new ApplicationFile(referral.caseId(), referral.store(), referral.applicationId(), referral.note(),
                // The amount and the instalment are loan terms the referral itself carries.
                amount(referral.note(), "([\\d,]+) USD unsecured personal loan"),
                Optional.ofNullable(positive(known.instalment())).orElse(amount(referral.note(), "instalment ([\\d,]+) USD a month")),
                positive(known.monthlyIncome()), Boolean.TRUE.equals(known.employmentConfirmed()) ? Boolean.TRUE : null,
                blankToNull(known.employmentType()), positive(known.reservesMonths()), positive(known.creditScore()), positive(known.monthlyDebts()), List.of());
        // The judgement goes on the application's record now: the application id is known from the referral.
        support.traceDecisions(store, referral.caseId(), AGENT_ID, referral.applicationId(), PERSONA);
        int count = (incomeKnown(file) ? 1 : 0) + (assetsKnown(file) ? 1 : 0) + (employmentKnown(file) ? 1 : 0) + (creditKnown(file) ? 1 : 0);
        ledger.step(PERSONA, "openApplicationFile", "Opened the application file: " + count + " of 4 checks' results recovered beyond the referral.",
                support.effort("underwriter", "open-application-file"), StoryLedger.Kind.CONTEXT);
        return file;
    }

    @Action(description = "Ask the borrower to send their pay stubs again", pre = {"incomeUnknown"}, post = {"incomeKnown"}, cost = 0.30, canRerun = false)
    public ApplicationFile askForPayStubs(ApplicationFile file) {
        StoryLedger ledger = support.ledger(file.caseId());
        // The simulated borrower re-sends the same documents they gave the origination system.
        LoanApplications.Application app = support.loans().forCase(file.caseId());
        String question = "Could you send your two most recent pay stubs?";
        String answer = "I uploaded them with the application... sending them again.";
        ledger.say(PERSONA, question);
        ledger.say("Borrower", answer);
        ledger.step(PERSONA, "askForPayStubs", "Asked the borrower to send their pay stubs again.", support.effort("underwriter", "ask-pay-stubs"), StoryLedger.Kind.ASK);
        ledger.count("borrowerReasks", 1);
        Exchange exchange = new Exchange(question, answer, "the borrower's income");
        reask(file, exchange);
        recordCheck(file, LoanIntakeAgent.INCOME, exchange.unknown(), "Confirm monthly income from the two pay stubs",
                "Income " + String.format("%,d", app.monthlyIncome()) + " USD a month, confirmed from two pay stubs re-sent by the borrower");
        return file.withIncome(app.monthlyIncome(), exchange);
    }

    @Action(description = "Ask the borrower to send their bank statements again", pre = {"assetsUnknown"}, post = {"assetsKnown"}, cost = 0.25, canRerun = false)
    public ApplicationFile askForBankStatements(ApplicationFile file) {
        StoryLedger ledger = support.ledger(file.caseId());
        LoanApplications.Application app = support.loans().forCase(file.caseId());
        String question = "Could you also send two months of bank statements?";
        String answer = "Those were uploaded too. Fine, here they are again.";
        ledger.say(PERSONA, question);
        ledger.say("Borrower", answer);
        ledger.step(PERSONA, "askForBankStatements", "Asked the borrower to send their bank statements again.", support.effort("underwriter", "ask-bank-statements"), StoryLedger.Kind.ASK);
        ledger.count("borrowerReasks", 1);
        Exchange exchange = new Exchange(question, answer, "the borrower's reserves");
        reask(file, exchange);
        recordCheck(file, LoanIntakeAgent.ASSETS, exchange.unknown(), "Confirm assets from two months of bank statements",
                "Bank statements for two months re-sent by the borrower: reserves cover " + app.reservesMonths() + " months");
        return file.withReserves(app.reservesMonths(), exchange);
    }

    @Action(description = "Verify employment with the employer", pre = {"employmentUnknown"}, post = {"employmentKnown"}, cost = 0.30, canRerun = false)
    public ApplicationFile verifyEmployment(ApplicationFile file) {
        StoryLedger ledger = support.ledger(file.caseId());
        // The origination system verified employment in every lane: doing it again is rework, whoever knows it.
        ledger.say(PERSONA, "Calling the employer's HR team to verify employment.");
        ledger.say("Employer HR", "We confirmed this for the lender's origination team already. Confirmed again.");
        ledger.step(PERSONA, "verifyEmployment", "Verified employment with the employer: the origination system's verification was not in the context it had.",
                support.effort("underwriter", "verify-employment"), StoryLedger.Kind.REWORK);
        // The simulated employer answers for the borrower's actual employment.
        LoanApplications.Application app = support.loans().forCase(file.caseId());
        recordCheck(file, LoanIntakeAgent.EMPLOYMENT, "the employment verification", "Verify employment with the employer (VOE)",
                "Employment confirmed by the employer's HR team, a second time; " + LoanIntakeAgent.EMPLOYMENT_TYPE + app.employment());
        return file.withEmployment(app.employment());
    }

    @Action(description = "Ask the borrower to consent to another credit pull, and pull the report", pre = {"creditUnknown"}, post = {"creditKnown"}, cost = 0.35, canRerun = false)
    public ApplicationFile pullCreditAgain(ApplicationFile file) {
        StoryLedger ledger = support.ledger(file.caseId());
        LoanApplications.Application app = support.loans().forCase(file.caseId());
        String question = "To finish underwriting I need to pull your credit report. Do you consent? It is a hard inquiry.";
        String answer = "You already pulled it last week. Another hard inquiry? ...Fine, I consent.";
        ledger.say(PERSONA, question);
        ledger.say("Borrower", answer);
        ledger.step(PERSONA, "pullCreditAgain", "Asked the borrower to consent to a second hard inquiry, and pulled the credit report again.",
                support.effort("underwriter", "pull-credit-again"), StoryLedger.Kind.ASK);
        ledger.count("borrowerReasks", 1);
        ledger.count("repeatedSteps", 1);
        ledger.count("hardInquiries", 1);
        Exchange exchange = new Exchange(question, answer, "the credit report");
        reask(file, exchange);
        recordCheck(file, LoanIntakeAgent.CREDIT, exchange.unknown(), "Pull the credit report, with the borrower's fresh consent",
                "Credit report pulled again with the borrower's fresh consent (a second hard inquiry): score " + app.creditScore()
                        + ", monthly obligations " + String.format("%,d", app.monthlyDebts()) + " USD");
        return file.withCredit(app.creditScore(), app.monthlyDebts(), exchange);
    }

    /** A re-ask, written to the application's conversation as it happens: what the borrower said last, then the question. */
    private void reask(ApplicationFile file, Exchange e) {
        support.recordEvent(support.store(file.store()), file.caseId(), AGENT_ID, file.applicationId(), REASKED,
                "not in the context the underwriter had: " + e.unknown(), file.lastSaid(), e.question());
    }

    /** A check the underwriter had to run itself, recorded in the application's conversation with why. */
    private void recordCheck(ApplicationFile file, String event, String unknown, String input, String output) {
        support.recordEvent(support.store(file.store()), file.caseId(), AGENT_ID, file.applicationId(), event,
                "not in the context the underwriter had: " + unknown, input, output);
    }

    @Action(description = "Compute debt-to-income from the file's figures",
            pre = {"incomeKnown", "assetsKnown", "employmentKnown", "creditKnown"}, post = {"affordabilityAssessed"}, cost = 0.10, canRerun = false)
    public Affordability assessAffordability(ApplicationFile file) {
        ContextStore store = support.store(file.store());
        // The borrower's last answer gets its reply; each re-ask was recorded as it happened.
        if (!file.asked().isEmpty()) support.record(store, file.caseId(), AGENT_ID, file.applicationId(), file.lastSaid(), "Thank you. I have what I need to make the credit call.");
        int obligations = file.monthlyDebts() + file.instalment();
        double dti = obligations * 100.0 / file.monthlyIncome();
        String working = "DTI " + String.format("%.1f", dti) + "% = (" + String.format("%,d", file.monthlyDebts()) + " obligations + " + String.format("%,d", file.instalment())
                + " instalment) / " + String.format("%,d", file.monthlyIncome()) + " income; score " + file.creditScore() + "; reserves " + file.reservesMonths() + " months";
        support.recordEvent(store, file.caseId(), AGENT_ID, file.applicationId(), AFFORDABILITY, null,
                "Compute debt-to-income for " + file.applicationId(), working);
        support.ledger(file.caseId()).step(PERSONA, "assessAffordability", working + ".", support.effort("underwriter", "assess-affordability"), StoryLedger.Kind.WORK);
        return new Affordability(file.caseId(), dti, file.creditScore(), file.reservesMonths(), working);
    }

    @Action(description = "Retrieve the underwriting policy for this application", pre = {"affordabilityAssessed"},
            post = {"policyKnown", "policyUnavailable"}, cost = 0.02, canRerun = false)
    public PolicyLookup retrievePolicy(ApplicationFile file, @Provided Ai ai) {
        StoryLedger ledger = support.ledger(file.caseId());
        List<StoreRecord> policies = support.store(file.store())
                .searchKnowledge(file.caseId(), AGENT_ID, "unsecured personal loan underwriting policy salaried borrowers pay stubs credit score DTI")
                .stream().filter(r -> StorySupport.extract(List.of(r), StorySupport.POLICY_ID).filter(id -> id.startsWith("LEND-")).isPresent()).toList();
        // The situation as the file establishes it: the loan's terms from the referral, the employment type as verified.
        String situation = "Unsecured personal loan of " + String.format("%,d", file.amountUsd()) + " USD; the borrower is "
                + file.employment() + " (from the employment verification). " + file.referral();
        PolicyChoice choice = policies.isEmpty() ? new PolicyChoice(null, "no underwriting policies retrieved")
                : decisions.decide(file.caseId(), PERSONA, "decisions/lending-policy", Map.of(),
                "Application: " + situation + "\n" + StorySupport.asContext("Policies", policies), PolicyChoice.class,
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

    @Action(description = "Escalate to credit risk to confirm which policy applies and its thresholds", pre = {"policyUnavailable"},
            post = {"policyKnown"}, cost = 0.40, canRerun = false)
    public PolicyLookup escalateForPolicy(ApplicationFile file, PolicyLookup lookup) {
        StoryLedger ledger = support.ledger(file.caseId());
        ledger.say(PERSONA, "I need credit risk to confirm the policy and thresholds for this application.");
        ledger.step(PERSONA, "escalateForPolicy", "Waited on credit risk to confirm the applicable policy and its thresholds.",
                support.effort("underwriter", "escalate-for-policy"), StoryLedger.Kind.ASK);
        // The simulated credit-risk team owns the policy documents and sends the one that covers the application.
        Optional<KnowledgeDocuments.Document> doc = documents.all().stream().filter(d -> covers(d.markdown(), file)).findFirst();
        List<StoreRecord> said = doc.map(d -> d.chunks().stream().map(c -> new StoreRecord(d.key(), c, CREDIT_RISK, "", d.filename())).toList()).orElse(List.of());
        String policy = doc.map(KnowledgeDocuments.Document::key).orElse(null);
        ledger.say("Credit risk", policy == null ? "There is no written policy for this; refer it to a human underwriter." : "Attached: " + policy + ".");
        PolicyLookup confirmed = new PolicyLookup(file.caseId(), policy, said, CREDIT_RISK, policy == null ? "no written policy covers this application" : SENT_ON_REQUEST);
        support.recordEvent(support.store(file.store()), file.caseId(), AGENT_ID, file.applicationId(), POLICY, null,
                "Apply the underwriting policy for " + file.applicationId(), (policy == null ? "none" : policy) + ": " + confirmed.reason());
        return confirmed;
    }

    @AchievesGoal(description = "The credit call is made")
    @Action(description = "Make the credit call against the policy's thresholds", pre = {"policyKnown"}, post = {"creditCallMade"},
            cost = 0.05, canRerun = false)
    public CreditCall makeCreditCall(ApplicationFile file, Affordability affordability, PolicyLookup lookup, @Provided Ai ai) {
        StoryLedger ledger = support.ledger(file.caseId());
        ContextStore store = support.store(file.store());
        String policyText = String.join("\n\n", lookup.text().stream().map(StoreRecord::text).toList());
        Optional<LendingThresholds> thresholds = LendingThresholds.parse(policyText);
        String context = "Figures in the application file: " + affordability.working() + ".\n" + StorySupport.asContext("Policy " + lookup.policy(), lookup.text());
        CallJudgement judgement = decisions.decide(file.caseId(), PERSONA, "decisions/lending-credit-call", Map.of(), context, CallJudgement.class,
                () -> thresholds.map(t -> new CallJudgement(t.apply(affordability.creditScore(), affordability.dtiPercent(), affordability.reservesMonths()),
                                lookup.policy() + ": " + t.describe(),
                                t.explain(affordability.creditScore(), affordability.dtiPercent(), affordability.reservesMonths())))
                        .orElse(new CallJudgement("refer", "none: the policy text states no thresholds the underwriter could apply",
                                "no thresholds to apply; refer to a human underwriter")), ai);
        String decision = lookup.policy() == null ? "refer" : normalize(judgement.decision());
        boolean sent = CREDIT_RISK.equals(lookup.writtenBy());
        String outcome = file.applicationId() + ": " + decision + " under " + (lookup.policy() == null ? "no written policy" : lookup.policy())
                + (sent && lookup.policy() != null ? " (" + SENT_ON_REQUEST + ")" : "") + ". " + judgement.reason() + ".";
        support.traceDecisions(store, file.caseId(), AGENT_ID, file.applicationId(), PERSONA);
        // The record states the thresholds applied and the factors the call rested on, as the policy requires.
        support.recordEvent(store, file.caseId(), AGENT_ID, file.applicationId(), CREDIT_CALL, null,
                "Make the credit call on " + file.applicationId(), outcome + " " + THRESHOLDS_APPLIED + judgement.thresholdsApplied()
                        + ". " + FACTORS + affordability.factors() + ".");
        store.rememberLater(file.caseId(), AGENT_ID, "Loan application " + file.applicationId() + " underwritten: " + outcome);
        ledger.step(PERSONA, "makeCreditCall", outcome, support.effort("underwriter", "make-credit-call"), StoryLedger.Kind.WORK);
        ledger.outcome(outcome);
        List<String> asked = ledger.steps().stream()
                .filter(st -> st.kind() == StoryLedger.Kind.ASK && st.persona().equals(PERSONA) && !st.action().equals("escalateForPolicy"))
                .map(StoryLedger.Step::text).toList();
        List<String> facts = new ArrayList<>(List.of(
                file.referral(),
                "Figures: " + affordability.working(),
                "Policy: " + lookup.policy() + " (" + lookup.reason() + ")",
                "Credit call: " + decision + ". " + judgement.reason()));
        facts.add(asked.isEmpty() ? "The borrower was not asked to do anything again after the referral"
                : "After the referral the borrower had to be asked " + asked.size() + " times: " + String.join(" ", asked));
        List<String> gaps = lookup.policy() == null ? List.of("No written underwriting policy covers this application") : List.of();
        int reasked = ledger.counters().getOrDefault("borrowerReasks", 0);
        ledger.handToWriter(new ResponseWriterAgent.WritingTask(file.caseId(), PERSONA, "message to the borrower",
                support.render("writer/borrower-credit-call", Map.of("reasked", reasked, "decision", decision)), facts, gaps,
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
        return limit.isEmpty() || file.amountUsd() == null || file.amountUsd() <= limit.get();
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

    private static Integer amount(String text, String regex) {
        return StorySupport.extract(rows(text), regex).map(s -> Integer.valueOf(s.replace(",", ""))).orElse(null);
    }

    private static Integer positive(Integer value) {
        return value == null || value <= 0 ? null : value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() || value.equalsIgnoreCase("null") || value.equalsIgnoreCase("unknown") ? null : value;
    }

    // ------------------------------------------------------------------ conditions

    @Condition(name = "fileOpen")
    public boolean fileOpen(ApplicationFile file) {
        return file != null;
    }

    @Condition(name = "incomeKnown")
    public boolean incomeKnown(ApplicationFile file) {
        return file != null && file.monthlyIncome() != null;
    }

    @Condition(name = "incomeUnknown")
    public boolean incomeUnknown(ApplicationFile file) {
        return file != null && file.monthlyIncome() == null;
    }

    @Condition(name = "assetsKnown")
    public boolean assetsKnown(ApplicationFile file) {
        return file != null && file.reservesMonths() != null;
    }

    @Condition(name = "assetsUnknown")
    public boolean assetsUnknown(ApplicationFile file) {
        return file != null && file.reservesMonths() == null;
    }

    @Condition(name = "employmentKnown")
    public boolean employmentKnown(ApplicationFile file) {
        return file != null && file.employmentConfirmed() != null && file.employment() != null;
    }

    @Condition(name = "employmentUnknown")
    public boolean employmentUnknown(ApplicationFile file) {
        return file != null && (file.employmentConfirmed() == null || file.employment() == null);
    }

    @Condition(name = "creditKnown")
    public boolean creditKnown(ApplicationFile file) {
        return file != null && file.creditScore() != null && file.monthlyDebts() != null;
    }

    @Condition(name = "creditUnknown")
    public boolean creditUnknown(ApplicationFile file) {
        return file != null && (file.creditScore() == null || file.monthlyDebts() == null);
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
