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
 * application, collects the documents and runs the checks: pay stubs, bank statements, employment verification and a
 * credit pull. Then it refers the application to your underwriter. What crosses is only the referral: the application
 * id and the loan terms. Everything else the underwriter knows has to come from a context store both sides can reach.
 */
@Agent(planner = PlannerType.GOAP, description = "Vendor loan-origination system that takes a loan application, runs its checks and refers it for underwriting")
public class LoanIntakeAgent {
    public static final String AGENT_ID = "vendor:loan-origination";
    static final String PERSONA = "Loan-origination system";

    public record LoanApplicationStart(String caseId, ContextStore.Kind store) {}

    /** What the checks found, each as the origination system recorded it. */
    /** @param terms the loan's terms as the referral states them: amount, purpose, term, APR, instalment */
    public record IntakeChecks(String caseId, ContextStore.Kind store, String applicationId, String terms, List<String> results) {}

    /** How the employment record states the borrower's employment type, which decides the policy that governs. */
    public static final String EMPLOYMENT_TYPE = "employment type: ";

    public record Referral(String caseId, ContextStore.Kind store, String applicationId, String note) {}

    /** Event names of the verification records in the application's conversation, written by whoever ran the check. */
    public static final String INCOME = "income confirmed";
    public static final String EMPLOYMENT = "employment confirmed";
    public static final String ASSETS = "assets confirmed";
    public static final String CREDIT = "credit pulled";
    /** The origination system's referral record. */
    public static final String REFERRAL = "referral to underwriting";

    private final StorySupport support;

    public LoanIntakeAgent(StorySupport support) {
        this.support = support;
    }

    /** The borrower's side of the application chat. The facts in it are what the underwriter will need. */
    static List<String[]> conversation(LoanApplications.Application app) {
        return List.of(
                new String[]{"I'd like a " + app.amount() + " personal loan to consolidate two credit cards.",
                        "Happy to help. I've opened application " + app.applicationId() + ". Please upload your two most recent pay stubs."},
                new String[]{"Uploaded both pay stubs.",
                        "Thanks. Now two months of bank statements, please."},
                new String[]{"Done, both statements are uploaded.",
                        "Got them. May we contact your employer to confirm your employment, and pull your credit report? The credit pull is a hard inquiry."},
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
                new String[]{ASSETS, "Confirm assets from two months of bank statements", "Bank statements for two months received: reserves cover " + app.reservesMonths() + " months"},
                new String[]{EMPLOYMENT, "Verify employment with the employer (VOE)", "Employment confirmed by the employer's HR team; "
                        + EMPLOYMENT_TYPE + app.employment() + ", income paid by the employer"},
                new String[]{CREDIT, "Pull the credit report, with the borrower's consent", "Credit report pulled with the borrower's consent (one hard inquiry): score "
                        + app.creditScore() + ", monthly obligations " + String.format("%,d", app.monthlyDebts()) + " USD"});
        for (String[] check : checks) support.recordEvent(store, start.caseId(), AGENT_ID, app.applicationId(), check[0], null, check[1], check[2]);
        ledger.count("hardInquiries", 1);
        ledger.step(PERSONA, "takeApplication", "Collected pay stubs and bank statements, verified employment and pulled the credit report.",
                support.effort("loan-intake", "take-application"), StoryLedger.Kind.WORK);
        String terms = app.amount() + " unsecured personal loan for " + app.purpose() + ", " + app.termMonths() + " months at "
                + app.aprPercent() + "% APR, instalment " + String.format("%,d", app.instalment()) + " USD a month";
        return new IntakeChecks(start.caseId(), start.store(), app.applicationId(), terms, checks.stream().map(c -> c[2]).toList());
    }

    @AchievesGoal(description = "The application is referred for underwriting")
    @Action(description = "Record the checks' results and refer the application to underwriting",
            pre = {"checksDone"}, post = {"referred"}, cost = 0.05, canRerun = false)
    public Referral refer(IntakeChecks checks) {
        StoryLedger ledger = support.ledger(checks.caseId());
        ContextStore store = support.store(checks.store());
        String terms = checks.terms();
        String facts = "Loan application " + checks.applicationId() + " intake facts (" + terms + "): " + String.join(". ", checks.results()) + ".";
        boolean saved = store.remember(checks.caseId(), AGENT_ID, facts).isPresent();
        support.recordEvent(store, checks.caseId(), AGENT_ID, checks.applicationId(), REFERRAL, null,
                "Refer application " + checks.applicationId() + " to underwriting", facts);
        ledger.step(PERSONA, "refer",
                support.writeOutcome(checks.caseId(), AGENT_ID, checks.store(), saved, "the checks' results") + "; referred the application.",
                support.effort("loan-intake", "refer"), StoryLedger.Kind.CONTEXT);
        return new Referral(checks.caseId(), checks.store(), checks.applicationId(),
                "Application " + checks.applicationId() + ": " + terms + ". Referred for underwriting.");
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
