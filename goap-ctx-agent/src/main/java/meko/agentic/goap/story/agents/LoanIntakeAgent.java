package meko.agentic.goap.story.agents;

import com.embabel.agent.api.annotation.AchievesGoal;
import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.Agent;
import com.embabel.agent.api.annotation.Condition;
import com.embabel.agent.api.common.PlannerType;
import meko.agentic.goap.story.LoanApplications;
import meko.agentic.goap.story.StoryLedger;
import meko.agentic.goap.story.store.ContextStore;

import java.util.List;

/**
 * Loan story, phase 1: a vendor's loan-origination system (a separate product with its own store) takes the borrower's
 * application, collects the documents and runs the checks, then refers the application to your underwriter with the
 * whole application file: income, assets, employment history and credit. What the file does not carry is what the
 * borrower explained along the way: where a large deposit came from, why there is a gap in their employment, and the
 * documents they sent to prove it. That lives in the application's conversation, and only a context store both sides
 * can reach carries it across.
 */
@Agent(planner = PlannerType.GOAP, description = "Vendor loan-origination system that takes a loan application, runs its checks and refers it for underwriting")
public class LoanIntakeAgent {
    public static final String AGENT_ID = "vendor:loan-origination";
    static final String PERSONA = "Loan-origination system";

    public record LoanApplicationStart(String caseId, ContextStore.Kind store) {}

    /** What the checks found, each as the origination system recorded it. */
    public record IntakeChecks(String caseId, ContextStore.Kind store, String applicationId, String terms, List<String> file) {}

    /**
     * What crosses to underwriting: the application id, the terms, and the application file (the documents' figures and
     * the checks' results). Not the borrower's explanations.
     *
     * @param precedents which credit-risk rulings count as precedent: rulings tagged with this set (see
     *                   {@code UnderwriterAgent#precedentTag}); set by your side, not by the vendor's system
     */
    public record Referral(String caseId, ContextStore.Kind store, String applicationId, String note, List<String> file, String precedents) {
        public Referral(String caseId, ContextStore.Kind store, String applicationId, String note, List<String> file) {
            this(caseId, store, applicationId, note, file, null);
        }
    }

    /** Event names of the origination system's records in the application's conversation. */
    public static final String INCOME = "income confirmed";
    public static final String EMPLOYMENT = "employment confirmed";
    public static final String ASSETS = "assets confirmed";
    public static final String CREDIT = "credit pulled";
    public static final String EXPLAINED = "explanation received";
    public static final String REFERRAL = "referral to underwriting";
    /** How the employment record states the borrower's employment type, which decides the policy that governs. */
    public static final String EMPLOYMENT_TYPE = "employment type: ";
    /** How the file states the items an underwriter may need explained: the largest deposit and the longest gap. */
    public static final String LARGEST_DEPOSIT = "largest single deposit ";
    public static final String LONGEST_GAP = "longest employment gap ";

    private final StorySupport support;

    public LoanIntakeAgent(StorySupport support) {
        this.support = support;
    }

    /** The application chat: the borrower's documents, and their explanations of the deposit and the gap. */
    static List<String[]> conversation(LoanApplications.Application app) {
        return List.of(
                new String[]{"I'd like a " + app.amount() + " personal loan for " + app.purpose() + ".",
                        "Happy to help. I've opened application " + app.applicationId() + ". Please upload your two most recent pay stubs and two months of bank statements."},
                new String[]{"Uploaded the pay stubs and both statements.",
                        "Thanks. I see a deposit of " + String.format("%,d", app.deposit().amountUsd()) + " USD on " + app.deposit().date() + ". Where did it come from?"},
                new String[]{"That's from " + app.deposit().source() + ". Here's " + app.deposit().evidence() + ".",
                        "Got it. Your employment history shows a gap from " + app.gap().period() + ". Can you tell me about it?"},
                new String[]{"That was " + app.gap().reason() + ". I've attached " + app.gap().evidence() + ".",
                        "Thank you. May we contact your employer to confirm your employment, and pull your credit report? The credit pull is a hard inquiry."},
                new String[]{"Yes to both.",
                        "Thank you. Your application is complete and goes to underwriting now."});
    }

    @Action(description = "Take the application, collect the documents and run the checks", post = {"checksDone"}, cost = 0.10, canRerun = false)
    public IntakeChecks takeApplication(LoanApplicationStart start) {
        StoryLedger ledger = support.ledger(start.caseId());
        ContextStore store = support.store(start.store());
        // The simulated borrower, employer and credit bureau: the system learns each figure from the document or check.
        LoanApplications.Application app = support.loans().forCase(start.caseId());
        for (String[] turn : conversation(app)) {
            ledger.say("Borrower", turn[0]);
            ledger.say(PERSONA, turn[1]);
            support.record(store, start.caseId(), AGENT_ID, app.applicationId(), turn[0], turn[1]);
        }
        List<String[]> checks = List.of(
                new String[]{INCOME, "Confirm monthly income from the two pay stubs", "Income " + String.format("%,d", app.monthlyIncome()) + " USD a month, confirmed from two pay stubs"},
                new String[]{ASSETS, "Confirm assets from two months of bank statements", "Bank statements for two months: reserves cover " + app.reservesMonths()
                        + " months; " + LARGEST_DEPOSIT + String.format("%,d", app.deposit().amountUsd()) + " USD on " + app.deposit().date()},
                new String[]{EMPLOYMENT, "Verify employment with the employer (VOE)", "Employment confirmed by the employer's HR team; "
                        + EMPLOYMENT_TYPE + app.employment() + ", income paid by the employer; " + LONGEST_GAP + app.gap().days() + " days (" + app.gap().period() + ")"},
                new String[]{CREDIT, "Pull the credit report, with the borrower's consent", "Credit report pulled with the borrower's consent (one hard inquiry): score "
                        + app.creditScore() + ", monthly obligations " + String.format("%,d", app.monthlyDebts()) + " USD"});
        for (String[] check : checks) support.recordEvent(store, start.caseId(), AGENT_ID, app.applicationId(), check[0], null, check[1], check[2]);
        // What the borrower explained, with the evidence: recorded as the origination system received it.
        support.recordEvent(store, start.caseId(), AGENT_ID, app.applicationId(), EXPLAINED, null,
                "Source the " + String.format("%,d", app.deposit().amountUsd()) + " USD deposit on " + app.deposit().date(),
                "Deposit: " + app.deposit().source() + " (" + app.deposit().kind() + "); evidence received: " + app.deposit().evidence());
        support.recordEvent(store, start.caseId(), AGENT_ID, app.applicationId(), EXPLAINED, null,
                "Explain the employment gap (" + app.gap().period() + ")",
                "Employment gap: " + app.gap().reason() + "; evidence received: " + app.gap().evidence());
        ledger.count("hardInquiries", 1);
        ledger.step(PERSONA, "takeApplication", "Collected pay stubs and bank statements, verified employment, pulled the credit report, and took the borrower's explanations of the deposit and the employment gap.",
                support.effort("loan-intake", "take-application"), StoryLedger.Kind.WORK);
        String terms = app.amount() + " unsecured personal loan for " + app.purpose() + ", " + app.termMonths() + " months at "
                + app.aprPercent() + "% APR, instalment " + String.format("%,d", app.instalment()) + " USD a month";
        return new IntakeChecks(start.caseId(), start.store(), app.applicationId(), terms, checks.stream().map(c -> c[2]).toList());
    }

    @AchievesGoal(description = "The application is referred for underwriting")
    @Action(description = "Refer the application to underwriting with the application file",
            pre = {"checksDone"}, post = {"referred"}, cost = 0.05, canRerun = false)
    public Referral refer(IntakeChecks checks) {
        StoryLedger ledger = support.ledger(checks.caseId());
        ContextStore store = support.store(checks.store());
        LoanApplications.Application app = support.loans().forCase(checks.caseId());
        // The borrower's explanations go to the context store, for whoever picks the application up next.
        String explanations = "Loan application " + checks.applicationId() + " borrower explanations, for the "
                + String.format("%,d", app.deposit().amountUsd()) + " USD deposit on " + app.deposit().date() + " and the employment gap "
                + app.gap().period() + ". Deposit: " + app.deposit().source() + " (" + app.deposit().kind() + "); evidence received: "
                + app.deposit().evidence() + ". Employment gap: " + app.gap().reason() + "; evidence received: " + app.gap().evidence() + ".";
        boolean saved = store.remember(checks.caseId(), AGENT_ID, explanations).isPresent();
        String note = "Application " + checks.applicationId() + ": " + checks.terms() + ". Application file attached. Referred for underwriting.";
        support.recordEvent(store, checks.caseId(), AGENT_ID, checks.applicationId(), REFERRAL, null,
                "Refer application " + checks.applicationId() + " to underwriting", note + " File: " + String.join(". ", checks.file()) + ".");
        ledger.step(PERSONA, "refer",
                support.writeOutcome(checks.caseId(), AGENT_ID, checks.store(), saved, "the borrower's explanations") + "; referred the application with its file.",
                support.effort("loan-intake", "refer"), StoryLedger.Kind.CONTEXT);
        return new Referral(checks.caseId(), checks.store(), checks.applicationId(), note, checks.file());
    }

    @Condition(name = "checksDone")
    public boolean checksDone(IntakeChecks checks) {
        return checks != null;
    }

    @Condition(name = "referred")
    public boolean referred(Referral referral) {
        return referral != null;
    }
}
